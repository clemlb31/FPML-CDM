"""OpenAICompatBackend — the shared trunk for every OpenAI-compatible endpoint.

Drives groq / ollama / vllm / lmstudio / openrouter / qwopus / openai: they all
speak the same `chat.completions.create` wire, so the call, the message wire
conversion, and the tool_call extraction live here ONCE. Per-endpoint differences
(base_url, api_key, model, `extra_body` knobs) are pure config from agent.yaml —
adding such a model is a YAML entry, no code.

A backend needs its own subclass only when it has a CODE quirk (e.g. OllamaBackend
injects `/no_think`). Everything config-shaped stays here.

Reasoning-model note: `extract_openai_tool_calls` already falls back to the
separate `reasoning` field when `content` is empty, so qwopus needs no subclass —
its only "quirk" is handled by the shared extractor.
"""
from __future__ import annotations

import os

import httpx

from agent.llm_call.base import LLMBackend, with_retry
from agent.protocol import (
    LLMResponse,
    extract_openai_tool_calls,
    messages_to_openai_wire,
    tools_to_openai,
)


class OpenAICompatBackend(LLMBackend):
    def __init__(self, *, name: str, params: dict):
        super().__init__(name=name, params=params)
        # base_url: optional env override (movable infra) → YAML default.
        self.base_url = (
            (params.get("base_url_env") and os.getenv(params["base_url_env"]))
            or params.get("base_url")
        )
        # api_key: a literal (e.g. "ollama"/"dummy") OR an env-var name for real secrets.
        self.api_key = (
            params.get("api_key")
            or (params.get("api_key_env") and os.getenv(params["api_key_env"]))
        )
        if not self.api_key:
            raise RuntimeError(
                f"{params.get('api_key_env') or 'API key'} not set for backend "
                f"{name!r} (check .env)"
            )
        self.extra_body = params.get("extra_body")

    # Subclass hook: mutate the wire messages in place before the call (no-op here).
    def _tweak_messages(self, messages: list[dict]) -> None:
        pass

    async def complete(self, *, label, prompt, max_tokens, tools=None, tool_choice=None) -> LLMResponse:
        from openai import AsyncOpenAI

        messages = messages_to_openai_wire(prompt)
        self._tweak_messages(messages)

        client = AsyncOpenAI(
            base_url=self.base_url,
            api_key=self.api_key,
            http_client=httpx.AsyncClient(
                timeout=httpx.Timeout(connect=30.0, read=1800.0, write=30.0, pool=30.0)
            ),
        )
        create_kwargs: dict = dict(
            model=self.model, messages=messages,
            max_tokens=max_tokens, temperature=self.temperature,
        )
        if tools:
            create_kwargs["tools"] = tools_to_openai(tools)
            create_kwargs["tool_choice"] = tool_choice or "auto"
        if self.extra_body is not None:
            create_kwargs["extra_body"] = self.extra_body

        response = await with_retry(
            label=label,
            call=lambda: client.chat.completions.create(**create_kwargs),
        )
        return extract_openai_tool_calls(response)
