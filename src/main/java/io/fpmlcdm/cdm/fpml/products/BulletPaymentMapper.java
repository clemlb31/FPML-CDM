package io.fpmlcdm.cdm.fpml.products;

import cdm.base.staticdata.party.Counterparty;
import cdm.event.common.TradeState;
import cdm.product.template.Payout;
import io.fpmlcdm.cdm.fpml.CdmToFpmlMappingContext;
import io.fpmlcdm.cdm.fpml.CdmToFpmlProductMapper;
import io.fpmlcdm.cdm.fpml.FpmlConstants;
import io.fpmlcdm.cdm.fpml.common.CdmDateMapper;
import io.fpmlcdm.cdm.fpml.common.CdmPartyMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.util.List;

/**
 * CDM BulletPayment/termDeposit/future to FpML mapper.
 * Maps single-payment and deposit products to <bulletPayment> FpML elements.
 */
public class BulletPaymentMapper implements CdmToFpmlProductMapper {

    @Override
    public Element map(TradeState tradeState, CdmToFpmlMappingContext context) {
        try {
            return doMap(tradeState, context);
        } catch (Exception e) {
            context.addWarning("BulletPayment mapping error: " + e.getMessage());
            Document doc = context.getDocument();
            return doc.createElementNS(FpmlConstants.FPML_NS, "bulletPayment");
        }
    }

    private Element doMap(TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Document doc = context.getDocument();
        registerParties(tradeState, context);

        Element bulletPayment = doc.createElementNS(FpmlConstants.FPML_NS, "bulletPayment");

        // Map payment date
        mapPaymentDate(doc, bulletPayment, tradeState, context);

        // Map payment amount
        mapPaymentAmount(doc, bulletPayment, tradeState, context);

        // Map deposit terms if applicable
        Object product = invokeField(tradeState, "getProduct");
        if (product != null) {
            Object econTerms = invokeField(product, "getEconomicTerms");
            if (econTerms != null) {
                Object termDeposit = invokeField(econTerms, "getTermDeposit");
                if (termDeposit != null) {
                    Element termDepositEl = mapTermDeposit(doc, termDeposit, context);
                    if (termDepositEl != null) {
                        bulletPayment.appendChild(termDepositEl);
                    }
                }
            }
        }

        return bulletPayment;
    }

    private void registerParties(TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
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

    private void mapPaymentDate(Document doc, Element parent, TradeState tradeState, CdmToFpmlMappingContext context) {
        try {
            List<? extends Payout> payouts = getPayouts(tradeState);
            if (payouts != null && !payouts.isEmpty()) {
                for (Payout p : payouts) {
                    Object settlementDate = invokeField(p, "getSettlementDate");
                    if (settlementDate != null) {
                        Element paymentDate = doc.createElementNS(FpmlConstants.FPML_NS, "paymentDate");
                        Object adjDate = unwrapAdjustableDate(settlementDate);
                        if (adjDate instanceof cdm.base.datetime.AdjustableDate) {
                            paymentDate.appendChild(CdmDateMapper.mapAdjustableDate(doc, (cdm.base.datetime.AdjustableDate) adjDate));
                        } else {
                            String dateStr = extractDateString(settlementDate);
                            if (dateStr != null) {
                                Element unadj = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                                unadj.setTextContent(dateStr);
                                paymentDate.appendChild(unadj);
                            }
                        }
                        parent.appendChild(paymentDate);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            context.addWarning("Could not map payment date: " + e.getMessage());
        }

        // Fallback: use tradeDate
        try {
            Object trade = invokeField(tradeState, "getTrade");
            if (trade != null) {
                Object tradeDate = invokeField(trade, "getTradeDate");
                if (tradeDate != null) {
                    Element paymentDate = doc.createElementNS(FpmlConstants.FPML_NS, "paymentDate");
                    Element unadj = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                    unadj.setTextContent(extractDateString(tradeDate));
                    paymentDate.appendChild(unadj);
                    parent.appendChild(paymentDate);
                }
            }
        } catch (Exception e) {
            context.addWarning("Could not map tradeDate as fallback: " + e.getMessage());
        }
    }

    private void mapPaymentAmount(Document doc, Element parent, TradeState tradeState, CdmToFpmlMappingContext context) {
        try {
            List<? extends Payout> payouts = getPayouts(tradeState);
            if (payouts != null && !payouts.isEmpty()) {
                for (Payout p : payouts) {
                    // Try CashflowPayout first
                    Object cashflowPayout = invokeField(p, "getCashflowPayout");
                    if (cashflowPayout != null) {
                        Element amountEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentCurrencyAmount");
                        mapAmountFromPayout(doc, amountEl, cashflowPayout, context);
                        parent.appendChild(amountEl);
                        return;
                    }

                    // Try InterestRatePayout with notional
                    Object irPayout = invokeField(p, "getInterestRatePayout");
                    if (irPayout != null) {
                        Element amountEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentCurrencyAmount");
                        mapNotionalToAmount(doc, amountEl, irPayout, context);
                        parent.appendChild(amountEl);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            context.addWarning("Could not map payment amount: " + e.getMessage());
        }

        // Fallback placeholder
        Element amountEl = doc.createElementNS(FpmlConstants.FPML_NS, "paymentCurrencyAmount");
        Element curr = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
        curr.setTextContent("USD");
        amountEl.appendChild(curr);
        Element amt = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
        Element val = doc.createElementNS(FpmlConstants.FPML_NS, "value");
        val.setTextContent("0");
        amt.appendChild(val);
        amountEl.appendChild(amt);
        parent.appendChild(amountEl);
    }

    private Element mapTermDeposit(Document doc, Object termDeposit, CdmToFpmlMappingContext context) {
        try {
            Element termDepositEl = doc.createElementNS(FpmlConstants.FPML_NS, "termDeposit");

            // Deposit period
            Object depositPeriod = invokeField(termDeposit, "getDepositPeriod");
            if (depositPeriod != null) {
                Element period = doc.createElementNS(FpmlConstants.FPML_NS, "depositPeriod");
                Element multiplier = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
                Object pm = invokeField(depositPeriod, "getPeriodMultiplier");
                if (pm != null) multiplier.setTextContent(String.valueOf(pm));
                period.appendChild(multiplier);

                Element periodUnit = doc.createElementNS(FpmlConstants.FPML_NS, "period");
                Object pu = invokeField(depositPeriod, "getPeriod");
                if (pu instanceof Enum) periodUnit.setTextContent(((Enum<?>) pu).name());
                period.appendChild(periodUnit);

                termDepositEl.appendChild(period);
            }

            // Fixed rate
            Object fixedRate = invokeField(termDeposit, "getFixedRate");
            if (fixedRate != null) {
                Element rate = doc.createElementNS(FpmlConstants.FPML_NS, "fixedRate");
                BigDecimal val = extractNumericValue(fixedRate);
                if (val != null) {
                    Element calc = doc.createElementNS(FpmlConstants.FPML_NS, "calculation");
                    Element amount = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                    Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                    valEl.setTextContent(formatBigDecimal(val));
                    amount.appendChild(valEl);
                    calc.appendChild(amount);
                    rate.appendChild(calc);
                    termDepositEl.appendChild(rate);
                }
            }

            return termDepositEl;
        } catch (Exception e) {
            context.addWarning("Could not map termDeposit: " + e.getMessage());
            return null;
        }
    }

    private void mapAmountFromPayout(Document doc, Element parent, Object payout, CdmToFpmlMappingContext context) {
        try {
            Object amount = invokeField(payout, "getAmount");
            if (amount != null) {
                Element amountEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                BigDecimal val = extractNumericValue(amount);
                if (val != null) {
                    Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                    valEl.setTextContent(formatBigDecimal(val));
                    amountEl.appendChild(valEl);
                }
                parent.appendChild(amountEl);
            }

            Object currency = invokeField(payout, "getCurrency");
            if (currency != null) {
                Element curr = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                curr.setTextContent(String.valueOf(currency));
                parent.appendChild(curr);
            }
        } catch (Exception e) {
            context.addWarning("Could not map amount from payout: " + e.getMessage());
        }
    }

    private void mapNotionalToAmount(Document doc, Element parent, Object irPayout, CdmToFpmlMappingContext context) {
        try {
            // Check for priceQuantity (CDM stores notional here)
            Object pq = invokeField(irPayout, "getPriceQuantity");
            if (pq != null) {
                BigDecimal val = extractNumericValue(pq);
                String curr = extractCurrency(pq);
                if (val != null && curr != null) {
                    Element amountEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                    Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                    valEl.setTextContent(formatBigDecimal(val));
                    amountEl.appendChild(valEl);
                    Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                    currEl.setTextContent(curr);
                    parent.appendChild(amountEl);
                    parent.appendChild(currEl);
                }
                return;
            }

            // Check for notional directly
            Object notional = invokeField(irPayout, "getNotional");
            if (notional != null) {
                BigDecimal val = extractNumericValue(notional);
                String curr = extractCurrency(notional);
                if (val != null && curr != null) {
                    Element amountEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                    Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                    valEl.setTextContent(formatBigDecimal(val));
                    amountEl.appendChild(valEl);
                    Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                    currEl.setTextContent(curr);
                    parent.appendChild(amountEl);
                    parent.appendChild(currEl);
                }
            }
        } catch (Exception e) {
            context.addWarning("Could not map notional to amount: " + e.getMessage());
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

    private Object unwrapAdjustableDate(Object obj) throws Exception {
        if (obj == null) return null;
        try {
            java.lang.reflect.Method m = obj.getClass().getMethod("getAdjustableDate");
            Object result = m.invoke(obj);
            if (result instanceof cdm.base.datetime.AdjustableDate) return result;
        } catch (NoSuchMethodException ignored) {}
        return obj;
    }

    private BigDecimal extractNumericValue(Object obj) throws Exception {
        if (obj == null) return null;
        try {
            Object val = invokeField(obj, "getValue");
            if (val instanceof BigDecimal) return (BigDecimal) val;
            if (val != null) return new BigDecimal(val.toString());
        } catch (Exception e) {}
        try {
            Object amt = invokeField(obj, "getAmount");
            if (amt instanceof BigDecimal) return (BigDecimal) amt;
            if (amt != null) return new BigDecimal(amt.toString());
        } catch (Exception e) {}
        return null;
    }

    private String extractCurrency(Object obj) throws Exception {
        if (obj == null) return null;
        try {
            Object curr = invokeField(obj, "getCurrency");
            if (curr != null) return String.valueOf(curr);
        } catch (Exception e) {}
        return null;
    }

    private String extractDateString(Object obj) throws Exception {
        if (obj == null) return null;
        try {
            Object adj = unwrapAdjustableDate(obj);
            if (adj instanceof cdm.base.datetime.AdjustableDate) {
                cdm.base.datetime.AdjustableDate adjDate = (cdm.base.datetime.AdjustableDate) adj;
                try {
                    java.lang.reflect.Method m = adjDate.getClass().getMethod("getUnadjustedDate");
                    Object unadj = m.invoke(adjDate);
                    if (unadj != null) {
                        try {
                            java.lang.reflect.Method getYear = unadj.getClass().getMethod("getYear");
                            java.lang.reflect.Method getMonth = unadj.getClass().getMethod("getMonth");
                            java.lang.reflect.Method getDay = unadj.getClass().getMethod("getDay");
                            int year = (Integer) getYear.invoke(unadj);
                            int month = (Integer) getMonth.invoke(unadj);
                            int day = (Integer) getDay.invoke(unadj);
                            return String.format("%04d-%02d-%02d", year, month, day);
                        } catch (Exception ignored) {}
                    }
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Exception e) {
            // Fallback to toString parsing
        }
        String str = obj.toString();
        if (str.contains("year=") && str.contains("month=") && str.contains("day=")) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("year=(\\d+), month=(\\d+), day=(\\d+)");
            java.util.regex.Matcher m = p.matcher(str);
            if (m.find()) {
                return String.format("%04d-%02d-%02d", Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            }
        }
        return null;
    }

    private String extractStringValue(Object obj) throws Exception {
        if (obj == null) return null;
        if (obj instanceof String) return (String) obj;
        try {
            java.lang.reflect.Method m = obj.getClass().getMethod("get");
            Object val = m.invoke(obj);
            if (val != null && !val.toString().contains("{")) return String.valueOf(val);
        } catch (NoSuchMethodException ignored) {}
        try {
            java.lang.reflect.Method m = obj.getClass().getMethod("getValue");
            Object val = m.invoke(obj);
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
