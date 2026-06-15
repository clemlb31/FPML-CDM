# FpML 5.x — Correlation swaps (source side) — STUB

> Status: scaffold. Lean on [../cdm/](../cdm/) + `cdm_lookup` + [../cdm/rosetta/](../cdm/rosetta/),
> and **grep a worked train pair** in `data/train/correlation-swaps-*/`, then expand this file and
> [../mapping/correlation-swaps.md](../mapping/correlation-swaps.md).

## FpML product element
`trade/correlationSwap` (an equity-derivatives transaction supplement variant).

## Key sub-structures (by local name)
- Basket of `underlyer` references, `correlationStrikePrice`, `numberOfDataSeries`,
  `valuationDates`, `notionalAmount`, payer/receiver.

## CDM target
A `PerformancePayout` (correlation return) under `economicTerms.payout`. Confirm the exact payout
type and the basket/observable shape with `cdm_lookup` + `cdm/rosetta/` (not in the rates
skeleton).

## Known gotchas
- Multi-underlier basket — the observable is a basket, not a single index.
