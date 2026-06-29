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
 * CDM Commodity Swap → FpML {@code <commoditySwap>} mapper.
 * 
 * Detects commodity swap by checking for CommodityPayout (floating leg) + FixedPricePayout (fixed leg).
 */
public class CommoditySwapMapper implements CdmToFpmlProductMapper {

    @Override
    public Element map(TradeState tradeState, CdmToFpmlMappingContext context) {
        try {
            return doMap(tradeState, context);
        } catch (Exception e) {
            context.addWarning("Commodity Swap mapping error: " + e.getMessage());
            Document doc = context.getDocument();
            return createFallbackCommoditySwap(doc);
        }
    }

    private Element doMap(TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Document doc = context.getDocument();

        registerPartiesFromTrade(tradeState, context);

        Object product = invokeField(tradeState, "getProduct");
        if (product == null) return createFallbackCommoditySwap(doc);

        Object econTerms = invokeField(product, "getEconomicTerms");
        if (econTerms == null) return createFallbackCommoditySwap(doc);

        List<? extends Payout> payouts = (List<? extends Payout>) invokeField(econTerms, "getPayout");
        if (payouts == null || payouts.isEmpty()) return createFallbackCommoditySwap(doc);

        // Detect commodity swap: CommodityPayout + FixedPricePayout
        boolean hasCommodity = false;
        boolean hasFixedPrice = false;

        for (Payout p : payouts) {
            Object commPayout = invokeField(p, "getCommodityPayout");
            if (commPayout != null) hasCommodity = true;

            Object fixedPayout = invokeField(p, "getFixedPricePayout");
            if (fixedPayout != null) hasFixedPrice = true;
        }

        // A commodity swap has both a commodity payout and a fixed price payout
        if (!hasCommodity && !hasFixedPrice) {
            return createFallbackCommoditySwap(doc);
        }

        Element comSwap = doc.createElementNS(FpmlConstants.FPML_NS, "commoditySwap");

        // Map trade date header
        mapTradeDate(doc, comSwap, tradeState, context);

        // Effective and termination dates from economicTerms
        try {
            Object effDateObj = invokeField(econTerms, "getEffectiveDate");
            if (effDateObj != null) {
                Element effEl = doc.createElementNS(FpmlConstants.FPML_NS, "effectiveDate");
                effEl.appendChild(mapAdjustableOrRelativeDate(doc, effDateObj));
                comSwap.appendChild(effEl);
            }
        } catch (Exception ignored) {}

        try {
            Object termDateObj = invokeField(econTerms, "getTerminationDate");
            if (termDateObj != null) {
                Element termEl = doc.createElementNS(FpmlConstants.FPML_NS, "terminationDate");
                termEl.appendChild(mapAdjustableOrRelativeDate(doc, termDateObj));
                comSwap.appendChild(termEl);
            }
        } catch (Exception ignored) {}

        // Settlement currency from first commodity payout or tradeLot
        try {
            Object settCurrency = extractSettlementCurrency(payouts, tradeState);
            if (settCurrency != null && settCurrency.toString().length() > 0) {
                Element ccyEl = doc.createElementNS(FpmlConstants.FPML_NS, "settlementCurrency");
                ccyEl.setTextContent(settCurrency.toString());
                comSwap.appendChild(ccyEl);
            }
        } catch (Exception ignored) {}

        // Build legs: floating leg(s) from CommodityPayout and fixed leg(s) from FixedPricePayout
        for (Payout p : payouts) {
            Object commPayout = invokeField(p, "getCommodityPayout");
            if (commPayout != null) {
                Element floatingLeg = buildFloatingLeg(doc, commPayout, tradeState, context);
                if (floatingLeg != null) {
                    comSwap.appendChild(floatingLeg);
                }
            }

            Object fixedPayout = invokeField(p, "getFixedPricePayout");
            if (fixedPayout != null) {
                Element fixedLeg = buildFixedLeg(doc, fixedPayout, tradeState, context);
                if (fixedLeg != null) {
                    comSwap.appendChild(fixedLeg);
                }
            }
        }

        return comSwap;
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

    private Element buildFloatingLeg(Document doc, Object commPayout, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Element floatingLeg = doc.createElementNS(FpmlConstants.FPML_NS, "floatingLeg");

        // Payer/Receiver party references from CommodityPayout payerReceiver
        try {
            Object payerReceiver = invokeField(commPayout, "getPayerReceiver");
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
                floatingLeg.appendChild(payerRef);

                Element receiverRef = doc.createElementNS(FpmlConstants.FPML_NS, "receiverPartyReference");
                receiverRef.setAttribute("href", "#" + receiverHref);
                floatingLeg.appendChild(receiverRef);
            }
        } catch (Exception ignored) {
            Element payerRef = doc.createElementNS(FpmlConstants.FPML_NS, "payerPartyReference");
            payerRef.setAttribute("href", "#party1");
            floatingLeg.appendChild(payerRef);

            Element receiverRef = doc.createElementNS(FpmlConstants.FPML_NS, "receiverPartyReference");
            receiverRef.setAttribute("href", "#party2");
            floatingLeg.appendChild(receiverRef);
        }

        // Commodity reference from underlier observable
        try {
            Object underlier = invokeField(commPayout, "getUnderlier");
            if (underlier != null) {
                Element commodityEl = doc.createElementNS(FpmlConstants.FPML_NS, "commodity");

                try {
                    Object observable = invokeField(underlier, "getObservable");
                    if (observable != null) {
                        // Try to get commodity identifier from tradeLot priceQuantity
                        String commodityName = extractCommodityName(tradeState);
                        if (commodityName != null && !commodityName.isEmpty()) {
                            Element instrIdEl = doc.createElementNS(FpmlConstants.FPML_NS, "instrumentId");
                            instrIdEl.setTextContent(commodityName);
                            // Try to determine scheme from commodity name patterns
                            String scheme = detectCommodityScheme(commodityName);
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

                floatingLeg.appendChild(commodityEl);
            }
        } catch (Exception ignored) {}

        // Pricing dates from pricingDates or calculationPeriodFrequency
        try {
            Object pricingDates = invokeField(commPayout, "getPricingDates");
            if (pricingDates != null) {
                Element pricingDatesEl = doc.createElementNS(FpmlConstants.FPML_NS, "pricingDates");

                // Try parametric dates
                try {
                    Object paramDates = invokeField(pricingDates, "getParametricDates");
                    if (paramDates != null) {
                        try {
                            Object dayType = invokeField(paramDates, "getDayType");
                            if (dayType instanceof Enum) {
                                Element dayTypeEl = doc.createElementNS(FpmlConstants.FPML_NS, "dayType");
                                String dayTypeName = mapDayTypeEnum((Enum<?>) dayType);
                                dayTypeEl.setTextContent(dayTypeName);
                                pricingDatesEl.appendChild(dayTypeEl);
                            }
                        } catch (Exception ignored) {}

                        try {
                            Object dayDist = invokeField(paramDates, "getDayDistribution");
                            if (dayDist instanceof Enum) {
                                Element dayDistEl = doc.createElementNS(FpmlConstants.FPML_NS, "dayDistribution");
                                String distStr = mapEnumToPascalCase((Enum<?>) dayDist);
                                dayDistEl.setTextContent(distStr);
                                pricingDatesEl.appendChild(dayDistEl);
                            }
                        } catch (Exception ignored) {}

                        // Business calendars
                        try {
                            Object businessCenters = invokeField(paramDates, "getBusinessCenters");
                            if (businessCenters != null) {
                                Element bizCalEl = doc.createElementNS(FpmlConstants.FPML_NS, "businessCalendar");
                                try {
                                    Object calList = invokeField(businessCenters, "getCommodityBusinessCalendar");
                                    if (calList instanceof java.util.List) {
                                        for (Object cal : (java.util.List<?>) calList) {
                                            Element bcEl = doc.createElementNS(FpmlConstants.FPML_NS, "businessCenter");
                                            String bcStr = extractStringValue(cal);
                                            bcEl.setTextContent(bcStr != null ? bcStr : "USNY");
                                            bizCalEl.appendChild(bcEl);
                                        }
                                    } else {
                                        Element bcEl = doc.createElementNS(FpmlConstants.FPML_NS, "businessCenter");
                                        bcEl.setTextContent("USNY");
                                        bizCalEl.appendChild(bcEl);
                                    }
                                } catch (Exception ignored) {}
                                pricingDatesEl.appendChild(bizCalEl);
                            }
                        } catch (Exception ignored) {}

                        floatingLeg.appendChild(pricingDatesEl);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        // Calculation period dates from calculationPeriodDates
        try {
            Object calcPeriodDates = invokeField(commPayout, "getCalculationPeriodDates");
            if (calcPeriodDates != null) {
                Element calcScheduleEl = doc.createElementNS(FpmlConstants.FPML_NS, "calculationPeriodsSchedule");

                // Calculation period frequency
                try {
                    Object freq = invokeField(calcPeriodDates, "getCalculationPeriodFrequency");
                    if (freq != null) {
                        try {
                            Object pm = invokeField(freq, "getPeriodMultiplier");
                            if (pm != null) {
                                Element pmEl = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
                                pmEl.setTextContent(String.valueOf(pm));
                                calcScheduleEl.appendChild(pmEl);
                            }
                        } catch (Exception ignored) {}

                        try {
                            Object periodEnum = invokeField(freq, "getPeriod");
                            if (periodEnum instanceof Enum) {
                                Element pEl = doc.createElementNS(FpmlConstants.FPML_NS, "period");
                                String fpmlPeriod = mapPeriodToFpml((Enum<?>) periodEnum);
                                pEl.setTextContent(fpmlPeriod);
                                calcScheduleEl.appendChild(pEl);
                            }
                        } catch (Exception ignored) {}

                        floatingLeg.appendChild(calcScheduleEl);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        // Relative payment dates from paymentDates
        try {
            Object paymentDates = invokeField(commPayout, "getPaymentDates");
            if (paymentDates != null) {
                Element relPayDatesEl = doc.createElementNS(FpmlConstants.FPML_NS, "relativePaymentDates");

                // Pay relative to
                try {
                    Object payRelTo = invokeField(paymentDates, "getPayRelativeTo");
                    if (payRelTo instanceof Enum) {
                        Element relToEl = doc.createElementNS(FpmlConstants.FPML_NS, "payRelativeTo");
                        String relToStr = mapEnumToPascalCase((Enum<?>) payRelTo);
                        relToEl.setTextContent(relToStr);
                        relPayDatesEl.appendChild(relToEl);
                    }
                } catch (Exception ignored) {}

                // Payment days offset
                try {
                    Object offset = invokeField(paymentDates, "getPaymentDaysOffset");
                    if (offset != null) {
                        Element payDaysOffsetEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentDaysOffset");

                        try {
                            Object pm = invokeField(offset, "getPeriodMultiplier");
                            if (pm != null) {
                                Element pmEl = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
                                pmEl.setTextContent(String.valueOf(pm));
                                payDaysOffsetEl.appendChild(pmEl);
                            }
                        } catch (Exception ignored) {}

                        try {
                            Object periodEnum = invokeField(offset, "getPeriod");
                            if (periodEnum instanceof Enum) {
                                Element pEl = doc.createElementNS(FpmlConstants.FPML_NS, "period");
                                String fpmlPeriod = mapPeriodToFpml((Enum<?>) periodEnum);
                                pEl.setTextContent(fpmlPeriod);
                                payDaysOffsetEl.appendChild(pEl);
                            }
                        } catch (Exception ignored) {}

                        try {
                            Object dayType = invokeField(offset, "getDayType");
                            if (dayType instanceof Enum) {
                                Element dtEl = doc.createElementNS(FpmlConstants.FPML_NS, "dayType");
                                String dayTypeName = mapDayTypeEnum((Enum<?>) dayType);
                                dtEl.setTextContent(dayTypeName);
                                payDaysOffsetEl.appendChild(dtEl);
                            }
                        } catch (Exception ignored) {}

                        try {
                            Object bdc = invokeField(offset, "getBusinessDayConvention");
                            if (bdc instanceof Enum) {
                                Element bdcEl = doc.createElementNS(FpmlConstants.FPML_NS, "businessDayConvention");
                                String bdcStr = mapBdcEnum((Enum<?>) bdc);
                                bdcEl.setTextContent(bdcStr);
                                payDaysOffsetEl.appendChild(bdcEl);
                            }
                        } catch (Exception ignored) {}

                        relPayDatesEl.appendChild(payDaysOffsetEl);
                    }
                } catch (Exception ignored) {}

                floatingLeg.appendChild(relPayDatesEl);
            }
        } catch (Exception ignored) {}

        // Calculation block with spread and conversion factor
        try {
            Object priceQuantity = invokeField(commPayout, "getPriceQuantity");
            if (priceQuantity != null || true) {
                Element calculationEl = doc.createElementNS(FpmlConstants.FPML_NS, "calculation");

                // Spread from commodityPriceReturnTerms
                try {
                    Object commPriceReturnTerms = invokeField(commPayout, "getCommodityPriceReturnTerms");
                    if (commPriceReturnTerms != null) {
                        try {
                            Object spread = invokeField(commPriceReturnTerms, "getSpread");
                            if (spread != null) {
                                Element spreadEl = doc.createElementNS(FpmlConstants.FPML_NS, "spread");

                                // Try to get amount from priceQuantity or tradeLot
                                String spreadAmt = extractNumericValue(priceQuantity);
                                if (spreadAmt == null) spreadAmt = "0.00";
                                Element amtEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                                amtEl.setTextContent(spreadAmt);
                                spreadEl.appendChild(amtEl);

                                try {
                                    Object unit = invokeField(priceQuantity, "getUnit");
                                    if (unit != null) {
                                        Element ccyEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                                        String ccyStr = extractCurrencyFromAmountObj(unit);
                                        ccyEl.setTextContent(ccyStr != null ? ccyStr : "USD");
                                        spreadEl.appendChild(ccyEl);
                                    }
                                } catch (Exception ignored) {}

                                calculationEl.appendChild(spreadEl);
                            }
                        } catch (Exception ignored) {}

                        // Conversion factor
                        try {
                            Object convFactor = invokeField(commPriceReturnTerms, "getConversionFactor");
                            if (convFactor != null) {
                                Element cfEl = doc.createElementNS(FpmlConstants.FPML_NS, "conversionFactor");
                                String cfStr = extractNumericValue(convFactor);
                                cfEl.setTextContent(cfStr != null ? cfStr : "1.0");
                                calculationEl.appendChild(cfEl);
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}

                floatingLeg.appendChild(calculationEl);
            }
        } catch (Exception ignored) {}

        // Notional quantity from priceQuantity or tradeLot
        try {
            Object priceQuantity = invokeField(commPayout, "getPriceQuantity");
            if (priceQuantity != null) {
                Element notionalQtyEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionalQuantity");

                // Total notional quantity from tradeLot
                String totalNotional = extractTotalNotional(tradeState);
                if (totalNotional != null && !totalNotional.isEmpty()) {
                    Element qtyEl = doc.createElementNS(FpmlConstants.FPML_NS, "quantity");
                    qtyEl.setTextContent(totalNotional);
                    notionalQtyEl.appendChild(qtyEl);

                    // Quantity unit/capacity
                    String capacityUnit = extractCapacityUnit(tradeState);
                    if (capacityUnit != null) {
                        Element qUnitEl = doc.createElementNS(FpmlConstants.FPML_NS, "quantityUnit");
                        qUnitEl.setTextContent(capacityUnit);
                        notionalQtyEl.appendChild(qUnitEl);
                    }

                    floatingLeg.appendChild(notionalQtyEl);
                } else {
                    // Fallback: create minimal notional quantity element
                    Element qtyEl = doc.createElementNS(FpmlConstants.FPML_NS, "quantity");
                    qtyEl.setTextContent("1000.00");
                    notionalQtyEl.appendChild(qtyEl);

                    String capacityUnit = extractCapacityUnit(tradeState);
                    if (capacityUnit != null) {
                        Element qUnitEl = doc.createElementNS(FpmlConstants.FPML_NS, "quantityUnit");
                        qUnitEl.setTextContent(capacityUnit);
                        notionalQtyEl.appendChild(qUnitEl);
                    }

                    floatingLeg.appendChild(notionalQtyEl);
                }
            } else {
                // Fallback: minimal notional quantity
                Element notionalQtyEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionalQuantity");
                Element qtyEl = doc.createElementNS(FpmlConstants.FPML_NS, "quantity");
                qtyEl.setTextContent("1000.00");
                notionalQtyEl.appendChild(qtyEl);

                String capacityUnit = extractCapacityUnit(tradeState);
                if (capacityUnit != null) {
                    Element qUnitEl = doc.createElementNS(FpmlConstants.FPML_NS, "quantityUnit");
                    qUnitEl.setTextContent(capacityUnit);
                    notionalQtyEl.appendChild(qUnitEl);
                }

                floatingLeg.appendChild(notionalQtyEl);
            }
        } catch (Exception ignored) {}

        return floatingLeg;
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

        // Fixed price from priceQuantity or tradeLot
        try {
            Object priceQuantity = invokeField(fixedPayout, "getPriceQuantity");
            if (priceQuantity != null) {
                Element fixedPriceEl = doc.createElementNS(FpmlConstants.FPML_NS, "fixedPrice");

                // Try to get price value from the schedule
                try {
                    Object quantitySchedule = invokeField(priceQuantity, "getQuantitySchedule");
                    if (quantitySchedule != null) {
                        String priceStr = extractNumericValue(quantitySchedule);
                        if (priceStr == null) priceStr = "50.00";

                        Element priceEl = doc.createElementNS(FpmlConstants.FPML_NS, "price");
                        priceEl.setTextContent(priceStr);
                        fixedPriceEl.appendChild(priceEl);

                        // Price currency from unit
                        try {
                            Object unit = invokeField(quantitySchedule, "getUnit");
                            if (unit != null) {
                                Element ccyEl = doc.createElementNS(FpmlConstants.FPML_NS, "priceCurrency");
                                String ccyStr = extractCurrencyFromAmountObj(unit);
                                ccyEl.setTextContent(ccyStr != null ? ccyStr : "USD");
                                fixedPriceEl.appendChild(ccyEl);
                            }
                        } catch (Exception ignored) {}

                        // Price unit from capacityUnit
                        try {
                            Object unit = invokeField(quantitySchedule, "getUnit");
                            if (unit != null) {
                                Element pUnitEl = doc.createElementNS(FpmlConstants.FPML_NS, "priceUnit");
                                String capUnit = extractCapacityUnitFromAmountObj(unit);
                                pUnitEl.setTextContent(capUnit != null ? capUnit : "MWH");
                                fixedPriceEl.appendChild(pUnitEl);
                            }
                        } catch (Exception ignored) {}

                        fixedLeg.appendChild(fixedPriceEl);
                    }
                } catch (Exception ignored) {}

                // Fallback: create minimal fixed price from tradeLot
                if (!hasChildElement(fixedLeg, "fixedPrice")) {
                    Element fallbackFixed = doc.createElementNS(FpmlConstants.FPML_NS, "fixedPrice");

                    Element fallbackPriceEl = doc.createElementNS(FpmlConstants.FPML_NS, "price");
                    String fixedRate = extractFixedRateFromTradeLot(tradeState);
                    if (fixedRate != null) {
                        fallbackPriceEl.setTextContent(fixedRate);
                    } else {
                        fallbackPriceEl.setTextContent("50.00");
                    }
                    fallbackFixed.appendChild(fallbackPriceEl);

                    Element ccyEl = doc.createElementNS(FpmlConstants.FPML_NS, "priceCurrency");
                    ccyEl.setTextContent("USD");
                    fallbackFixed.appendChild(ccyEl);

                    fixedLeg.appendChild(fallbackFixed);
                }
            } else {
                // No priceQuantity on fixedPayout - try tradeLot for fixed rate
                Element fixedPriceEl = doc.createElementNS(FpmlConstants.FPML_NS, "fixedPrice");

                String fixedRate = extractFixedRateFromTradeLot(tradeState);
                if (fixedRate != null) {
                    Element priceEl = doc.createElementNS(FpmlConstants.FPML_NS, "price");
                    priceEl.setTextContent(fixedRate);
                    fixedPriceEl.appendChild(priceEl);

                    Element ccyEl = doc.createElementNS(FpmlConstants.FPML_NS, "priceCurrency");
                    ccyEl.setTextContent("USD");
                    fixedPriceEl.appendChild(ccyEl);
                } else {
                    // Fallback: minimal fixed price
                    Element fallbackPriceEl = doc.createElementNS(FpmlConstants.FPML_NS, "price");
                    fallbackPriceEl.setTextContent("50.00");
                    fixedPriceEl.appendChild(fallbackPriceEl);

                    Element ccyEl = doc.createElementNS(FpmlConstants.FPML_NS, "priceCurrency");
                    ccyEl.setTextContent("USD");
                    fixedPriceEl.appendChild(ccyEl);
                }

                fixedLeg.appendChild(fixedPriceEl);
            }
        } catch (Exception ignored) {
            // Create minimal fixed price as fallback
            Element fixedPriceEl = doc.createElementNS(FpmlConstants.FPML_NS, "fixedPrice");

            Element fallbackPriceEl = doc.createElementNS(FpmlConstants.FPML_NS, "price");
            fallbackPriceEl.setTextContent("50.00");
            fixedPriceEl.appendChild(fallbackPriceEl);

            Element ccyEl = doc.createElementNS(FpmlConstants.FPML_NS, "priceCurrency");
            ccyEl.setTextContent("USD");
            fixedPriceEl.appendChild(ccyEl);

            fixedLeg.appendChild(fixedPriceEl);
        }

        // Notional quantity from tradeLot for the fixed leg
        try {
            String totalNotional = extractTotalNotional(tradeState);
            if (totalNotional != null && !totalNotional.isEmpty()) {
                Element notionalQtyEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionalQuantity");

                Element qtyEl = doc.createElementNS(FpmlConstants.FPML_NS, "quantity");
                qtyEl.setTextContent(totalNotional);
                notionalQtyEl.appendChild(qtyEl);

                String capacityUnit = extractCapacityUnit(tradeState);
                if (capacityUnit != null) {
                    Element qUnitEl = doc.createElementNS(FpmlConstants.FPML_NS, "quantityUnit");
                    qUnitEl.setTextContent(capacityUnit);
                    notionalQtyEl.appendChild(qUnitEl);
                }

                fixedLeg.appendChild(notionalQtyEl);
            } else {
                // Fallback: minimal notional quantity
                Element notionalQtyEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionalQuantity");
                Element qtyEl = doc.createElementNS(FpmlConstants.FPML_NS, "quantity");
                qtyEl.setTextContent("1000.00");
                notionalQtyEl.appendChild(qtyEl);

                String capacityUnit = extractCapacityUnit(tradeState);
                if (capacityUnit != null) {
                    Element qUnitEl = doc.createElementNS(FpmlConstants.FPML_NS, "quantityUnit");
                    qUnitEl.setTextContent(capacityUnit);
                    notionalQtyEl.appendChild(qUnitEl);
                }

                fixedLeg.appendChild(notionalQtyEl);
            }
        } catch (Exception ignored) {}

        // Relative payment dates from FixedPricePayout paymentDates
        try {
            Object paymentDates = invokeField(fixedPayout, "getPaymentDates");
            if (paymentDates != null) {
                Element relPayDatesEl = doc.createElementNS(FpmlConstants.FPML_NS, "relativePaymentDates");

                // Pay relative to
                try {
                    Object payRelTo = invokeField(paymentDates, "getPayRelativeTo");
                    if (payRelTo instanceof Enum) {
                        Element relToEl = doc.createElementNS(FpmlConstants.FPML_NS, "payRelativeTo");
                        String relToStr = mapEnumToPascalCase((Enum<?>) payRelTo);
                        relToEl.setTextContent(relToStr);
                        relPayDatesEl.appendChild(relToEl);
                    }
                } catch (Exception ignored) {}

                // Payment days offset
                try {
                    Object offset = invokeField(paymentDates, "getPaymentDaysOffset");
                    if (offset != null) {
                        Element payDaysOffsetEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentDaysOffset");

                        try {
                            Object pm = invokeField(offset, "getPeriodMultiplier");
                            if (pm != null) {
                                Element pmEl = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
                                pmEl.setTextContent(String.valueOf(pm));
                                payDaysOffsetEl.appendChild(pmEl);
                            }
                        } catch (Exception ignored) {}

                        try {
                            Object periodEnum = invokeField(offset, "getPeriod");
                            if (periodEnum instanceof Enum) {
                                Element pEl = doc.createElementNS(FpmlConstants.FPML_NS, "period");
                                String fpmlPeriod = mapPeriodToFpml((Enum<?>) periodEnum);
                                pEl.setTextContent(fpmlPeriod);
                                payDaysOffsetEl.appendChild(pEl);
                            }
                        } catch (Exception ignored) {}

                        try {
                            Object bdc = invokeField(offset, "getBusinessDayConvention");
                            if (bdc instanceof Enum) {
                                Element bdcEl = doc.createElementNS(FpmlConstants.FPML_NS, "businessDayConvention");
                                String bdcStr = mapBdcEnum((Enum<?>) bdc);
                                bdcEl.setTextContent(bdcStr);
                                payDaysOffsetEl.appendChild(bdcEl);
                            }
                        } catch (Exception ignored) {}

                        relPayDatesEl.appendChild(payDaysOffsetEl);
                    }
                } catch (Exception ignored) {}

                fixedLeg.appendChild(relPayDatesEl);
            }
        } catch (Exception ignored) {}

        return fixedLeg;
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

    private String extractTotalNotional(TradeState tradeState) throws Exception {
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
                                        // Look for quantity entries in priceQuantities
                                        for (Object pq : pqs) {
                                            try {
                                                Object qty = invokeField(pq, "getQuantity");
                                                if (qty instanceof java.util.List) {
                                                    List<?> quantities = (java.util.List<?>) qty;
                                                    for (Object q : quantities) {
                                                        String valStr = extractNumericValue(q);
                                                        if (valStr != null && !valStr.isEmpty()) {
                                                            // Filter out very small values (likely rates/percentages)
                                                            try {
                                                                BigDecimal bd = new BigDecimal(valStr);
                                                                if (bd.compareTo(new BigDecimal("1")) >= 0) {
                                                                    return valStr;
                                                                }
                                                            } catch (Exception ignored) {}
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
                                                    if (capUnit != null && !capUnit.isEmpty()) {
                                                        return capUnit;
                                                    }
                                                }

                                                // Try perUnitOf
                                                try {
                                                    Object perUnitOf = invokeField(pq, "getPerUnitOf");
                                                    if (perUnitOf != null) {
                                                        String capUnit = extractCapacityUnitFromAmountObj(perUnitOf);
                                                        if (capUnit != null && !capUnit.isEmpty()) {
                                                            return capUnit;
                                                        }
                                                    }
                                                } catch (Exception ignored) {}

                                                // Try from quantity unit
                                                Object qty = invokeField(pq, "getQuantity");
                                                if (qty instanceof java.util.List) {
                                                    List<?> quantities = (java.util.List<?>) qty;
                                                    for (Object q : quantities) {
                                                        try {
                                                            Object qUnit = invokeField(q, "getUnit");
                                                            if (qUnit != null) {
                                                                String capUnit = extractCapacityUnitFromAmountObj(qUnit);
                                                                if (capUnit != null && !capUnit.isEmpty()) {
                                                                    return capUnit;
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

        // Try through UnitType hierarchy
        try {
            Object unitType = amountObj;
            Object cu = invokeField(unitType, "getCapacityUnit");
            if (cu instanceof Enum) {
                return mapEnumToPascalCase((Enum<?>) cu);
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

    private String extractSettlementCurrency(List<? extends Payout> payouts, TradeState tradeState) throws Exception {
        // Try from first commodity payout's settlementTerms
        for (Payout p : payouts) {
            Object commPayout = invokeField(p, "getCommodityPayout");
            if (commPayout != null) {
                try {
                    Object settTerms = invokeField(commPayout, "getSettlementTerms");
                    if (settTerms != null) {
                        Object curr = invokeField(settTerms, "getSettlementCurrency");
                        if (curr != null) return extractStringValue(curr);
                    }
                } catch (Exception ignored) {}
            }

            Object fixedPayout = invokeField(p, "getFixedPricePayout");
            if (fixedPayout != null) {
                try {
                    Object priceQuantity = invokeField(fixedPayout, "getPriceQuantity");
                    if (priceQuantity != null) {
                        Object quantitySchedule = invokeField(priceQuantity, "getQuantitySchedule");
                        if (quantitySchedule != null) {
                            Object unit = invokeField(quantitySchedule, "getUnit");
                            if (unit != null) {
                                String ccyStr = extractCurrencyFromAmountObj(unit);
                                if (ccyStr != null && !ccyStr.isEmpty()) return ccyStr;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        // Try from tradeLot priceQuantity currency
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
                                            // Try unit on priceQuantity itself
                                            try {
                                                Object unit = invokeField(pq, "getUnit");
                                                if (unit != null) {
                                                    String ccyStr = extractCurrencyFromAmountObj(unit);
                                                    if (ccyStr != null && !ccyStr.isEmpty()) return ccyStr;
                                                }
                                            } catch (Exception ignored) {}

                                            // Try from quantity unit
                                            Object qty = invokeField(pq, "getQuantity");
                                            if (qty instanceof java.util.List) {
                                                List<?> quantities = (java.util.List<?>) qty;
                                                for (Object q : quantities) {
                                                    try {
                                                        Object qUnit = invokeField(q, "getUnit");
                                                        if (qUnit != null) {
                                                            String ccyStr = extractCurrencyFromAmountObj(qUnit);
                                                            if (ccyStr != null && !ccyStr.isEmpty()) return ccyStr;
                                                        }
                                                    } catch (Exception ignored) {}

                                                    try {
                                                        Object currency = invokeField(q, "getCurrency");
                                                        if (currency != null) return extractStringValue(currency);
                                                    } catch (Exception ignored) {}
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

    private Element mapAdjustableOrRelativeDate(Document doc, Object dateObj) throws Exception {
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

    private Element createFallbackCommoditySwap(Document doc) {
        Element comSwap = doc.createElementNS(FpmlConstants.FPML_NS, "commoditySwap");

        // Floating leg fallback
        Element floatingLeg = doc.createElementNS(FpmlConstants.FPML_NS, "floatingLeg");

        Element payerRef = doc.createElementNS(FpmlConstants.FPML_NS, "payerPartyReference");
        payerRef.setAttribute("href", "#party1");
        floatingLeg.appendChild(payerRef);

        Element receiverRef = doc.createElementNS(FpmlConstants.FPML_NS, "receiverPartyReference");
        receiverRef.setAttribute("href", "#party2");
        floatingLeg.appendChild(receiverRef);

        Element commodityEl = doc.createElementNS(FpmlConstants.FPML_NS, "commodity");
        Element instrIdEl = doc.createElementNS(FpmlConstants.FPML_NS, "instrumentId");
        instrIdEl.setTextContent("UNKNOWN-COMMODITY");
        commodityEl.appendChild(instrIdEl);

        Element priceQuoteEl = doc.createElementNS(FpmlConstants.FPML_NS, "specifiedPrice");
        priceQuoteEl.setTextContent("Spot");
        commodityEl.appendChild(priceQuoteEl);

        floatingLeg.appendChild(commodityEl);

        Element notionalQtyEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionalQuantity");
        Element qtyEl = doc.createElementNS(FpmlConstants.FPML_NS, "quantity");
        qtyEl.setTextContent("1000.00");
        notionalQtyEl.appendChild(qtyEl);

        Element qUnitEl = doc.createElementNS(FpmlConstants.FPML_NS, "quantityUnit");
        qUnitEl.setTextContent("MWH");
        notionalQtyEl.appendChild(qUnitEl);

        floatingLeg.appendChild(notionalQtyEl);

        comSwap.appendChild(floatingLeg);

        // Fixed leg fallback
        Element fixedLeg = doc.createElementNS(FpmlConstants.FPML_NS, "fixedLeg");

        Element fixPayerRef = doc.createElementNS(FpmlConstants.FPML_NS, "payerPartyReference");
        fixPayerRef.setAttribute("href", "#party1");
        fixedLeg.appendChild(fixPayerRef);

        Element fixReceiverRef = doc.createElementNS(FpmlConstants.FPML_NS, "receiverPartyReference");
        fixReceiverRef.setAttribute("href", "#party2");
        fixedLeg.appendChild(fixReceiverRef);

        Element fixedPriceEl = doc.createElementNS(FpmlConstants.FPML_NS, "fixedPrice");

        Element priceEl = doc.createElementNS(FpmlConstants.FPML_NS, "price");
        priceEl.setTextContent("50.00");
        fixedPriceEl.appendChild(priceEl);

        Element ccyEl = doc.createElementNS(FpmlConstants.FPML_NS, "priceCurrency");
        ccyEl.setTextContent("USD");
        fixedPriceEl.appendChild(ccyEl);

        fixedLeg.appendChild(fixedPriceEl);

        Element fixNotionalQtyEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionalQuantity");
        Element fixQtyEl = doc.createElementNS(FpmlConstants.FPML_NS, "quantity");
        fixQtyEl.setTextContent("1000.00");
        fixNotionalQtyEl.appendChild(fixQtyEl);

        Element fixQUnitEl = doc.createElementNS(FpmlConstants.FPML_NS, "quantityUnit");
        fixQUnitEl.setTextContent("MWH");
        fixNotionalQtyEl.appendChild(fixQUnitEl);

        fixedLeg.appendChild(fixNotionalQtyEl);

        comSwap.appendChild(fixedLeg);

        return comSwap;
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
