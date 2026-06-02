"""
Validator Server — MCP tool definitions
========================================
Provides the build/test feedback loop for the LangGraph agent.

Tools exposed:
  compile_project   — mvn clean compile → structured error list with source snippets
  run_test          — package + run JAR on one FpML file, diff vs expected CDM JSON
  run_test_all      — package once, run every test case from the active suite
  run_arbitrary_test — run JAR on an FpML file, return raw output (debug, no diff)
  list_test_suites  — list available suites and their case counts
  get_test_cases    — list all (fpml, cdm) pairs in a suite
  extract_method_source — extract one method body from Java source (for targeted patching)
  score_with_llm    — LLM-as-judge semantic evaluation of CDM mapping quality

Container lifecycle:
  A Docker/Podman container (maven:3.9-eclipse-temurin-21) is started when the server
  loads and stopped on exit. The agent never needs to manage the container directly.

Test selection:
  Edit workspaces/test_config.yaml to define suites and set active_suite BEFORE
  launching the agentic pipeline. The agent can also update this file via the
  filesystem MCP server.

Path conventions (all tool args use container-internal paths):
  /workspace  →  workspaces/  (project source, pom.xml, test_config.yaml)
  /data       →  data/test/   (read-only FpML/CDM test data)
"""

import atexit
import re
import signal
import sys

from mcp.server.fastmcp import FastMCP

try:
    from _internals import (
        WORKSPACE_CONTAINER,
        DATA_CONTAINER,
        WORKSPACE_HOST,
        DATA_HOST,
        container_to_host,
        container_running,
        start_container,
        stop_container,
        docker_exec,
        resolve_jar_container,
        parse_compile_errors,
        parse_crash,
        run_jar_and_diff,
        json_diff,
        json_score,
        load_test_config,
        resolve_suite,
    )
except ImportError:
    from ._internals import (
        WORKSPACE_CONTAINER,
        DATA_CONTAINER,
        WORKSPACE_HOST,
        DATA_HOST,
        container_to_host,
        container_running,
        start_container,
        stop_container,
        docker_exec,
        resolve_jar_container,
        parse_compile_errors,
        parse_crash,
        run_jar_and_diff,
        json_diff,
        json_score,
        load_test_config,
        resolve_suite,
    )


# ── Container lifecycle ───────────────────────────────────────────────────────

def _shutdown(*_):
    stop_container()
    sys.exit(0)


_start_result = start_container()
if not _start_result.get("ok"):
    print(
        f"[validator] WARNING: container start failed: {_start_result.get('stderr') or _start_result.get('note')}",
        file=sys.stderr,
    )

atexit.register(stop_container)
signal.signal(signal.SIGTERM, _shutdown)
try:
    signal.signal(signal.SIGINT, _shutdown)
except (OSError, ValueError):
    pass  # not in main thread


# ── MCP server ────────────────────────────────────────────────────────────────

mcp = FastMCP(
    "validator",
    instructions=(
        "Build-validation for the generated Java project. "
        "Project source is at /workspace (maps to workspaces/ on host). "
        "Test data is at /data (read-only, maps to data/test/ on host). "
        "Edit /workspace/test_config.yaml to select which tests to run. "
        "Typical flow: compile_project → fix errors → run_test (one case) "
        "→ fix logic → run_test_all (full regression)."
    ),
)


# ── 1. Compile ────────────────────────────────────────────────────────────────

def _resolve_pom_container_path(project_dir: str | None) -> str:
    """Translate an optional host project dir into the container pom.xml path.

    Accepts None (legacy: /workspace/pom.xml), a container path (/workspace/...),
    or a host path under workspaces/ (translated via host_to_container).
    """
    if not project_dir:
        return f"{WORKSPACE_CONTAINER}/pom.xml"
    if project_dir.startswith(WORKSPACE_CONTAINER):
        base = project_dir.rstrip("/")
    else:
        from pathlib import Path as _P
        host_p = _P(project_dir).resolve()
        try:
            rel = host_p.relative_to(WORKSPACE_HOST)
            base = f"{WORKSPACE_CONTAINER}/{rel.as_posix()}".rstrip("/")
        except ValueError:
            base = f"{WORKSPACE_CONTAINER}/pom.xml"
            return base
    return f"{base}/pom.xml" if not base.endswith("/pom.xml") else base


@mcp.tool()
def compile_project(project_dir: str = "") -> dict:
    """
    Run `mvn clean compile` for the given project (default: /workspace/pom.xml).
    Returns structured compilation errors with source snippets when present.

    Args:
        project_dir: optional. Container path (/workspace/test-fra) or host path
                     (workspaces/test-fra). If empty, compiles /workspace/pom.xml.

    Returns:
        { ok: bool, errors: list[{file, line, column, message, snippet}], raw_tail: str }
    """
    pom_path = _resolve_pom_container_path(project_dir)
    r = docker_exec(["mvn", "clean", "compile", "-f", pom_path])
    output = r["stdout"] + r["stderr"]
    parsed_errors = parse_compile_errors(output)
    # docker_exec ok already reflects returncode == 0. Also surface mvn-level
    # failures (POM parse errors, dependency resolution) when parse_compile_errors
    # misses them: anything with [ERROR] in stderr but no parsed errors → record
    # a synthetic error so the caller doesn't see "ok=False errors=[]".
    if not r["ok"] and not parsed_errors:
        tail = (r["stderr"] or output)[-1500:].strip()
        if tail:
            parsed_errors = [{
                "file":    pom_path,
                "line":    0,
                "column":  0,
                "message": tail.splitlines()[0][:200] if tail else "mvn failed",
                "snippet": tail,
            }]
    return {
        "ok":       r["ok"],
        "errors":   parsed_errors,
        "raw_tail": output[-3000:],
    }


# ── 2. Single test ────────────────────────────────────────────────────────────

def _to_container_data_path(p: str) -> str:
    """Convert a host path under data/test/ to its /data/... container path.

    Pass-through for paths already starting with /data/ or /workspace/.
    """
    if p.startswith(DATA_CONTAINER + "/") or p.startswith(WORKSPACE_CONTAINER + "/"):
        return p
    from pathlib import Path as _P
    host_p = _P(p).resolve()
    try:
        rel = host_p.relative_to(DATA_HOST)
        return f"{DATA_CONTAINER}/{rel.as_posix()}"
    except ValueError:
        return p  # leave as-is; caller will report not-found if it doesn't resolve


@mcp.tool()
def run_test(fpml_file: str, expected_json_file: str, project_dir: str = "") -> dict:
    """
    Package the project, run the JAR on one FpML file, diff output vs expected CDM JSON.

    Args (host or container paths both accepted):
        fpml_file:          FpML XML (host or /data/...)
        expected_json_file: Expected CDM JSON (host or /data/...)
        project_dir:        optional. Path to the Maven project (host or /workspace/...).
                            If empty, packages /workspace/pom.xml.

    Returns:
        { ok: bool, match: bool, crash: {...}|null, differences: list[str], actual_json: str }
    """
    pom_path = _resolve_pom_container_path(project_dir)
    pkg = docker_exec(
        ["mvn", "package", "-DskipTests", "-q", "-f", pom_path],
        timeout=600,
    )
    if not pkg["ok"]:
        return {"ok": False, "match": False, "crash": None,
                "differences": ["Package failed: " + (pkg["stderr"] or pkg["stdout"])[-500:]],
                "actual_json": ""}

    # Find the jar in the project's target/ dir (not the workspace root).
    project_target = pom_path.rsplit("/pom.xml", 1)[0] + "/target"
    jar_search = docker_exec([
        "find", project_target, "-name", "*-jar-with-dependencies.jar", "-type", "f",
    ])
    jars = [j for j in (jar_search.get("stdout") or "").strip().splitlines() if j]
    if not jars:
        return {"ok": False, "match": False, "crash": None,
                "differences": [f"No jar-with-dependencies found in {project_target}/"],
                "actual_json": ""}
    jar = sorted(jars)[-1]

    fpml_container = _to_container_data_path(fpml_file)
    cdm_host = container_to_host(_to_container_data_path(expected_json_file))
    if cdm_host is None or not cdm_host.exists():
        return {"ok": False, "match": False, "crash": None,
                "differences": [f"CDM file not found: {expected_json_file}"], "actual_json": ""}

    return run_jar_and_diff(jar, fpml_container, str(cdm_host))


# ── 3. Full regression suite ──────────────────────────────────────────────────

@mcp.tool()
def run_test_all() -> dict:
    """
    Package the project once, then run it against ALL test cases in the active suite
    defined in workspaces/test_config.yaml (active_suite key).

    Call list_test_suites to see available suites and which one is active.
    Call get_test_cases to inspect what a suite contains before running.

    Returns:
        {
          ok: bool, suite: str,
          total: int, passed: int, failed: int,
          failures: list[{ fpml_file, crash, differences }]
        }
    """
    config     = load_test_config()
    suite_name = config.get("active_suite")

    if not suite_name:
        return {"ok": False, "suite": None, "total": 0, "passed": 0, "failed": 0,
                "failures": [{"fpml_file": "", "crash": None,
                              "differences": ["No active_suite set in workspaces/test_config.yaml"]}]}

    pairs = resolve_suite(suite_name)
    if not pairs:
        return {"ok": False, "suite": suite_name, "total": 0, "passed": 0, "failed": 0,
                "failures": [{"fpml_file": "", "crash": None,
                              "differences": [f"Suite '{suite_name}' resolved to 0 test cases"]}]}

    # Package once
    pkg = docker_exec(
        ["mvn", "package", "-DskipTests", "-q", "-f", f"{WORKSPACE_CONTAINER}/pom.xml"],
        timeout=600,
    )
    if not pkg["ok"]:
        return {"ok": False, "suite": suite_name, "total": 0, "passed": 0, "failed": 0,
                "failures": [{"fpml_file": "", "crash": None,
                              "differences": ["Package failed: " + pkg["stderr"][-500:]]}]}

    jar = resolve_jar_container()
    if not jar:
        return {"ok": False, "suite": suite_name, "total": 0, "passed": 0, "failed": 0,
                "failures": [{"fpml_file": "", "crash": None,
                              "differences": ["No jar-with-dependencies found"]}]}

    total, passed, failures = 0, 0, []
    for fpml_container, cdm_host in pairs:
        total += 1
        result = run_jar_and_diff(jar, fpml_container, cdm_host)
        if result["match"]:
            passed += 1
        else:
            failures.append({
                "fpml_file":   fpml_container,
                "crash":       result["crash"],
                "differences": result["differences"],
            })

    return {
        "ok":       True,
        "suite":    suite_name,
        "total":    total,
        "passed":   passed,
        "failed":   total - passed,
        "failures": failures,
    }


# ── 4. Arbitrary test (debug) ─────────────────────────────────────────────────

@mcp.tool()
def run_arbitrary_test(fpml_file: str, expected_json_file: str = "") -> dict:
    """
    Run the JAR on an FpML file and return raw stdout/stderr without packaging.
    Useful for debugging crashes or inspecting the raw CDM output.
    Assumes the JAR is already built — call compile_project or run_test first.

    Args:
        fpml_file:          Container path to the FpML XML file
        expected_json_file: Optional container path to expected CDM JSON (triggers a diff if provided)

    Returns:
        { ok: bool, stdout: str, stderr: str, crash: {...}|null, diff: list[str]|null }
    """
    jar = resolve_jar_container()
    if not jar:
        return {"ok": False, "stdout": "", "stderr": "No JAR found — call compile_project first",
                "crash": None, "diff": None}

    result = docker_exec(["java", "-jar", jar, fpml_file], timeout=30)
    crash  = parse_crash(result["stderr"]) if not result["ok"] and result["stderr"] else None
    diff   = None

    if expected_json_file and result["ok"] and result["stdout"].strip():
        cdm_host = container_to_host(expected_json_file)
        if cdm_host and cdm_host.exists():
            from pathlib import Path
            expected = Path(cdm_host).read_text(encoding="utf-8")
            diff = json_diff(result["stdout"].strip(), expected)

    return {
        "ok":     result["ok"],
        "stdout": result["stdout"],
        "stderr": result["stderr"],
        "crash":  crash,
        "diff":   diff,
    }


# ── 5. List suites ────────────────────────────────────────────────────────────

@mcp.tool()
def list_test_suites() -> dict:
    """
    List available test suites from workspaces/test_config.yaml with their case counts.
    Use this to choose which suite to activate before running run_test_all.

    Returns:
        { active_suite: str|null, suites: list[{name, case_count, dirs, active}] }
    """
    config = load_test_config()
    active = config.get("active_suite")
    suites = []
    for name, suite in config.get("suites", {}).items():
        pairs = resolve_suite(name)
        dirs  = suite.get("dirs", [])
        suites.append({
            "name":       name,
            "case_count": len(pairs),
            "dirs":       dirs if dirs != "*" else ["* (all)"],
            "active":     name == active,
        })
    return {"active_suite": active, "suites": suites}


# ── 6. Get test cases ─────────────────────────────────────────────────────────

@mcp.tool()
def get_test_cases(suite_name: str) -> dict:
    """
    List all (fpml, cdm) test case pairs in a suite.
    Returns container-internal paths — pass them directly to run_test.

    Args:
        suite_name: Name of the suite (from list_test_suites)

    Returns:
        { suite: str, total: int, cases: list[{fpml, cdm}] }
    """
    from pathlib import Path
    from _internals import DATA_HOST

    pairs = resolve_suite(suite_name)
    cases = []
    for fpml_container, cdm_host_str in pairs:
        cdm_host = Path(cdm_host_str)
        try:
            rel          = cdm_host.relative_to(DATA_HOST)
            cdm_container = DATA_CONTAINER + "/" + rel.as_posix()
        except ValueError:
            cdm_container = cdm_host_str
        cases.append({"fpml": fpml_container, "cdm": cdm_container})

    return {"suite": suite_name, "total": len(cases), "cases": cases}


# ── 7. Method source extraction ───────────────────────────────────────────────

@mcp.tool()
def extract_method_source(java_source: str, method_name: str) -> dict:
    """
    Extract the full source (signature + body) of one method from Java source.
    Use this to inspect a specific method before patching it, to avoid rewriting
    the whole file unnecessarily.

    Args:
        java_source:  Full content of the .java file (read via filesystem MCP)
        method_name:  Method name to extract (e.g. "buildFixedLeg")

    Returns:
        { ok: bool, source: str, note: str }
    """
    lines  = java_source.splitlines()
    sig_re = re.compile(
        r"(?:private|public|protected)\s+(?:static\s+)?(?:[\w<>\[\],\s]+)\s+"
        + re.escape(method_name) + r"\s*\("
    )

    start_idx = next((i for i, l in enumerate(lines) if sig_re.search(l)), None)
    if start_idx is None:
        return {"ok": False, "source": "", "note": f"Method '{method_name}' not found."}

    depth, in_body, out = 0, False, []
    for line in lines[start_idx:]:
        out.append(line)
        depth += line.count("{") - line.count("}")
        if depth > 0:
            in_body = True
        if in_body and depth <= 0:
            break

    return {"ok": True, "source": "\n".join(out), "note": ""}


@mcp.tool()
def score_with_llm(
    actual_json: str,
    expected_json: str,
    fpml_context: str = "",
) -> dict:
    """
    LLM-as-judge: semantic evaluation of a CDM JSON mapping against the expected output.

    The LLM scores the mapping quality on a 0–100 scale and explains what is
    missing, wrong, or correct.  Use this AFTER run_test / run_test_all to get
    a qualitative view of the remaining gaps.

    Args:
        actual_json:   CDM JSON produced by the converter (string)
        expected_json: Reference CDM JSON (string)
        fpml_context:  Optional: the raw FpML XML snippet for extra context

    Returns:
        {
          ok: bool,
          score: 0-100,
          grade: "A"|"B"|"C"|"D"|"F",
          summary: str,
          critical_issues: list[str],
          minor_issues:    list[str],
          strengths:       list[str],
        }
    """
    import os
    import json as _json

    vllm_url   = os.environ.get("VLLM_BASE_URL", os.environ.get("VLLM_URL", "http://10.27.40.184:8000/v1"))
    vllm_model = os.environ.get("VLLM_MODEL",  "/models/qwen3.6-27b-fp8")

    context_block = ""
    if fpml_context:
        context_block = f"\n\n### FpML input (reference)\n```xml\n{fpml_context[:3000]}\n```"

    prompt = f"""You are an expert in the ISDA Common Domain Model (CDM).
Compare the following two CDM JSON objects and evaluate the quality of the mapping.

### Expected CDM JSON
```json
{expected_json[:4000]}
```

### Actual CDM JSON (produced by the converter)
```json
{actual_json[:4000]}
```{context_block}

Return a JSON object with exactly these fields:
{{
  "score": <integer 0-100>,
  "grade": "<A|B|C|D|F>",
  "summary": "<one paragraph>",
  "critical_issues": ["<issue 1>", ...],
  "minor_issues": ["<issue 1>", ...],
  "strengths": ["<strength 1>", ...]
}}

Grading scale: A=90-100, B=75-89, C=60-74, D=40-59, F=0-39.
Return ONLY the JSON object, no markdown fences, no preamble."""

    try:
        import httpx
        from openai import OpenAI

        http_client = httpx.Client(timeout=httpx.Timeout(read=120.0, connect=10.0, write=30.0))
        client = OpenAI(base_url=vllm_url, api_key="not-needed", http_client=http_client)
        response = client.chat.completions.create(
            model=vllm_model,
            messages=[{"role": "user", "content": prompt}],
            temperature=0.0,
            extra_body={"chat_template_kwargs": {"enable_thinking": False}},
        )
        raw = response.choices[0].message.content.strip()
        # Strip accidental markdown fences
        if raw.startswith("```"):
            raw = raw.split("```")[1]
            if raw.startswith("json"):
                raw = raw[4:]
        result = _json.loads(raw)
        result["ok"] = True
        return result
    except _json.JSONDecodeError as e:
        return {"ok": False, "error": f"LLM returned invalid JSON: {e}", "raw": raw}
    except Exception as e:
        return {"ok": False, "error": str(e)}


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--transport", default="stdio", choices=["stdio", "streamable-http", "streamablehttp"])
    parser.add_argument("--port", type=int, default=8003)
    args = parser.parse_args()
    transport = args.transport.replace("streamablehttp", "streamable-http")
    if transport == "streamable-http":
        mcp.settings.port = args.port
        mcp.settings.host = "0.0.0.0"
        mcp.run(transport="streamable-http")
    else:
        mcp.run(transport="stdio")

