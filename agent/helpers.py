"""Agent helpers — non-LLM utilities for the autonomous agent.

Just two things live here now:
  • get_servers() — load the MCP server map from configs/mcp.yaml (with ${VAR} expansion)
  • unwrap()      — turn an MCP tool result into plain text

LLM backends moved to the `agent/llm_call/` package (config-driven class hierarchy);
the old LangGraph-era helpers (skeleton/pom builders, training-pair readers, method
hints, manual backend, …) were removed with graph.py.
"""
from __future__ import annotations

import json
import os
import re
from pathlib import Path
from typing import Any

import yaml

# ── Load .env if present ──────────────────────────────────────────────────────
# Belt-and-suspenders: autonomous.py also loads it via python-dotenv, but importing
# helpers standalone (e.g. tool_wrappers, tests) should see the same env.
_env_file = Path(__file__).resolve().parents[1] / ".env"
if _env_file.exists():
    for _line in _env_file.read_text(encoding="utf-8").splitlines():
        _line = _line.strip()
        if _line and not _line.startswith("#") and "=" in _line:
            _k, _, _v = _line.partition("=")
            os.environ.setdefault(_k.strip(), _v.strip())


# ── MCP server config ─────────────────────────────────────────────────────────

def get_servers() -> dict[str, dict]:
    """
    Load MCP server definitions from configs/mcp.yaml.
    Returns a dict suitable for MultiServerMCPClient.
    ${VAR} tokens in url values are expanded from environment variables.
    """
    config_path = Path(__file__).resolve().parents[1] / "configs" / "mcp.yaml"
    with open(config_path, encoding="utf-8") as f:
        raw = yaml.safe_load(f) or {}

    servers: dict[str, dict] = {}
    for name, cfg in (raw.get("servers") or {}).items():
        entry = dict(cfg)
        if "url" in entry:
            # Expand ${VAR} → os.environ[VAR]
            entry["url"] = re.sub(
                r"\$\{([^}]+)\}",
                lambda m: os.environ.get(m.group(1), m.group(0)),
                entry["url"],
            )
            # Skip servers whose URL still contains an unresolved ${VAR}
            if "${" in entry["url"]:
                print(
                    f"[get_servers] skipping {name!r}: env var unresolved in url {entry['url']!r}"
                )
                continue
        servers[name] = entry
    return servers


# ── Tool result unwrapping ────────────────────────────────────────────────────

def unwrap(result: Any) -> str:
    """Extract plain text from an MCP tool result.

    Handles: plain strings, lists of TextContent objects or dicts
    ({"type": "text", "text": "..."} per MCP spec), and ToolMessage-style
    objects with a `.content` attribute. Never falls back to str(dict) — that
    would write Python reprs to disk.
    """
    if result is None:
        return ""
    if isinstance(result, str):
        return result
    if isinstance(result, list):
        parts = []
        for item in result:
            if hasattr(item, "text"):
                parts.append(item.text)
            elif isinstance(item, dict):
                if "text" in item:
                    parts.append(str(item["text"]))
                elif "content" in item:
                    parts.append(unwrap(item["content"]))
                else:
                    parts.append(json.dumps(item))
            elif isinstance(item, str):
                parts.append(item)
            else:
                parts.append(str(item))
        return "\n".join(parts)
    if isinstance(result, dict):
        if "text" in result:
            return str(result["text"])
        if "content" in result:
            return unwrap(result["content"])
        return json.dumps(result)
    if hasattr(result, "content"):
        return unwrap(result.content)
    return str(result)
