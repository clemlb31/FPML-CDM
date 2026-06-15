"""ManualBackend — no model. An external operator acts as the LLM.

Each prompt is written to `.llm_io/inbox/<id>.json`; the backend polls
`.llm_io/outbox/<id>.txt` until an operator (Claude in chat, a sub-agent, or a
human) writes the reply, then archives both and returns the text. The reply is
parsed as XML-in-text — there is no native tool_use here — so this backend sets
`uses_native_tools = False` and the agent loop drives it in XML mode.
"""
from __future__ import annotations

import asyncio
import json
import re
from pathlib import Path

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage

from agent.llm_call.base import LLMBackend
from agent.protocol import LLMResponse, parse_xml_response

_LLM_IO_DIR = Path(__file__).resolve().parents[2] / ".llm_io"


class ManualBackend(LLMBackend):
    uses_native_tools = False

    async def complete(self, *, label, prompt, max_tokens, tools=None, tool_choice=None) -> LLMResponse:
        text = await self._exchange(label, prompt, max_tokens)
        return parse_xml_response(text)

    async def _exchange(
        self,
        label: str,
        prompt: list,
        max_tokens: int,
        poll_interval: float = 1.0,
        timeout_seconds: float = 30 * 60,
    ) -> str:
        """Drop the prompt in an inbox file, wait for an outbox file, return its text."""
        inbox = _LLM_IO_DIR / "inbox"
        outbox = _LLM_IO_DIR / "outbox"
        history = _LLM_IO_DIR / "history"
        for d in (inbox, outbox, history):
            d.mkdir(parents=True, exist_ok=True)

        serialised: list[dict] = []
        for m in prompt:
            if isinstance(m, SystemMessage):
                serialised.append({"role": "system",    "content": m.content})
            elif isinstance(m, HumanMessage):
                serialised.append({"role": "user",      "content": m.content})
            elif isinstance(m, AIMessage):
                serialised.append({"role": "assistant", "content": m.content})
            elif isinstance(m, dict):
                serialised.append(m)
            else:
                serialised.append({"role": "user", "content": str(m)})

        safe_label = re.sub(r"[^a-zA-Z0-9_-]+", "_", label)
        ts = int(asyncio.get_event_loop().time() * 1000)
        req_id = f"{ts}_{safe_label}"
        req_file = inbox / f"{req_id}.json"
        resp_file = outbox / f"{req_id}.txt"

        payload = {
            "id":         req_id,
            "label":      label,
            "max_tokens": max_tokens,
            "messages":   serialised,
        }
        req_file.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"[{label}] manual: waiting on .llm_io/outbox/{resp_file.name}")

        deadline = asyncio.get_event_loop().time() + timeout_seconds
        while asyncio.get_event_loop().time() < deadline:
            if resp_file.exists():
                text = resp_file.read_text(encoding="utf-8")
                try:
                    (history / f"{req_id}.req.json").write_text(
                        req_file.read_text(encoding="utf-8"), encoding="utf-8")
                    (history / f"{req_id}.resp.txt").write_text(text, encoding="utf-8")
                except OSError:
                    pass
                req_file.unlink(missing_ok=True)
                resp_file.unlink(missing_ok=True)
                text = text.strip()
                if not text:
                    raise RuntimeError(f"[{label}] manual: empty response file")
                return text
            await asyncio.sleep(poll_interval)

        raise RuntimeError(
            f"[{label}] manual: timed out after {timeout_seconds:.0f}s. "
            f"Expected operator to write {resp_file}"
        )
