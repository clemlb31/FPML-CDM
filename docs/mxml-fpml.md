# MXML ↔ FpML Component

Bidirectional conversion between **MXML** (Murex XML) and **FpML 5.x**.

| Direction | Status | Approach |
|---|---|---|
| **MXML → FpML** | 🔨 To build | Java port from the XSLT spec (see below) → `io.fpmlcdm.mxml.fpml` |
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
it's pure XML→XML, so it compiles/runs even when the CDM build is blocked). The converter skeleton
(`MxmlToFpmlConverter`, `MxmlToFpmlContext`, `MxmlProductMapper`, `detect/MxmlProductDetector`) now
**exists as compiling skeletons**; the product-mapping logic remains to be ported.

```
io/fpmlcdm/mxml/fpml/
├── MxmlToFpmlConverter.java     # orchestration (MXML DOM → FpML DOM/string)
├── MxmlToFpmlContext.java       # conversion context (ID registry, error/warning collection)
├── MxmlProductMapper.java       # product-mapping entry point
├── detect/MxmlProductDetector   # dispatch by MXML product type
└── products/SwapMapper, FraMapper, CapFloorMapper, SwaptionMapper, …  (reuses io.fpmlcdm.core xml utils for the DOM)
```

Build order (from most to least covered by the spec):
1. **IRD vanilla swap** (`data/ground_truth/mxml-fpml/OUT_IRD_SWAP_*` / `OUT_IRD_ASWP_*`) — canonical case.
2. FRA, Cap/Floor, Swaption (oswp), Asset swap (aswp), Cross-currency (cs).

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
  display names (`partyId` text — the mxml-fpml dataset scrubs `MXpress`/`MUREX` →
  `BARCLAYS`/`party1` after generation); numeric formatting (`0.025 == 0.0250`). Parties
  matched by `@id` (order-independent) while all `href`s are compared, so a counterparty
  collapse (payer==receiver) is still caught.
- Runnable report [`MxmlToFpmlReport`](../src/main/java/io/fpmlcdm/mxml/fpml/MxmlToFpmlReport.java)
  (offline) + JUnit [`MxmlToFpmlTest`](../src/test/java/io/fpmlcdm/mxml/fpml/MxmlToFpmlTest.java)
  (NOMAP = skipped, so it auto-activates per mapper as they land).

## Status (2026-06-29) — vanilla IRS ported

`SwapMapper` covers the vanilla fixed/float IRS. On the 291-pair dataset: **1 EQUAL,
~78 produced-with-diffs, ~212 NOMAP** (no mapper yet for the other products). The first
EQUAL (`OUT_IRD_IRS_5-3_INS_01`) reproduces notional, rate (%/100), frequencies, day-count,
business centers, fixing dates and the precomputed per-period cashflows exactly.

Implemented & verified:
- **Floating-rate index mapping** (EURIBOR/LIBOR/EONIA/SONIA/BBSW/CIBOR/STIBOR/BKBM families).
  Faithfully reproduces Murex's actual output, **including its distortions onto LIBOR**
  (CDOR/FEDFUND/SARON/SOFR/KLIBOR) so output matches `_expected.xml`.
- **Unadjusted schedule regeneration** by roll-convention arithmetic (no holiday calendar
  needed); reduced the unadjusted-date diffs ~82%.
- **Day-count** from `rateConvention/dayCountFraction` (skips the `yieldConvention` decoy).
- **Reset/fixing business center** from `resetBusinessCenters` (not hardcoded).

Known blockers for further EQUALs (each its own scoped effort, not a quick win):
- **Lifecycle envelopes** — ~18 pairs expect a `requestConfirmation`/`FpML` root wrapping the
  trade in `<amendment>`/`<termination>`/`<novation>` + a message `header`. Touches trade
  direction & event semantics. Out of scope for the vanilla port.
- **Stubs** — `firstRegularPeriodStartDate`, `stubPeriodType` (irregular schedules).
- **Break clauses** — `earlyTerminationProvision` (mandatory ET + cash settlement). ⚠️ Note:
  the reference `_expected.xml` for these carries **unsubstituted XSLT placeholders**
  (literal `<businessCenter>businessCenter</businessCenter>`, `id="mandatoryEarlyTerminationDate"`)
  that are not derivable from the MXML — so these pairs cannot reach EQUAL even with a correct
  mapper. The termination-date `businessDayConvention` is also **not** a simple copy of the
  schedule generator's convention (a date-shift heuristic correlates ~150/166 but is wrong on
  16, incl. `PRECEDING`/`FOLLOWING` cases) — its true MXML source is still unidentified.


## FpML → MXML (future)
Symmetric direction: to be specified once MXML→FpML is stabilized (will reuse the `mapping/` tables in reverse).
