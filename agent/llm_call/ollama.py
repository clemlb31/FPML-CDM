"""OllamaBackend — OpenAI-compatible, plus the one Ollama code quirk.

For Qwen3 served by Ollama, the model keeps "thinking" unless nudged: we append
`/no_think` to the last user message. The `think: false` knob is config (set via
`extra_body` in agent.yaml), so only the message nudge needs code.
"""
from __future__ import annotations

from agent.llm_call.openai_compat import OpenAICompatBackend


class OllamaBackend(OpenAICompatBackend):
    def _tweak_messages(self, messages: list[dict]) -> None:
        if "qwen3" not in (self.model or "").lower():
            return
        for m in reversed(messages):
            if m["role"] == "user" and "/no_think" not in (m.get("content") or ""):
                m["content"] = (m["content"] or "").rstrip() + "\n\n/no_think"
                break
