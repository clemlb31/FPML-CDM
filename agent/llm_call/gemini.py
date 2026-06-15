"""GeminiBackend — native tool_use via the google-genai SDK.

If google-genai isn't installed, falls back to Gemini's OpenAI-compatibility
endpoint through OpenAICompatBackend — but that path has no native tools, so the
fallback disables tool use (matches the prior helpers.py behaviour exactly).
"""
from __future__ import annotations

import asyncio
import os

from agent.llm_call.base import LLMBackend, with_retry
from agent.llm_call.openai_compat import OpenAICompatBackend
from agent.protocol import (
    LLMResponse,
    extract_gemini_tool_calls,
    messages_to_gemini,
    tools_to_gemini,
)

# Gemini's OpenAI-compatible base URL, used only for the SDK-missing fallback.
_GEMINI_OPENAI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai/"


class GeminiBackend(LLMBackend):
    async def complete(self, *, label, prompt, max_tokens, tools=None, tool_choice=None) -> LLMResponse:
        try:
            from google import genai
            from google.genai import types
        except ImportError:
            # Fallback: OpenAI-compat endpoint (no native tools there).
            fallback = OpenAICompatBackend(
                name=self.name,
                params={**self.params, "base_url": _GEMINI_OPENAI_BASE_URL},
            )
            return await fallback.complete(
                label=label, prompt=prompt, max_tokens=max_tokens, tools=None,
            )

        api_key = os.getenv(self.params.get("api_key_env", "GEMINI_API_KEY"))
        if not api_key:
            raise RuntimeError(
                f"{self.params.get('api_key_env', 'GEMINI_API_KEY')} not set (check .env)"
            )
        client = genai.Client(api_key=api_key)

        contents, system_instruction = messages_to_gemini(prompt)
        cfg_kwargs: dict = {"max_output_tokens": max_tokens, "temperature": self.temperature}
        if system_instruction:
            cfg_kwargs["system_instruction"] = system_instruction
        if tools:
            cfg_kwargs["tools"] = tools_to_gemini(tools)
        config = types.GenerateContentConfig(**cfg_kwargs)

        response = await with_retry(
            label=label,
            call=lambda: asyncio.to_thread(
                client.models.generate_content,
                model=self.model, contents=contents, config=config,
            ),
        )
        return extract_gemini_tool_calls(response)
