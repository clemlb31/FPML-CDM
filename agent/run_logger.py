"""Run observability — a small, self-contained logger for autonomous runs.

Independent of the agent loop: instantiate it with a project_dir, hand it events,
and it does two things:

  1. streams a human-readable line to stdout AND appends it to `<project_dir>/run.log`
  2. tracks per-run state (best score, step count, timings) and writes a final
     `<project_dir>/run_summary.json` you can read in 10 seconds the next morning.

It parses `compile_project` / `run_test` tool outputs to surface the signal that
matters — compile ok/errors and the match score + diff count — without the caller
needing to know those formats.

Nothing here imports the agent; the agent imports this. Keep it that way.
"""
from __future__ import annotations

import json
import re
import time
from pathlib import Path
from typing import Any


def _coerce_json(text: str) -> dict | None:
    """Best-effort parse of a tool result string into a dict (handles fenced JSON)."""
    if not text:
        return None
    s = text.strip()
    if not s.startswith("{"):
        m = re.search(r"\{.*\}", s, re.DOTALL)
        if not m:
            return None
        s = m.group(0)
    try:
        obj = json.loads(s)
        return obj if isinstance(obj, dict) else None
    except (json.JSONDecodeError, ValueError):
        return None


class RunLogger:
    """Streams progress to stdout + run.log and accumulates a final summary."""

    def __init__(self, project_dir: str | Path, run_name: str = "run"):
        self.project_dir = Path(project_dir)
        self.project_dir.mkdir(parents=True, exist_ok=True)
        self.log_path = self.project_dir / "run.log"
        self.summary_path = self.project_dir / "run_summary.json"
        self.trace_path = self.project_dir / "trace.jsonl"   # rich per-turn trace
        self.run_name = run_name
        self._start = time.monotonic()

        # Accumulated state for the summary.
        self.best_score: float | None = None
        self.last_score: float | None = None
        self.last_diff_count: int | None = None
        self.test_runs = 0
        self.compile_runs = 0
        self.compile_failures = 0
        self.tool_calls = 0
        self.steps = 0

        # Truncate any previous run.log / trace for this workspace.
        self.log_path.write_text("", encoding="utf-8")
        self.trace_path.write_text("", encoding="utf-8")
        self.log(f"=== run start: {run_name} ===")

    # ── core sink ────────────────────────────────────────────────────────────
    def log(self, msg: str) -> None:
        line = f"[{self._elapsed():6.1f}s] {msg}"
        print(line, flush=True)
        with self.log_path.open("a", encoding="utf-8") as fh:
            fh.write(line + "\n")

    def _elapsed(self) -> float:
        return time.monotonic() - self._start

    # ── loop events ──────────────────────────────────────────────────────────
    def step(self, label: str, step: int, max_iter: int) -> None:
        self.steps = max(self.steps, step)
        self.log(f"[{label}] step {step}/{max_iter}")

    def note_tools(self, label: str, names: list[str]) -> None:
        self.tool_calls += len(names)
        self.log(f"[{label}] {len(names)} tool call(s): {', '.join(names)}")

    def record_tool(self, name: str, output: str) -> None:
        """Surface the meaningful signal from compile_project / run_test results."""
        if name == "compile_project":
            self.compile_runs += 1
            obj = _coerce_json(output)
            ok = bool(obj.get("ok")) if obj else ("error" not in (output or "").lower())
            if not ok:
                self.compile_failures += 1
            n_err = len(obj.get("errors") or []) if obj else None
            detail = f"{n_err} error(s)" if n_err is not None else ""
            self.log(f"    ↳ compile_project: {'OK' if ok else 'FAIL'} {detail}".rstrip())

        elif name == "run_test":
            self.test_runs += 1
            obj = _coerce_json(output)
            score = None
            diffs = None
            matched = total = None
            if obj:
                score = obj.get("score")
                match = obj.get("match")
                sd = obj.get("score_detail") or {}
                matched, total = sd.get("matched"), sd.get("total")
                # The validator surfaces the diff list under `differences`; older/other
                # shapes use `diffs`/`diff_count`. Count whichever is present.
                d = obj.get("differences")
                if not isinstance(d, list):
                    d = obj.get("diffs")
                diffs = len(d) if isinstance(d, list) else obj.get("diff_count")
            else:
                m = re.search(r"score\D+([0-9]+(?:\.[0-9]+)?)", output or "")
                score = float(m.group(1)) if m else None
                match = "match=true" in (output or "")
            if isinstance(score, (int, float)):
                self.last_score = float(score)
                self.best_score = max(self.best_score or 0.0, float(score))
            self.last_diff_count = diffs if isinstance(diffs, int) else self.last_diff_count
            fields = f" {matched}/{total} fields" if matched is not None and total is not None else ""
            self.log(
                f"    ↳ run_test: match={obj.get('match') if obj else match} "
                f"score={score} diffs={diffs}{fields} (best={self.best_score})"
            )

    def error(self, name: str, msg: str) -> None:
        self.log(f"    ↳ {name} ERROR: {msg[:200]}")

    # ── Rich per-turn trace ──────────────────────────────────────────────────
    @staticmethod
    def _arg_brief(name: str, args: dict) -> str:
        """One-line, human-friendly summary of a tool call's args for run.log."""
        a = args or {}
        if name in ("write_file", "edit_file"):
            extra = f", {len(str(a.get('content','')))}c" if "content" in a else ""
            if name == "edit_file":
                extra = f", {len(a.get('edits') or [])} edit(s)"
            return f"{a.get('path','?')}{extra}"
        if name == "grep":
            return f"{a.get('pattern','?')!r}" + (f" in {a['include']}" if a.get("include") else "")
        if name in ("read_file", "read_text_file", "mkdir_p"):
            return str(a.get("path", "?"))
        if name in ("compile_project", "run_test"):
            return str(a.get("project_dir", ""))
        if name == "spawn_subagent":
            return (a.get("task", "") or "")[:60]
        # generic: cap each value
        return ", ".join(f"{k}={str(v)[:40]}" for k, v in a.items())

    # Keys whose full value we ALWAYS keep in the trace — the actual code the LLM
    # emitted, so the trace is a faithful audit of what was generated vs copied.
    _FULL_ARG_KEYS = {"content", "edits", "new_string", "newText"}

    @classmethod
    def _cap_args(cls, args: dict, name: str | None = None) -> dict:
        """Cap long string arg values so trace.jsonl stays readable.

        Exception: for write_file / edit_file the code-bearing args (`content`,
        `edits`, …) are kept in FULL so the trace records exactly what the model
        wrote — that is the point of an audit trail. Everything else is still capped.
        """
        keep_full = cls._FULL_ARG_KEYS if name in ("write_file", "edit_file") else set()
        out = {}
        for k, v in (args or {}).items():
            if k in keep_full:
                out[k] = v
            elif isinstance(v, str) and len(v) > 400:
                out[k] = v[:400] + f"…(+{len(v) - 400}c)"
            else:
                out[k] = v
        return out

    def turn(self, label, step, text, reasoning, calls, results) -> None:
        """Record one model turn: its text, reasoning, tool calls (w/ args), results.

        `calls`   : list of (name, args_dict)
        `results` : list of (name, result_str)
        Writes a rich line to trace.jsonl AND a compact line to run.log; updates
        score tracking from the results.
        """
        self.tool_calls += len(calls)
        for name, out in results:
            self.record_tool(name, out)

        if calls:
            brief = "  ".join(f"{n}({self._arg_brief(n, a)})" for n, a in calls)
            self.log(f"    → {brief}")
        elif text:
            self.log(f"    (no tool call) {text.strip()[:120]}")

        rec = {
            "step": step,
            "label": label,
            "t": round(self._elapsed(), 1),
            "text": (text or "").strip()[:1500],
            "reasoning": (reasoning or "").strip()[:2500],
            "tool_calls": [{"name": n, "args": self._cap_args(a, n)} for n, a in calls],
            "results": [{"name": n, "preview": (o or "")[:500]} for n, o in results],
        }
        with self.trace_path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(rec, ensure_ascii=False) + "\n")

    # ── finalisation ─────────────────────────────────────────────────────────
    def finish(self, status: str, iterations: int, message: str = "") -> dict[str, Any]:
        summary = {
            "run_name": self.run_name,
            "status": status,                 # SUCCESS | FAIL | MAX_ITER | ERROR
            "iterations": iterations,
            "duration_s": round(self._elapsed(), 1),
            "best_score": self.best_score,
            "last_score": self.last_score,
            "last_diff_count": self.last_diff_count,
            "test_runs": self.test_runs,
            "compile_runs": self.compile_runs,
            "compile_failures": self.compile_failures,
            "tool_calls": self.tool_calls,
            "message": message[:500],
        }
        self.summary_path.write_text(json.dumps(summary, indent=2), encoding="utf-8")
        self.log(
            f"=== run end: {status} | {iterations} iters | best_score={self.best_score} "
            f"| {summary['duration_s']}s | summary → {self.summary_path.name} ==="
        )
        return summary
