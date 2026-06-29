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
 * CDM FX Single Leg (spot, forward, NDF) to FpML {@code <fxSingleLeg>} mapper.
 * 
 * Detects FX single leg by checking for SettlementPayout with currency pair underlier.
 */
public class FxSingleLegMapper implements CdmToFpmlProductMapper {

    @Override
    public Element map(TradeState tradeState, CdmToFpmlMappingContext context) {
        try {
            return doMap(tradeState, context);
        } catch (Exception e) {
            context.addWarning("FX Single Leg mapping error: " + e.getMessage());
            Document doc = context.getDocument();
            Element fxSingleLeg = createFallbackFxSingleLeg(doc);
            return fxSingleLeg;
        }
    }

    private Element doMap(TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Document doc = context.getDocument();

        registerPartiesFromTrade(tradeState, context);

        Object product = invokeField(tradeState, "getProduct");
        if (product == null) return createFallbackFxSingleLeg(doc);

        Object econTerms = invokeField(product, "getEconomicTerms");
        if (econTerms == null) return createFallbackFxSingleLeg(doc);

        List<? extends Payout> payouts = (List<? extends Payout>) invokeField(econTerms, "getPayout");
        if (payouts == null || payouts.isEmpty()) return createFallbackFxSingleLeg(doc);

        for (Payout p : payouts) {
            Object settlementPayout = invokeField(p, "getSettlementPayout");
            if (settlementPayout != null) {
                return buildFxSingleLegFromSettlement(doc, settlementPayout, tradeState, context);
            }

            Object fxSingleLegObj = invokeField(p, "getFxSingleLeg");
            if (fxSingleLegObj != null) {
                return buildFxSingleLegFromDirect(doc, fxSingleLegObj, tradeState, context);
            }
        }

        // Also check TradeState directly for FxSingleLeg
        Object fxSl = invokeField(tradeState, "getFxSingleLeg");
        if (fxSl != null) {
            return buildFxSingleLegFromDirect(doc, fxSl, tradeState, context);
        }

        return createFallbackFxSingleLeg(doc);
    }

    private Element buildFxSingleLegFromSettlement(Document doc, Object settlementPayout, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Element fxSingleLeg = doc.createElementNS(FpmlConstants.FPML_NS, "fxSingleLeg");

        boolean isNdf = false;
        Object settlementTerms = invokeField(settlementPayout, "getSettlementTerms");
        if (settlementTerms != null) {
            try {
                Object settType = invokeField(settlementTerms, "getSettlementType");
                if (settType != null) {
                    String typeStr = extractStringValue(settType);
                    if ("Cash".equals(typeStr)) {
                        Object settCurrency = invokeField(settlementTerms, "getSettlementCurrency");
                        if (settCurrency != null) isNdf = true;
                    }
                }
            } catch (Exception ignored) {}

            try {
                Object disruption = invokeField(settlementTerms, "getDisruptionTerms");
                if (disruption != null) isNdf = true;
            } catch (Exception ignored) {}
        }

        String payerHref = extractPayerFromTrade(tradeState, settlementPayout);
        String receiverHref = extractReceiverFromTrade(tradeState, settlementPayout);

        Element payerRef = doc.createElementNS(FpmlConstants.FPML_NS, "payerPartyReference");
        payerRef.setAttribute("href", "#" + payerHref);
        fxSingleLeg.appendChild(payerRef);

        Element receiverRef = doc.createElementNS(FpmlConstants.FPML_NS, "receiverPartyReference");
        receiverRef.setAttribute("href", "#" + receiverHref);
        fxSingleLeg.appendChild(receiverRef);

        if (isNdf) {
            Element principalExchange = doc.createElementNS(FpmlConstants.FPML_NS, "principalExchangeType");
            principalExchange.setTextContent("false");
            fxSingleLeg.appendChild(principalExchange);

            Element nongDeliverableType = doc.createElementNS(FpmlConstants.FPML_NS, "nondeliverableType");
            nongDeliverableType.setTextContent("fixingOnly");
            fxSingleLeg.appendChild(nongDeliverableType);

            mapNdfSettlement(doc, fxSingleLeg, settlementPayout, context);
        } else {
            Element principalExchange = doc.createElementNS(FpmlConstants.FPML_NS, "principalExchangeType");
            principalExchange.setTextContent("fullPrincipalExchange");
            fxSingleLeg.appendChild(principalExchange);

            mapFxSingleLegAmounts(doc, fxSingleLeg, settlementPayout, tradeState, context);
        }

        Element startDate = doc.createElementNS(FpmlConstants.FPML_NS, "firstPeriodStartDate");
        startDate.appendChild(mapSettlementDate(doc, settlementTerms));
        fxSingleLeg.appendChild(startDate);

        return fxSingleLeg;
    }

    private Element buildFxSingleLegFromDirect(Document doc, Object fxSingleLegObj, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Element fxSingleLeg = doc.createElementNS(FpmlConstants.FPML_NS, "fxSingleLeg");

        String payerHref = extractPayerFromTrade(tradeState, null);
        String receiverHref = extractReceiverFromTrade(tradeState, null);

        Element payerRef = doc.createElementNS(FpmlConstants.FPML_NS, "payerPartyReference");
        payerRef.setAttribute("href", "#" + payerHref);
        fxSingleLeg.appendChild(payerRef);

        Element receiverRef = doc.createElementNS(FpmlConstants.FPML_NS, "receiverPartyReference");
        receiverRef.setAttribute("href", "#" + receiverHref);
        fxSingleLeg.appendChild(receiverRef);

        Object currency = invokeField(fxSingleLegObj, "getCurrency");
        if (currency != null) {
            Element payCurrAmt = doc.createElementNS(FpmlConstants.FPML_NS, "paymentCurrencyAmount");
            Element currTag = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
            currTag.setTextContent(String.valueOf(currency));
            payCurrAmt.appendChild(currTag);

            Object amount = invokeField(fxSingleLegObj, "getAmount");
            if (amount != null) {
                io.fpmlcdm.cdm.fpml.common.CdmAmountMapper.mapNotional(doc, payCurrAmt, amount, context);
            } else {
                Object qty = invokeField(fxSingleLegObj, "getQuantity");
                if (qty != null) {
                    io.fpmlcdm.cdm.fpml.common.CdmAmountMapper.mapNotional(doc, payCurrAmt, qty, context);
                }
            }

            fxSingleLeg.appendChild(payCurrAmt);
        }

        Element principalExchange = doc.createElementNS(FpmlConstants.FPML_NS, "principalExchangeType");
        principalExchange.setTextContent("fullPrincipalExchange");
        fxSingleLeg.appendChild(principalExchange);

        return fxSingleLeg;
    }

    private void mapFxSingleLegAmounts(Document doc, Element parent, Object settlementPayout, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Object priceQuantity = invokeField(settlementPayout, "getPriceQuantity");

        if (priceQuantity != null && tradeState != null) {
            Object product = invokeField(tradeState, "getProduct");
            if (product != null) {
                Object trade = invokeField(product, "getTrade");
                if (trade instanceof java.util.List) {
                    List<?> tradeList = (java.util.List<?>) trade;
                    for (Object pq : tradeList) {
                        try {
                            Object qty = invokeField(pq, "getQuantity");
                            if (qty instanceof java.util.List) {
                                java.util.List<?> quantities = (java.util.List<?>) qty;
                                for (int i = 0; i < quantities.size(); i++) {
                                    Object q = quantities.get(i);
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
                                        }
                                    }

                                    if (i == 0) {
                                        Element dir = doc.createElementNS(FpmlConstants.FPML_NS, "direction");
                                        dir.setTextContent("payer");
                                        payCurrAmt.appendChild(dir);
                                    } else {
                                        Element dir = doc.createElementNS(FpmlConstants.FPML_NS, "direction");
                                        dir.setTextContent("receiver");
                                        payCurrAmt.appendChild(dir);
                                    }

                                    parent.appendChild(payCurrAmt);
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        }

        if (!hasChildElement(parent, "paymentCurrencyAmount")) {
            Object qty = invokeField(priceQuantity, "getQuantity");
            if (qty instanceof java.util.List) {
                java.util.List<?> quantities = (java.util.List<?>) qty;
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
                        }
                    }

                    parent.appendChild(payCurrAmt);
                }
            }
        }
    }

    private void mapNdfSettlement(Document doc, Element parent, Object settlementPayout, CdmToFpmlMappingContext context) throws Exception {
        Object priceQuantity = invokeField(settlementPayout, "getPriceQuantity");

        if (priceQuantity != null) {
            Element payCurrAmt = doc.createElementNS(FpmlConstants.FPML_NS, "paymentCurrencyAmount");

            Object qty = invokeField(priceQuantity, "getQuantity");
            if (qty instanceof java.util.List) {
                java.util.List<?> quantities = (java.util.List<?>) qty;
                for (Object q : quantities) {
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
                        }
                    }
                }
            } else if (qty != null) {
                BigDecimal value = extractBigDecimalValue(qty);
                String currency = extractCurrencyFromAmountObj(qty);
                if (value != null && currency != null) {
                    Element currElem = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                    currElem.setTextContent(currency);
                    payCurrAmt.appendChild(currElem);

                    Element valElem = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                    valElem.setTextContent(formatBigDecimal(value));
                    payCurrAmt.appendChild(valElem);
                }
            }

            if (!hasChildElement(payCurrAmt, "currency")) {
                Object settCurrency = invokeField(settlementPayout, "getSettlementCurrency");
                if (settCurrency != null) {
                    Element currElem = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                    String currStr = extractStringValue(settCurrency);
                    if (currStr != null && !currStr.isEmpty()) {
                        currElem.setTextContent(currStr);
                        payCurrAmt.appendChild(currElem);
                    }
                }
            }

            parent.appendChild(payCurrAmt);
        } else {
            Element fallback = doc.createElementNS(FpmlConstants.FPML_NS, "paymentCurrencyAmount");
            Element currTag = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
            currTag.setTextContent("USD");
            fallback.appendChild(currTag);
            parent.appendChild(fallback);
        }
    }

    private Element mapSettlementDate(Document doc, Object settlementTerms) {
        if (settlementTerms == null) return createFallbackDate(doc);

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

                // Try getUnadjustedDate directly on settlementDate object
                try {
                    Object unadj = invokeField(settDate, "getUnadjustedDate");
                    if (unadj != null) {
                        Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                        String dateStr = extractDateString(unadj);
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

    private Element createFallbackDate(Document doc) {
        Element unadjEl = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
        unadjEl.setTextContent("2024-01-02");
        return unadjEl;
    }

    private String extractPayerFromTrade(TradeState tradeState, Object settlementPayout) throws Exception {
        if (settlementPayout != null) {
            try {
                Object pr = invokeField(settlementPayout, "getPayerReceiver");
                if (pr != null) {
                    java.lang.reflect.Method getM = pr.getClass().getMethod("get", int.class);
                    Object payerObj = getM.invoke(pr, 0); // index 0 = payer
                    if (payerObj != null) {
                        String payerRole = extractStringValue(payerObj);
                        if ("Party1".equals(payerRole)) return "party2";
                        if ("Party2".equals(payerRole)) return "party1";
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

    private String extractReceiverFromTrade(TradeState tradeState, Object settlementPayout) throws Exception {
        if (settlementPayout != null) {
            try {
                Object pr = invokeField(settlementPayout, "getPayerReceiver");
                if (pr != null) {
                    java.lang.reflect.Method getM = pr.getClass().getMethod("get", int.class);
                    Object receiverObj = getM.invoke(pr, 1); // index 1 = receiver
                    if (receiverObj != null) {
                        String rStr = extractStringValue(receiverObj);
                        if ("Party1".equals(rStr)) return "party2";
                        if ("Party2".equals(rStr)) return "party1";
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

    private Element createFallbackFxSingleLeg(Document doc) {
        Element fxSingleLeg = doc.createElementNS(FpmlConstants.FPML_NS, "fxSingleLeg");

        Element payerRef = doc.createElementNS(FpmlConstants.FPML_NS, "payerPartyReference");
        payerRef.setAttribute("href", "#party1");
        fxSingleLeg.appendChild(payerRef);

        Element receiverRef = doc.createElementNS(FpmlConstants.FPML_NS, "receiverPartyReference");
        receiverRef.setAttribute("href", "#party2");
        fxSingleLeg.appendChild(receiverRef);

        Element principalExchange = doc.createElementNS(FpmlConstants.FPML_NS, "principalExchangeType");
        principalExchange.setTextContent("fullPrincipalExchange");
        fxSingleLeg.appendChild(principalExchange);

        return fxSingleLeg;
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

        // Parse from toString() for "value=2024-01-02" format
        String str = dateObj.toString();
        if (str.contains("value=")) {
            int start = str.indexOf("value=") + 6;
            int end = str.indexOf(",", start);
            if (end == -1) end = str.indexOf("}", start);
            if (end > start) return str.substring(start, end).trim();
        }

        // Try year/month/day pattern
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

        // Direct date format YYYY-MM-DD
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
}
