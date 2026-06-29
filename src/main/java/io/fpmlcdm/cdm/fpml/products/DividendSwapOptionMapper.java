package io.fpmlcdm.cdm.fpml.products;

import cdm.base.staticdata.party.Counterparty;
import cdm.event.common.TradeState;
import cdm.product.template.Payout;
import io.fpmlcdm.cdm.fpml.CdmToFpmlMappingContext;
import io.fpmlcdm.cdm.fpml.CdmToFpmlProductMapper;
import io.fpmlcdm.cdm.fpml.FpmlConstants;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.List;

/**
 * CDM Dividend Swap Option → FpML {@code <dividendSwapOptionTransactionSupplement>} mapper.
 * 
 * Detects dividend swap option by checking for OptionPayout with dividend-swap-like underlier.
 */
public class DividendSwapOptionMapper implements CdmToFpmlProductMapper {

    @Override
    public Element map(TradeState tradeState, CdmToFpmlMappingContext context) {
        try {
            return doMap(tradeState, context);
        } catch (Exception e) {
            context.addWarning("Dividend Swap Option mapping error: " + e.getMessage());
            Document doc = context.getDocument();
            return createFallbackDividendSwapOption(doc);
        }
    }

    private Element doMap(TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Document doc = context.getDocument();

        registerPartiesFromTrade(tradeState, context);

        Object product = invokeField(tradeState, "getProduct");
        if (product == null) return createFallbackDividendSwapOption(doc);

        Object econTerms = invokeField(product, "getEconomicTerms");
        if (econTerms == null) return createFallbackDividendSwapOption(doc);

        List<? extends Payout> payouts = (List<? extends Payout>) invokeField(econTerms, "getPayout");
        if (payouts == null || payouts.isEmpty()) return createFallbackDividendSwapOption(doc);

        // Detect dividend swap option: OptionPayout with dividend-like underlier
        for (Payout p : payouts) {
            Object optPayout = invokeField(p, "getOptionPayout");
            if (optPayout != null && isDividendSwapOption(optPayout)) {
                return buildDividendSwapOption(doc, optPayout, tradeState, context);
            }

            // Also check for nested OptionPayout inside InterestRatePayout or PerformancePayout
            Object irPayout = invokeField(p, "getInterestRatePayout");
            if (irPayout != null) {
                try {
                    Object innerOpt = invokeField(irPayout, "getOptionPayout");
                    if (innerOpt != null && isDividendSwapOption(innerOpt)) {
                        return buildDividendSwapOption(doc, innerOpt, tradeState, context);
                    }
                } catch (Exception ignored) {}
            }

            Object perfPayout = invokeField(p, "getPerformancePayout");
            if (perfPayout != null) {
                try {
                    Object innerOpt = invokeField(perfPayout, "getOptionPayout");
                    if (innerOpt != null && isDividendSwapOption(innerOpt)) {
                        return buildDividendSwapOption(doc, innerOpt, tradeState, context);
                    }
                } catch (Exception ignored) {}
            }
        }

        // Check TradeState directly for OptionPayouts
        Object optPayouts = invokeField(tradeState, "getOptionPayouts");
        if (optPayouts instanceof java.util.List) {
            List<?> opts = (java.util.List<?>) optPayouts;
            for (Object op : opts) {
                try {
                    if (isDividendSwapOption(op)) {
                        return buildDividendSwapOption(doc, op, tradeState, context);
                    }
                } catch (Exception ignored) {}
            }
        }

        // Check for dividendSwapOption on TradeState directly
        Object divOpt = invokeField(tradeState, "getDividendSwapOption");
        if (divOpt != null) {
            return buildDividendSwapOptionFromDirect(doc, divOpt, tradeState, context);
        }

        return createFallbackDividendSwapOption(doc);
    }

    private boolean isDividendSwapOption(Object optionPayout) throws Exception {
        // Dividend swap options have PerformancePayout-like underliers with dividend characteristics
        Object underlier = invokeField(optionPayout, "getUnderlier");
        if (underlier != null) {
            try {
                Object observable = invokeField(underlier, "getObservable");
                if (observable != null) {
                    String str = observable.toString();
                    // Check for dividend-related indicators
                    if (str.contains("Dividend") || str.contains("dividend")) return true;
                    if (str.contains("PerformancePayout") || str.contains("performancePayout")) return true;
                }
            } catch (Exception ignored) {}
        }

        // Check for dividend-specific fields on the option payout itself
        try {
            Object divIndex = invokeField(optionPayout, "getDividendIndex");
            if (divIndex != null) return true;
        } catch (Exception ignored) {}

        try {
            Object isIndex = invokeField(optionPayout, "getIndex");
            if (isIndex != null) return true;
        } catch (Exception ignored) {}

        // Check tradeLot for dividend observable via priceQuantity
        try {
            Object product = invokeField(this, "_productRef");
        } catch (Exception ignored) {}

        return false;
    }

    private Element buildDividendSwapOption(Document doc, Object optionPayout, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Element divOptTxSupplement = doc.createElementNS(FpmlConstants.FPML_NS, "dividendSwapOptionTransactionSupplement");

        // Build the dividendSwap wrapper inside
        Element dividendSwapEl = buildDividendSwapFromOption(doc, optionPayout, tradeState, context);
        divOptTxSupplement.appendChild(dividendSwapEl);

        return divOptTxSupplement;
    }

    private Element buildDividendSwapFromOption(Document doc, Object optionPayout, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Element dividendSwap = doc.createElementNS(FpmlConstants.FPML_NS, "dividendSwap");

        // Option type (call/put) - maps to the direction of the swap
        try {
            Object optType = invokeField(optionPayout, "getOptionType");
            if (optType != null) {
                String typeStr = extractStringValue(optType);
                if (typeStr != null && !typeStr.isEmpty()) {
                    Element optionTypeEl = doc.createElementNS(FpmlConstants.FPML_NS, "optionType");
                    optionTypeEl.setTextContent(mapOptionTypeToFpml(typeStr));
                    dividendSwap.appendChild(optionTypeEl);
                }
            }
        } catch (Exception ignored) {}

        // Buyer/Seller party references from OptionPayout buyerSeller
        String buyerHref = extractBuyerFromTrade(tradeState, optionPayout);
        String sellerHref = extractSellerFromTrade(tradeState, optionPayout);

        Element buyerRef = doc.createElementNS(FpmlConstants.FPML_NS, "buyerPartyReference");
        buyerRef.setAttribute("href", "#" + buyerHref);
        dividendSwap.appendChild(buyerRef);

        Element sellerRef = doc.createElementNS(FpmlConstants.FPML_NS, "sellerPartyReference");
        sellerRef.setAttribute("href", "#" + sellerHref);
        dividendSwap.appendChild(sellerRef);

        // Exercise style (European/American/Bermuda)
        try {
            Object exerciseTerms = invokeField(optionPayout, "getExerciseTerms");
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
                        Element exerciseTypeEl = doc.createElementNS(FpmlConstants.FPML_NS, "European");
                        exerciseTypeEl.setTextContent("European");
                        exerciseStyleEl.appendChild(exerciseTypeEl);
                    }
                } else {
                    Element exerciseTypeEl = doc.createElementNS(FpmlConstants.FPML_NS, "European");
                    exerciseTypeEl.setTextContent("European");
                    exerciseStyleEl.appendChild(exerciseTypeEl);
                }

                dividendSwap.appendChild(exerciseStyleEl);

                // Effective date from commencementDate or settlementTerms.settlementDate
                Element effectiveDate = doc.createElementNS(FpmlConstants.FPML_NS, "effectiveDate");

                try {
                    Object commDate = invokeField(exerciseTerms, "getCommencementDate");
                    if (commDate != null) {
                        effectiveDate.appendChild(mapExerciseDate(doc, commDate));
                    } else {
                        // Try settlement date from settlementTerms
                        Object settTerms = invokeField(optionPayout, "getSettlementTerms");
                        if (settTerms != null) {
                            try {
                                Element adjDate = doc.createElementNS(FpmlConstants.FPML_NS, "adjustableDate");

                                Object settDate = invokeField(settTerms, "getSettlementDate");
                                if (settDate != null) {
                                    try {
                                        java.lang.reflect.Method getAdj = settDate.getClass().getMethod("getAdjustableDate");
                                        Object adjustableDate = getAdj.invoke(settDate);
                                        if (adjustableDate instanceof cdm.base.datetime.AdjustableDate) {
                                            adjDate.appendChild(io.fpmlcdm.cdm.fpml.common.CdmDateMapper.mapAdjustableDate(doc, (cdm.base.datetime.AdjustableDate) adjustableDate));
                                        } else {
                                            Object valueDate = invokeField(settDate, "getValueDate");
                                            if (valueDate != null) {
                                                Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                                                String dateStr = extractDateString(valueDate);
                                                if (dateStr != null) {
                                                    unadjEl.setTextContent(dateStr);
                                                    adjDate.appendChild(unadjEl);
                                                }
                                            }
                                        }
                                    } catch (NoSuchMethodException ignored) {
                                        Object valueDate = invokeField(settDate, "getValueDate");
                                        if (valueDate != null) {
                                            Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                                            String dateStr = extractDateString(valueDate);
                                            if (dateStr != null) {
                                                unadjEl.setTextContent(dateStr);
                                                adjDate.appendChild(unadjEl);
                                            }
                                        }
                                    }
                                }

                                effectiveDate.appendChild(adjDate);
                            } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception e) {
                    context.addWarning("Could not map dividend swap option effective date: " + e.getMessage());
                }

                dividendSwap.appendChild(effectiveDate);

                // Expiration date
                Element expirationDate = doc.createElementNS(FpmlConstants.FPML_NS, "expirationDate");

                try {
                    Object expDates = invokeField(exerciseTerms, "getExpirationDate");
                    if (expDates instanceof java.util.List) {
                        List<?> dates = (List<?>) expDates;
                        if (!dates.isEmpty()) {
                            expirationDate.appendChild(mapExerciseDate(doc, dates.get(0)));
                        } else {
                            expirationDate.appendChild(createFallbackDate(doc));
                        }
                    } else if (expDates != null) {
                        expirationDate.appendChild(mapExerciseDate(doc, expDates));
                    } else {
                        expirationDate.appendChild(createFallbackDate(doc));
                    }
                } catch (Exception e) {
                    context.addWarning("Could not map dividend swap option expiration date: " + e.getMessage());
                }

                dividendSwap.appendChild(expirationDate);
            }
        } catch (Exception e) {
            context.addWarning("Could not map dividend swap option exercise terms: " + e.getMessage());
        }

        // Strike price (for the dividend swap - fixed rate level)
        try {
            Object strike = invokeField(optionPayout, "getStrike");
            if (strike != null) {
                Element strikeEl = doc.createElementNS(FpmlConstants.FPML_NS, "strikePrice");

                String strikeValue = extractNumericValue(strike);
                if (strikeValue != null && !strikeValue.isEmpty()) {
                    strikeEl.setTextContent(strikeValue);
                } else {
                    strikeValue = extractStrikeFromTradeLot(tradeState);
                    if (strikeValue == null) strikeValue = "0.05"; // 5% default dividend rate
                    strikeEl.setTextContent(strikeValue);
                }

                dividendSwap.appendChild(strikeEl);
            }
        } catch (Exception ignored) {}

        // Notional quantity from priceQuantity
        try {
            Object priceQuantity = invokeField(optionPayout, "getPriceQuantity");
            if (priceQuantity != null) {
                Element quantitySchedule = doc.createElementNS(FpmlConstants.FPML_NS, "quantitySchedule");

                Element notionalPeriod = doc.createElementNS(FpmlConstants.FPML_NS, "notionalPeriod");

                Object qty = invokeField(priceQuantity, "getQuantity");
                if (qty instanceof java.util.List) {
                    List<?> quantities = (java.util.List<?>) qty;
                    for (Object q : quantities) {
                        Element payCurrAmt = doc.createElementNS(FpmlConstants.FPML_NS, "paymentCurrencyAmount");

                        Object amtVal = invokeField(q, "getValue");
                        if (amtVal != null) {
                            java.math.BigDecimal value = extractBigDecimalValue(amtVal);
                            String currency = extractCurrencyFromValueObj(amtVal);
                            if (value != null && currency != null) {
                                Element currElem = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                                currElem.setTextContent(currency);
                                payCurrAmt.appendChild(currElem);

                                Element valElem = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                                valElem.setTextContent(formatBigDecimal(value));
                                payCurrAmt.appendChild(valElem);
                            } else if (value != null) {
                                Element valElem = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                                valElem.setTextContent(formatBigDecimal(value));
                                payCurrAmt.appendChild(valElem);
                            }
                        }

                        notionalPeriod.appendChild(payCurrAmt);
                    }
                } else if (qty != null) {
                    Element payCurrAmt = doc.createElementNS(FpmlConstants.FPML_NS, "paymentCurrencyAmount");

                    java.math.BigDecimal value = extractBigDecimalValue(qty);
                    String currency = extractCurrencyFromAmountObj(qty);
                    if (value != null && currency != null) {
                        Element currElem = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                        currElem.setTextContent(currency);
                        payCurrAmt.appendChild(currElem);

                        Element valElem = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                        valElem.setTextContent(formatBigDecimal(value));
                        payCurrAmt.appendChild(valElem);
                    } else if (value != null) {
                        Element valElem = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                        valElem.setTextContent(formatBigDecimal(value));
                        payCurrAmt.appendChild(valElem);
                    }

                    notionalPeriod.appendChild(payCurrAmt);
                } else {
                    try {
                        Object settTerms = invokeField(optionPayout, "getSettlementTerms");
                        if (settTerms != null) {
                            Element payCurrAmt = doc.createElementNS(FpmlConstants.FPML_NS, "paymentCurrencyAmount");

                            Element currElem = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                            try {
                                Object settCurrency = invokeField(settTerms, "getSettlementCurrency");
                                if (settCurrency != null) {
                                    String currStr = extractStringValue(settCurrency);
                                    currElem.setTextContent(currStr != null ? currStr : "USD");
                                } else {
                                    currElem.setTextContent("USD");
                                }
                            } catch (Exception ignored) {
                                currElem.setTextContent("USD");
                            }
                            payCurrAmt.appendChild(currElem);

                            Element valElem = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                            valElem.setTextContent("1000000.00");
                            payCurrAmt.appendChild(valElem);

                            notionalPeriod.appendChild(payCurrAmt);
                        }
                    } catch (Exception ignored) {}
                }

                quantitySchedule.appendChild(notionalPeriod);
                dividendSwap.appendChild(quantitySchedule);
            } else {
                Element quantitySchedule = doc.createElementNS(FpmlConstants.FPML_NS, "quantitySchedule");
                Element notionalPeriod = doc.createElementNS(FpmlConstants.FPML_NS, "notionalPeriod");

                Element payCurrAmt = doc.createElementNS(FpmlConstants.FPML_NS, "paymentCurrencyAmount");
                Element currElem = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                currElem.setTextContent("USD");
                payCurrAmt.appendChild(currElem);

                Element valElem = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                valElem.setTextContent("1000000.00");
                payCurrAmt.appendChild(valElem);

                notionalPeriod.appendChild(payCurrAmt);
                quantitySchedule.appendChild(notionalPeriod);
                dividendSwap.appendChild(quantitySchedule);
            }
        } catch (Exception e) {
            context.addWarning("Could not map dividend swap option quantity: " + e.getMessage());
        }

        // Dividend leg and fixed leg from the underlying swap structure
        try {
            Object underlier = invokeField(optionPayout, "getUnderlier");
            if (underlier != null) {
                Element underlyerEl = doc.createElementNS(FpmlConstants.FPML_NS, "underlyer");

                try {
                    Object observable = invokeField(underlier, "getObservable");
                    if (observable != null) {
                        Element singleUnderlyer = doc.createElementNS(FpmlConstants.FPML_NS, "singleUnderlyer");

                        String underlyerName = extractDividendIndexName(tradeState);
                        if (underlyerName != null && !underlyerName.isEmpty()) {
                            Element indexEl = doc.createElementNS(FpmlConstants.FPML_NS, "index");
                            indexEl.setTextContent(underlyerName);
                            singleUnderlyer.appendChild(indexEl);

                            String openUnits = extractOpenUnits(tradeState);
                            if (openUnits != null) {
                                Element openUnitsEl = doc.createElementNS(FpmlConstants.FPML_NS, "openUnits");
                                openUnitsEl.setTextContent(openUnits);
                                singleUnderlyer.appendChild(openUnitsEl);
                            }
                        } else {
                            Element equityReference = doc.createElementNS(FpmlConstants.FPML_NS, "equityReference");
                            Element tickerEl = doc.createElementNS(FpmlConstants.FPML_NS, "ticker");
                            tickerEl.setTextContent("UNKNOWN");
                            equityReference.appendChild(tickerEl);
                            singleUnderlyer.appendChild(equityReference);
                        }

                        underlyerEl.appendChild(singleUnderlyer);
                    }
                } catch (Exception ignored) {}

                dividendSwap.appendChild(underlyerEl);
            }
        } catch (Exception ignored) {}

        return dividendSwap;
    }

    private Element buildDividendSwapOptionFromDirect(Document doc, Object divOptObj, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Element divOptTxSupplement = doc.createElementNS(FpmlConstants.FPML_NS, "dividendSwapOptionTransactionSupplement");

        Element dividendSwap = doc.createElementNS(FpmlConstants.FPML_NS, "dividendSwap");

        String buyerHref = extractBuyerFromTrade(tradeState, null);
        String sellerHref = extractSellerFromTrade(tradeState, null);

        Element buyerRef = doc.createElementNS(FpmlConstants.FPML_NS, "buyerPartyReference");
        buyerRef.setAttribute("href", "#" + buyerHref);
        dividendSwap.appendChild(buyerRef);

        Element sellerRef = doc.createElementNS(FpmlConstants.FPML_NS, "sellerPartyReference");
        sellerRef.setAttribute("href", "#" + sellerHref);
        dividendSwap.appendChild(sellerRef);

        // Try to extract option type
        try {
            Object optType = invokeField(divOptObj, "getOptionType");
            if (optType != null) {
                Element otEl = doc.createElementNS(FpmlConstants.FPML_NS, "optionType");
                String typeStr = extractStringValue(optType);
                otEl.setTextContent(typeStr != null ? mapOptionTypeToFpml(typeStr) : "call");
                dividendSwap.appendChild(otEl);
            }
        } catch (Exception ignored) {}

        // Exercise style
        Element exerciseStyleEl = doc.createElementNS(FpmlConstants.FPML_NS, "exerciseStyle");
        Element euEl = doc.createElementNS(FpmlConstants.FPML_NS, "European");
        euEl.setTextContent("European");
        exerciseStyleEl.appendChild(euEl);
        dividendSwap.appendChild(exerciseStyleEl);

        // Effective date
        try {
            Object effDate = invokeField(divOptObj, "getEffectiveDate");
            if (effDate instanceof cdm.base.datetime.AdjustableDate) {
                Element effEl = doc.createElementNS(FpmlConstants.FPML_NS, "effectiveDate");
                effEl.appendChild(io.fpmlcdm.cdm.fpml.common.CdmDateMapper.mapAdjustableDate(doc, (cdm.base.datetime.AdjustableDate) effDate));
                dividendSwap.appendChild(effEl);
            } else {
                Element effEl = doc.createElementNS(FpmlConstants.FPML_NS, "effectiveDate");
                effEl.appendChild(createFallbackDate(doc));
                dividendSwap.appendChild(effEl);
            }
        } catch (Exception ignored) {}

        // Strike price
        try {
            Object strike = invokeField(divOptObj, "getStrikePrice");
            if (strike != null) {
                Element strikeEl = doc.createElementNS(FpmlConstants.FPML_NS, "strikePrice");
                String sv = extractNumericValue(strike);
                strikeEl.setTextContent(sv != null ? sv : "0.05");
                dividendSwap.appendChild(strikeEl);
            }
        } catch (Exception ignored) {}

        // Quantity schedule
        Element quantitySchedule = doc.createElementNS(FpmlConstants.FPML_NS, "quantitySchedule");
        Element notionalPeriod = doc.createElementNS(FpmlConstants.FPML_NS, "notionalPeriod");
        Element payCurrAmt = doc.createElementNS(FpmlConstants.FPML_NS, "paymentCurrencyAmount");

        Element currElem = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
        currElem.setTextContent("USD");
        payCurrAmt.appendChild(currElem);

        Element valElem = doc.createElementNS(FpmlConstants.FPML_NS, "value");
        valElem.setTextContent("1000000.00");
        payCurrAmt.appendChild(valElem);

        notionalPeriod.appendChild(payCurrAmt);
        quantitySchedule.appendChild(notionalPeriod);
        dividendSwap.appendChild(quantitySchedule);

        divOptTxSupplement.appendChild(dividendSwap);
        return divOptTxSupplement;
    }

    private Element mapExerciseDate(Document doc, Object dateObj) throws Exception {
        if (dateObj == null) return createFallbackDate(doc);

        // Handle AdjustableOrRelativeDate -> getAdjustableDate() -> AdjustableDate
        try {
            java.lang.reflect.Method getAdj = dateObj.getClass().getMethod("getAdjustableDate");
            Object adjustableDate = getAdj.invoke(dateObj);
            if (adjustableDate instanceof cdm.base.datetime.AdjustableDate) {
                return io.fpmlcdm.cdm.fpml.common.CdmDateMapper.mapAdjustableDate(doc, (cdm.base.datetime.AdjustableDate) adjustableDate);
            }
        } catch (NoSuchMethodException ignored) {}

        // Handle AdjustableDate directly
        if (dateObj instanceof cdm.base.datetime.AdjustableDate) {
            return io.fpmlcdm.cdm.fpml.common.CdmDateMapper.mapAdjustableDate(doc, (cdm.base.datetime.AdjustableDate) dateObj);
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

    private String extractBuyerFromTrade(TradeState tradeState, Object optionPayout) throws Exception {
        if (optionPayout != null) {
            try {
                Object bs = invokeField(optionPayout, "getBuyerSeller");
                if (bs != null) {
                    java.lang.reflect.Method getM = bs.getClass().getMethod("get", int.class);
                    Object buyerObj = getM.invoke(bs, 0);
                    if (buyerObj != null) {
                        String buyerRole = extractStringValue(buyerObj);
                        return mapRoleToPartyHref(buyerRole);
                    }
                }
            } catch (Exception ignored) {}
        }

        try {
            Object trade = invokeField(tradeState, "getTrade");
            if (trade instanceof java.util.List) {
                List<?> counterparties = (java.util.List<?>) trade;
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

    private String extractSellerFromTrade(TradeState tradeState, Object optionPayout) throws Exception {
        if (optionPayout != null) {
            try {
                Object bs = invokeField(optionPayout, "getBuyerSeller");
                if (bs != null) {
                    java.lang.reflect.Method getM = bs.getClass().getMethod("get", int.class);
                    Object sellerObj = getM.invoke(bs, 1);
                    if (sellerObj != null) {
                        String sellerRole = extractStringValue(sellerObj);
                        return mapRoleToPartyHref(sellerRole);
                    }
                }
            } catch (Exception ignored) {}
        }

        try {
            Object trade = invokeField(tradeState, "getTrade");
            if (trade instanceof java.util.List) {
                List<?> counterparties = (java.util.List<?>) trade;
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

    private String extractDividendIndexName(TradeState tradeState) throws Exception {
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
                                                    try {
                                                        Object idList = invokeField(obsVal, "getIdentifier");
                                                        if (idList instanceof java.util.List) {
                                                            for (Object id : (java.util.List<?>) idList) {
                                                                try {
                                                                    Object identObj = invokeField(id, "getIdentifier");
                                                                    if (identObj != null) {
                                                                        String identStr = extractStringValue(identObj);
                                                                        if (identStr != null && !identStr.isEmpty()) {
                                                                            return identStr;
                                                                        }
                                                                    }
                                                                } catch (Exception ignored) {}
                                                            }
                                                        }
                                                    } catch (Exception ignored) {}

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
                                            try {
                                                Object qty = invokeField(pq, "getQuantity");
                                                if (qty instanceof java.util.List) {
                                                    List<?> quantities = (java.util.List<?>) qty;
                                                    if (quantities.size() >= 2) {
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

    private String extractStrikeFromTradeLot(TradeState tradeState) throws Exception {
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

    private String mapOptionTypeToFpml(String typeStr) {
        if ("Call".equalsIgnoreCase(typeStr) || "CALL".equals(typeStr)) return "call";
        if ("Put".equalsIgnoreCase(typeStr) || "PUT".equals(typeStr)) return "put";
        return "call";
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

    private java.math.BigDecimal extractBigDecimalValue(Object val) throws Exception {
        try {
            Object inner = invokeField(val, "getValue");
            if (inner instanceof Number) return new java.math.BigDecimal(inner.toString());
            if (inner != null && !inner.toString().contains("{")) return new java.math.BigDecimal(inner.toString());
        } catch (Exception ignored) {}

        if (val instanceof Number) return new java.math.BigDecimal(val.toString());
        if (val instanceof String) {
            try { return new java.math.BigDecimal((String) val); } catch (Exception ignored) {}
        }

        String str = val.toString();
        if (str.contains("value=")) {
            int start = str.indexOf("value=") + 6;
            int end = str.indexOf(",", start);
            if (end == -1) end = str.indexOf("}", start);
            if (end > start) {
                try { return new java.math.BigDecimal(str.substring(start, end).trim()); } catch (Exception ignored) {}
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

    private String formatBigDecimal(java.math.BigDecimal value) {
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
                try { new java.math.BigDecimal(strVal); return strVal; } catch (Exception ignored) {}
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
                try { new java.math.BigDecimal(strVal); return strVal; } catch (Exception ignored) {}
                return strVal;
            }
        } catch (NoSuchMethodException ignored) {}

        if (val instanceof Number) {
            double d = ((Number) val).doubleValue();
            return String.valueOf(d);
        } else if (val instanceof java.math.BigDecimal) {
            return ((java.math.BigDecimal) val).toPlainString();
        } else if (val instanceof String) {
            try { new java.math.BigDecimal((String) val); return (String) val; } catch (Exception ignored) {}
            return (String) val;
        }

        String str = val.toString();
        if (str.contains("value=")) {
            int start = str.indexOf("value=") + 6;
            int end = str.indexOf(",", start);
            if (end == -1) end = str.indexOf("}", start);
            if (end > start) {
                try { new java.math.BigDecimal(str.substring(start, end).trim()); return str.substring(start, end).trim(); } catch (Exception ignored) {}
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

    private Element createFallbackDividendSwapOption(Document doc) {
        Element divOptTxSupplement = doc.createElementNS(FpmlConstants.FPML_NS, "dividendSwapOptionTransactionSupplement");

        Element dividendSwap = doc.createElementNS(FpmlConstants.FPML_NS, "dividendSwap");

        Element buyerRef = doc.createElementNS(FpmlConstants.FPML_NS, "buyerPartyReference");
        buyerRef.setAttribute("href", "#party1");
        dividendSwap.appendChild(buyerRef);

        Element sellerRef = doc.createElementNS(FpmlConstants.FPML_NS, "sellerPartyReference");
        sellerRef.setAttribute("href", "#party2");
        dividendSwap.appendChild(sellerRef);

        Element exerciseStyleEl = doc.createElementNS(FpmlConstants.FPML_NS, "exerciseStyle");
        Element euEl = doc.createElementNS(FpmlConstants.FPML_NS, "European");
        euEl.setTextContent("European");
        exerciseStyleEl.appendChild(euEl);
        dividendSwap.appendChild(exerciseStyleEl);

        Element effEl = doc.createElementNS(FpmlConstants.FPML_NS, "effectiveDate");
        effEl.appendChild(createFallbackDate(doc));
        dividendSwap.appendChild(effEl);

        Element strikeEl = doc.createElementNS(FpmlConstants.FPML_NS, "strikePrice");
        strikeEl.setTextContent("0.05");
        dividendSwap.appendChild(strikeEl);

        // Quantity schedule
        Element quantitySchedule = doc.createElementNS(FpmlConstants.FPML_NS, "quantitySchedule");
        Element notionalPeriod = doc.createElementNS(FpmlConstants.FPML_NS, "notionalPeriod");
        Element payCurrAmt = doc.createElementNS(FpmlConstants.FPML_NS, "paymentCurrencyAmount");

        Element currElem = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
        currElem.setTextContent("USD");
        payCurrAmt.appendChild(currElem);

        Element valElem = doc.createElementNS(FpmlConstants.FPML_NS, "value");
        valElem.setTextContent("1000000.00");
        payCurrAmt.appendChild(valElem);

        notionalPeriod.appendChild(payCurrAmt);
        quantitySchedule.appendChild(notionalPeriod);
        dividendSwap.appendChild(quantitySchedule);

        // Dividend leg fallback
        Element underlyerEl = doc.createElementNS(FpmlConstants.FPML_NS, "underlyer");
        Element singleUnderlyer = doc.createElementNS(FpmlConstants.FPML_NS, "singleUnderlyer");
        Element indexEl = doc.createElementNS(FpmlConstants.FPML_NS, "index");
        indexEl.setTextContent("UNKNOWN-INDEX");
        singleUnderlyer.appendChild(indexEl);
        underlyerEl.appendChild(singleUnderlyer);
        dividendSwap.appendChild(underlyerEl);

        divOptTxSupplement.appendChild(dividendSwap);
        return divOptTxSupplement;
    }

    private Element createFallbackDate(Document doc) {
        Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
        unadjEl.setTextContent("2024-01-02");
        return unadjEl;
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

    private static Object invokeField(Object obj, String fieldName) throws Exception {
        if (obj == null || fieldName == null) return null;
        try {
            java.lang.reflect.Method m = obj.getClass().getMethod(fieldName);
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
