from __future__ import annotations

import os
import requests
from dotenv import load_dotenv

load_dotenv()




class LLM:
    """Unified LLM class to be overridden by specific backend implementations.

    
    Usage:

    """

    def __init__(
        self,
    ):
        return  

    # ── Public API ────────────────────────────────────────────────────────────
    def generate(self, prompt: str) -> str:
        """Single stateless prompt → response. Used b y the agent."""
        pass

    def generate_with_mcp(self, prompt: str) -> str:
        """Generate and strip <think> tags (for reasoning models like Qwen3)."""
        response = self.generate(prompt)
        if "</think>" in response:
            response = response.split("</think>")[-1].strip()
        return response



if __name__ == "__main__":
    with LLM(backend="lmstudio", model_name="qwen/qwen3.5-9b") as llm:
        print(llm.generate("Who are you? Short answer."))
