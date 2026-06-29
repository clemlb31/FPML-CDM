package io.fpmlcdm.cdm.fpml.common;

import cdm.event.common.TradeState;
import io.fpmlcdm.cdm.fpml.CdmToFpmlMappingContext;
import io.fpmlcdm.cdm.fpml.FpmlConstants;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.util.List;

/**
 * Utility for mapping CDM Amount/Quantity constructs to FpML XML.
 */
public class CdmAmountMapper {

    /**
     * Maps a CDM Amount/Quantity to an FpML amount/quantity structure.
     */
    public static void mapAmount(Document doc, Element parent, BigDecimal amount, String currency) {
        if (amount == null || currency == null) return;

        Element amountElement = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
        amountElement.setAttribute("id", "amt_" + Math.abs(amount.hashCode()));
        
        Element valueElement = doc.createElementNS(FpmlConstants.FPML_NS, "value");
        valueElement.setTextContent(formatBigDecimal(amount));
        amountElement.appendChild(valueElement);

        Element currencyElement = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
        currencyElement.setTextContent(currency);
        amountElement.appendChild(currencyElement);

        parent.appendChild(amountElement);
    }

    /**
     * Maps a CDM Notional (Amount or Quantity object) to FpML <notional>.
     */
    public static void mapNotional(Document doc, Element parent, Object notionalObj, CdmToFpmlMappingContext context) {
        if (notionalObj == null) return;

        try {
            BigDecimal value = extractValue(notionalObj);
            String currency = extractCurrency(notionalObj);

            Element notionalElement = doc.createElementNS(FpmlConstants.FPML_NS, "notional");
            notionalElement.setAttribute("id", context.createFpmlId("notional"));
            
            if (value != null && currency != null) {
                mapAmount(doc, notionalElement, value, currency);
                
                // Check for schedule (variable notional)
                Object schedule = invokeField(notionalObj, "getSchedule");
                if (schedule instanceof List) {
                    Element schedElem = doc.createElementNS(FpmlConstants.FPML_NS, "notionalSchedule");
                    mapNotionalSchedule(doc, schedElem, (List<?>) schedule, context);
                    notionalElement.appendChild(schedElem);
                } else {
                    // Fixed notional — add <notionalAmount> wrapper per FpML convention
                    Element amtWrapper = doc.createElementNS(FpmlConstants.FPML_NS, "notionalAmount");
                    amtWrapper.appendChild(notionalElement.getFirstChild());
                    notionalElement.appendChild(amtWrapper);
                }
            } else {
                context.addWarning("Notional missing value or currency: " + notionalObj.getClass().getSimpleName());
            }

            parent.appendChild(notionalElement);
        } catch (Exception e) {
            context.addWarning("Could not map Notional: " + e.getMessage());
            Element fallback = doc.createElementNS(FpmlConstants.FPML_NS, "notional");
            fallback.setAttribute("id", context.createFpmlId("notional"));
            parent.appendChild(fallback);
        }
    }

    /**
     * Maps a list of notional schedule items (for variable notional swaps).
     */
    private static void mapNotionalSchedule(Document doc, Element parent, List<?> scheduleItems, CdmToFpmlMappingContext context) {
        if (scheduleItems.isEmpty()) return;

        for (Object item : scheduleItems) {
            try {
                Element period = doc.createElementNS(FpmlConstants.FPML_NS, "notionalPeriod");
                
                // Map start/end dates of the notional period
                Object startDate = invokeField(item, "getStartDate");
                if (startDate instanceof cdm.base.datetime.AdjustableDate) {
                    Element effDate = doc.createElementNS(FpmlConstants.FPML_NS, "notionPeriodStart");
                    effDate.appendChild(CdmDateMapper.mapAdjustableDate(doc, (cdm.base.datetime.AdjustableDate) startDate));
                    period.appendChild(effDate);
                }

                Object endDate = invokeField(item, "getEndDate");
                if (endDate instanceof cdm.base.datetime.AdjustableDate) {
                    Element termDate = doc.createElementNS(FpmlConstants.FPML_NS, "notionPeriodEnd");
                    termDate.appendChild(CdmDateMapper.mapAdjustableDate(doc, (cdm.base.datetime.AdjustableDate) endDate));
                    period.appendChild(termDate);
                }

                // Map the notional amount for this period
                Object amt = invokeField(item, "getAmount");
                if (amt != null) {
                    Element valElem = doc.createElementNS(FpmlConstants.FPML_NS, "notionPeriodAmount");
                    BigDecimal val = extractValue(amt);
                    String curr = extractCurrency(amt);
                    if (val != null && curr != null) {
                        mapAmount(doc, valElem, val, curr);
                    }
                    period.appendChild(valElem);
                }

                parent.appendChild(period);
            } catch (Exception e) {
                context.addWarning("Could not map notional schedule item: " + e.getMessage());
            }
        }
    }

    /**
     * Extracts the numeric value from a CDM Amount/Quantity object.
     */
    private static BigDecimal extractValue(Object obj) throws Exception {
        try {
            Object val = invokeField(obj, "getValue");
            if (val instanceof BigDecimal) return (BigDecimal) val;
            if (val != null) return new BigDecimal(val.toString());
        } catch (Exception e) {
            // Try alternate method names
        }
        
        try {
            Object amount = invokeField(obj, "getAmount");
            if (amount instanceof BigDecimal) return (BigDecimal) amount;
            if (amount != null) return new BigDecimal(amount.toString());
        } catch (Exception e) {
            // Try numeric value directly on obj
        }

        try {
            Object numVal = invokeField(obj, "getNumericValue");
            if (numVal instanceof Number) return new BigDecimal(numVal.toString());
        } catch (Exception ignored) {}

        return null;
    }

    /**
     * Extracts the currency code from a CDM Amount/Quantity object.
     */
    private static String extractCurrency(Object obj) throws Exception {
        try {
            Object curr = invokeField(obj, "getCurrency");
            if (curr != null) return String.valueOf(curr);
        } catch (Exception e) {}

        try {
            Object currency = invokeField(obj, "getCurrencyCode");
            if (currency != null) return String.valueOf(currency);
        } catch (Exception ignored) {}

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

    private static String formatBigDecimal(BigDecimal value) {
        if (value == null) return "0";
        // Remove trailing zeros for cleaner output, but keep at least 2 decimal places
        String s = value.stripTrailingZeros().toPlainString();
        if (!s.contains(".") && !s.contains("E")) {
            s += ".00";
        } else if (s.contains(".")) {
            int decimals = s.split("\\.")[1].length();
            if (decimals == 1) s += "0";
        }
        return s;
    }

    /**
     * Extracts notional from a TradeState by finding the first Amount/Quantity field.
     */
    public static void mapTradeNotional(Document doc, Element parent, TradeState tradeState, CdmToFpmlMappingContext context) {
        if (tradeState == null) return;

        try {
            Object product = invokeField(tradeState, "getProduct");
            if (product != null) {
                // Try to find economicTerms -> payout -> notional
                Object econTerms = invokeField(product, "getEconomicTerms");
                if (econTerms != null) {
                    Object payout = invokeField(econTerms, "getPayout");
                    if (payout != null) {
                        // Check for InterestRatePayout with priceQuantity (notional in CDM is often on the payout)
                        Class<?> payoutClass = payout.getClass();
                        
                        // Try getInterestRatePayouts first
                        Object irPayouts = invokeField(payout, "getInterestRatePayout");
                        if (irPayouts != null) {
                            mapNotionalFromPayout(doc, parent, irPayouts, context);
                            return;
                        }

                        // Try getNotional directly on payout
                        Object notional = invokeField(payout, "getNotional");
                        if (notional != null) {
                            mapNotional(doc, parent, notional, context);
                            return;
                        }
                    }
                }

                // Fallback: try to find priceQuantity on product directly
                Object tradeLot = invokeField(product, "getTradeLot");
                if (tradeLot != null) {
                    Object qty = invokeField(tradeLot, "getQuantity");
                    if (qty != null) {
                        mapNotional(doc, parent, qty, context);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            context.addWarning("Could not extract trade notional: " + e.getMessage());
        }

        // Final fallback — create a placeholder so FpML structure is complete
        Element placeholder = doc.createElementNS(FpmlConstants.FPML_NS, "notional");
        placeholder.setAttribute("id", context.createFpmlId("notional"));
        parent.appendChild(placeholder);
    }

    private static void mapNotionalFromPayout(Document doc, Element parent, Object payout, CdmToFpmlMappingContext context) throws Exception {
        // Try priceQuantity (CDM stores notional here for swaps)
        Object pq = invokeField(payout, "getPriceQuantity");
        if (pq != null) {
            mapNotional(doc, parent, pq, context);
            return;
        }

        // Try quantitySchedule
        Object qs = invokeField(payout, "getQuantitySchedule");
        if (qs != null) {
            mapNotional(doc, parent, qs, context);
            return;
        }

        // Try amount directly on payout
        Object amt = invokeField(payout, "getAmount");
        if (amt != null) {
            mapNotional(doc, parent, amt, context);
            return;
        }
    }
}
