"""Ollama backend via its OpenAI-compatible endpoint (default localhost:11434/v1).

No rate limits, runs entirely on the local machine. Slower than cloud backends
(typical ~20-40 t/s for 4B-7B on Apple Silicon) but unlimited and free.
"""
from __future__ import annotations

import os

from openai import OpenAI

from agent.llm_call.LLM_interface import BaseLLM


class OllamaCall(BaseLLM):
    DEFAULT_MODEL = "qwen3.5:4b"

    def __init__(self, model_name: str | None = None, base_url: str | None = None):
        super().__init__()
        self.model_name = model_name or os.getenv("OLLAMA_MODEL") or self.DEFAULT_MODEL
        self.base_url = (
            base_url
            or os.getenv("OLLAMA_BASE_URL")
            or "http://localhost:11434/v1"
        )
        # api_key is ignored by ollama but the OpenAI client requires a non-empty string
        self._client = OpenAI(base_url=self.base_url, api_key="ollama")

    def _is_qwen3(self) -> bool:
        return "qwen3" in self.model_name.lower()

    def _suffix(self, prompt: str) -> str:
        # Qwen3 thinking mode loops on small (4B) checkpoints — disable it.
        if self._is_qwen3() and "/no_think" not in prompt:
            return prompt.rstrip() + "\n\n/no_think"
        return prompt

    def _extract(self, response) -> str:
        msg = response.choices[0].message
        text = (msg.content or "").strip()
        if not text:
            text = (getattr(msg, "reasoning", None) or "").strip()
        return text

    def generate(self, prompt: str) -> str:
        response = self._client.chat.completions.create(
            model=self.model_name,
            messages=[{"role": "user", "content": self._suffix(prompt)}],
            temperature=0.2,
        )
        return self._extract(response)

    def generate_chat(self, messages: list[dict]) -> str:
        msgs = [dict(m) for m in messages]
        if self._is_qwen3():
            for m in reversed(msgs):
                if m.get("role") == "user":
                    m["content"] = self._suffix(m.get("content", ""))
                    break
        response = self._client.chat.completions.create(
            model=self.model_name,
            messages=msgs,
            temperature=0.2,
        )
        return self._extract(response)


if __name__ == "__main__":
    llm = OllamaCall()
    print(llm.generate("In one sentence, what model are you?"))
