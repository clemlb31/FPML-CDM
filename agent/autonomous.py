"""Autonomous LLM-driven agent — no LangGraph, no state machine, no planning node.

Single prompt + tools + loop. The model decides everything: when to read FpML,
when to write Java, when to compile, when to delegate to subagents, when to stop.

Architecture:
  • protocol.py        — parse XML / native tool_use into a unified LLMResponse
  • tools_registry.py  — single source of truth for tool descriptions + schemas
  • tool_wrappers.py   — local safety nets around MCP tools (edit_file uniqueness…)
  • context.py         — token estimation + auto/manual compaction
  • llm_call/          — pluggable LLM backends (config-driven; base class + per-API
                         subclasses) behind get_backend() / llm_call(); see configs/agent.yaml
  • helpers.py         — MCP server config (get_servers) + tool-result unwrap

Usage:
    python -m agent.autonomous \\
        --fpml      data/test/rates-5-10/fpml/ird-ex08-fra.xml \\
        --expected  data/test/rates-5-10/cdm/ird-ex08-fra.json \\
        --out       workspaces/test-fra \\
        [--max-iter 30] [--no-subagents] [--no-native-tools]
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import os
import sys
import textwrap
from pathlib import Path

from dotenv import load_dotenv
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
from langchain_mcp_adapters.client import MultiServerMCPClient

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

# Load .env early so the backend secrets/overrides (QWOPUS_* / *_API_KEY) and
# TAVILY_MCP are available to every code path (llm_call backends, get_servers).
load_dotenv(ROOT / ".env")

from agent.context import (
    COMPACT_TOOL_NAME,
    compact_messages,
    estimate_tokens,
    maybe_compact,
)
from agent.llm_call import get_backend, llm_call
from agent.helpers import get_servers, unwrap as _unwrap
from agent.protocol import (
    LLMResponse,
    ToolCall,
    format_xml_results,
)
from agent.tool_wrappers import apply_wrapper, has_wrapper
from agent.tools_registry import EXPOSED_TOOLS, render_tools_doc, schema_registry
from agent.run_logger import RunLogger

# Per-run observability sink, set once by main(). Shared by the main loop and any
# sub-agent loops (they run in the same process / same project_dir). None only
# when _run_loop is driven directly from a test — handlers fall back to print.
_RUN_LOGGER: RunLogger | None = None

# Resolved task paths, set by main(). The model tends to HALLUCINATE the fpml/expected
# paths it passes to run_test (observed: invented src/test/resources/FRA.xml → "file not
# found", wasting 3 of 4 tests). The harness knows the real paths, so execute_tool forces
# them on every run_test call — the model can't mis-path the test.
_TASK_FPML: str | None = None
_TASK_EXPECTED: str | None = None

# Paths already read this run. Small models tend to re-read the same large input
# files every turn, which balloons the context with duplicate content until they
# choke. We return a short stub on re-reads instead of re-dumping the file.
_READ_PATHS: set[str] = set()

# path → md5(content) of files already written this run. Small models loop on
# rewriting the SAME file (e.g. pom.xml) instead of moving on, because they don't
# track completed work. An identical rewrite is skipped with a "move on" directive
# that materialises the progress state (the web-recommended debounce + clear-state
# pattern). A genuinely different write (real edit) has a new hash → allowed.
_WRITTEN: dict[str, str] = {}

def _emit(msg: str) -> None:
    (_RUN_LOGGER.log if _RUN_LOGGER else print)(msg)


def _extract_reasoning(resp) -> str:
    """Pull the model's chain-of-thought (separate `reasoning` field on the raw
    OpenAI message, e.g. Ollama/qwopus). Empty if absent. For the trace log."""
    try:
        return getattr(resp.raw.choices[0].message, "reasoning", "") or ""
    except Exception:
        return ""


# ── System prompt ─────────────────────────────────────────────────────────────

# The knowledge base section. PROSE documentation only — by design there is NO
# pre-written transformer/snippet to copy; exact builder/enum signatures come from the
# `cdm_lookup` tool (live javap on the jar). Can be ABLATED (--no-kb) to measure how
# load-bearing the docs are. Ends with a blank line so the assembled prompt reads cleanly
# whether present or empty.
KB_SECTION = """\
# KNOWLEDGE BASE (read the index first)
A prose knowledge base of the CDM 6.19 model, the FpML source format, and the field
mappings lives under a single directory. Its router/index is at:
  {kb_index}
read_file that README ONCE — it routes you to the precise file for any need (object
model, the meta/reference model, dates, enum mangling, the FpML structure per product
family, the FpML→CDM field map). Then open ONLY the precise file you need and `grep` it
by symbol; do not read whole files. Two anchors you will use constantly:
  • cdm/structure-skeleton.json — a full CDM TradeState with every value null; grep it
    to find the EXACT JSON path / attribute name of any field.
  • `cdm_lookup name=<Type>` — the REAL builder set*/add* signatures and enum constants
    from the jar. The KB is documentation; it never gives copyable code. NEVER guess a
    method/enum name — cdm_lookup it (or grep cdm/rosetta/). Verify, don't guess.

"""

SYSTEM_PROMPT_TEMPLATE = """\
You are an autonomous software-engineering agent. Your task is to convert an
FpML 5.x XML document into the corresponding CDM 6.x JSON by writing, compiling
and running a Java program. You decide every step.

# Task contract
- Input  : an FpML XML file (path given in the user message).
- Target : the JSON file at the `expected` path. The program, when packaged and run
           on the FpML file, must produce JSON that matches the target under
           `SemanticDiff` rules (globalKey ignored, numeric BigDecimal-compare, etc.).

# The build is ALREADY SET UP — you write ONE file
The `project_dir` already contains the proven boilerplate (do NOT write or modify these):
  - pom.xml                                          (deps: cdm-java 6.19.0, jackson, guice)
  - src/main/java/com/example/FpmlToCdmApp.java      (the main: parse XML → transform → serialize → diff)
  - src/main/java/com/example/SemanticDiff.java      (the JSON comparator)
You write exactly ONE file — the MAPPING:
  - src/main/java/com/example/IrsTransformer.java
It MUST be `package com.example;` and expose, exactly as the provided main calls it:
      public cdm.event.common.TradeState transform(org.w3c.dom.Document doc)
read_file the provided FpmlToCdmApp.java ONCE to confirm that signature, then spend ALL
your effort on the transformer. Do not touch pom.xml / FpmlToCdmApp.java / SemanticDiff.java.

{kb_section}# Filesystem access (IMPORTANT)
- WRITE only under the `project_dir` (inside workspaces/). `knowledge_base/` and
  `data/` are READ-ONLY references — never write there.
- Your context window is SMALL. read_file a file at most ONCE. If a file is large
  (the expected JSON often is), use `grep` to pull only the elements you need rather
  than holding the whole file in context.

# Tools available
{tools_doc}

{tool_call_protocol}

# Role: you are the ORCHESTRATOR
You do NOT write the transformer yourself. You RESEARCH, PLAN, then DELEGATE the
coding to sub-agents and drive the compile/test loop. Think like a tech lead:
the plan is your deliverable; sub-agents are your workers.

# Operating procedure — work in PHASES, in order. Do not skip ahead.

## Phase 1 — RESEARCH (gather facts; write NO code yet)
- read_file the FpML once, and the expected CDM JSON once (grep them if large).
- read_file the knowledge-base index (path above), then open the PRECISE files it routes
  you to: the FpML structure for this product family, the CDM object model, the meta/
  reference model, dates, enum mangling, and the FpML→CDM field map. grep, don't read whole.
- grep `cdm/structure-skeleton.json` for the exact CDM JSON path of each field, and use
  `cdm_lookup name=<Type>` for the real builder signatures. Note the CDM types each part needs.
- ONLY if the KB + cdm_lookup don't cover a symbol or product: use `internet_search` for
  CDM/FpML/rosetta docs. Web research is a fallback — the KB and cdm_lookup come first.

## Phase 2 — PLAN (write `{project_dir}/plan.md`; still NO Java)
Write ONE detailed plan.md for the transformer ONLY (the boilerplate is already provided).
It is your contract. It MUST contain:
- The EXACT list of build methods (e.g. buildTrade, buildPayout, buildCalculationPeriodDates,
  buildParty…). For EACH method, on its own line:
    `methodName(sig) → reads <FpML elements> → builds <CDM path> → cdm_lookup: <types/enums>`
  plus any edge cases (null handling, enum mangling, fixed vs floating leg).
- Mark which methods are INDEPENDENT (can be written in parallel by separate sub-agents).
- The build order. Keep plan.md updated if reality diverges.

## Phase 3 — SCAFFOLD the transformer skeleton, then COMPILE
- The boilerplate (pom.xml, FpmlToCdmApp.java, SemanticDiff.java) is ALREADY in project_dir.
  Do NOT write them. Write ONLY src/main/java/com/example/IrsTransformer.java.
- write_file the transformer SKELETON: `package com.example;`, the class `IrsTransformer`,
  the entry method `public cdm.event.common.TradeState transform(org.w3c.dom.Document doc)`,
  and every build method from plan.md as a stub (real signature + `// TODO` returning null).
- compile_project IMMEDIATELY — the provided main references your class, so a compiling
  skeleton proves the wiring before you fill bodies. Compile early and often from here on.

## Phase 4 — IMPLEMENT (delegate method bodies to sub-agents)
- For each INDEPENDENT method in plan.md, `spawn_subagent` to write THAT method body.
  Give the sub-agent everything it needs and nothing else:
    • the method name + signature and the transformer file path
    • the absolute FpML path and the exact elements to read
    • the target CDM path (from structure-skeleton.json) and the types to cdm_lookup
    • "edit_file only this method's body; do not touch other methods".
  Spawn independent methods in PARALLEL (several spawn_subagent calls in one turn).
- You assemble: once sub-agents return, the transformer should be complete.

## Phase 5 — COMPILE & TEST LOOP
- compile_project. For EACH error: read the exact file:line, `cdm_lookup` the type (or grep
  cdm/rosetta + structure-skeleton) to find the real name, fix with edit_file. NEVER rewrite
  the whole file to fix one error. Delegate a cluster of errors in one method to a sub-agent.
- Test EARLY, even with stubs: run_test reports unfilled sections as "missing" — that diff IS
  your fill checklist. Don't wait for a perfect transformer; compile → run_test → fill what it
  says is missing → repeat. (The harness fills run_test's project/FpML/expected paths for you —
  just call run_test; never invent file paths.)
- A `// TODO` method returning null shows up as a "missing" section in the diff — fill those.
- match=true → emit <done>.

# Sub-agents — your workers
- `spawn_subagent` runs a focused task in its OWN fresh context: the files it reads do
  NOT consume yours; it returns only a one-line <done> summary. This is how you fill a
  big transformer without overflowing context — one method per worker, in parallel.

# Hard rules (avoid burning iterations)
- Phases are ordered: no write_file before plan.md exists; no compile before the
  skeleton is written. Research first, plan second, code third.
- Read each input file at most ONCE; grep for any re-lookup. Never re-read a whole file.
- Prefer delegating method bodies to sub-agents over writing them yourself.
- Never invent a CDM method/enum name. Verify with `cdm_lookup name=<Type>` (real jar
  signatures) or grep the KB (cdm/rosetta, structure-skeleton) first; internet_search last.

Be terse. No prose outside tool calls, plan.md and <done>.
"""

XML_TOOL_CALL_PROTOCOL = """\
# Tool-call protocol (XML mode)
- Emit one or more tool calls per turn. Each call MUST be wrapped exactly:
    <tool_call>
    {"name": "<tool_name>", "args": { ... }}
    </tool_call>
- Multiple <tool_call> blocks in a single turn run in PARALLEL.
- The host returns each result in a <tool_result name="..." idx="...">…</tool_result> block.
- When run_test reports match=true, emit:
    <done>one-sentence summary of what was built</done>
- If a tool errors, you get <tool_result … error="true">…</tool_result>; iterate.
- Do NOT emit prose outside tool_call / done — it is ignored.
"""

NATIVE_TOOL_CALL_PROTOCOL = """\
# Tool-call protocol
- Use the native function-calling interface to call tools (no XML wrappers needed).
- You may call multiple tools per turn; they run in PARALLEL.
- When run_test reports match=true, emit a plain text message containing exactly:
    <done>one-sentence summary of what was built</done>
- Otherwise keep iterating with tool calls — no commentary.
"""


SUBAGENT_SYSTEM_PROMPT = """\
You are a focused sub-agent. Your single task:
  {task}

Tools: read_file, grep, write_file, edit_file, mkdir_p, compile_project, run_test.
You CANNOT spawn further sub-agents.

Context is small, so be lean:
- read_file only what the task needs, at most ONCE; grep for specific elements of
  large files instead of reading them whole.
- Go straight to write_file / edit_file. Do not re-read files you already have.

When done, emit <done>summary</done> — a one-sentence result the parent can use
directly (e.g. "wrote IrsTransformer.buildFixedLeg, compiles").
"""


# ── Tool execution ─────────────────────────────────────────────────────────────

# Per-tool wall-clock ceilings (seconds). The validator runs `mvn` inside Docker
# and can pull the CDM jar on first use, so it gets a generous budget; everything
# else (filesystem / grep / tavily) is fast. A timeout is reported as a normal
# tool error and never kills the loop — see execute_tool.
_DEFAULT_TOOL_TIMEOUT = 120
_TOOL_TIMEOUTS = {
    "compile_project": 900,
    "run_test": 900,
}

# Read-only "exploration" tools. Small models tend to loop on these without ever
# writing code; the loop's stall guard nudges them back to writing/compiling.
_EXPLORE_TOOLS = {
    "read_file", "read_multiple_files", "read_text_file",
    "list_directory", "directory_tree", "search_files", "grep",
}
# Consecutive no-progress turns before we inject a steering nudge.
_STALL_LIMIT = 2

# write_file/edit_file calls allowed before we force a compile check. 4 covers a normal
# scaffold burst (pom, main, SemanticDiff, transformer); beyond that without compiling
# means the model is editing blind and likely drifting.
_COMPILE_NUDGE_LIMIT = 4
_COMPILE_NUDGE = (
    "You have written/edited several files without compiling. Call compile_project NOW "
    "to verify before making more edits — editing blind drifts away from a compiling "
    "state. Fix what compile_project reports, one error at a time, then continue."
)

# Injected when the model edits right after a clean compile without measuring. Locks
# in the score and gives it the diff to make targeted fixes instead of blind rewrites.
_RUN_TEST_NUDGE = (
    "The project just compiled cleanly — do NOT keep editing blindly. Call run_test "
    "NOW to measure the score and get the exact list of remaining mismatches. Then fix "
    "them ONE at a time (re-compiling as you go). A clean compile is progress: lock it "
    "in with a test before changing more, so you never regress the build."
)

# Injected when the model claims <done> but the last run_test was NOT match=true.
# It tends to declare victory at ~90% — this holds it to the real success criterion.
_FALSE_DONE_NUDGE = (
    "You emitted <done> but run_test has NOT reported match=true yet — you are NOT "
    "finished. Do not stop or claim success. Call run_test to see the exact remaining "
    "mismatches, then fix them one at a time (MISSING = add the field, EXTRA = remove "
    "it, WRONG = correct the value). Only emit <done> after run_test says match=true."
)

def _transformer_written() -> bool:
    """True once the model has written a .java file (the transformer). Boilerplate is
    pre-staged with the marker value 'boilerplate', so a real model write has a hash."""
    return any(p.endswith(".java") and _WRITTEN[p] != "boilerplate" for p in _WRITTEN)


# Phase-aware steering nudge injected when the model stalls (reads-only, or emits text
# with no tool call). It inspects what has actually been written and pushes to the NEXT
# real action. Boilerplate (pom/main/SemanticDiff) is pre-staged, so the only file the
# model writes is the transformer: once it exists, the right move is ALWAYS compile→fix.
def _stall_nudge() -> str:
    if _transformer_written():
        return (
            "Your transformer exists — STOP researching. THIS turn call compile_project. "
            "If it reports errors, fix ONLY the first one with edit_file (often a missing "
            "import like `import org.w3c.dom.Element;`, or a wrong builder name — cdm_lookup "
            "just THAT one symbol), then compile_project again. compile → fix → compile until "
            "it builds, then run_test. Do NOT broadly cdm_lookup/grep until it compiles."
        )
    if not any(p.endswith("plan.md") for p in _WRITTEN):
        return (
            "You have read enough — stop exploring. THIS turn, call write_file to create "
            "plan.md: for the transformer, list every build method with the FpML elements "
            "it reads, the CDM path it builds, and the types to cdm_lookup. Write plan.md now."
        )
    return (
        "plan.md exists and the boilerplate is already provided — stop reading. THIS turn "
        "write_file the transformer skeleton: `package com.example;`, class IrsTransformer, "
        "`public cdm.event.common.TradeState transform(org.w3c.dom.Document doc)`, and every "
        "build method as a `// TODO` stub. Then compile_project immediately."
    )

# Compile-cadence guard. The dominant failure once a transformer exists is the model
# researching/editing for many turns WITHOUT recompiling (observed: 1 missing import from a
# clean build, then 18 turns of cdm_lookup/grep and never a compile). Unlike the drift guard
# (counts edits), this counts EVERY non-compile turn and forces a compile after a few.
_COMPILE_CADENCE_LIMIT = 3
_COMPILE_CADENCE_NUDGE = (
    "You have a transformer but have NOT compiled in several turns. STOP researching NOW. "
    "Call compile_project THIS turn. If it errors, fix ONLY the first error with edit_file "
    "(missing import / wrong builder name — cdm_lookup just that one symbol if needed), then "
    "compile_project again. Tight loop: compile → fix first error → compile. Do not cdm_lookup, "
    "grep or read anything else until the project compiles, then run_test."
)

# ── Lean tool sets (token budget is tight on a 16-32k local model) ──────────────
# Exposing fewer tools means a smaller schema in every prompt, and dropping
# list_directory/search_files/read_multiple_files removes the read-only tools the
# model wandered into (and read_multiple_files blew the context by dumping two big
# files at once). The orchestrator can delegate; sub-agents cannot recurse.
_ORCHESTRATOR_TOOLS = [
    "read_file", "grep", "write_file", "edit_file", "mkdir_p", "cdm_lookup",
    "compile_project", "run_test", "compact_context", "spawn_subagent",
    "internet_search",   # web research (orchestrator only)
]
_SUBAGENT_TOOLS = [
    "read_file", "grep", "write_file", "edit_file", "mkdir_p", "cdm_lookup",
    "compile_project", "run_test",
]

# Cap very large tool results kept in the conversation so a single big file read
# cannot blow the context window. The model is told to grep for specifics instead.
_MAX_RESULT_CHARS = 12000


# Cap the number of individual diff lines fed back per run_test, so a wildly-wrong
# early attempt can't blow the context with hundreds of mismatches. Plenty for the
# tail end of convergence where only a handful remain.
_MAX_DIFF_LINES = 40


def _compact_run_test(text: str) -> str:
    """Turn the verbose run_test JSON into a compact, prioritised fix-list.

    The raw result is ~13k chars of nested JSON — heavy and noisy for a context-tight
    model. We keep every signal that matters (score, matched/total, and each concrete
    mismatch with its expected value) but render it as terse, actionable lines and drop
    the JSON scaffolding. Falls back to the raw text if it isn't parseable JSON.
    """
    import json as _json
    s = (text or "").strip()
    try:
        obj = _json.loads(s[s.index("{"):s.rindex("}") + 1])
    except (ValueError, _json.JSONDecodeError):
        return text
    if not isinstance(obj, dict) or "score" not in obj and "match" not in obj:
        return text

    sd = obj.get("score_detail") or {}
    matched, total = sd.get("matched"), sd.get("total")
    head = f"run_test: match={obj.get('match')} score={obj.get('score')}"
    if matched is not None and total is not None:
        head += f" ({matched}/{total} fields matched)"
    crash = obj.get("crash")
    if crash:
        exc = crash.get("exception") or "Exception"
        msg = crash.get("message") or ""
        loc = ""
        if crash.get("file") and crash.get("line"):
            where = f"{crash.get('method') or ''} ({crash['file']}:{crash['line']})".strip()
            loc = f"\n  at {where}  ← fix THIS line"
        elif crash.get("method"):
            loc = f"\n  in {crash['method']}"
        detail = f": {msg}" if msg else " (no message — usually a null passed to a CDM builder)"
        return (f"{head}\nRUNTIME CRASH — {exc}{detail}{loc}\n"
                "The program compiles but threw at runtime, so it produced no output (score 0). "
                "Go to that exact method/line: something is null (a missing FpML element, or a "
                "builder field never set). Guard the null or set the field, then run_test again. "
                "Fix the crash BEFORE chasing field diffs.")
    if obj.get("match"):
        return head + "  ✅ match — emit <done>."

    lines: list[str] = []
    for wv in (sd.get("wrong_values") or []):
        lines.append(f"WRONG  {wv.get('path')}: got {wv.get('got')} want {wv.get('want')}")
    for tm in (sd.get("type_mismatches") or []):
        lines.append(f"TYPE   {tm.get('path')}: got {tm.get('got')} want {tm.get('want')}")
    # `differences` are already human-readable "MISSING …/EXTRA …" strings.
    for d in (obj.get("differences") or []):
        lines.append(str(d))

    n_total = len(lines)
    extra = ""
    if n_total > _MAX_DIFF_LINES:
        extra = f"\n…(+{n_total - _MAX_DIFF_LINES} more mismatches — fix these first, re-run)"
        lines = lines[:_MAX_DIFF_LINES]

    legend = ("\nFix each, then run_test again. MISSING = add that field; EXTRA = remove it; "
              "WRONG/TYPE = change the value. Edit only the responsible method.")
    return f"{head}\nFIX THESE ({n_total} mismatches):\n" + "\n".join(lines) + extra + legend


def _cap_result(name: str, text: str) -> str:
    if name == "run_test":
        return _compact_run_test(text)
    if name in ("read_file", "read_text_file", "read_multiple_files") and len(text) > _MAX_RESULT_CHARS:
        return (text[:_MAX_RESULT_CHARS] + f"\n\n…[truncated {len(text) - _MAX_RESULT_CHARS} "
                "chars to save context. Use grep to pull the specific elements you need "
                "from this file rather than reading it whole.]")
    return text


async def _with_tool_timeout(name: str, coro):
    timeout = _TOOL_TIMEOUTS.get(name, _DEFAULT_TOOL_TIMEOUT)
    try:
        return await asyncio.wait_for(coro, timeout=timeout)
    except asyncio.TimeoutError:
        raise TimeoutError(f"tool '{name}' exceeded {timeout}s timeout")


# A hung HTTP request to the model provider (no response, socket kept open) must NOT
# freeze the whole run — that exact failure mode left a run sleeping for 43 min. Every
# model call is bounded by a hard timeout and retried with backoff; only after the
# retries are exhausted do we give up (the loop then ends cleanly as ERROR).
_LLM_CALL_TIMEOUT = 240          # seconds per single model call
_LLM_MAX_ATTEMPTS = 3

async def _llm_call_guarded(**kwargs):
    label = kwargs.get("label", "llm")
    last_exc: Exception | None = None
    for attempt in range(1, _LLM_MAX_ATTEMPTS + 1):
        try:
            return await asyncio.wait_for(llm_call(**kwargs), timeout=_LLM_CALL_TIMEOUT)
        except asyncio.TimeoutError as exc:
            last_exc = TimeoutError(f"LLM call exceeded {_LLM_CALL_TIMEOUT}s")
            reason = f"timeout >{_LLM_CALL_TIMEOUT}s"
        except Exception as exc:                       # transient API / network error
            last_exc = exc
            reason = f"{type(exc).__name__}: {str(exc)[:120]}"
        msg = f"    ⚠ LLM call [{label}] failed (attempt {attempt}/{_LLM_MAX_ATTEMPTS}): {reason}"
        (_RUN_LOGGER.log(msg) if _RUN_LOGGER else print(msg))
        if attempt < _LLM_MAX_ATTEMPTS:
            await asyncio.sleep(min(2 ** attempt, 15))
    raise last_exc


async def execute_tool(
    call: ToolCall,
    *,
    mcp_tools_by_name: dict,
    project_dir: str,
    allow_subagents: bool,
    messages_ref: list,                 # reference passed-through so compact_context can mutate
    backend_for_compaction: str,
) -> str:
    name = call.name
    args = call.args or {}

    if name == "_parse_error":
        return f"<error>could not parse tool call: {args.get('error')}</error>"

    # ── Force correct paths on compile/test: the model hallucinates fpml/expected
    # paths (and sometimes project_dir). The harness knows the truth, so override them
    # — run_test/compile_project always hit the real project and the real task inputs.
    if name in ("run_test", "compile_project"):
        args = {**args, "project_dir": project_dir}
        if name == "run_test":
            if _TASK_FPML:     args["fpml_file"] = _TASK_FPML
            if _TASK_EXPECTED: args["expected_json_file"] = _TASK_EXPECTED

    # ── Local tool: compaction ────────────────────────────────────────────────
    if name == COMPACT_TOOL_NAME:
        keep = int(args.get("keep_recent", 5))
        before = estimate_tokens(messages_ref, backend=backend_for_compaction)
        new_messages = await compact_messages(
            messages_ref,
            llm_call=lambda **kw: _text_call_via_llm_call(**kw),
            keep_recent=keep,
            label="manual-compact",
        )
        messages_ref.clear()
        messages_ref.extend(new_messages)
        after = estimate_tokens(messages_ref, backend=backend_for_compaction)
        return f"compacted: {before} → {after} tokens (kept last {keep} turns)"

    # ── Local tool: subagent spawn ────────────────────────────────────────────
    if name == "spawn_subagent":
        if not allow_subagents:
            return "<error>sub-agents cannot recurse</error>"
        sub_task = args.get("task", "")
        if not sub_task:
            return "<error>spawn_subagent: missing 'task'</error>"
        sub_label = args.get("label", "subagent")
        return await _run_subagent(
            sub_task, sub_label,
            mcp_tools_by_name=mcp_tools_by_name,
            project_dir=project_dir,
        )

    # ── Read de-duplication: stub re-reads to keep context from exploding ─────
    if name in ("read_file", "read_text_file") and args.get("path"):
        p = args["path"]
        if p in _READ_PATHS:
            return (f"(already read '{p}' earlier this run — content unchanged. "
                    f"Do NOT re-read it. Use what you have: write_file / edit_file / "
                    f"compile_project / run_test.)")
        _READ_PATHS.add(p)
    elif name == "read_multiple_files":
        paths = args.get("paths") or []
        if paths and all(p in _READ_PATHS for p in paths):
            return ("(already read these files earlier this run — content unchanged. "
                    "Do NOT re-read. Proceed to write_file / compile_project.)")
        _READ_PATHS.update(paths)

    # cdm_lookup and mkdir_p are now MCP tools (cdm_lookup_server / filesystem_server)
    # — they fall through to the MCP dispatch below.

    # ── write_file: a file is written ONCE; block re-writes, steer to next ────
    # Small models fixate on regenerating the first file (pom.xml) with slightly
    # different content forever, so a content-hash check is too weak — we block any
    # second write_file to the same path and point at edit_file / the next file.
    if name == "write_file" and args.get("path"):
        p = args["path"]
        if p in _WRITTEN:
            done = [Path(x).name for x in sorted(_WRITTEN)]
            return (f"(skip) '{Path(p).name}' was ALREADY written this run. To change it, "
                    f"use edit_file (NOT write_file). Otherwise MOVE ON. Files written: "
                    f"{done}. Now write the next missing source file (main class, "
                    f"transformer, or SemanticDiff), or compile_project if all exist.")
        try:
            res = await _with_tool_timeout(name, apply_wrapper(name, args, mcp_tools_by_name))
        except Exception as exc:
            return f"<error>{type(exc).__name__}: {exc}</error>"
        if not str(res).lstrip().startswith("<error"):
            _WRITTEN[p] = hashlib.md5((args.get("content") or "").encode("utf-8")).hexdigest()
        return res

    # ── Wrapped MCP tools (edit_file) ─────────────────────────────────────────
    if has_wrapper(name):
        try:
            return await _with_tool_timeout(name, apply_wrapper(name, args, mcp_tools_by_name))
        except Exception as exc:
            return f"<error>{type(exc).__name__}: {exc}</error>"

    # ── Direct MCP call ───────────────────────────────────────────────────────
    tool = mcp_tools_by_name.get(name)
    if tool is None:
        # Some filesystem MCP servers use `read_text_file` instead of `read_file`.
        if name == "read_file" and "read_text_file" in mcp_tools_by_name:
            tool = mcp_tools_by_name["read_text_file"]
        else:
            return f"<error>tool '{name}' not available</error>"
    try:
        result = await _with_tool_timeout(name, tool.ainvoke(args))
    except Exception as exc:
        return f"<error>{type(exc).__name__}: {exc}</error>"
    return _unwrap(result) or "<empty/>"


async def _text_call_via_llm_call(*, prompt, max_tokens, label):
    """Adapter: context.compact_messages wants a text-only callable."""
    resp = await llm_call(label=label, prompt=prompt, max_tokens=max_tokens, tools=None)
    return resp.text if isinstance(resp, LLMResponse) else str(resp)


# ── Sub-agent ──────────────────────────────────────────────────────────────────

async def _run_subagent(
    task: str,
    label: str,
    *,
    mcp_tools_by_name: dict,
    project_dir: str,
    max_iter: int = 15,
) -> str:
    return await _run_loop(
        system_prompt=SUBAGENT_SYSTEM_PROMPT.format(task=task),
        user_prompt=task,
        mcp_tools_by_name=mcp_tools_by_name,
        project_dir=project_dir,
        max_iter=max_iter,
        allow_subagents=False,
        label=label,
        use_native_tools=_use_native_tools(),
        tool_names=_SUBAGENT_TOOLS,   # lean schema: short-lived, no recursion
    )


# ── Main loop ──────────────────────────────────────────────────────────────────

def _use_native_tools() -> bool:
    """Use native tool_use when the configured backend supports it (manual does not)
    AND it isn't force-disabled via --no-native-tools / USE_NATIVE_TOOLS=0."""
    if not get_backend().uses_native_tools:
        return False
    return (os.getenv("USE_NATIVE_TOOLS", "1") not in ("0", "false", "False"))


async def _run_loop(
    *,
    system_prompt: str,
    user_prompt: str,
    mcp_tools_by_name: dict,
    project_dir: str,
    max_iter: int,
    allow_subagents: bool,
    label: str,
    use_native_tools: bool = True,
    tool_names: list[str] | None = None,
    require_match_for_done: bool = False,
) -> str:
    backend = get_backend().name          # drives gemini token-estimation factor only
    messages: list = [
        SystemMessage(content=system_prompt),
        HumanMessage(content=user_prompt),
    ]
    names_for_tools = tool_names or list(EXPOSED_TOOLS.keys())
    # Neutral tool registry; each backend converts it to its own wire format.
    tools_payload = schema_registry(names_for_tools) if use_native_tools else None
    last_compact_step = -10
    # Turns in a row that made no progress (no tool call, or read/list/search only).
    # Resets when the model writes/compiles/tests. Drives a steering nudge.
    no_progress = 0
    # write_file/edit_file calls since the last compile. Editing many times without
    # recompiling lets the model drift far from a compiling state (observed: a run
    # rewrote the transformer and regressed 10→49 errors while editing blind).
    mut_since_compile = 0
    # Whether the most recent run_test reported match=true. The model tends to emit
    # <done> claiming success while match=False (observed at score 90.2). When
    # require_match_for_done is set (orchestrator), a <done> is rejected until this
    # is true — so it spends its budget closing the last diffs instead of stopping.
    last_test_match = False
    # Set when compile_project returns 0 errors but no run_test has measured yet. The
    # dominant failure mode is: compile clean → keep editing "to improve" → break the
    # build → burn the budget recovering. After a clean compile we force a run_test
    # FIRST, to lock in a score and get the diff that guides targeted (not blind) edits.
    clean_compile_untested = False
    # Turns since the last compile_project, counted ONLY once a transformer exists. The
    # model tends to research/edit for many turns without recompiling (observed: 1 import
    # from a clean build, then 18 turns of cdm_lookup and never a compile). Forces a compile.
    turns_since_compile = 0

    for step in range(max_iter):
        # ── Auto-compact if context is too large ──────────────────────────────
        messages, last_compact_step, tok_before = await maybe_compact(
            messages,
            backend=backend,
            llm_call=lambda **kw: _text_call_via_llm_call(**kw),
            last_compact_step=last_compact_step,
            current_step=step,
            label=f"{label}/auto-compact",
        )
        if last_compact_step == step:
            tok_after = estimate_tokens(messages, backend=backend)
            _emit(f"[{label}] auto-compacted: {tok_before} → {tok_after} tokens")

        if _RUN_LOGGER:
            _RUN_LOGGER.step(label, step + 1, max_iter)
        else:
            print(f"\n[{label} step {step + 1}/{max_iter}] thinking...")
        # NOTE: tool_choice="required" was tried to break stalls but on this GGUF it
        # makes Ollama ~30x slower (grammar-constrained sampling) AND still returns no
        # tool call — net negative. We rely on the stall nudge instead. The tool_choice
        # plumbing stays in the backends for stronger models.
        resp: LLMResponse = await _llm_call_guarded(
            label=f"{label}/step{step + 1}",
            prompt=messages,
            max_tokens=8000,
            tools=tools_payload,
        )

        reasoning = _extract_reasoning(resp)
        calls = resp.tool_calls
        done = resp.done

        # ── Done without further work → return ────────────────────────────────
        if done is not None and not calls:
            if require_match_for_done and not last_test_match:
                if _RUN_LOGGER:
                    _RUN_LOGGER.turn(label, step + 1, resp.text, reasoning, [], [])
                _emit(f"[{label}] <done> REJECTED — no run_test match=true yet; pushing on")
                messages.append(HumanMessage(content=_FALSE_DONE_NUDGE))
                continue
            if _RUN_LOGGER:
                _RUN_LOGGER.turn(label, step + 1, resp.text, reasoning, [], [])
            _emit(f"[{label}] done: {done[:120]}")
            return done

        # ── Off-protocol: model produced no tool call ─────────────────────────
        if not calls:
            no_progress += 1
            if _RUN_LOGGER:
                _RUN_LOGGER.turn(label, step + 1, resp.text, reasoning, [], [])
                _RUN_LOGGER.log(f"[{label}] no tool call (#{no_progress})")
            else:
                print(f"[{label}] no tool call (#{no_progress}); {(resp.text or '')[:80]!r}")
            if resp.text:
                messages.append(AIMessage(content=resp.text))
            if no_progress >= _STALL_LIMIT:
                messages.append(HumanMessage(content=_stall_nudge()))
                no_progress = 0
            else:
                messages.append(HumanMessage(content=(
                    "Your previous turn had no tool call and no <done>. Respond with a "
                    "tool call now (e.g. write_file to create pom.xml), or emit <done>."
                )))
            continue

        # ── Record assistant turn ─────────────────────────────────────────────
        # In XML mode the model's text already contains the tool_call XML.
        # In native mode, record the model's OWN text. We must NOT synthesise a
        # "[calling N tool(s): ...]" placeholder: small models parrot that exact
        # string back as plain text on later turns instead of re-issuing tool
        # calls, stalling the loop. Past-tense neutral fallback if text is empty.
        if use_native_tools:
            note = (resp.text or "").strip() or f"(called {len(calls)} tool(s))"
            messages.append(AIMessage(content=note))
        else:
            messages.append(AIMessage(content=resp.text))

        # ── Execute tools in parallel ─────────────────────────────────────────
        async def _one(c: ToolCall):
            out = await execute_tool(
                c,
                mcp_tools_by_name=mcp_tools_by_name,
                project_dir=project_dir,
                allow_subagents=allow_subagents,
                messages_ref=messages,
                backend_for_compaction=backend,
            )
            return c, out, _cap_result(c.name, out)    # full out for the trace, capped for context

        # return_exceptions=True so a single tool blowing up never aborts the
        # whole turn; execute_tool already returns errors as strings, this is the
        # last-resort net.
        results = await asyncio.gather(*[_one(c) for c in calls], return_exceptions=True)
        pairs = []          # (ToolCall, capped_result) → goes into the model context
        trace_results = []  # (name, full_result)       → goes into the trace / score
        for c, r in zip(calls, results):
            if isinstance(r, BaseException):
                err = f"<error>{type(r).__name__}: {r}</error>"
                pairs.append((c, err))
                trace_results.append((c.name, err))
            else:
                _c, full, capped = r
                pairs.append((c, capped))
                trace_results.append((c.name, full))
                if c.name == "run_test":
                    last_test_match = '"match": true' in (full or "").lower() or "match=true" in (full or "").lower()
                    clean_compile_untested = False     # we measured; checkpoint cleared
                elif c.name == "compile_project":
                    ok = '"ok": true' in (full or "").lower()
                    clean_compile_untested = ok        # clean build that hasn't been tested yet

        if _RUN_LOGGER:
            _RUN_LOGGER.turn(label, step + 1, resp.text, reasoning,
                             [(c.name, c.args or {}) for c in calls], trace_results)
        else:
            print(f"[{label}] {len(calls)} tool call(s): {', '.join(c.name for c in calls)}")

        messages.append(HumanMessage(content=format_xml_results(pairs)))

        # ── Stall guard: exploration-only turns count as no progress ──────────
        names = {c.name for c in calls}
        if names and names.issubset(_EXPLORE_TOOLS):
            no_progress += 1
        else:
            no_progress = 0   # a write / compile / test (or mixed) turn = progress
        if no_progress >= _STALL_LIMIT:
            _emit(f"[{label}] stall guard: {no_progress} no-progress turns → nudging to write")
            messages.append(HumanMessage(content=_stall_nudge()))
            no_progress = 0

        # ── Drift guard: many edits without a compile → force a compile check ──
        if "compile_project" in names or "run_test" in names:
            mut_since_compile = 0
        else:
            mut_since_compile += sum(1 for c in calls if c.name in ("write_file", "edit_file"))
        if mut_since_compile >= _COMPILE_NUDGE_LIMIT:
            _emit(f"[{label}] drift guard: {mut_since_compile} edits without compile → nudging to compile")
            messages.append(HumanMessage(content=_COMPILE_NUDGE))
            mut_since_compile = 0

        # ── Checkpoint guard: after a clean compile, measure before editing on ──
        if clean_compile_untested and ("write_file" in names or "edit_file" in names) \
                and "run_test" not in names:
            _emit(f"[{label}] checkpoint guard: edited after clean compile w/o testing → nudging to run_test")
            messages.append(HumanMessage(content=_RUN_TEST_NUDGE))
            clean_compile_untested = False     # remind once per clean-compile checkpoint

        # ── Compile-cadence guard: once a transformer exists, force a compile after a few
        # non-compile turns. Counts EVERY turn (research included), unlike the drift guard,
        # because the killer pattern is researching for many turns one error from a clean build.
        if "compile_project" in names or "run_test" in names:
            turns_since_compile = 0
        elif _transformer_written():
            turns_since_compile += 1
        if turns_since_compile >= _COMPILE_CADENCE_LIMIT:
            _emit(f"[{label}] cadence guard: {turns_since_compile} turns w/o compile → forcing compile")
            messages.append(HumanMessage(content=_COMPILE_CADENCE_NUDGE))
            turns_since_compile = 0

        if done is not None:
            if require_match_for_done and not last_test_match:
                _emit(f"[{label}] <done> REJECTED — no run_test match=true yet; pushing on")
                messages.append(HumanMessage(content=_FALSE_DONE_NUDGE))
                continue
            _emit(f"[{label}] done after {step + 1} step(s): {done[:120]}")
            return done

    raise RuntimeError(f"[{label}] max iterations ({max_iter}) reached")


# ── Boilerplate pre-staging ─────────────────────────────────────────────────────
# The harness writes the proven, product-agnostic INFRA into every project so the model
# spends its whole budget on the MAPPING (IrsTransformer.java), not on re-deriving the
# build/main/diff. These are infra (build config, XML-in/JSON-out wiring, JSON comparator),
# NOT the FpML→CDM mapping — that stays the model's job. Sourced from a converging run.
_SCAFFOLD_DIR = ROOT / "scaffold"
_BOILERPLATE = {
    "pom.xml":          "pom.xml",
    "FpmlToCdmApp.java": "src/main/java/com/example/FpmlToCdmApp.java",
    "SemanticDiff.java": "src/main/java/com/example/SemanticDiff.java",
}


def _prestage_boilerplate(project_dir: str) -> list[str]:
    """Copy the proven infra files into project_dir. Returns the dest paths."""
    written: list[str] = []
    for src_name, rel_dest in _BOILERPLATE.items():
        dest = Path(project_dir) / rel_dest
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text((_SCAFFOLD_DIR / src_name).read_text(encoding="utf-8"), encoding="utf-8")
        written.append(str(dest))
    return written


# ── Entry point ────────────────────────────────────────────────────────────────

async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fpml",     required=True)
    parser.add_argument("--expected", required=True)
    parser.add_argument("--out",      default="./workspaces/test-fra")
    parser.add_argument("--max-iter", type=int, default=60)
    parser.add_argument("--no-subagents",    action="store_true")
    parser.add_argument("--no-kb", action="store_true",
                        help="ablation: drop the knowledge-base section from the prompt")
    parser.add_argument("--no-boilerplate", action="store_true",
                        help="ablation: do NOT pre-stage pom/main/SemanticDiff (model writes everything)")
    parser.add_argument("--no-native-tools", action="store_true",
                        help="Force XML-in-text tool protocol (for testing).")
    args = parser.parse_args()

    project_dir = str(Path(args.out).resolve())
    Path(project_dir).mkdir(parents=True, exist_ok=True)

    global _RUN_LOGGER
    _RUN_LOGGER = RunLogger(project_dir, run_name=Path(project_dir).name)
    _READ_PATHS.clear()
    _WRITTEN.clear()

    # Pre-stage the proven boilerplate so the model writes ONLY the transformer. Mark the
    # files as already-written so the write_file guard blocks the model from clobbering them.
    if not args.no_boilerplate:
        staged = _prestage_boilerplate(project_dir)
        for p in staged:
            _WRITTEN[p] = "boilerplate"
        _RUN_LOGGER.log(f"Pre-staged boilerplate ({len(staged)} files) — model writes ONLY the transformer")
    else:
        _RUN_LOGGER.log("ABLATION: boilerplate NOT pre-staged (model writes everything)")

    _backend = get_backend()
    _RUN_LOGGER.log(f"Backend  : {_backend.name}  (model={_backend.model})")
    _RUN_LOGGER.log(f"FpML     : {args.fpml}")
    _RUN_LOGGER.log(f"Expected : {args.expected}")
    _RUN_LOGGER.log(f"Out      : {project_dir}")
    _RUN_LOGGER.log(f"Max iter : {args.max_iter}")

    if args.no_native_tools:
        os.environ["USE_NATIVE_TOOLS"] = "0"
    use_native = _use_native_tools()
    print(f"Protocol : {'native tool_use' if use_native else 'XML-in-text'}")

    servers = get_servers()
    client = MultiServerMCPClient(servers)
    # Load tools per-server so one failing/optional server (e.g. tavily 401) is
    # SKIPPED instead of aborting the whole run. Robustness for unattended runs.
    tools_by_name = {}
    for name in servers:
        try:
            stools = await asyncio.wait_for(client.get_tools(server_name=name), timeout=60)
            for t in stools:
                tools_by_name[t.name] = t
            _RUN_LOGGER.log(f"MCP '{name}': {len(stools)} tool(s)")
        except Exception as e:
            _RUN_LOGGER.log(
                f"MCP '{name}' SKIPPED (load failed): {type(e).__name__}: {str(e)[:140]}"
            )
    _RUN_LOGGER.log(f"Loaded {len(tools_by_name)} MCP tools total")

    # Soft warning: some EXPOSED_TOOLS may not be backed by any loaded MCP tool.
    # That's fine for `compact_context` / `spawn_subagent` (in-loop, never MCP).
    local_only = {"compact_context", "spawn_subagent"}
    missing = [
        t for t in EXPOSED_TOOLS
        if t not in tools_by_name and t not in local_only
    ]
    if missing:
        print(f"WARNING — EXPOSED_TOOLS not loaded by any MCP: {missing}")

    # Lean orchestrator tool set (no list/search; delegates heavy work).
    exposed = [t for t in _ORCHESTRATOR_TOOLS if t in EXPOSED_TOOLS]
    if args.no_subagents:
        exposed = [t for t in exposed if t != "spawn_subagent"]

    # Absolute paths so the model never mis-resolves them. It otherwise tends to
    # prepend project_dir to a relative input path and read a non-existent file.
    fpml_abs = str(Path(args.fpml).resolve())
    expected_abs = str(Path(args.expected).resolve())
    # Hand the real task paths to execute_tool so it can force them onto every run_test
    # call (the model otherwise invents fpml/expected paths and the test can't find them).
    global _TASK_FPML, _TASK_EXPECTED
    _TASK_FPML, _TASK_EXPECTED = fpml_abs, expected_abs
    user_prompt = textwrap.dedent(f"""\
        Convert this FpML file to CDM JSON via a small Java program.

        These are ABSOLUTE paths — pass them to read_file EXACTLY as written, do not
        prepend the project dir or anything else:
          FpML file     : {fpml_abs}
          Expected JSON : {expected_abs}
        Write your Maven project under (and only under):
          Project dir   : {project_dir}

        Success criterion: `run_test` on this project reports match=true.
    """).strip()

    kb_section = "" if args.no_kb else KB_SECTION.format(
        kb_index=str((ROOT / "knowledge_base" / "README.md").resolve()))
    if args.no_kb:  _RUN_LOGGER.log("ABLATION: knowledge-base section DROPPED")

    sys_prompt = SYSTEM_PROMPT_TEMPLATE.format(
        tools_doc=render_tools_doc(exposed),
        tool_call_protocol=(NATIVE_TOOL_CALL_PROTOCOL if use_native else XML_TOOL_CALL_PROTOCOL),
        kb_section=kb_section,
        project_dir=str(project_dir),
    )

    try:
        result = await _run_loop(
            system_prompt=sys_prompt,
            user_prompt=user_prompt,
            mcp_tools_by_name=tools_by_name,
            project_dir=project_dir,
            max_iter=args.max_iter,
            allow_subagents=not args.no_subagents,
            label="main",
            use_native_tools=use_native,
            tool_names=exposed,
            require_match_for_done=True,   # orchestrator: <done> only after match=true
        )
        _RUN_LOGGER.finish("SUCCESS", _RUN_LOGGER.steps, result)
        return 0
    except RuntimeError as e:
        # max-iter reached, or a non-recoverable loop error.
        status = "MAX_ITER" if "max iterations" in str(e) else "FAIL"
        _RUN_LOGGER.finish(status, _RUN_LOGGER.steps, str(e))
        return 1
    except Exception as e:  # last-resort net so the run always writes a summary
        _RUN_LOGGER.finish("ERROR", _RUN_LOGGER.steps, f"{type(e).__name__}: {e}")
        raise
    finally:
        # Clean shutdown of MCP transports (best-effort; never masks the result).
        close = getattr(client, "aclose", None) or getattr(client, "close", None)
        if close:
            try:
                res = close()
                if asyncio.iscoroutine(res):
                    await res
            except Exception:
                pass


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
