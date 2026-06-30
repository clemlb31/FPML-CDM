# MXML ↔ FpML Component

Bidirectional conversion between **MXML** (Murex XML) and **FpML 5.x**.

| Direction | Status | Approach |
|---|---|---|
| **MXML → FpML** | 🔨 In progress | Java port from the XSLT spec (see below) → `io.fpmlcdm.mxml.fpml` |
| **FpML → MXML** | ⏳ Future | Symmetric, after MXML→FpML |

Test data: [data/ground_truth/mxml-fpml/](../data/ground_truth/mxml-fpml/) — each `OUT_IRD_*` / `OUT_CRD_*` folder contains
the MXML input (`*.xml`), the expected FpML (`*_expected.xml`), a diff mask (`*_ignored.xml`)
and the expected CDM (`*_CDM.json`).
Spec & schemas: [knowledge_base/mxml-fpml/](../knowledge_base/mxml-fpml/) · [schemas-and-validation.md](schemas-and-validation.md).

---

## The spec: Murex XSLT (reference, not executable as-is)

[knowledge_base/mxml-fpml/](../knowledge_base/mxml-fpml/) contains **Murex XSLT 1.0 modules**
(MXML→FpML, FpML-5 confirmation namespace): `ird-5-3/`, `ird-4-3/` (swap, fra, cf, oswp, aswp, cs),
`utils/` (dates, schedule, stubs) and `mapping/` (value tables: frequency, barrierType, quoteBasis).

⚠️ **Not executable as-is**: of 89 imported modules, ~60 are missing (the entire
`extract.mxml.*` layer that reads the MXML, most of the `any2fpml.*` building blocks, the EXSLT helpers), and the
`href` values are dotted names resolved by a Murex URIResolver that is not provided. Details:
[knowledge_base/mxml-fpml/MXML_XSLT_MANIFEST.md](../knowledge_base/mxml-fpml/MXML_XSLT_MANIFEST.md).

➡️ **Decision**: we use them as a **specification** and port the logic to Java (self-contained, with no
proprietary dependency). Rejected alternative: export the ~60 missing modules from Murex + a Xalan harness.

## Port plan — MXML → FpML (Java)

Target package: **`io.fpmlcdm.mxml.fpml`** (mirrors the existing structure, **with no CDM dependency**:
it's pure XML→XML, so it compiles/runs even when the CDM build is blocked).

```
io/fpmlcdm/mxml/fpml/
├── MxmlToFpmlConverter.java     # orchestration (MXML DOM → FpML DOM/string)
├── MxmlToFpmlContext.java       # conversion context (ID registry, error/warning collection)
├── MxmlProductMapper.java       # product-mapping entry point
├── detect/MxmlProductDetector   # dispatch by MXML product type (tradeCategory/typology)
└── products/SwapMapper, …       # IRS ported; CapFloor/Cs/Fra next (reuses io.fpmlcdm.core xml utils)
```

Build order (most to least covered by the spec, by NOMAP leverage):
1. **IRD vanilla swap** (IRS) — done.
2. Cap/Floor (cf), Cross-currency (cs), FRA, then Swaption (oswp), Asset swap (aswp).

Validation: compare the produced FpML against the `*_expected.xml` (semantic XML diff via
[`XmlSemanticDiff`](../src/main/java/io/fpmlcdm/report/XmlSemanticDiff.java)).
The produced FpML can then feed the existing **FpML→CDM** pipeline ⇒ see [mxml-cdm.md](mxml-cdm.md).

## Validation harness (built)

- [`core/dataset/PairLoader`](../src/main/java/io/fpmlcdm/core/dataset/PairLoader.java) +
  [`TestPair`](../src/main/java/io/fpmlcdm/core/dataset/TestPair.java) — format-agnostic
  discovery of `{input, expected}` pairs (works for all 3 datasets).
- [`report/XmlSemanticDiff`](../src/main/java/io/fpmlcdm/report/XmlSemanticDiff.java) —
  namespace-aware FpML comparison. **Compares against `*_expected.xml` only** (not the
  `*_ignored` masks — those only ever ignore non-deterministic timestamps, which the
  comparator handles generically). Tolerant of: volatile timestamps; anonymized party
  display names (`partyId`/`sentBy`/`sendTo` text — the dataset scrubs `MXpress`/`MUREX` →
  `BARCLAYS`/`party1` after generation); numeric formatting (`0.025 == 0.0250`). Parties
  matched by `@id` (order-independent) while all `href`s are compared, so a counterparty
  collapse (payer==receiver) is still caught.
- Runnable report [`MxmlToFpmlReport`](../src/main/java/io/fpmlcdm/mxml/fpml/MxmlToFpmlReport.java)
  (offline) + JUnit [`MxmlToFpmlTest`](../src/test/java/io/fpmlcdm/mxml/fpml/MxmlToFpmlTest.java)
  (NOMAP = skipped, so it auto-activates per mapper as they land).

## Status (2026-06-30) - vanilla IRS + amendment envelope

`SwapMapper` covers the vanilla fixed/float IRS plus the amendment lifecycle envelope.
On the 291-pair MXML->FpML dataset: **5 EQUAL, ~74 produced-with-diffs, ~212 NOMAP**
(no mapper yet for non-IRS products). Chained end-to-end, this yields **5/196 verified
MXML->CDM EQUAL** (see [mxml-cdm.md](mxml-cdm.md)).

The 5 EQUAL: INS_01, INS_17 (additionalPayment), INS_09 (amortizing notional +
cashflowsMatchParameters), INS_FinalShortCurrent (conditional paymentDaysOffset),
CancelAndReissue_01 (requestConfirmation/amendment envelope).

Implemented & verified (all XSLT/data-grounded; FpML->CDM held 563/563 throughout):
- **Floating-rate index mapping** (EURIBOR/LIBOR/EONIA/SONIA/BBSW/CIBOR/STIBOR/BKBM).
  Reproduces Murex's actual output, including its distortions onto LIBOR
  (CDOR/FEDFUND/SARON/SOFR/KLIBOR) so output matches `_expected.xml`.
- **Unadjusted schedule regeneration** by roll-convention arithmetic (~82% diff reduction).
- **Day-count** from the labeled `dayCountFraction` via the dayCountBasis table
  (ACT/365->ACT/365L etc.), skipping the index's unlabeled convention decoy.
- **Reset/fixing business center** + conditional `fixingDates` from `resetBusinessCenters`.
- **Stubs** (firstRegular/lastRegular period dates + stubPeriodType), **amortizing notional
  steps**, **conditional paymentDaysOffset** (emit iff the leg has a paymentSchedule shifter),
  **additionalPayment** (with derived payer), **first-fixing observedRate**.
- **Termination-date BDC** per the XSLT rule (per-stream maturity vs adjustedMaturity).
- **Party emission** excludes additionalPayment-only hrefs (still catches counterparty collapse).
- **Amendment envelope** (`requestConfirmation`/`amendment`) for CANCEL_REISSUE/RESTRUCTURE,
  gated strictly on `contractEvents` so vanilla `dataDocument` trades are untouched.

Known blockers for further EQUALs (each its own scoped effort):
- **Other product families = NOMAP** (the bulk): cross-currency swap (34 cdm pairs),
  cap/floor (15), CDS (17), FX (12), FRA (5)... only IRS is ported. Highest-leverage next
  work: CapFloorMapper (cf/ XSLT present, reuses swap stream logic -> ~4-6 EQUAL),
  then CsMapper (reuses swap tradeNode + principalExchange -> ~6 EQUAL), FraMapper (~3).
- **Lifecycle termination/novation** - `originalTrade`/`newTrade` body + notional-impact math;
  the `tradeNotionalChange`/`tradeNovationContent` XSLT modules are absent. Some pairs are also
  blocked by payment-precision (5e-10) and unidentified executionDateTime sources.
- **Break clauses** - `earlyTerminationProvision`. The reference `_expected.xml` carries
  unsubstituted XSLT placeholders (literal `<businessCenter>businessCenter</businessCenter>`),
  not derivable from MXML -> these pairs cannot reach EQUAL even with a correct mapper.
- **Inflation / FX-linked (MTM) / exotic** - different element trees, separate mappers.
- **Root choice (dataDocument vs requestConfirmation)** is NOT MXML-derivable in general:
  the XSLT takes `fpmlType` as an external parameter (e.g. Restructure_02 has no
  `contractEvents` yet expects `requestConfirmation`). The `contractEvents`-presence gate is
  the safe proxy (0 false positives, misses ~5).

## FpML → MXML (future)
Symmetric direction: to be specified once MXML→FpML is stabilized (will reuse the `mapping/` tables in reverse).
