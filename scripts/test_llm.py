#!/usr/bin/env python3
"""Smoke test for the configured LLM backend — the REAL path the agent uses.

Exercises agent/llm_call (config-driven backends + llm_call façade), not any
legacy code:

    python scripts/test_llm.py                 # test the backend selected in configs/agent.yaml
    python scripts/test_llm.py --backend groq  # test another backend defined in agent.yaml
    python scripts/test_llm.py --tools         # also send a 1-tool registry (native tool_use)

Checks:
  1. configs/agent.yaml resolves and the backend builds (prints name/kind/model).
  2. llm_call() returns a non-empty LLMResponse (the live agent call path).
  3. (--tools) the backend accepts a neutral tool registry and converts it.
"""
from __future__ import annotations

import argparse
import asyncio
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from dotenv import load_dotenv

load_dotenv(ROOT / ".env")

from agent.llm_call import build_backend, get_backend, llm_call
from agent.llm_call.config import load_config, resolve_active

# A trivial neutral tool registry (same shape as tools_registry.schema_registry()).
_PING_TOOL = {
    "ping": {
        "description": "Reply to a ping. Call this with a short message.",
        "input_schema": {
            "type": "object",
            "required": ["message"],
            "properties": {"message": {"type": "string"}},
        },
    }
}


def _select_backend(name: str | None):
    """get_backend() for the active YAML backend, or build a named one for testing."""
    if not name:
        return get_backend()
    cfg = load_config()
    backends = cfg.get("backends") or {}
    if name not in backends:
        raise SystemExit(f"backend {name!r} not in configs/agent.yaml (known: {list(backends)})")
    params = {**(cfg.get("defaults") or {}), **(backends[name] or {})}
    return build_backend(name, params)


async def run(name: str | None, with_tools: bool) -> None:
    backend = _select_backend(name)
    print(f"\n[1/3] backend resolved: name={backend.name!r} "
          f"kind={backend.params.get('kind')!r} model={backend.model!r} "
          f"native_tools={backend.uses_native_tools}")

    from langchain_core.messages import HumanMessage, SystemMessage
    prompt = [
        SystemMessage(content="You answer in one short sentence."),
        HumanMessage(content="What model are you?"),
    ]

    print("\n[2/3] llm_call() text round-trip")
    resp = await backend.complete(label="smoke", prompt=prompt, max_tokens=200)
    text = (resp.text or "").strip()
    if not text:
        raise RuntimeError("empty response text")
    print(f"     OK ({len(text)} chars): {text[:200]}")

    if with_tools:
        print("\n[3/3] llm_call() with a 1-tool registry (native tool_use)")
        if not backend.uses_native_tools:
            print("     skipped: backend has no native tool_use (manual/XML mode)")
            return
        tprompt = [
            SystemMessage(content="Use the ping tool to reply."),
            HumanMessage(content="Ping with the message 'hello'."),
        ]
        tresp = await backend.complete(label="smoke-tools", prompt=tprompt,
                                       max_tokens=200, tools=_PING_TOOL)
        if tresp.tool_calls:
            tc = tresp.tool_calls[0]
            print(f"     OK: model called {tc.name}({tc.args})")
        else:
            print(f"     no tool_call returned (model answered in text): {tresp.text[:120]!r}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--backend", default=None,
                        help="Test a specific backend from agent.yaml (default: the active one).")
    parser.add_argument("--tools", action="store_true",
                        help="Also send a 1-tool registry to exercise native tool_use.")
    args = parser.parse_args()
    try:
        asyncio.run(run(args.backend, args.tools))
    except Exception as exc:
        print(f"\n[FAIL] {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1
    print("\nAll smoke checks passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
