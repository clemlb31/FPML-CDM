# FpML 5.x — Variance swaps & options (source side) — STUB

> Status: scaffold. Lean on [../cdm/](../cdm/) + `cdm_lookup` + [../cdm/rosetta/](../cdm/rosetta/),
> and **grep a worked train pair** in `data/train/variance-swaps-*/`, then expand this file and
> [../mapping/variance-swaps.md](../mapping/variance-swaps.md).

## FpML product elements
`trade/varianceSwapTransactionSupplement`; `trade/varianceOption` for the option variant.

## Key sub-structures (by local name)
- `underlyer`, `varianceStrikePrice` / `volatilityStrikePrice`, `varianceAmount` (vega notional),
  `valuationProcess`, `annualizationFactor`, `numberOfDataSeries`, payer/receiver.
- Option variant adds `optionType`, `exercise`, `premium`.

## CDM target
A `PerformancePayout` (variance return) under `economicTerms.payout`; the option variant wraps it
in an `OptionPayout` (skeleton). Confirm exact names with `cdm_lookup` + `cdm/rosetta/`.

## Known gotchas
- Strike is quoted in variance/volatility terms; vega/variance notional is not a plain currency
  amount.
