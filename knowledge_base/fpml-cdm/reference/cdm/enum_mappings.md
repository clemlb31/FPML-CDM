# FpML string → CDM Java Enum Mappings

## DayCountFractionEnum
  "ACT/360"          → DayCountFractionEnum.ACT_360
  "ACT/365.Fixed"    → DayCountFractionEnum.ACT_365_FIXED
  "ACT/ACT.ISDA"     → DayCountFractionEnum.ACT_ACT_ISDA
  "ACT/ACT.ISMA"     → DayCountFractionEnum.ACT_ACT_ISMA
  "30/360"           → DayCountFractionEnum._30_360
  "30E/360"          → DayCountFractionEnum._30_E_360
  "30E/360.ISDA"     → DayCountFractionEnum._30_E_360_ISDA

## FloatingRateIndexEnum (common values)
  "USD-SOFR"              → FloatingRateIndexEnum.USD_SOFR
  "USD-LIBOR-BBA"         → FloatingRateIndexEnum.USD_LIBOR_BBA
  "EUR-EURIBOR-Reuters"   → FloatingRateIndexEnum.EUR_EURIBOR_REUTERS
  "EUR-ESTR-OIS-COMPOUND" → FloatingRateIndexEnum.EUR_ESTR_OIS_COMPOUND
  "GBP-SONIA-COMPOUND"    → FloatingRateIndexEnum.GBP_SONIA_COMPOUND
  "JPY-TONAR-OIS-COMPOUND"→ FloatingRateIndexEnum.JPY_TONAR_OIS_COMPOUND

## PeriodEnum / PeriodExtendedEnum
  "D" → PeriodEnum.D    (use for indexTenor: overnight)
  "W" → PeriodEnum.W
  "M" → PeriodEnum.M    (PeriodExtendedEnum.M for calculationPeriodFrequency)
  "Y" → PeriodEnum.Y

## BusinessDayConventionEnum
  "FOLLOWING"    → BusinessDayConventionEnum.FOLLOWING
  "MODFOLLOWING" → BusinessDayConventionEnum.MOD_FOLLOWING
  "PRECEDING"    → BusinessDayConventionEnum.PRECEDING
  "NONE"         → BusinessDayConventionEnum.NONE

## RollConventionEnum
  "EOM"  → RollConventionEnum.EOM
  "IMM"  → RollConventionEnum.IMM
  "NONE" → RollConventionEnum.NONE
  "15"   → RollConventionEnum._15   (day-of-month values have _ prefix)

## CounterpartyRoleEnum
  First party (payer of fixed by convention) → CounterpartyRoleEnum.PARTY_1
  Second party                               → CounterpartyRoleEnum.PARTY_2
