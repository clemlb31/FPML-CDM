# FpML 5.x — Loan products (source side) — STUB

> Status: scaffold. Lean on [../cdm/](../cdm/) + `cdm_lookup` + [../cdm/rosetta/](../cdm/rosetta/),
> and **grep a worked train pair** in `data/train/loan-*/`, then expand this file and
> [../mapping/loan.md](../mapping/loan.md).

## FpML product element
Loan / syndicated-loan servicing messages (the `loan-*` family is `-incomplete` in the dataset —
check what pairs actually exist before investing).

## Key sub-structures (by local name)
- Facility / loan contract identifiers, `amount`, `currency`, interest rate option, parties.

## CDM target
Map onto the closest CDM payout/asset representation — confirm with `cdm_lookup` + `cdm/rosetta/`.
This family is likely sparse; prioritise only if test pairs are present.

## Known gotchas
- Dataset marked incomplete — verify there is a matched FpML+CDM pair before attempting.
