"""
Internal helpers for the validator server.
Not exposed as MCP tools — called by server.py.

All commands run inside a persistent Docker/Podman container.
Path conventions:
  - WORKSPACE_CONTAINER (/workspace)  <->  WORKSPACE_HOST (workspaces/)
  - DATA_CONTAINER      (/data)        <->  DATA_HOST      (data/test/)
"""

from pathlib import Path
import subprocess
import json
import re
import shutil


# ── Configuration ─────────────────────────────────────────────────────────────

BASE_DIR            = Path(__file__).resolve().parent.parent.parent
WORKSPACE_HOST      = BASE_DIR / "workspaces"
DATA_HOST           = BASE_DIR / "data" / "test"
M2_CACHE_HOST       = Path.home() / ".m2"

CONTAINER_NAME      = "mcp-java-validator"
WORKSPACE_CONTAINER = "/workspace"
DATA_CONTAINER      = "/data"
MAVEN_IMAGE         = "maven:3.9-eclipse-temurin-21"


# ── Runtime detection ─────────────────────────────────────────────────────────

def _get_runtime() -> str:
    for rt in ("docker", "podman"):
        if shutil.which(rt):
            return rt
    raise RuntimeError("Neither docker nor podman found in PATH")


# ── Path mapping ──────────────────────────────────────────────────────────────

def _win_volume_path(p: Path) -> str:
    """Convert a Windows path to Docker-compatible volume format (forward slashes)."""
    return str(p).replace("\\", "/")


def container_to_host(container_path: str) -> Path | None:
    """Map a container-internal path back to the host filesystem."""
    if container_path.startswith(WORKSPACE_CONTAINER + "/"):
        rel = container_path[len(WORKSPACE_CONTAINER) + 1:]
        return WORKSPACE_HOST / rel
    if container_path.startswith(DATA_CONTAINER + "/"):
        rel = container_path[len(DATA_CONTAINER) + 1:]
        return DATA_HOST / rel
    return None


def host_to_container(host_path: Path) -> str | None:
    """Map a host filesystem path to a container-internal path."""
    try:
        rel = host_path.relative_to(WORKSPACE_HOST)
        return WORKSPACE_CONTAINER + "/" + rel.as_posix()
    except ValueError:
        pass
    try:
        rel = host_path.relative_to(DATA_HOST)
        return DATA_CONTAINER + "/" + rel.as_posix()
    except ValueError:
        pass
    return None


# ── Container lifecycle ───────────────────────────────────────────────────────

def container_running() -> bool:
    try:
        runtime = _get_runtime()
    except RuntimeError:
        return False
    r = subprocess.run(
        [runtime, "inspect", "--format", "{{.State.Running}}", CONTAINER_NAME],
        capture_output=True, text=True,
    )
    return r.returncode == 0 and r.stdout.strip() == "true"


def start_container() -> dict:
    """Start the persistent Maven container. Idempotent — safe to call if already running."""
    if container_running():
        return {"ok": True, "note": "already running"}

    try:
        runtime = _get_runtime()
    except RuntimeError as e:
        return {"ok": False, "note": str(e)}

    # Remove stale stopped container if present
    subprocess.run([runtime, "rm", "-f", CONTAINER_NAME], capture_output=True)

    cmd = [
        runtime, "run", "-dit",
        "--name", CONTAINER_NAME,
        "-v", f"{_win_volume_path(WORKSPACE_HOST)}:{WORKSPACE_CONTAINER}",
        "-v", f"{_win_volume_path(DATA_HOST)}:{DATA_CONTAINER}:ro",
        "-v", f"{_win_volume_path(M2_CACHE_HOST)}:/root/.m2",
        "-w", WORKSPACE_CONTAINER,
        MAVEN_IMAGE,
        "tail", "-f", "/dev/null",   # keep container alive indefinitely
    ]
    r = subprocess.run(cmd, capture_output=True, text=True)
    return {
        "ok":          r.returncode == 0,
        "container_id": r.stdout.strip(),
        "stderr":      r.stderr.strip(),
    }


def stop_container() -> None:
    """Stop and remove the container. Silent on errors."""
    try:
        runtime = _get_runtime()
        subprocess.run([runtime, "rm", "-f", CONTAINER_NAME], capture_output=True)
    except Exception:
        pass


# ── Docker exec ───────────────────────────────────────────────────────────────

def docker_exec(cmd: list[str], timeout: int = 300) -> dict:
    """Run a command inside the persistent container. Returns {ok, stdout, stderr}."""
    try:
        runtime = _get_runtime()
    except RuntimeError as e:
        return {"ok": False, "stdout": "", "stderr": str(e)}
    try:
        r = subprocess.run(
            [runtime, "exec", CONTAINER_NAME] + cmd,
            capture_output=True, text=True, timeout=timeout,
        )
        return {"ok": r.returncode == 0, "stdout": r.stdout, "stderr": r.stderr}
    except subprocess.TimeoutExpired:
        return {"ok": False, "stdout": "", "stderr": f"Timeout after {timeout}s"}
    except FileNotFoundError as e:
        return {"ok": False, "stdout": "", "stderr": str(e)}


# ── Maven helpers ─────────────────────────────────────────────────────────────

def resolve_jar_container() -> str | None:
    """Find the fat-jar in /workspace/target/ inside the container."""
    r = docker_exec([
        "find", f"{WORKSPACE_CONTAINER}/target",
        "-name", "*-jar-with-dependencies.jar",
        "-type", "f",
    ])
    if not r["ok"] or not r["stdout"].strip():
        return None
    jars = sorted(r["stdout"].strip().splitlines())
    return jars[-1] if jars else None


# ── Test config ───────────────────────────────────────────────────────────────

def load_test_config() -> dict:
    """Read workspaces/test_config.yaml from host. Returns empty config on missing file."""
    import yaml
    config_path = WORKSPACE_HOST / "test_config.yaml"
    if not config_path.exists():
        return {"active_suite": None, "suites": {}}
    with open(config_path, encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def resolve_suite(suite_name: str | None = None) -> list[tuple[str, str]]:
    """
    Expand a suite into test pairs.

    Returns list of (fpml_container_path, cdm_host_path_str):
      - fpml_container_path : passed directly to `java -jar` inside the container
      - cdm_host_path_str   : read on the host for JSON diffing
    """
    config = load_test_config()
    if suite_name is None:
        suite_name = config.get("active_suite")
    if not suite_name:
        return []

    suite = config.get("suites", {}).get(suite_name)
    if suite is None:
        return []

    dirs          = suite.get("dirs", [])
    max_files     = suite.get("max_files_per_dir")
    exclude_files = set(suite.get("exclude_files", []))

    if dirs == "*":
        dirs = sorted(d.name for d in DATA_HOST.iterdir() if d.is_dir())

    pairs: list[tuple[str, str]] = []
    for dir_name in dirs:
        fpml_dir = DATA_HOST / dir_name / "fpml"
        cdm_dir  = DATA_HOST / dir_name / "cdm"
        if not fpml_dir.exists():
            continue

        fpml_files = sorted(fpml_dir.glob("*.xml"))
        if max_files:
            fpml_files = fpml_files[:max_files]

        for fpml_path in fpml_files:
            if fpml_path.name in exclude_files:
                continue
            cdm_path = cdm_dir / (fpml_path.stem + ".json")
            if not cdm_path.exists():
                continue

            fpml_container = f"{DATA_CONTAINER}/{dir_name}/fpml/{fpml_path.name}"
            pairs.append((fpml_container, str(cdm_path)))

    return pairs


# ── Maven output parsing ──────────────────────────────────────────────────────

def parse_compile_errors(mvn_output: str, context_lines: int = 4) -> list[dict]:
    """
    Extract structured errors from `mvn compile` output.
    Each error is { file, line, column, message, snippet } or { message } for non-file errors.

    Maven runs inside the container and emits container-internal paths (/workspace/...).
    We map those back to host paths to read source snippets.
    """
    errors = []
    _file_lines_cache: dict[str, list[str]] = {}

    for line in mvn_output.splitlines():
        m = re.search(r"\[ERROR\]\s+(.*?\.java):\[(\d+),(\d+)\]\s+(.*)", line)
        if m:
            container_path = m.group(1)
            err_line  = int(m.group(2))
            err_col   = int(m.group(3))
            message   = m.group(4).strip()

            # Load source file once per file (map container path -> host path)
            if container_path not in _file_lines_cache:
                host_path = container_to_host(container_path)
                if host_path and host_path.exists():
                    try:
                        _file_lines_cache[container_path] = host_path.read_text(
                            encoding="utf-8"
                        ).splitlines()
                    except OSError:
                        _file_lines_cache[container_path] = []
                else:
                    _file_lines_cache[container_path] = []

            src_lines = _file_lines_cache[container_path]
            snippet   = _build_snippet(src_lines, err_line, err_col, context_lines)

            errors.append({
                "file":    container_path,
                "line":    err_line,
                "column":  err_col,
                "message": message,
                "snippet": snippet,
            })
        elif "[ERROR]" in line and "BUILD" not in line:
            msg = line.replace("[ERROR]", "").strip()
            if msg:
                errors.append({"message": msg})
    return errors


def _build_snippet(
    src_lines: list[str], err_line: int, err_col: int, context: int
) -> str:
    """Return a compact annotated excerpt of source lines centred on err_line."""
    if not src_lines:
        return ""

    first = max(0,           err_line - 1 - context)
    last  = min(len(src_lines), err_line - 1 + context + 1)
    width = len(str(last + 1))

    parts = []
    for i, src in enumerate(src_lines[first:last], start=first + 1):
        marker = ">" if i == err_line else " "
        parts.append(f"  {i:{width}d} {marker} {src}")
        if i == err_line and err_col > 0:
            parts.append("  " + " " * width + "   " + " " * (err_col - 1) + "^")

    return "\n".join(parts)


def parse_crash(stderr: str) -> dict:
    """
    Parse a Java stack trace into the most actionable frame.
    Returns { exception, message, method, file, line }.
    Skips JDK-internal frames — only reports com.example.* frames.
    """
    exception, msg, method, file_, line = "", "", "", "", 0

    exc_m = re.search(
        r"(?:Exception in thread \S+\s+)?([\w.$]+Exception[\w.$]*)(?::\s*(.*))?",
        stderr,
    )
    if exc_m:
        exception = exc_m.group(1)
        msg = (exc_m.group(2) or "").strip()

    for at_m in re.finditer(r"at (com\.example\.[^\(]+)\(([^:)]+):(\d+)\)", stderr):
        method = at_m.group(1)
        file_  = at_m.group(2)
        line   = int(at_m.group(3))
        break  # first user-code frame is the most relevant

    return {"exception": exception, "message": msg, "method": method, "file": file_, "line": line}


# ── JSON semantic diff ────────────────────────────────────────────────────────

def json_diff(actual_str: str, expected_str: str, max_diffs: int = 20) -> list[str]:
    """
    Compare two JSON strings and return a human-readable list of differences.
    Lists are compared semantically (order-insensitive) to avoid false positives
    on unordered CDM arrays like payout[] or party[].
    """
    try:
        actual   = json.loads(actual_str)
        expected = json.loads(expected_str)
    except json.JSONDecodeError as e:
        return [f"JSON parse error: {e}"]

    diffs: list[str] = []
    _diff_recursive(actual, expected, "", diffs, max_diffs)
    return diffs


def _diff_recursive(a, e, path: str, diffs: list, max_diffs: int):
    if len(diffs) >= max_diffs:
        return

    if type(a) != type(e):
        diffs.append(
            f"{path or 'root'}: type mismatch (got {type(a).__name__}, want {type(e).__name__})"
        )
        return

    if isinstance(e, dict):
        for k, ev in e.items():
            p = f"{path}.{k}" if path else k
            if k not in a:
                diffs.append(f"MISSING {p}  (expected: {json.dumps(ev)[:80]})")
            else:
                _diff_recursive(a[k], ev, p, diffs, max_diffs)
        for k in a:
            if k not in e:
                diffs.append(f"EXTRA   {path}.{k}")

    elif isinstance(e, list):
        if len(a) != len(e):
            diffs.append(f"{path}: list length got {len(a)}, want {len(e)}")
        # Sort both sides so element order doesn't matter
        key = lambda x: json.dumps(x, sort_keys=True)
        for i, (ai, ei) in enumerate(zip(sorted(a, key=key), sorted(e, key=key))):
            _diff_recursive(ai, ei, f"{path}[{i}]", diffs, max_diffs)

    else:
        if a != e:
            diffs.append(
                f"{path}:\n  got:  {json.dumps(a)[:80]}\n  want: {json.dumps(e)[:80]}"
            )


# ── Leaf counting & scoring ───────────────────────────────────────────────────

def _count_leaves(obj) -> int:
    """Count all primitive (leaf) values in a JSON object."""
    if isinstance(obj, dict):
        return sum(_count_leaves(v) for v in obj.values())
    elif isinstance(obj, list):
        return sum(_count_leaves(v) for v in obj) if obj else 1
    else:
        return 1  # string, number, bool, null


def _count_matching_leaves(actual, expected) -> int:
    """Count how many leaf values in expected are correctly present in actual."""
    if isinstance(actual, (int, float)) and isinstance(expected, (int, float)):
        return 1 if actual == expected else 0
    if type(actual) != type(expected):
        return 0
    if isinstance(expected, dict):
        count = 0
        for k, ev in expected.items():
            if isinstance(actual, dict) and k in actual:
                count += _count_matching_leaves(actual[k], ev)
        return count
    elif isinstance(expected, list):
        if not expected and not actual:
            return 1  # both empty
        if not isinstance(actual, list) or not actual:
            return 0
        key_fn = lambda x: json.dumps(x, sort_keys=True, default=str)
        matched = 0
        for ai, ei in zip(sorted(actual, key=key_fn), sorted(expected, key=key_fn)):
            matched += _count_matching_leaves(ai, ei)
        return matched
    else:
        return 1 if actual == expected else 0


def _collect_scored_diffs(
    a, e, path: str,
    missing: list, extra: list, wrong_vals: list, type_mismatches: list,
    max_each: int,
):
    """Walk expected vs actual and fill the four categorised diff lists."""
    if len(missing) + len(wrong_vals) >= max_each * 2:
        return
    if isinstance(e, dict):
        for k, ev in e.items():
            p = f"{path}.{k}" if path else k
            if not isinstance(a, dict) or k not in a:
                missing.append(p)
            else:
                _collect_scored_diffs(a[k], ev, p, missing, extra, wrong_vals, type_mismatches, max_each)
        if isinstance(a, dict):
            for k in a:
                if k not in e:
                    extra.append(f"{path}.{k}" if path else k)
    elif isinstance(e, list):
        if not isinstance(a, list):
            type_mismatches.append(f"{path}: got {type(a).__name__}, want list")
        elif len(a) != len(e):
            wrong_vals.append({"path": path, "got": f"len={len(a)}", "want": f"len={len(e)}"})
    else:
        if type(a) != type(e) and not (isinstance(a, (int, float)) and isinstance(e, (int, float))):
            type_mismatches.append(f"{path}: got {type(a).__name__}, want {type(e).__name__}")
        elif a != e:
            wrong_vals.append({"path": path, "got": str(a)[:80], "want": str(e)[:80]})


def json_score(actual_str: str, expected_str: str) -> dict:
    """
    Compute a numeric similarity score between actual and expected CDM JSON.

    score = matched_leaf_values / total_expected_leaf_values * 100

    Returns:
        {
          score: float (0–100),
          total: int,       # leaf count in expected
          matched: int,     # leaves that match
          missing: list[str],
          wrong_values: list[{path, got, want}],
          extra: list[str],
          type_mismatches: list[str],
        }
    """
    try:
        actual   = json.loads(actual_str) if isinstance(actual_str, str) else actual_str
        expected = json.loads(expected_str) if isinstance(expected_str, str) else expected_str
    except json.JSONDecodeError as e:
        return {"score": 0.0, "error": str(e), "total": 0, "matched": 0}

    total   = _count_leaves(expected)
    matched = _count_matching_leaves(actual, expected)

    missing, extra, wrong_vals, type_mismatches = [], [], [], []
    _collect_scored_diffs(actual, expected, "", missing, extra, wrong_vals, type_mismatches, 30)

    return {
        "score":           round(matched / total * 100, 1) if total > 0 else 0.0,
        "total":           total,
        "matched":         matched,
        "missing":         missing[:25],
        "wrong_values":    wrong_vals[:25],
        "extra":           extra[:10],
        "type_mismatches": type_mismatches[:10],
    }


# ── Single test execution ─────────────────────────────────────────────────────

def run_jar_and_diff(jar_container: str, fpml_container: str, cdm_host: str) -> dict:
    """
    Run the JAR (container path) on fpml_container, diff stdout vs cdm_host (read from host).
    Returns { ok, match, score, score_detail, crash, differences, actual_json }.
    """
    result = docker_exec(["java", "-jar", jar_container, fpml_container], timeout=30)
    actual = result["stdout"].strip()

    if not result["ok"] or not actual:
        return {
            "ok":          False,
            "match":       False,
            "score":       0.0,
            "score_detail": {},
            "crash":       parse_crash(result["stderr"]) if result["stderr"] else None,
            "differences": [],
            "actual_json": actual,
        }

    expected     = Path(cdm_host).read_text(encoding="utf-8")
    diffs        = json_diff(actual, expected)
    score_detail = json_score(actual, expected)
    return {
        "ok":           True,
        "match":        len(diffs) == 0,
        "score":        score_detail["score"],
        "score_detail": score_detail,
        "crash":        None,
        "differences":  diffs,
        "actual_json":  actual,
    }
