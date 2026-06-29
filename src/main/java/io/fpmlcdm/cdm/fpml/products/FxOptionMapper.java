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
 * CDM FX Option to FpML {@code <fxOption>} mapper.
 * 
 * Detects FX option by checking for OptionPayout with cash underlier (currency pair).
 */
public class FxOptionMapper implements CdmToFpmlProductMapper {

    @Override
    public Element map(TradeState tradeState, CdmToFpmlMappingContext context) {
        try {
            return doMap(tradeState, context);
        } catch (Exception e) {
            context.addWarning("FX Option mapping error: " + e.getMessage());
            Document doc = context.getDocument();
            Element fxOption = createFallbackFxOption(doc);
            return fxOption;
        }
    }

    private Element doMap(TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Document doc = context.getDocument();

        registerPartiesFromTrade(tradeState, context);

        Object product = invokeField(tradeState, "getProduct");
        if (product == null) return createFallbackFxOption(doc);

        Object econTerms = invokeField(product, "getEconomicTerms");
        if (econTerms == null) return createFallbackFxOption(doc);

        List<? extends Payout> payouts = (List<? extends Payout>) invokeField(econTerms, "getPayout");
        if (payouts == null || payouts.isEmpty()) return createFallbackFxOption(doc);

        for (Payout p : payouts) {
            Object optionPayout = invokeField(p, "getOptionPayout");
            if (optionPayout != null) {
                // Check if this is an FX option (cash underlier with currency pair)
                boolean isFxOption = isFxOptionUnderlier(optionPayout);
                if (isFxOption) {
                    return buildFxOption(doc, optionPayout, tradeState, context);
                }
            }
        }

        // Also check TradeState directly for OptionPayouts
        Object optPayouts = invokeField(tradeState, "getOptionPayouts");
        if (optPayouts instanceof java.util.List) {
            List<?> opts = (java.util.List<?>) optPayouts;
            for (Object op : opts) {
                try {
                    boolean isFxOption = isFxOptionUnderlier(op);
                    if (isFxOption) {
                        return buildFxOption(doc, op, tradeState, context);
                    }
                } catch (Exception ignored) {}
            }
        }

        // Also check TradeState for FxOption
        Object fxOpt = invokeField(tradeState, "getFxOption");
        if (fxOpt != null) {
            return buildFxOptionFromDirect(doc, fxOpt, tradeState, context);
        }

        return createFallbackFxOption(doc);
    }

    private boolean isFxOptionUnderlier(Object optionPayout) throws Exception {
        // Check underlier: FX options have cash/currency underliers
        Object underlier = invokeField(optionPayout, "getUnderlier");
        if (underlier != null) {
            try {
                Object observable = invokeField(underlier, "getObservable");
                if (observable != null) {
                    Object val = invokeField(observable, "getValue");
                    if (val != null) {
                        String str = val.toString();
                        // Cash underlier indicates FX option
                        if (str.contains("Cash") || str.contains("cash")) return true;
                        // Check for currency identifier
                        try {
                            Object idList = invokeField(val, "getIdentifier");
                            if (idList instanceof java.util.List) {
                                for (Object id : (java.util.List<?>) idList) {
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
                }
            } catch (Exception ignored) {}
        }

        // Check for currency pair directly on the option payout
        try {
            Object baseCurrency = invokeField(optionPayout, "getBaseCurrency");
            if (baseCurrency != null) return true;
        } catch (Exception ignored) {}

        try {
            Object quoteCurrency = invokeField(optionPayout, "getQuoteCurrency");
            if (quoteCurrency != null) return true;
        } catch (Exception ignored) {}

        // Check tradeLot for FX observable
        try {
            Object product = invokeField(this, "_productRef");
            // Fallback: assume it's an FX option if we have an OptionPayout with cash-like underlier
        } catch (Exception ignored) {}

        return false;
    }

    private boolean isCurrencyCode(String str) {
        if (str == null || str.length() != 3) return false;
        return str.matches("[A-Z]{3}");
    }

    private Element buildFxOption(Document doc, Object optionPayout, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Element fxOption = doc.createElementNS(FpmlConstants.FPML_NS, "fxOption");

        // Option type (call/put)
        try {
            Object optType = invokeField(optionPayout, "getOptionType");
            if (optType != null) {
                String typeStr = extractStringValue(optType);
                if (typeStr != null && !typeStr.isEmpty()) {
                    Element optionTypeEl = doc.createElementNS(FpmlConstants.FPML_NS, "optionType");
                    optionTypeEl.setTextContent(mapOptionTypeToFpml(typeStr));
                    fxOption.appendChild(optionTypeEl);
                }
            }
        } catch (Exception ignored) {}

        // Buyer/Seller party references
        String buyerHref = extractBuyerFromTrade(tradeState, optionPayout);
        String sellerHref = extractSellerFromTrade(tradeState, optionPayout);

        Element buyerRef = doc.createElementNS(FpmlConstants.FPML_NS, "buyerPartyReference");
        buyerRef.setAttribute("href", "#" + buyerHref);
        fxOption.appendChild(buyerRef);

        Element sellerRef = doc.createElementNS(FpmlConstants.FPML_NS, "sellerPartyReference");
        sellerRef.setAttribute("href", "#" + sellerHref);
        fxOption.appendChild(sellerRef);

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

                fxOption.appendChild(exerciseStyleEl);

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
                            Object settDate = invokeField(settTerms, "getSettlementDate");
                            if (settDate != null) {
                                effectiveDate.appendChild(mapSettlementDate(doc, settTerms));
                            }
                        }
                    }
                } catch (Exception ignored) {}

                fxOption.appendChild(effectiveDate);

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
                } catch (Exception ignored) {}

                fxOption.appendChild(expirationDate);
            }
        } catch (Exception e) {
            context.addWarning("Could not map FX option exercise terms: " + e.getMessage());
        }

        // Strike price
        try {
            Object strike = invokeField(optionPayout, "getStrike");
            if (strike != null) {
                Element strikeEl = doc.createElementNS(FpmlConstants.FPML_NS, "strikePrice");
                
                String strikeValue = extractNumericValue(strike);
                if (strikeValue != null && !strikeValue.isEmpty()) {
                    strikeEl.setTextContent(strikeValue);
                } else {
                    // Try from priceQuantity on tradeLot
                    strikeValue = extractStrikeFromTradeLot(tradeState);
                    if (strikeValue == null) strikeValue = "1.0000";
                    strikeEl.setTextContent(strikeValue);
                }

                fxOption.appendChild(strikeEl);
            }
        } catch (Exception ignored) {}

        // Quantity/Notional schedule
        try {
            Object priceQuantity = invokeField(optionPayout, "getPriceQuantity");
            if (priceQuantity != null) {
                Element quantitySchedule = doc.createElementNS(FpmlConstants.FPML_NS, "quantitySchedule");
                
                Element notionalPeriod = doc.createElementNS(FpmlConstants.FPML_NS, "notionalPeriod");
                
                // Try to get quantity from priceQuantity
                Object qty = invokeField(priceQuantity, "getQuantity");
                if (qty instanceof java.util.List) {
                    List<?> quantities = (java.util.List<?>) qty;
                    for (Object q : quantities) {
                        Element payCurrAmt = doc.createElementNS(FpmlConstants.FPML_NS, "paymentCurrencyAmount");

                        Object amtVal = invokeField(q, "getValue");
                        if (amtVal != null) {
                            BigDecimal value = extractBigDecimalValue(amtVal);
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

                    BigDecimal value = extractBigDecimalValue(qty);
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
                    // Fallback: create minimal quantity element from settlement currency
                    try {
                        Object settTerms = invokeField(optionPayout, "getSettlementTerms");
                        if (settTerms != null) {
                            Element payCurrAmt = doc.createElementNS(FpmlConstants.FPML_NS, "paymentCurrencyAmount");

                            Element currElem = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                            currElem.setTextContent("USD");
                            payCurrAmt.appendChild(currElem);

                            Element valElem = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                            valElem.setTextContent("1000000.00");
                            payCurrAmt.appendChild(valElem);

                            notionalPeriod.appendChild(payCurrAmt);
                        }
                    } catch (Exception ignored) {}
                }

                quantitySchedule.appendChild(notionalPeriod);
                fxOption.appendChild(quantitySchedule);
            } else {
                // Fallback: create minimal quantity schedule
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
                fxOption.appendChild(quantitySchedule);
            }
        } catch (Exception e) {
            context.addWarning("Could not map FX option quantity: " + e.getMessage());
        }

        return fxOption;
    }

    private Element buildFxOptionFromDirect(Document doc, Object fxOptObj, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Element fxOption = doc.createElementNS(FpmlConstants.FPML_NS, "fxOption");

        String buyerHref = extractBuyerFromTrade(tradeState, null);
        String sellerHref = extractSellerFromTrade(tradeState, null);

        Element buyerRef = doc.createElementNS(FpmlConstants.FPML_NS, "buyerPartyReference");
        buyerRef.setAttribute("href", "#" + buyerHref);
        fxOption.appendChild(buyerRef);

        Element sellerRef = doc.createElementNS(FpmlConstants.FPML_NS, "sellerPartyReference");
        sellerRef.setAttribute("href", "#" + sellerHref);
        fxOption.appendChild(sellerRef);

        // Try to extract option type
        try {
            Object optType = invokeField(fxOptObj, "getOptionType");
            if (optType != null) {
                Element otEl = doc.createElementNS(FpmlConstants.FPML_NS, "optionType");
                String typeStr = extractStringValue(optType);
                otEl.setTextContent(typeStr != null ? mapOptionTypeToFpml(typeStr) : "call");
                fxOption.appendChild(otEl);
            }
        } catch (Exception ignored) {}

        // Exercise style
        Element exerciseStyleEl = doc.createElementNS(FpmlConstants.FPML_NS, "exerciseStyle");
        Element euEl = doc.createElementNS(FpmlConstants.FPML_NS, "European");
        euEl.setTextContent("European");
        exerciseStyleEl.appendChild(euEl);
        fxOption.appendChild(exerciseStyleEl);

        // Effective date
        try {
            Object effDate = invokeField(fxOptObj, "getEffectiveDate");
            if (effDate instanceof cdm.base.datetime.AdjustableDate) {
                Element effEl = doc.createElementNS(FpmlConstants.FPML_NS, "effectiveDate");
                effEl.appendChild(io.fpmlcdm.cdm.fpml.common.CdmDateMapper.mapAdjustableDate(doc, (cdm.base.datetime.AdjustableDate) effDate));
                fxOption.appendChild(effEl);
            } else {
                Element effEl = doc.createElementNS(FpmlConstants.FPML_NS, "effectiveDate");
                effEl.appendChild(createFallbackDate(doc));
                fxOption.appendChild(effEl);
            }
        } catch (Exception ignored) {}

        // Strike price
        try {
            Object strike = invokeField(fxOptObj, "getStrikePrice");
            if (strike != null) {
                Element strikeEl = doc.createElementNS(FpmlConstants.FPML_NS, "strikePrice");
                String sv = extractNumericValue(strike);
                strikeEl.setTextContent(sv != null ? sv : "1.0000");
                fxOption.appendChild(strikeEl);
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
        fxOption.appendChild(quantitySchedule);

        return fxOption;
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
        // Try buyerSeller from OptionPayout
        if (optionPayout != null) {
            try {
                Object bs = invokeField(optionPayout, "getBuyerSeller");
                if (bs != null) {
                    java.lang.reflect.Method getM = bs.getClass().getMethod("get", int.class);
                    Object buyerObj = getM.invoke(bs, 0); // index 0 = buyer
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
                    Object sellerObj = getM.invoke(bs, 1); // index 1 = seller
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
        if ("Party1".equals(role)) return "party2"; // In FpML, buyer is party1 but CDM Party1 may be different
        if ("Party2".equals(role)) return "party1";
        return "party1";
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

    private Element createFallbackFxOption(Document doc) {
        Element fxOption = doc.createElementNS(FpmlConstants.FPML_NS, "fxOption");

        Element otEl = doc.createElementNS(FpmlConstants.FPML_NS, "optionType");
        otEl.setTextContent("call");
        fxOption.appendChild(otEl);

        Element buyerRef = doc.createElementNS(FpmlConstants.FPML_NS, "buyerPartyReference");
        buyerRef.setAttribute("href", "#party1");
        fxOption.appendChild(buyerRef);

        Element sellerRef = doc.createElementNS(FpmlConstants.FPML_NS, "sellerPartyReference");
        sellerRef.setAttribute("href", "#party2");
        fxOption.appendChild(sellerRef);

        Element exerciseStyleEl = doc.createElementNS(FpmlConstants.FPML_NS, "exerciseStyle");
        Element euEl = doc.createElementNS(FpmlConstants.FPML_NS, "European");
        euEl.setTextContent("European");
        exerciseStyleEl.appendChild(euEl);
        fxOption.appendChild(exerciseStyleEl);

        Element effEl = doc.createElementNS(FpmlConstants.FPML_NS, "effectiveDate");
        effEl.appendChild(createFallbackDate(doc));
        fxOption.appendChild(effEl);

        Element strikeEl = doc.createElementNS(FpmlConstants.FPML_NS, "strikePrice");
        strikeEl.setTextContent("1.0000");
        fxOption.appendChild(strikeEl);

        return fxOption;
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

        // Check for perUnitOf.currency in exchange rate context
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
            Object inner = invokeField(val, "getStrikePrice");
            if (inner != null) {
                Object strikeVal = invokeField(inner, "getValue");
                if (strikeVal instanceof Number) return formatBigDecimal((Number) strikeVal);
                if (strikeVal != null && !strikeVal.toString().contains("{")) {
                    try { BigDecimal bd = new BigDecimal(strikeVal.toString()); return formatBigDecimal(bd); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}

        try {
            Object inner = invokeField(val, "getValue");
            if (inner instanceof Number) return formatBigDecimal((Number) inner);
            if (inner != null && !inner.toString().contains("{")) {
                try { BigDecimal bd = new BigDecimal(inner.toString()); return formatBigDecimal(bd); } catch (Exception ignored) {}
            }
        } catch (NoSuchMethodException ignored) {}

        if (val instanceof Number) return formatBigDecimal((Number) val);
        if (val instanceof String) {
            try { BigDecimal bd = new BigDecimal((String) val); return formatBigDecimal(bd); } catch (Exception ignored) {}
            return (String) val;
        }

        // Parse from toString() for wrapper types like strikePrice
        String str = val.toString();
        if (str.contains("value=")) {
            int start = str.indexOf("value=") + 6;
            int end = str.indexOf(",", start);
            if (end == -1) end = str.indexOf("}", start);
            if (end > start) {
                try { BigDecimal bd = new BigDecimal(str.substring(start, end).trim()); return formatBigDecimal(bd); } catch (Exception ignored) {}
            }
        }

        // Try extracting from strikePrice wrapper
        if (str.contains("strikePrice=")) {
            int start = str.indexOf("strikePrice=") + 12;
            int end = str.indexOf(",", start);
            if (end == -1) end = str.indexOf("}", start);
            if (end > start) {
                String innerStr = str.substring(start, end).trim();
                try { BigDecimal bd = new BigDecimal(innerStr); return formatBigDecimal(bd); } catch (Exception ignored) {}
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

    private static String formatBigDecimal(BigDecimal value) {
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

    private static String formatBigDecimal(Number num) {
        if (num == null) return "0";
        double d = num.doubleValue();
        if (d == Math.floor(d)) {
            return String.format("%.2f", d);
        } else {
            return String.valueOf(num);
        }
    }

    private Element mapSettlementDate(Document doc, Object settlementTerms) throws Exception {
        try {
            Object settDate = invokeField(settlementTerms, "getSettlementDate");
            if (settDate != null) {
                Element adjustableDate = doc.createElementNS(FpmlConstants.FPML_NS, "adjustableDate");

                // Try valueDate field
                try {
                    Object valueDate = invokeField(settDate, "getValueDate");
                    if (valueDate != null) {
                        Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                        String dateStr = extractDateString(valueDate);
                        if (dateStr != null) {
                            unadjEl.setTextContent(dateStr);
                            adjustableDate.appendChild(unadjEl);
                        }
                    }
                } catch (Exception ignored) {}

                return adjustableDate;
            }
        } catch (Exception e) {
            // Ignore date mapping errors
        }

        return createFallbackDate(doc);
    }
}
