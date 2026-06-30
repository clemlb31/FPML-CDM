# Session 2026-06-24 — FpML→CDM fixes (following the LLM-judge audit)

**Context:** following the report [mxml-cdm-llm-judge.md](mxml-cdm-llm-judge.md) (37 confirmed material defects),
request: *"do all the families and loop until done"*. Autonomous work. This file = recap for resumption.

**Scope:** only **FpML→CDM** is editable/testable (MXML→FpML has no Java code — reference XSLT).
The MXML→FpML defects (index→LIBOR, counterparty merge, CDO tranche, TRS underlier) are **out of scope**.

---

## 1. What was fixed — 5 families, all verified

| Family | File(s) | Change | Fixed judge cases |
|---|---|---|---|
| **Optionality — swaps** | `common/TerminationProvisionMapper.java` | `map()` only read `earlyTerminationProvision`; wired up `cancelable`/`extendible` (methods already written, never called) | INS_Callable_01/02, INS_Extendable_01/02/03 |
| **Optionality — cap** | `products/CapFloorMapper.java` | calls `TerminationProvisionMapper.map(capFloor)` | Bermudan_01 (Bermuda style, **36 dates**, cashSettlement) |
| **Optionality — CDS swaption** | `products/CreditDefaultSwapOptionMapper.java` | handles the `<strike><price>` strike (in addition to `spread`/`ref`) | INS_12 (strike 1.0) |
| **Inflation — identity** | `common/QuantityMapper.java` | `addIdentifier` of the index name (the enum alone was null → empty InflationIndex), symmetric with floating | SWAP_INS_01, Restructure_V2 (+ 12 ASWP/infl. as a bonus) |
| **Cap/floor premiums** | `products/CapFloorMapper.java`, `products/FxOptionMapper.java` | iterates the `<premium>` → `transferHistory`; `buildPremiumTransfer` helper enriched (direct paymentDate) | FinalStub, PeriodicPremium (5 flows), MultiPremium (3), CF_4-3_INS_01 |
| **Sentinel date bug** | `common/DateMapper.java` | `parse` rejects years < 1900 (Murex sentinel `0002-11-30` present in the source FpML) | 10 files cleaned (FRA, lifecycle…) |
| **CAPITAL payments** | `products/SwapMapper.java` | captures the `<payment>` siblings of `<trade>` (in `<amendment>`) → `transferHistory` | Restructure-PAYG_01 (41509.99 €), Restructure_03 (1606.87 €) |

**~14-15 confirmed MAJOR/FAIL defects** addressed (out of ~25 fixable on the FpML→CDM side), + several MINOR.

## 2. Verification (method)

Env-locked build (Murex Nexus) → **offline compilation with `javac`** from the m2 cache, generation on the
565 FpML of `data/ground_truth/fpml-cdm`, **generated-vs-generated regression** (the Rosetta `globalKey` absent on both sides —
see note below). For each family: green compile + judge case validated directly + regression.

**Final cumulative regression (all families):** `data/ground_truth/fpml-cdm` **545/563 identical**, **18 changed — all
intentional**: 14 inflation (gain the identifier) + 4 optionality (cancel/extend swaps gain
`terminationProvision`). **0 collateral regression** (FX, vanilla cap, CDS, etc. unchanged).

> ⚠️ The committed `data/ground_truth/fpml-cdm/cdm/*.json` carry computed `globalKey` values that the CLI does not set → they are
> **not** byte-reproducible. The "530/530" is measured by `SemanticDiff` (globalKey-tolerant). To judge
> a regression: compare **generated-vs-generated**, never generated-vs-committed-reference.

## 3. Working-tree state (nothing is committed)

**Edited this session (7, verified, safe to commit):**
`TerminationProvisionMapper` (+6/−2) · `CapFloorMapper` (+12) · `CreditDefaultSwapOptionMapper` (+12) ·
`QuantityMapper` (+8) · `FxOptionMapper` (+10) · `DateMapper` (sentinel) · `SwapMapper` (+8).

**Pre-existing (untouched this session, already re-validated output-neutral on 563/563):**
`InterestRatePayoutMapper`, `CreditDefaultSwapMapper`, `SecurityLendingMapper`, `DateMapper` (base).

**Compilation blocker (to resolve):** `products/CommodityMetadataOnlyMapper.java` — WT rewrite (+225/−71)
that **does not compile** against the public CDM 6.19. For a green build: shelve it (`git stash` of this single file,
reverts to the output-neutral HEAD version) **or** port it to the public API. *(My offline builds exclude this
file by taking the HEAD version.)* Also: `cdmtofpml/` (untracked, CDM→FpML direction) does not compile — out of scope.

## 4. Not done (and why) — decisions to make with you

- **Known-amount (ZC leg `knownAmountSchedule`, e.g. INS_02 12 M €)**: requires a **CDM modeling
  decision** (a known-amount is not a notional). Options in [TODO.md](../TODO.md). Scope 3 cases.
  Not implemented so as not to freeze an uncertain structural choice autonomously.
- **Exotic products (FX barrier/digital, TRS on bond, FX range)**: **new mapping** (not a missing
  wiring) — e.g. `FxOptionMapper` has no handling of `<barrier>`. `OptionFeature` modeling to validate.
- **Structured inflation params** (interpolationMethod/lag/initialIndexLevel): **MINOR** (downgraded — the economics
  survives in the cashflows). To do only if a downstream consumer recomputes from the structured terms.
- **All of MXML→FpML**: no Java mapper → not editable (future port).

## 5. Proposed next steps (for your feedback)

1. **Review + commit** the 7 fixes (dedicated branch). Clean diff, each verified + regression at 0.
2. **Shelve `CommodityMetadataOnlyMapper`** (or port it) for a green `mvn`/Eclipse.
3. **Regenerate `data/generated/mxml-cdm`** to reflect the fixed converter (optional, I did not do it so as not
   to mutate the judge baseline without review). Command: for each `<cat>`,
   `java -cp "tmp/build_fix;$(cat tmp/cp.txt)" io.fpmlcdm.Cli -i data/ground_truth/mxml-fpml/<cat>/fpml -o tmp/regen/<cat>`
   then copy `tmp/regen/<cat>/*.json` → `data/generated/mxml-cdm/<cat>/cdm/`.
4. **Decide on the known-amount** (modeling) then implement it.
5. Possibly tackle the exotic FX barrier (new `OptionFeature` mapping).

## Offline build repro

```sh
# classpath from the m2 cache (without Murex mirror)
mvn -o -s tmp/nomirror-settings.xml -gs tmp/nomirror-settings.xml \
    org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath -Dmdep.outputFile=tmp/cp.txt
# compile FpML→CDM (excludes cdmtofpml ; commodity = HEAD version via tmp/srcs_fpmlcdm.txt)
javac -encoding UTF-8 -d tmp/build_fix -cp "$(cat tmp/cp.txt)" @tmp/srcs_fpmlcdm.txt
# generate + validate
java -cp "tmp/build_fix;$(cat tmp/cp.txt)" io.fpmlcdm.Cli -i data/ground_truth/fpml-cdm -o tmp/out_fix
```
Toolchain: Maven `C:\Maven\maven-3.6.0` (non-takari), JDK 21, `cdm-java:6.19.0` (m2 cache).
