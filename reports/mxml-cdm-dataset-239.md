# MXML → CDM dataset (MXML→FpML→CDM chaining)

**Date:** 2026-06-23 · **Compartment:** [docs/mxml-cdm.md](../docs/mxml-cdm.md) · **FpML source:** `data/ground_truth/mxml-fpml/<cat>/fpml/*.xml`

## Method

The MXML→CDM dataset is built by **chaining**: each known-good FpML (`_expected.xml`, produced by
MXML→FpML) is passed through the `io.fpmlcdm.Cli` converter (FpML→CDM, 530/530) to produce the expected
CDM. No CDM logic rewritten — see [docs/mxml-cdm.md](../docs/mxml-cdm.md).

```
data/ground_truth/mxml-fpml/<cat>/fpml/*.xml  ──(FpmlToCdmConverter)──►  data/generated/mxml-cdm/<cat>/cdm/*.json
```

### Reproducibility (offline, without Murex Nexus)

The default build points at the Murex Nexus (unreachable off-VPN). Reproducible generation from the
local m2 cache:

```sh
# 1. runtime classpath from the cache (settings without mirror, hidden plugin 3.6.1)
mvn -o -s tmp/nomirror-settings.xml -gs tmp/nomirror-settings.xml \
    org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath -Dmdep.outputFile=tmp/cp.txt
# 2. FpML→CDM conversion in one JVM
java -cp "target/classes;$(cat tmp/cp.txt)" io.fpmlcdm.Cli -i <fpml-dir> -o <cdm-out>
```

Verified toolchain: Maven `C:\Maven\maven-3.6.0` (non-takari), JDK 21, `cdm-java:6.19.0` (m2 cache).

## Result

| | Folders | Note |
|---|---:|---|
| Source `OUT_*` | **347** | all with an `_expected.xml` FpML |
| **Built pairs (MXML + CDM)** | **239** | MXML input + CDM, **byte-identical** to the committed `_CDM.json` (0 drift) |
| Excluded — empty CDM (lifecycle) | 106 | FpML→CDM produces 0 `TradeState` (post-trade events) |
| Excluded — non-convertible FpML | 2 | `IRD_IRS_ZC_5-3_NOVATION_04/05_CCSwap_StepOut` (StepOut → empty CDM) |

**Integrity check:** the 239 generated CDM are **byte-for-byte identical** to the already-committed
`_CDM.json` → the chaining is exact and reproducible. (The 239th, `Straddle_INS_01`, has an internal stem
different from the folder name — recovered via stem-aware handling.)

### Dataset breakdown (`data/generated/mxml-cdm/<family-subfamily>/`)

| Category | Pairs | | Category | Pairs |
|---|---:|---|---|---:|
| ird-irs | 96 | | crd-crdi | 6 |
| ird-cs | 37 | | crd-crdio | 5 |
| ird-cf | 20 | | ird-oswp | 4 |
| crd-cds | 19 | | crd-scdo | 3 |
| ird-aswp | 10 | | crd-rtrs | 3 |
| curr-fxd | 10 | | crd-ocds | 3 |
| curr-opt | 7 | | ird-opt | 2 |
| ird-ois | 6 | | ird-swaption | 1 |
| ird-fra | 6 | | ird-bs | 1 |

**18 categories · 239 pairs · 712 files** (`mxml/` 239 · `cdm/` 239 · `ignored/` 234).

Structure: `data/generated/mxml-cdm/<cat>/{mxml,cdm,ignored}/<OUT_...>.{xml,json,xml}` — MXML input, expected CDM,
`ignored` mask for the `SemanticDiff`. The intermediate FpML stays in `data/ground_truth/mxml-fpml/`.

## The 106 excluded — lifecycle events

The FpML→CDM converter models **new trades**, not **post-trade events**. The 106
excluded FpML convert without error but into **0 TradeState**. Breakdown by keyword:

| Event | n | | Event | n |
|---|---:|---|---|---:|
| assignment | 45 | | unwind | 7 |
| fullTermination | 17 | | backtraded | 5 |
| partialTermination | 13 | | stepOut | 4 |
| stepIn | 12 | | zeroCoupon | 3 |
| cancel | 8 | | novation | 4 |

By family: ird-cs 50 · ird-irs 33 · ird-fra 7 · ird-cf 5 · ird-aswp 5 · ird-ois 3 · crd-crdi 2 · ird-oswp 1.

**To cover them** would require either extending FpML→CDM to CDM `BusinessEvent`/`WorkflowStep`, or direct
MXML→CDM mappers (cf. "Future option" in [docs/mxml-cdm.md](../docs/mxml-cdm.md)). Out of scope
of the current chaining.

## Note — restructuring `data/ground_truth/mxml-fpml/` (2026-06-23)

The 347 flat `OUT_*` folders (4 suffixed files each) were reorganized into the same scheme as
this compartment: `data/ground_truth/mxml-fpml/<cat>/{mxml,fpml,ignored}/<stem>.<ext>` (stem = folder name). The
`_CDM.json` is no longer there (it lives in `mxml-cdm`). Discarded noise: `desktop.ini`, `_ignored - Copy.xml`.
Zipped inputs (56 `.zip` of the lifecycle scenarios) kept in `mxml/`. Preservation verified
file-by-file (1278 source = 1037 migrated + 239 cdm→mxml-cdm + 2 noise).
