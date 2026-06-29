package io.fpmlcdm.cdm.fpml.products;

import cdm.base.staticdata.party.Counterparty;
import cdm.event.common.TradeState;
import cdm.product.asset.InterestRatePayout;
import cdm.product.template.Payout;
import io.fpmlcdm.cdm.fpml.CdmToFpmlMappingContext;
import io.fpmlcdm.cdm.fpml.CdmToFpmlProductMapper;
import io.fpmlcdm.cdm.fpml.FpmlConstants;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.util.List;

/**
 * CDM FRA (Forward Rate Agreement) → FpML mapper.
 *
 * Maps a pair of InterestRatePayouts (fixed leg + floating leg) to an FpML {@code <fra>} element.
 *
 * In CDM, FRA has two payouts:
 *   - Fixed leg: payer = Party1 (buyer), receiver = Party2 (seller), rateSpecification = FixedRateSpecification
 *   - Floating leg: payer = Party2 (seller), receiver = Party1 (buyer), rateSpecification = FloatingRateSpecification
 *
 * Both share the same calculationPeriodDates (effectiveDate → terminationDate defines the FRA period).
 */
public class FraMapper implements CdmToFpmlProductMapper {

    @Override
    public Element map(TradeState tradeState, CdmToFpmlMappingContext context) {
        try {
            return doMap(tradeState, context);
        } catch (Exception e) {
            context.addWarning("FRA mapping error: " + e.getMessage());
            Document doc = context.getDocument();
            Element fraElement = doc.createElementNS(FpmlConstants.FPML_NS, "fra");
            return fraElement;
        }
    }

    private Element doMap(TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Document doc = context.getDocument();

        // Register parties from trade
        registerPartiesFromTrade(tradeState, context);

        Element fraElement = doc.createElementNS(FpmlConstants.FPML_NS, "fra");

        // Map tradeDate
        mapTradeDate(doc, fraElement, tradeState, context);

        // Get payouts - FRA should have exactly 2 InterestRatePayouts
        List<? extends Payout> payouts = getPayouts(tradeState);
        if (payouts == null || payouts.isEmpty()) {
            context.addWarning("No payouts found for FRA product");
            return fraElement;
        }

        // Find fixed and floating legs
        Object fixedPayout = null;
        Object floatPayout = null;

        for (Payout payout : payouts) {
            Object irPayoutObj = invokeField(payout, "getInterestRatePayout");
            if (irPayoutObj == null) continue;

            try {
                Object rateSpec = invokeField(irPayoutObj, "getRateSpecification");
                if (rateSpec != null) {
                    // Check for FixedRateSpecification vs FloatingRateSpecification
                    Object fixedSpec = invokeField(rateSpec, "getFixedRateSpecification");
                    Object floatSpec = invokeField(rateSpec, "getFloatingRateSpecification");

                    if (fixedSpec != null) {
                        fixedPayout = irPayoutObj;
                    } else if (floatSpec != null) {
                        floatPayout = irPayoutObj;
                    }
                }
            } catch (Exception ignored) {}
        }

        // If we found both legs, use them to build the FRA structure
        if (fixedPayout != null && floatPayout != null) {
            mapFraFromTwoLegs(doc, fraElement, fixedPayout, floatPayout, tradeState, context);
        } else if (fixedPayout != null || floatPayout != null) {
            // Fallback: use single payout to build minimal FRA structure
            Object irPayoutObj = fixedPayout != null ? fixedPayout : floatPayout;
            mapFraFromSingleLeg(doc, fraElement, irPayoutObj, tradeState, context);
        } else {
            context.addWarning("Could not identify fixed/floating legs for FRA");
        }

        return fraElement;
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

    private void mapTradeDate(Document doc, Element fraElement, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
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
            fraElement.insertBefore(tradeHeader, fraElement.getFirstChild());
        }
    }

    private void mapFraFromTwoLegs(Document doc, Element fraElement, Object fixedPayout, Object floatPayout,
                                    TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        // Determine buyer/seller party references from the fixed leg (buyer pays fixed)
        String buyerHref = "party1";
        String sellerHref = "party2";

        try {
            Object payerReceiver = invokeField(fixedPayout, "getPayerReceiver");
            if (payerReceiver != null) {
                java.lang.reflect.Method getM = payerReceiver.getClass().getMethod("get", int.class);
                Object payerSideObj = getM.invoke(payerReceiver, 0);

                Object trade = invokeField(tradeState, "getTrade");
                List<?> counterparties = (List<?>) invokeField(trade, "getCounterparty");
                if (counterparties != null) {
                    for (Object cp : counterparties) {
                        if (cp instanceof Counterparty) {
                            Object cpRole = invoke((Counterparty) cp, "getRole");
                            String extRef = extractExternalReferenceFromRef((Counterparty) cp);

                            if (extRef != null && cpRole instanceof Enum) {
                                String cdmRoleName = ((Enum<?>) cpRole).name();
                                String expectedPayerSide = "Party" + (cdmRoleName.contains("1") ? "1" : "2");

                                if (expectedPayerSide.equals(payerSideObj.toString())) {
                                    // Payer of fixed leg = buyer
                                    buyerHref = extRef;
                                } else {
                                    sellerHref = extRef;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // 1. Buyer/Seller party references
        Element buyerRef = doc.createElementNS(FpmlConstants.FPML_NS, "buyerPartyReference");
        buyerRef.setAttribute("href", "#" + buyerHref);
        fraElement.appendChild(buyerRef);

        Element sellerRef = doc.createElementNS(FpmlConstants.FPML_NS, "sellerPartyReference");
        sellerRef.setAttribute("href", "#" + sellerHref);
        fraElement.appendChild(sellerRef);

        // 2. FRA dates from calculation period dates (effectiveDate = firstPeriodStartDate, terminationDate = secondPeriodStartDate)
        String calcDatesId = context.createFpmlId("calcPeriodDates");
        try {
            Object calcDates = invokeField(fixedPayout, "getCalculationPeriodDates");
            if (calcDates != null) {
                // Extract effective date → firstPeriodStartDate
                try {
                    Object effDate = invokeField(calcDates, "getEffectiveDate");
                    if (effDate != null) {
                        Element firstStartEl = doc.createElementNS(FpmlConstants.FPML_NS, "firstPeriodStartDate");
                        Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                        String dateStr = extractUnadjustedDateString(effDate);
                        if (dateStr != null) {
                            unadjEl.setTextContent(dateStr);
                            firstStartEl.appendChild(unadjEl);
                            fraElement.appendChild(firstStartEl);
                        }
                    }
                } catch (Exception ignored) {}

                // Extract termination date → secondPeriodStartDate
                try {
                    Object termDate = invokeField(calcDates, "getTerminationDate");
                    if (termDate != null) {
                        Element secondStartEl = doc.createElementNS(FpmlConstants.FPML_NS, "secondPeriodStartDate");
                        Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                        String dateStr = extractUnadjustedDateString(termDate);
                        if (dateStr != null) {
                            unadjEl.setTextContent(dateStr);
                            secondStartEl.appendChild(unadjEl);
                            fraElement.appendChild(secondStartEl);
                        }
                    }
                } catch (Exception ignored) {}

                // Also try adjusted dates (some FRA data uses adjustedDate instead of unadjustedDate)
                if (fraElement.getElementsByTagNameNS(FpmlConstants.FPML_NS, "firstPeriodStartDate").getLength() == 0) {
                    try {
                        Object effDate = invokeField(calcDates, "getEffectiveDate");
                        if (effDate != null) {
                            Element firstStartEl = doc.createElementNS(FpmlConstants.FPML_NS, "firstPeriodStartDate");
                            String adjStr = extractAdjustedDateString(effDate);
                            if (adjStr != null) {
                                Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                                unadjEl.setTextContent(adjStr);
                                firstStartEl.appendChild(unadjEl);
                                fraElement.appendChild(firstStartEl);
                            }
                        }
                    } catch (Exception ignored) {}
                }

                if (fraElement.getElementsByTagNameNS(FpmlConstants.FPML_NS, "secondPeriodStartDate").getLength() == 0) {
                    try {
                        Object termDate = invokeField(calcDates, "getTerminationDate");
                        if (termDate != null) {
                            Element secondStartEl = doc.createElementNS(FpmlConstants.FPML_NS, "secondPeriodStartDate");
                            String adjStr = extractAdjustedDateString(termDate);
                            if (adjStr != null) {
                                Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                                unadjEl.setTextContent(adjStr);
                                secondStartEl.appendChild(unadjEl);
                                fraElement.appendChild(secondStartEl);
                            }
                        }
                    } catch (Exception ignored) {}
                }

                // 3. Notional schedule from fixed leg's priceQuantity
                try {
                    Element notionalSched = buildFraNotionalSchedule(doc, fixedPayout, floatPayout);
                    if (notionalSched != null) {
                        fraElement.appendChild(notionalSched);
                    }
                } catch (Exception e) {
                    context.addWarning("Could not map FRA notional schedule: " + e.getMessage());
                }

                // 4. Fixed rate from fixed leg's rateSpecification
                try {
                    Object rateSpec = invokeField(fixedPayout, "getRateSpecification");
                    if (rateSpec != null) {
                        Object fixedSpec = invokeField(rateSpec, "getFixedRateSpecification");
                        if (fixedSpec != null) {
                            String fixedRateStr = extractFixedRateValue(fixedSpec);
                            if (fixedRateStr != null && !fixedRateStr.isEmpty()) {
                                // Convert decimal representation: 0.04 → 0.04, or check if it's already in correct format
                                Element fixedRateEl = doc.createElementNS(FpmlConstants.FPML_NS, "fixedRate");
                                fixedRateEl.setTextContent(fixedRateStr);
                                fraElement.appendChild(fixedRateEl);
                            }
                        }
                    }
                } catch (Exception e) {
                    context.addWarning("Could not extract FRA fixed rate: " + e.getMessage());
                }

                // 5. Floating rate index from floating leg's rateSpecification
                try {
                    Object floatRateSpec = invokeField(floatPayout, "getRateSpecification");
                    if (floatRateSpec != null) {
                        Object floatSpec = invokeField(floatRateSpec, "getFloatingRateSpecification");
                        if (floatSpec != null) {
                            Element floatingCalc = doc.createElementNS(FpmlConstants.FPML_NS, "floatingRateCalculation");

                            // Floating rate index name
                            try {
                                Object rateOption = invokeField(floatSpec, "getRateOption");
                                if (rateOption != null) {
                                    String indexName = extractIndexName(rateOption);
                                    Element idxEl = doc.createElementNS(FpmlConstants.FPML_NS, "floatingRateIndex");
                                    idxEl.setTextContent(indexName != null ? indexName : "UNKNOWN-INDEX");
                                    floatingCalc.appendChild(idxEl);

                                    // Put floatingRateCalculation as a child of fra (not inside another element)
                                    // In FpML FRA, floatingRateIndex is a direct child or inside floatingRateCalculation
                                    Element floatIdxEl = doc.createElementNS(FpmlConstants.FPML_NS, "floatingRateIndex");
                                    floatIdxEl.setTextContent(indexName != null ? indexName : "UNKNOWN-INDEX");
                                    fraElement.appendChild(floatIdxEl);
                                }
                            } catch (Exception ignored) {}

                            // Index tenor from calculation period frequency
                            try {
                                Element indexTenor = mapIndexTenor(calcDates, doc);
                                if (indexTenor != null) {
                                    fraElement.appendChild(indexTenor);
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception e) {
                    context.addWarning("Could not extract floating rate index: " + e.getMessage());
                }

                // 6. Day count fraction from fixed leg
                try {
                    Object dcf = invokeField(fixedPayout, "getDayCountFraction");
                    if (dcf != null) {
                        Element dcfEl = doc.createElementNS(FpmlConstants.FPML_NS, "dayCountFraction");
                        dcfEl.setTextContent(extractDcfValue(dcf));
                        fraElement.appendChild(dcfEl);
                    }
                } catch (Exception ignored) {}

                // 7. FRA discounting method from either leg's discountingMethod
                try {
                    Object discMethod = invokeField(fixedPayout, "getDiscountingMethod");
                    if (discMethod == null) {
                        discMethod = invokeField(floatPayout, "getDiscountingMethod");
                    }
                    if (discMethod != null) {
                        Element fraDiscEl = doc.createElementNS(FpmlConstants.FPML_NS, "fraDiscounting");
                        try {
                            Object discType = invokeField(discMethod, "getDiscountingType");
                            if (discType != null) {
                                String val = String.valueOf(discType);
                                // Map CDM DiscountingTypeEnum to FpML fraDiscounting values
                                switch (val.toUpperCase()) {
                                    case "FRA":
                                        fraDiscEl.setTextContent("ISDA");
                                        break;
                                    case "AFMA":
                                        fraDiscEl.setTextContent("AFMA");
                                        break;
                                    default:
                                        fraDiscEl.setTextContent(val);
                                }
                            } else {
                                fraDiscEl.setTextContent("NONE");
                            }
                        } catch (Exception ignored) {
                            fraDiscEl.setTextContent("ISDA");
                        }
                        fraElement.appendChild(fraDiscEl);
                    }
                } catch (Exception ignored) {}

            }
        } catch (Exception e) {
            context.addWarning("Could not map FRA calculation period dates: " + e.getMessage());
        }
    }

    private void mapFraFromSingleLeg(Document doc, Element fraElement, Object irPayoutObj,
                                      TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        // Determine party references from payerReceiver
        String buyerHref = "party1";
        String sellerHref = "party2";

        try {
            Object payerReceiver = invokeField(irPayoutObj, "getPayerReceiver");
            if (payerReceiver != null) {
                java.lang.reflect.Method getM = payerReceiver.getClass().getMethod("get", int.class);
                Object payerSideObj = getM.invoke(payerReceiver, 0);

                Object trade = invokeField(tradeState, "getTrade");
                List<?> counterparties = (List<?>) invokeField(trade, "getCounterparty");
                if (counterparties != null) {
                    for (Object cp : counterparties) {
                        if (cp instanceof Counterparty) {
                            Object cpRole = invoke((Counterparty) cp, "getRole");
                            String extRef = extractExternalReferenceFromRef((Counterparty) cp);

                            if (extRef != null && cpRole instanceof Enum) {
                                String cdmRoleName = ((Enum<?>) cpRole).name();
                                String expectedPayerSide = "Party" + (cdmRoleName.contains("1") ? "1" : "2");

                                if (expectedPayerSide.equals(payerSideObj.toString())) {
                                    buyerHref = extRef;
                                } else {
                                    sellerHref = extRef;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // Buyer/Seller party references
        Element buyerRef = doc.createElementNS(FpmlConstants.FPML_NS, "buyerPartyReference");
        buyerRef.setAttribute("href", "#" + buyerHref);
        fraElement.appendChild(buyerRef);

        Element sellerRef = doc.createElementNS(FpmlConstants.FPML_NS, "sellerPartyReference");
        sellerRef.setAttribute("href", "#" + sellerHref);
        fraElement.appendChild(sellerRef);

        // Dates from calculation period dates
        try {
            Object calcDates = invokeField(irPayoutObj, "getCalculationPeriodDates");
            if (calcDates != null) {
                // First period start date (effectiveDate or adjustedDate)
                Element firstStartEl = doc.createElementNS(FpmlConstants.FPML_NS, "firstPeriodStartDate");
                String dateStr = extractUnadjustedDateString(calcDates);
                if (dateStr == null) {
                    dateStr = extractAdjustedDateString(calcDates);
                }
                if (dateStr != null) {
                    Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                    unadjEl.setTextContent(dateStr);
                    firstStartEl.appendChild(unadjEl);
                    fraElement.appendChild(firstStartEl);
                }

                // Second period start date (terminationDate or adjustedDate)
                Element secondStartEl = doc.createElementNS(FpmlConstants.FPML_NS, "secondPeriodStartDate");
                String termStr = extractUnadjustedTerminationDateString(calcDates);
                if (termStr == null) {
                    termStr = extractAdjustedTerminationDateString(calcDates);
                }
                if (termStr != null) {
                    Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                    unadjEl.setTextContent(termStr);
                    secondStartEl.appendChild(unadjEl);
                    fraElement.appendChild(secondStartEl);
                }

                // Notional schedule
                try {
                    Element notionalSched = buildFraNotionalSchedule(doc, irPayoutObj, null);
                    if (notionalSched != null) {
                        fraElement.appendChild(notionalSched);
                    }
                } catch (Exception e) {
                    context.addWarning("Could not map FRA notional schedule: " + e.getMessage());
                }

                // Fixed rate or floating index
                try {
                    Object rateSpec = invokeField(irPayoutObj, "getRateSpecification");
                    if (rateSpec != null) {
                        Object fixedSpec = invokeField(rateSpec, "getFixedRateSpecification");
                        if (fixedSpec != null) {
                            String fixedRateStr = extractFixedRateValue(fixedSpec);
                            if (fixedRateStr != null && !fixedRateStr.isEmpty()) {
                                Element fixedRateEl = doc.createElementNS(FpmlConstants.FPML_NS, "fixedRate");
                                fixedRateEl.setTextContent(fixedRateStr);
                                fraElement.appendChild(fixedRateEl);
                            }
                        } else {
                            Object floatSpec = invokeField(rateSpec, "getFloatingRateSpecification");
                            if (floatSpec != null) {
                                try {
                                    Object rateOption = invokeField(floatSpec, "getRateOption");
                                    if (rateOption != null) {
                                        String indexName = extractIndexName(rateOption);
                                        Element idxEl = doc.createElementNS(FpmlConstants.FPML_NS, "floatingRateIndex");
                                        idxEl.setTextContent(indexName != null ? indexName : "UNKNOWN-INDEX");
                                        fraElement.appendChild(idxEl);
                                    }
                                } catch (Exception ignored) {}

                                try {
                                    Element indexTenor = mapIndexTenor(calcDates, doc);
                                    if (indexTenor != null) {
                                        fraElement.appendChild(indexTenor);
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                } catch (Exception e) {
                    context.addWarning("Could not extract FRA rate: " + e.getMessage());
                }

                // Day count fraction
                try {
                    Object dcf = invokeField(irPayoutObj, "getDayCountFraction");
                    if (dcf != null) {
                        Element dcfEl = doc.createElementNS(FpmlConstants.FPML_NS, "dayCountFraction");
                        dcfEl.setTextContent(extractDcfValue(dcf));
                        fraElement.appendChild(dcfEl);
                    }
                } catch (Exception ignored) {}

            }
        } catch (Exception e) {
            context.addWarning("Could not map FRA dates: " + e.getMessage());
        }
    }

    private Element buildFraNotionalSchedule(Document doc, Object payout1, Object payout2) throws Exception {
        // Try to get notional from the first payout's priceQuantity
        Object priceQty = invokeField(payout1, "getPriceQuantity");
        if (priceQty == null && payout2 != null) {
            priceQty = invokeField(payout2, "getPriceQuantity");
        }

        String valStr = null;
        String currency = "USD";

        if (priceQty != null) {
            try {
                Object quantity = invokeField(priceQty, "getQuantity");
                if (quantity != null && quantity instanceof java.util.List) {
                    for (Object q : (java.util.List<?>) quantity) {
                        Object valueObj = invokeField(q, "getValue");
                        if (valueObj != null) {
                            valStr = extractNumericValue(valueObj);
                            if (valStr != null && !valStr.isEmpty()) break;
                        }
                    }
                }
            } catch (Exception ignored) {}

            try {
                currency = extractCurrencyFromPriceQuantity(priceQty);
            } catch (Exception ignored) {}
        }

        // Fallback: try quantitySchedule directly on the payout
        if (valStr == null || valStr.isEmpty()) {
            try {
                Object qtySched = invokeField(payout1, "getQuantitySchedule");
                if (qtySched != null) {
                    valStr = extractNumericValue(qtySched);
                }
            } catch (Exception ignored) {}

            if (valStr == null || valStr.isEmpty()) {
                try {
                    Object qtySched2 = invokeField(payout2, "getQuantitySchedule");
                    if (qtySched2 != null) {
                        valStr = extractNumericValue(qtySched2);
                    }
                } catch (Exception ignored) {}
            }
        }

        if (valStr == null || valStr.isEmpty()) {
            valStr = "1000000.00";
        }

        Element notionalSchedule = doc.createElementNS(FpmlConstants.FPML_NS, "notionalSchedule");
        Element notionalPeriod = doc.createElementNS(FpmlConstants.FPML_NS, "notionalPeriod");

        // Notion period start/end dates from calcDates
        try {
            Object calcDates = invokeField(payout1, "getCalculationPeriodDates");
            if (calcDates != null) {
                Element notionStartEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionPeriodStart");
                String dateStr = extractUnadjustedDateString(calcDates);
                if (dateStr == null) dateStr = extractAdjustedDateString(calcDates);
                if (dateStr != null) {
                    Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                    unadjEl.setTextContent(dateStr);
                    notionStartEl.appendChild(unadjEl);
                    notionalPeriod.appendChild(notionStartEl);
                }

                Element notionEndEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionPeriodEnd");
                String termStr = extractUnadjustedTerminationDateString(calcDates);
                if (termStr == null) termStr = extractAdjustedTerminationDateString(calcDates);
                if (termStr != null) {
                    Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                    unadjEl.setTextContent(termStr);
                    notionEndEl.appendChild(unadjEl);
                    notionalPeriod.appendChild(notionEndEl);
                }
            }
        } catch (Exception ignored) {}

        Element amountEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionPeriodAmount");
        Element valueEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
        valueEl.setTextContent(valStr);
        amountEl.appendChild(valueEl);

        Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
        currEl.setTextContent(currency);
        amountEl.appendChild(currEl);

        notionalPeriod.appendChild(amountEl);
        notionalSchedule.appendChild(notionalPeriod);

        return notionalSchedule;
    }

    private String extractFixedRateValue(Object fixedSpecObj) throws Exception {
        if (fixedSpecObj == null) return null;

        try {
            Object rateSchedule = invokeField(fixedSpecObj, "getRateSchedule");
            if (rateSchedule != null) {
                Object price = invokeField(rateSchedule, "getPrice");
                if (price != null) {
                    String valStr = extractNumericValue(price);
                    if (valStr != null && !valStr.isEmpty()) return valStr;
                }
            }
        } catch (Exception ignored) {}

        try {
            Object schedule = invokeField(fixedSpecObj, "getSchedule");
            if (schedule instanceof java.util.List) {
                for (Object item : (java.util.List<?>) schedule) {
                    String valStr = extractNumericValue(item);
                    if (valStr != null && !valStr.isEmpty()) return valStr;
                }
            }
        } catch (Exception ignored) {}

        // Try to get value directly from fixedSpecObj
        String valStr = extractNumericValue(fixedSpecObj);
        return valStr;
    }

    private String extractUnadjustedDateString(Object obj) throws Exception {
        if (obj == null) return null;

        try {
            Object effDate = invokeField(obj, "getEffectiveDate");
            if (effDate != null) {
                java.lang.reflect.Method getAdj = effDate.getClass().getMethod("getAdjustableDate");
                Object adjustableDate = getAdj.invoke(effDate);
                if (adjustableDate != null) {
                    try {
                        java.lang.reflect.Method getUnadj = adjustableDate.getClass().getMethod("getUnadjustedDate");
                        Object unadj = getUnadj.invoke(adjustableDate);
                        if (unadj != null) {
                            String dateStr = extractDateString(unadj);
                            if (dateStr != null && !dateStr.isEmpty()) return dateStr;
                        }
                    } catch (NoSuchMethodException ignored) {}

                    // Try toString() on adjustableDate for unadjustedDate= pattern
                    String adjStr = adjustableDate.toString();
                    if (adjStr.contains("unadjustedDate=")) {
                        int start = adjStr.indexOf("unadjustedDate=") + 15;
                        int end = adjStr.indexOf(",", start);
                        if (end == -1) end = adjStr.indexOf("}", start);
                        if (end > start) {
                            String dateVal = adjStr.substring(start, end).trim();
                            if (dateVal.matches("\\d{4}-\\d{2}-\\d{2}")) return dateVal;
                            // Handle date with year/month/day structure
                            if (dateVal.contains("year=")) {
                                int yStart = dateVal.indexOf("year=") + 5;
                                int yEnd = dateVal.indexOf(",", yStart);
                                int mStart = dateVal.indexOf("month=", Math.max(yEnd, 0));
                                int mEnd = dateVal.indexOf(",", mStart);
                                int dStart = dateVal.indexOf("day=", Math.max(mEnd, 0));
                                int dEnd = dateVal.indexOf("}", dStart);
                                if (yEnd > yStart && mEnd > mStart && dEnd > dStart) {
                                    String year = dateVal.substring(yStart, yEnd).trim();
                                    String month = dateVal.substring(mStart + 6, mEnd).trim();
                                    String day = dateVal.substring(dStart + 4, dEnd).trim();
                                    return String.format("%s-%02d-%02d", year, Integer.parseInt(month), Integer.parseInt(day));
                                }
                            }
                        }
                    }

                    // Handle adjustedDate as fallback
                    if (adjStr.contains("adjustedDate=")) {
                        int start = adjStr.indexOf("adjustedDate=") + 13;
                        int end = adjStr.indexOf(",", start);
                        if (end == -1) end = adjStr.indexOf("}", start);
                        if (end > start) {
                            String dateVal = adjStr.substring(start, end).trim();
                            if (dateVal.contains("value=")) {
                                int vStart = dateVal.indexOf("value=") + 6;
                                int vEnd = dateVal.indexOf(",", vStart);
                                if (vEnd == -1) vEnd = dateVal.indexOf("}", vStart);
                                if (vEnd > vStart) {
                                    String adjustedVal = dateVal.substring(vStart, vEnd).trim();
                                    if (adjustedVal.matches("\\d{4}-\\d{2}-\\d{2}")) return adjustedVal;
                                }
                            } else if (dateVal.matches("\\d{4}-\\d{2}-\\d{2}")) {
                                return dateVal;
                            }
                        }
                    }

                    // Direct Date value extraction from adjustableDate
                    try {
                        java.lang.reflect.Method getUnadj2 = adjustableDate.getClass().getMethod("getUnadjustedDate");
                        Object unadj2 = getUnadj2.invoke(adjustableDate);
                        if (unadj2 instanceof com.rosetta.model.lib.records.Date) {
                            com.rosetta.model.lib.records.Date d = (com.rosetta.model.lib.records.Date) unadj2;
                            return String.format("%04d-%02d-%02d", d.getYear(), d.getMonth(), d.getDay());
                        }
                    } catch (NoSuchMethodException ignored) {}

                    try {
                        java.lang.reflect.Method getAdj2 = adjustableDate.getClass().getMethod("getAdjustedDate");
                        Object adj2 = getAdj2.invoke(adjustableDate);
                        if (adj2 instanceof com.rosetta.model.lib.records.Date) {
                            com.rosetta.model.lib.records.Date d = (com.rosetta.model.lib.records.Date) adj2;
                            return String.format("%04d-%02d-%02d", d.getYear(), d.getMonth(), d.getDay());
                        }
                    } catch (NoSuchMethodException ignored) {}
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    private String extractAdjustedDateString(Object obj) throws Exception {
        if (obj == null) return null;

        try {
            Object effDate = invokeField(obj, "getEffectiveDate");
            if (effDate != null) {
                java.lang.reflect.Method getAdj = effDate.getClass().getMethod("getAdjustableDate");
                Object adjustableDate = getAdj.invoke(effDate);
                if (adjustableDate != null) {
                    try {
                        java.lang.reflect.Method getAdjusted = adjustableDate.getClass().getMethod("getAdjustedDate");
                        Object adjusted = getAdjusted.invoke(adjustableDate);
                        if (adjusted instanceof com.rosetta.model.lib.records.Date) {
                            com.rosetta.model.lib.records.Date d = (com.rosetta.model.lib.records.Date) adjusted;
                            return String.format("%04d-%02d-%02d", d.getYear(), d.getMonth(), d.getDay());
                        }
                    } catch (NoSuchMethodException ignored) {}

                    // Parse from toString() for adjustedDate.value pattern
                    String adjStr = adjustableDate.toString();
                    if (adjStr.contains("adjustedDate=")) {
                        int start = adjStr.indexOf("adjustedDate=") + 13;
                        int end = adjStr.indexOf(",", start);
                        if (end == -1) end = adjStr.indexOf("}", start);
                        if (end > start) {
                            String dateVal = adjStr.substring(start, end).trim();
                            if (dateVal.contains("value=")) {
                                int vStart = dateVal.indexOf("value=") + 6;
                                int vEnd = dateVal.indexOf(",", vStart);
                                if (vEnd == -1) vEnd = dateVal.indexOf("}", vStart);
                                if (vEnd > vStart) {
                                    String adjustedVal = dateVal.substring(vStart, vEnd).trim();
                                    if (adjustedVal.matches("\\d{4}-\\d{2}-\\d{2}")) return adjustedVal;
                                }
                            } else if (dateVal.matches("\\d{4}-\\d{2}-\\d{2}")) {
                                return dateVal;
                            }
                        }
                    }

                    // Try to extract year/month/day from the adjustableDate toString
                    String str = adjStr;
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
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    private String extractUnadjustedTerminationDateString(Object obj) throws Exception {
        if (obj == null) return null;

        try {
            Object termDate = invokeField(obj, "getTerminationDate");
            if (termDate != null) {
                java.lang.reflect.Method getAdj = termDate.getClass().getMethod("getAdjustableDate");
                Object adjustableDate = getAdj.invoke(termDate);
                if (adjustableDate != null) {
                    try {
                        java.lang.reflect.Method getUnadj = adjustableDate.getClass().getMethod("getUnadjustedDate");
                        Object unadj = getUnadj.invoke(adjustableDate);
                        if (unadj != null) {
                            String dateStr = extractDateString(unadj);
                            if (dateStr != null && !dateStr.isEmpty()) return dateStr;
                        }
                    } catch (NoSuchMethodException ignored) {}

                    // Parse from toString() for unadjustedDate= pattern
                    String adjStr = adjustableDate.toString();
                    if (adjStr.contains("unadjustedDate=")) {
                        int start = adjStr.indexOf("unadjustedDate=") + 15;
                        int end = adjStr.indexOf(",", start);
                        if (end == -1) end = adjStr.indexOf("}", start);
                        if (end > start) {
                            String dateVal = adjStr.substring(start, end).trim();
                            if (dateVal.matches("\\d{4}-\\d{2}-\\d{2}")) return dateVal;
                            // Handle date with year/month/day structure
                            if (dateVal.contains("year=")) {
                                int yStart = dateVal.indexOf("year=") + 5;
                                int yEnd = dateVal.indexOf(",", yStart);
                                int mStart = dateVal.indexOf("month=", Math.max(yEnd, 0));
                                int mEnd = dateVal.indexOf(",", mStart);
                                int dStart = dateVal.indexOf("day=", Math.max(mEnd, 0));
                                int dEnd = dateVal.indexOf("}", dStart);
                                if (yEnd > yStart && mEnd > mStart && dEnd > dStart) {
                                    String year = dateVal.substring(yStart, yEnd).trim();
                                    String month = dateVal.substring(mStart + 6, mEnd).trim();
                                    String day = dateVal.substring(dStart + 4, dEnd).trim();
                                    return String.format("%s-%02d-%02d", year, Integer.parseInt(month), Integer.parseInt(day));
                                }
                            }
                        }
                    }

                    // Handle adjustedDate as fallback
                    if (adjStr.contains("adjustedDate=")) {
                        int start = adjStr.indexOf("adjustedDate=") + 13;
                        int end = adjStr.indexOf(",", start);
                        if (end == -1) end = adjStr.indexOf("}", start);
                        if (end > start) {
                            String dateVal = adjStr.substring(start, end).trim();
                            if (dateVal.contains("value=")) {
                                int vStart = dateVal.indexOf("value=") + 6;
                                int vEnd = dateVal.indexOf(",", vStart);
                                if (vEnd == -1) vEnd = dateVal.indexOf("}", vStart);
                                if (vEnd > vStart) {
                                    String adjustedVal = dateVal.substring(vStart, vEnd).trim();
                                    if (adjustedVal.matches("\\d{4}-\\d{2}-\\d{2}")) return adjustedVal;
                                }
                            } else if (dateVal.matches("\\d{4}-\\d{2}-\\d{2}")) {
                                return dateVal;
                            }
                        }
                    }

                    // Direct Date value extraction from adjustableDate
                    try {
                        java.lang.reflect.Method getUnadj2 = adjustableDate.getClass().getMethod("getUnadjustedDate");
                        Object unadj2 = getUnadj2.invoke(adjustableDate);
                        if (unadj2 instanceof com.rosetta.model.lib.records.Date) {
                            com.rosetta.model.lib.records.Date d = (com.rosetta.model.lib.records.Date) unadj2;
                            return String.format("%04d-%02d-%02d", d.getYear(), d.getMonth(), d.getDay());
                        }
                    } catch (NoSuchMethodException ignored) {}

                    try {
                        java.lang.reflect.Method getAdj2 = adjustableDate.getClass().getMethod("getAdjustedDate");
                        Object adj2 = getAdj2.invoke(adjustableDate);
                        if (adj2 instanceof com.rosetta.model.lib.records.Date) {
                            com.rosetta.model.lib.records.Date d = (com.rosetta.model.lib.records.Date) adj2;
                            return String.format("%04d-%02d-%02d", d.getYear(), d.getMonth(), d.getDay());
                        }
                    } catch (NoSuchMethodException ignored) {}
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    private String extractAdjustedTerminationDateString(Object obj) throws Exception {
        if (obj == null) return null;

        try {
            Object termDate = invokeField(obj, "getTerminationDate");
            if (termDate != null) {
                java.lang.reflect.Method getAdj = termDate.getClass().getMethod("getAdjustableDate");
                Object adjustableDate = getAdj.invoke(termDate);
                if (adjustableDate != null) {
                    try {
                        java.lang.reflect.Method getAdjusted = adjustableDate.getClass().getMethod("getAdjustedDate");
                        Object adjusted = getAdjusted.invoke(adjustableDate);
                        if (adjusted instanceof com.rosetta.model.lib.records.Date) {
                            com.rosetta.model.lib.records.Date d = (com.rosetta.model.lib.records.Date) adjusted;
                            return String.format("%04d-%02d-%02d", d.getYear(), d.getMonth(), d.getDay());
                        }
                    } catch (NoSuchMethodException ignored) {}

                    // Parse from toString() for adjustedDate.value pattern
                    String adjStr = adjustableDate.toString();
                    if (adjStr.contains("adjustedDate=")) {
                        int start = adjStr.indexOf("adjustedDate=") + 13;
                        int end = adjStr.indexOf(",", start);
                        if (end == -1) end = adjStr.indexOf("}", start);
                        if (end > start) {
                            String dateVal = adjStr.substring(start, end).trim();
                            if (dateVal.contains("value=")) {
                                int vStart = dateVal.indexOf("value=") + 6;
                                int vEnd = dateVal.indexOf(",", vStart);
                                if (vEnd == -1) vEnd = dateVal.indexOf("}", vStart);
                                if (vEnd > vStart) {
                                    String adjustedVal = dateVal.substring(vStart, vEnd).trim();
                                    if (adjustedVal.matches("\\d{4}-\\d{2}-\\d{2}")) return adjustedVal;
                                }
                            } else if (dateVal.matches("\\d{4}-\\d{2}-\\d{2}")) {
                                return dateVal;
                            }
                        }
                    }

                    // Try to extract year/month/day from the adjustableDate toString
                    String str = adjStr;
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
                }
            }
        } catch (Exception ignored) {}

        return null;
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

    private String extractDateString(Object dateObj) throws Exception {
        if (dateObj == null) return null;

        try {
            java.lang.reflect.Method getVal = dateObj.getClass().getMethod("getValue");
            Object val = getVal.invoke(dateObj);
            if (val != null && !val.toString().contains("{")) {
                if (val instanceof java.time.LocalDate) return ((java.time.LocalDate) val).toString();
                if (val instanceof com.rosetta.model.lib.records.Date) {
                    try {
                        int year = ((com.rosetta.model.lib.records.Date) val).getYear();
                        int month = ((com.rosetta.model.lib.records.Date) val).getMonth();
                        int day = ((com.rosetta.model.lib.records.Date) val).getDay();
                        return String.format("%04d-%02d-%02d", year, month, day);
                    } catch (Exception ignored) {}
                }
                if (!val.toString().contains("{")) return String.valueOf(val);
            }
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
        if (dateObj instanceof com.rosetta.model.lib.records.Date) {
            try {
                int year = ((com.rosetta.model.lib.records.Date) dateObj).getYear();
                int month = ((com.rosetta.model.lib.records.Date) dateObj).getMonth();
                int day = ((com.rosetta.model.lib.records.Date) dateObj).getDay();
                return String.format("%04d-%02d-%02d", year, month, day);
            } catch (Exception ignored) {}
        }

        // Parse from toString() for date-like content with year/month/day
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
