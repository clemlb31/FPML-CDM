"""Agent helpers — utility functions shared across LangGraph nodes.

Located in agent/helpers.py (same package as graph.py) rather than scripts/
because this is core agent logic, not a one-shot admin script.
"""
from __future__ import annotations

import json
import os
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
        servers[name] = entry
    return servers


# ── Tool result unwrapping ────────────────────────────────────────────────────

def unwrap(result: Any) -> str:
    """Extract plain text from an MCP tool result (string, list of TextContent, ToolMessage…)."""
    if result is None:
        return ""
    if isinstance(result, str):
        return result
    if isinstance(result, list):
        parts = []
        for item in result:
            if hasattr(item, "text"):
                parts.append(item.text)
            elif isinstance(item, str):
                parts.append(item)
            else:
                parts.append(str(item))
        return "\n".join(parts)
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
    Call the vLLM endpoint with a list of LangChain messages.

    Env vars:
      VLLM_BASE_URL  (default: http://10.27.40.184:8000/v1)
      VLLM_MODEL     (default: /models/qwen3.6-27b-fp8)

    Raises RuntimeError if the LLM returns an empty response.
    """
    from openai import AsyncOpenAI

    base_url = os.getenv("VLLM_BASE_URL", "http://10.27.40.184:8000/v1")
    model    = os.getenv("VLLM_MODEL",    "/models/qwen3.6-27b-fp8")

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

    client = AsyncOpenAI(
        base_url=base_url,
        api_key="dummy",
        http_client=httpx.AsyncClient(
            timeout=httpx.Timeout(connect=30.0, read=1800.0, write=30.0, pool=30.0)
        ),
    )
    response = await client.chat.completions.create(
        model=model,
        messages=messages,
        max_tokens=max_tokens,
        temperature=0.2,
        extra_body={"chat_template_kwargs": {"enable_thinking": False}},
    )
    text = (response.choices[0].message.content or "").strip()
    if not text:
        raise RuntimeError(f"[{label}] LLM returned empty response")

    return _strip_code_fences(text) if strip_fences else text


def _strip_code_fences(text: str) -> str:
    text = re.sub(r"^```[a-zA-Z]*\n?", "", text.strip())
    text = re.sub(r"\n?```\s*$", "", text)
    return text.strip()


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

def maven_dependencies_from_raw(raw: Any) -> tuple[str, list[str], str]:
    """
    Extract <dependency>...</dependency> blocks from a raw tool result.
    Returns (full_xml_joined, list_of_blocks, raw_text).
    """
    raw_text = unwrap(raw)
    blocks   = re.findall(r"<dependency>.*?</dependency>", raw_text, re.DOTALL)
    full_xml = "\n".join(blocks)
    return full_xml, blocks, raw_text


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

def build_skeleton(method_specs: list[dict]) -> tuple[str, str]:
    """
    Generate IrsTransformer.java and FpmlToCdmApp.java from method specs.
    Returns (transformer_src, app_src).
    """
    return _build_transformer(method_specs), _build_app()


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
        import java.util.*;

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

        import com.fasterxml.jackson.databind.ObjectMapper;
        import com.fasterxml.jackson.databind.SerializationFeature;

        /**
         * CLI entry point: reads an FpML file, transforms to CDM JSON, prints to stdout.
         * Usage: java -jar target/*-jar-with-dependencies.jar <fpml.xml>
         */
        public class FpmlToCdmApp {{
            public static void main(String[] args) throws Exception {{
                if (args.length < 1) {{
                    System.err.println("Usage: FpmlToCdmApp <fpml-file.xml>");
                    System.exit(1);
                }}
                {_TRANSFORMER_CLASS} transformer = new {_TRANSFORMER_CLASS}();
                Object result = transformer.transform(args[0]);
                ObjectMapper mapper = new ObjectMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT);
                System.out.println(mapper.writeValueAsString(result));
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
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <dependencies>
    <!-- Jackson for JSON serialisation -->
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
      <version>2.17.0</version>
    </dependency>
    <!-- CDM Java dependencies (injected by the knowledge server) -->
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


def build_pom(maven_xml: str) -> str:
    """
    Build a pom.xml string.
    `maven_xml` is the raw <dependency>...</dependency> blocks from the knowledge server.
    """
    indented = "\n".join(
        "    " + line if line.strip() else ""
        for line in maven_xml.strip().splitlines()
    )
    return _POM_TEMPLATE.format(deps=indented)


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
