"""Context management — token estimation + automatic & manual compaction.

Two triggers fold the conversation:

  1. Automatic — _run_loop calls `maybe_compact(messages, …)` before each LLM
     call. If estimated tokens exceed COMPACT_THRESHOLD, the middle of the
     history is summarised by a follow-up LLM call.

  2. Manual — the model can emit a `compact_context` tool_call. The agent
     intercepts it (it never reaches the MCP layer), runs the same compaction,
     and feeds the result back as a tool_result.

The system message and the last `keep_recent` turns are always preserved
verbatim. Only the middle is replaced by a `[COMPACTED HISTORY]` AIMessage.
"""
from __future__ import annotations

from typing import Awaitable, Callable

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage


# ── Knobs ──────────────────────────────────────────────────────────────────────

COMPACT_THRESHOLD = 50_000   # auto-compact when estimated tokens exceed this
KEEP_RECENT       = 5        # always preserve the N most recent turns verbatim
COOLDOWN_STEPS    = 3        # no compaction within K steps of the last one


# ── Token estimation ──────────────────────────────────────────────────────────
#
# tiktoken is a good proxy for OpenAI/Claude/Mistral (~±10%). For Gemini the
# SentencePiece tokenizer is closer to chars/3 — we apply a rough 1.3× factor
# when the backend is gemini.

_ENC = None


def _get_encoder():
    global _ENC
    if _ENC is None:
        try:
            import tiktoken
            _ENC = tiktoken.encoding_for_model("gpt-4o")
        except Exception:
            _ENC = "fallback"
    return _ENC


def estimate_tokens(messages: list, backend: str = "openai") -> int:
    """Rough token count across a list of LangChain messages."""
    enc = _get_encoder()
    total = 0
    for m in messages:
        content = m.content if hasattr(m, "content") else str(m)
        if enc == "fallback":
            total += max(1, len(str(content)) // 4)
        else:
            total += len(enc.encode(str(content)))
        total += 4  # role + meta overhead
    if backend.lower() == "gemini":
        total = int(total * 1.3)
    return total


# ── Compaction ────────────────────────────────────────────────────────────────

SUMMARY_INSTRUCTION = (
    "Summarize the following autonomous-agent conversation history into a single "
    "dense paragraph (max 800 tokens). Preserve:\n"
    "  • files created/modified with their current state (line counts, key methods)\n"
    "  • errors encountered and how they were fixed (esp. CDM API quirks discovered)\n"
    "  • current build status (compiles? what was the last run_test score and main diffs?)\n"
    "  • key decisions (data shapes chosen, enum mappings, dependency versions)\n"
    "Omit chit-chat, intermediate dead-ends that were reverted, and verbose tool outputs "
    "that have been superseded. Output only the paragraph — no preamble."
)


async def compact_messages(
    messages: list,
    *,
    llm_call: Callable[..., Awaitable[str]],
    keep_recent: int = KEEP_RECENT,
    label: str = "compact",
) -> list:
    """Fold messages[1:-keep_recent] into a single summary AIMessage.

    `llm_call(prompt, **kw) -> str` is injected by the caller (decoupled from
    helpers.py to avoid import cycles).
    """
    if len(messages) <= keep_recent + 2:
        return messages  # nothing meaningful to compact

    system = messages[0]
    tail   = messages[-keep_recent:]
    middle = messages[1:-keep_recent]

    rendered = "\n\n".join(
        f"[{type(m).__name__}]\n{str(m.content)[:3000]}"
        for m in middle
    )
    summary_prompt = [
        SystemMessage(content=SUMMARY_INSTRUCTION),
        HumanMessage(content=rendered),
    ]
    summary = await llm_call(prompt=summary_prompt, max_tokens=1200, label=label)
    summary = (summary or "").strip()
    if not summary:
        summary = f"(compaction produced empty summary; {len(middle)} turns dropped)"

    return [
        system,
        AIMessage(content=f"[COMPACTED HISTORY — {len(middle)} turns summarized]\n{summary}"),
        *tail,
    ]


async def maybe_compact(
    messages: list,
    *,
    backend: str,
    llm_call: Callable[..., Awaitable[str]],
    last_compact_step: int,
    current_step: int,
    label: str = "auto-compact",
    threshold: int = COMPACT_THRESHOLD,
) -> tuple[list, int, int]:
    """Auto-trigger compaction if tokens exceed threshold and cooldown elapsed.

    Returns (messages, last_compact_step, tokens_before).
    """
    tokens = estimate_tokens(messages, backend=backend)
    if tokens <= threshold:
        return messages, last_compact_step, tokens
    if current_step - last_compact_step < COOLDOWN_STEPS:
        return messages, last_compact_step, tokens
    new_messages = await compact_messages(
        messages, llm_call=llm_call, label=label
    )
    return new_messages, current_step, tokens


# ── Tool-call shim for `compact_context` ──────────────────────────────────────
#
# The model can request compaction via a tool_call. The agent loop intercepts
# the name `compact_context` (never reaches MCP) and runs `compact_messages`
# directly, returning a synthetic tool_result.

COMPACT_TOOL_NAME = "compact_context"
COMPACT_TOOL_DESCRIPTION = (
    "Summarize the older portion of your own conversation history into a dense "
    "paragraph to free context space. Keeps the system prompt and your last "
    "`keep_recent` turns untouched. Call this when verbose intermediate tool "
    "results are no longer needed and the context is large."
)
COMPACT_TOOL_INPUT_SCHEMA = {
    "type": "object",
    "properties": {
        "keep_recent": {
            "type": "integer",
            "default": KEEP_RECENT,
            "description": f"How many recent turns to preserve verbatim (default {KEEP_RECENT}).",
        }
    },
}
