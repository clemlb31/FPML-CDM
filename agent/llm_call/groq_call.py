"""Groq backend via api.groq.com (free tier).

Free tier (Llama 3.3 70B): ~30 RPM, ~14400 TPM, ~1000 RPD.
Inference latency is very low (~500 t/s) — well suited to agent loops.
Get API keys at: https://console.groq.com/keys
"""
from __future__ import annotations

import os

from openai import OpenAI

from agent.llm_call.LLM_interface import BaseLLM


class GroqCall(BaseLLM):
    DEFAULT_MODEL = "llama-3.3-70b-versatile"
    BASE_URL = "https://api.groq.com/openai/v1"

    def __init__(self, model_name: str | None = None, api_key: str | None = None):
        super().__init__()
        self.model_name = model_name or os.getenv("GROQ_MODEL") or self.DEFAULT_MODEL
        api_key = api_key or os.getenv("GROQ_API_KEY")
        if not api_key:
            raise ValueError("GROQ_API_KEY not set. Add it to .env or pass api_key=...")
        self._client = OpenAI(base_url=self.BASE_URL, api_key=api_key)

    def generate(self, prompt: str) -> str:
        response = self._client.chat.completions.create(
            model=self.model_name,
            messages=[{"role": "user", "content": prompt}],
            temperature=0.2,
        )
        return (response.choices[0].message.content or "").strip()

    def generate_chat(self, messages: list[dict]) -> str:
        response = self._client.chat.completions.create(
            model=self.model_name,
            messages=messages,
            temperature=0.2,
        )
        return (response.choices[0].message.content or "").strip()


if __name__ == "__main__":
    llm = GroqCall()
    print(llm.generate("In one sentence, what model are you?"))
