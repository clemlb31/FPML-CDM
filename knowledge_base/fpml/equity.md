# FpML 5.x — Equity (swaps & options) (source side) — STUB

> Status: scaffold. Lean on [../cdm/](../cdm/) + `cdm_lookup` + [../cdm/rosetta/](../cdm/rosetta/),
> and **grep a worked train pair** in `data/train/equity-*/` to learn exact shapes, then expand
> this file and [../mapping/equity.md](../mapping/equity.md).

## FpML product elements
- `trade/returnSwap` or `equitySwapTransactionSupplement` — equity (total-return) swap.
- `trade/equityOption` — equity option.

## Key sub-structures (by local name)
- Equity swap: `return*`/`equityLeg` (underlier `singleUnderlyer/underlyingAsset`,
  initial/final price, `notional`), plus an `interestLeg` (a financing rate leg).
- Equity option: `equityExercise`, `equityPremium`, `strike`, `numberOfOptions`,
  `optionType` (Put/Call), `underlyer`.

## CDM target
Equity swap → an `EquityPayout` (a `PerformancePayout`) combined with an `InterestRatePayout` for
the financing leg (CDM composes them — see https://cdm.finos.org/docs/product-model/). Equity
option → an `OptionPayout` whose `underlier` wraps the equity product. Confirm exact payout type
names with `cdm_lookup` + `cdm/rosetta/`. The `OptionPayout` shape IS visible in
`cdm/structure-skeleton.json`.

## Known gotchas
- Equity swaps are two composed payouts (equity + interest) — don't force everything into one.
- Underlier is a nested product/asset; resolve the asset identifier scheme.
