"""Local safety wrappers around MCP tools.

Each wrapper intercepts a specific tool name BEFORE the MCP round-trip and adds
validation that the upstream server (e.g. @modelcontextprotocol/server-filesystem)
doesn't enforce. Wrappers are async, take the raw args + an MCP tool registry,
and return a string result. They are dispatched by `WRAPPERS` below.

Adding a new wrapper:
  1. Write `async def my_wrapper(args, mcp_tools_by_name) -> str:`.
  2. Add `"tool_name": my_wrapper` to WRAPPERS at the bottom.
  3. Done — `apply_wrapper` is called automatically by the agent loop.
"""
from __future__ import annotations

from pathlib import Path
from typing import Any, Awaitable, Callable

from agent.helpers import unwrap


# ── edit_file with uniqueness enforcement ─────────────────────────────────────

async def safe_edit_file(
    args: dict,
    mcp_tools_by_name: dict,
) -> str:
    """Validate every edit's `oldText` is unique in the target file before
    delegating to the upstream `edit_file` MCP tool. Fail fast with a useful
    message instead of letting an ambiguous match silently corrupt the file.
    """
    path = args.get("path", "")
    edits = args.get("edits") or []
    if not path:
        return "<error>edit_file: missing 'path'</error>"
    if not isinstance(edits, list) or not edits:
        return "<error>edit_file: 'edits' must be a non-empty list</error>"

    read_tool = mcp_tools_by_name.get("read_file") or mcp_tools_by_name.get("read_text_file")
    if read_tool is None:
        # Fall back to local read so we don't lose the safety net even if
        # the filesystem MCP renamed its read tool.
        try:
            content = Path(path).read_text(encoding="utf-8")
        except OSError as exc:
            return f"<error>edit_file: cannot read {path} ({exc})</error>"
    else:
        try:
            raw = await read_tool.ainvoke({"path": path})
        except Exception as exc:
            return f"<error>edit_file: read_file failed for {path}: {exc}</error>"
        content = unwrap(raw)

    # Validate each edit in sequence, simulating the cumulative effect.
    for i, edit in enumerate(edits):
        old = edit.get("oldText", "")
        new = edit.get("newText", "")
        if not old:
            return f"<error>edit_file: edits[{i}].oldText is empty</error>"
        count = content.count(old)
        if count == 0:
            preview = old[:160].replace("\n", "\\n")
            return (
                f"<error>edit_file: edits[{i}].oldText not found in {path}. "
                f"Snippet looked for: {preview!r}</error>"
            )
        if count > 1:
            preview = old[:160].replace("\n", "\\n")
            return (
                f"<error>edit_file: edits[{i}].oldText appears {count} times in {path}. "
                f"Add surrounding context to make it unique. Snippet: {preview!r}</error>"
            )
        content = content.replace(old, new, 1)

    edit_tool = mcp_tools_by_name.get("edit_file")
    if edit_tool is None:
        return "<error>edit_file: MCP edit_file tool unavailable</error>"
    try:
        result = await edit_tool.ainvoke(args)
    except Exception as exc:
        return f"<error>edit_file MCP failure: {type(exc).__name__}: {exc}</error>"
    return unwrap(result) or "ok"


# ── write_file with parent-dir auto-create ────────────────────────────────────

async def safe_write_file(
    args: dict,
    mcp_tools_by_name: dict,
) -> str:
    """Forward to MCP write_file, creating the parent directory first if missing.

    The upstream filesystem MCP fails with 'parent dir not found' surprisingly
    often — pre-creating saves a wasted iteration.
    """
    path = args.get("path", "")
    if path:
        try:
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            return f"<error>write_file: cannot create parent dir for {path}: {exc}</error>"

    write_tool = mcp_tools_by_name.get("write_file")
    if write_tool is None:
        return "<error>write_file: MCP write_file tool unavailable</error>"
    try:
        result = await write_tool.ainvoke(args)
    except Exception as exc:
        return f"<error>write_file MCP failure: {type(exc).__name__}: {exc}</error>"
    return unwrap(result) or "ok"


# ── Registry & dispatcher ─────────────────────────────────────────────────────

WRAPPERS: dict[str, Callable[[dict, dict], Awaitable[str]]] = {
    "edit_file":  safe_edit_file,
    "write_file": safe_write_file,
}


def has_wrapper(tool_name: str) -> bool:
    return tool_name in WRAPPERS


async def apply_wrapper(
    tool_name: str,
    args: dict,
    mcp_tools_by_name: dict,
) -> str:
    wrapper = WRAPPERS[tool_name]
    return await wrapper(args, mcp_tools_by_name)
