#!/usr/bin/env python3
"""Emit a `<tool_call>` block for the autonomous agent that creates a file
via the filesystem MCP `write_file` tool.

Usage:
    python scripts/emit_write_call.py <source> [<target>]
    python scripts/emit_write_call.py <s1> <t1> -- <s2> <t2> -- ...

If <target> is omitted, the source path is used as the target. Each --
delimits another (source, target) pair so multiple files can be emitted in
a single invocation (paste the result into one outbox file).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path


def emit(source: Path, target: str) -> str:
    content = source.read_text(encoding="utf-8")
    payload = {"name": "write_file", "args": {"path": target, "content": content}}
    return "<tool_call>\n" + json.dumps(payload, ensure_ascii=False) + "\n</tool_call>"


def parse_args(args: list[str]) -> list[tuple[Path, str]]:
    groups: list[list[str]] = [[]]
    for a in args:
        if a == "--":
            groups.append([])
        else:
            groups[-1].append(a)
    out: list[tuple[Path, str]] = []
    for g in groups:
        if not g:
            continue
        if len(g) == 1:
            src = Path(g[0])
            out.append((src, str(src.resolve())))
        elif len(g) == 2:
            out.append((Path(g[0]), g[1]))
        else:
            print(f"ERROR: too many args in group {g!r}", file=sys.stderr)
            sys.exit(2)
    return out


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    pairs = parse_args(sys.argv[1:])
    if not pairs:
        print(__doc__, file=sys.stderr)
        return 2
    blocks = []
    for src, tgt in pairs:
        if not src.is_file():
            print(f"ERROR: {src} is not a file", file=sys.stderr)
            return 1
        blocks.append(emit(src, tgt))
    print("\n".join(blocks))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
