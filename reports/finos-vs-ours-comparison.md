# FINOS (chaima/cdm) vs our converter — LLM-judge comparison

**Date:** 2026-06-26 · **Method:** same LLM-judge harness (MXML vs CDM), 20 sub-agents, on the **191 common
pairs** (where both produced a CDM). Verdicts: `reports/mxml-cdm-finos-judge-verdicts.json` (FINOS) ·
`reports/mxml-cdm-llm-judge-verdicts.json` (us).

## How the FINOS CDM was produced

1. **Cleaning the Murex FpML** (`tmp/clean_fpml.py`): removed the empty `<adjustedDate>` elements that crashed
   FINOS's strict XML mapper (4 removed, mostly the ZC).
2. **FINOS `FpmlToCdmBatchConverter`** (synonym ingestion + globalKey/qualify/reference post-processing) on
   the cleaned FpML: **291/347 succeeded** in 14 s (one JVM). The 56 failures = mostly `null TradeState`
   (FINOS recognizes lifecycle messages as WorkflowStep, not as new trades).

## Overall result (191 common pairs)

| | **Us** (pre-fixes) | **FINOS** |
|---|---:|---:|
| 🟢 PASS | 100 | 92 |
| 🟡 MINOR | 54 | 22 |
| 🟠 MAJOR | 34 | 20 |
| 🔴 **FAIL** | **3** | **57** |
| **Faithful %** (PASS+MINOR) | **81 %** | **60 %** |

**Head-to-head:** we win on **73**, FINOS wins on **40**, tie on **78**.

→ **On Murex data, our converter is overall more faithful** — but for a structural reason,
not because our product mapping is better.

## Why: the two are **complementary**, not competitors

### FINOS fails on lifecycle events (its big weakness here)
**57 FAIL, of which 91 % (52) are amendments/lifecycle**: CancelAndReIssue, Restructure, Novation, StepIn/Out,
trades wrapped in `<amendment>`/`<requestConfirmation>`/`<contractEvent>`. FINOS **does not unwrap the envelope** →
emits an **empty CDM (parties only)**. Our converter, by contrast, reaches into the trade inside → 54 of our
73 wins are exactly that (FINOS=empty FAIL vs us=real economics).

### FINOS wins on clean new trades (its strength)
**40 wins**, mostly on product-mapping quality: `crd-cds` (6 — cleaner CDS, including the sentinel
`fixedRecoveryRate=0` that our judge had misread), `ird-irs` (8), `ird-cf` (5), `ird-cs` (5). Often a notch
above (ours=MINOR → FINOS=PASS). **And FINOS captures things we dropped**: the **12M known-amount** of the
ZC, the **`productQualifier`** (Qualify step), the **globalKey** (content hash).

### Shared gaps (neither one)
Extendable/callable optionality (FINOS **also drops** it in `fpml→cdm`), FX barrier/digital, structured inflation
params (lag/interpolation), OIS cut-off/averaging. → These are limits of the CDM `InterestRatePayout` pipeline,
common to both.

## ⚠️ Comparison caveat

"Us" = the **pre-fixes** version (the `data/generated/mxml-cdm` dataset was not regenerated). My 5
families of fixes (optionality, known-amount via — no, that's still to do —, premiums, inflation-identity, dates,
CAPITAL) **close part of the 40 cases where FINOS wins** (notably ird-cf premiums). Post-fix, our lead
would widen. But the FINOS advantages **globalKey + Qualify + known-amount + CDS-cleanliness** remain.

## Strategic verdict

**Neither is a "drop-in" replacement for the other.**

| Dimension | Winner |
|---|---|
| Clean new trades (mapping quality) | **FINOS** |
| Known-amount, `productQualifier`, globalKey (on-chain!) | **FINOS** |
| Murex lifecycle events (the bulk of the dataset) | **Us** |
| Robustness on dirty Murex data (empty dates…) | **Us** (FINOS requires pre-cleaning) |

### The path that combines both strengths
1. **Pre-unwrap the Murex lifecycle envelope** (extract the underlying trade from the `<amendment>`/`<contractEvent>`)
   **then** run FINOS on it → FINOS quality **+** lifecycle coverage. This is probably the best engine.
2. **Or**: keep our converter (which handles lifecycle) and **graft on the FINOS post-processing**
   (globalKey/qualify/reference-resolver — already on our classpath) → indispensable for **on-chain** (identity
   hash, canonical form). Combinable with option 1.

→ For your **on-chain CDM** target, the FINOS post-processing is not optional, and neither is the lifecycle
unwrapping (otherwise half the Murex trades end up empty on the chain).

## Artifacts
- FINOS verdicts: `reports/mxml-cdm-finos-judge-verdicts.json` · generated FINOS CDM: `tmp/finos_cdm/<cat>/*.json`
- Cleaned FpML: `tmp/fpml_clean/` · FINOS batch: `tmp/build_finos` + `tmp/finos_batch.log`
