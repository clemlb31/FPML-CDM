---
name: cdm-builder-recipe
description: How to build FINOS CDM 6.19.0 objects correctly from Java (builders, enums, choice/wrapper types, address-refs, externalKey). Use whenever writing or fixing a mapper that constructs CDM objects (TradeState, payouts, dates, parties, quantities) or when a CDM class/method seems missing.
---

# Building CDM 6.19.0 objects correctly

**Golden rule — never invent a CDM symbol.** Before using any CDM class, enum value, or
builder method, look it up with the **`cdm-api`** tool (signatures via `javap`) and, if you
need the implementation, **`cdm-source`** (real source). The #1 source of bugs on this repo is
referencing CDM API that does not exist in 6.19.0.

Full domain notes live in:
- [knowledge_base/fpml-cdm/knowledge/cdm_api_quirks.md](../../../knowledge_base/fpml-cdm/knowledge/cdm_api_quirks.md) (authoritative)
- [knowledge_base/fpml-cdm/reference/cdm/enum_mappings.md](../../../knowledge_base/fpml-cdm/reference/cdm/enum_mappings.md)
- [knowledge_base/fpml-cdm/reference/cdm/date_handling.md](../../../knowledge_base/fpml-cdm/reference/cdm/date_handling.md)
- [knowledge_base/fpml-cdm/reference/cdm/global_key_guide.md](../../../knowledge_base/fpml-cdm/reference/cdm/global_key_guide.md)

## Builder basics
- Always `Xxx.builder()…build()`. `XxxBuilder` is mutable; `Xxx` is immutable.
- Choice types are set directly, no intermediate wrapper: `Observable.builder().setIndex(...)`,
  `Asset.builder().setCash(...)`. The JSON discriminator wrapper (`{"Index":{...}}`) only appears
  at serialization — confirm with `cdm-api` before assuming a setter.
- `toBuilder().build()` is **not** identity — it reinjects values and can reorder arrays. Use only
  to deliberately reset sub-objects.

## Enum naming traps (verify each with `cdm-api`)
- `DayCountFractionEnum`: `_30E_360`, `_30E_360_ISDA`, `ACT_365L`, `CAL_252` (not `BUS_252`).
- `RollConventionEnum`: numeric values are underscore-prefixed (`_14`, `_31`); `EOM`/`IMM`/`NONE` are not.
- `CounterpartyRoleEnum`: Java `PARTY_1`/`PARTY_2` → JSON `"Party1"`/`"Party2"`.
- `BusinessDayConventionEnum`: `MODFOLLOWING` (not `MOD_FOLLOWING`); `NotApplicable`→`NOT_APPLICABLE`.
- `PeriodEnum` (D/W/M/Y) vs `PeriodExtendedEnum` (adds T). Prefer `fromDisplayName(String)` over `valueOf`.

## Packages that moved (look here when "class not found")
- `FloatingRateIndexEnum` → `cdm.base.staticdata.asset.rates.FloatingRateIndexEnum`
- `ReferenceWithMetaBusinessCenters` → `cdm.base.datetime.metafields.…`
- cross-ref `Address` → `com.rosetta.model.lib.meta.Reference` (JSON key `address`)
- location `Key` → `com.rosetta.model.lib.meta.Key` (JSON key `location`)
- `PartyName`/`AveragingFeature` → **do not exist** in 6.19.0.

## Payout selection
`InterestRatePayout` (IRS/FRA/capFloor/swaption underlier) · `SettlementPayout` (FX, bullet, repo) ·
`OptionPayout` (all options) · `CreditDefaultPayout` (CDS) · `PerformancePayout` (return/variance/vol/div) ·
`FixedPricePayout` (commodity/dividend fixed leg).

## Address-refs (DOCUMENT scope) & externalKey
- `{"address":{"scope":"DOCUMENT","value":"quantity-1"}}` ← `ReferenceWithMetaXxx` →
  `Reference.builder().setScope("DOCUMENT").setReference("quantity-1")`.
- `{"meta":{"location":[…]}}` ← `MetaFields.builder().addKey(Key.builder().setScope("DOCUMENT").setKeyValue("quantity-1"))`.
- Propagate the FpML `id="…"` as `meta.externalKey` on Party, BusinessCenters, CalculationPeriodDates,
  PaymentDates, ResetDates, tradeDate — this is what makes `externalReference` resolve.

## What the diff legitimately ignores (don't "fix" these)
`globalKey`/`globalReference` (Regnosys hash), `assetType`, `securityType`, `priceSubType` — no setter
in 6.19.0. Also model typos aliased in `SemanticDiff`: `notionaReference` (missing `l`), `knock`↔`barrier`.

When you discover a new quirk, **append it to `cdm_api_quirks.md`** — that is the project's memory.
