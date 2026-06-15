"""Load and resolve the active LLM backend from configs/agent.yaml.

agent.yaml is the SOLE selector of which backend is active (`backend:` key).
Each entry under `backends:` carries its `kind` (which class drives it) plus data
(model, base_url, knobs). Secrets stay in .env, referenced here by env-var name
(`api_key_env`); movable infra / model pins may also be overridden via env
(`base_url_env` / `model_env`). `defaults:` are merged under every entry.
"""
from __future__ import annotations

from pathlib import Path

import yaml

_CONFIG_PATH = Path(__file__).resolve().parents[2] / "configs" / "agent.yaml"


def load_config(path: Path | str | None = None) -> dict:
    p = Path(path) if path else _CONFIG_PATH
    with open(p, encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def resolve_active(cfg: dict) -> tuple[str, dict]:
    """Return (active backend name, merged params) from a loaded config dict."""
    name = cfg.get("backend")
    backends = cfg.get("backends") or {}
    if not name:
        raise RuntimeError(
            f"configs/agent.yaml: no active `backend:` set. "
            f"Pick one of: {list(backends)}"
        )
    if name not in backends:
        raise RuntimeError(
            f"configs/agent.yaml: backend {name!r} not defined under `backends:`. "
            f"Known: {list(backends)}"
        )
    params = {**(cfg.get("defaults") or {}), **(backends[name] or {})}
    if "kind" not in params:
        raise RuntimeError(f"configs/agent.yaml: backend {name!r} has no `kind:`")
    return name, params
