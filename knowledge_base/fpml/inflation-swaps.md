# FpML 5.x — Inflation swaps (source side) — STUB

> Status: scaffold. Lean on [../cdm/](../cdm/) + `cdm_lookup` + [../cdm/rosetta/](../cdm/rosetta/),
> and **grep a worked train pair** in `data/train/inflation-swaps-*/`, then expand this file and
> [../mapping/inflation-swaps.md](../mapping/inflation-swaps.md).

## FpML product element
`trade/swap` whose floating leg uses `inflationRateCalculation` instead of `floatingRateCalculation`.

## Key sub-structures (by local name)
- Inflation leg: `inflationRateCalculation/floatingRateIndex` (an inflation index, e.g.
  `EUR-EXT-CPI`), `indexTenor`, `inflationLag`, `interpolationMethod`, `initialIndexLevel`,
  `fallbackBondApplicable`.
- The other leg is usually a normal fixed leg (see [rates.md](rates.md)).
- Some examples are fixed-vs-fixed with an FX-linked notional (`fxLinkedNotionalSchedule`).

## CDM target
An `InterestRatePayout` with inflation specifics on its rate specification — the rates structure
in [../cdm/object-model.md](../cdm/object-model.md) applies, plus inflation fields. The skeleton's
`quantityMultiplier.fxLinkedNotionalSchedule` covers the FX-linked-notional variant. Confirm
inflation attribute names with `cdm_lookup` + `cdm/rosetta/`.

## Known gotchas
- Closest family to vanilla rates — reuse the rates mapping, then add inflation index + lag.
- FX-linked notional variants exist (see skeleton `fxLinkedNotionalSchedule`).
