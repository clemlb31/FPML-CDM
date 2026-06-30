# LLM-as-judge report — MXML → CDM translation fidelity

**Date:** 2026-06-24 · **Dataset judged:** `data/generated/mxml-cdm/` (239 MXML→CDM pairs) · **Tie-breaker:** `data/ground_truth/mxml-fpml/` (intermediate FpML)
**Method:** 20 judge sub-agents (LLM), 1 pass, ~12 pairs each · **Raw verdicts:** `tmp/judge/out/out_*.json`
**Adversarial verification:** 8 refuters over the 43 MAJOR/FAIL → **37 confirmed, 6 downgraded** (see §1bis)

---

## 1. Executive summary

239 `(MXML input → CDM output)` pairs were judged on **economic fidelity**: does the CDM preserve
the economic content of the original MXML? The CDM is produced by chaining **MXML→FpML→CDM**; the FpML was used
to **localize** each loss (at the MXML→FpML or FpML→CDM stage).

| Verdict | n | % | Meaning |
|---|--:|--:|---|
| 🟢 **PASS** | 133 | 55.6 % | all material economics present |
| 🟡 **MINOR** | 63 | 26.4 % | cosmetic discrepancies only (labels, party anonymization) |
| 🟠 **MAJOR** | 40 | 16.7 % | one material economic element missing or distorted |
| 🔴 **FAIL** | 3 | 1.3 % | wrong product or unusable CDM |

**82 % of pairs are economically faithful** (PASS + MINOR), mean score **84/100**. The **vanilla core**
(fixed-float IRS, basis swaps, single-name CDS, OIS) is well translated: notionals, dates, fixed rates
(correct %→decimal conversion, e.g. 2.8564 → 0.028564), frequencies, day-count, payer/receiver direction.

**The 18 % of defects (MAJOR+FAIL) concentrate on non-vanilla products**: optionality (callable/
extendable/swaption), inflation, exotic FX (digital/barrier), bond total-return swaps, RFR
indices, CDO tranches.

### 🔑 Central insight — where information is lost

| Loss locus | n pairs |
|---|--:|
| No loss | 137 |
| **FpML → CDM** | **57** |
| **MXML → FpML** | **30** |
| Both | 14 |
| Indeterminate | 1 |

> The "byte-identical vs reference `_CDM.json`" verification (report
> [mxml-cdm-dataset-239.md](mxml-cdm-dataset-239.md)) proved **reproducibility**, **not fidelity**:
> the reference itself loses these economics. **The majority of losses are at the FpML→CDM stage (57 pairs)** —
> the "mature 530/530" converter emits the vanilla swap legs but **discards the envelopes**
> (optionality, premium, inflation parameters, known-amount). The MXML→FpML stage (30) mainly introduces
> **index distortions** (RFR → LIBOR) and **counterparty collapses**.

---

## 1bis. Adversarial verification of the 43 MAJOR/FAIL

Each MAJOR/FAIL case was submitted to an **independent refuter** (8 sub-agents) tasked with *proving that
the economics are in fact preserved* (exhaustive search for the allegedly missing element — `transferHistory`,
`additionalPayment`, `meta`, precomputed cashflows… — and materiality test).

**Result: 37 confirmed · 6 downgraded to MINOR · 0 escalated.** Reconciled distribution:

| Verdict | initial | **reconciled** |
|---|--:|--:|
| PASS | 133 | 133 |
| MINOR | 63 | **69** |
| MAJOR | 40 | **34** |
| FAIL | 3 | **3** |
| **Material defects (MAJOR+FAIL)** | 43 (18.0 %) | **37 (15.5 %)** |

### The 6 false positives / immaterial cases downgraded
- `CRD_CDS_5-3_INS_07_NoRecovery` — `fixedRecoveryRate=0` is a **Murex sentinel** ("no fixed recovery" →
  floating recovery 100−R / auction), not a 0 % feature; the CDM physical-settlement is faithful.
- `IRD_ASWP_5-3_Inflation_Interpolated / _NonInterpolated / _Interp_02` — the structured inflation flags
  (interpolationMethod/initialIndexLevel/lag) are dropped **but the economics survive in the precomputed
  cashflows** (`observedRate`, observation weights 19/9, 17/14, fixing dates) with `cashflowsMatchParameters=true`.
- `IRD_CF_4-3_INS_01` — premium **100 EUR** on a 1,000,000 EUR cap = de minimis (0.01 %).
- `IRD_IRS_5-3_Restructure_03` — CAPITAL payment **1,606 EUR** on 100,000,000 EUR = 0.0016 %, immaterial.

> ⚠️ Inflation caveat: the structured flags **are** indeed absent from the CDM; they are only "recoverable"
> because the cashflows are precomputed (`cashflowsMatchParameters=true`). If a downstream consumer **recomputes**
> from the structured terms rather than the cashflows, the loss becomes material again. Family B is therefore
> "MINOR conditionally", not harmless.

**37 confirmed defects** remain (appendix A, the 6 downgraded ones are marked ⬇). Detail per case:
[mxml-cdm-llm-judge-adjudication.json](mxml-cdm-llm-judge-adjudication.json).

---

## 2. Methodology

- **Unit judged:** each pair `data/generated/mxml-cdm/<cat>/{mxml,cdm}/<base>.{xml,json}`, with the FpML
  `data/ground_truth/mxml-fpml/<cat>/fpml/<base>.xml` serving as arbiter.
- **Per-pair procedure:** full read of the CDM (small); **targeted** read of the MXML (large — median
  118 KB, max 1.6 MB — read via `Grep`/slices over the economic core); on discrepancy, read of the FpML to
  assign the `loss_locus`.
- **Grid (10 dimensions):** product_type · parties/payer-receiver · notional/quantity · rate/index/spread ·
  fixed_rate · dates · frequencies · daycount/BDC · product_specific (strike/premium/barrier/style/zero-coupon/
  amortization) · information_loss.
- **Scale:** PASS / MINOR / MAJOR / FAIL (see §1).
- **Coverage:** 239/239 pairs, **single pass** (user choice — no second adversarial opinion).
  The judge consulted the FpML on **124/239** pairs.

⚠️ **Limitations** (see §7): single-pass LLM judge, MXML read via targeted sampling, heuristic scores.
The 43 MAJOR/FAIL cases warrant human review before action.

---

## 3. Scorecard by product family

| Category | n | PASS | MINOR | MAJOR | FAIL | Faithful %* | Score |
|---|--:|--:|--:|--:|--:|--:|--:|
| ird-irs | 96 | 65 | 19 | 12 | 0 | 88 | 88 |
| ird-cs | 37 | 26 | 8 | 3 | 0 | 92 | 88 |
| ird-cf (cap/floor) | 20 | 7 | 8 | 5 | 0 | 75 | 82 |
| crd-cds | 19 | 6 | 11 | 2 | 0 | 89 | 86 |
| curr-fxd | 10 | 4 | 5 | 1 | 0 | 90 | 83 |
| ird-aswp (asset/infl.) | 10 | 4 | 1 | 5 | 0 | **50** | 75 |
| curr-opt (FX opt.) | 7 | 3 | 0 | 2 | 2 | **43** | **57** |
| crd-crdi | 6 | 4 | 2 | 0 | 0 | 100 | 91 |
| ird-fra | 6 | 3 | 2 | 1 | 0 | 83 | 85 |
| ird-ois | 6 | 4 | 0 | 2 | 0 | 67 | 78 |
| crd-crdio | 5 | 0 | 4 | 1 | 0 | 80 | 79 |
| ird-oswp | 4 | 3 | 0 | 1 | 0 | 75 | 80 |
| crd-ocds | 3 | 0 | 3 | 0 | 0 | 100 | 88 |
| crd-rtrs (TRS) | 3 | 0 | 0 | 2 | 1 | **0** | **35** |
| crd-scdo (CDO) | 3 | 1 | 0 | 2 | 0 | **33** | 66 |
| ird-opt | 2 | 2 | 0 | 0 | 0 | 100 | 93 |
| ird-bs | 1 | 1 | 0 | 0 | 0 | 100 | 95 |
| ird-swaption | 1 | 0 | 0 | 1 | 0 | 0 | 60 |
| **TOTAL** | **239** | **133** | **63** | **40** | **3** | **82** | **84** |

*Faithful % = (PASS+MINOR)/n. The families in bold (**crd-rtrs, curr-opt, crd-scdo, ird-aswp**) are the most
degraded; the large vanilla families (ird-irs, ird-cs) are at 88-92 %.

---

## 4. Systematic defect families

### A. Optionality / exercise provisions lost — *FpML→CDM* — ~10 pairs, MAJOR
The CDM pipeline `InterestRatePayout` emits only the legs of the underlying swap and **ignores the option
envelopes** (`terminationProvision` / `cancelableProvision` / `extendibleProvision` / swaption exercise).
- **Callable IRS**: `INS_Callable_01` (cancelableProvision europeanExercise), `INS_Callable_02`.
- **Extendable IRS**: `INS_Extendable_01/02/03` (`extendibleProvision` expiry 2017-05-20).
- **Bermudan-cancellable inflation cap**: `IRD_CF_5-3_Bermudan_01` (35 bermudaExercise dates + cashSettlement).
- **CDS swaption**: `CRD_CDS_5-3_INS_12` (strike FpML=1.0 → CDM empty `exerciseTerms {}`).
→ The CDM contains only the 2 vanilla legs; the callability/extensibility is **entirely lost**.

### B. Inflation leg parameters lost — *FpML→CDM* — ~9 pairs, MAJOR
`InflationRateSpecification` keeps only the `rateOption` and **discards** `interpolationMethod`, `initialIndexLevel`,
`inflationLag`, `multiplier`, `indexSource` — and sometimes **the index identifier itself**.
- `IRD_ASWP_5-3_Inflation_Interpolated / _NonInterpolated / InflationSwapCustom / Restructure / Inflation_Interp_02`.
- `IRD_IRS_5-3_SWAP_INS_01` and `IRD_IRS_Restructure_V2`: **the CPI index name is `null`** in the CDM
  → one can no longer tell which CPI (US CPI-U vs EU HICP).

### C. Option premiums lost (cap/floor) — *FpML→CDM* — ~6 pairs, MAJOR
The CDM of a cap/floor emits only the `InterestRatePayout`; **no `OptionPayout`/premium/`additionalPayment`**.
- `IRD_CF_5-3_PeriodicPremium` (5 flows ~25,000 EUR), `..._CancelAndReissue_MultiPremium` (3 premiums),
  `..._FinalStub` (10,000 EUR), `IRD_CF_4-3_INS_01` (100 EUR).

### D. Floating-index family distortion → LIBOR — *MXML→FpML* — ~8 pairs, MAJOR ⚠️ *the most dangerous*
The MXML→FpML mapper **replaces non-LIBOR / RFR indices with LIBOR** (and overnight-compounded with
3M LIBOR), silently changing the rate benchmark.
- **SARON / SOFR** → LIBOR: `IRD_CS_5-3_INS_11` (xccy CHF/USD).
- **Fed-Funds-Average** → LIBOR: `IRD_CS_5-3_BusinessDayConvention`, `IRD_OIS_CutOff_INS_01`, `IRD_OIS_Average_INS_01`.
- **CDOR** → CAD-LIBOR: `IRD_CS_5-3_CancelAndReissue_01`.
- **KLIBOR (MY1)** → USD-LIBOR-BBA: `IRD_IRS_5-3_INS_07`, `INS_08` (quanto).
→ Risk: a SOFR/SARON/Fed-Funds-indexed swap becomes a LIBOR swap — **invisible economic error**.

### E. Known-amount / CAPITAL payments lost — *FpML→CDM* — ~5 pairs
- Zero-coupon: `IRD_IRS_ZC_5-3_INS_02` — `knownAmountSchedule` **12,000,000 EUR** absent from the CDM (the fixed
  leg has neither rate nor amount). *(calibration case, see §6)*
- `<payment paymentType=CAPITAL>`: `IRD_IRS_5-3_Restructure_03` (1,606 EUR), `Restructure-PAYG_01` (41,510 EUR).

### F. Counterparty collapse / direction lost — *MXML→FpML* — ~7 pairs, MAJOR
Distinct from benign anonymization: here **both** counterparties are merged into a single one
(`payer == receiver`), making the **direction of the trade unusable**.
- `CRD_RTRS_*` (all 3), `CRD_CRDIO_5-3_INS_02`, `IRD_OSWP_5-3_CustomizedFlows`, `CRD_CDS_5-3_INS_12`, `IRD_SWAPTION_INS_01`.
- *Not to be confused with* `→ BARCLAYS` anonymization with distinct roles (≈ 50+ pairs, **MINOR**).

### G. Product / underlier destruction (the worst) — MAJOR/FAIL
- **Bond TRS → EquitySwap on a fake index**: `CRD_RTRS_*` — the bond `FTEL 3.75 03/12` becomes
  an index `EUR-Annual Swap Rate-3 Month`, taxonomy `EquitySwap_Index` **wrong**. (1 FAIL, 2 MAJOR)
- **FX digital (one/no-touch rebate) → empty OptionPayout**: `CURR_OPT_RBT_INS_01/02` — trigger, payout,
  currency pair **gone**. (2 FAIL)
- **FX barriers (single & double KO/KI) → barrier dropped**: `CURR_OPT_BAR_INS_01`, `BAR2_INS_01`
  (vanilla preserved, barrier lost).
- **FX range/window forward → empty payout**: `CURR_FXD_FXD_GEN_PDT_INS_01` (JPY notional, rate, settlement dropped).
- **CDO tranche 1%-3% → 0%-3%**: `CRD_SCDO_*` — alters the loss exposure (attach distorted MXML→FpML).
- **Interpolated double-tenor FRA → single-tenor**: `IRD_FRA_5-3_INS_02_doubleIndexTenor` (3M/6M → 3M only).

### H. Data corruptions (few but outright bugs)
- `adjustedDate "0002-11-30"` on small cash transfers (`IRD_FRA_*`, pairs with `transferHistory`).
- First payment date inconsistent with the effective date (`CRD_SCDO_*`).

### I. Day-count / convention mislabels — *MXML→FpML* — mostly MINOR
- `ACT/365.FIXED` → `ACT/365L`; business-day-convention labels.

---

## 5. The 3 FAILs + most serious cases

| Verdict | Score | Pair | Issue |
|---|--:|---|---|
| 🔴 FAIL | 12 | `CURR_OPT_RBT_INS_02` | FX no-touch digital: trigger 1.13 + payout 329,000 USD dropped, premium=0, empty product |
| 🔴 FAIL | 20 | `CURR_OPT_RBT_INS_01` | FX one-touch: FpML encodes the digital correctly (trigger 1.1, 100,000 USD); CDM = **empty** `OptionPayout` |
| 🔴 FAIL | 22 | `CRD_RTRS_5-3_Restructure_01` | Bond TRS: bond underlier lost, wrong EquitySwap taxonomy, parties merged |
| 🟠 MAJOR | 28 | `IRD_IRS_5-3_INS_Callable_02` | rate **1.20 %→0.50 %** and **EURIBOR 3M→1M** (MXML→FpML) + extension dropped (FpML→CDM) |

The full list of the **43 MAJOR/FAIL cases** is in **appendix A**.

---

## 6. What works well (82 %)

- **Vanilla fixed-float IRS, basis swaps, single-name CDS, OIS, simple FRAs**: notionals, currencies, dates
  (trade/effective/maturity/roll), **fixed rates with correct %→decimal conversion**, frequencies
  (calc/payment/reset), day-count, conventions, payer/receiver direction — faithfully preserved.
- **Schedules**: amortizing notionals, step-up, initial/final interpolated stubs correctly carried over.
- **Break clauses** (mandatory/optional Bermuda) traced down to the MXML exercise dates.
- **Party anonymization** (`→ BARCLAYS`/`party1`): cosmetic as long as the **roles** remain distinct (MINOR).
- **Calibration case** `IRD_IRS_ZC_5-3_INS_02`: all the floating leg (10M +10 bp EURIBOR 6M, reset −2 BD,
  ACT/360, MODFOL) is exact; **only** the 12M `knownAmount` of the ZC fixed leg is lost (FpML→CDM, family E).

---

## 7. Limitations of the judgment

- **Single-pass** (user choice): no second adversarial opinion; an LLM judge may err on a given case.
- **MXML read via targeted sampling** (files up to 1.6 MB): an economic element outside the searched sections
  may have been missed.
- **Heuristic 0-100 scores**, not calibrated across agents; to be read as an order of magnitude.
- **Recommendation:** human review of the **43 MAJOR/FAIL** (appendix A) before any engineering decision; if
  needed, rerun a targeted adversarial pass on families D, F and G.

---

## 8. Recommendations (prioritized by impact × frequency)

**P0 — fix MXML→FpML (silent value errors)**
1. **Index mapping**: stop collapsing non-LIBOR/RFR indices onto LIBOR (SARON, SOFR, Fed-Funds,
   CDOR, KLIBOR, EONIA). Family D — ~8 pairs here, probably many more in production. *The most dangerous defect.*
2. **Counterparties & direction**: preserve both counterparties and the direction; collapsing into a single
   `BARCLAYS` makes ~7 trades directionally unusable (family F).

**P1 — close FpML→CDM (structural envelope losses)**
3. **Optionality**: model `terminationProvision`/cancelable/extendible/swaption exercise (family A — largest MAJOR block).
4. **Inflation**: carry `interpolationMethod`/`lag`/`initialIndexLevel`/`multiplier`/**index identifier** into `InflationRateSpecification` (family B).
5. **Premiums**: map cap/floor & swaption premiums to `OptionPayout`/`additionalPayment` (family C).

**P2**
6. **Known-amount / CAPITAL payments**: map `knownAmountSchedule` and `<payment paymentType=CAPITAL>` (family E).
7. **Exotic products**: dedicated mappers for FX digital/barrier, bond TRS, CDO tranches
   (today `genericProduct`/metadata-only → empty payouts; families G).

**P3**
8. **Data bugs**: `adjustedDate 0002-11-30`; CDO first payment date (family H).

> Link to the roadmap: MXML→FpML is an **XSLT port in progress** ([docs/mxml-fpml.md](../docs/mxml-fpml.md)) —
> families D/F/G/I are expected there. Families A/B/C/E (FpML→CDM) are more surprising given the
> "530/530": that score measures **structural validity + the diff vs reference**, not **economic
> completeness** — hence the value of this judgment.

---

## Appendix A — 43 MAJOR / FAIL cases (worst to least serious)

V = verdict · Sc = score · Locus = loss stage. ⬇ = **downgraded to MINOR** by the adversarial verification
(§1bis): `CRD_CDS_5-3_INS_07_NoRecovery`, `IRD_ASWP_5-3_Inflation_Interpolated`, `IRD_ASWP_5-3_Inflation_NonInterpolated`,
`IRD_ASWP_5-3_Inflation_Interp_02`, `IRD_CF_4-3_INS_01`, `IRD_IRS_5-3_Restructure_03` → **37 confirmed defects out of 43**.

| V | Sc | Locus | Pair | Product | Main loss |
|---|--:|---|---|---|---|
| FAIL | 12 | both | CURR_OPT_RBT_INS_02 | FX No-Touch Digital/Rebate (USD/AUD) | trigger 1.13 + payout 329k USD dropped; empty product |
| FAIL | 20 | fpml_to_cdm | CURR_OPT_RBT_INS_01 | FX One-Touch Digital/Rebate (USD/EUR) | FpML OK (trigger 1.1, 100k USD); empty CDM OptionPayout |
| FAIL | 22 | both | CRD_RTRS_5-3_Restructure_01 | Bond Total Return Swap | bond underlier lost; wrong EquitySwap taxonomy; parties merged |
| MAJOR | 28 | both | IRD_IRS_5-3_INS_Callable_02 | Callable/Extendable IRS EUR | 1.20%→0.50%, EURIBOR 3M→1M; extension dropped |
| MAJOR | 30 | mxml_to_fpml | CRD_RTRS_5-3_CancelAndReIssue_01 | Bond TRS | bond underlier→EquityIndex; payer=receiver=party1 |
| MAJOR | 35 | both | CURR_FXD_FXD_GEN_PDT_INS_01 | FX window/range forward AUD/JPY | JPY notional, rate, settlement, window dropped |
| MAJOR | 38 | fpml_to_cdm | IRD_CF_5-3_Bermudan_01 | Bermudan-cancellable inflation cap | 35-date bermudaExercise + cashSettlement entirely dropped |
| MAJOR | 45 | mxml_to_fpml | CRD_CRDIO_5-3_INS_02 | Index CDS Swaption | parties merged into BARCLAYS; restructuring emptied |
| MAJOR | 45 | fpml_to_cdm | CURR_OPT_BAR2_INS_01 | FX Double-Barrier KO (USD/JPY) | double barrier 102/115 dropped |
| MAJOR | 45 | mxml_to_fpml | IRD_CS_5-3_BusinessDayConvention | USD basis (Fed-Funds-Avg vs LIBOR 3M) | Fed-Funds→LIBOR (2 LIBOR legs) |
| MAJOR | 45 | fpml_to_cdm | IRD_IRS_5-3_INS_Callable_01 | Callable IRS (2.0329% vs EURIBOR 1Y) | callable option (strike 2.03289) dropped |
| MAJOR | 45 | fpml_to_cdm | IRD_IRS_ZC_5-3_INS_02 | Zero-coupon swap EUR | knownAmount 12,000,000 EUR fixed leg dropped |
| MAJOR | 47 | both | IRD_OIS_CutOff_INS_01 | Basis Fed-Funds avg + cut-off | Fed-Funds→LIBOR; rateCutOff + averaging dropped |
| MAJOR | 48 | fpml_to_cdm | CURR_OPT_BAR_INS_01 | FX Single-Barrier Down-and-In (EUR/USD) | barrier 0.8975 dropped |
| MAJOR | 50 | mxml_to_fpml | IRD_CS_5-3_CancelAndReissue_01 | Xccy fixed/float JPY/CAD | CDOR 3M→CAD-LIBOR-BBA |
| MAJOR | 50 | both | IRD_OIS_Average_INS_01 | Basis Fed-Funds avg vs LIBOR | Fed-Funds→LIBOR; averaging dropped |
| MAJOR | 52 | mxml_to_fpml | CRD_RTRS_5-3_INS_01 | Bond TRS | bond underlier→index; wrong EquitySwap taxonomy |
| MAJOR | 52 | mxml_to_fpml | CRD_SCDO_5-3_Restructure_01 | Synthetic CDO tranche (iTraxx) | attach 1%-3%→0%-3%; inconsistent first payment date |
| MAJOR | 55 | fpml_to_cdm | IRD_CF_5-3_PeriodicPremium | Cap EUR-EURIBOR 1Y, periodic premium | 5 premium flows (~25k EUR) dropped |
| MAJOR | 55 | mxml_to_fpml | IRD_CS_5-3_INS_11 | Xccy basis CHF/USD | SARON & SOFR compounded → LIBOR 3M |
| MAJOR | 55 | fpml_to_cdm | IRD_FRA_5-3_INS_02_doubleIndexTenor | FRA EUR EURIBOR interpolated | interpolation 3M/6M → 3M only |
| MAJOR | 55 | mxml_to_fpml | IRD_IRS_5-3_INS_07 | Quanto IRS (KLIBOR vs 3.5%) | MY1-KLIBOR 3M → USD-LIBOR-BBA |
| MAJOR | 55 | mxml_to_fpml | IRD_IRS_5-3_INS_08 | Quanto IRS FX-linked | MY1-KLIBOR 3M → USD-LIBOR-BBA |
| MAJOR | 55 | fpml_to_cdm | IRD_IRS_5-3_INS_Extendable_01 | Extendable IRS EUR | extendibleProvision dropped |
| MAJOR | 55 | fpml_to_cdm | IRD_IRS_5-3_INS_Extendable_02 | Extendable IRS EUR | extendibleProvision dropped |
| MAJOR | 55 | fpml_to_cdm | IRD_IRS_5-3_INS_Extendable_03 | Extendable IRS EUR | extendibleProvision dropped |
| MAJOR | 55 | both | IRD_IRS_Restructure_V2 | ZC inflation swap US CPI-U | FpML mislabels index; CDM index unnamed |
| MAJOR | 55 | mxml_to_fpml | IRD_OSWP_5-3_CustomizedFlows | Step-up swaption GBP | roles merged into BARCLAYS (direction lost) |
| MAJOR | 58 | both | CRD_CDS_5-3_INS_12 | CreditDefaultSwaption | strike 1.0 → exerciseTerms {}; parties merged |
| MAJOR | 58 | mxml_to_fpml | CRD_SCDO_5-3_CancelAndReIssue_01 | Synthetic CDO tranche (iTraxx) | attach 1%-3%→0%-3% |
| MAJOR | 60 | mxml_to_fpml | CRD_CDS_5-3_INS_07_NoRecovery | Zero-recovery single-name CDS | fixedRecoveryRate=0 dropped |
| MAJOR | 60 | fpml_to_cdm | IRD_ASWP_5-3_Inflation_Interpolated | Inflation YoY EUR-EXT-CPI | interpolationMethod/initialIndexLevel/lag dropped |
| MAJOR | 60 | fpml_to_cdm | IRD_ASWP_5-3_Inflation_NonInterpolated | Inflation YoY EUR-EXT-CPI | interpolationMethod/initialIndexLevel/lag dropped |
| MAJOR | 60 | mxml_to_fpml | IRD_SWAPTION_INS_01 | Bermudan swaption EUR | underlying swap direction broken |
| MAJOR | 62 | fpml_to_cdm | IRD_ASWP_5-3_InflationSwapCustom | Inflation YoY (fixed 1.25%) | multiplier 0.0128/lag/interpolation/initialIndexLevel dropped |
| MAJOR | 62 | fpml_to_cdm | IRD_IRS_5-3_Restructure-PAYG_01 | YoY inflation EUR | CAPITAL payment 41,510 EUR omitted |
| MAJOR | 62 | fpml_to_cdm | IRD_IRS_5-3_SWAP_INS_01 | YoY inflation basis US CPI-U | CPI index identifier absent from CDM |
| MAJOR | 63 | fpml_to_cdm | IRD_ASWP_5-3_Restructure | Inflation YoY EUR-EXT-CPI | multiplier 0.011/interpolation/initialIndexLevel/lag dropped |
| MAJOR | 64 | fpml_to_cdm | IRD_CF_5-3_CancelAndReissue_MultiPremium | Cap EUR-EURIBOR 1M multi-premium | 3 premiums dropped |
| MAJOR | 68 | fpml_to_cdm | IRD_ASWP_5-3_Inflation_Interp_02 | Inflation YoY + EURIBOR stub | interpolationMethod/initialIndexLevel/lag dropped |
| MAJOR | 70 | fpml_to_cdm | IRD_CF_4-3_INS_01 | Cap EUR-EURIBOR 3M 1.5% | premium 100 EUR dropped |
| MAJOR | 70 | fpml_to_cdm | IRD_IRS_5-3_Restructure_03 | IRS fixed-float EUR 100M | CAPITAL payment 1,606 EUR omitted |
| MAJOR | 72 | fpml_to_cdm | IRD_CF_5-3_FinalStub | Cap EUR-EURIBOR 6M 1.2% | premium 10,000 EUR dropped |

---

## Appendix B — Reproducibility

- **Per-pair verdicts (239 objects** — product, verdict, score, 10 dimensions, cited findings, loss_locus,
  consulted_fpml): [mxml-cdm-llm-judge-verdicts.json](mxml-cdm-llm-judge-verdicts.json).
- **Adversarial adjudication (43 cases** — adjudicated_verdict, refuted, confidence, rationale, evidence):
  [mxml-cdm-llm-judge-adjudication.json](mxml-cdm-llm-judge-adjudication.json).
- Raw per-batch outputs (ephemeral): `tmp/judge/out/out_*.json`, `tmp/judge/verify/out/out_*.json` · manifest/batches: `tmp/judge/manifest.txt`, `tmp/judge/chunk_*.txt`.
- 20 judge sub-agents (1 pass) + 8 adversarial refuters; CDM read in full, MXML read in a targeted way, FpML as arbiter.
