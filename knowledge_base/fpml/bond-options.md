# FpML 5.x — Bond options (source side) — STUB

> Status: scaffold. Lean on [../cdm/](../cdm/) + `cdm_lookup` + [../cdm/rosetta/](../cdm/rosetta/),
> and **grep a worked train pair** in `data/train/bond-options-*/`, then expand this file and
> [../mapping/bond-options.md](../mapping/bond-options.md).

## FpML product element
`trade/bondOption` — option on a bond.

## Key sub-structures (by local name)
- `bondOption/optionType` (Put/Call), `strike` (price or yield), `notionalAmount`.
- `buyerPartyReference` / `sellerPartyReference`.
- `europeanExercise` / `americanExercise` (`expirationDate`, `exerciseFee`), `premium`.
- Underlier `bond` (`instrumentId`, `couponRate`, `maturity`, `faceAmount`).

## CDM target
An `OptionPayout` (visible in `cdm/structure-skeleton.json`) whose `underlier` wraps the bond as a
product/asset, `buyerSeller`, `exerciseTerms`, and `settlementTerms`. Confirm exact attribute
names with `cdm_lookup` + the skeleton.

## Known gotchas
- Reuse the generic `OptionPayout`/`exerciseTerms` shape from the skeleton; the bond underlier is
  the family-specific part.
