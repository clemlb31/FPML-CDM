# FpML → CDM mapping — rates (IRS / FRA / OIS)

Field-by-field bridge for the interest-rate family. Source: [../fpml/rates.md](../fpml/rates.md).
Target paths: grep [../cdm/structure-skeleton.json](../cdm/structure-skeleton.json). Read
[principles.md](principles.md) first (address/location model, per-leg dates, role resolution).
**Transforms are described in words** — implement them; no code here. Confirm every CDM type with
`cdm_lookup`.

CDM paths below are written in dotted form (a path into the skeleton), not Java. `[i]` denotes a
list element.

## 1. Trade header

| FpML xpath | CDM path | Transform |
|---|---|---|
| `trade/tradeHeader/tradeDate` | `trade.tradeDate.value` | parse date; wrap (FieldWithMetaDate) |
| `trade/tradeHeader/partyTradeIdentifier[1]/tradeId` | `trade.tradeIdentifier[0].assignedIdentifier[0].identifier.value` | string |
| `trade/tradeHeader/partyTradeIdentifier[2]/tradeId` | `trade.tradeIdentifier[1].assignedIdentifier[0].identifier.value` | string |

One `tradeIdentifier` per `partyTradeIdentifier`. Count them in the expected JSON — getting the
*number* right matters for the diff.

## 2. Parties (document root)

| FpML xpath | CDM path | Transform |
|---|---|---|
| `dataDocument/party[@id]/partyId` (LEI scheme) | `trade.party[i].partyId[0].identifier.value` | string; carry the id scheme as `meta.scheme` |
| `dataDocument/party[@id]/partyName` | `trade.party[i].name.value` | string |
| `<party>/@id` | the party's `meta.externalKey` | carry the FpML id (target of externalReference) |

Each party also referenced from `trade.counterparty[i]` (role + `partyReference.externalReference`
= the FpML id). See [principles.md](principles.md) §3.

## 3. The actual values — `trade.tradeLot.priceQuantity`

This is where notional / fixed rate / floating index live, each with a `meta.location` label.

| FpML xpath | CDM path | Transform |
|---|---|---|
| `…/notionalStepSchedule/initialValue` (+ `/currency`) | `tradeLot.priceQuantity[q].quantity.value.value` (+ `…unit.currency.value`) | parse decimal; currency carries ISO-4217 `meta.scheme`; tag `quantity.meta.location` (e.g. `quantity-1`) |
| fixed leg `fixedRateSchedule/initialValue` | `tradeLot.priceQuantity[p].price.value.value` | parse decimal; `price.value.priceType` set to the interest-rate price type; tag `price.meta.location` (e.g. `price-1`) |
| floating `floatingRateIndex` (+ `indexTenor`) | `tradeLot.priceQuantity[o].observable.value.Index.InterestRateIndex.value.FloatingRateIndex.{floatingRateIndex.value, indexTenor.*}` | enum-map the index; tag `observable.meta.location` (e.g. `InterestRateIndex-1`) |

## 4. Per-leg `InterestRatePayout` (one Payout per swapStream)

Discriminate fixed vs floating by which calculation element is present (see
[../fpml/rates.md](../fpml/rates.md)). Each leg → `economicTerms.payout[].InterestRatePayout`.

| FpML xpath | CDM path | Transform |
|---|---|---|
| `swapStream/payerPartyReference/@href` | `…InterestRatePayout.payerReceiver.payer` | role resolve → CounterpartyRoleEnum |
| `swapStream/receiverPartyReference/@href` | `…InterestRatePayout.payerReceiver.receiver` | role resolve |
| `…/dayCountFraction` | `…InterestRatePayout.dayCountFraction.value` | enum map (DayCountFractionEnum) |
| `calculationPeriodDates/effectiveDate/unadjustedDate` | `…calculationPeriodDates.effectiveDate.adjustableDate.unadjustedDate` | parse date |
| `calculationPeriodDates/terminationDate/unadjustedDate` | `…calculationPeriodDates.terminationDate.adjustableDate.unadjustedDate` | parse date |
| `calculationPeriodFrequency/periodMultiplier` | `…calculationPeriodFrequency.periodMultiplier` | parse integer |
| `calculationPeriodFrequency/period` | `…calculationPeriodFrequency.period` | enum map → **PeriodExtendedEnum** |
| `calculationPeriodFrequency/rollConvention` | `…calculationPeriodFrequency.rollConvention` | enum map (RollConventionEnum) |
| `paymentDates/paymentFrequency/periodMultiplier` | `…paymentDates.paymentFrequency.periodMultiplier` | parse integer |
| `paymentDates/paymentFrequency/period` | `…paymentDates.paymentFrequency.period` | enum map → **PeriodExtendedEnum** |
| `paymentDates/payRelativeTo` | `…paymentDates.payRelativeTo` | enum map |
| business day conventions (calc/payment adjustments) | the matching `…Adjustments.businessDayConvention` (+ `businessCenters`) | enum map |

### Rate specification (address into the tradeLot — not the value)

| Leg | CDM path | Points at |
|---|---|---|
| fixed | `…InterestRatePayout.rateSpecification.FixedRateSpecification.rateSchedule.price.address.{scope,value}` | the `price` location (e.g. `price-1`) |
| floating | `…InterestRatePayout.rateSpecification.FloatingRateSpecification.rateOption.address.{scope,value}` | the `observable` location (e.g. `InterestRateIndex-1`) |
| floating spread (optional) | `…FloatingRateSpecification.spreadSchedule.price.address` | a spread price location, if present |

### Quantity reference (address into the tradeLot)

| CDM path | Points at |
|---|---|
| `…InterestRatePayout.priceQuantity.quantitySchedule.address.{scope,value}` | the `quantity` location for that leg |

### Reset dates (floating leg only)

`swapStream/resetDates/*` → `…InterestRatePayout.resetDates` (`resetFrequency`, `fixingDates`,
`resetDatesAdjustments`, and `calculationPeriodDatesReference` pointing back at the calc dates). A
fixed leg has none.

## 5. FRA and OIS deltas

- **FRA** (`trade/fra`, not a swap): uses *adjusted* dates (`adjustedEffectiveDate`,
  `adjustedTerminationDate`), a `paymentDate`, a `fixingDateOffset`, `fixedRateOrStrikePrice`, and a
  single `floatingRateIndex`+`indexTenor`. One `InterestRatePayout` with a floating observable and a
  fixed rate; map the adjusted dates onto `adjustedDate`/`unadjustedDate` as the expected JSON shows.
- **OIS**: vanilla-swap shape but the floating index is an overnight compounded rate; set the
  compounding method on the payout only if the expected JSON has it.

For anything not covered, grep the expected JSON + skeleton and verify types with `cdm_lookup`.
Historical note: this map descends from the old `rules/irs.md` field table, re-expressed without
code and corrected to the address/location reference model.
