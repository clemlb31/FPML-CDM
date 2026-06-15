"""AnthropicBackend — native tool_use via the anthropic SDK.

System prompt is a separate kwarg (not a message); tools use Anthropic's own
schema. The SDK import is lazy so the package only loads when this backend runs.
"""
from __future__ import annotations

import os

from agent.llm_call.base import LLMBackend, with_retry
from agent.protocol import (
    LLMResponse,
    extract_anthropic_tool_calls,
    messages_to_anthropic,
    tools_to_anthropic,
)


class AnthropicBackend(LLMBackend):
    async def complete(self, *, label, prompt, max_tokens, tools=None, tool_choice=None) -> LLMResponse:
        try:
            from anthropic import AsyncAnthropic
        except ImportError:
            raise RuntimeError(
                "anthropic SDK not installed. pip install anthropic, or pick another backend."
            )

        api_key = os.getenv(self.params.get("api_key_env", "ANTHROPIC_API_KEY"))
        if not api_key:
            raise RuntimeError(
                f"{self.params.get('api_key_env', 'ANTHROPIC_API_KEY')} not set (check .env)"
            )
        client = AsyncAnthropic(api_key=api_key)

        messages, system_instruction = messages_to_anthropic(prompt)
        create_kwargs: dict = dict(
            model=self.model, messages=messages,
            max_tokens=max_tokens, temperature=self.temperature,
        )
        if system_instruction:
            create_kwargs["system"] = system_instruction
        if tools:
            create_kwargs["tools"] = tools_to_anthropic(tools)

        response = await with_retry(
            label=label,
            call=lambda: client.messages.create(**create_kwargs),
        )
        return extract_anthropic_tool_calls(response)
