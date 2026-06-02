"""Unified LLM factory + base class.

Usage:
    from agent.llm_call.LLM_interface import LLM

    with LLM(backend="gemini") as llm:
        print(llm.generate("Hello"))

Backends: gemini | lmstudio | vllm | copilot
Backend resolution order: explicit arg > $LLM_BACKEND > "gemini".
"""
from __future__ import annotations

import os
from dotenv import load_dotenv

load_dotenv()


COPILOT_MODELS = [
    "openai/gpt-4o",
    "openai/gpt-4o-mini",
    "openai/gpt-4.1",
    "openai/gpt-4.1-mini",
    "openai/o4-mini",
    "openai/o3",
    "meta/llama-4-scout",
    "meta/llama-4-maverick",
    "mistral-ai/mistral-large-2411",
    "deepseek/deepseek-v3-0324",
    "deepseek/deepseek-r1",
    "xai/grok-3",
    "xai/grok-3-mini",
    "microsoft/phi-4",
    "cohere/cohere-command-r-plus-08-2024",
]


class BaseLLM:
    """Base class. Subclasses must override `generate()`."""

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        return False

    def generate(self, prompt: str) -> str:
        raise NotImplementedError

    def generate_chat(self, messages: list[dict]) -> str:
        """Multi-turn fallback: flatten to a single labelled prompt."""
        prompt = "\n".join(f"{m['role'].upper()}: {m['content']}" for m in messages)
        return self.generate(prompt)

    def generate_with_mcp(self, prompt: str) -> str:
        """Strip <think>...</think> blocks emitted by reasoning models."""
        response = self.generate(prompt)
        if "</think>" in response:
            response = response.split("</think>")[-1].strip()
        return response


def LLM(backend: str | None = None, **kwargs) -> BaseLLM:
    """Factory. Returns a concrete backend instance."""
    backend = (backend or os.getenv("LLM_BACKEND") or "gemini").lower()

    if backend == "gemini":
        from agent.llm_call.gemini_call import GeminiCall
        return GeminiCall(**kwargs)
    if backend == "groq":
        from agent.llm_call.groq_call import GroqCall
        return GroqCall(**kwargs)
    if backend == "ollama":
        from agent.llm_call.ollama_call import OllamaCall
        return OllamaCall(**kwargs)
    if backend == "lmstudio":
        from agent.llm_call.LMstudio_call import LMstudio_call
        return LMstudio_call(**kwargs)
    if backend == "vllm":
        from agent.llm_call.vllm_call import Vllm_call
        return Vllm_call(**kwargs)
    if backend == "copilot":
        from agent.llm_call.copilot_call import Copilote_call
        return Copilote_call(**kwargs)

    raise ValueError(
        f"Unknown LLM backend: {backend!r}. "
        f"Expected one of: gemini, groq, ollama, lmstudio, vllm, copilot."
    )


if __name__ == "__main__":
    with LLM() as llm:
        print(llm.generate("Who are you in one short sentence?"))
