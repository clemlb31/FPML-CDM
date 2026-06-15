# FpML 5.x — Credit derivatives (source side) — STUB

> Status: scaffold, not yet filled in depth. Until it is: lean on the cross-cutting
> [../cdm/](../cdm/) docs + `cdm_lookup` + [../cdm/rosetta/](../cdm/rosetta/), and **grep a worked
> train pair** in `data/train/credit-*/` (FpML + expected CDM JSON) to learn the exact shapes,
> then expand this file and [../mapping/credit.md](../mapping/credit.md).

## FpML product element
`trade/creditDefaultSwap` (single-name or index CDS). Index options under `creditDefaultSwapOption`.

## Key sub-structures (by local name)
- `generalTerms` — `effectiveDate`, `scheduledTerminationDate`, `referenceInformation`
  (single-name: `referenceEntity`, `referenceObligation`) or `indexReferenceInformation` (index).
- `feeLeg` — `periodicPayment` (fixed rate / amount, paymentFrequency), `initialPayment`.
- `protectionTerms` — `calculationAmount` (notional), `creditEvents`, `obligations`.

## CDM target
A `CreditDefaultPayout` under `economicTerms.payout` (protectionTerms + generalTerms +
periodicPayment), plus an `InterestRatePayout`/fee for the premium leg. Confirm the exact payout
type name with `cdm_lookup` and `cdm/rosetta/` (it is NOT in `cdm/structure-skeleton.json`, which
only covers rates/options).

## Known gotchas
- Single-name vs index reference information are different shapes — discriminate on which element
  is present.
- Notional and fixed fee rate follow the address/location reference model — see
  [../cdm/meta-and-references.md](../cdm/meta-and-references.md).
