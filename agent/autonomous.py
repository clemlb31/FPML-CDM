"""Autonomous LLM-driven agent — no LangGraph, no state machine, no planning node.

Single prompt + tools + loop. The model decides everything: when to read FpML,
when to write Java, when to compile, when to delegate to subagents, when to stop.

Architecture:
  • protocol.py        — parse XML / native tool_use into a unified LLMResponse
  • tools_registry.py  — single source of truth for tool descriptions + schemas
  • tool_wrappers.py   — local safety nets around MCP tools (edit_file uniqueness…)
  • context.py         — token estimation + auto/manual compaction
  • helpers.py         — backend dispatch, retry, message converters
  • llm_call/          — pluggable LLM backends (gemini/groq/ollama/vllm/anthropic/…)

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
import os
import sys
import textwrap
from pathlib import Path

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
from langchain_mcp_adapters.client import MultiServerMCPClient

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from agent.context import (
    COMPACT_TOOL_NAME,
    compact_messages,
    estimate_tokens,
    maybe_compact,
)
from agent.helpers import (
    llm_call,
    get_servers,
    unwrap as _unwrap,
)
from agent.protocol import (
    LLMResponse,
    ToolCall,
    format_xml_results,
    tools_to_openai,
)
from agent.tool_wrappers import apply_wrapper, has_wrapper
from agent.tools_registry import EXPOSED_TOOLS, render_tools_doc, schema_registry


# ── System prompt ─────────────────────────────────────────────────────────────

SYSTEM_PROMPT_TEMPLATE = """\
You are an autonomous software-engineering agent. Your task is to convert an
FpML 5.x XML document into the corresponding CDM 6.x JSON by writing, compiling
and running a Java program. You decide every step.

# Task contract
- Input  : an FpML XML file (path given in the user message).
- Target : the JSON file at the `expected` path. Your generated Java program,
           when packaged and run on the FpML file, must produce JSON that
           matches the target under `SemanticDiff` rules (globalKey ignored,
           numeric BigDecimal-compare, etc.).
- Output : a Maven project at the `project_dir` path, containing:
             pom.xml
             src/main/java/com/example/IrsTransformer.java  (or similar)
             src/main/java/com/example/FpmlToCdmApp.java    (main class)
             src/main/java/com/example/SemanticDiff.java    (compare util)

# CDM Maven coords (Maven Central)
- org.finos.cdm:cdm-java:6.19.0 (Java 17)
- com.fasterxml.jackson.core:jackson-databind:2.17.2
- com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2

# Tools available
{tools_doc}

{tool_call_protocol}

# Sub-agents
- Use `spawn_subagent` to delegate self-contained Java method bodies in parallel.
- A sub-agent has the same tools (except recursion) and a smaller iteration budget.
- Use it when you have ≥2 independent methods to fill; otherwise do the work yourself.

# Strategy (suggested, not enforced)
1. read_file / read_multiple_files / grep to understand FpML and expected JSON shape.
2. write_file the Maven skeleton (pom.xml + 3 Java files).
3. compile_project. Read errors. Fix iteratively (use edit_file when possible).
4. run_test. Read diffs. Fix iteratively.
5. When match=true → emit <done>.

Be terse. No prose outside tool calls and <done>.
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

Same tools as the main agent (read_file, write_file, edit_file, compile_project,
run_test, grep, get_maven_dependencies). You CANNOT spawn further sub-agents.

When you finish, emit <done>summary</done> with a one-sentence summary the parent
can use directly (e.g. "wrote IrsTransformer.buildFixedLeg, compiles").
"""


# ── Tool execution ─────────────────────────────────────────────────────────────

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

    # ── Local tool: mkdir_p (MCP create_directory doesn't recurse) ────────────
    if name == "mkdir_p":
        target = args.get("path", "")
        if not target:
            return "<error>mkdir_p: missing 'path'</error>"
        try:
            Path(target).mkdir(parents=True, exist_ok=True)
            return f"ok: created {target}"
        except OSError as exc:
            return f"<error>mkdir_p {target}: {exc}</error>"

    # ── Wrapped MCP tools (edit_file, write_file) ─────────────────────────────
    if has_wrapper(name):
        return await apply_wrapper(name, args, mcp_tools_by_name)

    # ── Direct MCP call ───────────────────────────────────────────────────────
    tool = mcp_tools_by_name.get(name)
    if tool is None:
        # Some filesystem MCP servers use `read_text_file` instead of `read_file`.
        if name == "read_file" and "read_text_file" in mcp_tools_by_name:
            tool = mcp_tools_by_name["read_text_file"]
        else:
            return f"<error>tool '{name}' not available</error>"
    try:
        result = await tool.ainvoke(args)
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
    )


# ── Main loop ──────────────────────────────────────────────────────────────────

def _use_native_tools() -> bool:
    """Native tool_use is the default for non-manual backends, opt-out with env."""
    backend = (os.getenv("LLM_BACKEND") or "gemini").lower()
    if backend == "manual":
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
) -> str:
    backend = (os.getenv("LLM_BACKEND") or "gemini").lower()
    messages: list = [
        SystemMessage(content=system_prompt),
        HumanMessage(content=user_prompt),
    ]
    tools_payload = (
        tools_to_openai(schema_registry()) if use_native_tools else None
    )
    last_compact_step = -10

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
            print(f"[{label}] auto-compacted: {tok_before} → {tok_after} tokens")

        print(f"\n[{label} step {step + 1}/{max_iter}] thinking...")
        resp: LLMResponse = await llm_call(
            label=f"{label}/step{step + 1}",
            prompt=messages,
            max_tokens=6000,
            tools=tools_payload,
        )

        calls = resp.tool_calls
        done = resp.done

        # ── Done without further work → return ────────────────────────────────
        if done is not None and not calls:
            print(f"[{label}] done: {done[:120]}")
            return done

        # ── Off-protocol nudge ────────────────────────────────────────────────
        if not calls:
            if resp.text:
                messages.append(AIMessage(content=resp.text))
            messages.append(HumanMessage(content=(
                "Your previous turn had no tool call and no <done>. "
                "Continue with a tool call or emit <done>."
            )))
            continue

        # ── Record assistant turn ─────────────────────────────────────────────
        # In XML mode the model's text already contains the tool_call XML.
        # In native mode we synthesise a brief assistant note for the log.
        if use_native_tools:
            call_names = ", ".join(c.name for c in calls)
            messages.append(AIMessage(content=f"[calling {len(calls)} tool(s): {call_names}]"))
        else:
            messages.append(AIMessage(content=resp.text))

        print(f"[{label}] {len(calls)} tool call(s): "
              f"{', '.join(c.name for c in calls)}")

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
            return c, out

        pairs = await asyncio.gather(*[_one(c) for c in calls])
        messages.append(HumanMessage(content=format_xml_results(pairs)))

        if done is not None:
            print(f"[{label}] done after {step + 1} step(s): {done[:120]}")
            return done

    raise RuntimeError(f"[{label}] max iterations ({max_iter}) reached")


# ── Entry point ────────────────────────────────────────────────────────────────

async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fpml",     required=True)
    parser.add_argument("--expected", required=True)
    parser.add_argument("--out",      default="./workspaces/test-fra")
    parser.add_argument("--max-iter", type=int, default=30)
    parser.add_argument("--no-subagents",    action="store_true")
    parser.add_argument("--no-native-tools", action="store_true",
                        help="Force XML-in-text tool protocol (for testing).")
    args = parser.parse_args()

    project_dir = str(Path(args.out).resolve())
    Path(project_dir).mkdir(parents=True, exist_ok=True)

    backend = os.getenv("LLM_BACKEND", "gemini")
    print(f"Backend  : {backend}")
    print(f"FpML     : {args.fpml}")
    print(f"Expected : {args.expected}")
    print(f"Out      : {project_dir}")

    if args.no_native_tools:
        os.environ["USE_NATIVE_TOOLS"] = "0"
    use_native = _use_native_tools()
    print(f"Protocol : {'native tool_use' if use_native else 'XML-in-text'}")

    servers = get_servers()
    client = MultiServerMCPClient(servers)
    tools = await client.get_tools()
    tools_by_name = {t.name: t for t in tools}
    print(f"Loaded {len(tools_by_name)} MCP tools")

    # Soft warning: some EXPOSED_TOOLS may not be backed by any loaded MCP tool.
    # That's fine for `compact_context`, `spawn_subagent`, `mkdir_p` (all local-only).
    local_only = {"compact_context", "spawn_subagent", "mkdir_p"}
    missing = [
        t for t in EXPOSED_TOOLS
        if t not in tools_by_name and t not in local_only
    ]
    if missing:
        print(f"WARNING — EXPOSED_TOOLS not loaded by any MCP: {missing}")

    exposed = list(EXPOSED_TOOLS.keys())
    if args.no_subagents:
        exposed = [t for t in exposed if t != "spawn_subagent"]

    user_prompt = textwrap.dedent(f"""\
        Convert this FpML file to CDM JSON via a small Java program.

        FpML file     : {args.fpml}
        Expected JSON : {args.expected}
        Project dir   : {project_dir}

        Success criterion: `run_test` on this project reports match=true.
    """).strip()

    sys_prompt = SYSTEM_PROMPT_TEMPLATE.format(
        tools_doc=render_tools_doc(exposed),
        tool_call_protocol=(NATIVE_TOOL_CALL_PROTOCOL if use_native else XML_TOOL_CALL_PROTOCOL),
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
        )
        print(f"\nSUCCESS — {result}")
        return 0
    except RuntimeError as e:
        print(f"\nFAIL — {e}")
        return 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
