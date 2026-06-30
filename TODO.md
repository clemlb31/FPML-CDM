# TODO — FpML · CDM · MXML Conversion Engine

Roadmap of real remaining work, by compartment. Overview: [README.md](README.md). Per-compartment docs:
[docs/fpml-cdm.md](docs/fpml-cdm.md) · [docs/cdm-to-fpml.md](docs/cdm-to-fpml.md) ·
[docs/mxml-fpml.md](docs/mxml-fpml.md) · [docs/mxml-cdm.md](docs/mxml-cdm.md) ·
[docs/schemas-and-validation.md](docs/schemas-and-validation.md).

---

## Build / environment

Dependencies (incl. `cdm-java:6.19.0`) **are on Maven Central** — the cached `6.19.0` came from
`central`. The blocker out-of-the-box is the machine's default Maven (`-takari`), which points at the
**Murex Nexus mirror** (`pandora.fr.murex.com` / `repo-dev/nexus`), unreachable off-VPN: dependency
resolution fails with `No versions available …` on the version-ranged eclipse.emf/antlr transitive deps
of the rosetta toolchain.

- [ ] **Provide a Central-based `settings.xml`** in the repo (or document `mvn -s …`) so the project
      builds without the Murex Nexus. With a Central mirror, deps resolve and the committed core compiles.
- [x] **README claim fixed** — was "no private repo"; now accurately: deps on Central, default toolchain
      points at an unreachable Murex mirror.
- [ ] **Heads-up for agents:** the project targets `cdm-java:6.19.0` (verified via `javap`, see
      `knowledge_base/fpml-cdm/knowledge/cdm_api_quirks.md`). Don't burn time trying other CDM versions — the
      remaining compile errors (below) are in specific files, not a version mismatch of the core.

---

## FpML → CDM (production-ready — 530/530)

**Status:** 360/360 curated · 530/530 full (incl. incomplete) · 3 validation signals
(1080/1080 and 1590/1590). 28 product mappers, 10 families. Source: `reports/fpml-cdm-train-530.md`
(2026-06-05) + git `652b41d`.

### Working-tree changes — RE-VALIDATED 2026-06-24 (offline javac + full-dataset regen)
Baseline established offline (javac + m2 classpath + generation over the 565 FpML files in `data/ground_truth/fpml-cdm`):
- [x] **`DateMapper`, `InterestRatePayoutMapper`, `CreditDefaultSwapMapper`, `SecurityLendingMapper`** —
      **output byte-identical to HEAD on 563/563 pairs** (WT-generated vs HEAD-generated, same serializer). These 4
      edits are **output-neutral → no regression**. Safe to commit.
- [ ] **`CommodityMetadataOnlyMapper`** — the WT rewrite (+225/−71) **does not compile** against the public CDM
      6.19 (`Account`, `TaxonomySourceEnum.FINOS_CDM`, `setProduct`, `setHref`, `COMMODITY_CODE`). **The only
      compile blocker** of the FpML→CDM compartment. Decide: port to the public 6.19 API, or **shelve it**
      (revert to the HEAD version that compiles + is output-neutral) to unblock the baseline.

> ⚠️ Method note: the committed `data/ground_truth/fpml-cdm/cdm/*.json` files **are not byte-reproducible** from the code
> (they carry computed Rosetta `globalKey` values that CLI generation does not set). The "530/530" is measured
> by **`SemanticDiff`** (which ignores those `globalKey` values), not by binary equality. To assess a regression,
> compare **generated-vs-generated** (globalKey absent on both sides), not generated-vs-committed-reference.
>
> Another out-of-scope blocker: the **`cdmtofpml/`** prototype (untracked, CDM→FpML direction) does not compile
> (`extractStringValue` undefined, `CashflowPayout` absent) — it breaks a global `mvn compile` but not FpML→CDM.

### Remaining functional gaps
- [ ] **`SecurityLendingMapper`** — currently metadata-only (tradeHeader + parties). Needs the full
      security-lending structure: borrowed-securities quantity/identification, collateral,
      fee/stock-fee calculation, return-of-securities leg.
- [ ] **Multi-trade FpML documents** — `FpmlToCdmConverter` already emits one `TradeState` per
      `<trade>`, but `DataDrivenValidationTest` asserts exactly one. Decide on the contract for the
      `*-incomplete` multi-trade docs and add coverage.
- [ ] **Negative tests** for malformed/invalid FpML input handling.
- [ ] **`genericProduct` fallback** — richer structure extraction than the current metadata-only path.

---

## CDM → FpML (prototype — needs major work)

**Status:** ~51 % conversion (289/565), **0 perfect round-trips**, all outputs have diffs
(top cause: counterparty-ID mismatch). 16 product mappers. Detail in [docs/cdm-to-fpml.md](docs/cdm-to-fpml.md).

### P0 — Make it compile
- [ ] **`CdmProductDetector`** references an **undefined** helper `extractStringValue(Object)`
      (called at lines 283/341/430, never declared) — genuine compile error, version-independent.
- [ ] **`cdmtofpml/products/BulletPaymentMapper`** imports `cdm.product.asset.CashflowPayout`,
      absent from every reachable CDM jar — confirm the intended CDM type and fix the import.
- [ ] Establish a green `mvn compile` for the whole `cdmtofpml` package, then a green `mvn test`
      for its test suite, in a Murex build env. (The "[x] done" claims in git history for this
      package were never verified against a compiling build.)

### P1 — Core mapping accuracy
- [ ] **Deterministic party IDs** — round-trip diffs are dominated by hash-based party IDs that don't
      match the original CDM identifiers. Reuse the source identifiers.
- [ ] **priceQuantity / notional schedules**, **fixed-rate extraction**, **floatingRateIndex name** —
      verify the values actually round-trip (claims exist; no passing round-trip yet).
- [ ] **Namespace validation** — assert all generated XML strictly adheres to the FpML namespace.

### P2 — Coverage & tests
- [ ] Make the 6 `cdmtofpml` test classes actually run (they depend on P0).
- [ ] Track per-product round-trip diff trends across dataset runs.

---

## MXML → CDM — fidelity defects (LLM-judge audit, 2026-06-24)

Dataset `data/generated/mxml-cdm/` (239 pairs) audited by LLM-as-judge + adversarial pass. **82 % faithful**
(PASS+MINOR); **37 confirmed material defects** (15.5 %, after rebutting the 6 false positives). Detail,
evidence and full list: [reports/mxml-cdm-llm-judge.md](reports/mxml-cdm-llm-judge.md). Prioritized:

**P0 — MXML→FpML (silent value errors)**
- [ ] **Floating-index mapping**: stop collapsing non-LIBOR/RFR indices onto LIBOR — SARON, SOFR,
      Fed-Funds-Average, CDOR, KLIBOR are rendered as `*-LIBOR-BBA`. ~8 cases (`IRD_CS_5-3_INS_11`,
      `IRD_OIS_*`, `IRD_IRS_5-3_INS_07/08`, `IRD_CS_5-3_CancelAndReissue_01`…). *The most dangerous defect.*
- [ ] **Counterparties & direction**: stop merging the two counterparties into a single `BARCLAYS`
      (`payer=receiver` → unusable trade) — `CRD_RTRS_*`, `CRD_CRDIO_5-3_INS_02`, `IRD_OSWP_5-3_CustomizedFlows`,
      `IRD_SWAPTION_INS_01`. (Anonymization with distinct roles = OK/cosmetic.)
- [ ] **CDO tranche**: `attachmentPoint` 1 % rendered as 0 % (`CRD_SCDO_*`) — changes the loss exposure.

**P1 — FpML→CDM (envelopes dropped by the `InterestRatePayout` pipeline)**
- [x] **Optionality — callable/extendable swaps** (2026-06-24): `TerminationProvisionMapper.map()` only read
      `earlyTerminationProvision` and **returned null** otherwise — the `mapCancelableProvision`/
      `mapExtendibleProvision` methods, **already written, were never called**. Wired up (6 lines). Verified:
      compiles green, **559/563 fpml-cdm unchanged (0 regression)**, the 5 judge cases (`INS_Callable_01/02`,
      `INS_Extendable_01/02/03`) now produce `terminationProvision`. *(working-tree edit, to commit.)*
- [x] **Optionality — Bermudan cap + CDS swaption** (2026-06-24):
      • `CapFloorMapper` now wires `TerminationProvisionMapper.map(capFloor, ctx)` → `IRD_CF_5-3_Bermudan_01`
        produces `terminationProvision.earlyTerminationProvision.optionalEarlyTermination` (Bermuda style,
        **36 exercise dates**, cashSettlement). • `CreditDefaultSwapOptionMapper` handles the `<strike><price>` strike
        (in addition to `spread`/`strikeReference`) → `CRD_CDS_5-3_INS_12` captures `OptionStrike.strikePrice` = 1.0.
      Verified: compiles green, **559/563 fpml-cdm unchanged** (these 2 cases have no equivalent in fpml-cdm,
      validated directly). *Working-tree edits, to commit.*
      ▸ Optionality family **closed**. The inflation params of `Bermudan_01` remain (family B, distinct).
- [x] **Inflation — index identity** (2026-06-24): `QuantityMapper` only set the `setInflationRateIndex`
      enum (null if no match) → empty `InflationIndex`. Added an `addIdentifier`
      (raw name, type OTHER), **symmetric to the floating branch**. Verified: compiles green, **14 inflation
      files** now identifiable (`EUR-EXT-CPI`, etc.), 0 collateral regression (545/563 unchanged
      excluding inflation+optionality). *(working-tree edit)* ⚠️ `SWAP_INS_01`/`Restructure_V2` carry the name
      **distorted** by MXML→FpML (Fed-Funds instead of CPURNSA) — faithful to the FpML, distortion out-of-scope.
- [ ] **Inflation — structured params** (MINOR, downgraded by the adversarial check because the economics survive
      in the `cashflowsMatchParameters=true` cashflows): interpolationMethod/lag/initialIndexLevel/multiplier
      in `InflationRateSpecification`. To be done only if a downstream consumer recomputes from the terms.
- [x] **Cap/floor premiums** (2026-06-24): `CapFloorMapper` iterates the `<premium>` elements of the `<capFloor>` →
      `transferHistory` (reuses `FxOptionMapper.buildPremiumTransfer`, enriched for the direct
      non-wrapped `paymentDate` of caps). Verified: `FinalStub`=1, `PeriodicPremium`=**5 flows**, `MultiPremium`=3,
      `CF_4-3_INS_01`=1 (amount/currency/payer-receiver/settlementDate correct). 0 FX/fpml-cdm regression. *(WT edit)*

**P2**
- [x] **CAPITAL payments** (2026-06-24): `SwapMapper` now captures the `<payment>` siblings of `<trade>`
      (e.g. inside an `<amendment>`) → `transferHistory` (reuses `buildPremiumTransfer`). Verified:
      `Restructure-PAYG_01`=41509.99 EUR, `Restructure_03`=1606.87 EUR; 0 fpml-cdm regression. *(WT edit)*
- [ ] **Known-amount (ZC leg `knownAmountSchedule`)** — NOT done: requires a **CDM modeling
      decision**. `StreamLabels` sets `quantityLabel=null` for these legs (l.67-72) → no priceQuantity nor
      rate. The "known amount" (e.g. `IRD_IRS_ZC_5-3_INS_02` 12 M EUR) **is not a notional**. Options:
      (a) localized — inline quantity on the `InterestRatePayout`; (b) consistent — emit a `quantity-N` in
      the tradeLot (touches `StreamLabels`+`QuantityMapper`+payout). Scope: 1 fpml-cdm + 2 mxml-fpml. To be decided
      with a human (semantics of the known-amount payout).
- [ ] **Exotic products → empty payouts** — NOT done: requires **new mapping** (not a missing wiring).
      FX barrier (`CURR_OPT_BAR*`: `<fxOption><barrier><triggerRate>` → `OptionFeature.barrier`, absent from
      `FxOptionMapper`), FX digital (`CURR_OPT_RBT_*` FAIL), TRS on bond (`CRD_RTRS_*` → wrong EquitySwap —
      underlier part MXML→FpML), FX range forward (`CURR_FXD_FXD_GEN_PDT_INS_01`). Modeling to validate.

**P3**
- [x] **Data bug — `0002-11-30` sentinel** (2026-06-24): it is a **Murex null-date sentinel present in
      the source FpML** (not a FpML→CDM bug). `DateMapper.parse` now rejects years < 1900 (guard
      extended, was `<= 0`) → the garbage date is no longer propagated. 10 mxml-cdm files affected, 0 fpml-cdm
      regression. *(WT edit)*  ▸ The inconsistent CDO first-payment date is MXML→FpML (out-of-scope).

---

## Shared / cross-cutting
- [ ] **Coverage matrix** — keep README's family table in sync with `ProductDetector` as mappers change.
- [x] **MXML → CDM dataset** — built by chaining: `data/generated/mxml-cdm/<family-subfamily>/{mxml,cdm,ignored}/`
      (239 pairs, [reports/mxml-cdm-dataset-239.md](reports/mxml-cdm-dataset-239.md)). Fidelity audited above.
      Direct MXML→CDM mappers: not required as long as chaining suffices (cf. defects above = to fix in the 2 legs).
- [ ] **Scratch cleanup** — `workspaces/test-fra-autonomous/` was a throwaway prototype (deleted in the
      working tree); confirm removal at commit time.
