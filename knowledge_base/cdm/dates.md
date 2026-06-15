# CDM 6.x dates

How dates and date schedules are shaped in CDM, **in words**. No code — get the exact factory and
builder method names from `cdm_lookup` (the `Date` type, `AdjustableDate`,
`AdjustableOrRelativeDate`, `BusinessDayAdjustments`). Grep `cdm/structure-skeleton.json` for the
exact nesting of any date field.

## The scalar date type

CDM uses its own date record — `com.rosetta.model.lib.records.Date` — a plain year / month / day
value. It is **not** `java.time.LocalDate`; passing a `LocalDate` into a CDM builder is rejected.
You obtain a `Date` either by parsing an ISO `yyyy-MM-dd` string (the form FpML gives you) or from
year/month/day components. Look up the exact factory name with `cdm_lookup name=Date`.

## Adjustable vs relative dates

Most "dates" in a rate product are not bare `Date`s — they are wrapper structures:

- **`AdjustableDate`** — an `unadjustedDate` (the raw `Date`) plus optional `dateAdjustments`
  (a `BusinessDayAdjustments`) plus an optional `adjustedDate`. FpML usually gives you the
  unadjusted date as text; the adjusted date is computed, so set `unadjustedDate` (+ adjustments)
  and only populate `adjustedDate` if the expected JSON actually contains one.
- **`AdjustableOrRelativeDate`** — a *choice* between an `adjustableDate` (an absolute date as
  above) and a `relativeDate` (an offset relative to another date). `effectiveDate` and
  `terminationDate` on `calculationPeriodDates` have this shape in the skeleton.
- **`BusinessDayAdjustments`** — a `businessDayConvention` (enum, e.g. `MODFOLLOWING`) plus
  `businessCenters` (either an inline list of business-center values or a
  `businessCentersReference` pointing at a shared set). See [enums.md](enums.md) for the
  convention/center enums.

## The date *schedules* on an InterestRatePayout

From the skeleton, each `InterestRatePayout` carries (set the ones the FpML leg has):

- **`calculationPeriodDates`** — `effectiveDate`, `terminationDate`,
  `calculationPeriodDatesAdjustments`, `calculationPeriodFrequency`
  (`periodMultiplier` + `period` + `rollConvention`), and optional first/last regular period dates
  and `stubPeriodType`. Its `meta` can carry an `externalKey` (the FpML `@id`).
- **`paymentDates`** — `paymentFrequency` (`periodMultiplier` + `period`), `payRelativeTo`,
  `paymentDatesAdjustments`, and optional first/last payment dates and `paymentDaysOffset`.
- **`resetDates`** — *floating legs only* — `calculationPeriodDatesReference` (points back at the
  calc-period-dates block), `resetRelativeTo`, `fixingDates`, `resetFrequency`,
  `resetDatesAdjustments`. A fixed leg has no `resetDates`.

## Where the period unit enum differs

`calculationPeriodFrequency.period`, `paymentFrequency.period` and `resetFrequency.period` take a
**`PeriodExtendedEnum`**, while a tenor/offset `period` (e.g. an `indexTenor`) takes a
**`PeriodEnum`**. This is a top compile trap — see [enums.md](enums.md). Confirm which by the
parameter type of the relevant setter via `cdm_lookup`.

## Per-leg, not product-level

The skeleton puts effective/termination/frequency dates **inside each
`InterestRatePayout.calculationPeriodDates`** — set them per payout (per leg), even when both legs
share the same dates. (The model also allows product-level dates on `EconomicTerms`, but the rate
examples use the per-leg location; match what the expected JSON shows for your trade.)
