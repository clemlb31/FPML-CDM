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

Validation: compare the produced FpML against the `*_expected.xml` (semantic XML diff masked by `*_ignored.xml`).
The produced FpML can then feed the existing **FpML→CDM** pipeline ⇒ see [mxml-cdm.md](mxml-cdm.md).

## FpML → MXML (future)
Symmetric direction: to be specified once MXML→FpML is stabilized (will reuse the `mapping/` tables in reverse).
