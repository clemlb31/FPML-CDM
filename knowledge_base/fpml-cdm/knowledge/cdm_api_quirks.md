# CDM 6.19.0 — API quirks and conventions

Notes accumulated on the CDM 6.19.0 Java model during the FpML→CDM project.
Everything below was verified via `javap` on the `org.finos.cdm:cdm-java:6.19.0` JAR.

---

## 1. Enum naming

CDM uses unusual conventions that don't always match what you'd expect coming from FpML.

### DayCountFractionEnum
- `_30E_360` (not `_30_E_360`)
- `_30E_360_ISDA` (not `_30_E_360_ISDA`)
- `ACT_365L` (not `ACT_365_L`)
- `CAL_252` (not `BUS_252` — that was the old name)
- `ACT_365_FIXED` — OK
- `ACT_ACT_ISDA` / `ACT_ACT_ICMA` / `ACT_ACT_AFB` — OK

### RollConventionEnum
The numeric values are **prefixed with an underscore**:
- `_14`, `_15`, `_31`, etc.
- `EOM`, `IMM`, `NONE` — without prefix

### CounterpartyRoleEnum
- Java values: `PARTY_1`, `PARTY_2`
- Serialized in JSON: `"Party1"`, `"Party2"` (capitalized, no underscore)

### PeriodEnum vs PeriodExtendedEnum
- `PeriodEnum`: D / W / M / Y — used for `indexTenor`, `paymentFrequency`
- `PeriodExtendedEnum`: adds T (Term) — used for `calculationPeriodFrequency`

### BusinessDayConventionEnum
- `MODFOLLOWING` (not `MOD_FOLLOWING`)
- `NotApplicable` / `NotEnumerated` in FpML → `NOT_APPLICABLE`

### Day / type enums
- `DayTypeEnum`: `CurrencyBusiness` → `CURRENCY_BUSINESS` (camelCase → UPPER_SNAKE regex)
- Use `fromDisplayName(String)` when available before `valueOf()`

---

## 2. Fields present in reference JSON but absent from the Java model

Verified via `javap` — these classes/setters **do not exist** in CDM 6.19.0:

| Field | Expected class | Status |
|---|---|---|
| `assetType` | `AssetBase`, `IndexBase` | no setter |
| `securityType` | `Security` | no setter |
| `priceSubType` | `PriceSchedule` | no setter |
| `averagingFeature` | `cdm.product.template.AveragingFeature` | **class does not exist** |

These fields are in the FINOS reference dataset but have **no** equivalent in the 6.19 Java model. Our `SemanticDiff.DROPPED_ANYWHERE` normalizes them — this isn't abusive masking, it's documenting the version divergence.

The official CDM ingestion (`MapDataDocumentToTradeState`) also omits these fields: it's confirmed that the reference comes from an older model.

---

## 3. Class names to look for when what you expect doesn't exist

| You look for | Where it is in CDM 6.19.0 |
|---|---|
| `cdm.observable.asset.FloatingRateIndexEnum` | **`cdm.base.staticdata.asset.rates.FloatingRateIndexEnum`** |
| `cdm.observable.asset.FloatingRateIndex` | **`cdm.observable.asset.FloatingRateIndex`** (OK) |
| `FieldWithMetaFloatingRateIndexEnum` | `cdm.base.staticdata.asset.rates.metafields.FieldWithMetaFloatingRateIndexEnum` |
| `ReferenceWithMetaBusinessCenters` | `cdm.base.datetime.metafields.ReferenceWithMetaBusinessCenters` (not `cdm.base.datetime`) |
| `Address` (for cross-ref) | `com.rosetta.model.lib.meta.Reference` (`address` key in JSON) |
| `Key` (for location) | `com.rosetta.model.lib.meta.Key` (`location` key in JSON via mixin) |
| `Identifier` (trade-level) | `cdm.event.common.TradeIdentifier` extends `cdm.base.staticdata.identifier.Identifier` |
| `PartyName` | doesn't exist — use `FieldWithMetaString` for `Party.name` |
| `AveragingFeature` | **doesn't exist in 6.19.0** |

---

## 4. DOCUMENT-scope address-refs (full pattern)

The `address` and `meta.location` are Jackson aliases set by MixIns in rosetta-common:

```java
// To produce {"address": {"scope": "DOCUMENT", "value": "quantity-1"}}
ReferenceWithMetaXxx.builder()
    .setReference(Reference.builder()
        .setScope("DOCUMENT")
        .setReference("quantity-1")
        .build())
    .build()
```

```java
// To produce {"meta": {"location": [{"scope": "DOCUMENT", "value": "quantity-1"}]}}
MetaFields.builder()
    .addKey(Key.builder()
        .setScope("DOCUMENT")
        .setKeyValue("quantity-1")
        .build())
    .build()
```

The responsible MixIns:
- `LegacyReferenceMixIn` — `Reference.getReference()` → JSON `value`
- `ReferenceWithMetaMixIn` — `getReference()` → JSON `address`
- `LegacyGlobalKeyFieldsMixIn` — `MetaFields.getKey()` → JSON `location`

---

## 5. JSON polymorphism via wrapper field

CDM uses JSON wrappers to discriminate choices (not Jackson `@type`):

```json
"observable": {
  "value": {
    "Index": {
      "InterestRateIndex": { ... }
    }
  }
}
```

On the Java side, the builder is flat: `setObservable(Observable.builder().setIndex(...))` — the wrapper appears only at serialization.

**Exception**: `unscheduledTransfer` in `transferExpression`. The wrapper has no Java counterpart (the model was flattened). Our `SemanticDiff.HOIST_WRAPPER` hoists its children for the diff.

---

## 6. Payout types and their use cases

| Payout | For |
|---|---|
| `InterestRatePayout` | IRS, FRA, capFloor, swaption underlier |
| `SettlementPayout` | FX spot/forward/NDF, FX swap legs, bullet payment, repo |
| `OptionPayout` | swaption, fxOption, equityOption, bondOption, commodityOption, swap option, variance/dividend option |
| `CreditDefaultPayout` | creditDefaultSwap (single, index, basket), CDS option underlier |
| `PerformancePayout` | returnSwap, variance/correlation/volatility swap, dividend swap, fx variance/volatility |
| `FixedPricePayout` | dividend swap fixed leg, commodity swap fixed leg |
| `CashflowPayout` (not encountered here) | — |

---

## 7. Builder API: common pitfalls

### Always use `.builder()` then `.build()`
The `XxxBuilder` are mutable, the `Xxx` (interface) are immutable.

### Choice types
For `Asset.setCash(...)` or `Observable.setAsset(...)`, **no** intermediate wrapper — direct.

### MetaFields.addKey vs setMeta(Key)
- `addKey(Key)` adds an element to the `location[]` list
- `setMeta` doesn't exist on most classes — it's typically on the `FieldWithMeta*` wrapper

### `toBuilder().build()` ≠ identity
Rebuilding an object via toBuilder() **reinjects** the values, which sometimes reorders the arrays. Use only when you explicitly want to reset sub-objects.

---

## 8. Typos / oddities of the model

- `notionalReference` in the reference — **`notionaReference`** in the 6.19.0 Java model (typo, missing 'l'). Aliased in `SemanticDiff.FIELD_ALIASES`.
- `barrier` in reference JSON vs `knock` in the model (for knock-in/knock-out). Aliased.

---

## 9. `meta.externalKey` fields vs FpML `id`

The `meta.externalKey` reproduces the FpML `id="..."` attribute on elements. Propagate it systematically on:
- `Party.meta.externalKey` (from `<party id="party1">`)
- `BusinessCenters.meta.externalKey` (from `<businessCenters id="primaryBusinessCenters">`)
- `CalculationPeriodDates.meta.externalKey` (from `<calculationPeriodDates id="fixedCalcDates1">`)
- `PaymentDates`, `ResetDates`, `tradeDate` — same pattern

This is what allows the `externalReference` (in `ReferenceWithMetaParty`, `businessCentersReference`, etc.) to resolve.
