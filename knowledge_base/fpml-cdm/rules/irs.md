# IRS FpML → CDM Mapping Rules

This file defines **every field mapping** from FpML 5.x IRS XML to the ISDA CDM Java object model.

**How to use:**
- Edit a row to change how a field is mapped (e.g. change the CDM path or transform logic)
- Run `python langgraph_agent/graph.py --patch` to regenerate only the affected Java method(s)
- The system detects which rows changed and patches the corresponding code

---

## 1. Trade Header

| Field               | FpML XPath                                                                | CDM Java Path                                                            | Transform                    |
|---------------------|---------------------------------------------------------------------------|--------------------------------------------------------------------------|------------------------------|
| Trade Date          | `/trade/tradeHeader/tradeDate`                                            | `Trade.tradeDate.value`                                                  | `Date.parse(text)`           |
| Own Trade ID        | `/trade/tradeHeader/partyTradeIdentifier[1]/tradeId`                      | `Trade.tradeIdentifier[0].assignedIdentifier[0].identifier.value`        | String                       |
| Counterparty ID     | `/trade/tradeHeader/partyTradeIdentifier[2]/tradeId`                      | `Trade.tradeIdentifier[1].assignedIdentifier[0].identifier.value`        | String                       |

## 2. Parties  *(at document root, not inside trade)*

| Field               | FpML XPath                                                                | CDM Java Path                                                            | Transform                    |
|---------------------|---------------------------------------------------------------------------|--------------------------------------------------------------------------|------------------------------|
| Party LEI           | `/dataDocument/party[@id]/partyId[iso17442 scheme]`                       | `Party.partyId[0].identifier.value`                                      | String (LEI)                 |
| Party Name          | `/dataDocument/party[@id]/partyName`                                      | `Party.name.value`                                                       | String                       |

## 3. Economic Dates  *(live inside each InterestRatePayout.calculationPeriodDates — NOT at EconomicTerms level)*

> **Important:** Reference CDM JSON (ird-ex01a-vanilla-swap.json) confirms that
> `economicTerms` contains only `["payout", "calculationAgent"]` — no `effectiveDate`
> or `terminationDate` at the `EconomicTerms` level.  Dates must be set on
> `InterestRatePayout.calculationPeriodDates` for each leg separately.

| Field               | FpML XPath                                                                | CDM Java Path                                                                            | Transform                    |
|---------------------|---------------------------------------------------------------------------|------------------------------------------------------------------------------------------|------------------------------|
| Effective Date      | `swapStream/calculationPeriodDates/effectiveDate/unadjustedDate`          | `InterestRatePayout.calculationPeriodDates.effectiveDate.adjustableDate.unadjustedDate`  | `Date.of(y,m,d)`             |
| Termination Date    | `swapStream/calculationPeriodDates/terminationDate/unadjustedDate`        | `InterestRatePayout.calculationPeriodDates.terminationDate.adjustableDate.unadjustedDate`| `Date.of(y,m,d)`             |

## 4. TradeLot / Price+Quantity  *(actual values with DOCUMENT-scope location labels)*

> **Important:** CDM uses DOCUMENT-scope address cross-references.  The **actual** notional
> and rate values live in `tradeLot[0].priceQuantity[]` with location labels.
> The InterestRatePayout only holds **address references** pointing back to these values.

| Field               | FpML XPath                                                                | CDM Java Path                                                                        | Transform                    |
|---------------------|---------------------------------------------------------------------------|--------------------------------------------------------------------------------------|------------------------------|
| Floating Observable | `swapStream/.../floatingRateIndex` + `indexTenor`                         | `Trade.tradeLot[0].priceQuantity[0].observable` with `meta.location=[{scope:DOCUMENT, value:InterestRateIndex-1}]` | FloatingRateIndex value     |
| Floating Notional   | `swapStream[float]/.../notionalStepSchedule/initialValue + currency`      | `Trade.tradeLot[0].priceQuantity[0].quantity[0]` with `meta.location=[{scope:DOCUMENT, value:quantity-1}]` | `new BigDecimal(text)`, UnitType.currency |
| Fixed Rate          | `swapStream[fixed]/.../fixedRateSchedule/initialValue`                    | `Trade.tradeLot[0].priceQuantity[1].price[0]` with `meta.location=[{scope:DOCUMENT, value:price-1}]`, `priceType=InterestRate` | `new BigDecimal(text)` |
| Fixed Notional      | `swapStream[fixed]/.../notionalStepSchedule/initialValue + currency`      | `Trade.tradeLot[0].priceQuantity[1].quantity[0]` with `meta.location=[{scope:DOCUMENT, value:quantity-2}]` | `new BigDecimal(text)`, UnitType.currency |

## 5. Fixed Leg  *(swapStream where fixedRateSchedule exists — uses address references into tradeLot)*

| Field               | FpML XPath                                                                | CDM Java Path                                                            | Transform                    |
|---------------------|---------------------------------------------------------------------------|--------------------------------------------------------------------------|------------------------------|
| Payer               | `swapStream/payerPartyReference/@href`                                    | `InterestRatePayout.payerReceiver.payer`                                 | href→`CounterpartyRoleEnum`  |
| Receiver            | `swapStream/receiverPartyReference/@href`                                 | `InterestRatePayout.payerReceiver.receiver`                              | href→`CounterpartyRoleEnum`  |
| Rate (address ref)  | *(points to tradeLot pq[1].price — see TradeLot section above)*           | `InterestRatePayout.rateSpecification` = `{FixedRateSpecification:{rateSchedule:{price:{address:{scope:DOCUMENT,value:price-1}}}}}` | address ref only |
| Quantity (addr ref) | *(points to tradeLot pq[1].quantity — see TradeLot section above)*        | `InterestRatePayout.priceQuantity.quantitySchedule.address` = `{scope:DOCUMENT,value:quantity-2}` | address ref only |
| Day Count Fraction  | `swapStream/.../dayCountFraction`                                         | `InterestRatePayout.dayCountFraction.value`                              | `DayCountFractionEnum` map   |
| Calc Period Mult    | `swapStream/calculationPeriodDates/.../periodMultiplier`                  | `CalculationPeriodDates.calculationPeriodFrequency.periodMultiplier`     | `Integer.parseInt(text)`     |
| Calc Period Unit    | `swapStream/calculationPeriodDates/.../period`                            | `CalculationPeriodDates.calculationPeriodFrequency.period`               | `PeriodExtendedEnum.valueOf` |
| Roll Convention     | `swapStream/calculationPeriodDates/.../rollConvention`                    | `CalculationPeriodDates.calculationPeriodFrequency.rollConvention`       | `RollConventionEnum` map     |
| Payment Freq Mult   | `swapStream/paymentDates/paymentFrequency/periodMultiplier`               | `PaymentDates.paymentFrequency.periodMultiplier`                         | `Integer.parseInt(text)`     |
| Payment Freq Unit   | `swapStream/paymentDates/paymentFrequency/period`                         | `PaymentDates.paymentFrequency.period`                                   | `PeriodExtendedEnum.valueOf` |

## 6. Floating Leg  *(swapStream where floatingRateCalculation exists — uses address references into tradeLot)*

| Field               | FpML XPath                                                                | CDM Java Path                                                            | Transform                    |
|---------------------|---------------------------------------------------------------------------|--------------------------------------------------------------------------|------------------------------|
| Payer               | `swapStream/payerPartyReference/@href`                                    | `InterestRatePayout.payerReceiver.payer`                                 | href→`CounterpartyRoleEnum`  |
| Receiver            | `swapStream/receiverPartyReference/@href`                                 | `InterestRatePayout.payerReceiver.receiver`                              | href→`CounterpartyRoleEnum`  |
| Rate (address ref)  | *(points to tradeLot pq[0].observable — see TradeLot section above)*      | `InterestRatePayout.rateSpecification` = `{FloatingRateSpecification:{rateOption:{address:{scope:DOCUMENT,value:InterestRateIndex-1}}}}` | address ref only |
| Quantity (addr ref) | *(points to tradeLot pq[0].quantity — see TradeLot section above)*        | `InterestRatePayout.priceQuantity.quantitySchedule.address` = `{scope:DOCUMENT,value:quantity-1}` | address ref only |
| Day Count Fraction  | `swapStream/.../dayCountFraction`                                         | `InterestRatePayout.dayCountFraction.value`                              | `DayCountFractionEnum` map   |
| Calc Period Mult    | `swapStream/calculationPeriodDates/.../periodMultiplier`                  | `CalculationPeriodDates.calculationPeriodFrequency.periodMultiplier`     | `Integer.parseInt(text)`     |
| Calc Period Unit    | `swapStream/calculationPeriodDates/.../period`                            | `CalculationPeriodDates.calculationPeriodFrequency.period`               | `PeriodExtendedEnum.valueOf` |
| Payment Freq Mult   | `swapStream/paymentDates/paymentFrequency/periodMultiplier`               | `PaymentDates.paymentFrequency.periodMultiplier`                         | `Integer.parseInt(text)`     |
| Payment Freq Unit   | `swapStream/paymentDates/paymentFrequency/period`                         | `PaymentDates.paymentFrequency.period`                                   | `PeriodExtendedEnum.valueOf` |

---
*Last modified: 2026-05-07*
