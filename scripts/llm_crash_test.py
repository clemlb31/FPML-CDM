"""Realistic large-context coding stress test for an LLM endpoint.

Feeds the model a BIG, realistic agentic context — large chunks of the project's
knowledge base (mapping rules, FpML guides, CDM builder conventions, the CDM
structure skeleton, rosetta schemas) plus a full real FpML example and its
expected CDM — then asks it to write a LONG, complete Java mapping method (and,
as a stretch, the whole transformer). It traces every prompt and every deepseek
response to readable .txt files and records length / truncation / contract pass.

Run (default: openrouter / deepseek-v4-flash):
    .venv/bin/python scripts/llm_crash_test.py
    .venv/bin/python scripts/llm_crash_test.py --kb-budget 180000 --backend openrouter

Outputs under --project-dir (default workspaces/llm-crash-deepseek/):
    llm_crash_report.json
    traces/<label>.prompt.txt      ← exact prompt sent
    traces/<label>.response.txt    ← exact deepseek output
"""
from __future__ import annotations

import argparse
import asyncio
import glob
import json
import re
import sys
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

from dotenv import load_dotenv
from langchain_core.messages import HumanMessage

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

load_dotenv(ROOT / ".env")

from agent.llm_call import build_backend                       # noqa: E402
from agent.llm_call.config import load_config                  # noqa: E402


# ── Backend selection (non-invasive: builds a backend without touching agent.yaml) ──

def make_backend(backend_name: str, model: str | None):
    cfg = load_config()
    backends = cfg.get("backends") or {}
    if backend_name not in backends:
        raise SystemExit(f"backend {backend_name!r} not in configs/agent.yaml (known: {list(backends)})")
    params = {**(cfg.get("defaults") or {}), **(backends[backend_name] or {})}
    backend = build_backend(backend_name, params)
    if model:
        backend.model = model
    return backend


# ── Endpoint helpers (inlined; the original imported these from scripts.helpers) ──

def _scale_max_tokens_for_thinking_models(requested: int) -> int:
    return requested                                           # deepseek-v4-flash: no scaling


def _suppress_thinking(prompt: Any) -> Any:
    return prompt


def _format_llm_error(*, label: str, error: BaseException) -> str:
    return f"[{label}] {type(error).__name__}: {error}"


def raw_llm_content(resp) -> str:
    return getattr(resp, "text", "") or ""


def _finish_reason(resp) -> str:
    """OpenAI finish_reason: 'stop' (model finished) vs 'length' (hit max_tokens → truncated)."""
    try:
        return resp.raw.choices[0].finish_reason or "?"
    except Exception:
        return "?"


def _strip_code_fences(text: str) -> str:
    text = re.sub(r"^```[a-zA-Z]*\n?", "", (text or "").strip())
    text = re.sub(r"\n?```\s*$", "", text)
    return text.strip()


def _check(text: str, *, contract: str, label: str) -> str:
    """Validate the model output against a shape contract; raises on violation."""
    if contract in ("class", "json"):
        text = _strip_code_fences(text)
    stripped = (text or "").strip()
    if not stripped:
        raise ValueError(f"contract: returned empty output for {label}")

    if contract == "method_body":
        if "```" in text:
            raise ValueError(f"contract: markdown fences present for {label}")
        last = stripped.splitlines()[-1].strip()
        if not (last.endswith(";") or last.endswith("}")):
            raise ValueError(f"contract: last statement not terminated for {label}: {last[:60]!r}")
        if "return" not in stripped:
            raise ValueError(f"contract: no return statement for {label}")
    elif contract == "class":
        if "class" not in stripped or "build" not in stripped.lower():
            raise ValueError(f"contract: does not look like a transformer class for {label}")
        if not stripped.rstrip().endswith("}"):
            raise ValueError(f"contract: class not closed (likely truncated) for {label}")
    elif contract == "json":
        if not stripped.lstrip().startswith(("[", "{")):
            raise ValueError(f"contract: not JSON for {label}")
        json.loads(stripped)                                  # raises JSONDecodeError if malformed
    return stripped


def parse_llm(resp, *, label: str, contract: str) -> str:
    return _check(raw_llm_content(resp), contract=contract, label=label)


def trace_llm(project_dir: str, *, label: str, prompt: str, raw: str, parsed: str, error: str) -> None:
    traces = Path(project_dir) / "traces"
    traces.mkdir(parents=True, exist_ok=True)
    safe = re.sub(r"[^a-zA-Z0-9_-]+", "_", label)
    (traces / f"{safe}.json").write_text(
        json.dumps({"label": label, "prompt": prompt, "raw": raw, "parsed": parsed, "error": error},
                   indent=2, ensure_ascii=False), encoding="utf-8")
    # Human-readable side files so the prompts/responses can be opened directly.
    (traces / f"{safe}.prompt.txt").write_text(prompt, encoding="utf-8")
    (traces / f"{safe}.response.txt").write_text(raw or error or "(no output)", encoding="utf-8")


# ── Knowledge pack (discovered dynamically — robust to KB reorganisation) ───────
# Ordered by relevance to a rates/IRS mapping task. Each entry: (glob, per-file cap).
# Missing files are skipped. Accumulated up to a total char budget.
_KB = ROOT / "knowledge_base"
_KB_SOURCES: list[tuple[str, int]] = [
    ("mapping/principles.md", 8000),
    ("mapping/rates.md", 12000),
    ("fpml/document-structure.md", 8000),
    ("fpml/rates.md", 8000),
    ("cdm/builder-conventions.md", 8000),
    ("cdm/enums.md", 8000),
    ("cdm/dates.md", 8000),
    ("cdm/pitfalls.md", 8000),
    ("cdm/meta-and-references.md", 8000),
    ("cdm/object-model.md", 8000),
    ("cdm/rosetta/*floatingrate*.rosetta", 12000),
    ("cdm/rosetta/*datetime*.rosetta", 16000),
    ("cdm/rosetta/product-template-type.rosetta", 24000),
    ("cdm/structure-skeleton.json", 40000),
    ("cdm/hierarchy.txt", 40000),
]


def _read_capped(path: Path, cap: int) -> str:
    try:
        text = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        text = path.read_text(encoding="utf-8-sig")
    return text[:cap]


def build_knowledge_pack(total_budget: int) -> tuple[str, list[dict]]:
    parts: list[str] = []
    manifest: list[dict] = []
    used = 0
    for pattern, cap in _KB_SOURCES:
        for fp in sorted(glob.glob(str(_KB / pattern))):
            if used >= total_budget:
                break
            p = Path(fp)
            chunk = _read_capped(p, min(cap, total_budget - used))
            if not chunk.strip():
                continue
            rel = p.relative_to(ROOT)
            parts.append(f"\n===== KNOWLEDGE FILE: {rel} =====\n{chunk}")
            manifest.append({"file": str(rel), "chars": len(chunk)})
            used += len(chunk)
    return "".join(parts), manifest


# ── Real FpML/CDM examples (this project's paths) ──────────────────────────────

_IRD_DIR = ROOT / "data" / "test" / "interest-rate-derivatives-5-13"


def _example(name: str) -> dict[str, str]:
    return {
        "name": name,
        "fpml": _read_capped(_IRD_DIR / "fpml" / f"{name}.xml", 40000),
        "cdm":  _read_capped(_IRD_DIR / "cdm" / f"{name}.json", 40000),
    }


# ── Prompt builders ────────────────────────────────────────────────────────────

_PREAMBLE = (
    "You are a focused sub-agent writing Java for an FpML 5.x → CDM 6.19 transformer.\n"
    "You have already researched the domain; the relevant knowledge base is below.\n"
)

_HELPERS = (
    "Available helper methods (already implemented elsewhere in the class):\n"
    "  - String getText(Element, String tagName)\n"
    "  - Element getElement(Element, String tagName)\n"
    "  - List<Element> getElements(Element, String tagName)\n"
)


def _ctx_block(kb: str, ex: dict[str, str]) -> str:
    return (
        f"{_PREAMBLE}\n"
        "================= KNOWLEDGE BASE (large excerpts) =================\n"
        f"{kb}\n\n"
        "================= REAL FpML EXAMPLE =================\n"
        f"File: {ex['name']}.xml\n{ex['fpml']}\n\n"
        "================= EXPECTED CDM (ground truth) =================\n"
        f"File: {ex['name']}.json\n{ex['cdm']}\n\n"
        f"{_HELPERS}\n"
    )


def _long_method_prompt(kb: str, ex: dict[str, str]) -> str:
    return _ctx_block(kb, ex) + (
        "================= TASK =================\n"
        "Write the COMPLETE body of this method:\n\n"
        "  private InterestRatePayout buildFloatingLeg(Element context)\n\n"
        "It must map EVERY field present in the floating InterestRatePayout of the expected\n"
        "CDM above — do not abbreviate, do not leave TODOs, do not stop early. Cover at least:\n"
        "  - payerReceiver (payer/receiver counterparty roles)\n"
        "  - priceQuantity / notional schedule\n"
        "  - rateSpecification: floating rate index, index tenor, spread\n"
        "  - dayCountFraction (enum mangling per the cheat-sheet)\n"
        "  - calculationPeriodDates (effective/termination dates, calculation period frequency,\n"
        "    business day adjustments, all nested sub-objects)\n"
        "  - paymentDates and resetDates\n\n"
        "Constraints:\n"
        "  - Output ONLY the method body (NO signature, NO surrounding braces).\n"
        "  - Use the REAL CDM builder method/enum names from the knowledge base — do not invent any.\n"
        "  - Use Date.of(y,m,d), never LocalDate, never Date.parse.\n"
        "  - Every statement ends with ';' or is a brace block. No markdown fences, no comments.\n"
        "  - End with: return <theBuiltInterestRatePayout>;\n"
        "Write the full, long method now.\n"
    )


def _full_transformer_prompt(kb: str, ex: dict[str, str]) -> str:
    return _ctx_block(kb, ex) + (
        "================= TASK =================\n"
        "Write the COMPLETE Java class `IrsTransformer` that converts this FpML into the expected\n"
        "CDM above. Implement the public entry `public TradeState transform(Element root)` AND every\n"
        "build* method it needs (buildTradeState, buildTrade, buildParties, buildFixedLeg,\n"
        "buildFloatingLeg, buildCalculationPeriodDates, buildPaymentDates, …), each with a FULL body\n"
        "that maps the real fields — no stubs, no TODOs, no '...'. Use only real CDM builder/enum\n"
        "names from the knowledge base.\n\n"
        "Constraints:\n"
        "  - Output ONLY Java (the full class). No markdown fences, no prose.\n"
        "  - Use Date.of(y,m,d). Do not invent helpers beyond getText/getElement/getElements.\n"
        "  - The class must be syntactically complete and end with a closing brace.\n"
        "Write the entire class now, however long it needs to be.\n"
    )


# ── Cases ──────────────────────────────────────────────────────────────────────

@dataclass
class CrashCase:
    label: str
    build_prompt: Any            # callable(kb, ex) -> str
    example: str
    max_tokens: int
    contract: str                # "method_body" | "class" | "json"
    notes: str = ""


@dataclass
class CrashResult:
    label: str
    status: str
    error_class: str
    finish_reason: str
    truncated: bool
    elapsed_seconds: float
    max_tokens: int
    prompt_chars: int
    prompt_tokens_est: int
    raw_chars: int
    output_tokens_est: int
    parsed_ok: bool
    notes: str
    error: str = ""


def _cases() -> list[CrashCase]:
    return [
        CrashCase(
            label="long-method/floating-leg",
            build_prompt=_long_method_prompt,
            example="ird-ex43-rfr-compound-swap-rate-cutoff",
            max_tokens=8000,
            contract="method_body",
            notes="Long single mapping method, full real context (compound RFR swap).",
        ),
        CrashCase(
            label="full-transformer/ois",
            build_prompt=_full_transformer_prompt,
            example="ird-ex07a-ois-swap",
            max_tokens=12000,
            contract="class",
            notes="Stretch: whole transformer class in one shot (max-length probe).",
        ),
    ]


def _classify_error(text: str) -> str:
    lowered = (text or "").lower()
    if "no loaded model" in lowered or "no models loaded" in lowered:
        return "no_model_loaded"
    if "model not found" in lowered or "notfounderror" in lowered:
        return "model_not_found"
    if "context length" in lowered or "maximum context" in lowered or "too many tokens" in lowered:
        return "context_overflow"
    if "contract: returned empty" in lowered:
        return "empty_output"
    if "truncated" in lowered or "not closed" in lowered:
        return "truncated_output"
    if "contract:" in lowered:
        return "contract_violation"
    if "cannot connect" in lowered or "connection error" in lowered or "connecterror" in lowered:
        return "connection_error"
    if "timeout" in lowered or "cancelled" in lowered:
        return "timeout"
    if "jsondecode" in lowered or "expecting value" in lowered:
        return "malformed_response"
    if "401" in lowered or "unauthorized" in lowered or "api key" in lowered:
        return "auth_error"
    return "other"


async def _run_case(case: CrashCase, *, backend, kb: str, project_dir: str) -> CrashResult:
    ex = _example(case.example)
    prompt_text = case.build_prompt(kb, ex)
    prompt = _suppress_thinking([HumanMessage(content=prompt_text)])
    effective = _scale_max_tokens_for_thinking_models(case.max_tokens)

    start = time.perf_counter()
    raw = error_text = ""
    finish = "?"
    parsed_ok = False
    try:
        resp = await backend.complete(label=case.label, prompt=prompt, max_tokens=effective)
        raw = raw_llm_content(resp)
        finish = _finish_reason(resp)
        parse_llm(resp, label=case.label, contract=case.contract)
        parsed_ok = True
        status, error_class = "ok", "none"
    except Exception as exc:  # noqa: BLE001
        error_text = _format_llm_error(label=case.label, error=exc)
        status, error_class = "error", _classify_error(error_text)
    elapsed = time.perf_counter() - start
    truncated = (finish == "length")

    trace_llm(project_dir, label=case.label, prompt=prompt_text, raw=raw, parsed=raw, error=error_text)

    return CrashResult(
        label=case.label, status=status, error_class=error_class,
        finish_reason=finish, truncated=truncated,
        elapsed_seconds=round(elapsed, 2), max_tokens=case.max_tokens,
        prompt_chars=len(prompt_text), prompt_tokens_est=len(prompt_text) // 4,
        raw_chars=len(raw or ""), output_tokens_est=len(raw or "") // 4,
        parsed_ok=parsed_ok, notes=case.notes, error=error_text,
    )


async def _main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend", default="openrouter")
    parser.add_argument("--model", default="deepseek/deepseek-v4-flash")
    parser.add_argument("--kb-budget", type=int, default=140000, help="max chars of knowledge base to inject")
    parser.add_argument("--project-dir", default=str(ROOT / "workspaces" / "llm-crash-deepseek"))
    parser.add_argument("--report", default=None)
    args = parser.parse_args()

    project_dir = str(Path(args.project_dir).resolve())
    report_path = Path(args.report) if args.report else Path(project_dir) / "llm_crash_report.json"

    backend = make_backend(args.backend, args.model)
    kb, manifest = build_knowledge_pack(args.kb_budget)
    kb_chars = sum(m["chars"] for m in manifest)
    print(f"endpoint: {args.backend} model={backend.model}")
    print(f"knowledge pack: {len(manifest)} files, {kb_chars} chars (~{kb_chars//4} tok)")
    for m in manifest:
        print(f"   + {m['file']}  ({m['chars']} ch)")
    print()

    results: list[CrashResult] = []
    for case in _cases():
        print(f"[{case.label}] running (max_tokens={case.max_tokens})...")
        r = await _run_case(case, backend=backend, kb=kb, project_dir=project_dir)
        results.append(r)
        print(f"[{case.label}] status={r.status} class={r.error_class} finish={r.finish_reason} "
              f"truncated={r.truncated} prompt~{r.prompt_tokens_est}tok out~{r.output_tokens_est}tok "
              f"elapsed={r.elapsed_seconds}s")
        if r.error:
            print(f"    error: {r.error}")

    payload = {
        "endpoint": getattr(backend, "base_url", None),
        "model": backend.model,
        "kb_budget": args.kb_budget,
        "kb_files": manifest,
        "kb_chars": kb_chars,
        "results": [asdict(item) for item in results],
    }
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(f"\nreport: {report_path}")
    print(f"prompts/responses traced under: {Path(project_dir) / 'traces'}/")
    return 0 if all(item.status == "ok" for item in results) else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(_main()))
