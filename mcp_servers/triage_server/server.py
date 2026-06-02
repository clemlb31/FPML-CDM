"""
Triage Server
=============
Deterministic (no-LLM) failure analysis that tells the patch node exactly:
  - Which Java method to fix
  - What the concrete problem is
  - Which MCP reference tools to call for context
  - A ready-made fix prompt fragment

TWO tools, matching the two failure modes the system produces:

  triage_compile_error(errors, java_source, method_specs) -> PatchDirective
  triage_test_diff    (diffs,               method_specs) -> PatchDirective

Design principles
-----------------
* Only looks at the FIRST error / diff so the LLM rewrites exactly one method per iteration.
* Uses brace-counting to locate method boundaries in Java source (no javalang dependency).
* Uses CDM-path prefix matching to map a JSON diff path to a method name.
* Hard-codes patterns for the most common CDM/Rosetta mistakes (FieldWithMeta, Date types,
  wrong class names) so the LLM gets an explicit, actionable hint rather than having to
  diagnose from a raw compiler message.
"""

from mcp.server.fastmcp import FastMCP
import re
import json

mcp = FastMCP(
    "triage",
    instructions=(
        "Deterministic failure analyser. Call triage_compile_error or triage_test_diff "
        "at the start of every patch iteration. The result tells you exactly which method "
        "to fix and what the problem is — trust it and follow the fix_prompt."
    ),
)

# ── CDM diff path → method name ───────────────────────────────────────────────
# Ordered longest-match first so the most specific path wins.
_DIFF_PATH_TO_METHOD: list[tuple[str, str]] = [
    # Fixed leg
    ("interestRatePayout.rateSpecification.fixedRate",          "buildFixedLeg"),
    ("interestRatePayout.rateSpecification.floatingRate",       "buildFloatingLeg"),
    ("interestRatePayout.calculationPeriodDates",               "buildCalculationPeriodDates"),
    ("interestRatePayout.paymentDates",                         "buildPaymentDates"),
    ("interestRatePayout.dayCountFraction",                     "mapDayCountFraction"),
    ("interestRatePayout.rateOption",                           "mapFloatingRateIndex"),
    ("interestRatePayout.payerReceiver",                        "resolvePartyRole"),
    # Trade header
    ("trade.party",                                             "buildParties"),
    ("tradableProduct.counterparty",                            "buildParties"),
    ("tradableProduct.tradeLot",                                "buildTradeLot"),
    # Qualification
    ("contractualProduct.productTaxonomy",                      "buildQualification"),
    # Dates
    ("economicTerms.effectiveDate",                             "buildCalculationPeriodDates"),
    ("economicTerms.terminationDate",                           "buildCalculationPeriodDates"),
]

# ── Compile error patterns → (human description, fix hint, reference tools) ──
_ERROR_PATTERNS: list[tuple[str, str, str, list[str]]] = [
    # pattern regex                          short label           fix hint                                    tools
    (r"cannot find symbol.*class (\w+)",
     "Unknown CDM class",
     "The class name '{match}' was not found. "
     "Call get_cdm_json_snippet() to see the actual CDM JSON structure and derive the correct class name. "
     "Common mistakes: AssignedIdentifier→TradeIdentifier, Rate→FixedRateSpecification, "
     "RateSchedule→Price, NotionalSchedule→NonNegativeQuantitySchedule.",
     ["get_cdm_json_snippet"]),

    (r"cannot find symbol.*method set(\w+)\(",
     "Unknown builder setter",
     "The setter .set{match}() does not exist on this builder. "
     "Call get_cdm_json_snippet() to see the JSON field names — they match the Java builder setter names.",
     ["get_cdm_json_snippet"]),

    (r"incompatible types.*String.*FieldWithMeta",
     "Missing FieldWithMeta wrapper",
     "You passed a raw String where a FieldWithMetaString is required. "
     "Wrap: FieldWithMetaString.builder().setValue(yourString).build(). "
     "Call get_cdm_global_key_guide() for all wrapper patterns.",
     ["get_cdm_global_key_guide"]),

    (r"incompatible types.*LocalDate",
     "Wrong Date type",
     "CDM uses com.rosetta.model.lib.records.Date, NOT java.time.LocalDate. "
     "Use: Date.of(year, month, day). Call get_cdm_date_handling() for details.",
     ["get_cdm_date_handling"]),

    (r"incompatible types.*java\.util\.Date",
     "Wrong Date type",
     "CDM uses com.rosetta.model.lib.records.Date, NOT java.util.Date. "
     "Use: Date.of(year, month, day). Call get_cdm_date_handling() for details.",
     ["get_cdm_date_handling"]),

    (r"incompatible types.*(\w+) cannot be converted to (\w+)Builder",
     "Object where builder expected",
     "You called .build() too early. Pass the builder, not the built object, "
     "to the parent builder's setter.",
     ["get_cdm_global_key_guide"]),

    (r"incompatible types.*(\w+)Builder cannot be converted to (\w+)",
     "Builder where object expected",
     "You passed a Builder where a built object is expected. Call .build() first.",
     ["get_cdm_global_key_guide"]),

    (r"incompatible types.*(\w+) cannot be converted to FieldWithMeta(\w+)",
     "Missing FieldWithMeta wrapper",
     "Wrap the value: FieldWithMeta{match}.builder().setValue(x).build(). "
     "See get_cdm_global_key_guide() for FieldWithMeta wrapper patterns.",
     ["get_cdm_global_key_guide"]),

    (r"required: com\.rosetta\.model\.metafields\.MetaAndTemplateFields",
     "Missing meta/globalKey",
     "This object requires a .setMeta() call. "
     "Call get_cdm_global_key_guide() for the globalKey + ReferenceWithMeta pattern.",
     ["get_cdm_global_key_guide"]),

    (r"ReferenceWithMeta",
     "ReferenceWithMeta usage",
     "An object is referenced via ReferenceWithMeta. "
     "Call get_cdm_global_key_guide() for the globalKey assignment + reference pattern.",
     ["get_cdm_global_key_guide"]),

    (r"cannot find symbol.*DayCountFractionEnum\.",
     "Unknown DayCountFractionEnum value",
     "Check the exact enum constant name in get_cdm_enum_mappings(). "
     "Common: ACT/360→ACT_360, ACT/365.FIXED→ACT_365_FIXED, 30/360→_30_360.",
     ["get_cdm_enum_mappings"]),

    (r"cannot find symbol.*FloatingRateIndexEnum\.",
     "Unknown FloatingRateIndexEnum value",
     "Check the exact enum constant in get_cdm_enum_mappings(). "
     "Common: USD-SOFR→USD_SOFR, USD-LIBOR-BBA→USD_LIBOR_BBA.",
     ["get_cdm_enum_mappings"]),

    (r"cannot find symbol.*(\w+Enum)\.",
     "Unknown enum constant",
     "Check get_cdm_enum_mappings() for the exact constant name for '{match}'.",
     ["get_cdm_enum_mappings"]),

    (r"unreported exception",
     "Unhandled checked exception",
     "Add 'throws Exception' to the method signature, or wrap in try-catch.",
     []),

    (r"NullPointerException|NPE",
     "NullPointerException",
     "Add a null-check before using the value. "
     "Use getElement()/getText() helpers which return null/'\" safely.",
     []),
]


# ── Java source method locator ────────────────────────────────────────────────

def _locate_method_for_line(java_source: str, error_line: int) -> str | None:
    """
    Given a 1-based line number, return the name of the Java method that contains it.
    Uses simple brace counting — no AST parser required.
    """
    lines = java_source.splitlines()
    sig_re = re.compile(
        r"(?:private|public|protected)\s+(?:static\s+)?(?:[\w<>\[\],\s]+)\s+(\w+)\s*\("
    )
    # Find all method signatures with their line numbers
    method_starts: list[tuple[int, str]] = []
    for i, line in enumerate(lines, start=1):
        m = sig_re.search(line)
        if m:
            method_starts.append((i, m.group(1)))

    if not method_starts:
        return None

    # Assign approximate end lines via brace counting
    method_ranges: list[tuple[int, int, str]] = []
    for idx, (start, name) in enumerate(method_starts):
        end = method_starts[idx + 1][0] - 1 if idx + 1 < len(method_starts) else len(lines)
        method_ranges.append((start, end, name))

    for start, end, name in method_ranges:
        if start <= error_line <= end:
            return name

    return None


def _extract_method_source(java_source: str, method_name: str) -> str:
    """
    Extract the full source of a named method using brace counting.
    Raises ValueError if method is not found.
    """
    lines = java_source.splitlines()
    sig_re = re.compile(
        r"(?:private|public|protected)\s+(?:static\s+)?(?:[\w<>\[\],\s]+)\s+"
        + re.escape(method_name)
        + r"\s*\("
    )
    start_idx = None
    for i, line in enumerate(lines):
        if sig_re.search(line):
            start_idx = i
            break

    if start_idx is None:
        raise ValueError(f"Method '{method_name}' not found")

    depth = 0
    in_method = False
    out_lines = []
    for line in lines[start_idx:]:
        out_lines.append(line)
        depth += line.count("{") - line.count("}")
        if depth > 0:
            in_method = True
        if in_method and depth <= 0:
            break
    return "\n".join(out_lines)


def _match_diff_to_method(diff_path: str, method_specs: list[dict]) -> str | None:
    """Match a CDM JSON diff path to a method name, using method_specs first then static table."""
    norm = diff_path.lower().replace("[0]", "").replace(" ", "")

    # Try method_specs.cdm_path first (authoritative)
    best_method = None
    best_len = 0
    for spec in method_specs:
        cdm_p = spec.get("cdm_path", "").lower().replace(" ", "")
        if not cdm_p:
            continue
        # Check if any component of the cdm_path appears in the diff path
        for part in cdm_p.split("."):
            if len(part) > 4 and part in norm and len(part) > best_len:
                best_method = spec.get("method_name")
                best_len = len(part)

    if best_method:
        return best_method

    # Fall back to hard-coded table
    for prefix, method in _DIFF_PATH_TO_METHOD:
        prefix_norm = prefix.lower().replace(" ", "")
        if prefix_norm in norm:
            return method

    return None


# ── Public tools ──────────────────────────────────────────────────────────────

@mcp.tool()
def triage_compile_error(
    errors: list,
    java_source: str,
    method_specs: list,
) -> dict:
    """
    Analyse the FIRST compile error and return a precise patch directive.

    Args:
        errors:       List of dicts from compile_project — [{file, line, message}, ...]
        java_source:  Full content of IrsTransformer.java (used to locate the guilty method)
        method_specs: List of MethodSpec dicts from the current agent state

    Returns:
        {
          target_method:   str,   # name of the Java method to rewrite
          problem_label:   str,   # short human description of the error category
          specific_error:  str,   # the raw error message
          fix_hint:        str,   # actionable fix instructions
          reference_tools: list,  # MCP tools the LLM should call for context
          method_source:   str,   # current body of target_method (for the LLM to read)
        }
    """
    if not errors:
        raise ValueError("triage_compile_error requires at least one compile error")

    # Prefer errors that carry a real (file, line) — Maven output also contains
    # generic "[ERROR] BUILD FAILURE" lines without source coordinates which
    # cannot be triaged to a method.
    structured = [e for e in errors if e.get("line", 0) and e.get("file")]
    first = structured[0] if structured else errors[0]
    msg = first.get("message", "")
    line = first.get("line", 0)

    # Locate guilty method from line number; fall back to first spec
    # (better to patch *something* than to halt the loop).
    target = _locate_method_for_line(java_source, line) if line else None

    # Classify error
    problem_label = "unknown error"
    fix_hint = f"Fix the compiler error: {msg}"
    reference_tools: list[str] = []

    for pattern, label, hint, tools in _ERROR_PATTERNS:
        m = re.search(pattern, msg, re.IGNORECASE)
        if m:
            problem_label = label
            # Substitute {match} placeholder with first capture group
            cap = m.group(1) if m.lastindex and m.lastindex >= 1 else ""
            fix_hint = hint.replace("{match}", cap)
            reference_tools = tools
            break

    if not target and method_specs:
        # Fallback: blame the first method. Patch loop will iterate.
        target = method_specs[0].get("method_name") or method_specs[0].get("name")
    if not target:
        raise ValueError(f"Unable to locate method for compile error line {line}")

    method_source = _extract_method_source(java_source, target)

    return {
        "target_method":  target,
        "problem_label":  problem_label,
        "specific_error": msg,
        "error_line":     line,
        "fix_hint":       fix_hint,
        "reference_tools": reference_tools,
        "method_source":  method_source,
    }


@mcp.tool()
def triage_test_diff(
    diffs: list,
    method_specs: list,
    java_source: str = "",
) -> dict:
    """
    Analyse the FIRST JSON diff between actual and expected CDM output and
    return a precise patch directive.

    Args:
        diffs:        List of diff strings from run_test — ["MISSING path (expected: ...)", ...]
        method_specs: List of MethodSpec dicts from the current agent state
        java_source:  (optional) Full content of IrsTransformer.java

    Returns:
        {
          target_method:   str,
          problem_label:   str,
          specific_error:  str,   # the first diff string
          cdm_path:        str,   # the CDM path extracted from the diff
          fix_hint:        str,
          reference_tools: list,
          method_source:   str,
        }
    """
    if not diffs:
        raise ValueError("triage_test_diff requires at least one diff")

    first_diff = diffs[0]

    # Extract the CDM path from diff formats:
    #   "MISSING trade.party[0].name  (expected: ...)"
    #   "trade.party[0].name:\n  got: ...\n  want: ..."
    #   "EXTRA businessEvent[0].after.trade.party[0].xxx"
    path_match = re.match(r"(?:MISSING|EXTRA)?\s*([\w.\[\]]+)", first_diff.strip())
    if not path_match:
        raise ValueError(f"Unable to parse diff path from: {first_diff}")
    cdm_path = path_match.group(1).strip(".")

    # Map CDM path to method
    target = _match_diff_to_method(cdm_path, method_specs)
    if not target:
        raise ValueError(f"Unable to map diff path to method: {cdm_path}")

    # Find the method spec for more context
    spec = next((s for s in method_specs if s.get("method_name") == target), None)
    if spec is None:
        raise ValueError(f"Method spec not found for target method: {target}")

    # Determine fix hint based on diff type
    if "MISSING" in first_diff:
        fix_hint = (
            f"The CDM field '{cdm_path}' is missing from your output. "
            f"Check method '{target}': {spec.get('description', '')}. "
            f"Expected FpML XPath: {spec.get('fpml_xpath', '')}. "
            f"Make sure you are reading the correct FpML element and setting the CDM field. "
            f"Call get_irs_xpath_guide() and get_all_mappings() for reference."
        )
        reference_tools = ["get_irs_xpath_guide", "get_all_mappings"]
    elif "EXTRA" in first_diff:
        fix_hint = (
            f"The CDM field '{cdm_path}' is present in your output but should NOT be. "
            f"Check method '{target}' — you may be setting a field that should be null/absent."
        )
        reference_tools = ["get_all_mappings"]
    elif "got:" in first_diff and "want:" in first_diff:
        # Value mismatch
        got_m = re.search(r"got:\s*(.+)", first_diff)
        want_m = re.search(r"want:\s*(.+)", first_diff)
        got = got_m.group(1).strip()[:80] if got_m else "?"
        want = want_m.group(1).strip()[:80] if want_m else "?"

        if "Enum" in want or "Enum" in cdm_path:
            fix_hint = (
                f"Wrong enum value at '{cdm_path}': got {got}, want {want}. "
                f"Call get_cdm_enum_mappings() for the correct constant."
            )
            reference_tools = ["get_cdm_enum_mappings"]
        elif "date" in cdm_path.lower() or "Date" in want:
            fix_hint = (
                f"Wrong date value at '{cdm_path}': got {got}, want {want}. "
                f"Call get_cdm_date_handling() and get_irs_xpath_guide() to verify the XPath."
            )
            reference_tools = ["get_cdm_date_handling", "get_irs_xpath_guide"]
        else:
            fix_hint = (
                f"Wrong value at '{cdm_path}': got {got}, want {want}. "
                f"Verify the FpML XPath in get_irs_xpath_guide() and the mapping in get_all_mappings()."
            )
            reference_tools = ["get_irs_xpath_guide", "get_all_mappings"]
    else:
        fix_hint = (
            f"Diff at '{cdm_path}': {first_diff[:200]}. "
            f"Check method '{target}' against get_all_mappings() and get_irs_xpath_guide()."
        )
        reference_tools = ["get_all_mappings", "get_irs_xpath_guide"]

    problem_label = (
        "missing field" if "MISSING" in first_diff else
        "extra field"   if "EXTRA"   in first_diff else
        "value mismatch"
    )

    method_source = _extract_method_source(java_source, target) if java_source and target else ""

    return {
        "target_method":  target,
        "problem_label":  problem_label,
        "specific_error": first_diff,
        "cdm_path":       cdm_path,
        "fix_hint":       fix_hint,
        "reference_tools": reference_tools,
        "method_source":  method_source,
    }


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--transport", default="stdio", choices=["stdio", "streamable-http", "streamablehttp"])
    parser.add_argument("--port", type=int, default=8002)
    args = parser.parse_args()
    transport = args.transport.replace("streamablehttp", "streamable-http")
    if transport == "streamable-http":
        mcp.settings.port = args.port
        mcp.settings.host = "0.0.0.0"
        mcp.run(transport="streamable-http")
    else:
        mcp.run(transport="stdio")
