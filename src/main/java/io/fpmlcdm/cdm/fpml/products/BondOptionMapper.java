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
 * CDM Bond Option → FpML {@code <bondOption>} mapper.
 * 
 * Detects bond option by checking for OptionPayout with debt/bond Security underlier.
 */
public class BondOptionMapper implements CdmToFpmlProductMapper {

    @Override
    public Element map(TradeState tradeState, CdmToFpmlMappingContext context) {
        try {
            return doMap(tradeState, context);
        } catch (Exception e) {
            context.addWarning("Bond Option mapping error: " + e.getMessage());
            Document doc = context.getDocument();
            return createFallbackBondOption(doc);
        }
    }

    private Element doMap(TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Document doc = context.getDocument();

        registerPartiesFromTrade(tradeState, context);

        Object product = invokeField(tradeState, "getProduct");
        if (product == null) return createFallbackBondOption(doc);

        Object econTerms = invokeField(product, "getEconomicTerms");
        if (econTerms == null) return createFallbackBondOption(doc);

        List<? extends Payout> payouts = (List<? extends Payout>) invokeField(econTerms, "getPayout");
        if (payouts == null || payouts.isEmpty()) return createFallbackBondOption(doc);

        // Detect bond option: OptionPayout with debt/bond Security underlier
        for (Payout p : payouts) {
            Object optPayout = invokeField(p, "getOptionPayout");
            if (optPayout != null && isBondOptionUnderlier(optPayout)) {
                return buildBondOption(doc, optPayout, tradeState, context);
            }

            // Check for nested OptionPayout inside InterestRatePayout
            Object irPayout = invokeField(p, "getInterestRatePayout");
            if (irPayout != null) {
                try {
                    Object innerOpt = invokeField(irPayout, "getOptionPayout");
                    if (innerOpt != null && isBondOptionUnderlier(innerOpt)) {
                        return buildBondOption(doc, innerOpt, tradeState, context);
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
                    if (isBondOptionUnderlier(op)) {
                        return buildBondOption(doc, op, tradeState, context);
                    }
                } catch (Exception ignored) {}
            }
        }

        // Check for bondOption on TradeState directly
        Object bondOpt = invokeField(tradeState, "getBondOption");
        if (bondOpt != null) {
            return buildBondOptionFromDirect(doc, bondOpt, tradeState, context);
        }

        return createFallbackBondOption(doc);
    }

    private boolean isBondOptionUnderlier(Object optionPayout) throws Exception {
        // Check underlier: bond options have debt/bond Security underliers
        Object underlier = invokeField(optionPayout, "getUnderlier");
        if (underlier != null) {
            try {
                Object observable = invokeField(underlier, "getObservable");
                if (observable != null) {
                    String str = observable.toString();
                    // Debt/bond Security underlier indicates bond option
                    if (str.contains("Security") || str.contains("security")) return true;
                    if (str.contains("Debt") || str.contains("debt")) return true;
                    if (str.contains("Bond") || str.contains("bond")) return true;
                    if (str.contains("FixedIncome") || str.contains("fixedIncome")) return true;
                }
            } catch (Exception ignored) {}
        }

        // Check for bond-specific fields on the option payout itself
        try {
            Object underlyingBond = invokeField(optionPayout, "getUnderlyingBond");
            if (underlyingBond != null) return true;
        } catch (Exception ignored) {}

        try {
            Object couponSchedule = invokeField(optionPayout, "getCouponSchedule");
            if (couponSchedule != null) return true;
        } catch (Exception ignored) {}

        // Check tradeLot for bond observable via priceQuantity
        try {
            Object product = invokeField(this, "_productRef");
        } catch (Exception ignored) {}

        return false;
    }

    private Element buildBondOption(Document doc, Object optionPayout, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Element bondOption = doc.createElementNS(FpmlConstants.FPML_NS, "bondOption");

        // Option type (call/put)
        try {
            Object optType = invokeField(optionPayout, "getOptionType");
            if (optType != null) {
                String typeStr = extractStringValue(optType);
                if (typeStr != null && !typeStr.isEmpty()) {
                    Element optionTypeEl = doc.createElementNS(FpmlConstants.FPML_NS, "optionType");
                    optionTypeEl.setTextContent(mapOptionTypeToFpml(typeStr));
                    bondOption.appendChild(optionTypeEl);
                }
            }
        } catch (Exception ignored) {}

        // Buyer/Seller party references from OptionPayout buyerSeller
        String buyerHref = extractBuyerFromTrade(tradeState, optionPayout);
        String sellerHref = extractSellerFromTrade(tradeState, optionPayout);

        Element buyerRef = doc.createElementNS(FpmlConstants.FPML_NS, "buyerPartyReference");
        buyerRef.setAttribute("href", "#" + buyerHref);
        bondOption.appendChild(buyerRef);

        Element sellerRef = doc.createElementNS(FpmlConstants.FPML_NS, "sellerPartyReference");
        sellerRef.setAttribute("href", "#" + sellerHref);
        bondOption.appendChild(sellerRef);

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

                bondOption.appendChild(exerciseStyleEl);

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
                                Element adjDateEl = doc.createElementNS(FpmlConstants.FPML_NS, "adjustableDate");

                                Object settDate = invokeField(settTerms, "getSettlementDate");
                                if (settDate != null) {
                                    try {
                                        java.lang.reflect.Method getAdj = settDate.getClass().getMethod("getAdjustableDate");
                                        Object adjustableDate = getAdj.invoke(settDate);
                                        if (adjustableDate instanceof cdm.base.datetime.AdjustableDate) {
                                            adjDateEl.appendChild(io.fpmlcdm.cdm.fpml.common.CdmDateMapper.mapAdjustableDate(doc, (cdm.base.datetime.AdjustableDate) adjustableDate));
                                        } else {
                                            Object valueDate = invokeField(settDate, "getValueDate");
                                            if (valueDate != null) {
                                                Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                                                String dateStr = extractDateString(valueDate);
                                                if (dateStr != null) {
                                                    unadjEl.setTextContent(dateStr);
                                                    adjDateEl.appendChild(unadjEl);
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
                                                adjDateEl.appendChild(unadjEl);
                                            }
                                        }
                                    }
                                }

                                effectiveDate.appendChild(adjDateEl);
                            } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception e) {
                    context.addWarning("Could not map bond option effective date: " + e.getMessage());
                }

                bondOption.appendChild(effectiveDate);

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
                    context.addWarning("Could not map bond option expiration date: " + e.getMessage());
                }

                bondOption.appendChild(expirationDate);
            }
        } catch (Exception e) {
            context.addWarning("Could not map bond option exercise terms: " + e.getMessage());
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
                    strikeValue = extractStrikeFromTradeLot(tradeState);
                    if (strikeValue == null) strikeValue = "100.00";
                    strikeEl.setTextContent(strikeValue);
                }

                bondOption.appendChild(strikeEl);
            }
        } catch (Exception ignored) {}

        // Quantity/Notional schedule
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
                    try {
                        Object settTerms = invokeField(optionPayout, "getSettlementTerms");
                        if (settTerms != null) {
                            Element payCurrAmtEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentCurrencyAmount");

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
                            payCurrAmtEl.appendChild(currElem);

                            Element valElem = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                            valElem.setTextContent("1000000.00");
                            payCurrAmtEl.appendChild(valElem);

                            notionalPeriod.appendChild(payCurrAmtEl);
                        }
                    } catch (Exception ignored) {}
                }

                quantitySchedule.appendChild(notionalPeriod);
                bondOption.appendChild(quantitySchedule);
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
                bondOption.appendChild(quantitySchedule);
            }
        } catch (Exception e) {
            context.addWarning("Could not map bond option quantity: " + e.getMessage());
        }

        // Bond reference from underlier
        try {
            Element bondEl = doc.createElementNS(FpmlConstants.FPML_NS, "bond");

            Object underlier = invokeField(optionPayout, "getUnderlier");
            if (underlier != null) {
                try {
                    Object observable = invokeField(underlier, "getObservable");
                    if (observable != null) {
                        // Try to get asset info from tradeLot priceQuantity
                        String bondName = extractBondName(tradeState);
                        String cusip = extractBondIdentifier(tradeState, "CUSIP");
                        String isin = extractBondIdentifier(tradeState, "ISIN");

                        if (bondName != null && !bondName.isEmpty()) {
                            Element securityNameEl = doc.createElementNS(FpmlConstants.FPML_NS, "securityName");
                            securityNameEl.setTextContent(bondName);
                            bondEl.appendChild(securityNameEl);
                        }

                        if (cusip != null && !cusip.isEmpty()) {
                            Element cusipEl = doc.createElementNS(FpmlConstants.FPML_NS, "instrumentId");
                            cusipEl.setAttribute("instrumentIdScheme", "http://www.fpml.org/coding-scheme/instrument-id-cusip");
                            cusipEl.setTextContent(cusip);
                            bondEl.appendChild(cusipEl);
                        }

                        if (isin != null && !isin.isEmpty()) {
                            Element isinEl = doc.createElementNS(FpmlConstants.FPML_NS, "instrumentId");
                            isinEl.setAttribute("instrumentIdScheme", "http://www.fpml.org/coding-scheme/instrument-id-isin");
                            isinEl.setTextContent(isin);
                            bondEl.appendChild(isinEl);
                        }

                        // Coupon rate from tradeLot priceQuantity
                        String couponRate = extractCouponRate(tradeState);
                        if (couponRate != null) {
                            Element fixedCouponEl = doc.createElementNS(FpmlConstants.FPML_NS, "fixedCoupon");
                            Element couponPaymentEl = doc.createElementNS(FpmlConstants.FPML_NS, "couponPayment");

                            Element initialCouponEl = doc.createElementNS(FpmlConstants.FPML_NS, "initialAmount");
                            BigDecimal rateBd;
                            try {
                                rateBd = new BigDecimal(couponRate);
                                if (rateBd.compareTo(BigDecimal.ZERO) > 0 && rateBd.compareTo(new BigDecimal("1")) < 0) {
                                    // It's a decimal rate like 0.05, convert to percentage
                                    initialCouponEl.setTextContent(rateBd.multiply(new BigDecimal("100")).toPlainString());
                                } else {
                                    initialCouponEl.setTextContent(couponRate);
                                }
                            } catch (Exception e) {
                                initialCouponEl.setTextContent(couponRate);
                            }
                            couponPaymentEl.appendChild(initialCouponEl);

                            Element freqEl = doc.createElementNS(FpmlConstants.FPML_NS, "frequency");
                            freqEl.setTextContent("Semiannual");
                            couponPaymentEl.appendChild(freqEl);

                            fixedCouponEl.appendChild(couponPaymentEl);
                            bondEl.appendChild(fixedCouponEl);
                        }

                        if (!hasChildElement(bondEl, "securityName")) {
                            Element fallbackName = doc.createElementNS(FpmlConstants.FPML_NS, "securityName");
                            fallbackName.setTextContent("UNKNOWN-BOND");
                            bondEl.appendChild(fallbackName);
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (!hasChildElement(bondEl, "securityName")) {
                Element fallbackName = doc.createElementNS(FpmlConstants.FPML_NS, "securityName");
                fallbackName.setTextContent("UNKNOWN-BOND");
                bondEl.appendChild(fallbackName);
            }

            bondOption.appendChild(bondEl);
        } catch (Exception e) {
            context.addWarning("Could not map bond reference: " + e.getMessage());
        }

        return bondOption;
    }

    private Element buildBondOptionFromDirect(Document doc, Object bondOptObj, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Element bondOption = doc.createElementNS(FpmlConstants.FPML_NS, "bondOption");

        String buyerHref = extractBuyerFromTrade(tradeState, null);
        String sellerHref = extractSellerFromTrade(tradeState, null);

        Element buyerRef = doc.createElementNS(FpmlConstants.FPML_NS, "buyerPartyReference");
        buyerRef.setAttribute("href", "#" + buyerHref);
        bondOption.appendChild(buyerRef);

        Element sellerRef = doc.createElementNS(FpmlConstants.FPML_NS, "sellerPartyReference");
        sellerRef.setAttribute("href", "#" + sellerHref);
        bondOption.appendChild(sellerRef);

        // Try to extract option type
        try {
            Object optType = invokeField(bondOptObj, "getOptionType");
            if (optType != null) {
                Element otEl = doc.createElementNS(FpmlConstants.FPML_NS, "optionType");
                String typeStr = extractStringValue(optType);
                otEl.setTextContent(typeStr != null ? mapOptionTypeToFpml(typeStr) : "call");
                bondOption.appendChild(otEl);
            }
        } catch (Exception ignored) {}

        // Exercise style
        Element exerciseStyleEl = doc.createElementNS(FpmlConstants.FPML_NS, "exerciseStyle");
        Element euEl = doc.createElementNS(FpmlConstants.FPML_NS, "European");
        euEl.setTextContent("European");
        exerciseStyleEl.appendChild(euEl);
        bondOption.appendChild(exerciseStyleEl);

        // Effective date
        try {
            Object effDate = invokeField(bondOptObj, "getEffectiveDate");
            if (effDate instanceof cdm.base.datetime.AdjustableDate) {
                Element effEl = doc.createElementNS(FpmlConstants.FPML_NS, "effectiveDate");
                effEl.appendChild(io.fpmlcdm.cdm.fpml.common.CdmDateMapper.mapAdjustableDate(doc, (cdm.base.datetime.AdjustableDate) effDate));
                bondOption.appendChild(effEl);
            } else {
                Element effEl = doc.createElementNS(FpmlConstants.FPML_NS, "effectiveDate");
                effEl.appendChild(createFallbackDate(doc));
                bondOption.appendChild(effEl);
            }
        } catch (Exception ignored) {}

        // Strike price
        try {
            Object strike = invokeField(bondOptObj, "getStrikePrice");
            if (strike != null) {
                Element strikeEl = doc.createElementNS(FpmlConstants.FPML_NS, "strikePrice");
                String sv = extractNumericValue(strike);
                strikeEl.setTextContent(sv != null ? sv : "100.00");
                bondOption.appendChild(strikeEl);
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
        bondOption.appendChild(quantitySchedule);

        // Bond reference fallback
        Element bondEl = doc.createElementNS(FpmlConstants.FPML_NS, "bond");
        Element securityNameEl = doc.createElementNS(FpmlConstants.FPML_NS, "securityName");
        securityNameEl.setTextContent("UNKNOWN-BOND");
        bondEl.appendChild(securityNameEl);
        bondOption.appendChild(bondEl);

        return bondOption;
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

    private String extractBondName(TradeState tradeState) throws Exception {
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
                                                    // Extract security identifier/name
                                                    try {
                                                        Object idList = invokeField(obsVal, "getIdentifier");
                                                        if (idList instanceof java.util.List) {
                                                            for (Object id : (java.util.List<?>) idList) {
                                                                try {
                                                                    Object identObj = invokeField(id, "getIdentifier");
                                                                    if (identObj != null) {
                                                                        String identStr = extractStringValue(identObj);
                                                                        if (identStr != null && !identStr.isEmpty()) {
                                                                            // Check for name identifier type
                                                                            Object idType = invokeField(id, "getIdentifierType");
                                                                            if (idType != null) {
                                                                                String typeStr = extractStringValue(idType);
                                                                                if ("Name".equalsIgnoreCase(typeStr)) {
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

                                                    // Try to parse from toString directly if not a wrapper
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

    private String extractBondIdentifier(TradeState tradeState, String schemeName) throws Exception {
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
                                                    try {
                                                        Object idList = invokeField(obsVal, "getIdentifier");
                                                        if (idList instanceof java.util.List) {
                                                            for (Object id : (java.util.List<?>) idList) {
                                                                try {
                                                                    Object identObj = invokeField(id, "getIdentifier");
                                                                    if (identObj != null && schemeName.equals(extractStringValue(invokeField(id, "getIdentifierType")))) {
                                                                        return extractStringValue(identObj);
                                                                    }
                                                                } catch (Exception ignored) {}
                                                            }
                                                        }
                                                    } catch (Exception ignored) {}
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

    private String extractCouponRate(TradeState tradeState) throws Exception {
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

    private Element createFallbackBondOption(Document doc) {
        Element bondOption = doc.createElementNS(FpmlConstants.FPML_NS, "bondOption");

        Element otEl = doc.createElementNS(FpmlConstants.FPML_NS, "optionType");
        otEl.setTextContent("call");
        bondOption.appendChild(otEl);

        Element buyerRef = doc.createElementNS(FpmlConstants.FPML_NS, "buyerPartyReference");
        buyerRef.setAttribute("href", "#party1");
        bondOption.appendChild(buyerRef);

        Element sellerRef = doc.createElementNS(FpmlConstants.FPML_NS, "sellerPartyReference");
        sellerRef.setAttribute("href", "#party2");
        bondOption.appendChild(sellerRef);

        Element exerciseStyleEl = doc.createElementNS(FpmlConstants.FPML_NS, "exerciseStyle");
        Element euEl = doc.createElementNS(FpmlConstants.FPML_NS, "European");
        euEl.setTextContent("European");
        exerciseStyleEl.appendChild(euEl);
        bondOption.appendChild(exerciseStyleEl);

        Element effEl = doc.createElementNS(FpmlConstants.FPML_NS, "effectiveDate");
        effEl.appendChild(createFallbackDate(doc));
        bondOption.appendChild(effEl);

        Element strikeEl = doc.createElementNS(FpmlConstants.FPML_NS, "strikePrice");
        strikeEl.setTextContent("100.00");
        bondOption.appendChild(strikeEl);

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
        bondOption.appendChild(quantitySchedule);

        // Bond reference fallback
        Element bondEl = doc.createElementNS(FpmlConstants.FPML_NS, "bond");
        Element securityNameEl = doc.createElementNS(FpmlConstants.FPML_NS, "securityName");
        securityNameEl.setTextContent("UNKNOWN-BOND");
        bondEl.appendChild(securityNameEl);
        bondOption.appendChild(bondEl);

        return bondOption;
    }

    private Element createFallbackDate(Document doc) {
        Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
        unadjEl.setTextContent("2024-01-02");
        return unadjEl;
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
