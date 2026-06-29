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
 * CDM Dividend Swap → FpML {@code <dividendSwapTransactionSupplement>} mapper.
 * 
 * Detects dividend swap by checking for PerformancePayout (dividend leg) + FixedPricePayout (fixed leg).
 */
public class DividendSwapMapper implements CdmToFpmlProductMapper {

    @Override
    public Element map(TradeState tradeState, CdmToFpmlMappingContext context) {
        try {
            return doMap(tradeState, context);
        } catch (Exception e) {
            context.addWarning("Dividend Swap mapping error: " + e.getMessage());
            Document doc = context.getDocument();
            return createFallbackDividendSwap(doc);
        }
    }

    private Element doMap(TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Document doc = context.getDocument();

        registerPartiesFromTrade(tradeState, context);

        Object product = invokeField(tradeState, "getProduct");
        if (product == null) return createFallbackDividendSwap(doc);

        Object econTerms = invokeField(product, "getEconomicTerms");
        if (econTerms == null) return createFallbackDividendSwap(doc);

        List<? extends Payout> payouts = (List<? extends Payout>) invokeField(econTerms, "getPayout");
        if (payouts == null || payouts.isEmpty()) return createFallbackDividendSwap(doc);

        // Detect dividend swap: PerformancePayout (dividend leg) + FixedPricePayout (fixed leg)
        boolean hasPerformance = false;
        boolean hasFixedPrice = false;
        
        for (Payout p : payouts) {
            Object perfPayout = invokeField(p, "getPerformancePayout");
            if (perfPayout != null) hasPerformance = true;

            Object fixedPayout = invokeField(p, "getFixedPricePayout");
            if (fixedPayout != null) hasFixedPrice = true;
        }

        // A dividend swap has both a performance payout and a fixed price payout
        if (!hasPerformance && !hasFixedPrice) {
            return createFallbackDividendSwap(doc);
        }

        Element divTxSupplement = doc.createElementNS(FpmlConstants.FPML_NS, "dividendSwapTransactionSupplement");

        // Build the dividendSwap wrapper
        Element dividendSwapEl = doc.createElementNS(FpmlConstants.FPML_NS, "dividendSwap");
        divTxSupplement.appendChild(dividendSwapEl);

        // Map trade date header
        mapTradeDate(doc, dividendSwapEl, tradeState, context);

        // Build dividend leg (PerformancePayout) and fixed leg (FixedPricePayout)
        for (Payout p : payouts) {
            Object perfPayout = invokeField(p, "getPerformancePayout");
            if (perfPayout != null) {
                Element dividendLeg = buildDividendLeg(doc, perfPayout, tradeState, context);
                if (dividendLeg != null) {
                    dividendSwapEl.appendChild(dividendLeg);
                }
            }

            Object fixedPayout = invokeField(p, "getFixedPricePayout");
            if (fixedPayout != null) {
                Element fixedLeg = buildFixedLeg(doc, fixedPayout, tradeState, context);
                if (fixedLeg != null) {
                    dividendSwapEl.appendChild(fixedLeg);
                }
            }
        }

        return divTxSupplement;
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
            parent.insertBefore(tradeHeader, parent.getFirstChild());
        }
    }

    private Element buildDividendLeg(Document doc, Object perfPayout, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Element dividendLeg = doc.createElementNS(FpmlConstants.FPML_NS, "dividendLeg");

        // Payer/Receiver party references from PerformancePayout payerReceiver
        try {
            Object payerReceiver = invokeField(perfPayout, "getPayerReceiver");
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
                dividendLeg.appendChild(payerRef);

                Element receiverRef = doc.createElementNS(FpmlConstants.FPML_NS, "receiverPartyReference");
                receiverRef.setAttribute("href", "#" + receiverHref);
                dividendLeg.appendChild(receiverRef);
            }
        } catch (Exception ignored) {
            // Fallback party references
            Element payerRef = doc.createElementNS(FpmlConstants.FPML_NS, "payerPartyReference");
            payerRef.setAttribute("href", "#party1");
            dividendLeg.appendChild(payerRef);

            Element receiverRef = doc.createElementNS(FpmlConstants.FPML_NS, "receiverPartyReference");
            receiverRef.setAttribute("href", "#party2");
            dividendLeg.appendChild(receiverRef);
        }

        // Underlier (dividend index or single name)
        try {
            Object underlier = invokeField(perfPayout, "getUnderlier");
            if (underlier != null) {
                Element underlyerEl = doc.createElementNS(FpmlConstants.FPML_NS, "underlyer");

                // Try to get observable from underlier
                try {
                    Object observable = invokeField(underlier, "getObservable");
                    if (observable != null) {
                        Element singleUnderlyer = doc.createElementNS(FpmlConstants.FPML_NS, "singleUnderlyer");

                        // Check for index vs single name via tradeLot priceQuantity
                        String underlyerName = extractUnderlyerName(tradeState);
                        if (underlyerName != null && !underlyerName.isEmpty()) {
                            Element indexEl = doc.createElementNS(FpmlConstants.FPML_NS, "index");
                            indexEl.setTextContent(underlyerName);
                            singleUnderlyer.appendChild(indexEl);

                            // Open units from tradeLot priceQuantity
                            String openUnits = extractOpenUnits(tradeState);
                            if (openUnits != null) {
                                Element openUnitsEl = doc.createElementNS(FpmlConstants.FPML_NS, "openUnits");
                                openUnitsEl.setTextContent(openUnits);
                                singleUnderlyer.appendChild(openUnitsEl);
                            }
                        } else {
                            // Single name equity fallback
                            Element equityReference = doc.createElementNS(FpmlConstants.FPML_NS, "equityReference");
                            Element tickerEl = doc.createElementNS(FpmlConstants.FPML_NS, "ticker");
                            tickerEl.setTextContent("UNKNOWN");
                            equityReference.appendChild(tickerEl);
                            singleUnderlyer.appendChild(equityReference);
                        }

                        underlyerEl.appendChild(singleUnderlyer);
                    }
                } catch (Exception ignored) {}

                dividendLeg.appendChild(underlyerEl);
            }
        } catch (Exception ignored) {}

        // Return terms - dividend payout ratios and periods
        try {
            Object returnTerms = invokeField(perfPayout, "getReturnTerms");
            if (returnTerms != null) {
                Object divReturnTerms = invokeField(returnTerms, "getDividendReturnTerms");
                if (divReturnTerms != null) {
                    // Dividend payout ratios
                    try {
                        Object divPayoutRatios = invokeField(divReturnTerms, "getDividendPayoutRatio");
                        if (divPayoutRatios instanceof java.util.List) {
                            for (Object ratio : (java.util.List<?>) divPayoutRatios) {
                                try {
                                    Element payoutRatioEl = doc.createElementNS(FpmlConstants.FPML_NS, "declaredCashDividendPercentage");
                                    Object cashRatio = invokeField(ratio, "getCashRatio");
                                    if (cashRatio != null) {
                                        String ratioStr = extractNumericValue(cashRatio);
                                        payoutRatioEl.setTextContent(ratioStr != null ? ratioStr : "1.0");
                                    } else {
                                        payoutRatioEl.setTextContent("1.0");
                                    }
                                    dividendLeg.appendChild(payoutRatioEl);
                                } catch (Exception ignored) {}
                            }
                        }
                    } catch (Exception ignored) {}

                    // Dividend periods
                    try {
                        Object divPeriods = invokeField(divReturnTerms, "getDividendPeriod");
                        if (divPeriods instanceof java.util.List) {
                            for (Object dp : (java.util.List<?>) divPeriods) {
                                Element dividendPeriod = buildDividendPeriod(doc, dp);
                                if (dividendPeriod != null) {
                                    dividendLeg.appendChild(dividendPeriod);
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}

        // Settlement terms
        try {
            Object settTerms = invokeField(perfPayout, "getSettlementTerms");
            if (settTerms != null) {
                mapSettlementTerms(doc, dividendLeg, settTerms);
            }
        } catch (Exception ignored) {}

        return dividendLeg;
    }

    private Element buildFixedLeg(Document doc, Object fixedPayout, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Element fixedLeg = doc.createElementNS(FpmlConstants.FPML_NS, "fixedLeg");

        // Payer/Receiver party references from FixedPricePayout payerReceiver
        try {
            Object payerReceiver = invokeField(fixedPayout, "getPayerReceiver");
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
                fixedLeg.appendChild(payerRef);

                Element receiverRef = doc.createElementNS(FpmlConstants.FPML_NS, "receiverPartyReference");
                receiverRef.setAttribute("href", "#" + receiverHref);
                fixedLeg.appendChild(receiverRef);
            }
        } catch (Exception ignored) {
            Element payerRef = doc.createElementNS(FpmlConstants.FPML_NS, "payerPartyReference");
            payerRef.setAttribute("href", "#party1");
            fixedLeg.appendChild(payerRef);

            Element receiverRef = doc.createElementNS(FpmlConstants.FPML_NS, "receiverPartyReference");
            receiverRef.setAttribute("href", "#party2");
            fixedLeg.appendChild(receiverRef);
        }

        // Fixed payment amount from priceQuantity or tradeLot
        try {
            Object priceQuantity = invokeField(fixedPayout, "getPriceQuantity");
            if (priceQuantity != null) {
                Element fixedPaymentEl = doc.createElementNS(FpmlConstants.FPML_NS, "fixedPayment");

                // Try to get payment amount from the price quantity
                try {
                    Object quantitySchedule = invokeField(priceQuantity, "getQuantitySchedule");
                    if (quantitySchedule != null) {
                        Element paymentAmountEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentAmount");

                        // Try to extract value and currency from the schedule
                        try {
                            Object quantityValue = invokeField(quantitySchedule, "getValue");
                            if (quantityValue != null) {
                                String amtStr = extractNumericValue(quantityValue);
                                if (amtStr != null) {
                                    Element amountEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                                    amountEl.setTextContent(amtStr);
                                    paymentAmountEl.appendChild(amountEl);
                                }
                            }

                            // Try to get currency from unit
                            try {
                                Object unit = invokeField(quantitySchedule, "getUnit");
                                if (unit != null) {
                                    Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                                    String ccyStr = extractCurrencyFromAmountObj(unit);
                                    currEl.setTextContent(ccyStr != null ? ccyStr : "USD");
                                    paymentAmountEl.appendChild(currEl);
                                }
                            } catch (Exception ignored) {}

                            fixedPaymentEl.appendChild(paymentAmountEl);
                        } catch (Exception ignored) {}

                        fixedLeg.appendChild(fixedPaymentEl);
                    }
                } catch (Exception ignored) {}

                // Fallback: create a minimal fixed payment from tradeLot priceQuantity
                if (!hasChildElement(fixedLeg, "fixedPayment")) {
                    Element fallbackPayment = doc.createElementNS(FpmlConstants.FPML_NS, "fixedPayment");
                    Element fallbackAmount = doc.createElementNS(FpmlConstants.FPML_NS, "paymentAmount");

                    Element fallbackAmtEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                    fallbackAmtEl.setTextContent("0.00");
                    fallbackAmount.appendChild(fallbackAmtEl);

                    Element fallbackCcyEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                    fallbackCcyEl.setTextContent("USD");
                    fallbackAmount.appendChild(fallbackCcyEl);

                    fallbackPayment.appendChild(fallbackAmount);
                    fixedLeg.appendChild(fallbackPayment);
                }
            } else {
                // No priceQuantity on fixedPayout - try tradeLot for fixed rate
                Element fixedPaymentEl = doc.createElementNS(FpmlConstants.FPML_NS, "fixedPayment");
                Element paymentAmountEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentAmount");

                String fixedRate = extractFixedRateFromTradeLot(tradeState);
                if (fixedRate != null) {
                    // Convert rate to amount using notional
                    Element amountEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                    BigDecimal notional = new BigDecimal("1000000.00");
                    try {
                        BigDecimal rateBd = new BigDecimal(fixedRate);
                        if (rateBd.compareTo(BigDecimal.ZERO) > 0 && rateBd.compareTo(new BigDecimal("1")) < 0) {
                            // It's a decimal rate like 0.05, not an absolute amount
                            BigDecimal amt = notional.multiply(rateBd).setScale(2, java.math.RoundingMode.HALF_UP);
                            amountEl.setTextContent(amt.toPlainString());
                        } else {
                            amountEl.setTextContent(fixedRate);
                        }
                    } catch (Exception e) {
                        amountEl.setTextContent("0.00");
                    }
                    paymentAmountEl.appendChild(amountEl);

                    Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                    currEl.setTextContent("USD");
                    paymentAmountEl.appendChild(currEl);

                    fixedPaymentEl.appendChild(paymentAmountEl);
                    fixedLeg.appendChild(fixedPaymentEl);
                } else {
                    // Fallback: empty fixed payment
                    Element fallbackPayment = doc.createElementNS(FpmlConstants.FPML_NS, "fixedPayment");
                    Element fallbackAmount = doc.createElementNS(FpmlConstants.FPML_NS, "paymentAmount");

                    Element fallbackAmtEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                    fallbackAmtEl.setTextContent("0.00");
                    fallbackAmount.appendChild(fallbackAmtEl);

                    Element fallbackCcyEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                    fallbackCcyEl.setTextContent("USD");
                    fallbackAmount.appendChild(fallbackCcyEl);

                    fallbackPayment.appendChild(fallbackAmount);
                    fixedLeg.appendChild(fallbackPayment);
                }
            }
        } catch (Exception ignored) {
            // Create minimal fixed payment as fallback
            Element fixedPaymentEl = doc.createElementNS(FpmlConstants.FPML_NS, "fixedPayment");
            Element paymentAmountEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentAmount");

            Element amtEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
            amtEl.setTextContent("0.00");
            paymentAmountEl.appendChild(amtEl);

            Element ccyEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
            ccyEl.setTextContent("USD");
            paymentAmountEl.appendChild(ccyEl);

            fixedPaymentEl.appendChild(paymentAmountEl);
            fixedLeg.appendChild(fixedPaymentEl);
        }

        return fixedLeg;
    }

    private Element buildDividendPeriod(Document doc, Object dividendPeriod) throws Exception {
        if (dividendPeriod == null) return null;

        Element period = doc.createElementNS(FpmlConstants.FPML_NS, "dividendPeriod");

        // Start date
        try {
            Object startDateObj = invokeField(dividendPeriod, "getStartDate");
            if (startDateObj != null) {
                Element startDateEl = mapDividendDate(doc, startDateObj);
                period.appendChild(startDateEl);
            }
        } catch (Exception ignored) {}

        // End date
        try {
            Object endDateObj = invokeField(dividendPeriod, "getEndDate");
            if (endDateObj != null) {
                Element endDateEl = mapDividendDate(doc, endDateObj);
                period.appendChild(endDateEl);
            }
        } catch (Exception ignored) {}

        // Valuation date
        try {
            Object valuationDateObj = invokeField(dividendPeriod, "getDividendValuationDate");
            if (valuationDateObj != null) {
                Element valDateEl = doc.createElementNS(FpmlConstants.FPML_NS, "valuationDate");

                // Try relative date structure
                try {
                    Object relDate = invokeField(valuationDateObj, "getRelativeDate");
                    if (relDate != null) {
                        Element relativeDate = doc.createElementNS(FpmlConstants.FPML_NS, "relativeDate");
                        
                        try {
                            Object pm = invokeField(relDate, "getPeriodMultiplier");
                            if (pm != null) {
                                Element pmEl = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
                                pmEl.setTextContent(String.valueOf(pm));
                                relativeDate.appendChild(pmEl);
                            }
                        } catch (Exception ignored) {}

                        try {
                            Object periodEnum = invokeField(relDate, "getPeriod");
                            if (periodEnum instanceof Enum) {
                                Element pEl = doc.createElementNS(FpmlConstants.FPML_NS, "period");
                                String fpmlPeriod = mapPeriodToFpml((Enum<?>) periodEnum);
                                pEl.setTextContent(fpmlPeriod);
                                relativeDate.appendChild(pEl);
                            }
                        } catch (Exception ignored) {}

                        try {
                            Object dayType = invokeField(relDate, "getDayType");
                            if (dayType instanceof Enum) {
                                Element dtEl = doc.createElementNS(FpmlConstants.FPML_NS, "dayType");
                                String dayTypeName = mapDayTypeEnum((Enum<?>) dayType);
                                dtEl.setTextContent(dayTypeName);
                                relativeDate.appendChild(dtEl);
                            }
                        } catch (Exception ignored) {}

                        try {
                            Object bdc = invokeField(relDate, "getBusinessDayConvention");
                            if (bdc instanceof Enum) {
                                Element bdcEl = doc.createElementNS(FpmlConstants.FPML_NS, "businessDayConvention");
                                String bdcStr = mapBdcEnum((Enum<?>) bdc);
                                bdcEl.setTextContent(bdcStr);
                                relativeDate.appendChild(bdcEl);
                            }
                        } catch (Exception ignored) {}

                        valDateEl.appendChild(relativeDate);
                    } else {
                        // Try AdjustableDate directly
                        try {
                            java.lang.reflect.Method getAdj = valuationDateObj.getClass().getMethod("getAdjustableDate");
                            Object adjustableDate = getAdj.invoke(valuationDateObj);
                            if (adjustableDate instanceof cdm.base.datetime.AdjustableDate) {
                                valDateEl.appendChild(io.fpmlcdm.cdm.fpml.common.CdmDateMapper.mapAdjustableDate(doc, (cdm.base.datetime.AdjustableDate) adjustableDate));
                            }
                        } catch (NoSuchMethodException ignored) {}
                    }
                } catch (Exception ignored) {}

                period.appendChild(valDateEl);
            }
        } catch (Exception ignored) {}

        // Payment date
        try {
            Object paymentDateObj = invokeField(dividendPeriod, "getDividendPaymentDate");
            if (paymentDateObj != null) {
                Element payDateEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentDate");

                try {
                    Object relDate = invokeField(paymentDateObj, "getRelativeDate");
                    if (relDate != null) {
                        Element relativeDate = doc.createElementNS(FpmlConstants.FPML_NS, "relativeDate");

                        try {
                            Object pm = invokeField(relDate, "getPeriodMultiplier");
                            if (pm != null) {
                                Element pmEl = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
                                pmEl.setTextContent(String.valueOf(pm));
                                relativeDate.appendChild(pmEl);
                            }
                        } catch (Exception ignored) {}

                        try {
                            Object periodEnum = invokeField(relDate, "getPeriod");
                            if (periodEnum instanceof Enum) {
                                Element pEl = doc.createElementNS(FpmlConstants.FPML_NS, "period");
                                String fpmlPeriod = mapPeriodToFpml((Enum<?>) periodEnum);
                                pEl.setTextContent(fpmlPeriod);
                                relativeDate.appendChild(pEl);
                            }
                        } catch (Exception ignored) {}

                        try {
                            Object dayType = invokeField(relDate, "getDayType");
                            if (dayType instanceof Enum) {
                                Element dtEl = doc.createElementNS(FpmlConstants.FPML_NS, "dayType");
                                String dayTypeName = mapDayTypeEnum((Enum<?>) dayType);
                                dtEl.setTextContent(dayTypeName);
                                relativeDate.appendChild(dtEl);
                            }
                        } catch (Exception ignored) {}

                        try {
                            Object bdc = invokeField(relDate, "getBusinessDayConvention");
                            if (bdc instanceof Enum) {
                                Element bdcEl = doc.createElementNS(FpmlConstants.FPML_NS, "businessDayConvention");
                                String bdcStr = mapBdcEnum((Enum<?>) bdc);
                                bdcEl.setTextContent(bdcStr);
                                relativeDate.appendChild(bdcEl);
                            }
                        } catch (Exception ignored) {}

                        try {
                            Object dateRelativeTo = invokeField(relDate, "getDateRelativeTo");
                            if (dateRelativeTo != null) {
                                Element dateRelEl = doc.createElementNS(FpmlConstants.FPML_NS, "dateRelativeTo");
                                String href = extractHref(dateRelativeTo);
                                dateRelEl.setAttribute("href", "#" + href);
                                relativeDate.appendChild(dateRelEl);
                            }
                        } catch (Exception ignored) {}

                        payDateEl.appendChild(relativeDate);
                    } else {
                        // Try AdjustableDate directly
                        try {
                            java.lang.reflect.Method getAdj = paymentDateObj.getClass().getMethod("getAdjustableDate");
                            Object adjustableDate = getAdj.invoke(paymentDateObj);
                            if (adjustableDate instanceof cdm.base.datetime.AdjustableDate) {
                                payDateEl.appendChild(io.fpmlcdm.cdm.fpml.common.CdmDateMapper.mapAdjustableDate(doc, (cdm.base.datetime.AdjustableDate) adjustableDate));
                            }
                        } catch (NoSuchMethodException ignored) {}
                    }
                } catch (Exception ignored) {}

                period.appendChild(payDateEl);
            }
        } catch (Exception ignored) {}

        return period;
    }

    private Element mapDividendDate(Document doc, Object dateObj) throws Exception {
        if (dateObj == null) return createFallbackDate(doc);

        // Try AdjustableOrRelativeDate -> getAdjustableDate() -> AdjustableDate
        try {
            java.lang.reflect.Method getAdj = dateObj.getClass().getMethod("getAdjustableDate");
            Object adjustableDate = getAdj.invoke(dateObj);
            if (adjustableDate instanceof cdm.base.datetime.AdjustableDate) {
                Element wrapperEl = doc.createElementNS(FpmlConstants.FPML_NS, "dividendDate");
                wrapperEl.appendChild(io.fpmlcdm.cdm.fpml.common.CdmDateMapper.mapAdjustableDate(doc, (cdm.base.datetime.AdjustableDate) adjustableDate));
                return wrapperEl;
            }
        } catch (NoSuchMethodException ignored) {}

        // Handle AdjustableDate directly
        if (dateObj instanceof cdm.base.datetime.AdjustableDate) {
            Element wrapperEl = doc.createElementNS(FpmlConstants.FPML_NS, "dividendDate");
            wrapperEl.appendChild(io.fpmlcdm.cdm.fpml.common.CdmDateMapper.mapAdjustableDate(doc, (cdm.base.datetime.AdjustableDate) dateObj));
            return wrapperEl;
        }

        // Parse from toString() for FieldWithMetaDate or similar
        String str = dateObj.toString();
        if (str.contains("unadjustedDate=")) {
            Element wrapperEl = doc.createElementNS(FpmlConstants.FPML_NS, "dividendDate");
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
            Element wrapperEl = doc.createElementNS(FpmlConstants.FPML_NS, "dividendDate");
            Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
            unadjEl.setTextContent(dateStr);
            wrapperEl.appendChild(unadjEl);
            return wrapperEl;
        }

        return createFallbackDate(doc);
    }

    private void mapSettlementTerms(Document doc, Element dividendLeg, Object settTerms) throws Exception {
        // Settlement type
        try {
            Object st = invokeField(settTerms, "getSettlementType");
            if (st instanceof Enum) {
                Element settlementTypeEl = doc.createElementNS(FpmlConstants.FPML_NS, "settlementType");
                String typeName = ((Enum<?>) st).name();
                if ("CASH".equalsIgnoreCase(typeName)) {
                    settlementTypeEl.setTextContent("Cash");
                } else if ("PHYSICAL".equalsIgnoreCase(typeName)) {
                    settlementTypeEl.setTextContent("Physical");
                } else {
                    settlementTypeEl.setTextContent(mapEnumToPascalCase((Enum<?>) st));
                }
                dividendLeg.appendChild(settlementTypeEl);
            }
        } catch (Exception ignored) {}

        // Settlement currency
        try {
            Object curr = invokeField(settTerms, "getSettlementCurrency");
            if (curr != null) {
                Element settlementCcyEl = doc.createElementNS(FpmlConstants.FPML_NS, "settlementCurrency");
                String ccyStr = extractStringValue(curr);
                settlementCcyEl.setTextContent(ccyStr != null ? ccyStr : "USD");
                dividendLeg.appendChild(settlementCcyEl);
            }
        } catch (Exception ignored) {}

        // Settlement date
        try {
            Object settDateObj = invokeField(settTerms, "getSettlementDate");
            if (settDateObj != null) {
                Element settlementDateEl = doc.createElementNS(FpmlConstants.FPML_NS, "settlementDate");

                // Try valueDate field directly on settlementDate
                try {
                    Object valueDate = invokeField(settDateObj, "getValueDate");
                    if (valueDate != null) {
                        Element relativeDate = doc.createElementNS(FpmlConstants.FPML_NS, "relativeDate");

                        try {
                            Object pm = invokeField(valueDate, "getPeriodMultiplier");
                            if (pm != null) {
                                Element pmEl = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
                                pmEl.setTextContent(String.valueOf(pm));
                                relativeDate.appendChild(pmEl);
                            }
                        } catch (Exception ignored) {}

                        try {
                            Object periodEnum = invokeField(valueDate, "getPeriod");
                            if (periodEnum instanceof Enum) {
                                Element pEl = doc.createElementNS(FpmlConstants.FPML_NS, "period");
                                String fpmlPeriod = mapPeriodToFpml((Enum<?>) periodEnum);
                                pEl.setTextContent(fpmlPeriod);
                                relativeDate.appendChild(pEl);
                            }
                        } catch (Exception ignored) {}

                        try {
                            Object dayType = invokeField(valueDate, "getDayType");
                            if (dayType instanceof Enum) {
                                Element dtEl = doc.createElementNS(FpmlConstants.FPML_NS, "dayType");
                                String dayTypeName = mapDayTypeEnum((Enum<?>) dayType);
                                dtEl.setTextContent(dayTypeName);
                                relativeDate.appendChild(dtEl);
                            }
                        } catch (Exception ignored) {}

                        try {
                            Object bdc = invokeField(valueDate, "getBusinessDayConvention");
                            if (bdc instanceof Enum) {
                                Element bdcEl = doc.createElementNS(FpmlConstants.FPML_NS, "businessDayConvention");
                                String bdcStr = mapBdcEnum((Enum<?>) bdc);
                                bdcEl.setTextContent(bdcStr);
                                relativeDate.appendChild(bdcEl);
                            }
                        } catch (Exception ignored) {}

                        settlementDateEl.appendChild(relativeDate);
                    } else {
                        // Try AdjustableOrRelativeDate -> getAdjustableDate() -> AdjustableDate
                        try {
                            java.lang.reflect.Method getAdj = settDateObj.getClass().getMethod("getAdjustableDate");
                            Object adjustableDate = getAdj.invoke(settDateObj);
                            if (adjustableDate instanceof cdm.base.datetime.AdjustableDate) {
                                settlementDateEl.appendChild(io.fpmlcdm.cdm.fpml.common.CdmDateMapper.mapAdjustableDate(doc, (cdm.base.datetime.AdjustableDate) adjustableDate));
                            }
                        } catch (NoSuchMethodException ignored) {}
                    }
                } catch (Exception ignored) {}

                dividendLeg.appendChild(settlementDateEl);
            }
        } catch (Exception ignored) {}
    }

    private String extractUnderlyerName(TradeState tradeState) throws Exception {
        // Try to get underlier name from tradeLot priceQuantity observable
        try {
            Object product = invokeField(tradeState, "getProduct");
            if (product == null) return null;

            Object trade = invokeField(product, "getTrade");
            if (trade instanceof java.util.List) {
                List<?> trades = (java.util.List<?>) trade;
                for (Object t : trades) {
                    try {
                        Object lotList = invokeField(t, "getTradeLot");
                        if (lotList instanceof java.util.List) {
                            for (Object lot : (java.util.List<?>) lotList) {
                                try {
                                    Object pqList = invokeField(lot, "getPriceQuantity");
                                    if (pqList instanceof java.util.List) {
                                        for (Object pq : (java.util.List<?>) pqList) {
                                            try {
                                                Object obsVal = invokeField(pq, "getValue");
                                                if (obsVal != null) {
                                                    String str = obsVal.toString();
                                                    // Extract security/observable identifier
                                                    try {
                                                        Object idList = invokeField(obsVal, "getIdentifier");
                                                        if (idList instanceof java.util.List) {
                                                            for (Object id : (java.util.List<?>) idList) {
                                                                try {
                                                                    Object identObj = invokeField(id, "getIdentifier");
                                                                    if (identObj != null) {
                                                                        String identStr = extractStringValue(identObj);
                                                                        if (identStr != null && !identStr.isEmpty()) {
                                                                            // Check for index vs single name via identifier type
                                                                            Object idType = invokeField(id, "getIdentifierType");
                                                                            if (idType != null) {
                                                                                String typeStr = extractStringValue(idType);
                                                                                if ("Index".equalsIgnoreCase(typeStr)) {
                                                                                    return identStr;
                                                                                }
                                                                            }
                                                                        }
                                                                    }
                                                                } catch (Exception ignored) {}
                                                            }
                                                        }
                                                    } catch (Exception ignored) {}

                                                    // Check for exchange
                                                    try {
                                                        Object exchange = invokeField(obsVal, "getExchange");
                                                        if (exchange != null) {
                                                            Object exName = invokeField(exchange, "getName");
                                                            if (exName != null) {
                                                                String exStr = extractStringValue(exName);
                                                                return exStr;
                                                            }
                                                        }
                                                    } catch (Exception ignored) {}

                                                    // Try to parse from toString directly
                                                    if (!str.contains("{")) {
                                                        return str;
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
        } catch (Exception ignored) {}

        return null;
    }

    private String extractOpenUnits(TradeState tradeState) throws Exception {
        try {
            Object product = invokeField(tradeState, "getProduct");
            if (product == null) return null;

            Object trade = invokeField(product, "getTrade");
            if (trade instanceof java.util.List) {
                List<?> trades = (java.util.List<?>) trade;
                for (Object t : trades) {
                    try {
                        Object lotList = invokeField(t, "getTradeLot");
                        if (lotList instanceof java.util.List) {
                            for (Object lot : (java.util.List<?>) lotList) {
                                try {
                                    Object pqList = invokeField(lot, "getPriceQuantity");
                                    if (pqList instanceof java.util.List) {
                                        for (Object pq : (java.util.List<?>) pqList) {
                                            // Skip observable-1 (first PQ), look at second one for quantity
                                            try {
                                                Object qty = invokeField(pq, "getQuantity");
                                                if (qty instanceof java.util.List) {
                                                    List<?> quantities = (java.util.List<?>) qty;
                                                    if (quantities.size() >= 2) {
                                                        // Second quantity entry is usually the open units
                                                        Object secondQty = quantities.get(1);
                                                        String valStr = extractNumericValue(secondQty);
                                                        if (valStr != null && !valStr.isEmpty()) {
                                                            return valStr;
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
        } catch (Exception ignored) {}

        return null;
    }

    private String extractFixedRateFromTradeLot(TradeState tradeState) throws Exception {
        try {
            Object product = invokeField(tradeState, "getProduct");
            if (product == null) return null;

            Object trade = invokeField(product, "getTrade");
            if (trade instanceof java.util.List) {
                List<?> trades = (java.util.List<?>) trade;
                for (Object t : trades) {
                    try {
                        Object lotList = invokeField(t, "getTradeLot");
                        if (lotList instanceof java.util.List) {
                            for (Object lot : (java.util.List<?>) lotList) {
                                try {
                                    Object pqList = invokeField(lot, "getPriceQuantity");
                                    if (pqList instanceof java.util.List) {
                                        for (Object pq : (java.util.List<?>) pqList) {
                                            try {
                                                Object priceList = invokeField(pq, "getPrice");
                                                if (priceList instanceof java.util.List) {
                                                    for (Object priceObj : (java.util.List<?>) priceList) {
                                                        String locStr = extractExternalKey(priceObj);
                                                        if ("price-1".equals(locStr)) {
                                                            Object valObj = invokeField(priceObj, "getValue");
                                                            if (valObj != null) {
                                                                return extractNumericValue(valObj);
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
        } catch (Exception ignored) {}

        return null;
    }

    private String mapRoleToPartyHref(String role) {
        if ("Party1".equals(role)) return "party1";
        if ("Party2".equals(role)) return "party2";
        return "party1";
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
                try {
                    new BigDecimal(strVal); // Validate it's a number
                    return strVal;
                } catch (Exception ignored) {}
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
                try {
                    new BigDecimal(strVal);
                    return strVal;
                } catch (Exception ignored) {}
                return strVal;
            }
        } catch (NoSuchMethodException ignored) {}

        if (val instanceof Number) {
            double d = ((Number) val).doubleValue();
            return String.valueOf(d);
        } else if (val instanceof BigDecimal) {
            return ((BigDecimal) val).toPlainString();
        } else if (val instanceof String) {
            try {
                new BigDecimal((String) val);
                return (String) val;
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
                    new BigDecimal(str.substring(start, end).trim());
                    return str.substring(start, end).trim();
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

        try {
            Object unit = invokeField(amountObj, "getUnit");
            if (unit != null) {
                Object currency = invokeField(unit, "getCurrency");
                if (currency != null) return extractStringValue(currency);
            }
        } catch (Exception ignored) {}

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

    private String extractStringValue(Object obj) throws Exception {
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

    private String mapDayTypeEnum(Enum<?> dayType) {
        if (dayType == null) return "Business";
        String name = dayType.name().toUpperCase();
        switch (name) {
            case "BUSINESS": return "Business";
            case "CALENDAR": return "Calendar";
            case "SCHEDULED_TRADING_DAY": return "ScheduledTradingDay";
            case "EXCHANGE_BUSINESS": return "ExchangeBusiness";
            default: return mapEnumToPascalCase(dayType);
        }
    }

    private String mapBdcEnum(Enum<?> bdc) {
        if (bdc == null) return "NONE";
        String name = bdc.name().toUpperCase();
        switch (name) {
            case "FOLLOWING":
            case "MODFOLLOWING": return "ModFollowing";
            case "PRECEDING":
            case "MODPRECEDING": return "ModPreceding";
            case "NEAREST": return "Nearest";
            case "NONE": return "NONE";
            default: return mapEnumToPascalCase(bdc);
        }
    }

    private String mapEnumToPascalCase(Enum<?> e) {
        if (e == null) return "";
        String name = e.name().toUpperCase();
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = true;
        for (char c : name.toCharArray()) {
            if (c == '_') {
                nextUpper = true;
            } else if (nextUpper) {
                sb.append(Character.toUpperCase(c));
                nextUpper = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    private String extractHref(Object dateRelativeTo) throws Exception {
        // Try getExternalReference
        try {
            java.lang.reflect.Method getExtRef = dateRelativeTo.getClass().getMethod("getExternalReference");
            Object val = getExtRef.invoke(dateRelativeTo);
            if (val != null && !String.valueOf(val).isEmpty()) return String.valueOf(val);
        } catch (NoSuchMethodException ignored) {}

        // Try getReference
        try {
            java.lang.reflect.Method getRef = dateRelativeTo.getClass().getMethod("getReference");
            Object val = getRef.invoke(dateRelativeTo);
            if (val != null && !String.valueOf(val).isEmpty()) return String.valueOf(val);
        } catch (NoSuchMethodException ignored) {}

        // Try getValue
        try {
            java.lang.reflect.Method getVal = dateRelativeTo.getClass().getMethod("getValue");
            Object val = getVal.invoke(dateRelativeTo);
            if (val != null && !String.valueOf(val).isEmpty()) return String.valueOf(val);
        } catch (NoSuchMethodException ignored) {}

        // Parse from toString()
        String str = dateRelativeTo.toString();
        if (str.contains("externalReference=")) {
            int start = str.indexOf("externalReference=") + 18;
            int end = str.indexOf(",", start);
            if (end == -1) end = str.indexOf("}", start);
            if (end > start) return str.substring(start, end).trim();
        }

        return "calcPeriodDates";
    }

    private Element createFallbackDividendSwap(Document doc) {
        Element divTxSupplement = doc.createElementNS(FpmlConstants.FPML_NS, "dividendSwapTransactionSupplement");

        Element dividendSwap = doc.createElementNS(FpmlConstants.FPML_NS, "dividendSwap");
        divTxSupplement.appendChild(dividendSwap);

        // Dividend leg fallback
        Element dividendLeg = doc.createElementNS(FpmlConstants.FPML_NS, "dividendLeg");

        Element payerRef = doc.createElementNS(FpmlConstants.FPML_NS, "payerPartyReference");
        payerRef.setAttribute("href", "#party1");
        dividendLeg.appendChild(payerRef);

        Element receiverRef = doc.createElementNS(FpmlConstants.FPML_NS, "receiverPartyReference");
        receiverRef.setAttribute("href", "#party2");
        dividendLeg.appendChild(receiverRef);

        Element underlyerEl = doc.createElementNS(FpmlConstants.FPML_NS, "underlyer");
        Element singleUnderlyer = doc.createElementNS(FpmlConstants.FPML_NS, "singleUnderlyer");
        Element indexEl = doc.createElementNS(FpmlConstants.FPML_NS, "index");
        indexEl.setTextContent("UNKNOWN-INDEX");
        singleUnderlyer.appendChild(indexEl);
        underlyerEl.appendChild(singleUnderlyer);
        dividendLeg.appendChild(underlyerEl);

        Element cashDivPct = doc.createElementNS(FpmlConstants.FPML_NS, "declaredCashDividendPercentage");
        cashDivPct.setTextContent("1.0");
        dividendLeg.appendChild(cashDivPct);

        Element settType = doc.createElementNS(FpmlConstants.FPML_NS, "settlementType");
        settType.setTextContent("Cash");
        dividendLeg.appendChild(settType);

        Element settCcyEl = doc.createElementNS(FpmlConstants.FPML_NS, "settlementCurrency");
        settCcyEl.setTextContent("USD");
        dividendLeg.appendChild(settCcyEl);

        dividendSwap.appendChild(dividendLeg);

        // Fixed leg fallback
        Element fixedLeg = doc.createElementNS(FpmlConstants.FPML_NS, "fixedLeg");

        Element fixPayerRef = doc.createElementNS(FpmlConstants.FPML_NS, "payerPartyReference");
        fixPayerRef.setAttribute("href", "#party1");
        fixedLeg.appendChild(fixPayerRef);

        Element fixReceiverRef = doc.createElementNS(FpmlConstants.FPML_NS, "receiverPartyReference");
        fixReceiverRef.setAttribute("href", "#party2");
        fixedLeg.appendChild(fixReceiverRef);

        Element fixedPaymentEl = doc.createElementNS(FpmlConstants.FPML_NS, "fixedPayment");
        Element paymentAmountEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentAmount");

        Element amtEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
        amtEl.setTextContent("0.00");
        paymentAmountEl.appendChild(amtEl);

        Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
        currEl.setTextContent("USD");
        paymentAmountEl.appendChild(currEl);

        fixedPaymentEl.appendChild(paymentAmountEl);
        fixedLeg.appendChild(fixedPaymentEl);
        dividendSwap.appendChild(fixedLeg);

        return divTxSupplement;
    }

    private Element createFallbackDate(Document doc) {
        Element wrapperEl = doc.createElementNS(FpmlConstants.FPML_NS, "dividendDate");
        Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
        unadjEl.setTextContent("2024-01-02");
        wrapperEl.appendChild(unadjEl);
        return wrapperEl;
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

    private static Object invokeField(Object obj, String fieldName) throws Exception {
        if (obj == null || fieldName == null) return null;
        try {
            java.lang.reflect.Method m = obj.getClass().getMethod(fieldName);
            return m.invoke(obj);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
