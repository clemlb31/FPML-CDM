# Consolidated data/ground_truth/fpml-cdm summary

Generated: 2026-06-05 14:52 — `mvn test -Dtest=DataDrivenValidationTest#semanticallyEqual -Dincludeincomplete=true`

Signal: semantic equality (`SemanticDiff`) between our converter output and the FINOS reference CDM JSON.
Surefire: **tests=530, failures=0, errors=0, skipped=0** (time 55.8s).

Scope (matches `DataDrivenValidationTest.pairs()` with `includeincomplete=true`):
- All `data/ground_truth/fpml-cdm/<category>/{fpml,cdm}` categories, including `*-incomplete`.
- Excluded: `invalid-products-*` (negative tests, out of scope by design).
- A pair = `fpml/<base>.xml` with a non-empty `cdm/<base>.json`. `loan-5-10-incomplete` (FpML notifications with no `<trade>`, empty refs) contributes 0 pairs.

| Category | Pairs | Pass | Fail | Coverage | Status |
|---|---:|---:|---:|---:|---|
| bond-options-5-10-incomplete | 2 | 2 | 0 | 100.0% | OK |
| bond-options-5-13 | 3 | 3 | 0 | 100.0% | OK |
| commodity-5-10 | 12 | 12 | 0 | 100.0% | OK |
| commodity-5-12 | 3 | 3 | 0 | 100.0% | OK |
| commodity-derivatives-5-10-incomplete | 25 | 25 | 0 | 100.0% | OK |
| commodity-derivatives-5-13 | 14 | 14 | 0 | 100.0% | OK |
| commodity-derivatives-5-13-incomplete | 25 | 25 | 0 | 100.0% | OK |
| correlation-swaps-5-10 | 3 | 3 | 0 | 100.0% | OK |
| correlation-swaps-5-13 | 2 | 2 | 0 | 100.0% | OK |
| correlation-swaps-5-13-incomplete | 1 | 1 | 0 | 100.0% | OK |
| credit-5-10 | 21 | 21 | 0 | 100.0% | OK |
| credit-5-12 | 15 | 15 | 0 | 100.0% | OK |
| credit-derivatives-5-10-incomplete | 34 | 34 | 0 | 100.0% | OK |
| credit-derivatives-5-13 | 35 | 35 | 0 | 100.0% | OK |
| dividend-swaps-5-10 | 5 | 5 | 0 | 100.0% | OK |
| dividend-swaps-5-13 | 4 | 4 | 0 | 100.0% | OK |
| equity-5-10 | 9 | 9 | 0 | 100.0% | OK |
| equity-5-12 | 15 | 15 | 0 | 100.0% | OK |
| equity-5-12-incomplete | 1 | 1 | 0 | 100.0% | OK |
| equity-options-5-10-incomplete | 22 | 22 | 0 | 100.0% | OK |
| equity-options-5-13 | 5 | 5 | 0 | 100.0% | OK |
| equity-options-5-13-incomplete | 15 | 15 | 0 | 100.0% | OK |
| equity-swaps-5-10-incomplete | 11 | 11 | 0 | 100.0% | OK |
| equity-swaps-5-13 | 17 | 17 | 0 | 100.0% | OK |
| equity-swaps-5-13-incomplete | 2 | 2 | 0 | 100.0% | OK |
| fx-5-10 | 12 | 12 | 0 | 100.0% | OK |
| fx-5-12 | 12 | 12 | 0 | 100.0% | OK |
| fx-derivatives-5-10-incomplete | 17 | 17 | 0 | 100.0% | OK |
| fx-derivatives-5-13 | 13 | 13 | 0 | 100.0% | OK |
| fx-derivatives-5-13-incomplete | 11 | 11 | 0 | 100.0% | OK |
| inflation-swaps-5-10 | 4 | 4 | 0 | 100.0% | OK |
| inflation-swaps-5-10-incomplete | 1 | 1 | 0 | 100.0% | OK |
| inflation-swaps-5-13 | 10 | 10 | 0 | 100.0% | OK |
| interest-rate-derivatives-5-13 | 53 | 53 | 0 | 100.0% | OK |
| rates-5-10 | 59 | 59 | 0 | 100.0% | OK |
| rates-5-12 | 21 | 21 | 0 | 100.0% | OK |
| repo-5-12 | 1 | 1 | 0 | 100.0% | OK |
| repo-5-13-incomplete | 1 | 1 | 0 | 100.0% | OK |
| securities-5-10-incomplete | 1 | 1 | 0 | 100.0% | OK |
| variance-swaps-5-10 | 5 | 5 | 0 | 100.0% | OK |
| variance-swaps-5-13 | 4 | 4 | 0 | 100.0% | OK |
| variance-swaps-5-13-incomplete | 1 | 1 | 0 | 100.0% | OK |
| volatility-swaps-5-10 | 2 | 2 | 0 | 100.0% | OK |
| volatility-swaps-5-13 | 1 | 1 | 0 | 100.0% | OK |
| **TOTAL** | **530** | **530** | **0** | **100.0%** | **OK:44 / FAIL:0** |
