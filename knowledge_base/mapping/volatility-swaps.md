# FpML → CDM mapping — Volatility swaps — STUB

> Status: scaffold. Source side: [../fpml/volatility-swaps.md](../fpml/volatility-swaps.md). Read
> [principles.md](principles.md) first (address/location model, per-leg dates, role resolution).
> Target paths: grep [../cdm/structure-skeleton.json](../cdm/structure-skeleton.json), then
> `cdm_lookup` + [../cdm/rosetta/](../cdm/rosetta/). Transforms in words only — no code.

## CDM target
A `PerformancePayout` (volatility return); near-identical to variance — reuse that mapping.

## How to fill this in
Work a train pair in `data/train/volatility-swaps-*/` (FpML + expected CDM JSON): for each populated CDM
field, find its FpML source and record **FpML xpath → CDM path → transform (in words)** in a
table like [rates.md](rates.md). Verify every CDM type/enum with `cdm_lookup` before writing the
transformer, and reuse the cross-cutting rules in [../cdm/](../cdm/) (meta/refs, dates, enums,
builder conventions).
