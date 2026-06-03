"""Grep MCP server — content search across the workspace via ripgrep.

The official @modelcontextprotocol/server-filesystem only does filename matching
(`search_files`). This server fills the gap so the agent can grep for symbols,
strings, etc. across many files at once — much faster than read_file scans.

Falls back to Python's built-in walker when `rg` is not on PATH (Mac default).
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
from pathlib import Path

from mcp.server.fastmcp import FastMCP


mcp = FastMCP("grep")

# Roots the server is allowed to read from.
# Mirrors what the filesystem MCP exposes — adjust here if those roots change.
ROOT = Path(__file__).resolve().parents[2]
ALLOWED_ROOTS = [
    ROOT / "workspaces",
    ROOT / "knowledge_base",
    ROOT / "data",
    ROOT / "agent",
    ROOT / "mcp_servers",
]


def _resolve_path(path: str) -> Path:
    """Return absolute path, refusing anything outside ALLOWED_ROOTS."""
    p = Path(path).resolve() if path else ROOT
    for root in ALLOWED_ROOTS:
        try:
            p.relative_to(root.resolve())
            return p
        except ValueError:
            continue
    raise ValueError(
        f"path {p} is outside the allowed roots: "
        f"{[str(r) for r in ALLOWED_ROOTS]}"
    )


def _rg_available() -> bool:
    return shutil.which("rg") is not None


def _grep_via_ripgrep(pattern: str, path: Path, include: str | None, max_results: int) -> dict:
    cmd = ["rg", "--line-number", "--no-heading", "--with-filename",
           "--color=never", "--max-count", str(max_results), pattern, str(path)]
    if include:
        cmd[1:1] = ["-g", include]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": "ripgrep timed out after 15s"}

    matches = []
    for line in proc.stdout.splitlines()[:max_results]:
        # rg format: <path>:<lineno>:<content>
        m = re.match(r"^([^:]+):(\d+):(.*)$", line)
        if not m:
            continue
        matches.append({
            "file": m.group(1),
            "line": int(m.group(2)),
            "text": m.group(3),
        })
    return {
        "ok":        True,
        "tool":      "ripgrep",
        "matches":   matches,
        "truncated": len(matches) >= max_results,
        "stderr":    proc.stderr[:400] if proc.returncode > 1 else "",
    }


def _grep_via_python(pattern: str, path: Path, include: str | None, max_results: int) -> dict:
    """Fallback: pure-Python regex walker. Slower but no dependency."""
    try:
        rx = re.compile(pattern)
    except re.error as e:
        return {"ok": False, "error": f"invalid regex: {e}"}

    glob_pat = include or "**/*"
    matches: list[dict] = []
    files = path.rglob(glob_pat) if path.is_dir() else [path]
    for f in files:
        if len(matches) >= max_results:
            break
        if not f.is_file():
            continue
        try:
            with f.open("r", encoding="utf-8", errors="ignore") as fp:
                for lineno, line in enumerate(fp, 1):
                    if rx.search(line):
                        matches.append({
                            "file": str(f),
                            "line": lineno,
                            "text": line.rstrip("\n"),
                        })
                        if len(matches) >= max_results:
                            break
        except OSError:
            continue
    return {
        "ok":        True,
        "tool":      "python-fallback",
        "matches":   matches,
        "truncated": len(matches) >= max_results,
    }


@mcp.tool()
def grep(
    pattern: str,
    path: str = "",
    include: str | None = None,
    max_results: int = 50,
) -> str:
    """Search file contents recursively for a regex pattern.

    Args:
        pattern:     Regex pattern (PCRE-style for ripgrep, Python re for fallback).
        path:        Directory or file to search under. Defaults to repo root.
        include:     Glob filter (e.g. '*.java'). Optional.
        max_results: Cap on matches returned (default 50).

    Returns JSON with: ok, tool, matches[{file, line, text}], truncated.
    """
    try:
        target = _resolve_path(path)
    except ValueError as e:
        return json.dumps({"ok": False, "error": str(e)})

    if _rg_available():
        result = _grep_via_ripgrep(pattern, target, include, max_results)
    else:
        result = _grep_via_python(pattern, target, include, max_results)
    return json.dumps(result, ensure_ascii=False)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="grep MCP server (ripgrep wrapper)")
    parser.add_argument("--transport", default="stdio",
                        choices=["stdio", "streamable-http", "streamablehttp"])
    parser.add_argument("--port", type=int, default=8005)
    args = parser.parse_args()

    transport = args.transport.replace("streamablehttp", "streamable-http")
    if transport == "streamable-http":
        mcp.settings.port = args.port
        mcp.run(transport="streamable-http")
    else:
        mcp.run(transport="stdio")
