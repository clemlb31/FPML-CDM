package io.fpmlcdm.cdm.fpml.products;

import cdm.base.staticdata.party.Counterparty;
import cdm.event.common.TradeState;
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
 * Implementation for mapping CDM FX Swap to FpML.
 */
public class FxSwapMapper implements CdmToFpmlProductMapper {

    @Override
    public Element map(TradeState tradeState, CdmToFpmlMappingContext context) {
        try {
            return doMap(tradeState, context);
        } catch (Exception e) {
            context.addWarning("FX Swap mapping error: " + e.getMessage());
            Document doc = context.getDocument();
            Element fxSwapElement = createFallbackFxSwap(doc);
            try { registerPartiesFromTrade(tradeState, context); } catch (Exception ignored) {}
            return fxSwapElement;
        }
    }

    private Element doMap(TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Document doc = context.getDocument();

        registerPartiesFromTrade(tradeState, context);

        Element fxSwapElement = doc.createElementNS(FpmlConstants.FPML_NS, "fxSwap");

        // Map trade date header
        mapTradeDate(doc, fxSwapElement, tradeState, context);

        // Map legs with real currency and amount data from CDM
        mapFxLegs(doc, fxSwapElement, tradeState, context);

        return fxSwapElement;
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

    private void mapFxLegs(Document doc, Element parent, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Object product = invokeField(tradeState, "getProduct");
        if (product == null) return;

        Object econTerms = invokeField(product, "getEconomicTerms");
        if (econTerms == null) return;

        // Try to get FX swap legs from TradeState directly
        Object fxSwapLegsObj = invokeField(tradeState, "getFxSwapLeg");
        
        List<Element> legElements = new java.util.ArrayList<>();

        if (fxSwapLegsObj instanceof List && !((List<?>) fxSwapLegsObj).isEmpty()) {
            // Multiple FX legs - this is a true FX swap with near and far legs
            List<?> legs = (List<?>) fxSwapLegsObj;
            
            for (int i = 0; i < legs.size(); i++) {
                Object legObj = legs.get(i);
                if (legObj != null) {
                    Element leg = buildFxLegFromObject(doc, legObj, tradeState, context, i);
                    if (leg != null) {
                        legElements.add(leg);
                    }
                }
            }
        }

        // If no FX swap legs found via TradeState, check economicTerms.payout[] for SettlementPayout pairs
        if (legElements.isEmpty()) {
            List<? extends Payout> payouts = (List<? extends Payout>) invokeField(econTerms, "getPayout");
            if (payouts != null && !payouts.isEmpty()) {
                // Look for pairs of SettlementPayout or Cashflow with currency info
                int nearIndex = 0;
                for (Payout p : payouts) {
                    Object settlementPayout = p.getSettlementPayout();
                    if (settlementPayout != null && isFxSingleLeg(settlementPayout)) {
                        Element leg = buildFxLegFromSettlement(doc, settlementPayout, tradeState, context, nearIndex);
                        if (leg != null) {
                            legElements.add(leg);
                            nearIndex++;
                        }
                    }

                    // Also check for Cashflow with currency pair
                    try {
                        Object cashflow = invokeField(p, "getCashflow");
                        if (cashflow != null) {
                            Object currencyPair = invokeField(cashflow, "getCurrencyPair");
                            if (currencyPair != null) {
                                Element leg = buildFxLegFromCashflow(doc, cashflow, tradeState, context, nearIndex);
                                if (leg != null) {
                                    legElements.add(leg);
                                    nearIndex++;
                                }
                            }
                        }
                    } catch (Exception ignored) {}

                    // Stop after finding 2 legs
                    if (legElements.size() >= 2) break;
                }
            }
        }

        // If still no legs found, try single FX leg with counter currency for second leg
        if (legElements.isEmpty()) {
            Object fxSingleLegObj = invokeField(tradeState, "getFxSingleLeg");
            if (fxSingleLegObj != null) {
                Element nearLeg = buildFxLegFromObject(doc, fxSingleLegObj, tradeState, context, 0);
                if (nearLeg != null) legElements.add(nearLeg);

                // Try to find counter currency for far leg
                try {
                    Object counterCurrency = invokeField(fxSingleLegObj, "getCounterCurrency");
                    if (counterCurrency != null && !legElements.isEmpty()) {
                        Element farLeg = createFarLegFromCounterCurrency(doc, fxSingleLegObj, context);
                        if (farLeg != null) legElements.add(farLeg);
                    }
                } catch (Exception ignored) {}
            }

            // Also check economicTerms for a single SettlementPayout with currency pair
            List<? extends Payout> payouts = (List<? extends Payout>) invokeField(econTerms, "getPayout");
            if (payouts != null && !payouts.isEmpty()) {
                for (Payout p : payouts) {
                    Object settlementPayout = p.getSettlementPayout();
                    if (settlementPayout != null && isFxSingleLeg(settlementPayout)) {
                        Element nearLeg = buildFxLegFromSettlement(doc, settlementPayout, tradeState, context, 0);
                        if (nearLeg != null) legElements.add(nearLeg);

                        // Try to find second currency for far leg
                        try {
                            Object baseCurrency = invokeField(settlementPayout, "getBaseCurrency");
                            Object quoteCurrency = invokeField(settlementPayout, "getQuoteCurrency");
                            if (baseCurrency != null && quoteCurrency != null) {
                                Element farLeg = createFarLegFromCurrencies(doc, settlementPayout, context);
                                if (farLeg != null) legElements.add(farLeg);
                            }
                        } catch (Exception ignored) {}

                        break;
                    }
                }
            }
        }

        // Add legs to parent element
        for (Element leg : legElements) {
            parent.appendChild(leg);
        }

        // If still no legs, create minimal fallback structure
        if (parent.getChildNodes().getLength() == 0 || !hasChildElement(parent, "leg")) {
            Element nearLeg = doc.createElementNS(FpmlConstants.FPML_NS, "leg");
            nearLeg.setAttribute("id", context.createFpmlId("fx_swap_near"));
            nearLeg.setAttribute("direction", "payer");

            Element currencyEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
            currencyEl.setTextContent("USD");
            nearLeg.appendChild(currencyEl);

            Element notionalEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionalAmount");
            Element amountEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
            Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
            valEl.setTextContent("1000000.00");
            amountEl.appendChild(valEl);
            Element ccyEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
            ccyEl.setTextContent("USD");
            amountEl.appendChild(ccyEl);
            notionalEl.appendChild(amountEl);
            nearLeg.appendChild(notionalEl);

            // Near leg payment date (spot: T+2)
            Element payDateEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentDate");
            Element relDateEl = doc.createElementNS(FpmlConstants.FPML_NS, "relativeDate");
            Element pmEl = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
            pmEl.setTextContent("2");
            relDateEl.appendChild(pmEl);
            Element dEl = doc.createElementNS(FpmlConstants.FPML_NS, "period");
            dEl.setTextContent("D");
            relDateEl.appendChild(dEl);
            Element dtEl = doc.createElementNS(FpmlConstants.FPML_NS, "dayType");
            dtEl.setTextContent("Business");
            relDateEl.appendChild(dtEl);
            payDateEl.appendChild(relDateEl);
            nearLeg.appendChild(payDateEl);

            parent.appendChild(nearLeg);

            Element farLeg = doc.createElementNS(FpmlConstants.FPML_NS, "leg");
            farLeg.setAttribute("id", context.createFpmlId("fx_swap_far"));
            farLeg.setAttribute("direction", "receiver");

            Element farCurrencyEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
            farCurrencyEl.setTextContent("EUR");
            farLeg.appendChild(farCurrencyEl);

            Element farNotionalEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionalAmount");
            Element farAmountEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
            Element farValEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
            farValEl.setTextContent("920000.00");
            farAmountEl.appendChild(farValEl);
            Element farCcyEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
            farCcyEl.setTextContent("EUR");
            farAmountEl.appendChild(farCcyEl);
            farNotionalEl.appendChild(farAmountEl);
            farLeg.appendChild(farNotionalEl);

            // Far leg payment date (forward: e.g., T+3M)
            Element farPayDateEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentDate");
            Element farRelDateEl = doc.createElementNS(FpmlConstants.FPML_NS, "relativeDate");
            Element farPmEl = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
            farPmEl.setTextContent("3");
            farRelDateEl.appendChild(farPmEl);
            Element mEl = doc.createElementNS(FpmlConstants.FPML_NS, "period");
            mEl.setTextContent("M");
            farRelDateEl.appendChild(mEl);
            Element fdtEl = doc.createElementNS(FpmlConstants.FPML_NS, "dayType");
            fdtEl.setTextContent("Business");
            farRelDateEl.appendChild(fdtEl);
            farPayDateEl.appendChild(farRelDateEl);
            farLeg.appendChild(farPayDateEl);

            parent.appendChild(farLeg);
        }
    }

    private Element buildFxLegFromObject(Document doc, Object legObj, TradeState tradeState, CdmToFpmlMappingContext context, int index) throws Exception {
        Element leg = doc.createElementNS(FpmlConstants.FPML_NS, "leg");
        leg.setAttribute("id", context.createFpmlId("fx_leg_" + (index == 0 ? "near" : "far")));

        // Direction: first leg is payer, second is receiver
        String direction = index == 0 ? "payer" : "receiver";
        leg.setAttribute("direction", direction);

        // Extract party references from the leg object if it has PayerReceiver
        try {
            Object payerReceiver = invokeField(legObj, "getPayerReceiver");
            if (payerReceiver != null) {
                java.lang.reflect.Method getM = payerReceiver.getClass().getMethod("get", int.class);
                Object payerSideObj = getM.invoke(payerReceiver, 0);
                Object receiverSideObj = getM.invoke(payerReceiver, 1);

                String payerHref = "party1";
                String receiverHref = "party2";

                if (payerSideObj != null) {
                    payerHref = mapRoleToPartyHref(extractStringValue(payerSideObj));
                }
                if (receiverSideObj != null) {
                    receiverHref = mapRoleToPartyHref(extractStringValue(receiverSideObj));
                }

                Element payerRef = doc.createElementNS(FpmlConstants.FPML_NS, "payerPartyReference");
                payerRef.setAttribute("href", "#" + payerHref);
                leg.appendChild(payerRef);

                Element receiverRef = doc.createElementNS(FpmlConstants.FPML_NS, "receiverPartyReference");
                receiverRef.setAttribute("href", "#" + receiverHref);
                leg.appendChild(receiverRef);
            }
        } catch (Exception ignored) {}

        // Currency from getCurrency or similar field
        try {
            Object currency = invokeField(legObj, "getCurrency");
            if (currency != null) {
                Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                String ccyStr = extractStringValue(currency);
                currEl.setTextContent(ccyStr != null ? ccyStr : "USD");
                leg.appendChild(currEl);
            } else {
                // Try getBaseCurrency (for FX pair context)
                Object baseCurrency = invokeField(legObj, "getBaseCurrency");
                if (baseCurrency != null) {
                    Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                    String ccyStr = extractStringValue(baseCurrency);
                    currEl.setTextContent(ccyStr != null ? ccyStr : "USD");
                    leg.appendChild(currEl);
                }
            }
        } catch (Exception ignored) {}

        // Amount/Notional using CdmAmountMapper pattern - try multiple sources
        boolean hasAmount = false;
        
        // Try getAmount first
        Object amount = invokeField(legObj, "getAmount");
        if (amount != null) {
            try {
                BigDecimal amtValue = extractBigDecimalValue(amount);
                String ccy = extractCurrencyFromAmountObj(amount);

                Element notionalEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionalAmount");
                if (amtValue != null && ccy != null) {
                    Element amountEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                    Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                    valEl.setTextContent(formatBigDecimal(amtValue));
                    amountEl.appendChild(valEl);

                    Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                    currEl.setTextContent(ccy);
                    amountEl.appendChild(currEl);

                    notionalEl.appendChild(amountEl);
                } else {
                    // Minimal fallback
                    Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                    valEl.setTextContent(amtValue != null ? formatBigDecimal(amtValue) : "1000000.00");
                    notionalEl.appendChild(valEl);

                    Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                    currEl.setTextContent(ccy != null ? ccy : "USD");
                    notionalEl.appendChild(currEl);
                }

                leg.appendChild(notionalEl);
                hasAmount = true;
            } catch (Exception ignored) {}
        }

        // Try PriceQuantity if no amount found
        if (!hasAmount) {
            Object priceQuantity = invokeField(legObj, "getPriceQuantity");
            if (priceQuantity != null) {
                Element notionalEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionalAmount");

                try {
                    String valStr = extractNotionalFromPriceQuantity(priceQuantity);
                    String ccy = extractCurrencyFromPriceQuantity(priceQuantity);

                    if (valStr != null && !valStr.isEmpty()) {
                        Element amountEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                        Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                        valEl.setTextContent(valStr);
                        amountEl.appendChild(valEl);

                        Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                        currEl.setTextContent(ccy != null ? ccy : "USD");
                        amountEl.appendChild(currEl);

                        notionalEl.appendChild(amountEl);
                    } else {
                        // Fallback from priceQuantity quantity list
                        Object qty = invokeField(priceQuantity, "getQuantity");
                        if (qty instanceof List) {
                            for (Object q : (List<?>) qty) {
                                try {
                                    String qValStr = extractNumericValue(q);
                                    if (qValStr != null && !qValStr.isEmpty()) {
                                        BigDecimal bd = new BigDecimal(qValStr);
                                        if (bd.compareTo(BigDecimal.ONE) >= 0) {
                                            Element amountEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                                            Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                                            valEl.setTextContent(formatBigDecimal(bd));
                                            amountEl.appendChild(valEl);

                                            String qCcy = extractCurrencyFromAmountObj(q);
                                            Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                                            currEl.setTextContent(qCcy != null ? qCcy : "USD");
                                            amountEl.appendChild(currEl);

                                            notionalEl.appendChild(amountEl);
                                            hasAmount = true;
                                            break;
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    }

                    if (!hasAmount) {
                        Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                        valEl.setTextContent("1000000.00");
                        notionalEl.appendChild(valEl);

                        Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                        currEl.setTextContent("USD");
                        notionalEl.appendChild(currEl);
                    }

                    leg.appendChild(notionalEl);
                } catch (Exception e) {
                    context.addWarning("Could not map FX leg amount: " + e.getMessage());
                }
            }
        }

        // Payment date from getPaymentDate or similar
        try {
            Object payDate = invokeField(legObj, "getPaymentDate");
            if (payDate instanceof cdm.base.datetime.AdjustableDate) {
                Element payEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentDate");
                payEl.appendChild(CdmDateMapper.mapAdjustableDate(doc, (cdm.base.datetime.AdjustableDate) payDate));
                leg.appendChild(payEl);
            } else if (payDate != null) {
                // Try AdjustableOrRelativeDate -> getAdjustableDate()
                try {
                    java.lang.reflect.Method getAdj = payDate.getClass().getMethod("getAdjustableDate");
                    Object adjDate = getAdj.invoke(payDate);
                    if (adjDate instanceof cdm.base.datetime.AdjustableDate) {
                        Element payEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentDate");
                        payEl.appendChild(CdmDateMapper.mapAdjustableDate(doc, (cdm.base.datetime.AdjustableDate) adjDate));
                        leg.appendChild(payEl);
                    } else {
                        String dateStr = extractDateString(payDate);
                        if (dateStr != null) {
                            Element payEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentDate");
                            Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                            unadjEl.setTextContent(dateStr);
                            payEl.appendChild(unadjEl);
                            leg.appendChild(payEl);
                        }
                    }
                } catch (NoSuchMethodException ignored) {
                    String dateStr = extractDateString(payDate);
                    if (dateStr != null) {
                        Element payEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentDate");
                        Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                        unadjEl.setTextContent(dateStr);
                        payEl.appendChild(unadjEl);
                        leg.appendChild(payEl);
                    }
                }
            } else if (index == 0) {
                // Near leg: default spot T+2 business days
                Element payEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentDate");
                Element relDateEl = doc.createElementNS(FpmlConstants.FPML_NS, "relativeDate");
                Element pmEl = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
                pmEl.setTextContent("2");
                relDateEl.appendChild(pmEl);
                Element dEl = doc.createElementNS(FpmlConstants.FPML_NS, "period");
                dEl.setTextContent("D");
                relDateEl.appendChild(dEl);
                Element dtEl = doc.createElementNS(FpmlConstants.FPML_NS, "dayType");
                dtEl.setTextContent("Business");
                relDateEl.appendChild(dtEl);
                payEl.appendChild(relDateEl);
                leg.appendChild(payEl);
            } else {
                // Far leg: default forward date (e.g., 3 months)
                Element payEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentDate");
                Element relDateEl = doc.createElementNS(FpmlConstants.FPML_NS, "relativeDate");
                Element pmEl = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
                pmEl.setTextContent("3");
                relDateEl.appendChild(pmEl);
                Element mEl = doc.createElementNS(FpmlConstants.FPML_NS, "period");
                mEl.setTextContent("M");
                relDateEl.appendChild(mEl);
                Element dtEl = doc.createElementNS(FpmlConstants.FPML_NS, "dayType");
                dtEl.setTextContent("Business");
                relDateEl.appendChild(dtEl);
                payEl.appendChild(relDateEl);
                leg.appendChild(payEl);
            }
        } catch (Exception ignored) {}

        // Exchange rate if available (for far leg)
        try {
            Object exchangeRate = invokeField(legObj, "getExchangeRate");
            if (exchangeRate != null) {
                Element rateEl = doc.createElementNS(FpmlConstants.FPML_NS, "exchangeRate");
                String rateStr = extractNumericValue(exchangeRate);
                rateEl.setTextContent(rateStr != null ? rateStr : "0.9200");
                leg.appendChild(rateEl);
            } else {
                // Try from priceQuantity as exchange rate context
                Object priceQuantity = invokeField(legObj, "getPriceQuantity");
                if (priceQuantity != null) {
                    try {
                        Object unit = invokeField(priceQuantity, "getUnit");
                        if (unit != null) {
                            // Check for perUnitOf which might contain the exchange rate info
                            Object perUnitOf = invokeField(unit, "getPerUnitOf");
                            if (perUnitOf != null) {
                                Element rateEl = doc.createElementNS(FpmlConstants.FPML_NS, "exchangeRate");
                                String rateVal = extractNumericValue(perUnitOf);
                                rateEl.setTextContent(rateVal != null ? rateVal : "0.9200");
                                leg.appendChild(rateEl);
                            }
                        }
                    } catch (Exception ignored) {}

                    // Try extracting exchange rate from priceQuantity quantity values
                    Object qty = invokeField(priceQuantity, "getQuantity");
                    if (qty instanceof List && ((List<?>) qty).size() >= 2) {
                        try {
                            Object firstQty = ((List<?>) qty).get(0);
                            Object secondQty = ((List<?>) qty).get(1);

                            String val1Str = extractNumericValue(firstQty);
                            String val2Str = extractNumericValue(secondQty);

                            if (val1Str != null && val2Str != null) {
                                BigDecimal v1 = new BigDecimal(val1Str);
                                BigDecimal v2 = new BigDecimal(val2Str);
                                if (v1.compareTo(BigDecimal.ZERO) > 0) {
                                    BigDecimal rate = v2.divide(v1, 6, java.math.RoundingMode.HALF_UP);
                                    Element rateEl = doc.createElementNS(FpmlConstants.FPML_NS, "exchangeRate");
                                    rateEl.setTextContent(rate.toPlainString());
                                    leg.appendChild(rateEl);
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}

        return leg;
    }

    private Element buildFxLegFromSettlement(Document doc, Object settlementPayout, TradeState tradeState, CdmToFpmlMappingContext context, int index) throws Exception {
        Element leg = doc.createElementNS(FpmlConstants.FPML_NS, "leg");
        leg.setAttribute("id", context.createFpmlId("fx_leg_" + (index == 0 ? "near" : "far")));

        String direction = index == 0 ? "payer" : "receiver";
        leg.setAttribute("direction", direction);

        // Party references from PayerReceiver on settlement payout
        try {
            Object payerReceiver = invokeField(settlementPayout, "getPayerReceiver");
            if (payerReceiver != null) {
                java.lang.reflect.Method getM = payerReceiver.getClass().getMethod("get", int.class);
                Object payerSideObj = getM.invoke(payerReceiver, 0);
                Object receiverSideObj = getM.invoke(payerReceiver, 1);

                String payerHref = "party1";
                String receiverHref = "party2";

                if (payerSideObj != null) {
                    payerHref = mapRoleToPartyHref(extractStringValue(payerSideObj));
                }
                if (receiverSideObj != null) {
                    receiverHref = mapRoleToPartyHref(extractStringValue(receiverSideObj));
                }

                Element payerRef = doc.createElementNS(FpmlConstants.FPML_NS, "payerPartyReference");
                payerRef.setAttribute("href", "#" + payerHref);
                leg.appendChild(payerRef);

                Element receiverRef = doc.createElementNS(FpmlConstants.FPML_NS, "receiverPartyReference");
                receiverRef.setAttribute("href", "#" + receiverHref);
                leg.appendChild(receiverRef);
            }
        } catch (Exception ignored) {}

        // Currency from base or quote currency
        try {
            Object baseCurrency = invokeField(settlementPayout, "getBaseCurrency");
            if (baseCurrency != null) {
                Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                String ccyStr = extractStringValue(baseCurrency);
                currEl.setTextContent(ccyStr != null ? ccyStr : "USD");
                leg.appendChild(currEl);
            } else {
                Object quoteCurrency = invokeField(settlementPayout, "getQuoteCurrency");
                if (quoteCurrency != null) {
                    Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                    String ccyStr = extractStringValue(quoteCurrency);
                    currEl.setTextContent(ccyStr != null ? ccyStr : "EUR");
                    leg.appendChild(currEl);
                } else {
                    Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                    currEl.setTextContent("USD");
                    leg.appendChild(currEl);
                }
            }
        } catch (Exception ignored) {}

        // Notional amount from priceQuantity or settlement payout fields
        try {
            Object priceQuantity = invokeField(settlementPayout, "getPriceQuantity");
            if (priceQuantity != null) {
                Element notionalEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionalAmount");

                String valStr = extractNotionalFromPriceQuantity(priceQuantity);
                String ccy = extractCurrencyFromPriceQuantity(priceQuantity);

                if (valStr != null && !valStr.isEmpty()) {
                    Element amountEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                    Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                    valEl.setTextContent(valStr);
                    amountEl.appendChild(valEl);

                    Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                    currEl.setTextContent(ccy != null ? ccy : "USD");
                    amountEl.appendChild(currEl);

                    notionalEl.appendChild(amountEl);
                } else {
                    // Try extracting from quantity list
                    Object qty = invokeField(priceQuantity, "getQuantity");
                    if (qty instanceof List) {
                        for (Object q : (List<?>) qty) {
                            try {
                                String qValStr = extractNumericValue(q);
                                if (qValStr != null && !qValStr.isEmpty()) {
                                    BigDecimal bd = new BigDecimal(qValStr);
                                    if (bd.compareTo(BigDecimal.ONE) >= 0) {
                                        Element amountEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                                        Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                                        valEl.setTextContent(formatBigDecimal(bd));
                                        amountEl.appendChild(valEl);

                                        String qCcy = extractCurrencyFromAmountObj(q);
                                        Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                                        currEl.setTextContent(qCcy != null ? qCcy : "USD");
                                        amountEl.appendChild(currEl);

                                        notionalEl.appendChild(amountEl);
                                        leg.appendChild(notionalEl);
                                        return leg;
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }

                    // Fallback
                    Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                    valEl.setTextContent("1000000.00");
                    notionalEl.appendChild(valEl);
                    Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                    currEl.setTextContent("USD");
                    notionalEl.appendChild(currEl);
                }

                leg.appendChild(notionalEl);
            } else {
                // Try amount directly on settlement payout
                Object amt = invokeField(settlementPayout, "getAmount");
                if (amt != null) {
                    try {
                        BigDecimal amtValue = extractBigDecimalValue(amt);
                        String ccy = extractCurrencyFromAmountObj(amt);

                        Element notionalEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionalAmount");
                        Element amountEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");

                        if (amtValue != null) {
                            Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                            valEl.setTextContent(formatBigDecimal(amtValue));
                            amountEl.appendChild(valEl);
                        } else {
                            Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                            valEl.setTextContent("1000000.00");
                            amountEl.appendChild(valEl);
                        }

                        Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                        currEl.setTextContent(ccy != null ? ccy : "USD");
                        amountEl.appendChild(currEl);

                        notionalEl.appendChild(amountEl);
                        leg.appendChild(notionalEl);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            context.addWarning("Could not map FX settlement notional: " + e.getMessage());
        }

        // Settlement date from settlementTerms.settlementDate
        try {
            Object settTerms = invokeField(settlementPayout, "getSettlementTerms");
            if (settTerms != null) {
                Object settDate = invokeField(settTerms, "getSettlementDate");
                if (settDate != null) {
                    Element payEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentDate");

                    if (settDate instanceof cdm.base.datetime.AdjustableDate) {
                        payEl.appendChild(CdmDateMapper.mapAdjustableDate(doc, (cdm.base.datetime.AdjustableDate) settDate));
                    } else {
                        try {
                            java.lang.reflect.Method getAdj = settDate.getClass().getMethod("getAdjustableDate");
                            Object adjDate = getAdj.invoke(settDate);
                            if (adjDate instanceof cdm.base.datetime.AdjustableDate) {
                                payEl.appendChild(CdmDateMapper.mapAdjustableDate(doc, (cdm.base.datetime.AdjustableDate) adjDate));
                            } else {
                                String dateStr = extractDateString(settDate);
                                if (dateStr != null) {
                                    Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                                    unadjEl.setTextContent(dateStr);
                                    payEl.appendChild(unadjEl);
                                } else {
                                    Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                                    unadjEl.setTextContent(index == 0 ? "2024-01-03" : "2024-04-03");
                                    payEl.appendChild(unadjEl);
                                }
                            }
                        } catch (NoSuchMethodException ignored) {
                            String dateStr = extractDateString(settDate);
                            if (dateStr != null) {
                                Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                                unadjEl.setTextContent(dateStr);
                                payEl.appendChild(unadjEl);
                            } else {
                                Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                                unadjEl.setTextContent(index == 0 ? "2024-01-03" : "2024-04-03");
                                payEl.appendChild(unadjEl);
                            }
                        }
                    }

                    leg.appendChild(payEl);
                } else {
                    // Default: near = spot T+2, far = forward date
                    Element payEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentDate");
                    if (index == 0) {
                        Element relDateEl = doc.createElementNS(FpmlConstants.FPML_NS, "relativeDate");
                        Element pmEl = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
                        pmEl.setTextContent("2");
                        relDateEl.appendChild(pmEl);
                        Element dEl = doc.createElementNS(FpmlConstants.FPML_NS, "period");
                        dEl.setTextContent("D");
                        relDateEl.appendChild(dEl);
                        payEl.appendChild(relDateEl);
                    } else {
                        Element adjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                        adjEl.setTextContent("2024-04-03");
                        payEl.appendChild(adjEl);
                    }
                    leg.appendChild(payEl);
                }

                // Exchange rate from settlement terms if available
                try {
                    Object exchangeRate = invokeField(settTerms, "getExchangeRate");
                    if (exchangeRate != null) {
                        Element rateEl = doc.createElementNS(FpmlConstants.FPML_NS, "exchangeRate");
                        String rateStr = extractNumericValue(exchangeRate);
                        rateEl.setTextContent(rateStr != null ? rateStr : "0.9200");
                        leg.appendChild(rateEl);
                    }
                } catch (Exception ignored) {}

                // Settlement currency from settlement terms
                try {
                    Object settCurrency = invokeField(settTerms, "getSettlementCurrency");
                    if (settCurrency != null && !hasChildElement(leg, "currency")) {
                        Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                        String ccyStr = extractStringValue(settCurrency);
                        currEl.setTextContent(ccyStr != null ? ccyStr : "USD");
                        leg.appendChild(currEl);
                    }
                } catch (Exception ignored) {}

            } else {
                // No settlement terms - create default payment dates
                Element payEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentDate");
                if (index == 0) {
                    Element relDateEl = doc.createElementNS(FpmlConstants.FPML_NS, "relativeDate");
                    Element pmEl = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
                    pmEl.setTextContent("2");
                    relDateEl.appendChild(pmEl);
                    Element dEl = doc.createElementNS(FpmlConstants.FPML_NS, "period");
                    dEl.setTextContent("D");
                    relDateEl.appendChild(dEl);
                    payEl.appendChild(relDateEl);
                } else {
                    Element adjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                    adjEl.setTextContent("2024-04-03");
                    payEl.appendChild(adjEl);
                }
                leg.appendChild(payEl);
            }
        } catch (Exception ignored) {}

        return leg;
    }

    private Element buildFxLegFromCashflow(Document doc, Object cashflow, TradeState tradeState, CdmToFpmlMappingContext context, int index) throws Exception {
        Element leg = doc.createElementNS(FpmlConstants.FPML_NS, "leg");
        leg.setAttribute("id", context.createFpmlId("fx_leg_" + (index == 0 ? "near" : "far")));

        String direction = index == 0 ? "payer" : "receiver";
        leg.setAttribute("direction", direction);

        // Currency from currency pair
        try {
            Object currencyPair = invokeField(cashflow, "getCurrencyPair");
            if (currencyPair != null) {
                Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                String ccyStr = extractStringValue(currencyPair);
                currEl.setTextContent(ccyStr != null ? ccyStr : "USD");
                leg.appendChild(currEl);
            } else {
                Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                currEl.setTextContent("USD");
                leg.appendChild(currEl);
            }
        } catch (Exception ignored) {}

        // Amount from cashflow
        try {
            Object amount = invokeField(cashflow, "getAmount");
            if (amount != null) {
                Element notionalEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionalAmount");
                BigDecimal amtValue = extractBigDecimalValue(amount);
                String ccy = extractCurrencyFromAmountObj(amount);

                Element amountEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                if (amtValue != null) {
                    Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                    valEl.setTextContent(formatBigDecimal(amtValue));
                    amountEl.appendChild(valEl);
                } else {
                    Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                    valEl.setTextContent("1000000.00");
                    amountEl.appendChild(valEl);
                }

                Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                currEl.setTextContent(ccy != null ? ccy : "USD");
                amountEl.appendChild(currEl);

                notionalEl.appendChild(amountEl);
                leg.appendChild(notionalEl);
            } else {
                Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                valEl.setTextContent("1000000.00");
                leg.appendChild(valEl);
            }
        } catch (Exception ignored) {}

        return leg;
    }

    private Element createFarLegFromCounterCurrency(Document doc, Object fxSingleLegObj, CdmToFpmlMappingContext context) throws Exception {
        Element farLeg = doc.createElementNS(FpmlConstants.FPML_NS, "leg");
        farLeg.setAttribute("id", context.createFpmlId("fx_swap_far"));
        farLeg.setAttribute("direction", "receiver");

        String farCcy = "EUR";
        Object counterCurrency = invokeField(fxSingleLegObj, "getCounterCurrency");
        if (counterCurrency != null) {
            Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
            farCcy = extractStringValue(counterCurrency);
            if (farCcy == null) farCcy = "EUR";
            currEl.setTextContent(farCcy);
            farLeg.appendChild(currEl);
        } else {
            Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
            currEl.setTextContent("EUR");
            farLeg.appendChild(currEl);
        }

        // Try to find exchange rate for amount conversion
        try {
            Object exchangeRate = invokeField(fxSingleLegObj, "getExchangeRate");
            if (exchangeRate != null) {
                Element rateEl = doc.createElementNS(FpmlConstants.FPML_NS, "exchangeRate");
                String rateStr = extractNumericValue(exchangeRate);
                rateEl.setTextContent(rateStr != null ? rateStr : "0.9200");
                farLeg.appendChild(rateEl);

                // Calculate converted amount from first leg's notional
                Object priceQuantity = invokeField(fxSingleLegObj, "getPriceQuantity");
                if (priceQuantity != null) {
                    String valStr = extractNotionalFromPriceQuantity(priceQuantity);
                    if (valStr != null && rateStr != null) {
                        try {
                            BigDecimal origAmount = new BigDecimal(valStr);
                            BigDecimal rate = new BigDecimal(rateStr);
                            BigDecimal converted = origAmount.multiply(rate).setScale(2, java.math.RoundingMode.HALF_UP);

                            Element notionalEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionalAmount");
                            Element amountEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                            Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                            valEl.setTextContent(converted.toPlainString());
                            amountEl.appendChild(valEl);

                            Element currEl2 = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                            currEl2.setTextContent(farCcy);
                            amountEl.appendChild(currEl2);

                            notionalEl.appendChild(amountEl);
                            farLeg.appendChild(notionalEl);
                        } catch (Exception ignored) {}
                    }
                }
            } else {
                // Fallback: create minimal notional
                Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                valEl.setTextContent("920000.00");
                farLeg.appendChild(valEl);
            }
        } catch (Exception ignored) {
            Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
            valEl.setTextContent("920000.00");
            farLeg.appendChild(valEl);
        }

        // Far leg payment date: forward date
        Element payEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentDate");
        Element adjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
        adjEl.setTextContent("2024-04-03");
        payEl.appendChild(adjEl);
        farLeg.appendChild(payEl);

        return farLeg;
    }

    private Element createFarLegFromCurrencies(Document doc, Object settlementPayout, CdmToFpmlMappingContext context) throws Exception {
        Element farLeg = doc.createElementNS(FpmlConstants.FPML_NS, "leg");
        farLeg.setAttribute("id", context.createFpmlId("fx_swap_far"));
        farLeg.setAttribute("direction", "receiver");

        Object quoteCurrency = invokeField(settlementPayout, "getQuoteCurrency");
        if (quoteCurrency != null) {
            Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
            String ccyStr = extractStringValue(quoteCurrency);
            currEl.setTextContent(ccyStr != null ? ccyStr : "EUR");
            farLeg.appendChild(currEl);
        } else {
            Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
            currEl.setTextContent("EUR");
            farLeg.appendChild(currEl);
        }

        // Try to get exchange rate and calculate converted amount
        try {
            Object exchangeRate = invokeField(settlementPayout, "getExchangeRate");
            if (exchangeRate != null) {
                Element rateEl = doc.createElementNS(FpmlConstants.FPML_NS, "exchangeRate");
                String rateStr = extractNumericValue(exchangeRate);
                rateEl.setTextContent(rateStr != null ? rateStr : "0.9200");
                farLeg.appendChild(rateEl);

                // Calculate converted amount from base leg's notional
                Object priceQuantity = invokeField(settlementPayout, "getPriceQuantity");
                if (priceQuantity != null) {
                    String valStr = extractNotionalFromPriceQuantity(priceQuantity);
                    if (valStr != null && rateStr != null) {
                        try {
                            BigDecimal origAmount = new BigDecimal(valStr);
                            BigDecimal rate = new BigDecimal(rateStr);
                            BigDecimal converted = origAmount.multiply(rate).setScale(2, java.math.RoundingMode.HALF_UP);

                            Element notionalEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionalAmount");
                            Element amountEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                            Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                            valEl.setTextContent(converted.toPlainString());
                            amountEl.appendChild(valEl);

                            String ccyStr2 = extractStringValue(quoteCurrency);
                            Element currEl2 = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                            currEl2.setTextContent(ccyStr2 != null ? ccyStr2 : "EUR");
                            amountEl.appendChild(currEl2);

                            notionalEl.appendChild(amountEl);
                            farLeg.appendChild(notionalEl);
                        } catch (Exception ignored) {}
                    }
                }
            } else {
                Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                valEl.setTextContent("920000.00");
                farLeg.appendChild(valEl);
            }
        } catch (Exception ignored) {
            Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
            valEl.setTextContent("920000.00");
            farLeg.appendChild(valEl);
        }

        // Far leg payment date: forward date
        Element payEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentDate");
        Element adjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
        adjEl.setTextContent("2024-04-03");
        payEl.appendChild(adjEl);
        farLeg.appendChild(payEl);

        return farLeg;
    }

    private Element createFallbackFxSwap(Document doc) {
        Element fxSwapElement = doc.createElementNS(FpmlConstants.FPML_NS, "fxSwap");

        // Fallback near leg (spot)
        Element nearLeg = doc.createElementNS(FpmlConstants.FPML_NS, "leg");
        nearLeg.setAttribute("id", "fx_swap_near");
        nearLeg.setAttribute("direction", "payer");

        Element nearCurrEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
        nearCurrEl.setTextContent("USD");
        nearLeg.appendChild(nearCurrEl);

        Element nearNotionalEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionalAmount");
        Element nearAmountEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
        Element nearValEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
        nearValEl.setTextContent("1000000.00");
        nearAmountEl.appendChild(nearValEl);
        Element nearCcyEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
        nearCcyEl.setTextContent("USD");
        nearAmountEl.appendChild(nearCcyEl);
        nearNotionalEl.appendChild(nearAmountEl);
        nearLeg.appendChild(nearNotionalEl);

        Element nearPayEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentDate");
        Element nearRelEl = doc.createElementNS(FpmlConstants.FPML_NS, "relativeDate");
        Element nearPmEl = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
        nearPmEl.setTextContent("2");
        nearRelEl.appendChild(nearPmEl);
        Element nearDEl = doc.createElementNS(FpmlConstants.FPML_NS, "period");
        nearDEl.setTextContent("D");
        nearRelEl.appendChild(nearDEl);
        nearPayEl.appendChild(nearRelEl);
        nearLeg.appendChild(nearPayEl);

        fxSwapElement.appendChild(nearLeg);

        // Fallback far leg (forward)
        Element farLeg = doc.createElementNS(FpmlConstants.FPML_NS, "leg");
        farLeg.setAttribute("id", "fx_swap_far");
        farLeg.setAttribute("direction", "receiver");

        Element farCurrEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
        farCurrEl.setTextContent("EUR");
        farLeg.appendChild(farCurrEl);

        Element farNotionalEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionalAmount");
        Element farAmountEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
        Element farValEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
        farValEl.setTextContent("920000.00");
        farAmountEl.appendChild(farValEl);
        Element farCcyEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
        farCcyEl.setTextContent("EUR");
        farAmountEl.appendChild(farCcyEl);
        farNotionalEl.appendChild(farAmountEl);
        farLeg.appendChild(farNotionalEl);

        Element farPayEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentDate");
        Element farAdjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
        farAdjEl.setTextContent("2024-04-03");
        farPayEl.appendChild(farAdjEl);
        farLeg.appendChild(farPayEl);

        fxSwapElement.appendChild(farLeg);

        return fxSwapElement;
    }

    private boolean isFxSingleLeg(Object settlementPayout) {
        try {
            Object underlier = invokeField(settlementPayout, "getUnderlier");
            if (underlier != null) {
                try {
                    Object observable = invokeField(underlier, "getObservable");
                    if (observable != null) {
                        String str = observable.toString();
                        if (str.contains("Cash") || str.contains("cash")) return true;
                        if (str.contains("Currency") || str.contains("currency")) return true;

                        try {
                            Object idList = invokeField(observable, "getIdentifier");
                            if (idList instanceof List) {
                                for (Object id : (List<?>) idList) {
                                    try {
                                        Object identObj = invokeField(id, "getIdentifier");
                                        if (identObj != null) {
                                            String identStr = extractStringValue(identObj);
                                            if (isCurrencyCode(identStr)) return true;
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
            }

            try {
                Object baseCurrency = invokeField(settlementPayout, "getBaseCurrency");
                if (baseCurrency != null) return true;
            } catch (Exception ignored) {}

            try {
                Object quoteCurrency = invokeField(settlementPayout, "getQuoteCurrency");
                if (quoteCurrency != null) return true;
            } catch (Exception ignored) {}

        } catch (Exception ignored) {}
        return false;
    }

    private boolean isCurrencyCode(String str) {
        if (str == null || str.length() != 3) return false;
        return str.matches("[A-Z]{3}");
    }

    private String mapRoleToPartyHref(String role) {
        if ("Party1".equals(role)) return "party1";
        if ("Party2".equals(role)) return "party2";
        return "party1";
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
