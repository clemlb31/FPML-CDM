"""Autonomous LLM-driven agent — no LangGraph, no state machine, no planning node.

Single prompt + tools + loop. The model decides everything: when to read FpML,
when to write Java, when to compile, when to delegate to subagents, when to stop.

Backend = whatever `helpers.llm_text_or_raise` is configured for (default: manual).

Usage:
    python -m agent.autonomous \\
        --fpml      data/test/rates-5-10/fpml/ird-ex08-fra.xml \\
        --expected  data/test/rates-5-10/cdm/ird-ex08-fra.json \\
        --out       workspaces/test-fra \\
        [--max-iter 30] [--no-subagents]
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import re
import sys
import textwrap
from pathlib import Path

from langchain_core.messages import HumanMessage, SystemMessage
from langchain_mcp_adapters.client import MultiServerMCPClient

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from agent.helpers import (
    llm_text_or_raise as _llm_text_or_raise,
    get_servers,
    unwrap as _unwrap,
)


# ── System prompt ─────────────────────────────────────────────────────────────

SYSTEM_PROMPT_TEMPLATE = """\
You are an autonomous software-engineering agent. Your task is to convert an
FpML 5.x XML document into the corresponding CDM 6.x JSON by writing, compiling
and running a small Java program. You decide every step.

# Task contract
- Input  : an FpML XML file (path given in the user message).
- Target : the JSON file at the `expected` path. Your generated Java program,
           when packaged and run on the FpML file, must produce JSON that
           matches the target under `SemanticDiff` rules (globalKey ignored,
           numeric BigDecimal-compare, etc. — see knowledge_base/).
- Output : a Maven project at the `project_dir` path, containing:
             pom.xml
             src/main/java/com/example/IrsTransformer.java  (or similar)
             src/main/java/com/example/FpmlToCdmApp.java    (main class)
             src/main/java/com/example/SemanticDiff.java    (compare util)
           The main class must print actual JSON to stdout when given the FpML path.

# CDM Maven coords (Maven Central — no custom repository needed)
- Group / artifact : org.finos.cdm / cdm-java
- Version          : 6.19.0
- Java target      : 17
- Also useful      : com.fasterxml.jackson.core:jackson-databind:2.17.2
                     com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
                     com.google.inject:guice:6.0.0

# Tools you can call
{tools_doc}

# Tool-call protocol
- Emit one or more tool calls per turn. Each tool call MUST be wrapped exactly:
    <tool_call>
    {{"name": "<tool_name>", "args": {{ ... }}}}
    </tool_call>
- Multiple <tool_call> blocks in a single turn run in PARALLEL.
- The host returns each result in a <tool_result name="..." idx="...">…</tool_result> block.
- When you have produced a CDM JSON that matches the expected and `run_test`
  reports match=true, emit:
    <done>one-sentence summary of what was built</done>
  Nothing else after <done>.
- If a tool errors, you get <tool_result … error="true">…</tool_result>; treat
  it like a normal observation and iterate.
- Do NOT emit explanatory prose outside tool_call / done — it is ignored.

# Sub-agents
- Use `spawn_subagent` to delegate self-contained Java method bodies in parallel.
- A sub-agent has the same tools (except recursion) and the same iteration budget.
- Use it when you have ≥2 independent methods to fill; otherwise do the work yourself.

# Strategy (suggested, not enforced)
1. read_file the FpML and the expected JSON to understand the shape of the data.
2. read_file knowledge_base/reference/cdm/* for CDM class hints if useful.
3. write_file the Maven skeleton (pom.xml + 3 Java files).
4. compile_project. Read errors. Fix iteratively.
5. run_test. Read diffs. Fix iteratively.
6. When match=true → <done>.

Be terse. No commentary. Tool calls and <done> only.
"""


# ── Tool registry ─────────────────────────────────────────────────────────────

# MCP tool names exposed to the agent. Anything not in this list is hidden,
# even if loaded via MultiServerMCPClient. Keeps the prompt focused.
EXPOSED_TOOLS = {
    # Filesystem (filesystem MCP)
    "read_file":          "Read a UTF-8 file. args: {path: string}",
    "write_file":         "Write a UTF-8 file (overwrite). args: {path: string, content: string}",
    "edit_file":          "Find/replace in a file. args: {path: string, edits: [{oldText, newText}], dryRun?: bool}",
    "list_directory":     "List files+dirs. args: {path: string}",
    "mkdir_p":            "Create a directory tree recursively (parents OK). args: {path: string}",
    # Validator (build/test feedback)
    "compile_project":    "Run `mvn clean compile` on a Maven project. args: {project_dir: string}",
    "run_test":           "Package + run JAR on (fpml_file, expected_json_file). args: {project_dir, fpml_file, expected_json_file}",
    "score_with_llm":     "LLM-as-judge semantic eval of actual vs expected JSON. args: {actual_json, expected_json, fpml_context?}",
    # Mapping
    "get_maven_dependencies": "Return the CDM/Jackson Maven dependency XML blocks. args: {}",
}


def render_tools_doc(extra_tools: list[str]) -> str:
    lines = []
    for name, desc in EXPOSED_TOOLS.items():
        lines.append(f"- `{name}` — {desc}")
    for name in extra_tools:
        lines.append(f"- `{name}` — see registry")
    return "\n".join(lines)


# ── Tool-call parsing ──────────────────────────────────────────────────────────

_TOOL_CALL_RE = re.compile(r"<tool_call>\s*(\{.*?\})\s*</tool_call>", re.DOTALL)
_DONE_RE      = re.compile(r"<done>(.*?)</done>", re.DOTALL)


def parse_response(text: str) -> tuple[list[dict], str | None]:
    """Extract tool calls + optional <done> from a model response."""
    done_match = _DONE_RE.search(text)
    done = done_match.group(1).strip() if done_match else None

    calls: list[dict] = []
    for m in _TOOL_CALL_RE.finditer(text):
        try:
            call = json.loads(m.group(1))
        except json.JSONDecodeError as e:
            calls.append({
                "name":  "_parse_error",
                "args":  {"raw": m.group(1)[:400], "error": str(e)},
            })
            continue
        if isinstance(call, dict) and "name" in call and "args" in call:
            calls.append(call)
        else:
            calls.append({
                "name":  "_parse_error",
                "args":  {"raw": str(call)[:400], "error": "missing name/args"},
            })

    return calls, done


# ── Tool execution ─────────────────────────────────────────────────────────────

async def execute_tool(
    call: dict,
    mcp_tools_by_name: dict,
    *,
    project_dir: str,
    allow_subagents: bool,
) -> str:
    name = call.get("name", "")
    args = call.get("args", {}) or {}

    if name == "_parse_error":
        return f"<error>could not parse tool call: {args.get('error')}</error>"

    if name == "spawn_subagent":
        if not allow_subagents:
            return "<error>sub-agents cannot recurse</error>"
        sub_task = args.get("task", "")
        if not sub_task:
            return "<error>spawn_subagent: missing 'task'</error>"
        sub_label = args.get("label", "subagent")
        return await _run_subagent(
            sub_task,
            sub_label,
            mcp_tools_by_name=mcp_tools_by_name,
            project_dir=project_dir,
        )

    if name == "mkdir_p":
        target = args.get("path", "")
        if not target:
            return "<error>mkdir_p: missing 'path'</error>"
        try:
            Path(target).mkdir(parents=True, exist_ok=True)
            return f"ok: created {target}"
        except OSError as exc:
            return f"<error>mkdir_p {target}: {exc}</error>"

    tool = mcp_tools_by_name.get(name)
    if tool is None:
        return f"<error>tool '{name}' not available</error>"
    try:
        result = await tool.ainvoke(args)
    except Exception as exc:
        return f"<error>{type(exc).__name__}: {exc}</error>"
    return _unwrap(result) or "<empty/>"


def format_results(results: list[tuple[dict, str]]) -> str:
    """Wrap each (call, result) pair in a <tool_result> block for the next turn."""
    parts = []
    for idx, (call, output) in enumerate(results):
        name = call.get("name", "?")
        is_error = output.startswith("<error>") or "<error>" in output[:50]
        parts.append(
            f'<tool_result name="{name}" idx="{idx}"'
            f'{" error=\"true\"" if is_error else ""}>\n'
            f'{output}\n'
            f'</tool_result>'
        )
    return "\n".join(parts)


# ── Sub-agent ──────────────────────────────────────────────────────────────────

async def _run_subagent(
    task: str,
    label: str,
    *,
    mcp_tools_by_name: dict,
    project_dir: str,
    max_iter: int = 15,
) -> str:
    """Run a focused sub-agent for a delegated task. Cannot itself spawn sub-agents."""
    return await _run_loop(
        system_prompt=SUBAGENT_SYSTEM_PROMPT.format(task=task),
        user_prompt=task,
        mcp_tools_by_name=mcp_tools_by_name,
        project_dir=project_dir,
        max_iter=max_iter,
        allow_subagents=False,
        label=label,
    )


SUBAGENT_SYSTEM_PROMPT = """\
You are a focused sub-agent. Your single task:
  {task}

You have the same filesystem and build tools as the main agent (read_file,
write_file, edit_file, compile_project, run_test, get_maven_dependencies).
You CANNOT spawn further sub-agents.

Same protocol: <tool_call>...</tool_call> blocks; <done>summary</done> to finish.

Be terse. When you finish, emit <done> with a one-sentence summary that the
parent can use directly (e.g. "wrote IrsTransformer.buildFixedLeg, compiles").
"""


# ── Main loop ──────────────────────────────────────────────────────────────────

async def _run_loop(
    *,
    system_prompt: str,
    user_prompt: str,
    mcp_tools_by_name: dict,
    project_dir: str,
    max_iter: int,
    allow_subagents: bool,
    label: str,
) -> str:
    messages: list = [
        SystemMessage(content=system_prompt),
        HumanMessage(content=user_prompt),
    ]

    for step in range(max_iter):
        print(f"\n[{label} step {step + 1}/{max_iter}] thinking...")
        text = await _llm_text_or_raise(
            project_dir=project_dir,
            label=f"{label}/step{step + 1}",
            prompt=messages,
            max_tokens=6000,
        )

        calls, done = parse_response(text)
        if done is not None and not calls:
            print(f"[{label}] done: {done[:120]}")
            return done
        if not calls:
            # Model went off-protocol. Nudge it back.
            messages.append(HumanMessage(content=text))
            messages.append(HumanMessage(content=(
                "Your previous turn had no <tool_call> and no <done>. "
                "Continue with a tool call or emit <done>."
            )))
            continue

        # Record the model's turn verbatim so it can see its own context
        messages.append(HumanMessage(content=text))

        print(f"[{label}] {len(calls)} tool call(s): "
              f"{', '.join(c.get('name', '?') for c in calls)}")

        async def _one(call):
            out = await execute_tool(
                call,
                mcp_tools_by_name=mcp_tools_by_name,
                project_dir=project_dir,
                allow_subagents=allow_subagents,
            )
            return call, out

        pairs = await asyncio.gather(*[_one(c) for c in calls])
        messages.append(HumanMessage(content=format_results(pairs)))

        # If <done> was also emitted alongside calls, honour it after the calls ran
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
    parser.add_argument("--no-subagents", action="store_true")
    args = parser.parse_args()

    project_dir = str(Path(args.out).resolve())
    Path(project_dir).mkdir(parents=True, exist_ok=True)

    print(f"Backend  : {os.getenv('LLM_BACKEND', 'gemini')}")
    print(f"FpML     : {args.fpml}")
    print(f"Expected : {args.expected}")
    print(f"Out      : {project_dir}")

    # Connect MCP servers
    servers = get_servers()
    client = MultiServerMCPClient(servers)
    tools = await client.get_tools()
    tools_by_name = {t.name: t for t in tools}
    print(f"Loaded {len(tools_by_name)} MCP tools")

    missing = [t for t in EXPOSED_TOOLS if t not in tools_by_name]
    if missing:
        print(f"WARNING — these EXPOSED_TOOLS are not loaded: {missing}")

    # Build the initial user prompt
    user_prompt = textwrap.dedent(f"""\
        Convert this FpML file to CDM JSON via a small Java program.

        FpML file       : {args.fpml}
        Expected JSON   : {args.expected}
        Project dir     : {project_dir}

        Success criterion: `run_test` on this project reports `match=true`.
    """).strip()

    sys_prompt = SYSTEM_PROMPT_TEMPLATE.format(
        tools_doc=render_tools_doc(
            ["spawn_subagent"] if not args.no_subagents else []
        )
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
        )
        print(f"\nSUCCESS — {result}")
        return 0
    except RuntimeError as e:
        print(f"\nFAIL — {e}")
        return 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
