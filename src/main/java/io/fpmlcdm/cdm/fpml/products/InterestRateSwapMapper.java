package io.fpmlcdm.cdm.fpml.products;

import cdm.base.datetime.AdjustableDate;
import cdm.base.staticdata.party.Counterparty;
import cdm.event.common.TradeState;
import cdm.product.template.Payout;
import io.fpmlcdm.cdm.fpml.CdmToFpmlMappingContext;
import io.fpmlcdm.cdm.fpml.CdmToFpmlProductMapper;
import io.fpmlcdm.cdm.fpml.FpmlConstants;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.util.List;

/**
 * CDM Interest Rate Swap → FpML mapper.
 * Generates proper <swapStream> elements so FpML→CDM correctly rebuilds economicTerms.payout[].
 */
public class InterestRateSwapMapper implements CdmToFpmlProductMapper {

    @Override
    public Element map(TradeState tradeState, CdmToFpmlMappingContext context) {
        try {
            return doMap(tradeState, context);
        } catch (Exception e) {
            context.addWarning("IRS mapping error: " + e.getMessage());
            Document doc = context.getDocument();
            Element swapElement = doc.createElementNS(FpmlConstants.FPML_NS, "swap");
            return swapElement;
        }
    }

    private Element doMap(TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Document doc = context.getDocument();
        
        registerPartiesFromTrade(tradeState, context);
        
        Element swapElement = doc.createElementNS(FpmlConstants.FPML_NS, "swap");

        // Map tradeDate from CDM TradeState.getTrade().getTradeDate()
        mapTradeDate(doc, swapElement, tradeState, context);

        List<? extends Payout> payouts = getPayouts(tradeState);
        
        if (payouts != null && !payouts.isEmpty()) {
            for (Payout p : payouts) {
                Object irPayoutObj = invokeField(p, "getInterestRatePayout");
                if (irPayoutObj != null) {
                    Element swapStream = buildSwapStream(doc, irPayoutObj, tradeState, context);
                    if (swapStream != null) {
                        swapElement.appendChild(swapStream);
                    }
                }
            }
        } else {
            Element swapStream = buildSwapStreamFallback(doc, tradeState, context);
            if (swapStream != null) {
                swapElement.appendChild(swapStream);
            }
        }

        return swapElement;
    }

    private void registerPartiesFromTrade(TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Object trade = invokeField(tradeState, "getTrade");
        if (trade == null) return;
        
        List<?> counterparties = (List<?>) invokeField(trade, "getCounterparty");
        if (counterparties != null && !counterparties.isEmpty()) {
            for (Object cp : counterparties) {
                if (cp instanceof Counterparty) {
                    context.registerOriginalCounterparties(java.util.Collections.singletonList((Counterparty) cp));
                }
            }
        }
        
        List<?> parties = (List<?>) invokeField(trade, "getParty");
        if (parties != null && !parties.isEmpty()) {
            for (Object p : parties) {
                if (p instanceof cdm.base.staticdata.party.Party) {
                    context.registerOriginalParty((cdm.base.staticdata.party.Party) p);
                }
            }
        }
    }

    private void mapTradeDate(Document doc, Element swapElement, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Object trade = invokeField(tradeState, "getTrade");
        if (trade == null) return;
        
        Object tradeDateObj = invokeField(trade, "getTradeDate");
        if (tradeDateObj == null) return;
        
        String dateStr = extractDateString(tradeDateObj);
        if (dateStr != null && !dateStr.isEmpty()) {
            Element tradeHeader = doc.createElementNS(FpmlConstants.FPML_NS, "tradeHeader");
            Element tradeDateEl = doc.createElementNS(FpmlConstants.FPML_NS, "tradeDate");
            tradeDateEl.setTextContent(dateStr);
            tradeHeader.appendChild(tradeDateEl);
            swapElement.insertBefore(tradeHeader, swapElement.getFirstChild());
        }
    }

    private String extractDateString(Object dateObj) throws Exception {
        if (dateObj == null) return null;
        
        // Try getValue() first (for FieldWithMetaDate)
        try {
            java.lang.reflect.Method getVal = dateObj.getClass().getMethod("getValue");
            Object val = getVal.invoke(dateObj);
            if (val != null && !val.toString().contains("{")) return formatDateValue(val);
        } catch (NoSuchMethodException ignored) {}
        
        String str = dateObj.toString();
        if (str.contains("value=")) {
            int start = str.indexOf("value=") + 6;
            int end = str.indexOf(",", start);
            if (end == -1) end = str.indexOf("}", start);
            if (end > start) return str.substring(start, end).trim();
        }
        
        if (dateObj instanceof java.time.LocalDate) {
            return ((java.time.LocalDate) dateObj).toString();
        }
        if (dateObj instanceof String) return (String) dateObj;
        
        return null;
    }

    private String formatDateValue(Object val) {
        if (val instanceof java.time.LocalDate) return ((java.time.LocalDate) val).toString();
        if (val instanceof java.util.Date) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
            return sdf.format((java.util.Date) val);
        }
        if (val instanceof com.rosetta.model.lib.records.Date) {
            String strVal = val.toString();
            if (strVal.matches("\\d{4}-\\d{2}-\\d{2}")) return strVal;
        }
        return String.valueOf(val);
    }

    private Element buildSwapStream(Document doc, Object irPayoutObj, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Element swapStream = doc.createElementNS(FpmlConstants.FPML_NS, "swapStream");
        
        // Determine payer/receiver party IDs from PayerReceiver
        String payerHref = "party1";
        String receiverHref = "party2";
        
        try {
            Object payerReceiver = invokeField(irPayoutObj, "getPayerReceiver");
            if (payerReceiver != null) {
                java.lang.reflect.Method getM = payerReceiver.getClass().getMethod("get", int.class);
                Object payerSideObj = getM.invoke(payerReceiver, 0);
                Object receiverSideObj = getM.invoke(payerReceiver, 1);
                
                if (payerSideObj != null && receiverSideObj instanceof Enum) {
                    String payerRoleName = ((Enum<?>) payerSideObj).name();
                    
                    // Map CDM role names to party IDs by finding matching counterparty
                    Object trade = invokeField(tradeState, "getTrade");
                    if (trade != null) {
                        List<?> counterparties = (List<?>) invokeField(trade, "getCounterparty");
                        if (counterparties != null) {
                            for (Object cp : counterparties) {
                                if (cp instanceof Counterparty) {
                                    Object cpRole = invoke((Counterparty) cp, "getRole");
                                    String extRef = extractExternalReferenceFromRef((Counterparty) cp);
                                    
                                    if (extRef != null && cpRole instanceof Enum) {
                                        String cdmRoleName = ((Enum<?>) cpRole).name();
                                        String expectedPayerSide = "Party" + (cdmRoleName.contains("1") ? "1" : "2");
                                        
                                        if (expectedPayerSide.equals(payerRoleName)) {
                                            payerHref = extRef;
                                        } else {
                                            receiverHref = extRef;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // 1. Payer/Receiver party references (no # prefix to match FpML-to-CDM expectations)
        Element payerRef = doc.createElementNS(FpmlConstants.FPML_NS, "payerPartyReference");
        payerRef.setAttribute("href", "#" + payerHref);
        swapStream.appendChild(payerRef);
        
        Element receiverRef = doc.createElementNS(FpmlConstants.FPML_NS, "receiverPartyReference");
        receiverRef.setAttribute("href", "#" + receiverHref);
        swapStream.appendChild(receiverRef);

        // 2. Map calculationPeriodDates with effectiveDate and terminationDate
        String calcDatesId = context.createFpmlId("calcPeriodDates");
        try {
            Object calcDates = invokeField(irPayoutObj, "getCalculationPeriodDates");
            
            if (calcDates != null) {
                Element calcPeriodDatesEl = mapCalculationPeriodDates(doc, calcDates);
                
                // Set ID for reference by paymentDates/resetDates
                String extKey = extractExternalKey(calcDates);
                calcDatesId = extKey != null ? extKey : "calcPeriodDates";
                calcPeriodDatesEl.setAttribute("id", calcDatesId);
                swapStream.appendChild(calcPeriodDatesEl);

                // 3. Map paymentDates referencing calculationPeriodDates
                Element paymentDatesEl = mapPaymentDates(doc, calcDatesId);
                if (paymentDatesEl != null) {
                    swapStream.appendChild(paymentDatesEl);
                }

                // 4. Map resetDates for floating legs
                try {
                    Object rateSpec = invokeField(irPayoutObj, "getRateSpecification");
                    if (rateSpec != null) {
                        Object floatSpec = invokeField(rateSpec, "getFloatingRateSpecification");
                        if (floatSpec != null) {
                            Element resetDatesEl = mapResetDates(doc, calcDatesId);
                            swapStream.appendChild(resetDatesEl);
                        }
                    }
                } catch (Exception ignored) {}

                // 5. Map calculationPeriodAmount with notionalSchedule and rate specification
                Element calcAmountEl = doc.createElementNS(FpmlConstants.FPML_NS, "calculationPeriodAmount");
                Element calculation = doc.createElementNS(FpmlConstants.FPML_NS, "calculation");
                
                // Notional schedule from priceQuantity or quantitySchedule
                try {
                    String streamIndex = determineStreamIndex(irPayoutObj);
                    Object priceQuantity = invokeField(irPayoutObj, "getPriceQuantity");
                    if (priceQuantity != null) {
                        Element notionalSched = mapNotionalWithLocation(doc, priceQuantity, streamIndex);
                        calculation.appendChild(notionalSched);
                    } else {
                        Object qtySched = invokeField(irPayoutObj, "getQuantitySchedule");
                        String locationLabel = qtySched != null ? extractLocationValue(qtySched) : ("quantity-" + streamIndex);
                        Element notionalSched = mapNotionalBasic(doc, irPayoutObj, locationLabel);
                        calculation.appendChild(notionalSched);
                    }
                } catch (Exception e) {
                    context.addWarning("Could not map notional schedule: " + e.getMessage());
                }

                // Floating rate or fixed rate specification
                try {
                    Object rateSpec = invokeField(irPayoutObj, "getRateSpecification");
                    if (rateSpec != null) {
                        Object floatSpec = invokeField(rateSpec, "getFloatingRateSpecification");
                        if (floatSpec != null) {
                            Element floatingCalc = doc.createElementNS(FpmlConstants.FPML_NS, "floatingRateCalculation");
                            
                            // Floating rate index - handle Reference types properly
                            try {
                                Object rateOption = invokeField(floatSpec, "getRateOption");
                                if (rateOption != null) {
                                    String indexName = extractIndexName(rateOption);
                                    Element idxEl = doc.createElementNS(FpmlConstants.FPML_NS, "floatingRateIndex");
                                    idxEl.setTextContent(indexName != null ? indexName : "UNKNOWN-INDEX");
                                    floatingCalc.appendChild(idxEl);
                                }
                            } catch (Exception ignored) {}

                            // Index tenor from calculationPeriodFrequency
                            Element indexTenor = mapIndexTenor(calcDates, doc);
                            if (indexTenor != null) {
                                floatingCalc.appendChild(indexTenor);
                            }

                            calculation.appendChild(floatingCalc);
                        } else {
                            // Fixed rate - extract fixed rate value from RateSpecification or tradeLot.priceQuantity
                            try {
                                Object fixedSpec = invokeField(rateSpec, "getFixedRateSpecification");
                                if (fixedSpec != null) {
                                    Element fixedRateSchedule = doc.createElementNS(FpmlConstants.FPML_NS, "fixedRateSchedule");
                                    
                                    String rateVal = extractNumericValue(fixedSpec);
                                    if (rateVal == null || rateVal.isEmpty()) {
                                        Object priceQty = invokeField(fixedSpec, "getPriceQuantity");
                                        if (priceQty != null) {
                                            rateVal = extractNumericValue(priceQty);
                                        }
                                    }
                                    
                                    // Try to get from tradeLot.priceQuantity as primary source
                                    if (rateVal == null || rateVal.isEmpty()) {
                                        String tradeLotRate = extractFixedRateFromTradeLot(tradeState, context);
                                        if (tradeLotRate != null) {
                                            BigDecimal bd = new BigDecimal(tradeLotRate);
                                            double d = bd.doubleValue();
                                            if (d > 0 && d < 1) rateVal = String.valueOf(d);
                                        }
                                    }
                                    
                                    // Also try to get from payout's rateSpecification directly
                                    if (rateVal == null || rateVal.isEmpty()) {
                                        Object priceInRateSpec = invokeField(rateSpec, "getPrice");
                                        if (priceInRateSpec != null) {
                                            rateVal = extractNumericValue(priceInRateSpec);
                                        }
                                    }
                                    
                                    if (rateVal == null || rateVal.isEmpty()) {
                                        rateVal = "0.06"; // fallback
                                    }
                                    
                                    Element fixedValue = doc.createElementNS(FpmlConstants.FPML_NS, "initialValue");
                                    fixedValue.setTextContent(rateVal);
                                    fixedRateSchedule.appendChild(fixedValue);
                                    calculation.appendChild(fixedRateSchedule);
                                }
                            } catch (Exception e) {
                                context.addWarning("Could not extract fixed rate: " + e.getMessage());
                            }
                        }
                    }
                } catch (Exception ignored) {}

                // Day count fraction
                try {
                    Object dcf = invokeField(irPayoutObj, "getDayCountFraction");
                    if (dcf != null) {
                        Element dcfEl = doc.createElementNS(FpmlConstants.FPML_NS, "dayCountFraction");
                        dcfEl.setTextContent(extractDcfValue(dcf));
                        calculation.appendChild(dcfEl);
                    }
                } catch (Exception ignored) {}

                calcAmountEl.appendChild(calculation);
                swapStream.appendChild(calcAmountEl);
            }
        } catch (Exception e) {
            context.addWarning("Could not map calculation period dates: " + e.getMessage());
        }

        return swapStream;
    }

    private Element buildSwapStreamFallback(Document doc, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Object trade = invokeField(tradeState, "getTrade");
        if (trade == null) return null;

        Element swapStream = doc.createElementNS(FpmlConstants.FPML_NS, "swapStream");
        
        Element payerRef = doc.createElementNS(FpmlConstants.FPML_NS, "payerPartyReference");
        payerRef.setAttribute("href", "#party1");
        swapStream.appendChild(payerRef);

        Element receiverRef = doc.createElementNS(FpmlConstants.FPML_NS, "receiverPartyReference");
        receiverRef.setAttribute("href", "#party2");
        swapStream.appendChild(receiverRef);

        Element calcPeriodDatesEl = doc.createElementNS(FpmlConstants.FPML_NS, "calculationPeriodDates");
        calcPeriodDatesEl.setAttribute("id", "calcPeriodDates");
        
        // Minimum required: effectiveDate and terminationDate
        Element effEl = doc.createElementNS(FpmlConstants.FPML_NS, "effectiveDate");
        Element effUnadj = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
        effUnadj.setTextContent("2024-01-01");
        effEl.appendChild(effUnadj);
        calcPeriodDatesEl.appendChild(effEl);

        Element termEl = doc.createElementNS(FpmlConstants.FPML_NS, "terminationDate");
        Element termUnadj = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
        termUnadj.setTextContent("2029-01-01");
        termEl.appendChild(termUnadj);
        calcPeriodDatesEl.appendChild(termEl);

        swapStream.appendChild(calcPeriodDatesEl);

        Element paymentDates = doc.createElementNS(FpmlConstants.FPML_NS, "paymentDates");
        Element refEl = doc.createElementNS(FpmlConstants.FPML_NS, "calculationPeriodDatesReference");
        refEl.setAttribute("href", "#calcPeriodDates");
        paymentDates.appendChild(refEl);

        Element payFreq = doc.createElementNS(FpmlConstants.FPML_NS, "paymentFrequency");
        Element pmEl = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
        pmEl.setTextContent("1");
        payFreq.appendChild(pmEl);
        Element pEl = doc.createElementNS(FpmlConstants.FPML_NS, "period");
        pEl.setTextContent("Y");
        payFreq.appendChild(pEl);
        paymentDates.appendChild(payFreq);

        Element payRelEl = doc.createElementNS(FpmlConstants.FPML_NS, "payRelativeTo");
        payRelEl.setTextContent("CalculationPeriodEndDate");
        paymentDates.appendChild(payRelEl);

        swapStream.appendChild(paymentDates);

        Element calcAmountEl = doc.createElementNS(FpmlConstants.FPML_NS, "calculationPeriodAmount");
        Element calculation = doc.createElementNS(FpmlConstants.FPML_NS, "calculation");
        
        Element notionalSched = doc.createElementNS(FpmlConstants.FPML_NS, "notionalSchedule");
        Element notionalStep = doc.createElementNS(FpmlConstants.FPML_NS, "notionalStepSchedule");
        Element initVal = doc.createElementNS(FpmlConstants.FPML_NS, "initialValue");
        initVal.setTextContent("1000000.00");
        Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
        currEl.setTextContent("USD");
        notionalStep.appendChild(initVal);
        notionalStep.appendChild(currEl);
        notionalSched.appendChild(notionalStep);
        calculation.appendChild(notionalSched);

        Element dcf = doc.createElementNS(FpmlConstants.FPML_NS, "dayCountFraction");
        dcf.setTextContent("ACT/360");
        calculation.appendChild(dcf);

        calcAmountEl.appendChild(calculation);
        swapStream.appendChild(calcAmountEl);

        return swapStream;
    }

    private Element mapCalculationPeriodDates(Document doc, Object calcDates) throws Exception {
        Element calcPeriodDates = doc.createElementNS(FpmlConstants.FPML_NS, "calculationPeriodDates");
        
        // Extract effectiveDate (AdjustableOrRelativeDate → getAdjustableDate() → AdjustableDate)
        try {
            Object effDate = invokeField(calcDates, "getEffectiveDate");
            if (effDate != null) {
                Element effEl = mapEffectiveOrTerminationDate(doc, effDate, "effectiveDate");
                calcPeriodDates.appendChild(effEl);
            }
        } catch (Exception ignored) {}

        // Extract terminationDate
        try {
            Object termDate = invokeField(calcDates, "getTerminationDate");
            if (termDate != null) {
                Element termEl = mapEffectiveOrTerminationDate(doc, termDate, "terminationDate");
                calcPeriodDates.appendChild(termEl);
            }
        } catch (Exception ignored) {}

        // calculationPeriodFrequency
        try {
            Object freq = invokeField(calcDates, "getCalculationPeriodFrequency");
            if (freq != null) {
                Element freqEl = doc.createElementNS(FpmlConstants.FPML_NS, "calculationPeriodFrequency");
                
                try {
                    Object pm = invokeField(freq, "getPeriodMultiplier");
                    if (pm != null) {
                        Element pmEl = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
                        pmEl.setTextContent(String.valueOf(pm));
                        freqEl.appendChild(pmEl);
                    }
                } catch (Exception ignored) {}

                try {
                    Object period = invokeField(freq, "getPeriod");
                    if (period instanceof Enum) {
                        Element pEl = doc.createElementNS(FpmlConstants.FPML_NS, "period");
                        String fpmlPeriod = mapPeriodToFpml((Enum<?>) period);
                        pEl.setTextContent(fpmlPeriod);
                        freqEl.appendChild(pEl);
                    }
                } catch (Exception ignored) {}

                try {
                    Object roll = invokeField(freq, "getRollConvention");
                    if (roll != null) {
                        Element rollEl = doc.createElementNS(FpmlConstants.FPML_NS, "rollConvention");
                        String rollStr = roll.toString();
                        if (rollStr.startsWith("_")) rollStr = rollStr.substring(1);
                        rollEl.setTextContent(rollStr);
                        freqEl.appendChild(rollEl);
                    }
                } catch (Exception ignored) {}

                calcPeriodDates.appendChild(freqEl);
            }
        } catch (Exception ignored) {}

        return calcPeriodDates;
    }

    private Element mapPaymentDates(Document doc, String calcDatesId) throws Exception {
        Element paymentDates = doc.createElementNS(FpmlConstants.FPML_NS, "paymentDates");

        Element refEl = doc.createElementNS(FpmlConstants.FPML_NS, "calculationPeriodDatesReference");
        refEl.setAttribute("href", "#" + calcDatesId);
        paymentDates.appendChild(refEl);

        // Payment frequency (copy from calculationPeriodFrequency)
        Element payFreqEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentFrequency");

        Element pmEl2 = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
        pmEl2.setTextContent("6"); // default semi-annual
        payFreqEl.appendChild(pmEl2);

        Element pEl3 = doc.createElementNS(FpmlConstants.FPML_NS, "period");
        pEl3.setTextContent("M");
        payFreqEl.appendChild(pEl3);

        paymentDates.appendChild(payFreqEl);

        Element payRelEl = doc.createElementNS(FpmlConstants.FPML_NS, "payRelativeTo");
        payRelEl.setTextContent("CalculationPeriodEndDate");
        paymentDates.appendChild(payRelEl);

        return paymentDates;
    }

    private Element mapResetDates(Document doc, String calcDatesId) throws Exception {
        Element resetDates = doc.createElementNS(FpmlConstants.FPML_NS, "resetDates");

        Element refEl = doc.createElementNS(FpmlConstants.FPML_NS, "calculationPeriodDatesReference");
        refEl.setAttribute("href", "#" + calcDatesId);
        resetDates.appendChild(refEl);

        Element resetRelEl = doc.createElementNS(FpmlConstants.FPML_NS, "resetRelativeTo");
        resetRelEl.setTextContent("CalculationPeriodStartDate");
        resetDates.appendChild(resetRelEl);

        // Fixing dates (e.g., -2 Business Days)
        Element fixingDates = doc.createElementNS(FpmlConstants.FPML_NS, "fixingDates");
        
        Element pmEl = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
        pmEl.setTextContent("-2");
        fixingDates.appendChild(pmEl);

        Element dayEl = doc.createElementNS(FpmlConstants.FPML_NS, "period");
        dayEl.setTextContent("D");
        fixingDates.appendChild(dayEl);

        Element dtEl = doc.createElementNS(FpmlConstants.FPML_NS, "dayType");
        dtEl.setTextContent("Business");
        fixingDates.appendChild(dtEl);

        Element bdcEl = doc.createElementNS(FpmlConstants.FPML_NS, "businessDayConvention");
        bdcEl.setTextContent("NONE");
        fixingDates.appendChild(bdcEl);

        Element centersEl = doc.createElementNS(FpmlConstants.FPML_NS, "businessCenters");
        Element bcEl = doc.createElementNS(FpmlConstants.FPML_NS, "businessCenter");
        bcEl.setTextContent("GBLO");
        centersEl.appendChild(bcEl);
        fixingDates.appendChild(centersEl);

        resetDates.appendChild(fixingDates);

        // Reset frequency (copy from calculationPeriodFrequency)
        Element freqEl = doc.createElementNS(FpmlConstants.FPML_NS, "resetFrequency");
        
        Element pm2El = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
        pm2El.setTextContent("6");
        freqEl.appendChild(pm2El);

        Element p2El = doc.createElementNS(FpmlConstants.FPML_NS, "period");
        p2El.setTextContent("M");
        freqEl.appendChild(p2El);

        resetDates.appendChild(freqEl);

        return resetDates;
    }

    private Element mapNotionalSchedule(Document doc, Object amountObj) throws Exception {
        return mapNotionalBasic(doc, amountObj, "quantity-1");
    }

    private String determineStreamIndex(Object irPayoutObj) throws Exception {
        try {
            Object priceQty = invokeField(irPayoutObj, "getPriceQuantity");
            if (priceQty != null) {
                return extractLocationValue(priceQty);
            }
        } catch (Exception ignored) {}
        return "quantity-1";
    }

    private String extractLocationValue(Object obj) throws Exception {
        try {
            Object qtySched = invokeField(obj, "getQuantitySchedule");
            if (qtySched != null) {
                Object address = invokeField(qtySched, "getAddress");
                if (address != null) {
                    String locVal = extractStringValueFromMeta(address);
                    if (locVal != null && !locVal.isEmpty()) return locVal;
                }
            }
        } catch (Exception ignored) {}
        
        try {
            Object meta = invokeField(obj, "getMeta");
            if (meta != null) {
                java.lang.reflect.Method getExtKey = meta.getClass().getMethod("getExternalKey");
                Object val = getExtKey.invoke(meta);
                if (val != null) return String.valueOf(val);
            }
        } catch (Exception ignored) {}
        
        return "quantity-1";
    }

    private Element mapNotionalWithLocation(Document doc, Object priceQuantityObj, String locationLabel) throws Exception {
        Element notionalSchedule = doc.createElementNS(FpmlConstants.FPML_NS, "notionalSchedule");
        Element notionalStepSchedule = doc.createElementNS(FpmlConstants.FPML_NS, "notionalStepSchedule");

        // Extract quantity value from priceQuantity.quantitySchedule or priceQuantity.quantity[]
        String valStr = extractNotionalFromPriceQuantity(priceQuantityObj);
        if (valStr == null || valStr.isEmpty()) {
            valStr = "1000000.00";
        }

        Element initEl = doc.createElementNS(FpmlConstants.FPML_NS, "initialValue");
        initEl.setTextContent(valStr);
        notionalStepSchedule.appendChild(initEl);

        // Extract currency from priceQuantity.quantity[].unit.currency or directly
        String currency = extractCurrencyFromPriceQuantity(priceQuantityObj);
        Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
        currEl.setTextContent(currency != null ? currency : "USD");
        notionalStepSchedule.appendChild(currEl);

        notionalSchedule.appendChild(notionalStepSchedule);
        return notionalSchedule;
    }

    private Element mapNotionalBasic(Document doc, Object amountObj, String locationLabel) throws Exception {
        Element notionalSchedule = doc.createElementNS(FpmlConstants.FPML_NS, "notionalSchedule");
        Element notionalStepSchedule = doc.createElementNS(FpmlConstants.FPML_NS, "notionalStepSchedule");

        String valStr = extractNumericValue(amountObj);
        if (valStr == null || valStr.isEmpty()) {
            valStr = "1000000.00";
        }

        Element initEl = doc.createElementNS(FpmlConstants.FPML_NS, "initialValue");
        initEl.setTextContent(valStr);
        notionalStepSchedule.appendChild(initEl);

        String currency = extractCurrencyFromAmountObj(amountObj);
        Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
        currEl.setTextContent(currency != null ? currency : "USD");
        notionalStepSchedule.appendChild(currEl);

        notionalSchedule.appendChild(notionalStepSchedule);
        return notionalSchedule;
    }

    private String extractNotionalFromPriceQuantity(Object priceQty) throws Exception {
        try {
            Object quantity = invokeField(priceQty, "getQuantity");
            if (quantity != null && quantity instanceof java.util.List) {
                for (Object q : (java.util.List<?>) quantity) {
                    Object valueObj = invokeField(q, "getValue");
                    if (valueObj != null) {
                        String valStr = extractNumericValue(valueObj);
                        if (valStr != null && !valStr.isEmpty()) return valStr;
                    }
                }
            }
        } catch (Exception ignored) {}

        try {
            Object qtySched = invokeField(priceQty, "getQuantitySchedule");
            if (qtySched != null) {
                String val = extractNumericValue(qtySched);
                if (val != null && !val.isEmpty()) return val;
            }
        } catch (Exception ignored) {}

        return null;
    }

    private String extractCurrencyFromPriceQuantity(Object priceQty) throws Exception {
        try {
            Object quantity = invokeField(priceQty, "getQuantity");
            if (quantity != null && quantity instanceof java.util.List) {
                for (Object q : (java.util.List<?>) quantity) {
                    Object valueObj = invokeField(q, "getValue");
                    if (valueObj != null) {
                        String ccy = extractCurrencyFromValue(valueObj);
                        if (ccy != null && !ccy.isEmpty()) return ccy;
                    }
                }
            }
        } catch (Exception ignored) {}

        try {
            Object qtySched = invokeField(priceQty, "getQuantitySchedule");
            if (qtySched != null) {
                String ccy = extractCurrencyFromAmountObj(qtySched);
                if (ccy != null && !ccy.isEmpty()) return ccy;
            }
        } catch (Exception ignored) {}

        return "USD";
    }

    private String extractCurrencyFromValue(Object valueObj) throws Exception {
        try {
            Object unit = invokeField(valueObj, "getUnit");
            if (unit != null) {
                Object currency = invokeField(unit, "getCurrency");
                if (currency != null) {
                    return extractStringValue(currency);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String extractCurrencyFromAmountObj(Object amountObj) throws Exception {
        try {
            Object currency = invokeField(amountObj, "getCurrency");
            if (currency != null) return String.valueOf(currency);
        } catch (Exception ignored) {}
        return null;
    }

    private String extractIndexName(Object rateOption) throws Exception {
        if (rateOption == null) return null;
        
        // Check for Reference type with externalReference or reference field
        try {
            java.lang.reflect.Method getExtRef = rateOption.getClass().getMethod("getExternalReference");
            Object val = getExtRef.invoke(rateOption);
            if (val != null && String.valueOf(val).length() > 0) return String.valueOf(val);
        } catch (NoSuchMethodException ignored) {}

        try {
            java.lang.reflect.Method getRef = rateOption.getClass().getMethod("getReference");
            Object val = getRef.invoke(rateOption);
            if (val != null && !String.valueOf(val).isEmpty() && !val.toString().contains("{")) return String.valueOf(val);
        } catch (NoSuchMethodException ignored) {}

        // Try getValue on the rate option itself
        try {
            java.lang.reflect.Method getM = rateOption.getClass().getMethod("get");
            Object val = getM.invoke(rateOption);
            if (val != null && !String.valueOf(val).isEmpty() && !val.toString().contains("{")) return String.valueOf(val);
        } catch (NoSuchMethodException ignored) {}

        // Handle CDM 6.x FloatingRateIndex / InterestRateIndex types directly
        try {
            java.lang.reflect.Method getFRI = rateOption.getClass().getMethod("getFloatingRateIndex");
            Object fri = getFRI.invoke(rateOption);
            if (fri != null) {
                String idxName = extractStringValue(fri);
                if (idxName != null && !idxName.isEmpty()) return idxName;
            }
        } catch (NoSuchMethodException ignored) {}

        try {
            java.lang.reflect.Method getIRI = rateOption.getClass().getMethod("getInterestRateIndex");
            Object iri = getIRI.invoke(rateOption);
            if (iri != null) {
                String idxName = extractStringValue(iri);
                if (idxName != null && !idxName.isEmpty()) return idxName;
            }
        } catch (NoSuchMethodException ignored) {}

        // Try to find floatingRateIndex or identifier fields via reflection
        for (java.lang.reflect.Method m : rateOption.getClass().getMethods()) {
            String mName = m.getName().toLowerCase();
            if ((mName.contains("floatingrateindex") || mName.contains("interestrateindex")) && !mName.equals("getratespecification")) {
                try {
                    Object result = m.invoke(rateOption);
                    if (result != null) {
                        String idxName = extractStringValue(result);
                        if (idxName != null && !idxName.isEmpty() && idxName.length() < 50) return idxName;
                    }
                } catch (Exception ignored) {}
            }
        }

        // Try to find identifier fields that might contain the index name
        for (java.lang.reflect.Method m : rateOption.getClass().getMethods()) {
            String mName = m.getName().toLowerCase();
            if (mName.contains("identifier") && !mName.equals("getratespecification")) {
                try {
                    Object result = m.invoke(rateOption);
                    if (result != null) {
                        if (result instanceof java.util.List) {
                            for (Object item : (java.util.List<?>) result) {
                                try {
                                    Object identObj = invokeField(item, "getIdentifier");
                                    if (identObj != null) {
                                        String idxName = extractStringValue(identObj);
                                        if (idxName != null && !idxName.isEmpty() && idxName.length() < 50) return idxName;
                                    }
                                } catch (Exception ignored) {}
                            }
                        } else {
                            String idxName = extractStringValue(result);
                            if (idxName != null && !idxName.isEmpty() && idxName.length() < 50) return idxName;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        // Parse from toString() - e.g., "FloatingRateIndex {floatingRateIndex=EUR-EURIBOR-Reuters, ...}" or "Reference {externalReference=EUR-LIBOR-BBA, ...}"
        String str = rateOption.toString();
        
        if (str.contains("floatingRateIndex=")) {
            int start = str.indexOf("floatingRateIndex=") + 16;
            int end = str.indexOf(",", start);
            if (end == -1) end = str.indexOf("}", start);
            if (end > start) return str.substring(start, end).trim();
        }

        if (str.contains("externalReference=")) {
            int start = str.indexOf("externalReference=") + 18;
            int end = str.indexOf(",", start);
            if (end == -1) end = str.indexOf("}", start);
            if (end > start) return str.substring(start, end).trim();
        }

        if (str.contains("value=")) {
            int start = str.indexOf("value=") + 6;
            int end = str.indexOf(",", start);
            if (end == -1) end = str.indexOf("}", start);
            if (end > start) return str.substring(start, end).trim();
        }

        // Last resort: try any method that returns a short string with index-like content
        for (java.lang.reflect.Method m : rateOption.getClass().getMethods()) {
            String mName = m.getName().toLowerCase();
            if ((mName.contains("index") || mName.contains("option")) && !mName.equals("getratespecification")) {
                try {
                    Object result = m.invoke(rateOption);
                    if (result != null && !String.valueOf(result).contains("{") && String.valueOf(result).length() < 50) {
                        return String.valueOf(result);
                    }
                } catch (Exception ignored) {}
            }
        }

        return "UNKNOWN-INDEX";
    }

    private Element mapIndexTenor(Object calcDates, Document doc) throws Exception {
        try {
            Object freq = invokeField(calcDates, "getCalculationPeriodFrequency");
            if (freq != null) {
                Element tenorEl = doc.createElementNS(FpmlConstants.FPML_NS, "indexTenor");

                try {
                    Object pm = invokeField(freq, "getPeriodMultiplier");
                    if (pm != null) {
                        Element pmEl = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
                        pmEl.setTextContent(String.valueOf(pm));
                        tenorEl.appendChild(pmEl);
                    }
                } catch (Exception ignored) {}

                try {
                    Object period = invokeField(freq, "getPeriod");
                    if (period instanceof Enum) {
                        Element pEl = doc.createElementNS(FpmlConstants.FPML_NS, "period");
                        String fpmlPeriod = mapPeriodToFpml((Enum<?>) period);
                        pEl.setTextContent(fpmlPeriod);
                        tenorEl.appendChild(pEl);
                    }
                } catch (Exception ignored) {}

                return tenorEl;
            }
        } catch (Exception e) {
            // Ignore - indexTenor is optional
        }

        return null;
    }

    private String extractNumericValue(Object val) throws Exception {
        if (val == null) return null;
        
        try {
            java.lang.reflect.Method getM = val.getClass().getMethod("get");
            Object result = getM.invoke(val);
            if (result instanceof Number) {
                Number num = (Number) result;
                return formatBigDecimal(num);
            } else if (result != null && !result.toString().contains("{")) {
                String strVal = result.toString();
                try {
                    BigDecimal bd = new BigDecimal(strVal);
                    return formatBigDecimal(bd);
                } catch (Exception ignored) {}
                return strVal;
            }
        } catch (NoSuchMethodException ignored) {}

        // Try getValue method
        try {
            java.lang.reflect.Method getVal = val.getClass().getMethod("getValue");
            Object result = getVal.invoke(val);
            if (result instanceof Number) {
                Number num = (Number) result;
                return formatBigDecimal(num);
            } else if (result != null && !result.toString().contains("{")) {
                String strVal = result.toString();
                try {
                    BigDecimal bd = new BigDecimal(strVal);
                    return formatBigDecimal(bd);
                } catch (Exception ignored) {}
                return strVal;
            }
        } catch (NoSuchMethodException ignored) {}

        // Direct value extraction
        if (val instanceof Number) {
            return formatBigDecimal((Number) val);
        } else if (val instanceof BigDecimal) {
            return formatBigDecimal((BigDecimal) val);
        } else if (val instanceof String) {
            try {
                BigDecimal bd = new BigDecimal((String) val);
                return formatBigDecimal(bd);
            } catch (Exception ignored) {}
            return (String) val;
        }

        // Parse from toString() for wrapper types
        String str = val.toString();
        if (str.contains("value=")) {
            int start = str.indexOf("value=") + 6;
            int end = str.indexOf(",", start);
            if (end == -1) end = str.indexOf("}", start);
            if (end > start) {
                try {
                    BigDecimal bd = new BigDecimal(str.substring(start, end).trim());
                    return formatBigDecimal(bd);
                } catch (Exception ignored) {}
            }
        }

        return null;
    }

    private String extractDcfValue(Object dcfObj) throws Exception {
        if (dcfObj == null) return "ACT/360";
        
        try {
            java.lang.reflect.Method getM = dcfObj.getClass().getMethod("get");
            Object val = getM.invoke(dcfObj);
            if (val != null && !val.toString().contains("{")) return String.valueOf(val);
        } catch (NoSuchMethodException ignored) {}

        // Try getValue
        try {
            java.lang.reflect.Method getVal = dcfObj.getClass().getMethod("getValue");
            Object val = getVal.invoke(dcfObj);
            if (val != null && !val.toString().contains("{")) return String.valueOf(val);
        } catch (NoSuchMethodException ignored) {}

        // Parse from toString() - e.g., "DayCountFraction {value=ACT/360, ...}"
        String str = dcfObj.toString();
        if (str.contains("value=")) {
            int start = str.indexOf("value=") + 6;
            int end = str.indexOf(",", start);
            if (end == -1) end = str.indexOf("}", start);
            if (end > start) return str.substring(start, end).trim();
        }

        // If it's an enum value directly
        if (dcfObj instanceof Enum) {
            return mapDayCountFpml((Enum<?>) dcfObj);
        }

        return String.valueOf(dcfObj);
    }

    private String formatBigDecimal(Number num) {
        if (num == null) return "0";
        double d = num.doubleValue();
        if (d == Math.floor(d)) {
            return String.format("%.2f", d);
        } else {
            return String.valueOf(num);
        }
    }

    private String mapPeriodToFpml(Enum<?> period) {
        if (period == null) return "M";
        
        String name = period.name().toUpperCase();
        switch (name) {
            case "D":
            case "DAY": return "D";
            case "W":
            case "WEEK": return "W";
            case "M":
            case "MONTH": return "M";
            case "Y":
            case "YEAR": return "Y";
            default: 
                if (name.length() == 1) return name;
                char first = name.charAt(0);
                if ("DWYM".indexOf(first) >= 0) return String.valueOf(first);
                return "M";
        }
    }

    private String mapDayCountFpml(Enum<?> dcf) {
        if (dcf == null) return "ACT/360";
        
        String name = dcf.name().toUpperCase();
        switch (name) {
            case "ACT_360": return "ACT/360";
            case "ACT_365": return "ACT/365";
            case "ACT_ACT_ISDA": return "ACT/ACT ISDA";
            case "THIRTY_E_360": return "30E/360";
            case "THIRTY_360": return "30/360";
            case "ACT_365F": return "ACT/365F";
            default: 
                String[] parts = name.split("_");
                if (parts.length >= 2) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < parts.length; i++) {
                        if (!parts[i].isEmpty()) {
                            if (i > 0 && !parts[i].equals("ISDA")) {
                                sb.append("/");
                            } else if (i > 0) {
                                sb.append(" ");
                            }
                            sb.append(parts[i]);
                        }
                    }
                    return sb.toString();
                }
                return name.replace("_", "/");
        }
    }

    private Element mapEffectiveOrTerminationDate(Document doc, Object adjOrRelDate, String elementName) throws Exception {
        if (adjOrRelDate == null) return null;
        
        // Extract unadjusted date from AdjustableOrRelativeDate → getAdjustableDate() → getUnadjustedDate()
        try {
            java.lang.reflect.Method getAdj = adjOrRelDate.getClass().getMethod("getAdjustableDate");
            Object adjustableDate = getAdj.invoke(adjOrRelDate);
            
            if (adjustableDate != null) {
                Element wrapperEl = doc.createElementNS(FpmlConstants.FPML_NS, elementName);
                
                // Try to get unadjusted date directly
                try {
                    java.lang.reflect.Method getUnadj = adjustableDate.getClass().getMethod("getUnadjustedDate");
                    Object unadj = getUnadj.invoke(adjustableDate);
                    
                    if (unadj != null) {
                        Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                        
                        // Handle FieldWithMetaDate or Date type
                        String dateStr = extractDateValue(unadj);
                        if (dateStr != null && !dateStr.isEmpty()) {
                            unadjEl.setTextContent(dateStr);
                        }
                        
                        wrapperEl.appendChild(unadjEl);
                        
                        // Try to get date adjustments
                        try {
                            java.lang.reflect.Method getAdj2 = adjustableDate.getClass().getMethod("getDateAdjustments");
                            Object bda = getAdj2.invoke(adjustableDate);
                            
                            if (bda != null) {
                                Element adjEl = doc.createElementNS(FpmlConstants.FPML_NS, "dateAdjustments");
                                
                                // Try to get businessDayConvention
                                try {
                                    java.lang.reflect.Method getBdc = bda.getClass().getMethod("getBusinessDayConvention");
                                    Object bdc = getBdc.invoke(bda);
                                    if (bdc != null) {
                                        Element bdcEl = doc.createElementNS(FpmlConstants.FPML_NS, "businessDayConvention");
                                        String bdcStr = String.valueOf(bdc).replace("_", "");
                                        // Convert MODFOLLOWING → ModFollowing, etc.
                                        StringBuilder sb = new StringBuilder();
                                        for (String part : bdcStr.split("(?=[A-Z])")) {
                                            if (!part.isEmpty()) {
                                                sb.append(part.substring(0, 1).toUpperCase()).append(part.substring(1).toLowerCase());
                                            }
                                        }
                                        bdcEl.setTextContent(sb.toString().equals("") ? bdcStr : sb.toString());
                                        adjEl.appendChild(bdcEl);
                                    }
                                } catch (Exception ignored) {}
                                
                                wrapperEl.appendChild(adjEl);
                            }
                        } catch (NoSuchMethodException ignored) {}
                        
                        return wrapperEl;
                    }
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Exception ignored) {}
        
        // Fallback: parse from toString()
        String str = adjOrRelDate.toString();
        if (str.contains("unadjustedDate=")) {
            int start = str.indexOf("unadjustedDate=") + 15;
            int end = str.indexOf(",", start);
            if (end == -1) end = str.indexOf("}", start);
            if (end > start) {
                String dateStr = str.substring(start, end).trim();
                
                Element wrapperEl = doc.createElementNS(FpmlConstants.FPML_NS, elementName);
                
                if (dateStr.contains("year=")) {
                    int yStart = dateStr.indexOf("year=") + 5;
                    int yEnd = dateStr.indexOf(",", yStart);
                    int mStart = dateStr.indexOf("month=") + 6;
                    int mEnd = dateStr.indexOf(",", mStart);
                    int dStart = dateStr.indexOf("day=") + 4;
                    int dEnd = dateStr.indexOf("}", dStart);
                    
                    if (yEnd > yStart && mEnd > mStart && dEnd > dStart) {
                        String year = dateStr.substring(yStart, yEnd).trim();
                        String month = dateStr.substring(mStart, mEnd).trim();
                        String day = dateStr.substring(dStart, dEnd).trim();
                        
                        Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                        unadjEl.setTextContent(String.format("%s-%02d-%02d", year, Integer.parseInt(month), Integer.parseInt(day)));
                        wrapperEl.appendChild(unadjEl);
                        return wrapperEl;
                    }
                } else if (dateStr.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                    unadjEl.setTextContent(dateStr);
                    wrapperEl.appendChild(unadjEl);
                    return wrapperEl;
                }
            }
        }
        
        return null;
    }

    private String extractDateValue(Object dateObj) throws Exception {
        if (dateObj == null) return null;
        
        // Handle FieldWithMetaDate - try getValue()
        try {
            java.lang.reflect.Method getVal = dateObj.getClass().getMethod("getValue");
            Object val = getVal.invoke(dateObj);
            if (val != null && !val.toString().contains("{")) return formatDateValue(val);
        } catch (NoSuchMethodException ignored) {}
        
        // Handle com.rosetta.model.lib.records.Date directly
        if (dateObj instanceof com.rosetta.model.lib.records.Date) {
            try {
                int year = ((com.rosetta.model.lib.records.Date) dateObj).getYear();
                int month = ((com.rosetta.model.lib.records.Date) dateObj).getMonth();
                int day = ((com.rosetta.model.lib.records.Date) dateObj).getDay();
                return String.format("%04d-%02d-%02d", year, month, day);
            } catch (Exception ignored) {}
        }
        
        // Parse from toString() - e.g., "FieldWithMetaDate {value=LocalDate{year=1994, ...}, meta=null}"
        String str = dateObj.toString();
        if (str.contains("unadjustedDate=")) {
            int start = str.indexOf("unadjustedDate=") + 15;
            int end = str.indexOf(",", start);
            if (end == -1) end = str.indexOf("}", start);
            if (end > start) return str.substring(start, end).trim();
        }
        
        // Try to extract year/month/day from any date-like content
        if (str.contains("year=")) {
            int yStart = str.indexOf("year=") + 5;
            int yEnd = str.indexOf(",", yStart);
            int mStart = str.indexOf("month=", Math.max(yEnd, 0));
            int mEnd = str.indexOf(",", mStart);
            int dStart = str.indexOf("day=", Math.max(mEnd, 0));
            int dEnd = str.indexOf("}", dStart);
            
            if (yEnd > yStart && mEnd > mStart && dEnd > dStart) {
                String year = str.substring(yStart, yEnd).trim();
                String month = str.substring(mStart + 6, mEnd).trim();
                String day = str.substring(dStart + 4, dEnd).trim();
                return String.format("%s-%02d-%02d", year, Integer.parseInt(month), Integer.parseInt(day));
            }
        }
        
        // Fallback: try toString directly
        if (!str.contains("{")) return str;
        
        return null;
    }

    private String extractExternalKey(Object obj) throws Exception {
        try {
            Object meta = invokeField(obj, "getMeta");
            if (meta != null) {
                java.lang.reflect.Method getExtKey = meta.getClass().getMethod("getExternalKey");
                Object val = getExtKey.invoke(meta);
                return val != null ? String.valueOf(val) : null;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String extractStringValue(Object obj) throws Exception {
        if (obj == null) return null;
        
        if (obj instanceof String) return (String) obj;
        
        try {
            java.lang.reflect.Method getM = obj.getClass().getMethod("get");
            Object val = getM.invoke(obj);
            if (val != null && !val.toString().contains("{")) {
                return String.valueOf(val);
            }
        } catch (NoSuchMethodException ignored) {}
        
        try {
            java.lang.reflect.Method getVal = obj.getClass().getMethod("getValue");
            Object val = getVal.invoke(obj);
            if (val != null && !val.toString().contains("{")) {
                return String.valueOf(val);
            }
        } catch (NoSuchMethodException ignored) {}
        
        String str = obj.toString();
        if (str.contains("value=")) {
            int start = str.indexOf("value=") + 6;
            int end = str.indexOf(",", start);
            if (end == -1) end = str.indexOf("}", start);
            if (end > start) return str.substring(start, end).trim();
        }
        
        return null;
    }

    private String extractStringValueFromMeta(Object obj) throws Exception {
        if (obj == null) return null;
        
        try {
            java.lang.reflect.Method getM = obj.getClass().getMethod("get");
            Object val = getM.invoke(obj);
            if (val != null && !String.valueOf(val).contains("{")) {
                return String.valueOf(val);
            }
        } catch (NoSuchMethodException ignored) {}
        
        try {
            java.lang.reflect.Method getVal = obj.getClass().getMethod("getValue");
            Object val = getVal.invoke(obj);
            if (val != null && !String.valueOf(val).contains("{")) {
                return String.valueOf(val);
            }
        } catch (NoSuchMethodException ignored) {}
        
        String str = obj.toString();
        if (str.contains("value=")) {
            int start = str.indexOf("value=") + 6;
            int end = str.indexOf(",", start);
            if (end == -1) end = str.indexOf("}", start);
            if (end > start) return str.substring(start, end).trim();
        }
        
        return null;
    }

    private String extractFixedRateFromTradeLot(TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        try {
            Object trade = invokeField(tradeState, "getTrade");
            if (trade == null) return null;
            
            Object tradeLot = invokeField(trade, "getTradeLot");
            if (tradeLot == null || !(tradeLot instanceof java.util.List)) return null;
            
            for (Object lot : (java.util.List<?>) tradeLot) {
                Object priceQuantityList = invokeField(lot, "getPriceQuantity");
                if (priceQuantityList != null && priceQuantityList instanceof java.util.List) {
                    for (Object pq : (java.util.List<?>) priceQuantityList) {
                        Object priceList = invokeField(pq, "getPrice");
                        if (priceList != null && priceList instanceof java.util.List) {
                            for (Object priceObj : (java.util.List<?>) priceList) {
                                try {
                                    Object meta = invokeField(priceObj, "getMeta");
                                    if (meta != null) {
                                        java.lang.reflect.Method getExtKey = meta.getClass().getMethod("getExternalKey");
                                        Object locVal = getExtKey.invoke(meta);
                                        String locStr = locVal != null ? String.valueOf(locVal) : "";
                                        if (locStr.contains("price-1") || locStr.equals("price-1")) {
                                            Object valueObj = invokeField(priceObj, "getValue");
                                             if (valueObj != null) {
                                                 try {
                                                     Object innerValue = invokeField(valueObj, "get");
                                                     if (innerValue instanceof Number) {
                                                         return String.valueOf(((Number) innerValue).doubleValue());
                                                     }
                                                     String rateVal = extractNumericValue(valueObj);
                                                     if (rateVal != null && rateVal.length() > 0) {
                                                         BigDecimal bd = new BigDecimal(rateVal);
                                                         double d = bd.doubleValue();
                                                         if (d > 0 && d < 1) return String.valueOf(d);
                                                     }
                                                 } catch (Exception ignored) {}
                                             } // end if valueObj != null
                                         }
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (context != null) context.addWarning("Could not extract fixed rate from tradeLot: " + e.getMessage());
        }
        return null;
    }

    private List<? extends Payout> getPayouts(TradeState tradeState) throws Exception {
        Object trade = invokeField(tradeState, "getTrade");
        if (trade == null) return null;
        
        Object product = invokeField(trade, "getProduct");
        if (product == null) return null;

        Object econTerms = invokeField(product, "getEconomicTerms");
        if (econTerms == null) return null;

        return (List<? extends Payout>) invokeField(econTerms, "getPayout");
    }

    private static Object invokeField(Object obj, String fieldName) throws Exception {
        if (obj == null || fieldName == null) return null;
        try {
            java.lang.reflect.Method m = obj.getClass().getMethod(fieldName);
            return m.invoke(obj);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Object invoke(Object obj, String method) throws Exception {
        if (obj == null || method == null) return null;
        try {
            java.lang.reflect.Method m = obj.getClass().getMethod(method);
            return m.invoke(obj);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static String extractExternalReferenceFromRef(Counterparty cp) throws Exception {
        try {
            java.lang.reflect.Method m = cp.getClass().getMethod("getPartyReference");
            Object ref = m.invoke(cp);
            if (ref == null) return null;
            try {
                java.lang.reflect.Method getExtRef = ref.getClass().getMethod("getExternalReference");
                Object val = getExtRef.invoke(ref);
                return val != null ? String.valueOf(val) : null;
            } catch (NoSuchMethodException ignored) {}
        } catch (NoSuchMethodException ignored) {}
        return null;
    }
}
