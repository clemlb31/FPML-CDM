"""LLMBackend — the parent class every model backend inherits.

One contract for the agent loop:

    resp: LLMResponse = await backend.complete(
        label=…, prompt=[messages…], max_tokens=…, tools=<registry|None>, tool_choice=…)

`tools` is the NEUTRAL tool registry — `{name: {"description", "input_schema"}}`
(exactly what `tools_registry.schema_registry()` returns). Each subclass converts
it to its provider's wire format; pass `None` to disable tool use.

What the parent gives every backend:
  • `name`   — the active backend key (also drives token estimation in context.py)
  • `model`  — resolved model id (YAML default, overridable by its `model_env`)
  • `uses_native_tools` — capability flag the loop reads to pick native vs XML mode
  • `with_retry(...)` — shared transient-error retry/backoff around the SDK call

What each subclass owns (the per-provider specifics):
  • message wire conversion, the SDK call itself, and tool_call extraction.

Add a backend = add a subclass overriding `complete()` (or, for an OpenAI-compatible
endpoint with no code quirk, just a YAML entry under `kind: openai_compat`).
"""
from __future__ import annotations

import asyncio
import os
import random
import re
from abc import ABC, abstractmethod

from agent.protocol import LLMResponse


class LLMBackend(ABC):
    # Capability flag the agent loop branches on. Native-tool backends return
    # structured tool_calls from the provider API; `manual` overrides this to
    # False and falls back to XML-in-text parsing.
    uses_native_tools: bool = True

    def __init__(self, *, name: str, params: dict):
        self.name = name                       # active backend key (token-estimation hint too)
        self.params = params                   # resolved config: defaults merged with this backend's entry
        self.temperature = params.get("temperature", 0.2)
        # Model id: YAML `model`, overridable by the backend's `model_env` (movable
        # infra / model pinning kept in .env). `manual` has no model → None.
        self.model = os.getenv(params.get("model_env", ""), "") or params.get("model")

    @abstractmethod
    async def complete(
        self,
        *,
        label: str,
        prompt: list,
        max_tokens: int,
        tools: dict | None = None,
        tool_choice: str | None = None,
    ) -> LLMResponse:
        """Call the model and return a unified LLMResponse (text + tool_calls + done)."""
        ...


# ── Shared transient-error retry / backoff ──────────────────────────────────────
# Moved verbatim from helpers._with_retry — used by every SDK-based backend.

async def with_retry(*, label: str, call):
    """Retry an async callable on transient HTTP errors with backoff."""
    from openai import APIConnectionError, APIError, APITimeoutError, RateLimitError
    last_exc: Exception | None = None
    status = None
    for attempt in range(6):
        try:
            return await call()
        except (RateLimitError, APIConnectionError, APITimeoutError) as e:
            last_exc = e
            status = getattr(e, "status_code", None) or 429
        except APIError as e:
            last_exc = e
            status = getattr(e, "status_code", None)
            if status not in (413, 429, 500, 502, 503, 504):
                raise
        except Exception as e:
            # Catch SDK-specific errors (anthropic, google) that don't inherit openai.APIError.
            # We treat status hinted in the message as 429 by default.
            msg = str(e).lower()
            if any(s in msg for s in ("429", "rate limit", "resource_exhausted",
                                       "overload", "503", "502", "504", "timeout")):
                last_exc = e
                status = 429
            else:
                raise
        if attempt == 5:
            break
        msg_text = str(last_exc)
        m = re.search(r"retry(?:Delay|-after)?[\":\s]+(\d+(?:\.\d+)?)\s*s", msg_text, re.IGNORECASE)
        hint = float(m.group(1)) if m else 0.0
        if status in (413, 429):
            delay = max(hint + 2.0, 65.0)
        else:
            delay = (2 ** attempt) + random.uniform(0, 1)
        print(
            f"[{label}] LLM transient error ({type(last_exc).__name__} {status}); "
            f"retry {attempt + 1}/5 in {delay:.1f}s"
        )
        await asyncio.sleep(delay)

    raise RuntimeError(f"[{label}] LLM failed after retries: {last_exc}")
