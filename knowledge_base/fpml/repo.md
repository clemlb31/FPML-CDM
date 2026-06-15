# FpML 5.x — Repo / repurchase agreements (source side) — STUB

> Status: scaffold. Lean on [../cdm/](../cdm/) + `cdm_lookup` + [../cdm/rosetta/](../cdm/rosetta/),
> and **grep a worked train pair** in `data/train/repo-*/`, then expand this file and
> [../mapping/repo.md](../mapping/repo.md).

## FpML product element
`trade/repo` (FpML 5.13) — repurchase agreement.

## Key sub-structures (by local name)
- `nearLeg` / `farLeg` (settlement dates, cash amounts, payer/receiver).
- `collateral` (the underlying securities, `nominalAmount`, haircut).
- `repoRate`, `dayCountFraction`, `duration` (term vs open).

## CDM target
CDM has a dedicated repo representation — see
https://cdm.finos.org/docs/next/repurchase-agreement-representation/ . Expect an `AssetPayout`
(collateral) plus an interest/financing payout. Confirm the exact payout types with `cdm_lookup`
+ `cdm/rosetta/` (not in the rates skeleton).

## Known gotchas
- Two-leg cash settlement (near/far) plus a collateral asset — three things to model.
- Term vs open repo changes which dates are present.
