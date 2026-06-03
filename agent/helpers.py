"""Agent helpers — utility functions shared across LangGraph nodes.

Located in agent/helpers.py (same package as graph.py) rather than scripts/
because this is core agent logic, not a one-shot admin script.
"""
from __future__ import annotations

import asyncio
import json
import os
import random
import re
import textwrap
from pathlib import Path
from typing import Any

import httpx
import yaml
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage

# ── Load .env if present ──────────────────────────────────────────────────────
_env_file = Path(__file__).resolve().parents[1] / ".env"
if _env_file.exists():
    for _line in _env_file.read_text(encoding="utf-8").splitlines():
        _line = _line.strip()
        if _line and not _line.startswith("#") and "=" in _line:
            _k, _, _v = _line.partition("=")
            os.environ.setdefault(_k.strip(), _v.strip())


# ── Constants ─────────────────────────────────────────────────────────────────

_PACKAGE              = "com.example"
_TRANSFORMER_CLASS    = "IrsTransformer"
_JAVA_VERSION         = "17"   # matches what `main` builds against (org.finos.cdm requires 17+)
_MAX_PATCH_ITERATIONS = 8


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


def unwrap_dict(result: Any) -> dict:
    """Extract a dict from an MCP tool result. Parses JSON strings automatically."""
    text = unwrap(result).strip()
    if not text:
        return {}
    if text.startswith("{"):
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            pass
    # Strip markdown fences then retry
    inner = re.sub(r"```[a-zA-Z]*\n?", "", text).strip().rstrip("```").strip()
    if inner.startswith("{"):
        try:
            return json.loads(inner)
        except json.JSONDecodeError:
            pass
    return {"content": text}


# ── Tool call helper ──────────────────────────────────────────────────────────

async def tool_text(tools_by_name: dict, tool_name: str, **kwargs) -> str:
    """Invoke a named MCP tool and return its text output.

    If a dedicated knowledge tool is not available, falls back to filesystem MCP
    `read_file` for known reference files under knowledge_base/.
    """
    knowledge_fallbacks = {
        "get_irs_xpath_guide": "knowledge_base/reference/fpml/irs_xpath_guide.md",
        "get_cdm_enum_mappings": "knowledge_base/reference/cdm/enum_mappings.md",
        "get_cdm_date_handling": "knowledge_base/reference/cdm/date_handling.md",
        "get_cdm_global_key_guide": "knowledge_base/reference/cdm/global_key_guide.md",
        "get_cdm_class_hierarchy": "knowledge_base/reference/cdm/hierarchy.txt",
        "get_all_mappings": "knowledge_base/rules/irs.md",
    }

    t = tools_by_name.get(tool_name)
    if t:
        result = await t.ainvoke(kwargs or {})
        return unwrap(result)

    # Fallback: map missing knowledge tools to filesystem MCP read_file.
    rel_path = knowledge_fallbacks.get(tool_name)
    read_tool = tools_by_name.get("read_file")
    if rel_path and read_tool:
        try:
            root = Path(__file__).resolve().parents[1]
            full_path = str(root / rel_path)
            result = await read_tool.ainvoke({"path": full_path})
            payload = unwrap_dict(result)
            return payload.get("content") or unwrap(result)
        except Exception:
            return ""

    return ""


# ── LLM call ──────────────────────────────────────────────────────────────────

async def llm_text_or_raise(
    project_dir: str,
    label: str,
    prompt: list,
    max_tokens: int = 2000,
    strip_fences: bool = False,
) -> str:
    """
    Call the configured LLM backend with a list of LangChain messages.

    Backend selected by $LLM_BACKEND (default: gemini). Supported:
      gemini    — Google AI Studio (OpenAI-compatible endpoint)
      groq      — Groq Cloud (OpenAI-compatible endpoint)
      ollama    — Local Ollama server (OpenAI-compatible endpoint)
      vllm      — local/remote vLLM server
      manual    — Writes prompt to .llm_io/inbox/, polls .llm_io/outbox/ for a
                  text response. Use when an external operator (Claude in chat,
                  human, separate process) answers each prompt.

    All non-manual backends are spoken via the OpenAI async client.
    Raises RuntimeError if the LLM returns an empty response.
    """
    backend = (os.getenv("LLM_BACKEND") or "gemini").lower()

    if backend == "manual":
        return await _manual_llm_call(label, prompt, max_tokens, strip_fences)

    from openai import AsyncOpenAI

    if backend == "gemini":
        api_key = os.getenv("GEMINI_API_KEY")
        if not api_key:
            raise RuntimeError("GEMINI_API_KEY not set (check .env)")
        base_url = "https://generativelanguage.googleapis.com/v1beta/openai/"
        model = os.getenv("GEMINI_MODEL", "gemini-2.5-flash")
        extra_body: dict | None = None
    elif backend == "groq":
        api_key = os.getenv("GROQ_API_KEY")
        if not api_key:
            raise RuntimeError("GROQ_API_KEY not set (check .env)")
        base_url = "https://api.groq.com/openai/v1"
        model = os.getenv("GROQ_MODEL", "llama-3.3-70b-versatile")
        extra_body = None
    elif backend == "ollama":
        api_key = "ollama"  # any non-empty string; ollama ignores auth
        base_url = os.getenv("OLLAMA_BASE_URL", "http://localhost:11434/v1")
        model = os.getenv("OLLAMA_MODEL", "qwen3.5:4b")
        # Ollama's OpenAI-compat forwards `think` as a native option.
        # `think: false` disables Qwen3's thinking mode at the API level —
        # belt-and-braces alongside the `/no_think` suffix on the user message.
        extra_body = {"think": False}
    elif backend == "vllm":
        api_key = "dummy"
        base_url = os.getenv("VLLM_BASE_URL", "http://10.27.40.184:8000/v1")
        model    = os.getenv("VLLM_MODEL",    "/models/qwen3.6-27b-fp8")
        extra_body = {"chat_template_kwargs": {"enable_thinking": False}}
    else:
        raise RuntimeError(
            f"Unsupported LLM_BACKEND={backend!r} for llm_text_or_raise. "
            f"Expected one of: gemini, groq, ollama, vllm."
        )

    # Convert LangChain message types to OpenAI wire format
    messages = []
    for m in prompt:
        if isinstance(m, SystemMessage):
            messages.append({"role": "system",    "content": m.content})
        elif isinstance(m, HumanMessage):
            messages.append({"role": "user",      "content": m.content})
        elif isinstance(m, AIMessage):
            messages.append({"role": "assistant", "content": m.content})
        elif isinstance(m, dict):
            messages.append(m)
        else:
            messages.append({"role": "user", "content": str(m)})

    # Qwen3 defaults to thinking mode which loops on small (4B) checkpoints and
    # leaves `content` empty. The /no_think token disables it for that turn.
    if backend == "ollama" and "qwen3" in model.lower():
        for m in reversed(messages):
            if m["role"] == "user" and "/no_think" not in m["content"]:
                m["content"] = m["content"].rstrip() + "\n\n/no_think"
                break

    client = AsyncOpenAI(
        base_url=base_url,
        api_key=api_key,
        http_client=httpx.AsyncClient(
            timeout=httpx.Timeout(connect=30.0, read=1800.0, write=30.0, pool=30.0)
        ),
    )
    create_kwargs: dict = dict(
        model=model,
        messages=messages,
        max_tokens=max_tokens,
        temperature=0.2,
    )
    if extra_body is not None:
        create_kwargs["extra_body"] = extra_body

    # Retry transient errors (overloaded / rate-limited / network)
    from openai import APIConnectionError, APIError, APITimeoutError, RateLimitError
    last_exc: Exception | None = None
    for attempt in range(6):
        try:
            response = await client.chat.completions.create(**create_kwargs)
            msg = response.choices[0].message
            text = (msg.content or "").strip()
            # Some backends (Ollama with Qwen3 thinking-mode) put output in
            # `reasoning` instead of `content` when the model never escapes
            # its thinking loop. Fall back so we don't lose the run entirely.
            if not text:
                reasoning = getattr(msg, "reasoning", None) or ""
                text = reasoning.strip()
            if not text:
                raise RuntimeError(f"[{label}] LLM returned empty response")
            return _strip_code_fences(text) if strip_fences else text
        except (RateLimitError, APIConnectionError, APITimeoutError) as e:
            last_exc = e
            status = getattr(e, "status_code", None) or 429
        except APIError as e:
            last_exc = e
            status = getattr(e, "status_code", None)
            # 413 = Groq TPM exceeded (not a real payload error), 429 = RPM
            if status not in (413, 429, 500, 502, 503, 504):
                raise
        if attempt == 5:
            break
        msg_text = str(last_exc)
        m = re.search(r"retry(?:Delay|-after)?[\":\s]+(\d+(?:\.\d+)?)\s*s", msg_text, re.IGNORECASE)
        hint = float(m.group(1)) if m else 0.0
        # Per-minute quota windows need >60s to fully clear
        if status in (413, 429):
            delay = max(hint + 2.0, 65.0)
        else:
            delay = (2 ** attempt) + random.uniform(0, 1)
        print(
            f"[{label}] LLM transient error ({type(last_exc).__name__} {status}); "
            f"retry {attempt + 1}/5 in {delay:.1f}s"
        )
        await asyncio.sleep(delay)

    raise RuntimeError(f"[{label}] LLM failed after retries: {last_exc}")


def _strip_code_fences(text: str) -> str:
    text = re.sub(r"^```[a-zA-Z]*\n?", "", text.strip())
    text = re.sub(r"\n?```\s*$", "", text)
    return text.strip()


# ── Manual backend ─────────────────────────────────────────────────────────────
# Acts as an LLM by writing each prompt to disk and polling for the answer.
# An external operator (Claude in chat, sub-agent, or human) reads `.llm_io/inbox/`
# and writes the response to `.llm_io/outbox/<same-id>.txt`.

_LLM_IO_DIR = Path(__file__).resolve().parents[1] / ".llm_io"


async def _manual_llm_call(
    label: str,
    prompt: list,
    max_tokens: int,
    strip_fences: bool,
    poll_interval: float = 1.0,
    timeout_seconds: float = 30 * 60,
) -> str:
    """Drop the prompt in an inbox file, wait for an outbox file, return its text.

    Inbox file is JSON so the operator can read context (label, messages, hints).
    Outbox file is plain text (the answer). Both are deleted after consumption.
    """
    inbox  = _LLM_IO_DIR / "inbox"
    outbox = _LLM_IO_DIR / "outbox"
    history = _LLM_IO_DIR / "history"
    for d in (inbox, outbox, history):
        d.mkdir(parents=True, exist_ok=True)

    # Serialise prompt to plain dicts (OpenAI message shape)
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
    req_file  = inbox  / f"{req_id}.json"
    resp_file = outbox / f"{req_id}.txt"

    payload = {
        "id":            req_id,
        "label":         label,
        "max_tokens":    max_tokens,
        "strip_fences":  strip_fences,
        "messages":      serialised,
    }
    req_file.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"[{label}] manual: waiting on .llm_io/outbox/{resp_file.name}")

    deadline = asyncio.get_event_loop().time() + timeout_seconds
    while asyncio.get_event_loop().time() < deadline:
        if resp_file.exists():
            text = resp_file.read_text(encoding="utf-8")
            # Archive both files so future runs and the operator can re-read.
            try:
                (history / f"{req_id}.req.json").write_text(req_file.read_text(encoding="utf-8"), encoding="utf-8")
                (history / f"{req_id}.resp.txt").write_text(text, encoding="utf-8")
            except OSError:
                pass
            req_file.unlink(missing_ok=True)
            resp_file.unlink(missing_ok=True)
            text = text.strip()
            if not text:
                raise RuntimeError(f"[{label}] manual: empty response file")
            return _strip_code_fences(text) if strip_fences else text
        await asyncio.sleep(poll_interval)

    raise RuntimeError(
        f"[{label}] manual: timed out after {timeout_seconds:.0f}s. "
        f"Expected operator to write {resp_file}"
    )


# ── JSON helpers ──────────────────────────────────────────────────────────────

def json_list_or_raise(raw: str, label: str) -> list:
    """
    Parse a JSON array from raw LLM output.
    Strips markdown fences and extracts the first [...] block.
    """
    text = _strip_code_fences(raw.strip())
    start = text.find("[")
    end   = text.rfind("]")
    if start == -1 or end == -1:
        raise ValueError(
            f"[{label}] No JSON array found in LLM output:\n{text[:400]}"
        )
    try:
        return json.loads(text[start : end + 1])
    except json.JSONDecodeError as e:
        raise ValueError(
            f"[{label}] JSON parse error: {e}\nText:\n{text[:400]}"
        ) from e


# ── Maven dependency extraction ───────────────────────────────────────────────

def maven_dependencies_from_raw(raw: Any) -> tuple[str, list[str], str, str, str]:
    """
    Parse the mapping_server's get_maven_dependencies response.

    The MCP tool returns a JSON dict {dependencies_xml, properties_xml, repositories_xml}.
    After MCP serialisation we receive the JSON-encoded string — we must parse it before
    the embedded newlines are escaped as \\n in our outputs.

    Returns:
        (deps_xml, dep_blocks, properties_xml, repositories_xml, raw_text)
    """
    raw_text = unwrap(raw)
    deps_xml, props_xml, repos_xml = "", "", ""

    if raw_text.lstrip().startswith("{"):
        try:
            parsed = json.loads(raw_text)
            if isinstance(parsed, dict):
                deps_xml  = str(parsed.get("dependencies_xml")  or "")
                props_xml = str(parsed.get("properties_xml")    or "")
                repos_xml = str(parsed.get("repositories_xml")  or "")
        except json.JSONDecodeError:
            pass

    if not deps_xml:
        # Fallback: regex against the raw text (legacy format).
        deps_xml = raw_text

    blocks = re.findall(r"<dependency>.*?</dependency>", deps_xml, re.DOTALL)
    full_xml = "\n".join(blocks)
    return full_xml, blocks, props_xml, repos_xml, raw_text


# ── Prompt helpers ────────────────────────────────────────────────────────────

def excerpt_if_present(text: str, max_len: int = 300) -> str:
    """Return text[:max_len] unless text is empty or a 'not yet created' placeholder."""
    if not text or "not yet created" in text:
        return ""
    return text[:max_len]


_METHOD_HINTS: dict[str, str] = {
    "mapDayCountFraction": (
        "FpML uses strings like 'ACT/360', 'ACT/365.FIXED', '30/360'. "
        "Map to CDM DayCountFractionEnum (ACT_360, ACT_365_FIXED, …). "
        "Use a switch/if-else on getText(context, 'dayCountFraction')."
    ),
    "mapFloatingRateIndex": (
        "FpML floatingRateIndex text (e.g. 'USD-LIBOR-BBA') maps to "
        "FloatingRateIndexEnum. Normalise to uppercase+underscores or use a lookup map."
    ),
    "resolvePartyRole": (
        "FpML payerPartyReference/@href and receiverPartyReference/@href "
        "contain party IDs. Match against party elements to assign "
        "CounterpartyRoleEnum.PARTY_1 / PARTY_2."
    ),
    "buildParties": (
        "FpML <party> elements have <partyId> children. "
        "Build Party with PartyIdentifier. "
        "Use FieldWithMetaString.builder().setValue(id).build() for identifiers."
    ),
}


def method_hint(
    method: str,
    enum_short: str = "",
    date_short: str = "",
    key_short: str  = "",
) -> str:
    """Return a targeted hint string for known tricky methods."""
    base = _METHOD_HINTS.get(method, "")
    if "date" in method.lower() and date_short:
        base += f"\nDate tip: {date_short[:120]}"
    if method in ("buildParties", "resolvePartyRole") and key_short:
        base += f"\nGlobalKey tip: {key_short[:80]}"
    return base


# ── Training data helpers (no exemple_server needed) ─────────────────────────

_DATA_TRAIN_DIR = Path(__file__).resolve().parents[1] / "data" / "train"


async def read_training_pairs(n: int = 2) -> str:
    """
    Return n FpML+CDM training pairs formatted as text for LLM context.
    Reads directly from data/train/ — no MCP round-trip needed.
    """
    dirs = sorted(
        d for d in _DATA_TRAIN_DIR.iterdir()
        if d.is_dir() and (d / "fpml").exists() and (d / "cdm").exists()
    )[:n]

    parts = []
    for d in dirs:
        fpml_files = sorted((d / "fpml").glob("*.xml"))
        cdm_files  = sorted((d / "cdm").glob("*.json"))
        if not fpml_files or not cdm_files:
            continue
        fpml_text = fpml_files[0].read_text(encoding="utf-8")[:1500]
        cdm_text  = cdm_files[0].read_text(encoding="utf-8")[:1500]
        parts.append(
            f"### Example: {d.name}\n"
            f"**FpML input:**\n```xml\n{fpml_text}\n```\n"
            f"**CDM output:**\n```json\n{cdm_text}\n```"
        )

    return "\n\n".join(parts) if parts else "(no training examples found)"


async def read_cdm_snippet(field_path: str) -> str:
    """
    Extract a sub-tree from a CDM JSON training example at the given
    dot-separated path (e.g. 'trade.party', 'trade.product.economicTerms.payout').
    Reads directly from data/train/ — no MCP round-trip needed.
    """
    dirs = sorted(
        d for d in _DATA_TRAIN_DIR.iterdir()
        if d.is_dir() and (d / "cdm").exists()
    )
    for d in dirs:
        cdm_files = sorted((d / "cdm").glob("*.json"))
        if not cdm_files:
            continue
        try:
            obj = json.loads(cdm_files[0].read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            continue

        # Walk the dot-separated path
        node = obj
        for key in field_path.split("."):
            if isinstance(node, dict) and key in node:
                node = node[key]
            elif isinstance(node, list) and node:
                node = node[0]
                if isinstance(node, dict) and key in node:
                    node = node[key]
                else:
                    node = None
                    break
            else:
                node = None
                break

        if node is not None:
            snippet = json.dumps(node, indent=2)[:600]
            return f"CDM snippet at '{field_path}':\n```json\n{snippet}\n```"

    return f"(no CDM snippet found for path '{field_path}')"


# ── Java skeleton generation ──────────────────────────────────────────────────

def build_skeleton(method_specs: list[dict]) -> tuple[str, str, str]:
    """
    Generate the 3 source files of the project from method specs.
    Returns (transformer_src, app_src, semantic_diff_src).
    """
    return _build_transformer(method_specs), _build_app(), _build_semantic_diff()


def _build_transformer(specs: list[dict]) -> str:
    stubs = []
    for spec in specs:
        name   = spec["method_name"]
        ret    = spec.get("return_type", "Object")
        desc   = spec.get("description", "")
        xpath  = (spec.get("fpml_xpath") or "")[:80]
        cdm    = (spec.get("cdm_path")   or "")[:80]
        stubs.append(
            f"    /**\n"
            f"     * {desc}\n"
            f"     * FpML: {xpath}\n"
            f"     * CDM:  {cdm}\n"
            f"     */\n"
            f"    private {ret} {name}(Element context) {{\n"
            f'        throw new UnsupportedOperationException("TODO: {name}");\n'
            f"    }}"
        )

    stubs_block = "\n\n".join(stubs)

    return textwrap.dedent(f"""\
        package {_PACKAGE};

        import org.w3c.dom.*;
        import javax.xml.parsers.*;
        import java.io.*;
        import java.math.BigDecimal;
        import java.util.ArrayList;
        import java.util.Collections;
        import java.util.HashMap;
        import java.util.List;
        import java.util.Map;
        import java.util.Optional;

        // CDM model (org.finos.cdm:cdm-java:6.19.0)
        import cdm.base.datetime.*;
        import cdm.base.math.*;
        import cdm.base.staticdata.asset.common.*;
        import cdm.base.staticdata.party.*;
        import cdm.event.common.*;
        import cdm.legaldocumentation.common.*;
        import cdm.observable.asset.*;
        import cdm.observable.common.*;
        import cdm.product.asset.*;
        import cdm.product.common.schedule.*;
        import cdm.product.common.settlement.*;
        import cdm.product.template.*;

        // Rosetta meta types
        import com.rosetta.model.lib.records.Date;       // CDM date \u2014 NOT java.util.Date
        import com.rosetta.model.metafields.FieldWithMetaString;
        import com.rosetta.model.metafields.MetaFields;

        /**
         * FpML 5.x \u2192 CDM 6.x IRS transformer.
         * Auto-generated skeleton \u2014 method bodies filled by the LLM agent.
         */
        public class {_TRANSFORMER_CLASS} {{

            // \u2500\u2500 Entry point \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

            public Object transform(String fpmlPath) throws Exception {{
                Document doc = parseXml(fpmlPath);
                Element root = doc.getDocumentElement();
                return buildTradeState(root);
            }}

            // \u2500\u2500 Generated stubs \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

        {stubs_block}

            // \u2500\u2500 DOM helpers \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

            protected String getText(Element el, String tag) {{
                NodeList nl = el.getElementsByTagName(tag);
                if (nl.getLength() == 0) return null;
                return nl.item(0).getTextContent().trim();
            }}

            protected Element getElement(Element el, String tag) {{
                NodeList nl = el.getElementsByTagName(tag);
                return nl.getLength() > 0 ? (Element) nl.item(0) : null;
            }}

            protected List<Element> getElements(Element el, String tag) {{
                NodeList nl = el.getElementsByTagName(tag);
                List<Element> list = new ArrayList<>();
                for (int i = 0; i < nl.getLength(); i++) {{
                    list.add((Element) nl.item(i));
                }}
                return list;
            }}

            private Document parseXml(String path) throws Exception {{
                DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                f.setNamespaceAware(true);
                return f.newDocumentBuilder().parse(new File(path));
            }}
        }}
        """)


def _build_app() -> str:
    return textwrap.dedent(f"""\
        package {_PACKAGE};

        import com.fasterxml.jackson.databind.JsonNode;
        import com.fasterxml.jackson.databind.ObjectMapper;
        import com.fasterxml.jackson.databind.SerializationFeature;

        import java.nio.file.Files;
        import java.nio.file.Paths;

        /**
         * CLI entry point.
         *
         * Two modes:
         *   transform: java -jar app.jar <fpml.xml>
         *      → prints CDM JSON to stdout, exit 0.
         *   compare:   java -jar app.jar <fpml.xml> --expected <expected-cdm.json>
         *      → also runs SemanticDiff vs expected. Exit 0 if equal, 2 if diffs,
         *        1 on hard error. Prints "===EQUAL===" or "===DIFFS===\\n<diff lines>".
         */
        public class FpmlToCdmApp {{
            public static void main(String[] args) throws Exception {{
                if (args.length < 1) {{
                    System.err.println("Usage: FpmlToCdmApp <fpml.xml> [--expected <expected.json>]");
                    System.exit(1);
                }}
                String fpmlPath = args[0];
                String expectedPath = null;
                for (int i = 1; i < args.length - 1; i++) {{
                    if ("--expected".equals(args[i])) {{
                        expectedPath = args[i + 1];
                        break;
                    }}
                }}

                {_TRANSFORMER_CLASS} transformer = new {_TRANSFORMER_CLASS}();
                Object result = transformer.transform(fpmlPath);

                ObjectMapper mapper = new ObjectMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT);
                String actualJson = mapper.writeValueAsString(result);
                System.out.println(actualJson);

                if (expectedPath == null) {{
                    System.exit(0);
                }}

                String expectedJson = new String(Files.readAllBytes(Paths.get(expectedPath)));
                JsonNode actualTree   = mapper.readTree(actualJson);
                JsonNode expectedTree = mapper.readTree(expectedJson);
                SemanticDiff.Result diff = SemanticDiff.compare(expectedTree, actualTree);

                if (diff.isEqual()) {{
                    System.out.println("===EQUAL===");
                    System.exit(0);
                }} else {{
                    System.out.println("===DIFFS===");
                    System.out.println(diff);
                    System.exit(2);
                }}
            }}
        }}
        """)


def _build_semantic_diff() -> str:
    """SemanticDiff.java — ported from main branch (io.fpmlcdm.report → com.example)."""
    return textwrap.dedent(f"""\
        package {_PACKAGE};

        import com.fasterxml.jackson.databind.JsonNode;
        import com.fasterxml.jackson.databind.ObjectMapper;
        import com.fasterxml.jackson.databind.node.ArrayNode;
        import com.fasterxml.jackson.databind.node.ObjectNode;

        import java.math.BigDecimal;
        import java.util.ArrayList;
        import java.util.Iterator;
        import java.util.LinkedHashSet;
        import java.util.List;
        import java.util.Map;
        import java.util.Set;
        import java.util.TreeMap;

        /**
         * Compares two CDM JSON documents semantically. Ported from main branch.
         * Normalisation:
         *   - drop globalKey / globalReference / assetType / securityType / priceSubType
         *   - drop meta objects that empty out after the above
         *   - object key order irrelevant
         *   - numeric compare via BigDecimal.compareTo (0.025 == 0.0250)
         *   - field aliases for CDM typos: notionalReference → notionaReference, barrier → knock
         *   - hoist unscheduledTransfer wrapper
         */
        public final class SemanticDiff {{

            private static final ObjectMapper MAPPER = new ObjectMapper();
            private static final Set<String> DROPPED_META_KEYS = Set.of("globalKey");
            private static final Set<String> DROPPED_ANYWHERE = Set.of(
                "globalReference", "assetType", "securityType", "priceSubType"
            );
            private static final Set<String> UNWRAP_SINGLE_ARRAY = Set.of("stubPeriodType");
            private static final Map<String, String> FIELD_ALIASES = Map.of(
                "notionalReference", "notionaReference",
                "barrier", "knock"
            );
            private static final Set<String> HOIST_WRAPPER = Set.of("unscheduledTransfer");

            private SemanticDiff() {{}}

            public static Result compare(JsonNode expected, JsonNode actual) {{
                List<String> diffs = new ArrayList<>();
                JsonNode normExpected = normalise(expected.deepCopy());
                JsonNode normActual   = normalise(actual.deepCopy());
                walk("", normExpected, normActual, diffs);
                return new Result(diffs);
            }}

            public static Result compare(String expectedJson, String actualJson) throws Exception {{
                return compare(MAPPER.readTree(expectedJson), MAPPER.readTree(actualJson));
            }}

            static JsonNode normalise(JsonNode node) {{
                if (node.isObject()) {{
                    ObjectNode obj = (ObjectNode) node;
                    Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
                    while (it.hasNext()) {{
                        Map.Entry<String, JsonNode> e = it.next();
                        if (DROPPED_ANYWHERE.contains(e.getKey())) {{
                            it.remove();
                        }} else if (e.getKey().equals("meta")) {{
                            JsonNode cleaned = stripDroppedKeys(e.getValue().deepCopy());
                            if (cleaned == null || (cleaned.isObject() && cleaned.size() == 0)) {{
                                it.remove();
                            }} else {{
                                e.setValue(normalise(cleaned));
                            }}
                        }} else if (UNWRAP_SINGLE_ARRAY.contains(e.getKey())
                                && e.getValue().isArray() && e.getValue().size() == 1) {{
                            e.setValue(normalise(e.getValue().get(0)));
                        }} else {{
                            e.setValue(normalise(e.getValue()));
                        }}
                    }}
                    for (Map.Entry<String, String> alias : FIELD_ALIASES.entrySet()) {{
                        JsonNode val = obj.get(alias.getKey());
                        if (val != null) {{
                            obj.remove(alias.getKey());
                            obj.set(alias.getValue(), val);
                        }}
                    }}
                    for (String wrapper : HOIST_WRAPPER) {{
                        JsonNode child = obj.get(wrapper);
                        if (child != null && child.isObject()) {{
                            obj.remove(wrapper);
                            child.fields().forEachRemaining(f -> obj.set(f.getKey(), f.getValue()));
                        }}
                    }}
                }} else if (node.isArray()) {{
                    ArrayNode arr = (ArrayNode) node;
                    for (int i = 0; i < arr.size(); i++) {{
                        arr.set(i, normalise(arr.get(i)));
                    }}
                }}
                return node;
            }}

            private static JsonNode stripDroppedKeys(JsonNode node) {{
                if (!node.isObject()) return node;
                ObjectNode obj = (ObjectNode) node;
                for (String dropped : DROPPED_META_KEYS) {{
                    obj.remove(dropped);
                }}
                return obj;
            }}

            private static void walk(String path, JsonNode expected, JsonNode actual, List<String> diffs) {{
                if (expected.isObject() && actual.isObject()) {{
                    walkObject(path, (ObjectNode) expected, (ObjectNode) actual, diffs);
                }} else if (expected.isArray() && actual.isArray()) {{
                    walkArray(path, (ArrayNode) expected, (ArrayNode) actual, diffs);
                }} else if (expected.isNumber() && actual.isNumber()) {{
                    BigDecimal a = new BigDecimal(expected.asText());
                    BigDecimal b = new BigDecimal(actual.asText());
                    if (a.compareTo(b) != 0) {{
                        diffs.add("~ " + path + " : " + a + " -> " + b);
                    }}
                }} else if (!expected.equals(actual)) {{
                    diffs.add("~ " + path + " : " + safeRender(expected) + " -> " + safeRender(actual));
                }}
            }}

            private static void walkObject(String path, ObjectNode expected, ObjectNode actual, List<String> diffs) {{
                Map<String, JsonNode> e = sortFields(expected);
                Map<String, JsonNode> a = sortFields(actual);
                Set<String> all = new LinkedHashSet<>();
                all.addAll(e.keySet());
                all.addAll(a.keySet());
                for (String k : all) {{
                    String childPath = path.isEmpty() ? k : path + "." + k;
                    if (!e.containsKey(k)) {{
                        diffs.add("+ " + childPath + " : " + safeRender(a.get(k)));
                    }} else if (!a.containsKey(k)) {{
                        diffs.add("- " + childPath + " : " + safeRender(e.get(k)));
                    }} else {{
                        walk(childPath, e.get(k), a.get(k), diffs);
                    }}
                }}
            }}

            private static void walkArray(String path, ArrayNode expected, ArrayNode actual, List<String> diffs) {{
                int n = Math.max(expected.size(), actual.size());
                for (int i = 0; i < n; i++) {{
                    String childPath = path + "[" + i + "]";
                    if (i >= expected.size()) {{
                        diffs.add("+ " + childPath + " : " + safeRender(actual.get(i)));
                    }} else if (i >= actual.size()) {{
                        diffs.add("- " + childPath + " : " + safeRender(expected.get(i)));
                    }} else {{
                        walk(childPath, expected.get(i), actual.get(i), diffs);
                    }}
                }}
            }}

            private static Map<String, JsonNode> sortFields(ObjectNode node) {{
                Map<String, JsonNode> m = new TreeMap<>();
                node.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue()));
                return m;
            }}

            private static String safeRender(JsonNode node) {{
                String s = node.toString();
                return s.length() > 120 ? s.substring(0, 117) + "..." : s;
            }}

            public record Result(List<String> diffs) {{
                public boolean isEqual() {{ return diffs.isEmpty(); }}
                public int size() {{ return diffs.size(); }}
                @Override public String toString() {{
                    return diffs.isEmpty() ? "<equal>" : String.join("\\n", diffs);
                }}
            }}
        }}
        """)


# ── pom.xml generation ────────────────────────────────────────────────────────

_POM_TEMPLATE = """\
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>fpml-cdm-transformer</artifactId>
  <version>1.0-SNAPSHOT</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
{properties}
  </properties>

  <repositories>
{repositories}
  </repositories>

  <dependencies>
    <!-- Jackson for JSON serialisation -->
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
      <version>2.17.0</version>
    </dependency>
{deps}
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-assembly-plugin</artifactId>
        <version>3.6.0</version>
        <configuration>
          <descriptorRefs>
            <descriptorRef>jar-with-dependencies</descriptorRef>
          </descriptorRefs>
          <archive>
            <manifest>
              <mainClass>com.example.FpmlToCdmApp</mainClass>
            </manifest>
          </archive>
        </configuration>
        <executions>
          <execution>
            <id>make-assembly</id>
            <phase>package</phase>
            <goals><goal>single</goal></goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
"""


def _indent_block(xml: str, indent: str = "    ") -> str:
    return "\n".join(
        indent + line if line.strip() else ""
        for line in xml.strip().splitlines()
    )


def build_pom(deps_xml: str, properties_xml: str = "", repositories_xml: str = "") -> str:
    """
    Build a pom.xml string from the 3 XML chunks returned by mapping_server.

    All inputs are raw (un-indented). Empty `repositories_xml` keeps the
    <repositories/> element empty — Maven Central is the only source.
    """
    return _POM_TEMPLATE.format(
        deps=_indent_block(deps_xml, "    "),
        properties=_indent_block(properties_xml, "    "),
        repositories=_indent_block(repositories_xml, "    "),
    )


# ── Method body replacement ───────────────────────────────────────────────────

def replace_method_body_or_raise(
    source: str,
    current_body: str,
    target: str,
    new_body: str,
) -> str:
    """
    Replace the body of `target` in `source`.

    `current_body` — full method text (signature + braces), as returned by
                     extract_method_source.
    `new_body`     — only the statements that go inside the braces.

    Raises RuntimeError if the method cannot be located in source.
    """
    if not current_body or not current_body.strip():
        raise RuntimeError(
            f"replace_method_body_or_raise: empty current_body for '{target}'"
        )
    if current_body not in source:
        raise RuntimeError(
            f"replace_method_body_or_raise: body of '{target}' not found in source"
        )

    # Keep the signature up to and including the opening brace
    brace_idx = current_body.index("{")
    signature = current_body[:brace_idx + 1]

    # Detect indentation from the first non-empty body line
    body_indent = "        "  # fallback: 8 spaces
    for line in current_body[brace_idx + 1:].splitlines():
        stripped = line.lstrip()
        if stripped:
            body_indent = " " * (len(line) - len(stripped))
            break

    indented_body = "\n".join(
        body_indent + ln if ln.strip() else ""
        for ln in new_body.strip().splitlines()
    )
    close_indent = " " * max(0, len(body_indent) - 4)
    replacement  = f"{signature}\n{indented_body}\n{close_indent}}}"

    return source.replace(current_body, replacement, 1)
