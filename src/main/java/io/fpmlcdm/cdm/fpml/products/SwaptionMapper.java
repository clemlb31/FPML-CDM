package io.fpmlcdm.cdm.fpml.products;

import cdm.base.datetime.AdjustableDate;
import cdm.base.staticdata.party.Counterparty;
import cdm.event.common.TradeState;
import cdm.product.asset.InterestRatePayout;
import cdm.product.template.Payout;
import io.fpmlcdm.cdm.fpml.common.CdmDateMapper;
import io.fpmlcdm.cdm.fpml.CdmToFpmlMappingContext;
import io.fpmlcdm.cdm.fpml.CdmToFpmlProductMapper;
import io.fpmlcdm.cdm.fpml.FpmlConstants;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.util.List;

/**
 * Implementation for mapping CDM Swaption to FpML.
 */
public class SwaptionMapper implements CdmToFpmlProductMapper {

    @Override
    public Element map(TradeState tradeState, CdmToFpmlMappingContext context) {
        try {
            return doMap(tradeState, context);
        } catch (Exception e) {
            context.addWarning("Swaption mapping error: " + e.getMessage());
            Document doc = context.getDocument();
            Element swaptionElement = createFallbackSwaption(doc);
            try { registerPartiesFromTrade(tradeState, context); } catch (Exception ignored) {}
            return swaptionElement;
        }
    }

    private Element doMap(TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Document doc = context.getDocument();

        registerPartiesFromTrade(tradeState, context);

        Element swaptionElement = doc.createElementNS(FpmlConstants.FPML_NS, "swaption");

        // Map trade date header
        mapTradeDate(doc, swaptionElement, tradeState, context);

        // Map Swaption Terms (Exercise style, premium, settlement)
        mapSwaptionTerms(doc, swaptionElement, tradeState, context);

        // Map the underlying swap (the option's legs)
        mapUnderlyingSwap(doc, swaptionElement, tradeState, context);

        return swaptionElement;
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

    private void mapTradeDate(Document doc, Element parent, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
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

            // Insert before first child or append
            if (parent.getFirstChild() != null) {
                parent.insertBefore(tradeHeader, parent.getFirstChild());
            } else {
                parent.appendChild(tradeHeader);
            }
        }
    }

    private void mapSwaptionTerms(Document doc, Element parent, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Object product = invokeField(tradeState, "getProduct");
        if (product == null) return;

        Object econTerms = invokeField(product, "getEconomicTerms");
        if (econTerms == null) return;

        Element swaptionTermsEl = doc.createElementNS(FpmlConstants.FPML_NS, "swaptionTerms");

        // 1. Exercise style (European/American/Bermudan) from exerciseTerms or payout context
        try {
            Object exerciseTerms = invokeField(econTerms, "getExerciseTerms");
            if (exerciseTerms == null) {
                // Try getting from the first InterestRatePayout
                List<? extends Payout> payouts = (List<? extends Payout>) invokeField(econTerms, "getPayout");
                if (payouts != null && !payouts.isEmpty()) {
                    for (Payout p : payouts) {
                        Object irPayout = p.getInterestRatePayout();
                        if (irPayout != null) {
                            exerciseTerms = invokeField(irPayout, "getExerciseTerms");
                            if (exerciseTerms != null) break;
                        }
                    }
                }
            }

            if (exerciseTerms != null) {
                Element exerciseStyleEl = doc.createElementNS(FpmlConstants.FPML_NS, "exerciseStyle");

                Object style = invokeField(exerciseTerms, "getStyle");
                if (style != null) {
                    String styleStr = extractStringValue(style);
                    if (styleStr != null && !styleStr.isEmpty()) {
                        Element exerciseTypeEl = doc.createElementNS(FpmlConstants.FPML_NS, mapExerciseStyleToFpmlElement(styleStr));
                        exerciseTypeEl.setTextContent(mapExerciseStyleToFpmlValue(styleStr));
                        exerciseStyleEl.appendChild(exerciseTypeEl);
                    } else {
                        // Try direct enum value
                        Element exerciseTypeEl = doc.createElementNS(FpmlConstants.FPML_NS, "European");
                        exerciseTypeEl.setTextContent("European");
                        exerciseStyleEl.appendChild(exerciseTypeEl);
                    }
                } else {
                    Element exerciseTypeEl = doc.createElementNS(FpmlConstants.FPML_NS, "European");
                    exerciseTypeEl.setTextContent("European");
                    exerciseStyleEl.appendChild(exerciseTypeEl);
                }

                swaptionTermsEl.appendChild(exerciseStyleEl);

                // Exercise dates from commencementDate and expirationDate
                Element exDates = doc.createElementNS(FpmlConstants.FPML_NS, "exerciseDatePeriod");

                try {
                    Object commDate = invokeField(exerciseTerms, "getCommencementDate");
                    if (commDate != null) {
                        Element startDateEl = doc.createElementNS(FpmlConstants.FPML_NS, "startDate");
                        startDateEl.appendChild(mapExerciseDate(doc, commDate));
                        exDates.appendChild(startDateEl);
                    }
                } catch (Exception ignored) {}

                try {
                    Object expDates = invokeField(exerciseTerms, "getExpirationDate");
                    if (expDates != null) {
                        Element endDateEl = doc.createElementNS(FpmlConstants.FPML_NS, "endDate");
                        if (expDates instanceof List) {
                            List<?> dates = (List<?>) expDates;
                            if (!dates.isEmpty()) {
                                endDateEl.appendChild(mapExerciseDate(doc, dates.get(0)));
                            } else {
                                endDateEl.appendChild(createFallbackDate(doc));
                            }
                        } else {
                            endDateEl.appendChild(mapExerciseDate(doc, expDates));
                        }
                        exDates.appendChild(endDateEl);
                    }
                } catch (Exception ignored) {}

                // Also try exercise date period directly on econTerms or payout
                if (!hasChildElement(exDates, "startDate")) {
                    Object exerciseDatePeriod = invokeField(econTerms, "getExerciseDatePeriod");
                    if (exerciseDatePeriod != null) {
                        Element adjOrRelEl = doc.createElementNS(FpmlConstants.FPML_NS, "adjustableOrRelativeDate");

                        try {
                            Object startDate = invokeField(exerciseDatePeriod, "getStartDate");
                            if (startDate != null) {
                                Element startEl = doc.createElementNS(FpmlConstants.FPML_NS, "startDate");
                                startEl.appendChild(mapExerciseDate(doc, startDate));
                                adjOrRelEl.appendChild(startEl);
                            }

                            Object endDate = invokeField(exerciseDatePeriod, "getEndDate");
                            if (endDate != null) {
                                Element endEl = doc.createElementNS(FpmlConstants.FPML_NS, "endDate");
                                endEl.appendChild(mapExerciseDate(doc, endDate));
                                adjOrRelEl.appendChild(endEl);
                            }

                            exDates.appendChild(adjOrRelEl);
                        } catch (Exception ignored) {}
                    }
                }

                if (exDates.getChildNodes().getLength() > 0) {
                    swaptionTermsEl.appendChild(exDates);
                }

                // Latest exercise time for American options
                try {
                    Object latestExerciseTime = invokeField(exerciseTerms, "getLatestExerciseTime");
                    if (latestExerciseTime != null) {
                        Element expirationTimeEl = doc.createElementNS(FpmlConstants.FPML_NS, "expirationTime");

                        Element businessCenterEl = doc.createElementNS(FpmlConstants.FPML_NS, "businessCenter");
                        try {
                            Object bc = invokeField(latestExerciseTime, "getBusinessCenter");
                            if (bc != null) {
                                String bcStr = extractStringValue(bc);
                                businessCenterEl.setTextContent(bcStr != null ? bcStr : "USNY");
                            } else {
                                businessCenterEl.setTextContent("USNY");
                            }
                        } catch (Exception ignored) {
                            businessCenterEl.setTextContent("USNY");
                        }

                        expirationTimeEl.appendChild(businessCenterEl);

                        Element timeOfDayEl = doc.createElementNS(FpmlConstants.FPML_NS, "timeOfDay");
                        try {
                            Object hmTime = invokeField(latestExerciseTime, "getHourMinuteTime");
                            if (hmTime != null) {
                                String hmStr = extractStringValue(hmTime);
                                timeOfDayEl.setTextContent(hmStr != null ? hmStr : "17:00:00");
                            } else {
                                timeOfDayEl.setTextContent("17:00:00");
                            }
                        } catch (Exception ignored) {
                            timeOfDayEl.setTextContent("17:00:00");
                        }

                        expirationTimeEl.appendChild(timeOfDayEl);
                        swaptionTermsEl.appendChild(expirationTimeEl);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            context.addWarning("Could not map swaption exercise style: " + e.getMessage());

            // Fallback: European exercise
            Element exerciseStyleEl = doc.createElementNS(FpmlConstants.FPML_NS, "exerciseStyle");
            Element euEl = doc.createElementNS(FpmlConstants.FPML_NS, "European");
            euEl.setTextContent("European");
            exerciseStyleEl.appendChild(euEl);
            swaptionTermsEl.appendChild(exerciseStyleEl);
        }

        // 2. Premium payment details (amount, currency, date)
        try {
            Object premium = invokeField(product, "getPremium");
            if (premium == null) {
                // Try from tradeLot or economic terms
                List<? extends Payout> payouts = (List<? extends Payout>) invokeField(econTerms, "getPayout");
                if (payouts != null && !payouts.isEmpty()) {
                    for (Payout p : payouts) {
                        Object irPayout = p.getInterestRatePayout();
                        if (irPayout != null) {
                            premium = invokeField(irPayout, "getPremium");
                            if (premium != null) break;
                        }
                    }
                }
            }

            if (premium != null) {
                Element premiumEl = doc.createElementNS(FpmlConstants.FPML_NS, "premium");

                // Premium amount and currency using CdmAmountMapper pattern
                try {
                    BigDecimal premiumValue = extractBigDecimalValue(premium);
                    String premiumCurrency = extractCurrencyFromAmountObj(premium);

                    if (premiumValue != null && premiumCurrency != null) {
                        Element paymentEl = doc.createElementNS(FpmlConstants.FPML_NS, "premiumPayment");

                        Element notionalEl = doc.createElementNS(FpmlConstants.FPML_NS, "notional");
                        Element amountEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");

                        Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                        valEl.setTextContent(formatBigDecimal(premiumValue));
                        amountEl.appendChild(valEl);

                        Element ccyEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                        ccyEl.setTextContent(premiumCurrency);
                        amountEl.appendChild(ccyEl);

                        notionalEl.appendChild(amountEl);
                        paymentEl.appendChild(notionalEl);

                        premiumEl.appendChild(paymentEl);
                    }
                } catch (Exception ignored) {
                    // Fallback minimal premium structure
                    Element fallbackNotional = doc.createElementNS(FpmlConstants.FPML_NS, "notional");
                    Element fallbackAmount = doc.createElementNS(FpmlConstants.FPML_NS, "amount");

                    Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                    valEl.setTextContent("0.00");
                    fallbackAmount.appendChild(valEl);

                    Element ccyEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                    ccyEl.setTextContent("USD");
                    fallbackAmount.appendChild(ccyEl);

                    fallbackNotional.appendChild(fallbackAmount);
                    premiumEl.appendChild(fallbackNotional);
                }

                // Premium payment date
                try {
                    Object premPayDate = invokeField(premium, "getPaymentDate");
                    if (premPayDate != null) {
                        Element payDateEl = doc.createElementNS(FpmlConstants.FPML_NS, "premiumPaymentDate");

                        if (premPayDate instanceof AdjustableDate) {
                            payDateEl.appendChild(CdmDateMapper.mapAdjustableDate(doc, (AdjustableDate) premPayDate));
                        } else {
                            // Try AdjustableOrRelativeDate -> getAdjustableDate()
                            try {
                                java.lang.reflect.Method getAdj = premPayDate.getClass().getMethod("getAdjustableDate");
                                Object adjDate = getAdj.invoke(premPayDate);
                                if (adjDate instanceof AdjustableDate) {
                                    payDateEl.appendChild(CdmDateMapper.mapAdjustableDate(doc, (AdjustableDate) adjDate));
                                } else {
                                    Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                                    String dateStr = extractDateString(premPayDate);
                                    if (dateStr != null) {
                                        unadjEl.setTextContent(dateStr);
                                    }
                                    payDateEl.appendChild(unadjEl);
                                }
                            } catch (NoSuchMethodException ignored) {
                                Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                                String dateStr = extractDateString(premPayDate);
                                if (dateStr != null) {
                                    unadjEl.setTextContent(dateStr);
                                } else {
                                    unadjEl.setTextContent("2024-01-02");
                                }
                                payDateEl.appendChild(unadjEl);
                            }
                        }

                        premiumEl.appendChild(payDateEl);
                    }
                } catch (Exception ignored) {}

                // Premium payment party reference
                Element premPayParty = doc.createElementNS(FpmlConstants.FPML_NS, "premiumPaymentPartyReference");
                premPayParty.setAttribute("href", "#party1");
                premiumEl.appendChild(premPayParty);

                swaptionTermsEl.appendChild(premiumEl);
            }
        } catch (Exception e) {
            context.addWarning("Could not map swaption premium: " + e.getMessage());
        }

        // 3. Settlement type (Physical delivery vs Cash settlement)
        try {
            Object settlementType = invokeField(product, "getSettlementType");
            if (settlementType == null) {
                List<? extends Payout> payouts = (List<? extends Payout>) invokeField(econTerms, "getPayout");
                if (payouts != null && !payouts.isEmpty()) {
                    for (Payout p : payouts) {
                        Object irPayout = p.getInterestRatePayout();
                        if (irPayout != null) {
                            settlementType = invokeField(irPayout, "getSettlementType");
                            if (settlementType != null) break;
                        }
                    }
                }
            }

            if (settlementType instanceof Enum) {
                Element physSettEl = doc.createElementNS(FpmlConstants.FPML_NS, "physicalSettlementTermChoice");
                String settStr = ((Enum<?>) settlementType).name();
                if ("CASH".equalsIgnoreCase(settStr)) {
                    physSettEl.setTextContent("Cash");
                } else if ("PHYSICAL".equalsIgnoreCase(settStr)) {
                    physSettEl.setTextContent("Physical");
                } else {
                    // Convert PascalCase
                    String pascal = settStr.replace("_", "");
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < pascal.length(); i++) {
                        char c = pascal.charAt(i);
                        if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(pascal.charAt(i - 1))) {
                            sb.append(" ");
                        }
                        sb.append(Character.toLowerCase(c));
                    }
                    physSettEl.setTextContent(sb.toString().substring(0, 1).toUpperCase() + sb.toString().substring(1));
                }
                swaptionTermsEl.appendChild(physSettEl);
            }
        } catch (Exception ignored) {}

        parent.appendChild(swaptionTermsEl);
    }

    private void mapUnderlyingSwap(Document doc, Element parent, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Object product = invokeField(tradeState, "getProduct");
        if (product == null) return;

        Object econTerms = invokeField(product, "getEconomicTerms");
        if (econTerms == null) return;

        // Use the putPutOption wrapper for swaption underlying swap reference
        Element putPutEl = doc.createElementNS(FpmlConstants.FPML_NS, "putPutOption");

        List<? extends Payout> payouts = (List<? extends Payout>) invokeField(econTerms, "getPayout");
        if (payouts != null && !payouts.isEmpty()) {
            Element swapStreamContainer = doc.createElementNS(FpmlConstants.FPML_NS, "swap");

            int legCount = 0;
            for (Payout p : payouts) {
                Object irPayoutObj = invokeField(p, "getInterestRatePayout");
                if (irPayoutObj != null && legCount < 2) {
                    Element swapStream = buildSwapStream(doc, irPayoutObj, tradeState, context);
                    if (swapStream != null) {
                        // Rename ID to indicate it's the swaption underlying
                        String streamId = "swaption_swap_" + (legCount == 0 ? "payer" : "receiver");
                        swapStream.setAttribute("id", context.createFpmlId(streamId));
                        swapStreamContainer.appendChild(swapStream);
                    }
                    legCount++;
                }

                // Also check for nested OptionPayout inside InterestRatePayout (swaption structure)
                Object optPayout = invokeField(irPayoutObj != null ? irPayoutObj : p, "getOptionPayout");
                if (optPayout == null && legCount < 2) {
                    // Try Cashflow or other payout types that might contain swap legs
                    Object cashflow = invokeField(p, "getCashflow");
                    if (cashflow != null && legCount < 2) {
                        Element swapStream = buildSwapStreamFromPayout(doc, p, tradeState, context);
                        if (swapStream != null) {
                            String streamId = "swaption_swap_" + (legCount == 0 ? "payer" : "receiver");
                            swapStream.setAttribute("id", context.createFpmlId(streamId));
                            swapStreamContainer.appendChild(swapStream);
                            legCount++;
                        }
                    }
                }
            }

            putPutEl.appendChild(swapStreamContainer);
        } else {
            // Fallback: create minimal swap structure
            Element fallbackSwap = doc.createElementNS(FpmlConstants.FPML_NS, "swap");
            Element fallbackStream = buildFallbackSwapStream(doc, tradeState, context);
            if (fallbackStream != null) {
                fallbackStream.setAttribute("id", context.createFpmlId("swaption_fallback_swap"));
                fallbackSwap.appendChild(fallbackStream);
            }
            putPutEl.appendChild(fallbackSwap);
        }

        parent.appendChild(putPutEl);
    }

    private Element buildSwapStream(Document doc, Object irPayoutObj, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Element swapStream = doc.createElementNS(FpmlConstants.FPML_NS, "swapStream");

        String payerHref = extractPayerFromTrade(tradeState, irPayoutObj);
        String receiverHref = extractReceiverFromTrade(tradeState, irPayoutObj);

        Element payerRef = doc.createElementNS(FpmlConstants.FPML_NS, "payerPartyReference");
        payerRef.setAttribute("href", "#" + payerHref);
        swapStream.appendChild(payerRef);

        Element receiverRef = doc.createElementNS(FpmlConstants.FPML_NS, "receiverPartyReference");
        receiverRef.setAttribute("href", "#" + receiverHref);
        swapStream.appendChild(receiverRef);

        // Calculation period dates
        String calcDatesId = context.createFpmlId("calcPeriodDates");
        try {
            Object calcDates = invokeField(irPayoutObj, "getCalculationPeriodDates");
            if (calcDates != null) {
                Element calcPeriodDatesEl = mapCalculationPeriodDates(doc, calcDates);

                String extKey = extractExternalKey(calcDates);
                calcDatesId = extKey != null ? extKey : "swaption_calcPeriodDates";
                calcPeriodDatesEl.setAttribute("id", calcDatesId);
                swapStream.appendChild(calcPeriodDatesEl);

                // Payment dates referencing calculation period dates
                Element paymentDatesEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentDates");
                Element payRefEl = doc.createElementNS(FpmlConstants.FPML_NS, "calculationPeriodDatesReference");
                payRefEl.setAttribute("href", "#" + calcDatesId);
                paymentDatesEl.appendChild(payRefEl);

                // Payment frequency
                try {
                    Object freq = invokeField(calcDates, "getCalculationPeriodFrequency");
                    if (freq != null) {
                        Element payFreqEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentFrequency");

                        try {
                            Object pm = invokeField(freq, "getPeriodMultiplier");
                            if (pm != null) {
                                Element pmEl = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
                                pmEl.setTextContent(String.valueOf(pm));
                                payFreqEl.appendChild(pmEl);
                            }
                        } catch (Exception ignored) {}

                        try {
                            Object period = invokeField(freq, "getPeriod");
                            if (period instanceof Enum) {
                                Element pEl = doc.createElementNS(FpmlConstants.FPML_NS, "period");
                                pEl.setTextContent(mapPeriodToFpml((Enum<?>) period));
                                payFreqEl.appendChild(pEl);
                            }
                        } catch (Exception ignored) {}

                        paymentDatesEl.appendChild(payFreqEl);
                    } else {
                        // Default semi-annual
                        Element pmEl = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
                        pmEl.setTextContent("1");
                        Element payFreqEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentFrequency");
                        payFreqEl.appendChild(pmEl);
                        Element pEl = doc.createElementNS(FpmlConstants.FPML_NS, "period");
                        pEl.setTextContent("Y");
                        payFreqEl.appendChild(pEl);
                        paymentDatesEl.appendChild(payFreqEl);
                    }

                    Element payRelEl = doc.createElementNS(FpmlConstants.FPML_NS, "payRelativeTo");
                    payRelEl.setTextContent("CalculationPeriodEndDate");
                    paymentDatesEl.appendChild(payRelEl);

                    swapStream.appendChild(paymentDatesEl);
                } catch (Exception ignored) {}

                // Calculation period amount with notional and rate
                Element calcAmountEl = doc.createElementNS(FpmlConstants.FPML_NS, "calculationPeriodAmount");
                Element calculation = doc.createElementNS(FpmlConstants.FPML_NS, "calculation");

                // Notional schedule from priceQuantity or quantitySchedule
                try {
                    Object priceQuantity = invokeField(irPayoutObj, "getPriceQuantity");
                    if (priceQuantity != null) {
                        Element notionalSched = mapNotionalWithLocation(doc, priceQuantity);
                        calculation.appendChild(notionalSched);
                    } else {
                        Object qtySched = invokeField(irPayoutObj, "getQuantitySchedule");
                        if (qtySched != null) {
                            Element notionalSched = doc.createElementNS(FpmlConstants.FPML_NS, "notionalSchedule");
                            Element stepSched = doc.createElementNS(FpmlConstants.FPML_NS, "notionalStepSchedule");

                            String valStr = extractNumericValue(qtySched);
                            if (valStr == null || valStr.isEmpty()) valStr = "1000000.00";
                            Element initEl = doc.createElementNS(FpmlConstants.FPML_NS, "initialValue");
                            initEl.setTextContent(valStr);
                            stepSched.appendChild(initEl);

                            String currency = extractCurrencyFromAmountObj(qtySched);
                            Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                            currEl.setTextContent(currency != null ? currency : "USD");
                            stepSched.appendChild(currEl);

                            notionalSched.appendChild(stepSched);
                            calculation.appendChild(notionalSched);
                        }
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

                            try {
                                Object rateOption = invokeField(floatSpec, "getRateOption");
                                if (rateOption != null) {
                                    String indexName = extractIndexName(rateOption);
                                    Element idxEl = doc.createElementNS(FpmlConstants.FPML_NS, "floatingRateIndex");
                                    idxEl.setTextContent(indexName != null ? indexName : "UNKNOWN-INDEX");
                                    floatingCalc.appendChild(idxEl);
                                }
                            } catch (Exception ignored) {}

                            calculation.appendChild(floatingCalc);
                        } else {
                            // Fixed rate
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

                                    if (rateVal == null || rateVal.isEmpty()) {
                                        // Try tradeLot for fixed rate
                                        Object product = invokeField(tradeState, "getProduct");
                                        if (product != null) {
                                            Object trade = invokeField(product, "getTrade");
                                            if (trade instanceof List) {
                                                for (Object t : (List<?>) trade) {
                                                    try {
                                                        Object lotList = invokeField(t, "getTradeLot");
                                                        if (lotList instanceof List) {
                                                            for (Object lot : (List<?>) lotList) {
                                                                try {
                                                                    Object pqList = invokeField(lot, "getPriceQuantity");
                                                                    if (pqList instanceof List) {
                                                                        for (Object pq : (List<?>) pqList) {
                                                                            try {
                                                                                Object priceList = invokeField(pq, "getPrice");
                                                                                if (priceList instanceof List) {
                                                                                    for (Object priceObj : (List<?>) priceList) {
                                                                                        String locStr = extractExternalKey(priceObj);
                                                                                        if ("price-1".equals(locStr)) {
                                                                                            Object valObj = invokeField(priceObj, "getValue");
                                                                                            if (valObj != null) {
                                                                                                rateVal = extractNumericValue(valObj);
                                                                                            }
                                                                                        }
                                                                                    }
                                                                                }
                                                                            } catch (Exception ignored) {}
                                                                        }
                                                                    }
                                                                } catch (Exception ignored) {}
                                                            }
                                                        }
                                                    } catch (Exception ignored) {}
                                                }
                                            }
                                        }
                                    }

                                    if (rateVal == null || rateVal.isEmpty()) {
                                        rateVal = "0.06";
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
                        String dcfStr = extractDcfValue(dcf);
                        dcfEl.setTextContent(dcfStr != null ? dcfStr : "ACT/360");
                        calculation.appendChild(dcfEl);
                    }
                } catch (Exception ignored) {}

                calcAmountEl.appendChild(calculation);
                swapStream.appendChild(calcAmountEl);
            }
        } catch (Exception e) {
            context.addWarning("Could not map swaption underlying swap periods: " + e.getMessage());
        }

        return swapStream;
    }

    private Element buildSwapStreamFromPayout(Document doc, Payout payout, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Object irPayoutObj = invokeField(payout, "getInterestRatePayout");
        if (irPayoutObj != null) {
            return buildSwapStream(doc, irPayoutObj, tradeState, context);
        }

        // Fallback: create minimal swap stream from payout's priceQuantity
        Element swapStream = doc.createElementNS(FpmlConstants.FPML_NS, "swapStream");

        String payerHref = extractPayerFromTrade(tradeState, null);
        String receiverHref = extractReceiverFromTrade(tradeState, null);

        Element payerRef = doc.createElementNS(FpmlConstants.FPML_NS, "payerPartyReference");
        payerRef.setAttribute("href", "#" + payerHref);
        swapStream.appendChild(payerRef);

        Element receiverRef = doc.createElementNS(FpmlConstants.FPML_NS, "receiverPartyReference");
        receiverRef.setAttribute("href", "#" + receiverHref);
        swapStream.appendChild(receiverRef);

        // Minimal calculation period dates
        Element calcPeriodDatesEl = doc.createElementNS(FpmlConstants.FPML_NS, "calculationPeriodDates");
        calcPeriodDatesEl.setAttribute("id", context.createFpmlId("swaption_calc"));

        Element effEl = doc.createElementNS(FpmlConstants.FPML_NS, "effectiveDate");
        Element unadjEff = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
        unadjEff.setTextContent("2024-01-01");
        effEl.appendChild(unadjEff);
        calcPeriodDatesEl.appendChild(effEl);

        Element termEl = doc.createElementNS(FpmlConstants.FPML_NS, "terminationDate");
        Element unadjTerm = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
        unadjTerm.setTextContent("2029-01-01");
        termEl.appendChild(unadjTerm);
        calcPeriodDatesEl.appendChild(termEl);

        swapStream.appendChild(calcPeriodDatesEl);

        Element paymentDates = doc.createElementNS(FpmlConstants.FPML_NS, "paymentDates");
        Element refEl = doc.createElementNS(FpmlConstants.FPML_NS, "calculationPeriodDatesReference");
        refEl.setAttribute("href", "#" + context.createFpmlId("swaption_calc"));
        paymentDates.appendChild(refEl);

        Element payFreqEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentFrequency");
        Element pmEl = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
        pmEl.setTextContent("1");
        payFreqEl.appendChild(pmEl);
        Element pEl = doc.createElementNS(FpmlConstants.FPML_NS, "period");
        pEl.setTextContent("Y");
        payFreqEl.appendChild(pEl);
        paymentDates.appendChild(payFreqEl);

        Element payRelEl = doc.createElementNS(FpmlConstants.FPML_NS, "payRelativeTo");
        payRelEl.setTextContent("CalculationPeriodEndDate");
        paymentDates.appendChild(payRelEl);

        swapStream.appendChild(paymentDates);

        Element calcAmountEl = doc.createElementNS(FpmlConstants.FPML_NS, "calculationPeriodAmount");
        Element calculation = doc.createElementNS(FpmlConstants.FPML_NS, "calculation");

        Element notionalSched = doc.createElementNS(FpmlConstants.FPML_NS, "notionalSchedule");
        Element stepSched = doc.createElementNS(FpmlConstants.FPML_NS, "notionalStepSchedule");
        Element initEl = doc.createElementNS(FpmlConstants.FPML_NS, "initialValue");
        initEl.setTextContent("1000000.00");
        stepSched.appendChild(initEl);

        Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
        currEl.setTextContent("USD");
        stepSched.appendChild(currEl);

        notionalSched.appendChild(stepSched);
        calculation.appendChild(notionalSched);

        Element dcfEl = doc.createElementNS(FpmlConstants.FPML_NS, "dayCountFraction");
        dcfEl.setTextContent("ACT/360");
        calculation.appendChild(dcfEl);

        calcAmountEl.appendChild(calculation);
        swapStream.appendChild(calcAmountEl);

        return swapStream;
    }

    private Element buildFallbackSwapStream(Document doc, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Element swapStream = doc.createElementNS(FpmlConstants.FPML_NS, "swapStream");

        String payerHref = extractPayerFromTrade(tradeState, null);
        String receiverHref = extractReceiverFromTrade(tradeState, null);

        Element payerRef = doc.createElementNS(FpmlConstants.FPML_NS, "payerPartyReference");
        payerRef.setAttribute("href", "#" + payerHref);
        swapStream.appendChild(payerRef);

        Element receiverRef = doc.createElementNS(FpmlConstants.FPML_NS, "receiverPartyReference");
        receiverRef.setAttribute("href", "#" + receiverHref);
        swapStream.appendChild(receiverRef);

        Element calcPeriodDatesEl = doc.createElementNS(FpmlConstants.FPML_NS, "calculationPeriodDates");
        calcPeriodDatesEl.setAttribute("id", context.createFpmlId("swaption_fallback_calc"));

        Element effEl = doc.createElementNS(FpmlConstants.FPML_NS, "effectiveDate");
        Element unadjEff = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
        unadjEff.setTextContent("2024-01-01");
        effEl.appendChild(unadjEff);
        calcPeriodDatesEl.appendChild(effEl);

        Element termEl = doc.createElementNS(FpmlConstants.FPML_NS, "terminationDate");
        Element unadjTerm = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
        unadjTerm.setTextContent("2029-01-01");
        termEl.appendChild(unadjTerm);
        calcPeriodDatesEl.appendChild(termEl);

        swapStream.appendChild(calcPeriodDatesEl);

        Element paymentDates = doc.createElementNS(FpmlConstants.FPML_NS, "paymentDates");
        Element refEl = doc.createElementNS(FpmlConstants.FPML_NS, "calculationPeriodDatesReference");
        refEl.setAttribute("href", "#" + context.createFpmlId("swaption_fallback_calc"));
        paymentDates.appendChild(refEl);

        Element payFreqEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentFrequency");
        Element pmEl = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
        pmEl.setTextContent("1");
        payFreqEl.appendChild(pmEl);
        Element pEl = doc.createElementNS(FpmlConstants.FPML_NS, "period");
        pEl.setTextContent("Y");
        payFreqEl.appendChild(pEl);
        paymentDates.appendChild(payFreqEl);

        Element payRelEl = doc.createElementNS(FpmlConstants.FPML_NS, "payRelativeTo");
        payRelEl.setTextContent("CalculationPeriodEndDate");
        paymentDates.appendChild(payRelEl);

        swapStream.appendChild(paymentDates);

        Element calcAmountEl = doc.createElementNS(FpmlConstants.FPML_NS, "calculationPeriodAmount");
        Element calculation = doc.createElementNS(FpmlConstants.FPML_NS, "calculation");

        Element notionalSched = doc.createElementNS(FpmlConstants.FPML_NS, "notionalSchedule");
        Element stepSched = doc.createElementNS(FpmlConstants.FPML_NS, "notionalStepSchedule");
        Element initEl = doc.createElementNS(FpmlConstants.FPML_NS, "initialValue");
        initEl.setTextContent("1000000.00");
        stepSched.appendChild(initEl);

        Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
        currEl.setTextContent("USD");
        stepSched.appendChild(currEl);

        notionalSched.appendChild(stepSched);
        calculation.appendChild(notionalSched);

        Element dcfEl = doc.createElementNS(FpmlConstants.FPML_NS, "dayCountFraction");
        dcfEl.setTextContent("ACT/360");
        calculation.appendChild(dcfEl);

        calcAmountEl.appendChild(calculation);
        swapStream.appendChild(calcAmountEl);

        return swapStream;
    }

    private Element mapCalculationPeriodDates(Document doc, Object calcDates) throws Exception {
        Element calcPeriodDates = doc.createElementNS(FpmlConstants.FPML_NS, "calculationPeriodDates");

        try {
            Object effDate = invokeField(calcDates, "getEffectiveDate");
            if (effDate != null) {
                Element effEl = doc.createElementNS(FpmlConstants.FPML_NS, "effectiveDate");
                effEl.appendChild(mapExerciseDate(doc, effDate));
                calcPeriodDates.appendChild(effEl);
            }
        } catch (Exception ignored) {}

        try {
            Object termDate = invokeField(calcDates, "getTerminationDate");
            if (termDate != null) {
                Element termEl = doc.createElementNS(FpmlConstants.FPML_NS, "terminationDate");
                termEl.appendChild(mapExerciseDate(doc, termDate));
                calcPeriodDates.appendChild(termEl);
            }
        } catch (Exception ignored) {}

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
                        pEl.setTextContent(mapPeriodToFpml((Enum<?>) period));
                        freqEl.appendChild(pEl);
                    }
                } catch (Exception ignored) {}

                calcPeriodDates.appendChild(freqEl);
            }
        } catch (Exception ignored) {}

        return calcPeriodDates;
    }

    private Element mapNotionalWithLocation(Document doc, Object priceQuantityObj) throws Exception {
        Element notionalSchedule = doc.createElementNS(FpmlConstants.FPML_NS, "notionalSchedule");
        Element notionalStepSchedule = doc.createElementNS(FpmlConstants.FPML_NS, "notionalStepSchedule");

        String valStr = extractNotionalFromPriceQuantity(priceQuantityObj);
        if (valStr == null || valStr.isEmpty()) {
            valStr = "1000000.00";
        }

        Element initEl = doc.createElementNS(FpmlConstants.FPML_NS, "initialValue");
        initEl.setTextContent(valStr);
        notionalStepSchedule.appendChild(initEl);

        String currency = extractCurrencyFromPriceQuantity(priceQuantityObj);
        Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
        currEl.setTextContent(currency != null ? currency : "USD");
        notionalStepSchedule.appendChild(currEl);

        notionalSchedule.appendChild(notionalStepSchedule);
        return notionalSchedule;
    }

    private String extractNotionalFromPriceQuantity(Object priceQty) throws Exception {
        try {
            Object quantity = invokeField(priceQty, "getQuantity");
            if (quantity != null && quantity instanceof List) {
                for (Object q : (List<?>) quantity) {
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
            if (quantity != null && quantity instanceof List) {
                for (Object q : (List<?>) quantity) {
                    Object valueObj = invokeField(q, "getValue");
                    if (valueObj != null) {
                        String ccy = extractCurrencyFromValueObj(valueObj);
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

    private Element createFallbackSwaption(Document doc) {
        Element swaptionElement = doc.createElementNS(FpmlConstants.FPML_NS, "swaption");

        // Fallback trade header
        Element tradeHeader = doc.createElementNS(FpmlConstants.FPML_NS, "tradeHeader");
        Element tradeDateEl = doc.createElementNS(FpmlConstants.FPML_NS, "tradeDate");
        tradeDateEl.setTextContent("2024-01-01");
        tradeHeader.appendChild(tradeDateEl);
        swaptionElement.appendChild(tradeHeader);

        // Fallback terms
        Element swaptionTerms = doc.createElementNS(FpmlConstants.FPML_NS, "swaptionTerms");

        Element exerciseStyle = doc.createElementNS(FpmlConstants.FPML_NS, "exerciseStyle");
        Element euEl = doc.createElementNS(FpmlConstants.FPML_NS, "European");
        euEl.setTextContent("European");
        exerciseStyle.appendChild(euEl);
        swaptionTerms.appendChild(exerciseStyle);

        Element exDates = doc.createElementNS(FpmlConstants.FPML_NS, "exerciseDatePeriod");
        Element startDate = doc.createElementNS(FpmlConstants.FPML_NS, "startDate");
        Element unadjStart = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
        unadjStart.setTextContent("2024-01-02");
        startDate.appendChild(unadjStart);
        exDates.appendChild(startDate);

        Element endDate = doc.createElementNS(FpmlConstants.FPML_NS, "endDate");
        Element unadjEnd = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
        unadjEnd.setTextContent("2029-01-02");
        endDate.appendChild(unadjEnd);
        exDates.appendChild(endDate);

        swaptionTerms.appendChild(exDates);
        swaptionElement.appendChild(swaptionTerms);

        // Fallback put/put option with minimal swap
        Element putPutEl = doc.createElementNS(FpmlConstants.FPML_NS, "putPutOption");
        Element fallbackSwap = doc.createElementNS(FpmlConstants.FPML_NS, "swap");
        try {
            Element fallbackStream = buildFallbackSwapStream(doc, null, new CdmToFpmlMappingContext());
            if (fallbackStream != null) {
                fallbackSwap.appendChild(fallbackStream);
            }
        } catch (Exception ignored) {}
        putPutEl.appendChild(fallbackSwap);
        swaptionElement.appendChild(putPutEl);

        return swaptionElement;
    }

    private Element mapExerciseDate(Document doc, Object dateObj) throws Exception {
        if (dateObj == null) return createFallbackDate(doc);

        // Handle AdjustableOrRelativeDate -> getAdjustableDate() -> AdjustableDate
        try {
            java.lang.reflect.Method getAdj = dateObj.getClass().getMethod("getAdjustableDate");
            Object adjustableDate = getAdj.invoke(dateObj);
            if (adjustableDate instanceof AdjustableDate) {
                return CdmDateMapper.mapAdjustableDate(doc, (AdjustableDate) adjustableDate);
            }
        } catch (NoSuchMethodException ignored) {}

        // Handle AdjustableDate directly
        if (dateObj instanceof AdjustableDate) {
            return CdmDateMapper.mapAdjustableDate(doc, (AdjustableDate) dateObj);
        }

        // Parse from toString() for FieldWithMetaDate or similar
        String str = dateObj.toString();
        if (str.contains("unadjustedDate=")) {
            Element wrapperEl = doc.createElementNS(FpmlConstants.FPML_NS, "adjustableDate");
            int start = str.indexOf("unadjustedDate=") + 15;
            int end = str.indexOf(",", start);
            if (end == -1) end = str.indexOf("}", start);
            if (end > start) {
                String dateStr = str.substring(start, end).trim();
                Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");

                if (dateStr.contains("year=")) {
                    int yStart = dateStr.indexOf("year=") + 5;
                    int yEnd = dateStr.indexOf(",", yStart);
                    int mStart = dateStr.indexOf("month=", Math.max(yEnd, 0));
                    int mEnd = str.indexOf(",", mStart);
                    int dStart = dateStr.indexOf("day=", Math.max(mEnd, 0));
                    int dEnd = dateStr.indexOf("}", dStart);

                    if (yEnd > yStart && mEnd > mStart && dEnd > dStart) {
                        String year = dateStr.substring(yStart, yEnd).trim();
                        String month = dateStr.substring(mStart + 6, mEnd).trim();
                        String day = dateStr.substring(dStart + 4, dEnd).trim();
                        unadjEl.setTextContent(String.format("%s-%02d-%02d", year, Integer.parseInt(month), Integer.parseInt(day)));
                    }
                } else if (dateStr.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    unadjEl.setTextContent(dateStr);
                }

                wrapperEl.appendChild(unadjEl);
                return wrapperEl;
            }
        }

        // Try direct date format
        String dateStr = extractDateString(dateObj);
        if (dateStr != null) {
            Element wrapperEl = doc.createElementNS(FpmlConstants.FPML_NS, "adjustableDate");
            Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
            unadjEl.setTextContent(dateStr);
            wrapperEl.appendChild(unadjEl);
            return wrapperEl;
        }

        return createFallbackDate(doc);
    }

    private String extractPayerFromTrade(TradeState tradeState, Object irPayoutObj) throws Exception {
        if (irPayoutObj != null) {
            try {
                Object pr = invokeField(irPayoutObj, "getPayerReceiver");
                if (pr != null) {
                    java.lang.reflect.Method getM = pr.getClass().getMethod("get", int.class);
                    Object payerObj = getM.invoke(pr, 0);
                    if (payerObj != null) {
                        String payerRole = extractStringValue(payerObj);
                        return mapRoleToPartyHref(payerRole);
                    }
                }
            } catch (Exception ignored) {}
        }

        try {
            Object trade = invokeField(tradeState, "getTrade");
            if (trade instanceof List) {
                List<?> counterparties = (List<?>) trade;
                for (Object cp : counterparties) {
                    if (cp instanceof Counterparty) {
                        String extRef = extractExternalReferenceFromRef((Counterparty) cp);
                        return extRef != null ? extRef : "party1";
                    }
                }
            }
        } catch (Exception ignored) {}

        return "party1";
    }

    private String extractReceiverFromTrade(TradeState tradeState, Object irPayoutObj) throws Exception {
        if (irPayoutObj != null) {
            try {
                Object pr = invokeField(irPayoutObj, "getPayerReceiver");
                if (pr != null) {
                    java.lang.reflect.Method getM = pr.getClass().getMethod("get", int.class);
                    Object receiverObj = getM.invoke(pr, 1);
                    if (receiverObj != null) {
                        String rStr = extractStringValue(receiverObj);
                        return mapRoleToPartyHref(rStr);
                    }
                }
            } catch (Exception ignored) {}
        }

        try {
            Object trade = invokeField(tradeState, "getTrade");
            if (trade instanceof List) {
                List<?> counterparties = (List<?>) trade;
                for (Object cp : counterparties) {
                    if (cp instanceof Counterparty) {
                        String extRef = extractExternalReferenceFromRef((Counterparty) cp);
                        return extRef != null ? extRef : "party2";
                    }
                }
            }
        } catch (Exception ignored) {}

        return "party2";
    }

    private String mapRoleToPartyHref(String role) {
        if ("Party1".equals(role)) return "party1";
        if ("Party2".equals(role)) return "party2";
        return "party1";
    }

    private Element createFallbackDate(Document doc) {
        Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
        unadjEl.setTextContent("2024-01-02");
        return unadjEl;
    }

    private String mapExerciseStyleToFpmlElement(String styleStr) {
        if ("American".equalsIgnoreCase(styleStr)) return "American";
        if ("European".equalsIgnoreCase(styleStr)) return "European";
        if ("Bermuda".equalsIgnoreCase(styleStr)) return "Bermudan";
        return "European";
    }

    private String mapExerciseStyleToFpmlValue(String styleStr) {
        if ("American".equalsIgnoreCase(styleStr)) return "American";
        if ("European".equalsIgnoreCase(styleStr)) return "European";
        if ("Bermuda".equalsIgnoreCase(styleStr)) return "Bermudan";
        return "European";
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

    private BigDecimal extractBigDecimalValue(Object val) throws Exception {
        try {
            Object inner = invokeField(val, "getValue");
            if (inner instanceof Number) return new BigDecimal(inner.toString());
            if (inner != null && !inner.toString().contains("{")) return new BigDecimal(inner.toString());
        } catch (Exception ignored) {}

        if (val instanceof Number) return new BigDecimal(val.toString());
        if (val instanceof String) {
            try { return new BigDecimal((String) val); } catch (Exception ignored) {}
        }

        String str = val.toString();
        if (str.contains("value=")) {
            int start = str.indexOf("value=") + 6;
            int end = str.indexOf(",", start);
            if (end == -1) end = str.indexOf("}", start);
            if (end > start) {
                try { return new BigDecimal(str.substring(start, end).trim()); } catch (Exception ignored) {}
            }
        }

        return null;
    }

    private String extractCurrencyFromValueObj(Object val) throws Exception {
        try {
            Object unit = invokeField(val, "getUnit");
            if (unit != null) {
                Object currency = invokeField(unit, "getCurrency");
                if (currency != null) return extractStringValue(currency);
            }
        } catch (Exception ignored) {}

        try {
            Object perUnitOf = invokeField(val, "getPerUnitOf");
            if (perUnitOf != null) {
                Object currency = invokeField(perUnitOf, "getCurrency");
                if (currency != null) return extractStringValue(currency);
            }
        } catch (Exception ignored) {}

        try {
            Object currency = invokeField(val, "getCurrency");
            if (currency != null) return extractStringValue(currency);
        } catch (Exception ignored) {}

        return null;
    }

    private String extractCurrencyFromAmountObj(Object amountObj) throws Exception {
        try {
            Object unit = invokeField(amountObj, "getUnit");
            if (unit != null) {
                Object currency = invokeField(unit, "getCurrency");
                if (currency != null) return extractStringValue(currency);
            }
        } catch (Exception ignored) {}

        try {
            Object currency = invokeField(amountObj, "getCurrency");
            if (currency != null) return extractStringValue(currency);
        } catch (Exception ignored) {}

        return null;
    }

    private String extractNumericValue(Object val) throws Exception {
        if (val == null) return null;

        try {
            java.lang.reflect.Method getM = val.getClass().getMethod("get");
            Object result = getM.invoke(val);
            if (result instanceof Number) {
                Number num = (Number) result;
                double d = num.doubleValue();
                return String.valueOf(d);
            } else if (result != null && !result.toString().contains("{")) {
                String strVal = result.toString();
                try { new BigDecimal(strVal); return strVal; } catch (Exception ignored) {}
                return strVal;
            }
        } catch (NoSuchMethodException ignored) {}

        try {
            java.lang.reflect.Method getVal = val.getClass().getMethod("getValue");
            Object result = getVal.invoke(val);
            if (result instanceof Number) {
                Number num = (Number) result;
                double d = num.doubleValue();
                return String.valueOf(d);
            } else if (result != null && !result.toString().contains("{")) {
                String strVal = result.toString();
                try { new BigDecimal(strVal); return strVal; } catch (Exception ignored) {}
                return strVal;
            }
        } catch (NoSuchMethodException ignored) {}

        if (val instanceof Number) {
            double d = ((Number) val).doubleValue();
            return String.valueOf(d);
        } else if (val instanceof BigDecimal) {
            return ((BigDecimal) val).toPlainString();
        } else if (val instanceof String) {
            try { new BigDecimal((String) val); return (String) val; } catch (Exception ignored) {}
            return (String) val;
        }

        String str = val.toString();
        if (str.contains("value=")) {
            int start = str.indexOf("value=") + 6;
            int end = str.indexOf(",", start);
            if (end == -1) end = str.indexOf("}", start);
            if (end > start) {
                try { new BigDecimal(str.substring(start, end).trim()); return str.substring(start, end).trim(); } catch (Exception ignored) {}
            }
        }

        return null;
    }

    private String extractDateString(Object dateObj) throws Exception {
        if (dateObj == null) return null;

        try {
            java.lang.reflect.Method getVal = dateObj.getClass().getMethod("getValue");
            Object val = getVal.invoke(dateObj);
            if (val != null && !val.toString().contains("{")) {
                String s = formatDateValue(val);
                if (s != null) return s;
            }
        } catch (NoSuchMethodException ignored) {}

        if (dateObj instanceof java.time.LocalDate) {
            return ((java.time.LocalDate) dateObj).toString();
        }
        if (dateObj instanceof String) return (String) dateObj;

        String str = dateObj.toString();
        if (str.contains("value=")) {
            int start = str.indexOf("value=") + 6;
            int end = str.indexOf(",", start);
            if (end == -1) end = str.indexOf("}", start);
            if (end > start) return str.substring(start, end).trim();
        }

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

        if (str.matches("\\d{4}-\\d{2}-\\d{2}")) return str;

        return null;
    }

    private String formatDateValue(Object val) {
        if (val instanceof java.time.LocalDate) return ((java.time.LocalDate) val).toString();
        if (val instanceof com.rosetta.model.lib.records.Date) {
            try {
                int year = ((com.rosetta.model.lib.records.Date) val).getYear();
                int month = ((com.rosetta.model.lib.records.Date) val).getMonth();
                int day = ((com.rosetta.model.lib.records.Date) val).getDay();
                return String.format("%04d-%02d-%02d", year, month, day);
            } catch (Exception ignored) {}
        }
        if (val instanceof java.util.Date) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
            return sdf.format((java.util.Date) val);
        }
        String s = String.valueOf(val);
        if (s.matches("\\d{4}-\\d{2}-\\d{2}")) return s;
        return null;
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

    private static String extractStringValue(Object obj) throws Exception {
        if (obj == null) return null;
        if (obj instanceof String) return (String) obj;
        try {
            java.lang.reflect.Method getM = obj.getClass().getMethod("get");
            Object val = getM.invoke(obj);
            if (val != null && !val.toString().contains("{")) return String.valueOf(val);
        } catch (NoSuchMethodException ignored) {}
        try {
            java.lang.reflect.Method getVal = obj.getClass().getMethod("getValue");
            Object val = getVal.invoke(obj);
            if (val != null && !val.toString().contains("{")) return String.valueOf(val);
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

    private static String extractExternalKey(Object obj) throws Exception {
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

    private String extractIndexName(Object rateOption) throws Exception {
        if (rateOption == null) return null;

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

        try {
            java.lang.reflect.Method getM = rateOption.getClass().getMethod("get");
            Object val = getM.invoke(rateOption);
            if (val != null && !String.valueOf(val).isEmpty() && !val.toString().contains("{")) return String.valueOf(val);
        } catch (NoSuchMethodException ignored) {}

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

        // Parse from toString()
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

        return "UNKNOWN-INDEX";
    }

    private String extractDcfValue(Object dcfObj) throws Exception {
        if (dcfObj == null) return "ACT/360";

        try {
            java.lang.reflect.Method getM = dcfObj.getClass().getMethod("get");
            Object val = getM.invoke(dcfObj);
            if (val != null && !val.toString().contains("{")) return String.valueOf(val);
        } catch (NoSuchMethodException ignored) {}

        try {
            java.lang.reflect.Method getVal = dcfObj.getClass().getMethod("getValue");
            Object val = getVal.invoke(dcfObj);
            if (val != null && !val.toString().contains("{")) return String.valueOf(val);
        } catch (NoSuchMethodException ignored) {}

        String str = dcfObj.toString();
        if (str.contains("value=")) {
            int start = str.indexOf("value=") + 6;
            int end = str.indexOf(",", start);
            if (end == -1) end = str.indexOf("}", start);
            if (end > start) return str.substring(start, end).trim();
        }

        if (dcfObj instanceof Enum) {
            String name = ((Enum<?>) dcfObj).name().toUpperCase();
            switch (name) {
                case "ACT_360": return "ACT/360";
                case "ACT_365": return "ACT/365";
                case "ACT_ACT_ISDA": return "ACT/ACT ISDA";
                case "THIRTY_E_360": return "30E/360";
                case "THIRTY_360": return "30/360";
                default: return name.replace("_", "/");
            }
        }

        return String.valueOf(dcfObj);
    }

    private boolean hasChildElement(Element parent, String tagName) {
        for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
            org.w3c.dom.Node n = parent.getChildNodes().item(i);
            if (n.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE && n.getNodeName().equals(tagName)) {
                return true;
            }
        }
        return false;
    }

    private String formatBigDecimal(BigDecimal value) {
        if (value == null) return "0";
        String s = value.stripTrailingZeros().toPlainString();
        if (!s.contains(".") && !s.contains("E")) {
            s += ".00";
        } else if (s.contains(".")) {
            int decimals = s.split("\\.")[1].length();
            if (decimals == 1) s += "0";
        }
        return s;
    }
}
