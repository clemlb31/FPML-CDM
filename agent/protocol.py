"""Tool-call protocol — abstracts XML-in-text vs native tool_use APIs.

Two paths, one interface:
  • XML mode    — model emits <tool_call>{...}</tool_call> blocks in plain text.
                  Used by the `manual` backend (file-based inbox/outbox) and as
                  fallback for any backend without tool support.
  • Native mode — model returns structured tool_calls via the provider's API
                  (OpenAI tools=[…], Anthropic tools=[…], Gemini function_declarations).
                  No JSON-escape failures, no parsing errors.

Either way the agent loop sees a unified `LLMResponse(text, tool_calls, done)`.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Any

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage


# ── Data types ─────────────────────────────────────────────────────────────────

@dataclass
class ToolCall:
    name: str
    args: dict
    call_id: str | None = None   # provider-assigned id (for native tool_use turn-pairing)

    def to_dict(self) -> dict:
        return {"name": self.name, "args": self.args}


@dataclass
class LLMResponse:
    text: str                                # raw assistant text (may be empty in native mode)
    tool_calls: list[ToolCall] = field(default_factory=list)
    done: str | None = None                  # <done>...</done> summary, if any
    raw: Any = None                          # original SDK response (for debug)


# ── XML protocol (manual backend + fallback) ───────────────────────────────────

_TOOL_CALL_RE = re.compile(r"<tool_call>\s*(\{.*?\})\s*</tool_call>", re.DOTALL)
_DONE_RE      = re.compile(r"<done>(.*?)</done>", re.DOTALL)


def parse_xml_response(text: str) -> LLMResponse:
    """Extract tool_calls + optional <done> from a model's plain-text response."""
    done_match = _DONE_RE.search(text)
    done = done_match.group(1).strip() if done_match else None

    calls: list[ToolCall] = []
    for m in _TOOL_CALL_RE.finditer(text):
        try:
            obj = json.loads(m.group(1))
        except json.JSONDecodeError as e:
            calls.append(ToolCall(
                name="_parse_error",
                args={"raw": m.group(1)[:400], "error": str(e)},
            ))
            continue
        if isinstance(obj, dict) and "name" in obj and "args" in obj:
            calls.append(ToolCall(name=obj["name"], args=obj["args"] or {}))
        else:
            calls.append(ToolCall(
                name="_parse_error",
                args={"raw": str(obj)[:400], "error": "missing name/args"},
            ))

    return LLMResponse(text=text, tool_calls=calls, done=done)


def format_xml_results(results: list[tuple[ToolCall, str]]) -> str:
    """Render tool results as <tool_result …> blocks for the next user turn."""
    parts = []
    for idx, (call, output) in enumerate(results):
        is_error = output.startswith("<error>") or "<error>" in output[:50]
        err_attr = ' error="true"' if is_error else ""
        parts.append(
            f'<tool_result name="{call.name}" idx="{idx}"{err_attr}>\n'
            f'{output}\n'
            f'</tool_result>'
        )
    return "\n".join(parts)


# ── Native tool_use schema converters ──────────────────────────────────────────
#
# All converters take a registry shape:
#   {tool_name: {"description": str, "input_schema": <JSON Schema dict>}}
# and emit the provider-specific tools= payload.

def tools_to_openai(registry: dict[str, dict]) -> list[dict]:
    """OpenAI / Groq / Ollama / vLLM / LM Studio / Mistral / Together — all the same shape."""
    return [
        {
            "type": "function",
            "function": {
                "name": name,
                "description": spec.get("description", ""),
                "parameters": spec.get("input_schema") or {"type": "object", "properties": {}},
            },
        }
        for name, spec in registry.items()
    ]


def tools_to_anthropic(registry: dict[str, dict]) -> list[dict]:
    """Anthropic native tools format."""
    return [
        {
            "name": name,
            "description": spec.get("description", ""),
            "input_schema": spec.get("input_schema") or {"type": "object", "properties": {}},
        }
        for name, spec in registry.items()
    ]


def tools_to_gemini(registry: dict[str, dict]) -> list[dict]:
    """Gemini function_declarations format (OpenAPI 3 subset)."""
    return [{
        "function_declarations": [
            {
                "name": name,
                "description": spec.get("description", ""),
                "parameters": spec.get("input_schema") or {"type": "object", "properties": {}},
            }
            for name, spec in registry.items()
        ]
    }]


# ── Native tool_call extractors ────────────────────────────────────────────────

def extract_openai_tool_calls(response: Any) -> LLMResponse:
    """Parse an OpenAI chat completion response with possible tool_calls."""
    msg = response.choices[0].message
    text = (msg.content or "").strip()
    # Some backends (Ollama Qwen3 thinking) put output in `reasoning` instead.
    if not text:
        reasoning = getattr(msg, "reasoning", None) or ""
        text = reasoning.strip()

    calls: list[ToolCall] = []
    for tc in (msg.tool_calls or []) if hasattr(msg, "tool_calls") else []:
        try:
            args = json.loads(tc.function.arguments or "{}")
        except json.JSONDecodeError as e:
            args = {"_parse_error": str(e), "raw": tc.function.arguments[:400]}
        calls.append(ToolCall(name=tc.function.name, args=args, call_id=tc.id))

    # Even in native mode, the model may emit <done> in the assistant text
    done_match = _DONE_RE.search(text)
    done = done_match.group(1).strip() if done_match else None

    return LLMResponse(text=text, tool_calls=calls, done=done, raw=response)


def extract_anthropic_tool_calls(response: Any) -> LLMResponse:
    """Parse an Anthropic Messages API response with possible tool_use blocks."""
    text_parts: list[str] = []
    calls: list[ToolCall] = []
    for block in response.content:
        btype = getattr(block, "type", None)
        if btype == "text":
            text_parts.append(block.text)
        elif btype == "tool_use":
            calls.append(ToolCall(
                name=block.name,
                args=dict(block.input) if block.input else {},
                call_id=block.id,
            ))
    text = "\n".join(text_parts).strip()
    done_match = _DONE_RE.search(text)
    done = done_match.group(1).strip() if done_match else None
    return LLMResponse(text=text, tool_calls=calls, done=done, raw=response)


def extract_gemini_tool_calls(response: Any) -> LLMResponse:
    """Parse a google-genai response with possible function_call parts."""
    text_parts: list[str] = []
    calls: list[ToolCall] = []
    for cand in (response.candidates or []):
        content = getattr(cand, "content", None)
        if not content:
            continue
        for part in (content.parts or []):
            if getattr(part, "text", None):
                text_parts.append(part.text)
            fc = getattr(part, "function_call", None)
            if fc is not None:
                calls.append(ToolCall(
                    name=fc.name,
                    args=dict(fc.args) if fc.args else {},
                ))
    text = "\n".join(text_parts).strip()
    done_match = _DONE_RE.search(text)
    done = done_match.group(1).strip() if done_match else None
    return LLMResponse(text=text, tool_calls=calls, done=done, raw=response)


# ── Message wire converters (neutral LangChain messages → provider wire) ───────
#
# The agent loop carries history as LangChain SystemMessage/HumanMessage/AIMessage
# (or plain dicts). Each backend turns that into the shape its SDK expects. These
# live here next to the tool-schema converters and response extractors so all
# request/response wire-format conversion sits in one module.

def messages_to_openai_wire(prompt: list) -> list[dict]:
    """OpenAI chat format: a flat [{role, content}] list; system is a normal message."""
    out: list[dict] = []
    for m in prompt:
        if isinstance(m, SystemMessage):
            out.append({"role": "system",    "content": m.content})
        elif isinstance(m, HumanMessage):
            out.append({"role": "user",      "content": m.content})
        elif isinstance(m, AIMessage):
            out.append({"role": "assistant", "content": m.content})
        elif isinstance(m, dict):
            out.append(m)
        else:
            out.append({"role": "user", "content": str(m)})
    return out


def messages_to_anthropic(prompt: list) -> tuple[list[dict], str | None]:
    """Anthropic expects `system` as a separate kwarg, not in the messages list."""
    system_parts: list[str] = []
    messages: list[dict] = []
    for m in prompt:
        if isinstance(m, SystemMessage):
            system_parts.append(m.content)
        elif isinstance(m, HumanMessage):
            messages.append({"role": "user",      "content": m.content})
        elif isinstance(m, AIMessage):
            messages.append({"role": "assistant", "content": m.content})
        elif isinstance(m, dict):
            role = m.get("role", "user")
            content = m.get("content", "")
            if role == "system":
                system_parts.append(content)
            else:
                messages.append({"role": role, "content": content})
        else:
            messages.append({"role": "user", "content": str(m)})
    return messages, ("\n\n".join(system_parts) or None)


def messages_to_gemini(prompt: list) -> tuple[list[dict], str | None]:
    """Gemini Contents API: roles `user`/`model`; system goes into config.system_instruction."""
    system_parts: list[str] = []
    contents: list[dict] = []
    for m in prompt:
        if isinstance(m, SystemMessage):
            system_parts.append(m.content)
            continue
        role = "user"
        text = ""
        if isinstance(m, HumanMessage):
            role, text = "user", m.content
        elif isinstance(m, AIMessage):
            role, text = "model", m.content
        elif isinstance(m, dict):
            r = m.get("role", "user")
            role = "model" if r == "assistant" else r
            text = m.get("content", "")
        else:
            text = str(m)
        contents.append({"role": role, "parts": [{"text": text}]})
    return contents, ("\n\n".join(system_parts) or None)
