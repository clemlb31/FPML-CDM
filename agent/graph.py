"""LangGraph orchestration for FpML -> CDM Java generation and patch loop."""

import argparse
import asyncio
import json
import os
import sys
import textwrap
from pathlib import Path
from typing import Optional, TypedDict

from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, StateGraph
from langchain_mcp_adapters.client import MultiServerMCPClient

from agent.helpers import (
    unwrap as _unwrap,
    unwrap_dict as _unwrap_dict,
    llm_text_or_raise as _llm_text_or_raise,
    json_list_or_raise as _json_list_or_raise,
    replace_method_body_or_raise as _replace_method_body_or_raise,
    tool_text as _tool_text,
    maven_dependencies_from_raw as _maven_dependencies_from_raw,
    excerpt_if_present as _excerpt_if_present,
    method_hint as _method_hint,
    build_skeleton as _build_skeleton,
    build_pom as _build_pom,
    get_servers,
    read_training_pairs as _read_training_pairs,
    read_cdm_snippet as _read_cdm_snippet,
    _PACKAGE,
    _TRANSFORMER_CLASS,
    _MAX_PATCH_ITERATIONS,
)


# MCP server definitions imported from helpers.get_servers()
_SERVERS = get_servers()

# ── Agent state ───────────────────────────────────────────────────────────────


class MethodSpec(TypedDict):
    """
    One private Java method the transformer will contain.
    Produced by plan_node; consumed by skeleton_node and fill_methods_node.
    """
    method_name: str   # e.g. "buildFixedLeg"
    fpml_xpath:  str   # e.g. "swapStream/calculationPeriodAmount/calculation/notionalSchedule/..."
    cdm_path:    str   # e.g. "InterestRatePayout.rateSpecification.fixedRate.rateSchedule.price"
    return_type: str   # e.g. "RateSpecification"
    description: str   # short English sentence for the Javadoc stub
    transform:   str   # e.g. "new BigDecimal(text); DayCountFractionEnum switch"


class AgentState(TypedDict):
    project_dir:          str
    fpml_file:            str
    expected_json:        str
    mode:                 str               # "generate" | "patch"
    method_specs:         list[MethodSpec]  # populated by plan_node
    filled_methods:       dict[str, str]    # method_name → Java body text
    compile_errors:       list[dict]
    transform_diffs:      list[str]
    iteration:            int
    patch_target:         Optional[str]     # method name to fix in current patch cycle
    disambiguation_changed: bool            # True if human edited disambiguation.md


# ── Node: plan ─────────────────────────────────────────────────────────────────

async def plan_node(state: AgentState, tools_by_name: dict) -> AgentState:
    """
    Plan in three phases.

    Sub-call 1 (parallel, FpML specialist LLM):
        Reads get_irs_xpath_guide → summarises FpML IRS XPath structure by concern.

    Sub-call 2 (parallel, CDM specialist LLM):
        Reads get_training_pairs + get_cdm_class_hierarchy → summarises CDM class
        structure observed in real examples + official Rosetta type hierarchy.

    Sub-call 3 (synthesis):
        Combines both analyses + prior learned schema + disambiguation rules
        → produces JSON list of MethodSpec.

    In strict debug mode we run these calls sequentially (not in parallel)
    so each failure point is isolated and easier to inspect.
    """
    # ── Shared context fetchers ───────────────────────────────────────────────
    async def _fetch(tool_name: str, **kwargs) -> str:
        return await _tool_text(tools_by_name, tool_name, **kwargs)

    # ── Sub-call 1: FpML structure analysis ──────────────────────────────────
    async def analyze_fpml() -> str:
        print("[plan/fpml] Analysing FpML XPath structure...")
        xpath = await _fetch("get_irs_xpath_guide")
        prompt = (
            f"Study this FpML XPath reference:\n{xpath[:2000]}\n\n"
            "Summarise the key FpML paths grouped by concern:\n"
            "1) Trade identification\n"
            "2) Parties\n"
            "3) Fixed leg\n"
            "4) Floating leg\n"
            "5) Shared dates\n"
            "6) Calculation periods and payment dates\n"
            "Output only short lines in this format: XPath => meaning."
        )
        messages = [
            SystemMessage(content=(
                "You are an FpML 5.x IRS specialist. "
                "Analyse the XML structure so a Java developer can write DOM XPath lookups."
            )),
            HumanMessage(content=prompt),
        ]
        return await _llm_text_or_raise(
            project_dir=state["project_dir"],
            label="plan/fpml",
            prompt=messages,
            max_tokens=4000,
        )

    # ── Sub-call 2: CDM class structure analysis ──────────────────────────────
    async def analyze_cdm() -> str:
        print("[plan/cdm] Analysing CDM class structure from examples + hierarchy...")
        examples_task   = asyncio.create_task(_read_training_pairs(n=2))
        hierarchy_task  = asyncio.create_task(_fetch("get_cdm_class_hierarchy"))
        examples, hierarchy = await asyncio.gather(examples_task, hierarchy_task)

        prompt = (
            f"Training examples (FpML input → CDM JSON output):\n{examples[:1500]}\n\n"
            f"Official CDM type hierarchy:\n{hierarchy[:1200]}\n\n"
            "Summarise the CDM Java structure by concern:\n"
            "  1. Root — TradeState, Trade fields, NonTransferableProduct\n"
            "  2. Parties — Party, PartyIdentifier, Counterparty, role enum values\n"
            "  3. Product — EconomicTerms, Payout, InterestRatePayout\n"
            "  4. Rate spec — FixedRateSpecification, FloatingRateSpecification\n"
            "  5. Notional — TradeLot, PriceQuantity, NonNegativeQuantitySchedule\n"
            "  6. Dates — CalculationPeriodDates, AdjustableDate, Date.of()\n"
            "Note enum string literals you see (e.g. 'Party1', 'ACT_360') — "
            "these are the Java enum constant names."
        )

        messages = [
            SystemMessage(content=(
                "You are a CDM 6.x Java specialist. "
                "Analyse the CDM class structure from real serialised JSON examples "
                "and the official Rosetta type hierarchy. "
                "JSON keys ARE Java class names. "
                "e.g. {\"InterestRatePayout\": {...}} → class InterestRatePayout."
            )),
            HumanMessage(content=prompt),
        ]
        return await _llm_text_or_raise(
            project_dir=state["project_dir"],
            label="plan/cdm",
            prompt=messages,
            max_tokens=2500,
        )

    # Run sequentially for easier debugging/attribution.
    fpml_analysis = await analyze_fpml()
    cdm_analysis = await analyze_cdm()
    print("[plan] Analyses complete. Synthesising method specs...")

    # ── Load prior observations and disambiguation rules ──────────────────────
    learned  = await _fetch("get_learned_schema")
    disambig = await _fetch("get_disambiguation_rules")

    # ── Sub-call 3: Synthesis — produce method specs ──────────────────────────
    spec_prompt = textwrap.dedent(f"""
        Based on the FpML and CDM analyses below, produce a JSON array of method specs.
        Output ONLY valid JSON — no markdown fences, no prose.

        FpML XPath analysis:
        {fpml_analysis[:800]}

        CDM class structure analysis:
        {cdm_analysis[:800]}

        Prior schema observations (what worked/failed in earlier iterations):
        {learned[:400] if learned and "not yet created" not in learned else "(first run — no prior data)"}

        Disambiguation rules:
        {disambig[:400] if disambig and "not yet created" not in disambig else "(disambiguation.md not yet created)"}

        Rules for the JSON output:
          - Derive ALL class names from the CDM analysis above (JSON keys = Java classes).
          - Derive enum values from the observed string literals.
          - Do NOT invent class names not seen in the examples or hierarchy.
          - If return type is uncertain, use "Object" — compile loop will fix it.

        Required fields per element:
          method_name  (camelCase Java method name)
          fpml_xpath   (which FpML element(s) this method reads)
          cdm_path     (which CDM JSON path this method populates)
          return_type  (Java return type, derived from CDM analysis)
          description  (one sentence)
          transform    (key logic inferred from comparing FpML to CDM JSON)

        Design guidelines:
          - One method per logical group (leg, party, dates, qualification, etc.)
          - buildTradeState must be the top-level orchestrator returning TradeState
          - Include at least: buildTradeState, buildParties, buildTradeLot,
            buildFixedLeg, buildFloatingLeg, buildCalculationPeriodDates,
            buildPaymentDates, mapDayCountFraction, mapFloatingRateIndex,
            resolvePartyRole, buildQualification
    """).strip()

    raw = await _llm_text_or_raise(
        project_dir=state["project_dir"],
        label="plan/synth",
        prompt=[HumanMessage(content=spec_prompt)],
        max_tokens=8000,
        strip_fences=True,
    )
    specs: list[MethodSpec] = _json_list_or_raise(raw, label="plan/synth")

    print(f"[plan] {len(specs)} method specs: {[s['method_name'] for s in specs]}")
    return {**state, "method_specs": specs}


# ── Node: skeleton ─────────────────────────────────────────────────────────────

async def skeleton_node(state: AgentState, tools_by_name: dict) -> AgentState:
    """
    Phase 2: Pure Python writes IrsTransformer.java, FpmlToCdmApp.java, and pom.xml.
    No LLM — deterministic and fast.
    """
    # Fetch Maven dependency XML from cdm_server
    dep_tool = tools_by_name.get("get_maven_dependencies")
    if dep_tool:
        raw = await dep_tool.ainvoke({})
        deps_xml, dep_blocks, props_xml, repos_xml, raw_text = _maven_dependencies_from_raw(raw)
    else:
        print("[skeleton] WARNING: get_maven_dependencies tool not available, using static pom.xml")
        deps_xml, dep_blocks, props_xml, repos_xml, raw_text = "", [], "", "", ""

    print(f"[skeleton] Extracted {len(dep_blocks)} dependency blocks for pom.xml")
    if not dep_blocks:
        print(f"[skeleton] WARNING: No dependencies found! Raw response length: {len(raw_text)}")
        print(f"[skeleton] First 500 chars of raw: {raw_text[:500]}")

    transformer_src, app_src, semantic_diff_src = _build_skeleton(state["method_specs"])
    pom_src = _build_pom(deps_xml, properties_xml=props_xml, repositories_xml=repos_xml)

    pkg_path = _PACKAGE.replace(".", "/")
    write = tools_by_name["write_file"]

    # MCP create_directory does not auto-create parents; just mkdir on host.
    # write_file (MCP) only requires the parent dir to exist beforehand.
    project_dir = Path(state["project_dir"])
    (project_dir / "src" / "main" / "java" / pkg_path).mkdir(parents=True, exist_ok=True)

    for rel_path, content in [
        ("pom.xml",                                                    pom_src),
        (f"src/main/java/{pkg_path}/{_TRANSFORMER_CLASS}.java",        transformer_src),
        (f"src/main/java/{pkg_path}/FpmlToCdmApp.java",                app_src),
        (f"src/main/java/{pkg_path}/SemanticDiff.java",                semantic_diff_src),
    ]:
        full_path = str(project_dir / rel_path)
        await write.ainvoke({"path": full_path, "content": content})
        print(f"[skeleton] wrote {rel_path}")

    return state


# ── Node: fill_methods ─────────────────────────────────────────────────────────

# Map method name to CDM JSON path to show as the "target structure" reference.
# These paths are derived from what the CDM JSON examples actually contain,
# not from pre-encoded Rosetta knowledge.
_METHOD_JSON_PATH: dict[str, str] = {
    "buildTradeState":            "trade",
    "buildParties":               "trade.party",
    "buildTradeLot":              "trade.tradeLot",
    "buildFixedLeg":              "trade.product.economicTerms.payout",
    "buildFloatingLeg":           "trade.product.economicTerms.payout",
    "buildCalculationPeriodDates":"trade.product.economicTerms.payout",
    "buildPaymentDates":          "trade.product.economicTerms.payout",
    "mapDayCountFraction":        "trade.product.economicTerms.payout",
    "mapFloatingRateIndex":       "trade.tradeLot",
    "resolvePartyRole":           "trade.counterparty",
    "buildQualification":         "trade.product.taxonomy",
}


async def fill_methods_node(state: AgentState, tools_by_name: dict) -> AgentState:
    """
    Strict fill: each method stub is filled sequentially and must succeed.

    Each call receives:
      1. The CDM JSON snippet showing exactly what that method's output must look like.
      2. The FpML XPath guide (abbreviated).
      3. The method spec (name, fpml_xpath, cdm_path, transform).
      4. Disambiguation rules (for resolving class/enum ambiguity).
      5. Learned schema (prior iteration observations).

    Any empty/error response raises immediately to stop the graph at first failure.
    """
    pkg_path  = _PACKAGE.replace(".", "/")
    java_path = str(Path(state["project_dir"]) / f"src/main/java/{pkg_path}/{_TRANSFORMER_CLASS}.java")

    read_tool  = tools_by_name["read_file"]
    write_tool = tools_by_name["write_file"]

    file_result    = await read_tool.ainvoke({"path": java_path})
    _fr = _unwrap_dict(file_result)
    current_source = _fr.get("content") or _unwrap(file_result)

    # Pre-fetch shared context once (used by all methods)
    async def _fetch(tool_name: str, **kwargs) -> str:
        return await _tool_text(tools_by_name, tool_name, **kwargs)

    xpath_ref,   enum_ref,   date_ref,   key_ref, disambig, learned = await asyncio.gather(
        _fetch("get_irs_xpath_guide"),
        _fetch("get_cdm_enum_mappings"),
        _fetch("get_cdm_date_handling"),
        _fetch("get_cdm_global_key_guide"),
        _fetch("get_disambiguation_rules"),
        _fetch("get_learned_schema"),
    )

    xpath_short    = xpath_ref[:200]
    enum_short     = enum_ref[:150]
    date_short     = date_ref[:150]
    key_short      = key_ref[:150]
    disambig_short = _excerpt_if_present(disambig, max_len=150)
    learned_short  = learned[:150]  # include learned schema for context

    async def _json_snippet(method: str) -> str:
        path = _METHOD_JSON_PATH.get(method, "trade")
        return await _read_cdm_snippet(path)

    # ── Atomic: fill ONE method (called in parallel for all methods) ──────────
    async def fill_one(spec: MethodSpec) -> tuple[str, str]:
        """Returns (method_name, body_text) — empty body means no change."""
        method     = spec["method_name"]
        stub_throw = f'throw new UnsupportedOperationException("TODO: {method}");'
        if stub_throw not in current_source:
            return method, ""

        print(f"[fill] Writing {method} ...")

        json_target = (await _json_snippet(method))[:300]

        extra = _method_hint(method, enum_short=enum_short, date_short=date_short, key_short=key_short)

        prompt = (
            f"Write the Java body for method {spec['method_name']} in IrsTransformer.\n"
            f"Signature (already in file): private {spec['return_type']} {spec['method_name']}(Element context)\n"
            f"Purpose: {spec['description']}\n"
            f"FpML path: {(spec['fpml_xpath'] or '')[:120]}\n"
            f"CDM path: {(spec['cdm_path'] or '')[:80]}\n"
            f"CDM JSON target:\n{json_target}\n"
            f"FpML XPath ref (abbreviated):\n{xpath_short}\n"
            + (f"{extra}\n" if extra else "")
            + (f"Rules: {disambig_short}\n" if disambig_short else "")
            + "Output ONLY the method body (statements inside braces). "
            + "Use getText/getElement helpers, builder pattern, Date.of(), no new imports."
        )

        body = await _llm_text_or_raise(
            project_dir=state["project_dir"],
            label=f"fill/{method}",
            prompt=[HumanMessage(content=prompt)],
            max_tokens=2500,
            strip_fences=True,
        )
        return method, body

    # Launch fills sequentially so failures are attributable and easier to debug.
    print(f"[fill] Starting {len(state['method_specs'])} sequential fills...")
    results: list[tuple[str, str]] = []
    for spec in state["method_specs"]:
        results.append(await fill_one(spec))

    # Apply all bodies sequentially (preserves stable ordering)
    filled: dict[str, str] = {}
    for method, body in results:
        if body:
            stub_throw = f'throw new UnsupportedOperationException("TODO: {method}");'
            current_source = current_source.replace(stub_throw, body, 1)
            filled[method] = body

    await write_tool.ainvoke({"path": java_path, "content": current_source})
    if len(filled) != len(state["method_specs"]):
        raise RuntimeError(f"fill incomplete: wrote {len(filled)}/{len(state['method_specs'])} methods")
    print(f"[fill] Done — {len(filled)} method(s) written.")
    return {**state, "filled_methods": filled}


# ── Node: compile ──────────────────────────────────────────────────────────────

async def compile_node(state: AgentState, tools_by_name: dict) -> AgentState:
    # (subprocess.run doesn't inherit full PATH in MCP subprocesses on Windows)
    print(f"[compile] mvn compile...")

    result = await tools_by_name["compile_project"].ainvoke(
        {"project_dir": state["project_dir"]}
    )
    r = _unwrap_dict(result)
    errors = r.get("errors", [])
    print(f"[compile] ok={r.get('ok')} errors={len(errors)}")
    for e in errors[:3]:
        print(f"           {e.get('file','?')}:{e.get('line','?')} -> {e.get('message','')[:90]}")
    return {**state, "compile_errors": errors}


# ── Node: test ─────────────────────────────────────────────────────────────────

async def test_node(state: AgentState, tools_by_name: dict) -> AgentState:
    result = await tools_by_name["run_test"].ainvoke({
        "project_dir":        state["project_dir"],
        "fpml_file":          state["fpml_file"],
        "expected_json_file": state["expected_json"],
    })
    r = _unwrap_dict(result)
    diffs = list(r.get("differences", []))
    print(f"[test] ok={r.get('ok')} match={r.get('match')} diffs={len(diffs)}")
    for d in diffs[:3]:
        print(f"       {d[:100]}")
    if not r.get("ok"):
        print("[test] runner returned not-ok; routing to patch if iterations remain")

    return {**state, "transform_diffs": diffs, "compile_errors": []}


# ── Node: patch ─────────────────────────────────────────────────────────────────

async def patch_node(state: AgentState, tools_by_name: dict) -> AgentState:
    """
    Surgical single-method patch.

    1. Call triage_compile_error OR triage_test_diff (deterministic, no LLM).
       This returns the exact target_method, fix_hint, and relevant reference tools.
    2. Extract that method's current body via extract_method_source.
    3. Build a focused prompt: spec context + triage hint + current body.
    4. LLM rewrites ONLY that method body.
    5. Inject the new body back into the file.

    In --patch mode (user changed irs.md): triage is called with an empty
    compile_errors list, which falls through to diff-based triage using the
    FIRST diff only.
    """
    pkg_path  = _PACKAGE.replace(".", "/")
    java_path = str(Path(state["project_dir"]) / f"src/main/java/{pkg_path}/{_TRANSFORMER_CLASS}.java")

    read_tool    = tools_by_name["read_file"]
    write_tool   = tools_by_name["write_file"]
    extract_tool = tools_by_name["extract_method_source"]
    triage_ce    = tools_by_name["triage_compile_error"]
    triage_td    = tools_by_name["triage_test_diff"]

    file_result    = await read_tool.ainvoke({"path": java_path})
    current_source = _unwrap_dict(file_result).get("content") or _unwrap(file_result)

    # ── Step 0: check for human edits to disambiguation.md ───────────────────
    disambig_changed = state.get("disambiguation_changed", False)
    disambig_rules   = ""
    disambig_tool    = tools_by_name.get("get_disambiguation_changes")
    if disambig_tool:
        changes = _unwrap_dict(await disambig_tool.ainvoke({}))
        if changes.get("changed"):
            disambig_changed = True
            print(f"[patch] Human edit detected in disambiguation.md — incorporating changes")
            dr_tool = tools_by_name.get("get_disambiguation_rules")
            if dr_tool:
                disambig_rules = str(await dr_tool.ainvoke({}))

    # ── Step 1: triage ────────────────────────────────────────────────────────
    if state["compile_errors"] and triage_ce:
        directive = await triage_ce.ainvoke({
            "errors":       state["compile_errors"],
            "java_source":  current_source,
            "method_specs": state["method_specs"],
        })
    elif triage_td and (state["transform_diffs"] or state["mode"] == "patch"):
        diffs = state["transform_diffs"] or ["(patch mode — re-read irs.md for changed rules)"]
        directive = await triage_td.ainvoke({
            "diffs":        diffs,
            "method_specs": state["method_specs"],
            "java_source":  current_source,
        })
    else:
        directive = {
            "target_method":  (state["method_specs"][0]["method_name"] if state["method_specs"]
                               else "buildTradeState"),
            "fix_hint":       "Re-read irs.md and fix the mapping.",
            "reference_tools": ["get_all_mappings"],
            "method_source":  "",
        }

    directive = _unwrap_dict(directive)
    target    = directive["target_method"]
    fix_hint  = directive.get("fix_hint", "Fix the error.")
    ref_tools = directive.get("reference_tools", [])

    print(f"[patch] Target: {target} | Problem: {directive.get('problem_label','?')}")

    # ── Step 2: extract current method body ───────────────────────────────────
    extract_result = _unwrap_dict(await extract_tool.ainvoke({
        "java_source": current_source,
        "method_name": target,
    }))
    current_body = extract_result.get("source", directive.get("method_source", ""))

    # ── Step 3: fetch targeted reference context ───────────────────────────────
    ref_context = ""
    for tool_name in ref_tools[:2]:  # max 2 reference tools to keep prompt small
        raw = await _tool_text(tools_by_name, tool_name)
        if raw:
            ref_context += f"\n--- {tool_name} ---\n{raw[:700]}\n"

    # ── Step 4: find spec for this method ─────────────────────────────────────
    spec = next((s for s in state["method_specs"] if s.get("method_name") == target), {})

    # ── Step 5: focused patch prompt ──────────────────────────────────────────
    disambig_section = ""
    if disambig_rules:
        disambig_section = (
            f"\nDISAMBIGUATION RULES (human-edited — apply these corrections):\n"
            f"{disambig_rules[:800]}\n"
        )
    elif disambig_changed:
        disambig_section = "\n(Disambiguation.md was edited but could not be read.)\n"

    prompt = textwrap.dedent(f"""
        Fix this Java method in IrsTransformer.

        Method: {target}
        Purpose: {spec.get('description', 'see Javadoc in file')}
        FpML XPath: {spec.get('fpml_xpath', 'see irs.md')}
        CDM path  : {spec.get('cdm_path', 'see CDM hierarchy')}
        Transform : {spec.get('transform', 'see irs.md')}

        PROBLEM:
        {fix_hint}

        CURRENT METHOD SOURCE:
        {current_body or "(method not yet extracted)"}

        REFERENCE:
        {ref_context or "(no additional reference — rely on the problem description)"}
        {disambig_section}
        INSTRUCTIONS:
        - Output ONLY the corrected method body (statements inside the braces).
        - Do NOT output the signature line or closing brace.
        - Use getText(context, "tag"), getElement(context, "tag") helpers.
        - Use CDM builder pattern: SomeClass.builder().setX(...).build()
        - Wrap String values: FieldWithMetaString.builder().setValue(s).build()
        - Wrap enum values:   FieldWithMetaXxx.builder().setValue(e).build()
        - Use Date.of(y,m,d) — NOT LocalDate.
    """).strip()

    new_body = await _llm_text_or_raise(
        project_dir=state["project_dir"],
        label="patch",
        prompt=[HumanMessage(content=prompt)],
        max_tokens=3000,
        strip_fences=True,
    )

    # ── Step 6: inject new body ────────────────────────────────────────────────
    updated = _replace_method_body_or_raise(current_source, current_body, target, new_body)

    await write_tool.ainvoke({"path": java_path, "content": updated})
    print(f"[patch] Patched {target} — iteration {state['iteration'] + 1}")

    return {
        **state,
        "iteration":              state["iteration"] + 1,
        "patch_target":           target,
        "disambiguation_changed": disambig_changed,
    }


# ── Node: disambig_init ──────────────────────────────────────────────────────

async def disambig_init_node(state: AgentState, tools_by_name: dict) -> AgentState:
    """
    Runs once at the start of every generate mode run.

    Preserves disambiguation.md if it already has content (> 200 chars) —
    humans may have edited it.  Otherwise the LLM creates it from training data.
    """
    get_rules   = tools_by_name.get("get_disambiguation_rules")
    write_rules = tools_by_name.get("write_disambiguation_rules")

    if not get_rules or not write_rules:
        return state

    current = str(await get_rules.ainvoke({}))
    if "not yet created" not in current and len(current.strip()) > 200:
        print("[disambig_init] disambiguation.md exists - preserving")
        return state

    print("[disambig_init] Creating initial disambiguation rules from training data...")

    async def _fetch(name: str, **kwargs) -> str:
        return await _tool_text(tools_by_name, name, **kwargs)

    examples, xpath, irs_rules = await asyncio.gather(
        _read_training_pairs(n=2),
        _fetch("get_irs_xpath_guide"),
        _fetch("get_all_mappings"),
    )

    prompt = textwrap.dedent(f"""
        Create a disambiguation.md file for a FpML 5.x -> CDM 6.x Java code generator.
        The file resolves ambiguous CDM class/enum choices the LLM might face.

        FpML->CDM training examples:
        {examples[:2800]}

        FpML XPath guide:
        {xpath[:800]}

        irs.md mapping rules:
        {irs_rules[:600]}

        Write ## sections for each topic:
          1. CDM Class Naming   2. Optional vs Required fields
          3. Cross-reference vs Inline value
          4. String wrapper types (FieldWithMetaString, FieldWithMetaDate)
          5. Date handling (Date.of() not LocalDate)
          6. Party role mapping (Party1/Party2)
          7. Payout structure (choice types)
          8. Rate specification (fixed vs floating)
          9. Notional / PriceQuantity location
         10. Calculation period dates

        For each rule include a concise Java example (correct AND incorrect).
        Add <!-- AUTO-GENERATED - human-editable --> at the top.
        Output ONLY the markdown.
    """).strip()

    content = await _llm_text_or_raise(
        project_dir=state["project_dir"],
        label="disambig_init",
        prompt=[HumanMessage(content=prompt)],
        max_tokens=5000,
        strip_fences=True,
    )

    await write_rules.ainvoke({"content": content})
    print(f"[disambig_init] Created ({len(content)} chars)")
    return state


# ── Node: schema_learn ─────────────────────────────────────────────────────────

async def schema_learn_node(state: AgentState, tools_by_name: dict) -> AgentState:
    """
    Runs after every test to record what the LLM observed this iteration.

    Writes to knowledge/fpml_observations.md and knowledge/cdm_class_decisions.md
    via schema_server, and appends one line to knowledge/iteration_trace.jsonl.
    These files accumulate across iterations and are read by fill/patch nodes.
    """
    update_tool = tools_by_name.get("update_learned_schema")
    trace_tool  = tools_by_name.get("append_trace_entry")

    if not update_tool:
        return state

    compile_status = "OK" if not state["compile_errors"] else (
        f"{len(state['compile_errors'])} error(s): " +
        "; ".join(e.get("message", str(e))[:80] for e in state["compile_errors"][:2])
    )
    test_status = "OK" if not state["transform_diffs"] else (
        f"{len(state['transform_diffs'])} diff(s): " +
        "; ".join(str(d)[:60] for d in state["transform_diffs"][:2])
    )
    methods_summary = "\n".join(
        f"### {m}\n{body[:400]}..."
        for m, body in list(state["filled_methods"].items())[:6]
    )

    prompt = textwrap.dedent(f"""
        Review this FpML->CDM Java iteration and record schema observations.

        Iteration: {state['iteration']}
        Methods: {list(state['filled_methods'].keys())}
        Compile: {compile_status}
        Test:    {test_status}

        Method bodies (excerpts):
        {methods_summary or '(none)'}

        Write two sections:

        ## FpML XPath Observations (iteration {state['iteration']})
        method: xpath_used -> cdm_path [OK|FAILED: reason]

        ## CDM Class Decisions (iteration {state['iteration']})
        method: ClassName.builder().setX() [OK|FAILED: reason]
    """).strip()

    observations = await _llm_text_or_raise(
        project_dir=state["project_dir"],
        label="schema_learn",
        prompt=[HumanMessage(content=prompt)],
        max_tokens=2500,
    )

    await update_tool.ainvoke({"observations": observations, "iteration": state["iteration"]})

    if trace_tool:
        await trace_tool.ainvoke({
            "iteration":    state["iteration"],
            "compile_ok":   not state["compile_errors"],
            "test_ok":      not state["transform_diffs"],
            "methods":      list(state["filled_methods"].keys()),
            "patch_target": state.get("patch_target") or "",
        })

    print(f"[schema_learn] iter={state['iteration']} saved")
    return state


# ── Node: done ─────────────────────────────────────────────────────────────────

def done_node(state: AgentState) -> AgentState:
    if state["transform_diffs"]:
        print(f"\n⚠  Finished with {len(state['transform_diffs'])} diff(s) "
              f"after {state['iteration']} patch(es).")
        print("   Top diffs:")
        for d in state["transform_diffs"][:5]:
            print(f"     {d[:120]}")
    else:
        print("\nSUCCESS: project validated.")
        print(f"   Output   : {state['project_dir']}")
        print(f"   Patches  : {state['iteration']}")
        print(f"   Run with : java -jar {state['project_dir']}/target/"
              "*-jar-with-dependencies.jar <fpml.xml>")
    return state


# ── Routing ────────────────────────────────────────────────────────────────────

def _route_entry(state: AgentState) -> str:
    # generate: disambig_init first, then plan
    # patch:    skip disambig_init, go straight to patch
    return "disambig_init" if state["mode"] == "generate" else "patch"

def _route_after_compile(state: AgentState) -> str:
    if not state["compile_errors"]:
        return "test"
    return "done" if state["iteration"] >= _MAX_PATCH_ITERATIONS else "patch"

def _route_after_test(state: AgentState) -> str:
    # Always go to schema_learn first so the LLM records what it learned
    return "schema_learn"

def _route_after_schema_learn(state: AgentState) -> str:
    if not state["transform_diffs"]:
        return "done"
    return "done" if state["iteration"] >= _MAX_PATCH_ITERATIONS else "patch"

def _route_after_patch(_: AgentState) -> str:
    return "compile"


# ── Graph ──────────────────────────────────────────────────────────────────────

def build_graph(tools_by_name: dict, checkpointer=None):
    """
    Build the compiled LangGraph.

    Graph flow:
      generate: route → disambig_init → plan → skeleton → fill_methods
                → compile → test → schema_learn → done
                               ↑ patch ←──────────────────┘ (if diffs)
                               └─────── patch ←────────────────────────┘ (if compile errors)

      patch:    route → patch → compile → test → schema_learn → done/patch

    New nodes vs original:
      disambig_init — creates disambiguation.md from training data if absent
      schema_learn  — LLM records what was learned after every test
    """
    import functools

    def bind(fn):
        @functools.wraps(fn)
        async def _node(state: AgentState):
            return await fn(state, tools_by_name)
        return _node

    g = StateGraph(AgentState)
    g.add_node("route",         lambda s: s)
    g.add_node("disambig_init", bind(disambig_init_node))
    g.add_node("plan",          bind(plan_node))
    g.add_node("skeleton",      bind(skeleton_node))
    g.add_node("fill_methods",  bind(fill_methods_node))
    g.add_node("compile",       bind(compile_node))
    g.add_node("test",          bind(test_node))
    g.add_node("schema_learn",  bind(schema_learn_node))
    g.add_node("patch",         bind(patch_node))
    g.add_node("done",          done_node)

    g.set_entry_point("route")
    g.add_conditional_edges("route", _route_entry,
                            {"disambig_init": "disambig_init", "patch": "patch"})
    g.add_edge("disambig_init",  "plan")
    g.add_edge("plan",           "skeleton")
    g.add_edge("skeleton",       "fill_methods")
    g.add_edge("fill_methods",   "compile")
    g.add_conditional_edges("compile", _route_after_compile,
                            {"test": "test", "patch": "patch", "done": "done"})
    g.add_conditional_edges("test", _route_after_test,
                            {"schema_learn": "schema_learn"})
    g.add_conditional_edges("schema_learn", _route_after_schema_learn,
                            {"done": "done", "patch": "patch"})
    g.add_conditional_edges("patch", _route_after_patch, {"compile": "compile"})
    g.add_edge("done", END)

    return g.compile(checkpointer=checkpointer)


# ── CLI ─────────────────────────────────────────────────────────────────────────

async def main():
    parser = argparse.ArgumentParser(
        description="FpML IRS → CDM Java code generator with LangGraph"
    )
    parser.add_argument("--fpml",      required=True,  help="Test FpML XML input file")
    parser.add_argument("--expected",  required=True,  help="Expected CDM JSON file")
    parser.add_argument("--out",       default="./generated", help="Output Maven project dir")
    parser.add_argument("--patch",     action="store_true",
                        help="Patch mode: re-generate only methods changed in irs.md")
    parser.add_argument("--start-at",  choices=["route", "compile", "test"], default="route",
                        help="Force start node: route (default), compile, or test")
    args = parser.parse_args()

    checkpointer = MemorySaver()

    state: AgentState = {
        "project_dir":          str(Path(args.out).resolve()),
        "fpml_file":            str(Path(args.fpml).resolve()),
        "expected_json":        str(Path(args.expected).resolve()),
        "mode":                 "patch" if args.patch else "generate",
        "method_specs":         [],
        "filled_methods":       {},
        "compile_errors":       [],
        "transform_diffs":      [],
        "iteration":            0,
        "patch_target":         None,
        "disambiguation_changed": False,
    }

    model = os.getenv("VLLM_MODEL", "qwen/qwen3.6-35b-a3b")
    print(f"Mode  : {state['mode']}")
    print(f"Model : {model}")
    print(f"LLM URL   : {os.getenv('VLLM_BASE_URL', 'http://localhost:1234/v1')}")
    print(f"Output    : {state['project_dir']}")
    print()

    client = MultiServerMCPClient(_SERVERS)
    tools = await client.get_tools()
    tools_by_name = {t.name: t for t in tools}
    print(f"Loaded {len(tools)} MCP tools: {sorted(tools_by_name.keys())}\n")

    async def _continue_from(node: str, s: AgentState) -> AgentState:
        """Run the remaining pipeline from an internal node to done."""
        current = node
        state_cur = s
        while True:
            if current == "compile":
                state_cur = await compile_node(state_cur, tools_by_name)
                current = _route_after_compile(state_cur)
            elif current == "test":
                state_cur = await test_node(state_cur, tools_by_name)
                current = _route_after_test(state_cur)
            elif current == "schema_learn":
                state_cur = await schema_learn_node(state_cur, tools_by_name)
                current = _route_after_schema_learn(state_cur)
            elif current == "patch":
                state_cur = await patch_node(state_cur, tools_by_name)
                current = _route_after_patch(state_cur)
            elif current == "done":
                return done_node(state_cur)
            else:
                raise RuntimeError(f"Unsupported start-at node: {current}")

    # Manual fast-paths for debugging existing generated projects.
    # These bypass planning/filling and continue the rest of the pipeline.
    if args.start_at in {"compile", "test"}:
        await _continue_from(args.start_at, state)
        return

    graph = build_graph(tools_by_name, checkpointer=checkpointer)
    thread_id = f"{Path(args.fpml).stem}-{state['mode']}"
    await graph.ainvoke(state, config={"configurable": {"thread_id": thread_id}})


if __name__ == "__main__":
    asyncio.run(main())
