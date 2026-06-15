"""Filesystem MCP server — sandboxed file access for the agent.

Replaces the off-the-shelf `@modelcontextprotocol/server-filesystem` (run via npx)
with a small, self-contained server we own and can reason about. It exposes exactly
the file operations the agent uses, with a two-tier sandbox:

  • READ  is allowed under: workspaces/ , knowledge_base/ , data/
  • WRITE is allowed under: workspaces/ ONLY

so the agent can read the references (knowledge_base, data) and the FpML inputs, but
can only ever create/modify files inside its build sandbox (workspaces/). Any path
outside these roots is refused.

Tools: read_file, write_file, edit_file, mkdir_p, list_directory.
Signatures match what the agent loop / tool_wrappers expect, so it is a drop-in
replacement for the npx server (one fewer external dependency, native recursive
mkdir, no tool-name shadowing).
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from mcp.server.fastmcp import FastMCP


mcp = FastMCP("filesystem")

ROOT = Path(__file__).resolve().parents[2]
READ_ROOTS = [ROOT / "workspaces", ROOT / "knowledge_base", ROOT / "data"]
WRITE_ROOTS = [ROOT / "workspaces"]


def _resolve(path: str, roots: list[Path]) -> Path:
    """Resolve `path` to an absolute path, refusing anything outside `roots`."""
    if not path:
        raise ValueError("empty path")
    p = Path(path).resolve()
    for root in roots:
        try:
            p.relative_to(root.resolve())
            return p
        except ValueError:
            continue
    raise ValueError(f"path {p} is outside the allowed roots: {[str(r) for r in roots]}")


@mcp.tool()
def read_file(path: str) -> str:
    """Read a UTF-8 text file. Allowed under workspaces/, knowledge_base/, data/."""
    try:
        p = _resolve(path, READ_ROOTS)
    except ValueError as e:
        return f"<error>read_file: {e}</error>"
    try:
        return p.read_text(encoding="utf-8")
    except FileNotFoundError:
        return f"<error>read_file: no such file: {p}</error>"
    except OSError as e:
        return f"<error>read_file: {e}</error>"


@mcp.tool()
def read_multiple_files(paths: list[str]) -> str:
    """Read several UTF-8 files in one call. Returns a JSON path→content map.

    Each path is sandbox-checked independently; an unreadable path maps to its
    error string instead of failing the whole call.
    """
    out: dict[str, str] = {}
    for path in paths or []:
        try:
            p = _resolve(path, READ_ROOTS)
            out[path] = p.read_text(encoding="utf-8")
        except FileNotFoundError:
            out[path] = "<error>no such file>"
        except (ValueError, OSError) as e:
            out[path] = f"<error>{e}</error>"
    return json.dumps(out, ensure_ascii=False)


@mcp.tool()
def write_file(path: str, content: str) -> str:
    """Create or overwrite a UTF-8 file under workspaces/. Parent dirs auto-created."""
    try:
        p = _resolve(path, WRITE_ROOTS)
    except ValueError as e:
        return f"<error>write_file: {e}  (writes are only allowed under workspaces/)</error>"
    try:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")
    except OSError as e:
        return f"<error>write_file: {e}</error>"
    return f"ok: wrote {len(content)} chars to {p}"


@mcp.tool()
def edit_file(path: str, edits: list[dict], dryRun: bool = False) -> str:
    """Apply a list of {oldText, newText} replacements to a file under workspaces/.

    Each `oldText` must appear EXACTLY ONCE (add surrounding context to disambiguate),
    otherwise the whole edit is rejected and the file is left untouched.
    """
    try:
        p = _resolve(path, WRITE_ROOTS)
    except ValueError as e:
        return f"<error>edit_file: {e}  (edits are only allowed under workspaces/)</error>"
    if not isinstance(edits, list) or not edits:
        return "<error>edit_file: 'edits' must be a non-empty list of {oldText, newText}</error>"
    try:
        content = p.read_text(encoding="utf-8")
    except OSError as e:
        return f"<error>edit_file: cannot read {p} ({e})</error>"

    new_content = content
    for i, edit in enumerate(edits):
        old = (edit or {}).get("oldText", "")
        new = (edit or {}).get("newText", "")
        if not old:
            return f"<error>edit_file: edits[{i}].oldText is empty</error>"
        n = new_content.count(old)
        if n == 0:
            return f"<error>edit_file: edits[{i}].oldText not found in {p.name}</error>"
        if n > 1:
            return (f"<error>edit_file: edits[{i}].oldText appears {n} times in {p.name}; "
                    "add surrounding context to make it unique</error>")
        new_content = new_content.replace(old, new, 1)

    if dryRun:
        return f"ok (dry-run): {len(edits)} edit(s) would apply cleanly to {p.name}"
    try:
        p.write_text(new_content, encoding="utf-8")
    except OSError as e:
        return f"<error>edit_file: {e}</error>"
    return f"ok: applied {len(edits)} edit(s) to {p.name}"


@mcp.tool()
def mkdir_p(path: str) -> str:
    """Create a directory tree recursively under workspaces/ (idempotent)."""
    try:
        p = _resolve(path, WRITE_ROOTS)
    except ValueError as e:
        return f"<error>mkdir_p: {e}  (only under workspaces/)</error>"
    try:
        p.mkdir(parents=True, exist_ok=True)
    except OSError as e:
        return f"<error>mkdir_p: {e}</error>"
    return f"ok: created {p}"


@mcp.tool()
def list_directory(path: str) -> str:
    """List file and sub-directory names in a directory (read roots)."""
    try:
        p = _resolve(path, READ_ROOTS)
    except ValueError as e:
        return f"<error>list_directory: {e}</error>"
    if not p.is_dir():
        return f"<error>list_directory: not a directory: {p}</error>"
    entries = [f"{e.name}/" if e.is_dir() else e.name for e in sorted(p.iterdir())]
    return json.dumps({"path": str(p), "entries": entries}, ensure_ascii=False)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="filesystem MCP server (sandboxed)")
    parser.add_argument("--transport", default="stdio",
                        choices=["stdio", "streamable-http", "streamablehttp"])
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args()

    transport = args.transport.replace("streamablehttp", "streamable-http")
    if transport == "streamable-http":
        mcp.settings.port = args.port
        mcp.run(transport="streamable-http")
    else:
        mcp.run(transport="stdio")
