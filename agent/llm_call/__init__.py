"""Public LLM API for the agent.

    from agent.llm_call import llm_call, get_backend

    resp = await llm_call(label=…, prompt=[…], max_tokens=…, tools=<registry|None>)

`get_backend()` builds the backend selected in configs/agent.yaml once (cached for
the process — same instance shared by the main loop and sub-agents). `llm_call(...)`
is a thin façade delegating to `get_backend().complete(...)`, so call sites stay
unchanged while the class hierarchy lives underneath.

Adding a backend:
  • OpenAI-compatible endpoint, no code quirk → just a YAML entry, `kind: openai_compat`.
  • New API shape or a code quirk → add a subclass and register its `kind` in `_KINDS`.
"""
from __future__ import annotations

from agent.llm_call.anthropic import AnthropicBackend
from agent.llm_call.base import LLMBackend
from agent.llm_call.config import load_config, resolve_active
from agent.llm_call.gemini import GeminiBackend
from agent.llm_call.manual import ManualBackend
from agent.llm_call.ollama import OllamaBackend
from agent.llm_call.openai_compat import OpenAICompatBackend
from agent.protocol import LLMResponse

# kind (from agent.yaml) → backend class.
_KINDS: dict[str, type[LLMBackend]] = {
    "openai_compat": OpenAICompatBackend,
    "ollama":        OllamaBackend,
    "anthropic":     AnthropicBackend,
    "gemini":        GeminiBackend,
    "manual":        ManualBackend,
}

_BACKEND: LLMBackend | None = None


def build_backend(name: str, params: dict) -> LLMBackend:
    kind = params.get("kind")
    cls = _KINDS.get(kind)
    if cls is None:
        raise RuntimeError(
            f"agent.yaml backend {name!r}: unknown kind {kind!r}. "
            f"Known kinds: {list(_KINDS)}"
        )
    return cls(name=name, params=params)


def get_backend(*, reload: bool = False) -> LLMBackend:
    """Return the process-wide backend selected in configs/agent.yaml (cached)."""
    global _BACKEND
    if _BACKEND is None or reload:
        name, params = resolve_active(load_config())
        _BACKEND = build_backend(name, params)
    return _BACKEND


async def llm_call(
    *,
    label: str,
    prompt: list,
    max_tokens: int = 6000,
    tools: dict | None = None,
    tool_choice: str | None = None,
) -> LLMResponse:
    """Call the configured LLM and return a unified LLMResponse.

    `tools` is the neutral tool registry (`schema_registry(...)` shape) or None to
    disable tool use. The selected backend converts it to its own wire format.
    """
    return await get_backend().complete(
        label=label, prompt=prompt, max_tokens=max_tokens,
        tools=tools, tool_choice=tool_choice,
    )


__all__ = ["llm_call", "get_backend", "build_backend", "LLMBackend", "LLMResponse"]
