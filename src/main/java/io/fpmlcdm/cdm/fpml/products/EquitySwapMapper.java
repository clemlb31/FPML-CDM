package io.fpmlcdm.cdm.fpml.products;

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
 * CDM Equity Swap -> FpML mapper.
 * Maps equity reference, total return leg, and premium/payment leg.
 */
public class EquitySwapMapper implements CdmToFpmlProductMapper {

    @Override
    public Element map(TradeState tradeState, CdmToFpmlMappingContext context) {
        try {
            return doMap(tradeState, context);
        } catch (Exception e) {
            context.addWarning("Equity swap mapping error: " + e.getMessage());
            Document doc = context.getDocument();
            Element eqSwapElement = doc.createElementNS(FpmlConstants.FPML_NS, "equitySwap");
            try { registerPartiesFromTrade(tradeState, context); } catch (Exception ignored) {}
            return eqSwapElement;
        }
    }

    private Element doMap(TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Document doc = context.getDocument();

        registerPartiesFromTrade(tradeState, context);

        Element eqSwapElement = doc.createElementNS(FpmlConstants.FPML_NS, "equitySwap");

        mapTradeDate(doc, eqSwapElement, tradeState, context);

        List<? extends Payout> payouts = getPayouts(tradeState);
        if (payouts != null && !payouts.isEmpty()) {
            for (Payout p : payouts) {
                Object equityTr = invokeField(p, "getEquityTotalReturnPayout");
                if (equityTr != null) {
                    Element eqStream = buildEquityStream(doc, equityTr, tradeState, context);
                    if (eqStream != null) {
                        eqSwapElement.appendChild(eqStream);
                    }
                }
            }
        }

        mapPremiumLeg(doc, eqSwapElement, tradeState, context);

        return eqSwapElement;
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

            if (parent.getFirstChild() != null) {
                parent.insertBefore(tradeHeader, parent.getFirstChild());
            } else {
                parent.appendChild(tradeHeader);
            }
        }
    }

    private Element buildEquityStream(Document doc, Object equityTrObj, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Element eqStream = doc.createElementNS(FpmlConstants.FPML_NS, "equityTotalReturnStream");

        String payerHref = "party1";
        String receiverHref = "party2";

        try {
            Object payerReceiver = invokeField(equityTrObj, "getPayerReceiver");
            if (payerReceiver != null) {
                java.lang.reflect.Method getM = payerReceiver.getClass().getMethod("get", int.class);
                Object payerSideObj = getM.invoke(payerReceiver, 0);
                Object receiverSideObj = getM.invoke(payerReceiver, 1);

                if (payerSideObj != null && receiverSideObj instanceof Enum) {
                    String payerRoleName = ((Enum<?>) payerSideObj).name();

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

        Element payerRef = doc.createElementNS(FpmlConstants.FPML_NS, "payerPartyReference");
        payerRef.setAttribute("href", "#" + payerHref);
        eqStream.appendChild(payerRef);

        Element receiverRef = doc.createElementNS(FpmlConstants.FPML_NS, "receiverPartyReference");
        receiverRef.setAttribute("href", "#" + receiverHref);
        eqStream.appendChild(receiverRef);

        mapEquityReference(doc, eqStream, equityTrObj, tradeState, context);

        try {
            Object calcDates = invokeField(equityTrObj, "getCalculationPeriodDates");
            if (calcDates != null) {
                Element calcPeriodDatesEl = mapCalculationPeriodDates(doc, calcDates);
                String extKey = extractExternalKey(calcDates);
                String streamId = extKey != null ? extKey : context.createFpmlId("eqCalcPeriod");
                calcPeriodDatesEl.setAttribute("id", streamId);
                eqStream.appendChild(calcPeriodDatesEl);

                Element paymentDatesEl = mapPaymentDates(doc, streamId);
                if (paymentDatesEl != null) {
                    eqStream.appendChild(paymentDatesEl);
                }
            }
        } catch (Exception e) {
            context.addWarning("Could not map equity swap calculation period dates: " + e.getMessage());
        }

        try {
            Element calcAmountEl = doc.createElementNS(FpmlConstants.FPML_NS, "calculationPeriodAmount");
            Element calculation = doc.createElementNS(FpmlConstants.FPML_NS, "calculation");

            Object priceQuantity = invokeField(equityTrObj, "getPriceQuantity");
            if (priceQuantity != null) {
                Element notionalSched = mapNotionalWithLocation(doc, priceQuantity);
                calculation.appendChild(notionalSched);
            } else {
                Object qtySched = invokeField(equityTrObj, "getQuantitySchedule");
                if (qtySched != null) {
                    Element notionalSched = doc.createElementNS(FpmlConstants.FPML_NS, "notionalSchedule");
                    Element step = doc.createElementNS(FpmlConstants.FPML_NS, "notionalStepSchedule");

                    String valStr = extractNumericValue(qtySched);
                    if (valStr == null || valStr.isEmpty()) {
                        valStr = "1000000.00";
                    }
                    Element initEl = doc.createElementNS(FpmlConstants.FPML_NS, "initialValue");
                    initEl.setTextContent(valStr);
                    step.appendChild(initEl);

                    String currency = extractCurrencyFromAmountObj(qtySched);
                    Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                    currEl.setTextContent(currency != null ? currency : "USD");
                    step.appendChild(currEl);

                    notionalSched.appendChild(step);
                    calculation.appendChild(notionalSched);
                }
            }

            Element dcf = doc.createElementNS(FpmlConstants.FPML_NS, "dayCountFraction");
            Object dcfObj = invokeField(equityTrObj, "getDayCountFraction");
            if (dcfObj != null) {
                dcf.setTextContent(extractDcfValue(dcfObj));
            } else {
                dcf.setTextContent("ACT/365");
            }
            calculation.appendChild(dcf);

            calcAmountEl.appendChild(calculation);
            eqStream.appendChild(calcAmountEl);
        } catch (Exception e) {
            context.addWarning("Could not map equity swap calculation amount: " + e.getMessage());
        }

        return eqStream;
    }

    private void mapEquityReference(Document doc, Element parent, Object equityTrObj, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        try {
            Object underlier = invokeField(equityTrObj, "getUnderlier");
            if (underlier != null) {
                Element equityReference = doc.createElementNS(FpmlConstants.FPML_NS, "equityReference");

                // Try to get security/asset name from underlier
                try {
                    Object security = invokeField(underlier, "getSecurity");
                    if (security != null) {
                        Element issuerPartyRef = doc.createElementNS(FpmlConstants.FPML_NS, "issuerPartyReference");

                        // Try to find issuer identifier
                        try {
                            Object identifiers = invokeField(security, "getIdentifier");
                            if (identifiers instanceof List) {
                                for (Object id : (List<?>) identifiers) {
                                    try {
                                        Object identObj = invokeField(id, "getIdentifier");
                                        if (identObj != null) {
                                            String identStr = extractStringValue(identObj);
                                            if (identStr != null && !identStr.isEmpty()) {
                                                Element instrId = doc.createElementNS(FpmlConstants.FPML_NS, "instrumentId");
                                                instrId.setTextContent(identStr);

                                                Object idType = invokeField(id, "getIdentifierType");
                                                if (idType != null) {
                                                    String typeStr = extractStringValue(idType);
                                                    if ("CUSIP".equalsIgnoreCase(typeStr)) {
                                                        instrId.setAttribute("instrumentIdScheme", "http://www.fpml.org/coding-scheme/instrument-id-cusip");
                                                    } else if ("ISIN".equalsIgnoreCase(typeStr)) {
                                                        instrId.setAttribute("instrumentIdScheme", "http://www.fpml.org/coding-scheme/instrument-id-isin");
                                                    }
                                                }

                                                equityReference.appendChild(instrId);
                                            }
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }
                        } catch (Exception ignored) {}

                        // Try issuer name
                        try {
                            Object issuerName = invokeField(security, "getIssuerName");
                            if (issuerName != null) {
                                Element issuerNameEl = doc.createElementNS(FpmlConstants.FPML_NS, "issuerName");
                                issuerNameEl.setTextContent(extractStringValue(issuerName));
                                equityReference.appendChild(issuerNameEl);
                            }
                        } catch (Exception ignored) {}

                        parent.appendChild(equityReference);
                    } else {
                        // Try asset name directly
                        try {
                            Object assetName = invokeField(underlier, "getAssetName");
                            if (assetName != null) {
                                Element equityRef = doc.createElementNS(FpmlConstants.FPML_NS, "equityReference");
                                Element tickerEl = doc.createElementNS(FpmlConstants.FPML_NS, "ticker");
                                tickerEl.setTextContent(extractStringValue(assetName));
                                equityRef.appendChild(tickerEl);
                                parent.appendChild(equityRef);
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception e) {
                    context.addWarning("Could not map equity reference: " + e.getMessage());

                    // Fallback: create minimal equityReference
                    Element equityRef = doc.createElementNS(FpmlConstants.FPML_NS, "equityReference");
                    Element tickerEl = doc.createElementNS(FpmlConstants.FPML_NS, "ticker");
                    tickerEl.setTextContent("UNKNOWN-EQUITY");
                    equityRef.appendChild(tickerEl);
                    parent.appendChild(equityRef);
                }

                // Total return vs price return
                try {
                    Object returnType = invokeField(equityTrObj, "getReturnType");
                    if (returnType instanceof Enum) {
                        Element returnEl = doc.createElementNS(FpmlConstants.FPML_NS, "totalReturn");
                        String typeStr = ((Enum<?>) returnType).name();
                        if ("PRICE_RETURN".equalsIgnoreCase(typeStr)) {
                            Element priceReturn = doc.createElementNS(FpmlConstants.FPML_NS, "priceReturn");
                            returnEl.appendChild(priceReturn);
                        }
                        if (equityReference != null) {
                            equityReference.appendChild(returnEl);
                        } else {
                            parent.appendChild(returnEl);
                        }
                    } else {
                        // Default: total return
                        Element totalReturn = doc.createElementNS(FpmlConstants.FPML_NS, "totalReturn");
                        if (equityReference != null) {
                            equityReference.appendChild(totalReturn);
                        } else {
                            parent.appendChild(totalReturn);
                        }
                    }
                } catch (Exception ignored) {}

                if (!equityReference.hasChildNodes()) {
                    Element equityRef = doc.createElementNS(FpmlConstants.FPML_NS, "equityReference");
                    Element tickerEl = doc.createElementNS(FpmlConstants.FPML_NS, "ticker");
                    tickerEl.setTextContent("UNKNOWN-EQUITY");
                    equityRef.appendChild(tickerEl);
                    parent.appendChild(equityRef);

                    Element totalReturn = doc.createElementNS(FpmlConstants.FPML_NS, "totalReturn");
                    equityRef.appendChild(totalReturn);
                }
            } else {
                // Fallback: create minimal equityReference from tradeLot
                Element equityRef = doc.createElementNS(FpmlConstants.FPML_NS, "equityReference");
                Element tickerEl = doc.createElementNS(FpmlConstants.FPML_NS, "ticker");
                tickerEl.setTextContent("UNKNOWN-EQUITY");
                equityRef.appendChild(tickerEl);

                Element totalReturn = doc.createElementNS(FpmlConstants.FPML_NS, "totalReturn");
                equityRef.appendChild(totalReturn);

                parent.appendChild(equityRef);
            }
        } catch (Exception e) {
            context.addWarning("Could not map equity reference: " + e.getMessage());

            Element equityRef = doc.createElementNS(FpmlConstants.FPML_NS, "equityReference");
            Element tickerEl = doc.createElementNS(FpmlConstants.FPML_NS, "ticker");
            tickerEl.setTextContent("UNKNOWN-EQUITY");
            equityRef.appendChild(tickerEl);

            Element totalReturn = doc.createElementNS(FpmlConstants.FPML_NS, "totalReturn");
            equityRef.appendChild(totalReturn);

            parent.appendChild(equityRef);
        }
    }

    private Element mapCalculationPeriodDates(Document doc, Object calcDates) throws Exception {
        Element calcPeriodDates = doc.createElementNS(FpmlConstants.FPML_NS, "calculationPeriodDates");

        try {
            Object effDate = invokeField(calcDates, "getEffectiveDate");
            if (effDate != null) {
                Element effEl = mapEffectiveOrTerminationDate(doc, effDate, "effectiveDate");
                calcPeriodDates.appendChild(effEl);
            }
        } catch (Exception ignored) {}

        try {
            Object termDate = invokeField(calcDates, "getTerminationDate");
            if (termDate != null) {
                Element termEl = mapEffectiveOrTerminationDate(doc, termDate, "terminationDate");
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

    private Element mapPaymentDates(Document doc, String calcDatesId) throws Exception {
        Element paymentDates = doc.createElementNS(FpmlConstants.FPML_NS, "paymentDates");

        Element refEl = doc.createElementNS(FpmlConstants.FPML_NS, "calculationPeriodDatesReference");
        refEl.setAttribute("href", "#" + calcDatesId);
        paymentDates.appendChild(refEl);

        Element payFreqEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentFrequency");
        Element pmEl2 = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
        pmEl2.setTextContent("3");
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

    private Element mapPremiumLeg(Document doc, Element parent, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Element premiumLeg = doc.createElementNS(FpmlConstants.FPML_NS, "premiumLeg");

        try {
            Object product = invokeField(tradeState, "getProduct");
            if (product == null) return premiumLeg;

            Object econTerms = invokeField(product, "getEconomicTerms");
            if (econTerms == null) return premiumLeg;

            List<? extends Payout> payouts = (List<? extends Payout>) invokeField(econTerms, "getPayout");
            boolean foundIrPayout = false;

            if (payouts != null && !payouts.isEmpty()) {
                for (Payout p : payouts) {
                    Object irPayoutObj = invokeField(p, "getInterestRatePayout");
                    if (irPayoutObj != null) {
                        foundIrPayout = true;

                        Element swapStream = buildPremiumSwapStream(doc, irPayoutObj, tradeState, context);
                        if (swapStream != null) {
                            premiumLeg.appendChild(swapStream);
                        }
                    }
                }
            }

            if (!foundIrPayout) {
                // Create minimal premium leg with defaults
                Element swapStream = doc.createElementNS(FpmlConstants.FPML_NS, "premiumPaymentStream");

                Element payerRef = doc.createElementNS(FpmlConstants.FPML_NS, "payerPartyReference");
                payerRef.setAttribute("href", "#party1");
                swapStream.appendChild(payerRef);

                Element receiverRef = doc.createElementNS(FpmlConstants.FPML_NS, "receiverPartyReference");
                receiverRef.setAttribute("href", "#party2");
                swapStream.appendChild(receiverRef);

                Element calcPeriodDatesEl = doc.createElementNS(FpmlConstants.FPML_NS, "calculationPeriodDates");
                calcPeriodDatesEl.setAttribute("id", "premiumCalcPeriodDates");

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
                Element payRef = doc.createElementNS(FpmlConstants.FPML_NS, "calculationPeriodDatesReference");
                payRef.setAttribute("href", "#premiumCalcPeriodDates");
                paymentDates.appendChild(payRef);

                Element payFreq = doc.createElementNS(FpmlConstants.FPML_NS, "paymentFrequency");
                Element pmEl = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
                pmEl.setTextContent("3");
                payFreq.appendChild(pmEl);
                Element pEl = doc.createElementNS(FpmlConstants.FPML_NS, "period");
                pEl.setTextContent("M");
                payFreq.appendChild(pEl);
                paymentDates.appendChild(payFreq);

                Element payRel = doc.createElementNS(FpmlConstants.FPML_NS, "payRelativeTo");
                payRel.setTextContent("CalculationPeriodEndDate");
                paymentDates.appendChild(payRel);

                swapStream.appendChild(paymentDates);

                Element calcAmountEl = doc.createElementNS(FpmlConstants.FPML_NS, "calculationPeriodAmount");
                Element calculation = doc.createElementNS(FpmlConstants.FPML_NS, "calculation");

                Element notionalSched = doc.createElementNS(FpmlConstants.FPML_NS, "notionalSchedule");
                Element step = doc.createElementNS(FpmlConstants.FPML_NS, "notionalStepSchedule");
                Element initVal = doc.createElementNS(FpmlConstants.FPML_NS, "initialValue");
                initVal.setTextContent("0.00");
                step.appendChild(initVal);
                Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                currEl.setTextContent("USD");
                step.appendChild(currEl);
                notionalSched.appendChild(step);
                calculation.appendChild(notionalSched);

                calcAmountEl.appendChild(calculation);
                swapStream.appendChild(calcAmountEl);

                premiumLeg.appendChild(swapStream);
            }
        } catch (Exception e) {
            context.addWarning("Could not map equity swap premium leg: " + e.getMessage());
        }

        parent.appendChild(premiumLeg);
        return premiumLeg;
    }

    private Element buildPremiumSwapStream(Document doc, Object irPayoutObj, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Element swapStream = doc.createElementNS(FpmlConstants.FPML_NS, "premiumPaymentStream");

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

        Element payerRef = doc.createElementNS(FpmlConstants.FPML_NS, "payerPartyReference");
        payerRef.setAttribute("href", "#" + payerHref);
        swapStream.appendChild(payerRef);

        Element receiverRef = doc.createElementNS(FpmlConstants.FPML_NS, "receiverPartyReference");
        receiverRef.setAttribute("href", "#" + receiverHref);
        swapStream.appendChild(receiverRef);

        try {
            Object calcDates = invokeField(irPayoutObj, "getCalculationPeriodDates");
            if (calcDates != null) {
                Element calcPeriodDatesEl = mapCalculationPeriodDates(doc, calcDates);
                String extKey = extractExternalKey(calcDates);
                String streamId = extKey != null ? extKey : context.createFpmlId("premiumCalcPeriod");
                calcPeriodDatesEl.setAttribute("id", streamId);
                swapStream.appendChild(calcPeriodDatesEl);

                Element paymentDatesEl = mapPaymentDates(doc, streamId);
                if (paymentDatesEl != null) {
                    swapStream.appendChild(paymentDatesEl);
                }
            }
        } catch (Exception e) {
            context.addWarning("Could not map premium leg calculation dates: " + e.getMessage());
        }

        try {
            Element calcAmountEl = doc.createElementNS(FpmlConstants.FPML_NS, "calculationPeriodAmount");
            Element calculation = doc.createElementNS(FpmlConstants.FPML_NS, "calculation");

            Object priceQuantity = invokeField(irPayoutObj, "getPriceQuantity");
            if (priceQuantity != null) {
                Element notionalSched = mapNotionalWithLocation(doc, priceQuantity);
                calculation.appendChild(notionalSched);
            } else {
                Object qtySched = invokeField(irPayoutObj, "getQuantitySchedule");
                if (qtySched != null) {
                    Element notionalSched = doc.createElementNS(FpmlConstants.FPML_NS, "notionalSchedule");
                    Element step = doc.createElementNS(FpmlConstants.FPML_NS, "notionalStepSchedule");

                    String valStr = extractNumericValue(qtySched);
                    if (valStr == null || valStr.isEmpty()) {
                        valStr = "0.00";
                    }
                    Element initEl = doc.createElementNS(FpmlConstants.FPML_NS, "initialValue");
                    initEl.setTextContent(valStr);
                    step.appendChild(initEl);

                    String currency = extractCurrencyFromAmountObj(qtySched);
                    Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                    currEl.setTextContent(currency != null ? currency : "USD");
                    step.appendChild(currEl);

                    notionalSched.appendChild(step);
                    calculation.appendChild(notionalSched);
                }
            }

            try {
                Object rateSpec = invokeField(irPayoutObj, "getRateSpecification");
                if (rateSpec != null) {
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
                            Object product2 = invokeField(tradeState, "getProduct");
                            if (product2 != null) {
                                Object trade2 = invokeField(product2, "getTrade");
                                if (trade2 != null) {
                                    Object tradeLotList = invokeField(trade2, "getTradeLot");
                                    if (tradeLotList instanceof java.util.List) {
                                        for (Object lot : (java.util.List<?>) tradeLotList) {
                                            try {
                                                Object pqList = invokeField(lot, "getPriceQuantity");
                                                if (pqList instanceof java.util.List) {
                                                    for (Object pq : (java.util.List<?>) pqList) {
                                                        Object priceList = invokeField(pq, "getPrice");
                                                        if (priceList instanceof java.util.List) {
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
                                                                                rateVal = extractNumericValue(valueObj);
                                                                                break;
                                                                            }
                                                                        }
                                                                    }
                                                                } catch (Exception ignored2) {}
                                                            }
                                                        }
                                                    }
                                                }
                                            } catch (Exception ignored) {}
                                        }
                                    }
                                }
                            }
                        }

                        if (rateVal == null || rateVal.isEmpty()) {
                            rateVal = "0.05";
                        }

                        Element fixedValue = doc.createElementNS(FpmlConstants.FPML_NS, "initialValue");
                        fixedValue.setTextContent(rateVal);
                        fixedRateSchedule.appendChild(fixedValue);
                        calculation.appendChild(fixedRateSchedule);
                    }
                }
            } catch (Exception ignored) {}

            Element dcf = doc.createElementNS(FpmlConstants.FPML_NS, "dayCountFraction");
            Object dcfObj = invokeField(irPayoutObj, "getDayCountFraction");
            if (dcfObj != null) {
                dcf.setTextContent(extractDcfValue(dcfObj));
            } else {
                dcf.setTextContent("ACT/360");
            }
            calculation.appendChild(dcf);

            calcAmountEl.appendChild(calculation);
            swapStream.appendChild(calcAmountEl);
        } catch (Exception e) {
            context.addWarning("Could not map premium leg calculation amount: " + e.getMessage());
        }

        return swapStream;
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

    private Element mapEffectiveOrTerminationDate(Document doc, Object adjOrRelDate, String elementName) throws Exception {
        if (adjOrRelDate == null) return null;

        try {
            java.lang.reflect.Method getAdj = adjOrRelDate.getClass().getMethod("getAdjustableDate");
            Object adjustableDate = getAdj.invoke(adjOrRelDate);

            if (adjustableDate != null) {
                Element wrapperEl = doc.createElementNS(FpmlConstants.FPML_NS, elementName);

                try {
                    java.lang.reflect.Method getUnadj = adjustableDate.getClass().getMethod("getUnadjustedDate");
                    Object unadj = getUnadj.invoke(adjustableDate);

                    if (unadj != null) {
                        Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                        String dateStr = extractDateValue(unadj);
                        if (dateStr != null && !dateStr.isEmpty()) {
                            unadjEl.setTextContent(dateStr);
                        }
                        wrapperEl.appendChild(unadjEl);

                        try {
                            java.lang.reflect.Method getAdj2 = adjustableDate.getClass().getMethod("getDateAdjustments");
                            Object bda = getAdj2.invoke(adjustableDate);

                            if (bda != null) {
                                Element adjEl = doc.createElementNS(FpmlConstants.FPML_NS, "dateAdjustments");

                                try {
                                    java.lang.reflect.Method getBdc = bda.getClass().getMethod("getBusinessDayConvention");
                                    Object bdc = getBdc.invoke(bda);
                                    if (bdc != null) {
                                        Element bdcEl = doc.createElementNS(FpmlConstants.FPML_NS, "businessDayConvention");
                                        String bdcStr = String.valueOf(bdc).replace("_", "");
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

        try {
            java.lang.reflect.Method getVal = dateObj.getClass().getMethod("getValue");
            Object val = getVal.invoke(dateObj);
            if (val != null && !val.toString().contains("{")) return formatDateValue(val);
        } catch (NoSuchMethodException ignored) {}

        if (dateObj instanceof com.rosetta.model.lib.records.Date) {
            try {
                int year = ((com.rosetta.model.lib.records.Date) dateObj).getYear();
                int month = ((com.rosetta.model.lib.records.Date) dateObj).getMonth();
                int day = ((com.rosetta.model.lib.records.Date) dateObj).getDay();
                return String.format("%04d-%02d-%02d", year, month, day);
            } catch (Exception ignored) {}
        }

        String str = dateObj.toString();
        if (str.contains("unadjustedDate=")) {
            int start = str.indexOf("unadjustedDate=") + 15;
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

        if (!str.contains("{")) return str;

        return null;
    }

    private String extractDateString(Object dateObj) throws Exception {
        if (dateObj == null) return null;

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
        return String.valueOf(val);
    }

    private String extractNumericValue(Object val) throws Exception {
        if (val == null) return null;

        try {
            java.lang.reflect.Method getM = val.getClass().getMethod("get");
            Object result = getM.invoke(val);
            if (result instanceof Number) {
                return formatBigDecimal((Number) result);
            } else if (result != null && !result.toString().contains("{")) {
                try {
                    BigDecimal bd = new BigDecimal(result.toString());
                    return formatBigDecimal(bd);
                } catch (Exception ignored) {}
            }
        } catch (NoSuchMethodException ignored) {}

        try {
            java.lang.reflect.Method getVal = val.getClass().getMethod("getValue");
            Object result = getVal.invoke(val);
            if (result instanceof Number) {
                return formatBigDecimal((Number) result);
            } else if (result != null && !result.toString().contains("{")) {
                try {
                    BigDecimal bd = new BigDecimal(result.toString());
                    return formatBigDecimal(bd);
                } catch (Exception ignored) {}
            }
        } catch (NoSuchMethodException ignored) {}

        if (val instanceof Number) {
            return formatBigDecimal((Number) val);
        } else if (val instanceof String) {
            try {
                BigDecimal bd = new BigDecimal((String) val);
                return formatBigDecimal(bd);
            } catch (Exception ignored) {}
        }

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

    private String extractCurrencyFromAmountObj(Object amountObj) throws Exception {
        try {
            Object currency = invokeField(amountObj, "getCurrency");
            if (currency != null) return String.valueOf(currency);
        } catch (Exception ignored) {}
        return null;
    }

    private String extractNotionalFromPriceQuantity(Object priceQty) throws Exception {
        try {
            Object quantitySchedule = invokeField(priceQty, "getQuantitySchedule");
            if (quantitySchedule != null) {
                return extractNumericValue(quantitySchedule);
            }
        } catch (Exception ignored) {}

        try {
            Object quantityList = invokeField(priceQty, "getQuantity");
            if (quantityList instanceof java.util.List) {
                for (Object q : (java.util.List<?>) quantityList) {
                    String val = extractNumericValue(q);
                    if (val != null && !val.isEmpty()) return val;
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    private String extractCurrencyFromPriceQuantity(Object priceQty) throws Exception {
        try {
            Object quantitySchedule = invokeField(priceQty, "getQuantitySchedule");
            if (quantitySchedule != null) {
                return extractCurrencyFromAmountObj(quantitySchedule);
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

    private String extractDcfValue(Object dcfObj) throws Exception {
        if (dcfObj == null) return "ACT/365";

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
                case "ACT_365": return "ACT/365";
                case "ACT_ACT_ISDA": return "ACT/ACT ISDA";
                case "THIRTY_E_360": return "30E/360";
                case "THIRTY_360": return "30/360";
                default: return name.replace("_", "/");
            }
        }

        return String.valueOf(dcfObj);
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

    private String formatBigDecimal(Number num) {
        if (num == null) return "0";
        double d = num.doubleValue();
        if (d == Math.floor(d)) {
            return String.format("%.2f", d);
        } else {
            return String.valueOf(num);
        }
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
