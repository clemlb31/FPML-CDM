# FpML 5.x — Securities / securities financing (source side) — STUB

> Status: scaffold. Lean on [../cdm/](../cdm/) + `cdm_lookup` + [../cdm/rosetta/](../cdm/rosetta/),
> and **grep a worked train pair** in `data/train/securities-*/`, then expand this file and
> [../mapping/securities.md](../mapping/securities.md).

## FpML product element
Securities / securities-lending messages (the `securities-*` family is `-incomplete` in the
dataset — verify available pairs first).

## Key sub-structures (by local name)
- Security identifiers (`instrumentId`), `quantity`, `price`, parties, settlement dates;
  for lending: `collateral`, `lendingRate`.

## CDM target
CDM securities-finance / collateral representation — confirm payout/asset types with `cdm_lookup`
+ `cdm/rosetta/`. Cross-reference [repo.md](repo.md), which shares the collateral pattern.

## Known gotchas
- Dataset marked incomplete — confirm a matched pair exists before attempting.
