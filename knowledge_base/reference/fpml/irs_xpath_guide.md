# FpML 5.x Interest Rate Swap — XML Structure Reference

Namespace: http://www.fpml.org/FpML-5/confirmation
Root:       <dataDocument> (or <FpML>)
Trade:      /dataDocument/trade

## Trade Header
  Trade date:          /trade/tradeHeader/tradeDate                          → text "YYYY-MM-DD"
  Party 1 trade ID:    /trade/tradeHeader/partyTradeIdentifier[1]/tradeId   → text
  Party 2 trade ID:    /trade/tradeHeader/partyTradeIdentifier[2]/tradeId   → text
  Party 1 ref:         /trade/tradeHeader/partyTradeIdentifier[1]/partyReference/@href

## Parties  (at document level, not inside <trade>)
  Party element:       /dataDocument/party[@id='partyX']
  Party LEI:           party/partyId[@partyIdScheme contains 'iso17442']    → text (LEI string)
  Party name:          party/partyName                                       → text

## Swap Streams
  All streams:         /trade/swap/swapStream                                → NodeList
  Payer href:          swapStream/payerPartyReference/@href
  Receiver href:       swapStream/receiverPartyReference/@href
  Fixed leg marker:    swapStream/calculationPeriodAmount/calculation/fixedRateSchedule   (element exists)
  Float leg marker:    swapStream/calculationPeriodAmount/calculation/floatingRateCalculation (element exists)

## Dates (inside each swapStream)
  Effective date:      swapStream/calculationPeriodDates/effectiveDate/unadjustedDate     → text
  Termination date:    swapStream/calculationPeriodDates/terminationDate/unadjustedDate   → text

## Calculation Period Frequency (inside calculationPeriodDates)
  Multiplier:          calculationPeriodFrequency/periodMultiplier  → int text
  Period unit:         calculationPeriodFrequency/period            → "D"|"W"|"M"|"Y"
  Roll convention:     calculationPeriodFrequency/rollConvention    → "EOM"|"IMM"|"15"|"NONE"…

## Notional (inside each swapStream)
  Amount:   calculationPeriodAmount/calculation/notionalSchedule/notionalStepSchedule/initialValue → decimal text
  Currency: calculationPeriodAmount/calculation/notionalSchedule/notionalStepSchedule/currency     → "USD"…

## Fixed Leg (inside calculationPeriodAmount/calculation)
  Fixed rate:          fixedRateSchedule/initialValue          → decimal text  e.g. "0.025"
  Day count fraction:  dayCountFraction                        → e.g. "ACT/360"

## Floating Leg (inside calculationPeriodAmount/calculation)
  Rate index:          floatingRateCalculation/floatingRateIndex              → e.g. "USD-SOFR"
  Tenor multiplier:    floatingRateCalculation/indexTenor/periodMultiplier    → int
  Tenor unit:          floatingRateCalculation/indexTenor/period              → "D"|"M"|"Y"
  Spread:              floatingRateCalculation/spreadSchedule/initialValue    → decimal (optional, may be absent)
  Day count fraction:  dayCountFraction                                       → e.g. "ACT/360"

## Payment Dates (inside each swapStream)
  Freq multiplier:     paymentDates/paymentFrequency/periodMultiplier → int
  Freq unit:           paymentDates/paymentFrequency/period           → "M"|"Y"…
  Pay relative to:     paymentDates/payRelativeTo                     → "CalculationPeriodEndDate"
  Business day conv:   paymentDates/paymentDatesAdjustments/businessDayConvention
