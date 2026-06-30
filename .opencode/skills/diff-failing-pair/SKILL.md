---
name: diff-failing-pair
description: Debug workflow for a single failing FpML→CDM pair (semantic diff). Use when a test fails on a specific file or a mapper produces wrong CDM output and you need to find and fix the root cause.
---

# Debug a failing FpML→CDM pair

1. **Reproduce in isolation** with the `diff-pair` tool: `{category, base}` → prints `EQUAL` or the list
   of semantic diffs (path + expected vs actual). Far faster than the full suite.
2. **Read the diff paths.** Each diff is a JSON path into the CDM tree. Map it back to the mapper code
   that should produce that branch (search `products/` and `payouts/` for the field/builder).
3. **Compare against the reference JSON** (`data/train/<category>/cdm/<base>.json`) and the source FpML
   (`data/train/<category>/fpml/<base>.xml`) side by side — the JSON is ground truth.
4. **Ground every CDM assumption** with the `cdm-api` / `cdm-source` tools before changing builder calls.
   Consult the `cdm-builder-recipe` skill for enum/wrapper/address-ref traps.
5. **Check the family knowledge_base** for known patterns:
   - [knowledge_base/fpml-cdm/knowledge/fpml_to_cdm_patterns.md](../../../knowledge_base/fpml-cdm/knowledge/fpml_to_cdm_patterns.md)
   - [knowledge_base/fpml-cdm/knowledge/validation_findings.md](../../../knowledge_base/fpml-cdm/knowledge/validation_findings.md)
   - [knowledge_base/fpml-cdm/rules/](../../../knowledge_base/fpml-cdm/rules/) and [knowledge_base/fpml-cdm/reference/fpml/](../../../knowledge_base/fpml-cdm/reference/fpml/)
6. **Decide: real bug vs legitimate version divergence.**
   - Real bug → fix the mapper.
   - Genuine CDM 6.19.0 vs reference-model divergence (no setter / class absent) → it may belong in
     `SemanticDiff` masking, but only if already justified in `cdm_api_quirks.md`. Don't mask to go green.
7. **Re-run `diff-pair` until EQUAL**, then `category-report` on the family, then `run-dataset-tests`.

> A diff in `globalKey`/`globalReference`/`assetType`/`securityType`/`priceSubType` is expected and
> already ignored — don't chase it.
