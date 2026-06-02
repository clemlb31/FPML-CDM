"""Gemini backend via Google AI Studio (free tier).

Free tier quotas for gemini-2.5-flash: 15 RPM, 1M tokens/day.
Get / rotate API keys at: https://aistudio.google.com/apikey
"""
from __future__ import annotations

import os

from agent.llm_call.LLM_interface import BaseLLM


class GeminiCall(BaseLLM):
    DEFAULT_MODEL = "gemini-2.5-flash"

    def __init__(self, model_name: str | None = None, api_key: str | None = None):
        super().__init__()
        self.model_name = model_name or os.getenv("GEMINI_MODEL") or self.DEFAULT_MODEL
        api_key = api_key or os.getenv("GEMINI_API_KEY")
        if not api_key:
            raise ValueError(
                "GEMINI_API_KEY not set. Add it to .env or pass api_key=..."
            )
        # Lazy import so the rest of the codebase doesn't need google-genai installed.
        from google import genai
        self._client = genai.Client(api_key=api_key)

    def generate(self, prompt: str) -> str:
        response = self._client.models.generate_content(
            model=self.model_name,
            contents=prompt,
        )
        return (response.text or "").strip()

    def generate_json(self, prompt: str) -> str:
        """Generate with JSON output enforced via response_mime_type."""
        from google.genai import types
        response = self._client.models.generate_content(
            model=self.model_name,
            contents=prompt,
            config=types.GenerateContentConfig(response_mime_type="application/json"),
        )
        return (response.text or "").strip()

    def generate_chat(self, messages: list[dict]) -> str:
        """Multi-turn: map OpenAI-style messages to Gemini contents."""
        system_parts = [m["content"] for m in messages if m.get("role") == "system"]
        contents = []
        for m in messages:
            role = m.get("role")
            if role == "system":
                continue
            gemini_role = "user" if role == "user" else "model"
            contents.append({"role": gemini_role, "parts": [{"text": m["content"]}]})

        from google.genai import types
        config = None
        if system_parts:
            config = types.GenerateContentConfig(
                system_instruction="\n".join(system_parts)
            )

        response = self._client.models.generate_content(
            model=self.model_name,
            contents=contents,
            config=config,
        )
        return (response.text or "").strip()


if __name__ == "__main__":
    llm = GeminiCall()
    print(llm.generate("In one sentence, what model are you?"))
