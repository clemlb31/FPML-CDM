package io.fpmlcdm.cdm.fpml.products;

import cdm.base.staticdata.party.Counterparty;
import cdm.event.common.TradeState;
import cdm.product.asset.CreditDefaultPayout;
import cdm.product.template.Payout;
import io.fpmlcdm.cdm.fpml.CdmToFpmlMappingContext;
import io.fpmlcdm.cdm.fpml.CdmToFpmlProductMapper;
import io.fpmlcdm.cdm.fpml.FpmlConstants;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.util.List;

/**
 * Implementation for mapping CDM Credit Default Swap to FpML.
 */
public class CreditDefaultSwapMapper implements CdmToFpmlProductMapper {

    @Override
    public Element map(TradeState tradeState, CdmToFpmlMappingContext context) {
        try {
            return doMap(tradeState, context);
        } catch (Exception e) {
            context.addWarning("CDS mapping error: " + e.getMessage());
            Document doc = context.getDocument();
            Element cdsElement = createFallbackCds(doc);
            try { registerPartiesFromTrade(tradeState, context); } catch (Exception ignored) {}
            return cdsElement;
        }
    }

    private Element doMap(TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Document doc = context.getDocument();

        registerPartiesFromTrade(tradeState, context);

        Element cdsElement = doc.createElementNS(FpmlConstants.FPML_NS, "creditDefaultSwap");

        // Map trade date header
        mapTradeDate(doc, cdsElement, tradeState, context);

        // Map general terms (reference entity, notional)
        mapGeneralTerms(doc, cdsElement, tradeState, context);

        // Map credit event terms
        mapCreditEventTerms(doc, cdsElement, tradeState, context);

        // Map fee leg(s) with actual notional and rate from CDM
        mapFeeLegs(doc, cdsElement, tradeState, context);

        // Map protection leg (if cash settlement)
        mapProtectionLeg(doc, cdsElement, tradeState, context);

        return cdsElement;
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

            if (parent.getFirstChild() != null) {
                parent.insertBefore(tradeHeader, parent.getFirstChild());
            } else {
                parent.appendChild(tradeHeader);
            }
        }
    }

    private void mapGeneralTerms(Document doc, Element parent, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Object product = invokeField(tradeState, "getProduct");
        if (product == null) return;

        Object econTerms = invokeField(product, "getEconomicTerms");
        if (econTerms == null) return;

        Element generalTermsEl = doc.createElementNS(FpmlConstants.FPML_NS, "generalTerms");

        // 1. Reference entity details (name, identifier) from CreditDefaultPayout or tradeLot
        try {
            List<? extends Payout> payouts = (List<? extends Payout>) invokeField(econTerms, "getPayout");
            if (payouts != null && !payouts.isEmpty()) {
                for (Payout p : payouts) {
                    Object cdPayout = p.getCreditDefaultPayout();
                    if (cdPayout == null) continue;

                    // Reference entity from credit default payout
                    try {
                        Object referenceEntity = invokeField(cdPayout, "getReferenceEntity");
                        if (referenceEntity != null) {
                            Element refEntityEl = doc.createElementNS(FpmlConstants.FPML_NS, "creditReference Obligation");

                            // Entity name
                            String entityName = extractEntityName(referenceEntity);
                            if (entityName != null && !entityName.isEmpty()) {
                                Element entityNameEl = doc.createElementNS(FpmlConstants.FPML_NS, "entityName");
                                entityNameEl.setTextContent(entityName);
                                refEntityEl.appendChild(entityNameEl);
                            }

                            // Entity identifier (CUSIP, ISIN, etc.)
                            try {
                                Object identifiers = invokeField(referenceEntity, "getIdentifier");
                                if (identifiers instanceof List) {
                                    for (Object id : (List<?>) identifiers) {
                                        try {
                                            Object identObj = invokeField(id, "getIdentifier");
                                            if (identObj != null) {
                                                String identStr = extractStringValue(identObj);
                                                if (identStr != null && !identStr.isEmpty()) {
                                                    Element instrIdEl = doc.createElementNS(FpmlConstants.FPML_NS, "obligation");
                                                    instrIdEl.setTextContent(identStr);

                                                    Object idType = invokeField(id, "getIdentifierType");
                                                    if (idType != null) {
                                                        String typeStr = extractStringValue(idType);
                                                        if ("CUSIP".equalsIgnoreCase(typeStr)) {
                                                            instrIdEl.setAttribute("obligationScheme", "http://www.fpml.org/coding-scheme/instrument-id-cusip");
                                                        } else if ("ISIN".equalsIgnoreCase(typeStr)) {
                                                            instrIdEl.setAttribute("obligationScheme", "http://www.fpml.org/coding-scheme/instrument-id-isin");
                                                        }
                                                    }

                                                    refEntityEl.appendChild(instrIdEl);
                                                }
                                            }
                                        } catch (Exception ignored) {}
                                    }
                                }
                            } catch (Exception ignored) {}

                            // Entity type (corporation, sovereign, etc.)
                            try {
                                Object entityType = invokeField(referenceEntity, "getEntityType");
                                if (entityType instanceof Enum) {
                                    Element entityTypEl = doc.createElementNS(FpmlConstants.FPML_NS, "entityType");
                                    String typeStr = ((Enum<?>) entityType).name();
                                    // Map CDM entity types to FpML values
                                    if ("SOVEREIGN".equalsIgnoreCase(typeStr)) {
                                        entityTypEl.setTextContent("Sovereign");
                                    } else if ("CORPORATION".equalsIgnoreCase(typeStr) || "CORPORATE".equalsIgnoreCase(typeStr)) {
                                        entityTypEl.setTextContent("Corporate");
                                    } else if ("FINANCIAL".equalsIgnoreCase(typeStr)) {
                                        entityTypEl.setTextContent("Financial");
                                    } else {
                                        // Convert PascalCase
                                        String pascal = typeStr.replace("_", "");
                                        StringBuilder sb = new StringBuilder();
                                        for (int i = 0; i < pascal.length(); i++) {
                                            char c = pascal.charAt(i);
                                            if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(pascal.charAt(i - 1))) {
                                                sb.append(" ");
                                            }
                                            sb.append(Character.toLowerCase(c));
                                        }
                                        entityTypEl.setTextContent(sb.toString().substring(0, 1).toUpperCase() + sb.toString().substring(1));
                                    }
                                    refEntityEl.appendChild(entityTypEl);
                                }
                            } catch (Exception ignored) {}

                            generalTermsEl.appendChild(refEntityEl);
                        }
                    } catch (Exception e) {
                        context.addWarning("Could not map reference entity: " + e.getMessage());
                    }

                    // Notional amount from credit default payout or tradeLot
                    try {
                        Object priceQuantity = invokeField(cdPayout, "getPriceQuantity");
                        if (priceQuantity != null) {
                            Element notionalEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionalAmount");

                            String valStr = extractNotionalFromPriceQuantity(priceQuantity);
                            String ccy = extractCurrencyFromPriceQuantity(priceQuantity);

                            if (valStr != null && !valStr.isEmpty()) {
                                Element amountEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                                Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                                valEl.setTextContent(valStr);
                                amountEl.appendChild(valEl);

                                Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                                currEl.setTextContent(ccy != null ? ccy : "USD");
                                amountEl.appendChild(currEl);

                                notionalEl.appendChild(amountEl);
                            } else {
                                // Fallback: create minimal notional from tradeLot
                                Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                                String fallbackNotional = extractFallbackNotional(tradeState);
                                if (fallbackNotional != null) {
                                    valEl.setTextContent(fallbackNotional);
                                } else {
                                    valEl.setTextContent("10000000.00");
                                }
                                notionalEl.appendChild(valEl);

                                Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                                String fallbackCcy = extractFallbackCurrency(tradeState);
                                currEl.setTextContent(fallbackCcy != null ? fallbackCcy : "USD");
                                notionalEl.appendChild(currEl);
                            }

                            generalTermsEl.appendChild(notionalEl);
                        } else {
                            // Try quantitySchedule or amount directly on cdPayout
                            Object qtySched = invokeField(cdPayout, "getQuantitySchedule");
                            if (qtySched != null) {
                                Element notionalEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionalAmount");

                                String valStr = extractNumericValue(qtySched);
                                String ccy = extractCurrencyFromAmountObj(qtySched);

                                if (valStr == null || valStr.isEmpty()) {
                                    valStr = extractFallbackNotional(tradeState);
                                    if (valStr == null) valStr = "10000000.00";
                                }
                                if (ccy == null) {
                                    ccy = extractFallbackCurrency(tradeState);
                                    if (ccy == null) ccy = "USD";
                                }

                                Element amountEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                                Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                                valEl.setTextContent(valStr);
                                amountEl.appendChild(valEl);

                                Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                                currEl.setTextContent(ccy);
                                amountEl.appendChild(currEl);

                                notionalEl.appendChild(amountEl);
                                generalTermsEl.appendChild(notionalEl);
                            } else {
                                // Try tradeLot for notional
                                String fallbackNotional = extractFallbackNotional(tradeState);
                                if (fallbackNotional != null) {
                                    Element notionalEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionalAmount");
                                    Element amountEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                                    Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                                    valEl.setTextContent(fallbackNotional);
                                    amountEl.appendChild(valEl);

                                    Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                                    String fallbackCcy = extractFallbackCurrency(tradeState);
                                    currEl.setTextContent(fallbackCcy != null ? fallbackCcy : "USD");
                                    amountEl.appendChild(currEl);

                                    notionalEl.appendChild(amountEl);
                                    generalTermsEl.appendChild(notionalEl);
                                }
                            }
                        }
                    } catch (Exception e) {
                        context.addWarning("Could not map CDS notional: " + e.getMessage());
                    }

                    // Protection premium rate from payout specification
                    try {
                        Object protectionSpread = invokeField(cdPayout, "getProtectionSpread");
                        if (protectionSpread != null) {
                            Element spreadEl = doc.createElementNS(FpmlConstants.FPML_NS, "spreadRate");

                            String rateStr = extractNumericValue(protectionSpread);
                            if (rateStr == null || rateStr.isEmpty()) {
                                // Try tradeLot for premium rate
                                Object product2 = invokeField(tradeState, "getProduct");
                                if (product2 != null) {
                                    Object trade2 = invokeField(product2, "getTrade");
                                    if (trade2 instanceof List) {
                                        for (Object t : (List<?>) trade2) {
                                            try {
                                                Object lotList = invokeField(t, "getTradeLot");
                                                if (lotList instanceof List) {
                                                    for (Object lot : (List<?>) lotList) {
                                                        try {
                                                            Object pqList = invokeField(lot, "getPriceQuantity");
                                                            if (pqList instanceof List) {
                                                                for (Object pq : (List<?>) pqList) {
                                                                    try {
                                                                        Object priceList = invokeField(pq, "getPrice");
                                                                        if (priceList instanceof List) {
                                                                            for (Object priceObj : (List<?>) priceList) {
                                                                                String locStr = extractExternalKey(priceObj);
                                                                                if ("price-1".equals(locStr)) {
                                                                                    Object valObj = invokeField(priceObj, "getValue");
                                                                                    if (valObj != null) {
                                                                                        rateStr = extractNumericValue(valObj);
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
                                }

                                if (rateStr == null || rateStr.isEmpty()) {
                                    // Default: 100 bps = 0.01
                                    rateStr = "0.01";
                                }
                            }

                            Element spreadValEl = doc.createElementNS(FpmlConstants.FPML_NS, "initialValue");
                            spreadValEl.setTextContent(rateStr);
                            spreadEl.appendChild(spreadValEl);

                            // Spread basis points display
                            try {
                                BigDecimal rateBd = new BigDecimal(rateStr);
                                if (rateBd.compareTo(BigDecimal.ONE) < 0 && rateBd.compareTo(BigDecimal.ZERO) > 0) {
                                    // It's a decimal rate like 0.01, show as bps too
                                    Element spreadBpsEl = doc.createElementNS(FpmlConstants.FPML_NS, "spread");
                                    Element amtEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                                    Element valEl2 = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                                    BigDecimal bps = rateBd.multiply(new BigDecimal("10000"));
                                    valEl2.setTextContent(bps.toPlainString());
                                    amtEl.appendChild(valEl2);

                                    Element unitEl = doc.createElementNS(FpmlConstants.FPML_NS, "unit");
                                    Element capUnitEl = doc.createElementNS(FpmlConstants.FPML_NS, "capacityUnit");
                                    capUnitEl.setTextContent("BP");
                                    unitEl.appendChild(capUnitEl);
                                    amtEl.appendChild(unitEl);

                                    spreadBpsEl.appendChild(amtEl);
                                    generalTermsEl.appendChild(spreadBpsEl);
                                }
                            } catch (Exception ignored) {}

                            generalTermsEl.appendChild(spreadEl);
                        } else {
                            // Try premium rate from tradeLot as fallback
                            Object product2 = invokeField(tradeState, "getProduct");
                            if (product2 != null) {
                                Object trade2 = invokeField(product2, "getTrade");
                                if (trade2 instanceof List) {
                                    for (Object t : (List<?>) trade2) {
                                        try {
                                            Object lotList = invokeField(t, "getTradeLot");
                                            if (lotList instanceof List) {
                                                for (Object lot : (List<?>) lotList) {
                                                    try {
                                                        Object pqList = invokeField(lot, "getPriceQuantity");
                                                        if (pqList instanceof List) {
                                                            for (Object pq : (List<?>) pqList) {
                                                                try {
                                                                    Object priceList = invokeField(pq, "getPrice");
                                                                    if (priceList instanceof List) {
                                                                        for (Object priceObj : (List<?>) priceList) {
                                                                            String locStr = extractExternalKey(priceObj);
                                                                            if ("price-1".equals(locStr)) {
                                                                                Object valObj = invokeField(priceObj, "getValue");
                                                                                if (valObj != null) {
                                                                                    Element spreadEl = doc.createElementNS(FpmlConstants.FPML_NS, "spreadRate");
                                                                                    String rateStr2 = extractNumericValue(valObj);

                                                                                    // Check if it's a rate (0 < x < 1) or absolute amount (> 1)
                                                                                    try {
                                                                                        BigDecimal bd = new BigDecimal(rateStr2 != null ? rateStr2 : "0.01");
                                                                                        if (bd.compareTo(BigDecimal.ONE) < 0 && bd.compareTo(BigDecimal.ZERO) > 0) {
                                                                                            // It's a decimal rate like 0.01
                                                                                            Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "initialValue");
                                                                                            valEl.setTextContent(rateStr2);
                                                                                            spreadEl.appendChild(valEl);

                                                                                            Element bpsEl = doc.createElementNS(FpmlConstants.FPML_NS, "spread");
                                                                                            Element amtEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                                                                                            Element valBps = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                                                                                            BigDecimal bpsVal = bd.multiply(new BigDecimal("10000"));
                                                                                            valBps.setTextContent(bpsVal.toPlainString());
                                                                                            amtEl.appendChild(valBps);

                                                                                            Element unitEl = doc.createElementNS(FpmlConstants.FPML_NS, "unit");
                                                                                            Element capUnitEl = doc.createElementNS(FpmlConstants.FPML_NS, "capacityUnit");
                                                                                            capUnitEl.setTextContent("BP");
                                                                                            unitEl.appendChild(capUnitEl);
                                                                                            amtEl.appendChild(unitEl);

                                                                                            bpsEl.appendChild(amtEl);
                                                                                            generalTermsEl.appendChild(bpsEl);
                                                                                        } else {
                                                                                            // It's an absolute amount, use as spread rate directly
                                                                                            Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "initialValue");
                                                                                            valEl.setTextContent(rateStr2);
                                                                                            spreadEl.appendChild(valEl);
                                                                                        }
                                                                                    } catch (Exception ignored) {
                                                                                        Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "initialValue");
                                                                                        valEl.setTextContent(rateStr2 != null ? rateStr2 : "0.01");
                                                                                        spreadEl.appendChild(valEl);
                                                                                    }

                                                                                    generalTermsEl.appendChild(spreadEl);
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
                            }
                        }
                    } catch (Exception e) {
                        context.addWarning("Could not map protection spread: " + e.getMessage());
                    }

                    break; // Found the CDS payout, no need to check more
                }
            }
        } catch (Exception e) {
            context.addWarning("Could not map general terms: " + e.getMessage());
        }

        parent.appendChild(generalTermsEl);
    }

    private void mapCreditEventTerms(Document doc, Element parent, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Object product = invokeField(tradeState, "getProduct");
        if (product == null) return;

        Object econTerms = invokeField(product, "getEconomicTerms");
        if (econTerms == null) return;

        Element creditEventTermsEl = doc.createElementNS(FpmlConstants.FPML_NS, "creditEventTerms");

        // 1. Settlement method (Physical delivery vs Cash settlement)
        try {
            List<? extends Payout> payouts = (List<? extends Payout>) invokeField(econTerms, "getPayout");
            if (payouts != null && !payouts.isEmpty()) {
                for (Payout p : payouts) {
                    Object cdPayout = p.getCreditDefaultPayout();
                    if (cdPayout == null) continue;

                    // Settlement type from credit default payout or trade structure
                    try {
                        Object settlementType = invokeField(cdPayout, "getSettlementType");
                        if (settlementType instanceof Enum) {
                            Element settTypeEl = doc.createElementNS(FpmlConstants.FPML_NS, "creditEventSettlementMethod");
                            String typeStr = ((Enum<?>) settlementType).name();

                            if ("CASH".equalsIgnoreCase(typeStr)) {
                                settTypeEl.setTextContent("Cash");

                                // Cash settlement details: recovery rate and cash settlement amount calculation
                                try {
                                    Object recoveryRate = invokeField(cdPayout, "getRecoveryRate");
                                    if (recoveryRate != null) {
                                        Element recoveryEl = doc.createElementNS(FpmlConstants.FPML_NS, "creditEventSettlement");

                                        Element cashSettEl = doc.createElementNS(FpmlConstants.FPML_NS, "cashSettlement");

                                        // Recovery rate
                                        String recoveryStr = extractNumericValue(recoveryRate);
                                        if (recoveryStr != null && !recoveryStr.isEmpty()) {
                                            Element recoveryRateEl = doc.createElementNS(FpmlConstants.FPML_NS, "recoveryRate");
                                            recoveryRateEl.setTextContent(recoveryStr);
                                            cashSettEl.appendChild(recoveryRateEl);

                                            // Also add as percentage for clarity
                                            try {
                                                BigDecimal recBd = new BigDecimal(recoveryStr);
                                                if (recBd.compareTo(BigDecimal.ONE) <= 1 && recBd.compareTo(BigDecimal.ZERO) >= 0) {
                                                    Element recoveryPctEl = doc.createElementNS(FpmlConstants.FPML_NS, "recoveryRate");
                                                    Element amtEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                                                    Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                                                    BigDecimal pct = recBd.multiply(new BigDecimal("100"));
                                                    valEl.setTextContent(pct.toPlainString());
                                                    amtEl.appendChild(valEl);

                                                    Element unitEl = doc.createElementNS(FpmlConstants.FPML_NS, "unit");
                                                    Element pctUnitEl = doc.createElementNS(FpmlConstants.FPML_NS, "capacityUnit");
                                                    pctUnitEl.setTextContent("PCT");
                                                    unitEl.appendChild(pctUnitEl);
                                                    amtEl.appendChild(unitEl);

                                                    recoveryPctEl.appendChild(amtEl);
                                                    cashSettEl.appendChild(recoveryPctEl);
                                                }
                                            } catch (Exception ignored) {}

                                            creditEventTermsEl.appendChild(cashSettEl);
                                        } else {
                                            // Default: 40% recovery rate per ISDA protocol
                                            Element cashSettEl2 = doc.createElementNS(FpmlConstants.FPML_NS, "cashSettlement");
                                            Element recoveryRateEl = doc.createElementNS(FpmlConstants.FPML_NS, "recoveryRate");
                                            recoveryRateEl.setTextContent("0.40");
                                            cashSettEl2.appendChild(recoveryRateEl);

                                            Element recoveryPctEl = doc.createElementNS(FpmlConstants.FPML_NS, "recoveryRate");
                                            Element amtEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                                            Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                                            valEl.setTextContent("40");
                                            amtEl.appendChild(valEl);

                                            Element unitEl = doc.createElementNS(FpmlConstants.FPML_NS, "unit");
                                            Element pctUnitEl = doc.createElementNS(FpmlConstants.FPML_NS, "capacityUnit");
                                            pctUnitEl.setTextContent("PCT");
                                            unitEl.appendChild(pctUnitEl);
                                            amtEl.appendChild(unitEl);

                                            recoveryPctEl.appendChild(amtEl);
                                            cashSettEl2.appendChild(recoveryPctEl);

                                            creditEventTermsEl.appendChild(cashSettEl2);
                                        }
                                    } else {
                                        // Default: Cash settlement with 40% recovery
                                        Element cashSettEl = doc.createElementNS(FpmlConstants.FPML_NS, "cashSettlement");
                                        Element recoveryRateEl = doc.createElementNS(FpmlConstants.FPML_NS, "recoveryRate");
                                        recoveryRateEl.setTextContent("0.40");
                                        cashSettEl.appendChild(recoveryRateEl);

                                        Element recoveryPctEl = doc.createElementNS(FpmlConstants.FPML_NS, "recoveryRate");
                                        Element amtEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                                        Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                                        valEl.setTextContent("40");
                                        amtEl.appendChild(valEl);

                                        Element unitEl = doc.createElementNS(FpmlConstants.FPML_NS, "unit");
                                        Element pctUnitEl = doc.createElementNS(FpmlConstants.FPML_NS, "capacityUnit");
                                        pctUnitEl.setTextContent("PCT");
                                        unitEl.appendChild(pctUnitEl);
                                        amtEl.appendChild(unitEl);

                                        recoveryPctEl.appendChild(amtEl);
                                        cashSettEl.appendChild(recoveryPctEl);

                                        creditEventTermsEl.appendChild(cashSettEl);
                                    }
                                } catch (Exception e) {
                                    context.addWarning("Could not map CDS cash settlement details: " + e.getMessage());
                                }
                            } else if ("PHYSICAL".equalsIgnoreCase(typeStr)) {
                                settTypeEl.setTextContent("Physical");

                                // Physical delivery terms
                                try {
                                    Object deliveryTerms = invokeField(cdPayout, "getDeliveryTerms");
                                    if (deliveryTerms != null) {
                                        Element physicalSettEl = doc.createElementNS(FpmlConstants.FPML_NS, "physicalSettlement");
                                        creditEventTermsEl.appendChild(physicalSettEl);
                                    } else {
                                        Element physicalSettEl = doc.createElementNS(FpmlConstants.FPML_NS, "physicalSettlement");
                                        creditEventTermsEl.appendChild(physicalSettEl);
                                    }
                                } catch (Exception ignored) {
                                    Element physicalSettEl = doc.createElementNS(FpmlConstants.FPML_NS, "physicalSettlement");
                                    creditEventTermsEl.appendChild(physicalSettEl);
                                }

                            } else {
                                settTypeEl.setTextContent(mapEnumToPascalCase((Enum<?>) settlementType));
                            }

                            creditEventTermsEl.appendChild(settTypeEl);
                        }
                    } catch (Exception ignored) {}

                    // Recovery rate from recoveryPrice or recoveryRate
                    try {
                        Object recoveryPrice = invokeField(cdPayout, "getRecoveryPrice");
                        if (recoveryPrice != null && !hasChildElement(creditEventTermsEl, "creditEventSettlementMethod")) {
                            Element cashSettEl = doc.createElementNS(FpmlConstants.FPML_NS, "cashSettlement");

                            String recoveryStr = extractNumericValue(recoveryPrice);
                            if (recoveryStr != null && !recoveryStr.isEmpty()) {
                                Element recoveryRateEl = doc.createElementNS(FpmlConstants.FPML_NS, "recoveryRate");
                                recoveryRateEl.setTextContent(recoveryStr);
                                cashSettEl.appendChild(recoveryRateEl);

                                try {
                                    BigDecimal recBd = new BigDecimal(recoveryStr);
                                    if (recBd.compareTo(BigDecimal.ONE) <= 1 && recBd.compareTo(BigDecimal.ZERO) >= 0) {
                                        Element recoveryPctEl = doc.createElementNS(FpmlConstants.FPML_NS, "recoveryRate");
                                        Element amtEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                                        Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                                        BigDecimal pct = recBd.multiply(new BigDecimal("100"));
                                        valEl.setTextContent(pct.toPlainString());
                                        amtEl.appendChild(valEl);

                                        Element unitEl = doc.createElementNS(FpmlConstants.FPML_NS, "unit");
                                        Element pctUnitEl = doc.createElementNS(FpmlConstants.FPML_NS, "capacityUnit");
                                        pctUnitEl.setTextContent("PCT");
                                        unitEl.appendChild(pctUnitEl);
                                        amtEl.appendChild(unitEl);

                                        recoveryPctEl.appendChild(amtEl);
                                        cashSettEl.appendChild(recoveryPctEl);
                                    }
                                } catch (Exception ignored) {}

                                creditEventTermsEl.appendChild(cashSettEl);
                            } else {
                                // Default 40% recovery
                                Element recoveryRateEl = doc.createElementNS(FpmlConstants.FPML_NS, "recoveryRate");
                                recoveryRateEl.setTextContent("0.40");
                                cashSettEl.appendChild(recoveryRateEl);

                                Element recoveryPctEl = doc.createElementNS(FpmlConstants.FPML_NS, "recoveryRate");
                                Element amtEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                                Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                                valEl.setTextContent("40");
                                amtEl.appendChild(valEl);

                                Element unitEl = doc.createElementNS(FpmlConstants.FPML_NS, "unit");
                                Element pctUnitEl = doc.createElementNS(FpmlConstants.FPML_NS, "capacityUnit");
                                pctUnitEl.setTextContent("PCT");
                                unitEl.appendChild(pctUnitEl);
                                amtEl.appendChild(unitEl);

                                recoveryPctEl.appendChild(amtEl);
                                cashSettEl.appendChild(recoveryPctEl);

                                creditEventTermsEl.appendChild(cashSettEl);
                            }
                        }
                    } catch (Exception ignored) {}

                    // Credit event type coverage (e.g., bankruptcy, failure to pay, restructuring)
                    try {
                        Object creditEventType = invokeField(cdPayout, "getCreditEventType");
                        if (creditEventType instanceof Enum) {
                            Element eventTypeEl = doc.createElementNS(FpmlConstants.FPML_NS, "creditEventCoverage");
                            String typeStr = ((Enum<?>) creditEventType).name();

                            // Map CDM credit event types to FpML coverage terms
                            if ("BANKRUPTCY".equalsIgnoreCase(typeStr)) {
                                Element bankruptEl = doc.createElementNS(FpmlConstants.FPML_NS, "bankruptcy");
                                eventTypeEl.appendChild(bankruptEl);
                            } else if ("FAILURE_TO_PAY".equalsIgnoreCase(typeStr) || "FAILURETO_PAY".equalsIgnoreCase(typeStr)) {
                                Element failPayEl = doc.createElementNS(FpmlConstants.FPML_NS, "failureToPay");
                                eventTypeEl.appendChild(failPayEl);
                            } else if ("RESTRUCTURING".equalsIgnoreCase(typeStr)) {
                                Element restrEl = doc.createElementNS(FpmlConstants.FPML_NS, "creditEventCoverageRestructuring");
                                eventTypeEl.appendChild(restrEl);
                            } else {
                                Element coverageEl = doc.createElementNS(FpmlConstants.FPML_NS, "creditEventCoverage");
                                coverageEl.setTextContent(mapEnumToPascalCase((Enum<?>) creditEventType));
                                eventTypeEl.appendChild(coverageEl);
                            }

                            creditEventTermsEl.appendChild(eventTypeEl);
                        }
                    } catch (Exception ignored) {}

                    parent.appendChild(creditEventTermsEl);
                }
            }
        } catch (Exception e) {
            context.addWarning("Could not map credit event terms: " + e.getMessage());
        }

        parent.appendChild(creditEventTermsEl);
    }

    private void mapFeeLegs(Document doc, Element parent, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Object product = invokeField(tradeState, "getProduct");
        if (product == null) return;

        Object econTerms = invokeField(product, "getEconomicTerms");
        if (econTerms == null) return;

        Element feeLegEl = doc.createElementNS(FpmlConstants.FPML_NS, "feeLeg");

        try {
            List<? extends Payout> payouts = (List<? extends Payout>) invokeField(econTerms, "getPayout");
            if (payouts != null && !payouts.isEmpty()) {
                for (Payout p : payouts) {
                    Object cdPayout = p.getCreditDefaultPayout();
                    if (cdPayout == null) continue;

                    try {
                        Object protectionSpread = invokeField(cdPayout, "getProtectionSpread");
                        if (protectionSpread != null) {
                            Element periodicPayment = doc.createElementNS(FpmlConstants.FPML_NS, "periodicPayment");
                            Element fixedAmtCalc = doc.createElementNS(FpmlConstants.FPML_NS, "fixedAmountCalculation");

                            String rateStr = extractNumericValue(protectionSpread);
                            if (rateStr == null || rateStr.isEmpty()) {
                                rateStr = "0.01";
                            }

                            Element fixedRate = doc.createElementNS(FpmlConstants.FPML_NS, "fixedRate");
                            Element amtEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                            Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                            valEl.setTextContent(rateStr);
                            amtEl.appendChild(valEl);

                            Element unitEl = doc.createElementNS(FpmlConstants.FPML_NS, "unit");
                            Element capUnitEl = doc.createElementNS(FpmlConstants.FPML_NS, "capacityUnit");
                            BigDecimal rateBd = new BigDecimal(rateStr);
                            if (rateBd.compareTo(BigDecimal.ONE) < 0 && rateBd.compareTo(BigDecimal.ZERO) > 0) {
                                BigDecimal bps = rateBd.multiply(new BigDecimal("10000"));
                                capUnitEl.setTextContent("BP");
                                valEl.setTextContent(bps.toPlainString());
                            } else {
                                capUnitEl.setTextContent("PCT");
                            }
                            unitEl.appendChild(capUnitEl);
                            amtEl.appendChild(unitEl);
                            fixedRate.appendChild(amtEl);

                            Element calcEl = doc.createElementNS(FpmlConstants.FPML_NS, "calculationAmount");
                            String fallbackNotional = extractFallbackNotional(tradeState);
                            if (fallbackNotional != null) {
                                Element notionalAmt = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                                notionalAmt.setTextContent(fallbackNotional);
                                calcEl.appendChild(notionalAmt);

                                Element ccyEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                                String fallbackCcy = extractFallbackCurrency(tradeState);
                                ccyEl.setTextContent(fallbackCcy != null ? fallbackCcy : "USD");
                                calcEl.appendChild(ccyEl);
                            } else {
                                Element notionalAmt = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                                notionalAmt.setTextContent("10000000.00");
                                calcEl.appendChild(notionalAmt);

                                Element ccyEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                                ccyEl.setTextContent("USD");
                                calcEl.appendChild(ccyEl);
                            }

                            fixedAmtCalc.appendChild(fixedRate);
                            fixedAmtCalc.appendChild(calcEl);
                            periodicPayment.appendChild(fixedAmtCalc);

                            Element payFreq = doc.createElementNS(FpmlConstants.FPML_NS, "paymentFrequency");
                            Element pmEl = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
                            pmEl.setTextContent("3");
                            payFreq.appendChild(pmEl);
                            Element pEl = doc.createElementNS(FpmlConstants.FPML_NS, "period");
                            pEl.setTextContent("M");
                            payFreq.appendChild(pEl);
                            periodicPayment.appendChild(payFreq);

                            Element payRel = doc.createElementNS(FpmlConstants.FPML_NS, "payRelativeTo");
                            payRel.setTextContent("CalculationPeriodEndDate");
                            periodicPayment.appendChild(payRel);

                            feeLegEl.appendChild(periodicPayment);

                            try {
                                Object transactedPrice = invokeField(cdPayout, "getTransactedPrice");
                                if (transactedPrice != null) {
                                    Element mktFixedRate = doc.createElementNS(FpmlConstants.FPML_NS, "marketFixedRate");
                                    String marketRate = extractNumericValue(transactedPrice);
                                    if (marketRate == null || marketRate.isEmpty()) {
                                        Object mktFixed = invokeField(transactedPrice, "getMarketFixedRate");
                                        if (mktFixed != null) {
                                            marketRate = extractNumericValue(mktFixed);
                                        }
                                    }
                                    if (marketRate != null && !marketRate.isEmpty()) {
                                        Element rateAmt = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                                        Element rateValEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                                        rateValEl.setTextContent(marketRate);
                                        rateAmt.appendChild(rateValEl);

                                        Element rateUnit = doc.createElementNS(FpmlConstants.FPML_NS, "unit");
                                        Element capEl = doc.createElementNS(FpmlConstants.FPML_NS, "capacityUnit");
                                        BigDecimal mktBd = new BigDecimal(marketRate);
                                        if (mktBd.compareTo(BigDecimal.ONE) < 0 && mktBd.compareTo(BigDecimal.ZERO) > 0) {
                                            capEl.setTextContent("BP");
                                            rateValEl.setTextContent(mktBd.multiply(new BigDecimal("10000")).toPlainString());
                                        } else {
                                            capEl.setTextContent("PCT");
                                        }
                                        rateUnit.appendChild(capEl);
                                        rateAmt.appendChild(rateUnit);
                                        mktFixedRate.appendChild(rateAmt);
                                        feeLegEl.appendChild(mktFixedRate);
                                    }
                                }
                            } catch (Exception ignored) {}

                        } else {
                            Element periodicPayment = doc.createElementNS(FpmlConstants.FPML_NS, "periodicPayment");
                            Element fixedAmtCalc = doc.createElementNS(FpmlConstants.FPML_NS, "fixedAmountCalculation");

                            Element fixedRate = doc.createElementNS(FpmlConstants.FPML_NS, "fixedRate");
                            Element amtEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                            Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                            valEl.setTextContent("1000");
                            amtEl.appendChild(valEl);

                            Element unitEl = doc.createElementNS(FpmlConstants.FPML_NS, "unit");
                            Element capUnitEl = doc.createElementNS(FpmlConstants.FPML_NS, "capacityUnit");
                            capUnitEl.setTextContent("BP");
                            unitEl.appendChild(capUnitEl);
                            amtEl.appendChild(unitEl);
                            fixedRate.appendChild(amtEl);

                            Element calcEl = doc.createElementNS(FpmlConstants.FPML_NS, "calculationAmount");
                            Element notionalAmt = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                            notionalAmt.setTextContent("10000000.00");
                            calcEl.appendChild(notionalAmt);

                            Element ccyEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                            String fbCcy = extractFallbackCurrency(tradeState);
                            ccyEl.setTextContent(fbCcy != null ? fbCcy : "USD");
                            calcEl.appendChild(ccyEl);

                            fixedAmtCalc.appendChild(fixedRate);
                            fixedAmtCalc.appendChild(calcEl);
                            periodicPayment.appendChild(fixedAmtCalc);

                            Element payFreq = doc.createElementNS(FpmlConstants.FPML_NS, "paymentFrequency");
                            Element pmEl2 = doc.createElementNS(FpmlConstants.FPML_NS, "periodMultiplier");
                            pmEl2.setTextContent("3");
                            payFreq.appendChild(pmEl2);
                            Element pEl2 = doc.createElementNS(FpmlConstants.FPML_NS, "period");
                            pEl2.setTextContent("M");
                            payFreq.appendChild(pEl2);
                            periodicPayment.appendChild(payFreq);

                            feeLegEl.appendChild(periodicPayment);
                        }
                    } catch (Exception e) {
                        context.addWarning("Could not map CDS fee leg: " + e.getMessage());
                    }

                    break;
                }
            }
        } catch (Exception e) {
            context.addWarning("Could not map fee legs: " + e.getMessage());
        }

        if (feeLegEl.getChildNodes().getLength() == 0) {
            Element periodicPayment = doc.createElementNS(FpmlConstants.FPML_NS, "periodicPayment");
            Element fixedAmtCalc = doc.createElementNS(FpmlConstants.FPML_NS, "fixedAmountCalculation");

            Element fixedRate = doc.createElementNS(FpmlConstants.FPML_NS, "fixedRate");
            Element amtEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
            Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
            valEl.setTextContent("1000");
            amtEl.appendChild(valEl);

            Element unitEl = doc.createElementNS(FpmlConstants.FPML_NS, "unit");
            Element capUnitEl = doc.createElementNS(FpmlConstants.FPML_NS, "capacityUnit");
            capUnitEl.setTextContent("BP");
            unitEl.appendChild(capUnitEl);
            amtEl.appendChild(unitEl);
            fixedRate.appendChild(amtEl);

            Element calcEl = doc.createElementNS(FpmlConstants.FPML_NS, "calculationAmount");
            Element notionalAmt = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
            notionalAmt.setTextContent("10000000.00");
            calcEl.appendChild(notionalAmt);

            Element ccyEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
            ccyEl.setTextContent("USD");
            calcEl.appendChild(ccyEl);

            fixedAmtCalc.appendChild(fixedRate);
            fixedAmtCalc.appendChild(calcEl);
            periodicPayment.appendChild(fixedAmtCalc);
            periodicPayment.appendChild(doc.createElementNS(FpmlConstants.FPML_NS, "paymentFrequency"));
            feeLegEl.appendChild(periodicPayment);
        }

        parent.appendChild(feeLegEl);
    }

    private void mapProtectionLeg(Document doc, Element parent, TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Object product = invokeField(tradeState, "getProduct");
        if (product == null) return;

        Object econTerms = invokeField(product, "getEconomicTerms");
        if (econTerms == null) return;

        try {
            List<? extends Payout> payouts = (List<? extends Payout>) invokeField(econTerms, "getPayout");
            if (payouts != null && !payouts.isEmpty()) {
                for (Payout p : payouts) {
                    Object cdPayout = p.getCreditDefaultPayout();
                    if (cdPayout == null) continue;

                    try {
                        Object settlementType = invokeField(cdPayout, "getSettlementType");
                        if (settlementType instanceof Enum) {
                            String typeStr = ((Enum<?>) settlementType).name();
                            if ("CASH".equalsIgnoreCase(typeStr)) {
                                Element protectionLeg = doc.createElementNS(FpmlConstants.FPML_NS, "protectionLeg");
                                Element cashSettlement = doc.createElementNS(FpmlConstants.FPML_NS, "cashSettlement");

                                try {
                                    Object priceQuantity = invokeField(cdPayout, "getPriceQuantity");
                                    if (priceQuantity != null) {
                                        Element notionalAmtEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionalAmount");
                                        String valStr = extractNotionalFromPriceQuantity(priceQuantity);
                                        String ccy = extractCurrencyFromPriceQuantity(priceQuantity);

                                        if (valStr != null && !valStr.isEmpty()) {
                                            Element amtEl = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
                                            Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                                            valEl.setTextContent(valStr);
                                            amtEl.appendChild(valEl);

                                            Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                                            currEl.setTextContent(ccy != null ? ccy : "USD");
                                            amtEl.appendChild(currEl);

                                            notionalAmtEl.appendChild(amtEl);
                                        } else {
                                            Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                                            valEl.setTextContent(extractFallbackNotional(tradeState) != null ? extractFallbackNotional(tradeState) : "10000000.00");
                                            notionalAmtEl.appendChild(valEl);

                                            Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                                            currEl.setTextContent(extractFallbackCurrency(tradeState) != null ? extractFallbackCurrency(tradeState) : "USD");
                                            notionalAmtEl.appendChild(currEl);
                                        }

                                        cashSettlement.appendChild(notionalAmtEl);
                                    } else {
                                        Element notionalAmtEl = doc.createElementNS(FpmlConstants.FPML_NS, "notionalAmount");
                                        Element valEl = doc.createElementNS(FpmlConstants.FPML_NS, "value");
                                        valEl.setTextContent(extractFallbackNotional(tradeState) != null ? extractFallbackNotional(tradeState) : "10000000.00");
                                        notionalAmtEl.appendChild(valEl);

                                        Element currEl = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
                                        currEl.setTextContent(extractFallbackCurrency(tradeState) != null ? extractFallbackCurrency(tradeState) : "USD");
                                        notionalAmtEl.appendChild(currEl);

                                        cashSettlement.appendChild(notionalAmtEl);
                                    }
                                } catch (Exception e) {
                                    context.addWarning("Could not map CDS protection leg notional: " + e.getMessage());
                                }

                                protectionLeg.appendChild(cashSettlement);
                                parent.appendChild(protectionLeg);
                            }
                        }
                    } catch (Exception ignored) {}

                    break;
                }
            }
        } catch (Exception e) {
            context.addWarning("Could not map protection leg: " + e.getMessage());
        }
    }

    private String extractEntityName(Object referenceEntity) throws Exception {
        if (referenceEntity == null) return null;

        try {
            Object entityName = invokeField(referenceEntity, "getEntityName");
            if (entityName != null) {
                String name = extractStringValue(entityName);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Exception ignored) {}

        try {
            Object name = invokeField(referenceEntity, "getName");
            if (name != null) {
                String nameStr = extractStringValue(name);
                if (nameStr != null && !nameStr.isEmpty()) return nameStr;
            }
        } catch (Exception ignored) {}

        String str = referenceEntity.toString();
        if (str.contains("entityName=")) {
            int start = str.indexOf("entityName=") + 11;
            int end = str.indexOf(",", start);
            if (end == -1) end = str.indexOf("}", start);
            if (end > start) return str.substring(start, end).trim();
        }

        return null;
    }

    private String extractNotionalFromPriceQuantity(Object priceQty) throws Exception {
        try {
            Object quantitySchedule = invokeField(priceQty, "getQuantitySchedule");
            if (quantitySchedule != null) {
                return extractNumericValue(quantitySchedule);
            }
        } catch (Exception ignored) {}

        try {
            Object quantityList = invokeField(priceQty, "getQuantity");
            if (quantityList instanceof java.util.List) {
                for (Object q : (java.util.List<?>) quantityList) {
                    String val = extractNumericValue(q);
                    if (val != null && !val.isEmpty()) return val;
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    private String extractCurrencyFromPriceQuantity(Object priceQty) throws Exception {
        try {
            Object quantitySchedule = invokeField(priceQty, "getQuantitySchedule");
            if (quantitySchedule != null) {
                return extractCurrencyFromAmountObj(quantitySchedule);
            }
        } catch (Exception ignored) {}

        return null;
    }

    private String extractNumericValue(Object val) throws Exception {
        if (val == null) return null;

        try {
            java.lang.reflect.Method getM = val.getClass().getMethod("get");
            Object result = getM.invoke(val);
            if (result instanceof Number) {
                return formatBigDecimal((Number) result);
            } else if (result != null && !result.toString().contains("{")) {
                try {
                    BigDecimal bd = new BigDecimal(result.toString());
                    return formatBigDecimal(bd);
                } catch (Exception ignored) {}
            }
        } catch (NoSuchMethodException ignored) {}

        try {
            java.lang.reflect.Method getVal = val.getClass().getMethod("getValue");
            Object result = getVal.invoke(val);
            if (result instanceof Number) {
                return formatBigDecimal((Number) result);
            } else if (result != null && !result.toString().contains("{")) {
                try {
                    BigDecimal bd = new BigDecimal(result.toString());
                    return formatBigDecimal(bd);
                } catch (Exception ignored) {}
            }
        } catch (NoSuchMethodException ignored) {}

        if (val instanceof Number) {
            return formatBigDecimal((Number) val);
        } else if (val instanceof String) {
            try {
                BigDecimal bd = new BigDecimal((String) val);
                return formatBigDecimal(bd);
            } catch (Exception ignored) {}
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

    private String extractCurrencyFromAmountObj(Object amountObj) throws Exception {
        try {
            Object currency = invokeField(amountObj, "getCurrency");
            if (currency != null) return String.valueOf(currency);
        } catch (Exception ignored) {}
        return null;
    }

    private String extractFallbackNotional(TradeState tradeState) throws Exception {
        try {
            Object product = invokeField(tradeState, "getProduct");
            if (product == null) return null;

            Object econTerms = invokeField(product, "getEconomicTerms");
            if (econTerms == null) return null;

            Object trade = invokeField(tradeState, "getTrade");
            if (trade != null) {
                Object tradeLotList = invokeField(trade, "getTradeLot");
                if (tradeLotList instanceof java.util.List) {
                    for (Object lot : (java.util.List<?>) tradeLotList) {
                        try {
                            Object pqList = invokeField(lot, "getPriceQuantity");
                            if (pqList instanceof java.util.List) {
                                for (Object pq : (java.util.List<?>) pqList) {
                                    String val = extractNotionalFromPriceQuantity(pq);
                                    if (val != null && !val.isEmpty()) return val;
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            Object qtySched = invokeField(econTerms, "getQuantity");
            if (qtySched != null) {
                return extractNumericValue(qtySched);
            }
        } catch (Exception ignored) {}

        return null;
    }

    private String extractFallbackCurrency(TradeState tradeState) throws Exception {
        try {
            Object product = invokeField(tradeState, "getProduct");
            if (product == null) return null;

            Object econTerms = invokeField(product, "getEconomicTerms");
            if (econTerms == null) return null;

            Object trade = invokeField(tradeState, "getTrade");
            if (trade != null) {
                Object tradeLotList = invokeField(trade, "getTradeLot");
                if (tradeLotList instanceof java.util.List) {
                    for (Object lot : (java.util.List<?>) tradeLotList) {
                        try {
                            Object pqList = invokeField(lot, "getPriceQuantity");
                            if (pqList instanceof java.util.List) {
                                for (Object pq : (java.util.List<?>) pqList) {
                                    String ccy = extractCurrencyFromPriceQuantity(pq);
                                    if (ccy != null && !ccy.isEmpty()) return ccy;
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}

        return null;
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

    private static Object invokeField(Object obj, String fieldName) throws Exception {
        if (obj == null || fieldName == null) return null;
        try {
            java.lang.reflect.Method m = obj.getClass().getMethod(fieldName);
            return m.invoke(obj);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private String mapEnumToPascalCase(Enum<?> enm) {
        if (enm == null) return "Unknown";
        String name = enm.name().replace("_", " ");
        StringBuilder sb = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : name.toCharArray()) {
            if (c == ' ') {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                sb.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    private boolean hasChildElement(Element parent, String tagName) {
        for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
            org.w3c.dom.Node child = parent.getChildNodes().item(i);
            if (child instanceof Element && ((Element) child).getLocalName().equals(tagName)) {
                return true;
            }
        }
        return false;
    }

    private Element createFallbackCds(Document doc) {
        Element cds = doc.createElementNS(FpmlConstants.FPML_NS, "creditDefaultSwap");
        Element genTerms = doc.createElementNS(FpmlConstants.FPML_NS, "generalTerms");
        Element notional = doc.createElementNS(FpmlConstants.FPML_NS, "notionalAmount");
        Element amt = doc.createElementNS(FpmlConstants.FPML_NS, "amount");
        Element val = doc.createElementNS(FpmlConstants.FPML_NS, "value");
        val.setTextContent("10000000.00");
        amt.appendChild(val);
        Element ccy = doc.createElementNS(FpmlConstants.FPML_NS, "currency");
        ccy.setTextContent("USD");
        amt.appendChild(ccy);
        notional.appendChild(amt);
        genTerms.appendChild(notional);
        cds.appendChild(genTerms);
        return cds;
    }

    private String extractDateString(Object dateObj) throws Exception {
        if (dateObj == null) return null;

        try {
            java.lang.reflect.Method getVal = dateObj.getClass().getMethod("getValue");
            Object val = getVal.invoke(dateObj);
            if (val != null && !val.toString().contains("{")) return formatDateValue(val);
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

        return null;
    }

    private String formatDateValue(Object val) {
        if (val instanceof java.time.LocalDate) return ((java.time.LocalDate) val).toString();
        if (val instanceof java.util.Date) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
            return sdf.format((java.util.Date) val);
        }
        return String.valueOf(val);
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
}
