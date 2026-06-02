#!/usr/bin/env python3
"""Smoke test du backend LLM configuré.

Usage:
    python scripts/test_llm.py [--backend gemini|lmstudio|vllm]
    python scripts/test_llm.py --via-helpers   # teste aussi helpers.llm_text_or_raise

Vérifie:
  1. Que le backend factory répond
  2. Que helpers.llm_text_or_raise répond (chemin utilisé par le graph)
  3. Que le mode JSON marche (Gemini only)
"""
from __future__ import annotations

import argparse
import asyncio
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


def test_factory(backend: str | None) -> None:
    from agent.llm_call.LLM_interface import LLM

    print(f"\n[1/3] Factory LLM(backend={backend!r}) → generate()")
    with LLM(backend=backend) as llm:
        resp = llm.generate("In one short sentence, what model are you?")
    print(f"     OK ({len(resp)} chars): {resp[:200]}")


def test_factory_json() -> None:
    """Gemini-only: vérifie que response_mime_type=application/json marche."""
    from agent.llm_call.gemini_call import GeminiCall

    print("\n[2/3] GeminiCall.generate_json()")
    llm = GeminiCall()
    resp = llm.generate_json(
        'Return a JSON object with keys "ok" (bool) and "model" (string). Nothing else.'
    )
    print(f"     OK ({len(resp)} chars): {resp[:200]}")


async def test_via_helpers() -> None:
    """Chemin réel utilisé par le LangGraph (helpers.llm_text_or_raise)."""
    from langchain_core.messages import HumanMessage, SystemMessage

    from agent.helpers import llm_text_or_raise

    print("\n[3/3] helpers.llm_text_or_raise()  (= chemin utilisé par graph.py)")
    text = await llm_text_or_raise(
        project_dir=str(ROOT / "workspaces" / "current_project"),
        label="smoke-test",
        prompt=[
            SystemMessage(content="You answer in one short sentence."),
            HumanMessage(content="What model are you?"),
        ],
        max_tokens=100,
    )
    print(f"     OK ({len(text)} chars): {text[:200]}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--backend",
        default=None,
        help="Override LLM_BACKEND for this test (gemini/lmstudio/vllm).",
    )
    parser.add_argument(
        "--skip-json",
        action="store_true",
        help="Skip the JSON mode test (Gemini only).",
    )
    parser.add_argument(
        "--skip-helpers",
        action="store_true",
        help="Skip the helpers.llm_text_or_raise test.",
    )
    args = parser.parse_args()

    try:
        test_factory(args.backend)
        if not args.skip_json and (args.backend or "gemini") == "gemini":
            test_factory_json()
        if not args.skip_helpers:
            asyncio.run(test_via_helpers())
    except Exception as exc:
        print(f"\n[FAIL] {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1

    print("\nAll smoke checks passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
