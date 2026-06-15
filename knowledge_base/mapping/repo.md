# FpML → CDM mapping — Repo / repurchase agreements — STUB

> Status: scaffold. Source side: [../fpml/repo.md](../fpml/repo.md). Read
> [principles.md](principles.md) first (address/location model, per-leg dates, role resolution).
> Target paths: grep [../cdm/structure-skeleton.json](../cdm/structure-skeleton.json), then
> `cdm_lookup` + [../cdm/rosetta/](../cdm/rosetta/). Transforms in words only — no code.

## CDM target
An `AssetPayout` (collateral) plus a financing payout; near/far cash legs. See cdm.finos.org/docs/next/repurchase-agreement-representation/.

## How to fill this in
Work a train pair in `data/train/repo-*/` (FpML + expected CDM JSON): for each populated CDM
field, find its FpML source and record **FpML xpath → CDM path → transform (in words)** in a
table like [rates.md](rates.md). Verify every CDM type/enum with `cdm_lookup` before writing the
transformer, and reuse the cross-cutting rules in [../cdm/](../cdm/) (meta/refs, dates, enums,
builder conventions).
