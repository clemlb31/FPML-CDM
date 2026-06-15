# CDM 6.x enums — mangling rules + the PeriodEnum trap

How an FpML string becomes a CDM enum **constant name**, the one enum trap that causes the most
compile errors, and reference tables of known values. Constant *names* are facts about the API
(the same thing `cdm_lookup name=<SomeEnum>` prints) — so they belong here as documentation. But
**the jar is the final authority**: when a constant's exact spelling matters, list it with
`cdm_lookup` rather than trusting any table below.

## Mangling rules (FpML token → CDM constant)

1. **Case boundary → underscore, then upper-case.** `InterestRate` → `INTEREST_RATE`.
2. **A constant that would start with a digit gets a leading underscore.** `30/360` → `_30_360`;
   a roll convention `15` → `_15`.
3. **Separators `/`, `.`, `-` generally become `_`.** `ACT/360` → `ACT_360`,
   `USD-LIBOR-BBA` → `USD_LIBOR_BBA`, `EUR-EURIBOR-Reuters` → `EUR_EURIBOR_REUTERS`.
4. **Some tokens map to an ISDA-qualified constant, not a literal transform.** `ACT/ACT` commonly
   resolves to `ACT_ACT_ISDA`. These are not derivable by string rules — verify with `cdm_lookup`.

When in doubt, apply the rule to get a *candidate*, then confirm the constant exists with
`cdm_lookup`. A wrong constant is a "cannot find symbol" compile error.

## ⚠️ The PeriodEnum vs PeriodExtendedEnum trap (top enum error)

There are **two** period enums and they are **not** interchangeable. Pick by the *container*:

| Container attribute | Period enum |
|---|---|
| `indexTenor.period`, relative-date offset `period` (RelativeDateOffset) | **`PeriodEnum`** (`D` `W` `M` `Y`) |
| `calculationPeriodFrequency.period` | **`PeriodExtendedEnum`** |
| `paymentFrequency.period`, any `Frequency.period` | **`PeriodExtendedEnum`** |
| `resetFrequency.period` | **`PeriodExtendedEnum`** |

Rule of thumb: a **frequency** takes `PeriodExtendedEnum`; a **tenor / offset** takes `PeriodEnum`.
The setter's parameter type tells you which — check it with `cdm_lookup` on the container type.

## Reference tables (verify exact spelling with `cdm_lookup`)

**DayCountFractionEnum** — `ACT/360`→`ACT_360`, `ACT/365.FIXED`→`ACT_365_FIXED`,
`ACT/ACT.ISDA`→`ACT_ACT_ISDA`, `ACT/ACT.ISMA`→`ACT_ACT_ISMA`, `30/360`→`_30_360`,
`30E/360`→`_30E_360` *(or `_30_E_360` — the two spellings differ across versions; confirm in the
jar)*, `30E/360.ISDA`→`_30E_360_ISDA`.

**FloatingRateIndexEnum** (examples — many values exist, grep the enum):
`USD-SOFR`→`USD_SOFR`, `USD-LIBOR-BBA`→`USD_LIBOR_BBA`, `EUR-EURIBOR-Reuters`→`EUR_EURIBOR_REUTERS`,
`EUR-ESTR-OIS-COMPOUND`→`EUR_ESTR_OIS_COMPOUND`, `GBP-SONIA-COMPOUND`→`GBP_SONIA_COMPOUND`,
`JPY-TONAR-OIS-COMPOUND`→`JPY_TONAR_OIS_COMPOUND`.

**PeriodEnum / PeriodExtendedEnum** — `D` (day), `W` (week), `M` (month), `Y` (year). The extended
enum adds further values (e.g. `T` for term); use it for frequencies.

**BusinessDayConventionEnum** — `FOLLOWING`, `MODFOLLOWING`→`MOD_FOLLOWING`, `PRECEDING`,
`MODPRECEDING`→`MOD_PRECEDING`, `NONE`, `NotApplicable`→`NOT_APPLICABLE`.

**RollConventionEnum** — `EOM`, `IMM`, `NONE`; day-of-month numbers get a leading underscore
(`15`→`_15`).

**CounterpartyRoleEnum** — `PARTY_1` (serializes as `"Party1"`), `PARTY_2` (`"Party2"`).

**AssetClassEnum** — `INTEREST_RATE`, `CREDIT`, `EQUITY`, `FOREIGN_EXCHANGE`, `COMMODITY`, …

**BusinessCenterEnum** — usually the FpML code verbatim (`EUTA`, `USNY`, `GBLO`, …); pass it
through and confirm the constant exists.

For any enum not listed, or to settle a spelling: `cdm_lookup name=<Enum>` lists every real
constant. Never guess a constant twice — look it up.
