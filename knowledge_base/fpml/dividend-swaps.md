# FpML 5.x — Dividend swaps (source side) — STUB

> Status: scaffold. Lean on [../cdm/](../cdm/) + `cdm_lookup` + [../cdm/rosetta/](../cdm/rosetta/),
> and **grep a worked train pair** in `data/train/dividend-swaps-*/`, then expand this file and
> [../mapping/dividend-swaps.md](../mapping/dividend-swaps.md).

## FpML product element
`trade/dividendSwapTransactionSupplement` (equity-derivatives supplement).

## Key sub-structures (by local name)
- `underlyer` (single name or index), `dividendPeriods`, `dividendPaymentDates`,
  `notionalAmount`, fixed dividend strike, payer/receiver.

## CDM target
A `PerformancePayout` (dividend return) under `economicTerms.payout`, typically combined with an
`InterestRatePayout` financing leg. Confirm exact names with `cdm_lookup` + `cdm/rosetta/`.

## Known gotchas
- Dividend periods define observation windows — distinct from interest calc periods.
