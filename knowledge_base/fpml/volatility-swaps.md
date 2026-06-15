# FpML 5.x — Volatility swaps (source side) — STUB

> Status: scaffold. Lean on [../cdm/](../cdm/) + `cdm_lookup` + [../cdm/rosetta/](../cdm/rosetta/),
> and **grep a worked train pair** in `data/train/volatility-swaps-*/`, then expand this file and
> [../mapping/volatility-swaps.md](../mapping/volatility-swaps.md).

## FpML product element
`trade/volatilitySwapTransactionSupplement` (equity-derivatives supplement; close cousin of the
variance swap).

## Key sub-structures (by local name)
- `underlyer`, `volatilityStrikePrice`, `vegaNotionalAmount`, `valuationDates`,
  `annualizationFactor`, payer/receiver.

## CDM target
A `PerformancePayout` (volatility return) under `economicTerms.payout`. Very similar to
[variance-swaps.md](variance-swaps.md) — reuse that mapping, swap variance→volatility strike.
Confirm exact names with `cdm_lookup` + `cdm/rosetta/`.

## Known gotchas
- Strike is in volatility points; notional is a vega notional.
