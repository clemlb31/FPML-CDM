# CDM → FpML Conversion (prototype)

Reverse direction of the main converter: takes a FINOS **CDM 6.x** `TradeState` (JSON) and
re-specialises it into **FpML 5.x** XML. Mirrors the `FpmlToCdmConverter` architecture.

> **Status: prototype, not production.** ~51 % of files convert to *well-formed* FpML
> (289/565 on the train set); **no file round-trips cleanly** (CDM → FpML → CDM still diffs).
> The 2 former P0 compile defects are now **fixed** (see below).
> The mature, validated direction is FpML → CDM (see [fpml-cdm.md](fpml-cdm.md)).

Conceptual mapping reference: [CDM_to_FpML_Mapping_Concepts.md](../knowledge_base/fpml-cdm/CDM_to_FpML_Mapping_Concepts.md).

---

## Architecture

```
CDM JSON (TradeState)
  → RosettaObjectMapper (deserialize)
  → CdmProductDetector            # route on productQualifier / payout shape
  → CdmToFpmlProductMapper.map()  # build the FpML product subtree
  → FpmlXmlBuilder                # namespaced XML emission
  → CdmToFpmlMappingContext       # ID registry + href resolution + error/warning collection
  → FpML XML
```

Code lives under [src/main/java/io/fpmlcdm/cdm/fpml/](../src/main/java/io/fpmlcdm/cdm/fpml/):

- **`CdmToFpmlConverter`** — orchestration.
- **`CdmProductDetector`** — routes CDM products to a mapper.
- **`CdmToFpmlMappingContext`** — XML document lifecycle, deterministic `createFpmlId(prefix)`
  (counter-based, e.g. `calcPeriodDates_1`), `resolveHref()`, collect-and-report errors/warnings.
- **`FpmlXmlBuilder`** / **`FpmlConstants`** — namespaced XML helpers.
- **`common/`** — `CdmPartyMapper`, `CdmDateMapper`, `CdmAmountMapper`.
- **`payouts/`** — `FpmlInterestRatePayoutMapper`.
- **`products/`** — 16 mappers: InterestRateSwap, Swaption, CapFloor, Fra, BulletPayment,
  FxSwap, FxSingleLeg, FxOption, CreditDefaultSwap, EquitySwap, EquityOption,
  DividendSwap, DividendSwapOption, BondOption, CommoditySwap, CommodityOption.
- **`CdmToFpmlRoundTripCli`** — CDM → FpML → CDM round-trip with `SemanticDiff`, writes XML to
  [generated-fpml/](../generated-fpml/).
- **`CdmToFpmlReportWriter`** — HTML/Markdown reports.

---

## Resolved defects (P0 — fixed)

These previously blocked a clean build against the public CDM artifact; both are now resolved:

1. **`CdmProductDetector`** called `extractStringValue(Object)` at lines 283/341/430 without declaring it.
   ✅ **Fixed** — the `extractStringValue` helper has been added to `CdmProductDetector`.
2. **`cdm/fpml/products/BulletPaymentMapper`** imported `cdm.product.asset.CashflowPayout`, which is
   absent from every reachable CDM jar (6.7.0, 6.19.0, nightly).
   ✅ **Fixed** — the dead `CashflowPayout` import has been removed from `BulletPaymentMapper`.

> Note: the "289/565 / 51 %" figure and the round-trip results below predate the current source state.
> Treat them as historical until a green `mvn compile` + `mvn test` is reproduced for the `cdm/fpml` package.

---

## Round-trip results (historical)

- **51 % conversion** (289/565 train files produced well-formed FpML XML).
- **0 perfect round-trips** — every converted file still diffs after CDM → FpML → CDM.
- **Top diff cause (~70 %): counterparty-ID mismatch** — our hash-based party IDs differ from the
  original CDM identifiers. Reusing source identifiers is the highest-leverage fix.

---

## Mapping notes (from earlier checkpoints)

Kept here so the context isn't lost; verify each against a compiling build before relying on it.

- **Party href format** — FpML fragment identifiers must be `href="#party1"` (with `#`), not `href="party1"`.
- **Deterministic IDs** — `createFpmlId()` is counter-based and `synchronized`, so output is
  reproducible (`calcPeriodDates_1`, `calcPeriodDates_2`, …) instead of nanoTime-based.
- **Notional / priceQuantity** — `InterestRateSwapMapper.mapNotionalWithLocation()` extracts actual
  values from CDM `priceQuantity.quantity[]` and emits `<notionalStepSchedule>`; stream index
  (`quantity-1`, `quantity-2`) comes from the location metadata.
- **Fixed rate** — extraction chain: `trade.lot.priceQuantity[].price[]` (location `price-1`)
  → `FixedRateSpecification.rateSchedule.price` → `RateSpecification.price` → hard-coded `0.06`
  fallback (the fallback should ultimately be removed).
- **Floating-rate index name** — `extractIndexName()` reads `getFloatingRateIndex()` /
  `getInterestRateIndex()` and identifier fields to recover e.g. `EUR-EURIBOR-Reuters`.

---

## Roadmap

See the **CDM → FpML** section of [TODO.md](../TODO.md). Priority order: (P0 ✅ done) compiles,
(P1) deterministic party IDs + value round-tripping + namespace validation, (P2) re-enable the
6 `cdm/fpml` test classes and track diff trends.
