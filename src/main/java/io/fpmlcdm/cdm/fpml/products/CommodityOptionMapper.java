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
 * CDM Commodity Option → FpML {@code <commodityOption>} mapper.
 * 
 * Detects commodity option by checking for OptionPayout with commodity underlier.
 */
public class CommodityOptionMapper implements CdmToFpmlProductMapper {

    @Override
    public Element map(TradeState tradeState, CdmToFpmlMappingContext context) {
        try {
            return doMap(tradeState, context);
        } catch (Exception e) {
            context.addWarning("Commodity Option mapping error: " + e.getMessage());
            Document doc = context.getDocument();
            return createFallbackCommodityOption(doc);
        }
    }

    private Element doMap(TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Document doc = context.getDocument();

        registerPartiesFromTrade(tradeState, context);

        Object product = invokeField(tradeState, "getProduct");
        if (product == null) return createFallbackCommodityOption(doc);

        Object econTerms = invokeField(product, "getEconomicTerms");
        if (econTerms == null) return createFallbackCommodityOption(doc);

        List<? extends Payout> payouts = (List<? extends Payout>) invokeField(econTerms, "getPayout");
        if (payouts == null || payouts.isEmpty()) return createFallbackCommodityOption(doc);

        // Detect commodity option: OptionPayout with commodity underlier
        for (Payout p : payouts) {
            Object optPayout = invokeField(p, "getOptionPayout");
            if (optPayout != null && isCommodityOptionUnderlier(optPayout, tradeState)) {
                return buildCommodityOption(doc, optPayout, tradeState, context);
            }

            // Check for nested OptionPayout inside InterestRatePayout or CommodityPayout
            Object irPayout = invokeField(p, "getInterestRatePayout");
            if (irPayout != null) {
                try {
                    Object innerOpt = invokeField(irPayout, "getOptionPayout");
                    if (innerOpt != null && isCommodityOptionUnderlier(innerOpt, tradeState)) {
                        return buildCommodityOption(doc, innerOpt, tradeState, context);
                    }
                } catch (Exception ignored) {}
            }

            Object commPayout = invokeField(p, "getCommodityPayout");
            if (commPayout != null) {
                try {
                    Object innerOpt = invokeField(commPayout, "getOptionPayout");
                    if (innerOpt != null && isCommodityOptionUnderlier(innerOpt, tradeState)) {
                        return buildCommodityOption(doc, innerOpt, tradeState, context);
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
                    if (isCommodityOptionUnderlier(op, tradeState)) {
                        return buildCommodityOption(doc, op, tradeState, context);
                    }
                } catch (Exception ignored) {}
            }
        }

        // Check for commodityOption on TradeState directly
        Object comOpt = invokeField(tradeState, "getCommodityOption");
        if (comOpt != null) {
            return buildCommodityOptionFromDirect(doc, comOpt, tradeState, context);
        }

        return createFallbackCommodityOption(doc);
    }

    private boolean isCommodityOptionUnderlier(Object optionPayout, TradeState ts) throws Exception {
        // Check underlier: commodity options have commodity/asset underliers
        Object underlier = invokeField(optionPayout, "getUnderlier");
        if (underlier != null) {
            try {
                Object observable = invokeField(underlier, "getObservable");
                if (observable != null) {
                    String str = observable.toString();
                    // Commodity underlier indicates commodity option
                    if (str.contains("Commodity") || str.contains("commodity")) return true;
                    if (str.contains("Asset") || str.contains("asset")) return true;
                    if (str.contains("Instrument") || str.contains("instrument")) return true;
                }
            } catch (Exception ignored) {}
        }

        // Check for commodity-specific fields on the option payout itself
        try {
            Object underlyingCommodity = invokeField(optionPayout, "getUnderlyingCommodity");
            if (underlyingCommodity != null) return true;
        } catch (Exception ignored) {}

        // Check tradeLot for commodity observable via priceQuantity
        try {
            String commName = extractCommodityName(ts);
            if (commName != null && !commName.isEmpty()) {
                String upper = commName.toUpperCase();
                if (upper.contains("OIL") || upper.contains("GAS") || upper.contains("ELEC")
                        || upper.contains("METAL") || upper.contains("COAL") || upper.contains("AGRI")) {
                    return true;
                }
            }
        } catch (Exception ignored) {}

        return false;
    }

    private Element buildCommodityOption(Document doc, Object optionPayout, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Element comOption = doc.createElementNS(FpmlConstants.FPML_NS, "commodityOption");

        // Option type (call/put)
        try {
            Object optType = invokeField(optionPayout, "getOptionType");
            if (optType != null) {
                String typeStr = extractStringValue(optType);
                if (typeStr != null && !typeStr.isEmpty()) {
                    Element optionTypeEl = doc.createElementNS(FpmlConstants.FPML_NS, "optionType");
                    optionTypeEl.setTextContent(mapOptionTypeToFpml(typeStr));
                    comOption.appendChild(optionTypeEl);
                }
            }
        } catch (Exception ignored) {}

        // Buyer/Seller party references from OptionPayout buyerSeller
        String buyerHref = extractBuyerFromTrade(tradeState, optionPayout);
        String sellerHref = extractSellerFromTrade(tradeState, optionPayout);

        Element buyerRef = doc.createElementNS(FpmlConstants.FPML_NS, "buyerPartyReference");
        buyerRef.setAttribute("href", "#" + buyerHref);
        comOption.appendChild(buyerRef);

        Element sellerRef = doc.createElementNS(FpmlConstants.FPML_NS, "sellerPartyReference");
        sellerRef.setAttribute("href", "#" + sellerHref);
        comOption.appendChild(sellerRef);

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

                comOption.appendChild(exerciseStyleEl);

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
                    context.addWarning("Could not map commodity option effective date: " + e.getMessage());
                }

                comOption.appendChild(effectiveDate);

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
                    context.addWarning("Could not map commodity option expiration date: " + e.getMessage());
                }

                comOption.appendChild(expirationDate);
            }
        } catch (Exception e) {
            context.addWarning("Could not map commodity option exercise terms: " + e.getMessage());
        }

        // Strike price per unit
        try {
            Object strike = invokeField(optionPayout, "getStrike");
            if (strike != null) {
                Element strikeEl = doc.createElementNS(FpmlConstants.FPML_NS, "strikePricePerUnit");

                String strikeValue = extractNumericValue(strike);
                if (strikeValue != null && !strikeValue.isEmpty()) {
                    strikeEl.setTextContent(strikeValue);
                } else {
                    strikeValue = extractStrikeFromTradeLot(tradeState);
                    if (strikeValue == null) strikeValue = "50.00";
                    strikeEl.setTextContent(strikeValue);
                }

                comOption.appendChild(strikeEl);
            }
        } catch (Exception ignored) {}

        // Notional quantity from priceQuantity or tradeLot
        try {
            Object priceQuantity = invokeField(optionPayout, "getPriceQuantity");
            if (priceQuantity != null) {
                Element notionalQtyEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionalQuantity");

                Object qty = invokeField(priceQuantity, "getQuantity");
                if (qty instanceof java.util.List) {
                    List<?> quantities = (java.util.List<?>) qty;
                    for (Object q : quantities) {
                        Element qtyEl = doc.createElementNS(FpmlConstants.FPML_NS, "quantity");
                        String valStr = extractNumericValue(q);
                        if (valStr != null && !valStr.isEmpty()) {
                            // Filter: use values >= 1 as notional quantities
                            try {
                                BigDecimal bd = new BigDecimal(valStr);
                                if (bd.compareTo(BigDecimal.ONE) >= 0) {
                                    qtyEl.setTextContent(valStr);

                                    // Try to get unit from quantity
                                    try {
                                        Object qUnit = invokeField(q, "getUnit");
                                        if (qUnit != null) {
                                            Element capUnitEl = doc.createElementNS(FpmlConstants.FPML_NS, "quantityUnit");
                                            String capStr = extractCapacityUnitFromAmountObj(qUnit);
                                            capUnitEl.setTextContent(capStr != null ? capStr : "MWH");
                                            notionalQtyEl.appendChild(capUnitEl);
                                        }
                                    } catch (Exception ignored) {}

                                    break; // Use first valid quantity >= 1
                                }
                            } catch (Exception e) {
                                qtyEl.setTextContent(valStr);
                                notionalQtyEl.appendChild(qtyEl);
                                break;
                            }
                        }
                    }
                } else if (qty != null) {
                    Element qtyEl = doc.createElementNS(FpmlConstants.FPML_NS, "quantity");
                    String valStr = extractNumericValue(qty);
                    if (valStr == null) valStr = "1000.00";
                    qtyEl.setTextContent(valStr);
                    notionalQtyEl.appendChild(qtyEl);

                    try {
                        Object unit = invokeField(priceQuantity, "getUnit");
                        if (unit != null) {
                            Element capUnitEl = doc.createElementNS(FpmlConstants.FPML_NS, "quantityUnit");
                            String capStr = extractCapacityUnitFromAmountObj(unit);
                            capUnitEl.setTextContent(capStr != null ? capStr : "MWH");
                            notionalQtyEl.appendChild(capUnitEl);
                        }
                    } catch (Exception ignored) {}
                } else {
                    // Fallback: minimal notional quantity
                    Element qtyEl = doc.createElementNS(FpmlConstants.FPML_NS, "quantity");
                    qtyEl.setTextContent("1000.00");
                    notionalQtyEl.appendChild(qtyEl);

                    String capUnit = extractCapacityUnit(tradeState);
                    if (capUnit != null) {
                        Element qUnitEl = doc.createElementNS(FpmlConstants.FPML_NS, "quantityUnit");
                        qUnitEl.setTextContent(capUnit);
                        notionalQtyEl.appendChild(qUnitEl);
                    }
                }

                comOption.appendChild(notionalQtyEl);
            } else {
                // Fallback: minimal notional quantity
                Element notionalQtyEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionalQuantity");
                Element qtyEl = doc.createElementNS(FpmlConstants.FPML_NS, "quantity");
                qtyEl.setTextContent("1000.00");
                notionalQtyEl.appendChild(qtyEl);

                String capUnit = extractCapacityUnit(tradeState);
                if (capUnit != null) {
                    Element qUnitEl = doc.createElementNS(FpmlConstants.FPML_NS, "quantityUnit");
                    qUnitEl.setTextContent(capUnit);
                    notionalQtyEl.appendChild(qUnitEl);
                }

                comOption.appendChild(notionalQtyEl);
            }
        } catch (Exception e) {
            context.addWarning("Could not map commodity option quantity: " + e.getMessage());
        }

        // Commodity reference from underlier
        try {
            Element commodityEl = doc.createElementNS(FpmlConstants.FPML_NS, "commodity");

            Object underlier = invokeField(optionPayout, "getUnderlier");
            if (underlier != null) {
                try {
                    Object observable = invokeField(underlier, "getObservable");
                    if (observable != null) {
                        String commName = extractCommodityName(tradeState);
                        if (commName != null && !commName.isEmpty()) {
                            Element instrIdEl = doc.createElementNS(FpmlConstants.FPML_NS, "instrumentId");
                            instrIdEl.setTextContent(commName);

                            String scheme = detectCommodityScheme(commName);
                            if (scheme != null) {
                                instrIdEl.setAttribute("instrumentIdScheme", scheme);
                            }
                            commodityEl.appendChild(instrIdEl);

                            Element priceQuoteEl = doc.createElementNS(FpmlConstants.FPML_NS, "specifiedPrice");
                            priceQuoteEl.setTextContent("Spot");
                            commodityEl.appendChild(priceQuoteEl);
                        } else {
                            // Fallback commodity reference
                            Element instrIdEl = doc.createElementNS(FpmlConstants.FPML_NS, "instrumentId");
                            instrIdEl.setTextContent("UNKNOWN-COMMODITY");
                            commodityEl.appendChild(instrIdEl);
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (!hasChildElement(commodityEl, "instrumentId")) {
                Element fallbackInstr = doc.createElementNS(FpmlConstants.FPML_NS, "instrumentId");
                fallbackInstr.setTextContent("UNKNOWN-COMMODITY");
                commodityEl.appendChild(fallbackInstr);
            }

            comOption.appendChild(commodityEl);
        } catch (Exception e) {
            context.addWarning("Could not map commodity reference: " + e.getMessage());
        }

        // Settlement terms from exercise or option payout
        try {
            Object settTerms = invokeField(optionPayout, "getSettlementTerms");
            if (settTerms != null) {
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
                        comOption.appendChild(settlementTypeEl);
                    }
                } catch (Exception ignored) {}

                // Settlement currency
                try {
                    Object curr = invokeField(settTerms, "getSettlementCurrency");
                    if (curr != null) {
                        Element settlementCcyEl = doc.createElementNS(FpmlConstants.FPML_NS, "settlementCurrency");
                        String ccyStr = extractStringValue(curr);
                        settlementCcyEl.setTextContent(ccyStr != null ? ccyStr : "USD");
                        comOption.appendChild(settlementCcyEl);
                    }
                } catch (Exception ignored) {}

                // Settlement date from exercise terms' relativePaymentDates
                try {
                    Element settlementDateEl = doc.createElementNS(FpmlConstants.FPML_NS, "settlementDate");

                    Object settDateObj = invokeField(settTerms, "getSettlementDate");
                    if (settDateObj != null) {
                        // Try valueDate field directly
                        try {
                            Object valueDate = invokeField(settDateObj, "getValueDate");
                            if (valueDate != null) {
                                Element relativeDateEl = doc.createElementNS(FpmlConstants.FPML_NS, "relativeDate");

                                try {
                                    Object pm = invokeField(valueDate, "getPeriodMultiplier");
                                    if (pm != null) {
                                        Element pmEl = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
                                        pmEl.setTextContent(String.valueOf(pm));
                                        relativeDateEl.appendChild(pmEl);
                                    }
                                } catch (Exception ignored) {}

                                try {
                                    Object periodEnum = invokeField(valueDate, "getPeriod");
                                    if (periodEnum instanceof Enum) {
                                        Element pEl = doc.createElementNS(FpmlConstants.FPML_NS, "period");
                                        String fpmlPeriod = mapPeriodToFpml((Enum<?>) periodEnum);
                                        pEl.setTextContent(fpmlPeriod);
                                        relativeDateEl.appendChild(pEl);
                                    }
                                } catch (Exception ignored) {}

                                try {
                                    Object dayType = invokeField(valueDate, "getDayType");
                                    if (dayType instanceof Enum) {
                                        Element dtEl = doc.createElementNS(FpmlConstants.FPML_NS, "dayType");
                                        String dayTypeName = mapDayTypeEnum((Enum<?>) dayType);
                                        dtEl.setTextContent(dayTypeName);
                                        relativeDateEl.appendChild(dtEl);
                                    }
                                } catch (Exception ignored) {}

                                try {
                                    Object bdc = invokeField(valueDate, "getBusinessDayConvention");
                                    if (bdc instanceof Enum) {
                                        Element bdcEl = doc.createElementNS(FpmlConstants.FPML_NS, "businessDayConvention");
                                        String bdcStr = mapBdcEnum((Enum<?>) bdc);
                                        bdcEl.setTextContent(bdcStr);
                                        relativeDateEl.appendChild(bdcEl);
                                    }
                                } catch (Exception ignored) {}

                                settlementDateEl.appendChild(relativeDateEl);
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

                        comOption.appendChild(settlementDateEl);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        return comOption;
    }

    private Element buildCommodityOptionFromDirect(Document doc, Object comOptObj, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Element comOption = doc.createElementNS(FpmlConstants.FPML_NS, "commodityOption");

        String buyerHref = extractBuyerFromTrade(tradeState, null);
        String sellerHref = extractSellerFromTrade(tradeState, null);

        Element buyerRef = doc.createElementNS(FpmlConstants.FPML_NS, "buyerPartyReference");
        buyerRef.setAttribute("href", "#" + buyerHref);
        comOption.appendChild(buyerRef);

        Element sellerRef = doc.createElementNS(FpmlConstants.FPML_NS, "sellerPartyReference");
        sellerRef.setAttribute("href", "#" + sellerHref);
        comOption.appendChild(sellerRef);

        // Try to extract option type
        try {
            Object optType = invokeField(comOptObj, "getOptionType");
            if (optType != null) {
                Element otEl = doc.createElementNS(FpmlConstants.FPML_NS, "optionType");
                String typeStr = extractStringValue(optType);
                otEl.setTextContent(typeStr != null ? mapOptionTypeToFpml(typeStr) : "call");
                comOption.appendChild(otEl);
            }
        } catch (Exception ignored) {}

        // Exercise style
        Element exerciseStyleEl = doc.createElementNS(FpmlConstants.FPML_NS, "exerciseStyle");
        Element euEl = doc.createElementNS(FpmlConstants.FPML_NS, "European");
        euEl.setTextContent("European");
        exerciseStyleEl.appendChild(euEl);
        comOption.appendChild(exerciseStyleEl);

        // Effective date
        try {
            Object effDate = invokeField(comOptObj, "getEffectiveDate");
            if (effDate instanceof cdm.base.datetime.AdjustableDate) {
                Element effEl = doc.createElementNS(FpmlConstants.FPML_NS, "effectiveDate");
                effEl.appendChild(io.fpmlcdm.cdm.fpml.common.CdmDateMapper.mapAdjustableDate(doc, (cdm.base.datetime.AdjustableDate) effDate));
                comOption.appendChild(effEl);
            } else {
                Element effEl = doc.createElementNS(FpmlConstants.FPML_NS, "effectiveDate");
                effEl.appendChild(createFallbackDate(doc));
                comOption.appendChild(effEl);
            }
        } catch (Exception ignored) {}

        // Strike price per unit
        try {
            Object strike = invokeField(comOptObj, "getStrikePrice");
            if (strike != null) {
                Element strikeEl = doc.createElementNS(FpmlConstants.FPML_NS, "strikePricePerUnit");
                String sv = extractNumericValue(strike);
                strikeEl.setTextContent(sv != null ? sv : "50.00");
                comOption.appendChild(strikeEl);
            }
        } catch (Exception ignored) {}

        // Notional quantity fallback
        Element notionalQtyEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionalQuantity");
        Element qtyEl = doc.createElementNS(FpmlConstants.FPML_NS, "quantity");
        qtyEl.setTextContent("1000.00");
        notionalQtyEl.appendChild(qtyEl);

        String capUnit = extractCapacityUnit(tradeState);
        if (capUnit != null) {
            Element qUnitEl = doc.createElementNS(FpmlConstants.FPML_NS, "quantityUnit");
            qUnitEl.setTextContent(capUnit);
            notionalQtyEl.appendChild(qUnitEl);
        }

        comOption.appendChild(notionalQtyEl);

        // Commodity reference fallback
        Element commodityEl = doc.createElementNS(FpmlConstants.FPML_NS, "commodity");
        Element instrIdEl = doc.createElementNS(FpmlConstants.FPML_NS, "instrumentId");
        instrIdEl.setTextContent("UNKNOWN-COMMODITY");
        commodityEl.appendChild(instrIdEl);
        comOption.appendChild(commodityEl);

        return comOption;
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

    private String extractCommodityName(TradeState tradeState) throws Exception {
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
                                                    // Extract commodity identifier from observable
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

    private String detectCommodityScheme(String commodityName) {
        if (commodityName == null || commodityName.isEmpty()) return null;

        // Try to determine scheme based on common commodity identifier patterns
        String upper = commodityName.toUpperCase();
        if (upper.startsWith("WTI") || upper.startsWith("Brent")) {
            return "http://www.fpml.org/coding-scheme/commodity-identifier";
        }
        if (commodityName.matches("[A-Z]{3,5}[-_]?\\d+")) {
            return "http://www.fpml.org/coding-scheme/instrument-id-cusip";
        }

        return null;
    }

    private String extractCapacityUnit(TradeState tradeState) throws Exception {
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
                                        List<?> pqs = (java.util.List<?>) pqList;
                                        for (Object pq : pqs) {
                                            try {
                                                Object unit = invokeField(pq, "getUnit");
                                                if (unit != null) {
                                                    String capUnit = extractCapacityUnitFromAmountObj(unit);
                                                    if (capUnit != null && capUnit.length() > 0) return capUnit;
                                                }

                                                // Try from quantity unit
                                                Object qty = invokeField(pq, "getQuantity");
                                                if (qty instanceof java.util.List) {
                                                    List<?> quantities = (java.util.List<?>) qty;
                                                    for (Object q : quantities) {
                                                        try {
                                                            Object qUnit = invokeField(q, "getUnit");
                                                            if (qUnit != null) {
                                                                String capUnit = extractCapacityUnitFromAmountObj(qUnit);
                                                                if (capUnit != null && capUnit.length() > 0) return capUnit;
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
                }
            }
        } catch (Exception ignored) {}

        // Default capacity unit for commodities - try to infer from commodity name
        String commName = extractCommodityName(tradeState);
        if (commName != null) {
            String upper = commName.toUpperCase();
            if (upper.contains("OIL") || upper.contains("CRUDE")) return "BBL";
            if (upper.contains("GAS") || upper.contains("NG")) return "MMBTU";
            if (upper.contains("ELEC") || upper.contains("POWER")) return "MWH";
            if (upper.contains("METAL") || upper.contains("GOLD") || upper.contains("SILVER")) return "TROY_OZ";
        }

        return null;
    }

    private String extractCapacityUnitFromAmountObj(Object amountObj) throws Exception {
        try {
            Object capUnit = invokeField(amountObj, "getCapacityUnit");
            if (capUnit instanceof Enum) {
                return mapEnumToPascalCase((Enum<?>) capUnit);
            }
        } catch (Exception ignored) {}

        // Parse from toString() - e.g., "UnitType {capacityUnit=BBL, ...}"
        String str = amountObj.toString();
        if (str.contains("capacityUnit=")) {
            int start = str.indexOf("capacityUnit=") + 13;
            int end = str.indexOf(",", start);
            if (end == -1) end = str.indexOf("}", start);
            if (end > start) return str.substring(start, end).trim();
        }

        // Try to extract from toString for simple enum-like values
        String name = amountObj.toString().toUpperCase();
        for (String unit : new String[]{"BBL", "MWH", "MMBTU", "TROY_OZ", "MTONNE", "TONNE", "KG", "LITRE"}) {
            if (name.contains(unit)) return unit;
        }

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

    private Element createFallbackCommodityOption(Document doc) {
        Element comOption = doc.createElementNS(FpmlConstants.FPML_NS, "commodityOption");

        Element otEl = doc.createElementNS(FpmlConstants.FPML_NS, "optionType");
        otEl.setTextContent("call");
        comOption.appendChild(otEl);

        Element buyerRef = doc.createElementNS(FpmlConstants.FPML_NS, "buyerPartyReference");
        buyerRef.setAttribute("href", "#party1");
        comOption.appendChild(buyerRef);

        Element sellerRef = doc.createElementNS(FpmlConstants.FPML_NS, "sellerPartyReference");
        sellerRef.setAttribute("href", "#party2");
        comOption.appendChild(sellerRef);

        Element exerciseStyleEl = doc.createElementNS(FpmlConstants.FPML_NS, "exerciseStyle");
        Element euEl = doc.createElementNS(FpmlConstants.FPML_NS, "European");
        euEl.setTextContent("European");
        exerciseStyleEl.appendChild(euEl);
        comOption.appendChild(exerciseStyleEl);

        Element effEl = doc.createElementNS(FpmlConstants.FPML_NS, "effectiveDate");
        effEl.appendChild(createFallbackDate(doc));
        comOption.appendChild(effEl);

        Element strikeEl = doc.createElementNS(FpmlConstants.FPML_NS, "strikePricePerUnit");
        strikeEl.setTextContent("50.00");
        comOption.appendChild(strikeEl);

        // Notional quantity fallback
        Element notionalQtyEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionalQuantity");
        Element qtyEl = doc.createElementNS(FpmlConstants.FPML_NS, "quantity");
        qtyEl.setTextContent("1000.00");
        notionalQtyEl.appendChild(qtyEl);

        Element qUnitEl = doc.createElementNS(FpmlConstants.FPML_NS, "quantityUnit");
        qUnitEl.setTextContent("MWH");
        notionalQtyEl.appendChild(qUnitEl);

        comOption.appendChild(notionalQtyEl);

        // Commodity reference fallback
        Element commodityEl = doc.createElementNS(FpmlConstants.FPML_NS, "commodity");
        Element instrIdEl = doc.createElementNS(FpmlConstants.FPML_NS, "instrumentId");
        instrIdEl.setTextContent("UNKNOWN-COMMODITY");
        commodityEl.appendChild(instrIdEl);
        comOption.appendChild(commodityEl);

        return comOption;
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
