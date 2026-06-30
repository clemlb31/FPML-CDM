# MXML → CDM — Investigation Overview & Strategy

**Date:** 2026-06-26 · **Scope:** consolidated synthesis of the MXML→CDM work — dataset, semantic audit,
converter fixes, the FINOS reference comparison, and the recommended path toward the end goal (**CDM on a
blockchain**).

This is the single overview document. Deep dives are linked per section.

---

## 1. Context

The project converts between three financial-product representations: **FpML 5.x** (XML), **FINOS CDM 6.19**
(JSON) and **MXML** (Murex XML), organised in three bidirectional compartments
([README.md](../README.md)). The **end goal** is a faithful **MXML → CDM** conversion whose output is put
**on-chain**; FpML is only a scaffold/intermediate, not a runtime target.

Two converters exist for the FpML→CDM leg:
- **Ours** — `io.fpmlcdm`, 28 hand-written Java product mappers (XPath extraction).
- **FINOS** — `Ingest_FpmlConfirmationToTradeState`, a declarative synonym-based ingestion (generated),
  bundled in `cdm-java-6.19.0.jar`, plus the official post-processing pipeline (globalKey / qualify /
  reference-resolution). It runs in our environment against the existing m2 classpath (no extra build).

There is **no executable MXML→FpML mapper** (the Murex XSLT is a non-runnable spec); the `_expected.xml` FpML
files are reference data. So `MXML → CDM` today is the chain `MXML → FpML → CDM`.

---

## 2. The dataset (`data/`)

Reorganised into two compartments by trust level:
- **`data/ground_truth/`** — certain references: `fpml-cdm/` (565 FpML/CDM pairs, the 530/530 set) and
  `mxml-fpml/` (347 trades, MXML + expected FpML + ignored masks).
- **`data/generated/`** — `mxml-cdm/` (239 pairs), produced by our **uncertain** chaining and therefore to be
  validated, not trusted.

Build details: [mxml-cdm-dataset-239.md](mxml-cdm-dataset-239.md).

---

## 3. Semantic audit of our converter (LLM-as-judge)

A 20-agent LLM-judge + adversarial verification graded the 239 generated pairs (MXML vs CDM).
Full report: [mxml-cdm-llm-judge.md](mxml-cdm-llm-judge.md).

- **82% faithful** (PASS+MINOR); **37 confirmed material defects** (15.5%, after refuting 6 false positives).
- Losses concentrate at **FpML→CDM** (the `InterestRatePayout` pipeline drops *wrappers*: optionality,
  premiums, inflation params, known-amount) and at **MXML→FpML** (index→LIBOR distortion, counterparty
  collapse — not fixable here, no Java mapper).
- **Key caveat:** the committed `_CDM.json` are not byte-reproducible (they carry Rosetta `globalKey` hashes
  the CLI does not emit); "530/530" is a `SemanticDiff` measure, not byte-equality.

---

## 4. Converter fixes (our FpML→CDM)

5 families fixed and verified (offline javac + generated-vs-generated regression, 0 collateral regression).
Full report: [session-2026-06-24-fpml-cdm-fixes.md](session-2026-06-24-fpml-cdm-fixes.md).

| Family | Change |
|---|---|
| Optionality (swaps) | wired the already-written `cancelable`/`extendible` mappers (never called) |
| Optionality (cap, CDS swaption) | `CapFloorMapper` → terminationProvision; CDS strike `<price>` |
| Inflation index identity | preserve the index name even when no enum matches |
| Premiums cap/floor | iterate `<premium>` → `transferHistory` |
| Date sentinel | `DateMapper` rejects year < 1900 (`0002-11-30` Murex null-date) |
| CAPITAL payments | `<payment>` siblings of `<trade>` → `transferHistory` |

Still open (modelling decisions / new mapping): **known-amount** (ZC), **exotic FX barrier/digital**,
structured inflation params (MINOR). Roadmap: [TODO.md](../TODO.md).

---

## 5. FINOS vs ours — head-to-head (the pivotal comparison)

We ran the FINOS converter on the (date-cleaned) Murex FpML and judged its output with the same harness.
Full report: [finos-vs-ours-comparison.md](finos-vs-ours-comparison.md).

On the 191 common pairs: **ours 81% faithful vs FINOS 60%** — but for a **structural** reason, not mapping
quality:

- **FINOS fails on lifecycle/amendment events** — 57 FAIL, **91% are** Cancel/Restructure/Novation/amendment-
  wrapped trades that FINOS reduces to **empty party-only stubs** (it does not unwrap the envelope). Our
  converter digs into the wrapped trade → wins 73 head-to-head (54 of them = FINOS empty stub).
- **FINOS wins on clean new trades** (40 cases) — cleaner CDS, and it **captures what we dropped**: the
  known-amount, the `productQualifier` (Qualify step), and the **globalKey** content hashes.
- **Shared gaps**: extendible optionality (FINOS drops it too), FX barrier, structured inflation params.

**Conclusion: the two converters are complementary, neither is a drop-in replacement.**

---

## 6. ISO 20022 idea — assessed and set aside

Validating MXML→CDM via an ISO 20022 round-trip (MXML→CDM→ISO vs Murex MXML→ISO) was considered. CDM's ISO
synonyms target **`auth.030` (MiFIR transaction reporting)** — a **narrow regulatory subset** that does *not*
carry the economics we struggle with (optionality, known-amount, inflation). It would be **blind to exactly
the hard cases**, and there is no runnable CDM→ISO projection here (needs the separate DRR build). Since the
target is the **full CDM** (not regulatory reporting), ISO 20022 is the wrong oracle — at best a minor
cross-check on parties/notional.

---

## 7. Strategy toward the end goal (CDM on-chain)

### 7.1 Building a trustworthy MXML→CDM dataset
No single source yields trusted CDM (chain is lossy; FINOS fails on Murex lifecycle; LLM-direct is
unvalidated; hand-authoring is costly). Use a **tiered dataset + multi-method consensus**:
- **Gold** (~20–30 trades, 1–2 per family): human-verified anchor for acceptance/regression.
- **Silver**: bulk where ≥2 independent methods agree semantically (chain ∩ FINOS on clean FpML, + LLM-direct
  from MXML for the MXML-only enrichment) → auto-trusted.
- **To-review**: where methods disagree → the genuinely hard cases → human queue.
FpML stays a *fabrication scaffold*, not a runtime dependency.

### 7.2 On-chain requirements (raise the bar)
On-chain immutability makes the correctness bar high and adds concrete requirements our converter currently
misses:
- **globalKey** (content hash) → object identity / integrity — likely the on-chain ID.
- **Reference resolution** + **Qualify** (`productQualifier`) → consumers/contracts dispatch on it.
- **Canonical, deterministic serialization** → same trade ⇒ same bytes ⇒ same hash.

These are exactly the **FINOS post-processing steps** (in `rosetta-common`, already on our classpath). They
operate on a CDM `TradeState`, so they can be applied to **our** converter's output regardless of engine.

### 7.3 The recommended engine — hybrid
1. **Pre-unwrap the Murex lifecycle/amendment envelope** (extract the underlying new-trade FpML), **then** run
   FINOS → combine FINOS mapping quality with lifecycle coverage. *(Without this, ~half the Murex trades go
   on-chain empty — the FINOS trap.)*
2. **Graft the FINOS post-processing** (globalKey/qualify/reference-resolver) onto the output → canonical,
   hashed, on-chain-ready CDM. *(Without this, trades go on-chain with no identity hash — our trap.)*
You need **both**.

### 7.4 Validation strategy
For immutable on-chain data: **structural CDM validator (non-negotiable) + human-verified gold + multi-method
consensus**. The LLM-judge is a **discovery/triage** tool (it has a real false-positive rate — 6/43 here),
**not** an acceptance gate.

---

## 8. Artifact index

| Artifact | Path |
|---|---|
| This overview | `reports/mxml-cdm-overview-and-strategy.md` |
| Our-converter judge report | `reports/mxml-cdm-llm-judge.md` (+ `-verdicts.json`, `-adjudication.json`) |
| FINOS-vs-ours comparison | `reports/finos-vs-ours-comparison.md` (+ `mxml-cdm-finos-judge-verdicts.json`) |
| Converter fixes session | `reports/session-2026-06-24-fpml-cdm-fixes.md` |
| Dataset build | `reports/mxml-cdm-dataset-239.md` |
| Roadmap | `TODO.md` |
| FINOS converter (compiled) | `tmp/build_finos/` · cleaned FpML `tmp/fpml_clean/` · FINOS CDM `tmp/finos_cdm/` |
