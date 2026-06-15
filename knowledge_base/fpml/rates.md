# FpML 5.x rates products (IRS, FRA, OIS, basis, cross-currency)

The source-side structure for the interest-rate family. Paths are by **local name** (ignore
namespaces — see [document-structure.md](document-structure.md)). For the CDM target, see
[../mapping/rates.md](../mapping/rates.md) and [../cdm/object-model.md](../cdm/object-model.md).

> Source: FpML interest-rate derivative product architecture (confirmation view),
> https://www.fpml.org/spec/ — a swapStream defines one swap leg; there is no swap header.

## Vanilla / basis / cross-currency swap — `trade/swap`

A `<swap>` holds **one `<swapStream>` per leg** (a fixed/float IRS has two). Each swapStream is
self-contained — FpML has no "swap header"; every parameter lives inside its stream.

`swapStream` has up to 13 child components; the ones that matter for conversion:
- `payerPartyReference/@href`, `receiverPartyReference/@href` — who pays / receives this leg.
- `calculationPeriodDates` — the schedule:
  - `effectiveDate/unadjustedDate`, `terminationDate/unadjustedDate` (ISO `yyyy-MM-dd` text)
  - `calculationPeriodDatesAdjustments/businessDayConvention` (+ business centers)
  - `calculationPeriodFrequency/periodMultiplier`, `/period` (`D|W|M|Y`), `/rollConvention`
- `paymentDates`:
  - `paymentFrequency/periodMultiplier`, `/period`
  - `payRelativeTo` (e.g. `CalculationPeriodEndDate`)
  - `paymentDatesAdjustments/businessDayConvention`
- `resetDates` — **floating legs only** (a fixed leg never has it): `resetFrequency`,
  `fixingDates`, `resetDatesAdjustments`.
- `calculationPeriodAmount/calculation` — the economics:
  - notional: `…/notionalSchedule/notionalStepSchedule/initialValue` (decimal) + `/currency`
  - `dayCountFraction` (e.g. `ACT/360`)
  - **fixed leg marker**: a `fixedRateSchedule/initialValue` element exists (the fixed rate).
  - **floating leg marker**: a `floatingRateCalculation` element exists, containing
    `floatingRateIndex` (e.g. `EUR-EURIBOR-Reuters`), `indexTenor/periodMultiplier` + `/period`,
    and optionally `spreadSchedule/initialValue`.
- `stubCalculationPeriodAmount` — present only when the leg has an initial and/or final stub.
- `principalExchanges` — present for cross-currency / principal-exchanging swaps.

**Leg discrimination:** decide fixed vs floating by which calculation element is present
(`fixedRateSchedule` ⇒ fixed; `floatingRateCalculation` ⇒ floating). Don't rely on order.

## FRA (forward rate agreement) — `trade/fra`

A FRA is **not** a swap and has **no swapStream**. Its `<fra>` element is flat:
- `buyerPartyReference/@href`, `sellerPartyReference/@href`
- `adjustedEffectiveDate`, `adjustedTerminationDate` (note: *adjusted* dates, often with an
  `@id`), `paymentDate/unadjustedDate` + adjustments
- `fixingDateOffset` (a relative date offset, e.g. -2 business days)
- `dayCountFraction`, `calculationPeriodNumberOfDays`
- `notional/amount` + `notional/currency`
- `fixedRateOrStrikePrice` (the agreed rate)
- `floatingRateIndex` + `indexTenor` (the reference index)

So a FRA maps to a single CDM `InterestRatePayout` pair-of-roles with a floating observable and a
fixed rate, but the date handling uses *adjusted* dates and an offset-based fixing — different
from a swap. Grep a train FRA pair (`data/train/rates-5-10/`) and the expected JSON to see the
exact target shape.

## OIS (overnight index swap)

Structurally an OIS is a `<swap>` with two swapStreams like a vanilla IRS, but the floating leg's
`floatingRateIndex` is an overnight rate (e.g. `EUR-ESTR-OIS-COMPOUND`, `USD-SOFR`) and the leg
carries compounding (`floatingRateCalculation` may include compounding/averaging details). The
calculation/reset frequencies often differ from a vanilla IRS. Map the index via the
FloatingRateIndex enum (see [../cdm/enums.md](../cdm/enums.md)) and set the compounding method on
the payout only if the expected JSON has it.

## Always cross-check against the target

For any field above, confirm where CDM expects it by grepping `../cdm/structure-skeleton.json`
and the expected JSON for the specific trade. The FpML element name is the *source*; the CDM path
is the *destination* — [../mapping/rates.md](../mapping/rates.md) is the bridge.
