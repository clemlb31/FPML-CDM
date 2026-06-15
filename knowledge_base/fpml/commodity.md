# FpML 5.x — Commodity derivatives (source side) — STUB

> Status: scaffold. Lean on [../cdm/](../cdm/) + `cdm_lookup` + [../cdm/rosetta/](../cdm/rosetta/),
> and **grep a worked train pair** in `data/train/commodity-*/`, then expand this file and
> [../mapping/commodity.md](../mapping/commodity.md).

## FpML product elements
- `trade/commoditySwap` (fixed/float commodity swap), `commodityForward`, `commodityOption`.

## Key sub-structures (by local name)
- `commodity` underlier (`instrumentId`, `specifiedPrice`, `commodityBase`/`commodityDetails`).
- Fixed leg: `fixedLeg` (`fixedPrice`, `quantity`, `calculationPeriods`).
- Float leg: `floatingLeg` (`commodity` reference price, `spread`, averaging).
- `notionalQuantity` / `totalNotionalQuantity`, `quantityUnit`, `priceUnit`.

## CDM target
A `CommodityPayout` (and/or `AssetPayout`/`PerformancePayout`) under `economicTerms.payout`.
Confirm the exact payout type names with `cdm_lookup` + `cdm/rosetta/`.

## Known gotchas
- Quantity has a unit of measure (not just currency) — distinct from rates notional.
- Pricing dates / averaging schedules differ from interest schedules.
