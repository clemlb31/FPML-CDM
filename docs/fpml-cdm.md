# FpML ↔ CDM Component

Bidirectional conversion between **FpML 5.x** (XML) and **FINOS CDM 6.19.0** (JSON).

| Direction | Maturity | Result |
|---|---|---|
| **FpML → CDM** | ✅ Production-ready | **530/530** (full) · **360/360** (curated) · 3 signals ⇒ 1590/1590 and 1080/1080 |
| **CDM → FpML** | 🧪 Prototype | ~51 %, 0 clean round-trip — details in [cdm-to-fpml.md](cdm-to-fpml.md) |

Data: [data/ground_truth/fpml-cdm/](../data/ground_truth/fpml-cdm/) (565 FpML/CDM pairs, 44 categories).
Domain notes: [knowledge_base/fpml-cdm/](../knowledge_base/fpml-cdm/).
Schemas & structural validity: [schemas-and-validation.md](schemas-and-validation.md).

---

## FpML → CDM (mature)

Source of truth: [reports/fpml-cdm-train-530.md](../reports/fpml-cdm-train-530.md) (2026-06-05) + git `652b41d`.

### Test scopes ([`DataDrivenValidationTest`](../src/test/java/io/fpmlcdm/DataDrivenValidationTest.java))

| Scope | Pairs | Filtering |
|---|---:|---|
| Curated (`mvn test`) | 360 | excludes `*-incomplete`, `invalid-products-*`, empty JSON |
| Full (`-Dincludeincomplete=true`) | 530 | excludes only `invalid-products-*` + empty JSON |
| Raw (`data/ground_truth/fpml-cdm`) | 565 | all `.xml`/`.json` |

### Coverage by family

| Family | FpML products | Mapper(s) |
|---|---|---|
| Rates / IRS | swap, swaption, capFloor, fra, bulletPayment, termDeposit, future | SwapMapper, SwaptionMapper, CapFloorMapper, FraMapper, BulletPaymentMapper |
| Inflation | swap (inflationRateCalculation) | SwapMapper |
| FX | fxSingleLeg, fxSwap, fxOption, fxDigitalOption, fxVariance/Volatility, fxForwardVolatilityAgreement | Fx*Mapper |
| Credit | creditDefaultSwap, creditDefaultSwapOption | CreditDefaultSwap(Option)Mapper |
| Equity | equityOption, returnSwap, equitySwap…, dividendSwap(Option)… | EquityOptionMapper, ReturnSwapMapper, DividendSwap(Option)Mapper |
| Commodity | commoditySwap, commodityOption, commoditySwaption, commodityBasketOption, commodity{Perf,Digital,Forward} | Commodity*Mapper, CommodityMetadataOnlyMapper |
| Variance/Corr/Vol | varianceSwap, correlationSwap, volatilitySwap, varianceOption… | VarianceSwapMapper, VarianceOptionMapper |
| Bond options | bondOption | BondOptionMapper |
| Repo / Sec. lending | securityLending | SecurityLendingMapper (*metadata only*) |
| Generic | genericProduct, strategy, instrumentTradeDetails | GenericProductMapper, BulletPaymentMapper |

### The 3 validation signals
1. **`semanticallyEqual`** — semantic JSON diff ([`SemanticDiff`](../src/main/java/io/fpmlcdm/report/SemanticDiff.java)) vs FINOS reference.
2. **`noNewCdmViolations`** — no new CDM data-rule violation ([`CdmValidator`](../src/main/java/io/fpmlcdm/fpml/cdm/validate/CdmValidator.java), Guice `CdmRuntimeModule`).
3. **`globalKeyIntegrity`** — after Regnosys recomputation ([`GlobalKeyReproducer`](../src/main/java/io/fpmlcdm/fpml/cdm/validate/GlobalKeyReproducer.java)), each `globalReference` resolves to a `globalKey`.

### Architecture

```
FpML XML → DOM (XmlUtils.parse) → ProductDetector.dispatch()
        → ProductMapper.map() → TradeState (CDM builders)
        → RosettaObjectMapper → SemanticDiff.compare()
```

```
src/main/java/io/fpmlcdm/fpml/cdm/
├── Cli.java, FpmlToCdmConverter.java, CategoryReport.java, DiffOne.java
├── common/   (16 classes: XmlUtils, DateMapper, EnumMappers, PartyMapper, …)
├── detect/   ProductDetector.java  (~43 FpML elements)
├── products/ 28 product mappers
├── payouts/  InterestRatePayoutMapper, CashflowMapper
└── validate/ CdmValidator, GlobalKeyReproducer, *Cli
```
(`io.fpmlcdm.report` SemanticDiff/ReportWriter is shared, outside this tree.)

### Semantic normalization (ignored fields — no setter in 6.19.0)
`globalKey`/`globalReference` (Regnosys hash), `assetType`, `securityType`, `priceSubType`.

### Debug tools
```bash
mvn -q exec:java -Dexec.mainClass=io.fpmlcdm.fpml.cdm.CategoryReport -Dexec.args="rates-5-10 credit-derivatives-5-13"
mvn -q exec:java -Dexec.mainClass=io.fpmlcdm.fpml.cdm.DiffOne       -Dexec.args="rates-5-10 ird-ex01-vanilla-swap"
```

### Adding a mapper
1. `products/XxxMapper.java implements ProductMapper` 2. dispatch in `detect/ProductDetector.java`
3. reuse `common/*` 4. validate against the full dataset (3 signals).

---

## CDM → FpML (prototype)

Full details: **[cdm-to-fpml.md](cdm-to-fpml.md)**. In short: 16 mappers under
[`src/main/java/io/fpmlcdm/cdm/fpml/`](../src/main/java/io/fpmlcdm/cdm/fpml/), ~51 % conversion,
0 clean round-trip.

## Build / environment

See [schemas-and-validation.md](schemas-and-validation.md) and the root [README](../README.md): the deps
(including `cdm-java:6.19.0`) are on Maven Central; the default `mvn -takari` points to the Murex Nexus
(unreachable off VPN) → workaround `mvn -s` Central.
