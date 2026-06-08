package com.example;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import javax.xml.xpath.*;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import com.rosetta.model.lib.records.Date;
import com.rosetta.model.lib.records.globalkey.GlobalKey;
import com.rosetta.model.lib.meta.scope.Scope;

import org.finos.cdm.model.Cdm;
import org.finos.cdm.model.Instruction;
import org.finos.cdm.model.PrimitiveInstruction;
import org.finos.cdm.model.ExecutionInstruction;
import org.finos.cdm.model.TradeState;
import org.finos.cdm.model.Trade;
import org.finos.cdm.model.TradeIdentifier;
import org.finos.cdm.model.Identifier;
import org.finos.cdm.model.AssignedIdentifier;
import org.finos.cdm.model.Party;
import org.finos.cdm.model.PartyIdentifier;
import org.finos.cdm.model.PartyRole;
import org.finos.cdm.model.NonTransferableProduct;
import org.finos.cdm.model.ProductTaxonomy;
import org.finos.cdm.model.EconomicTerms;
import org.finos.cdm.model.Counterparty;
import org.finos.cdm.model.TradeLot;
import org.finos.cdm.model.PriceQuantity;
import org.finos.cdm.model.Price;
import org.finos.cdm.model.PriceSchedule;
import org.finos.cdm.model.NonNegativeQuantitySchedule;
import org.finos.cdm.model.Quantity;
import org.finos.cdm.model.Unit;
import org.finos.cdm.model.Observable;
import org.finos.cdm.model.Asset;
import org.finos.cdm.model.Index;
import org.finos.cdm.model.FloatingRateIndex;
import org.finos.cdm.model.InterestRateIndex;
import org.finos.cdm.model.Payout;
import org.finos.cdm.model.InterestRatePayout;
import org.finos.cdm.model.PayerReceiver;
import org.finos.cdm.model.CalculationPeriodDates;
import org.finos.cdm.model.CalculationPeriodFrequency;
import org.finos.cdm.model.AdjustableDate;
import org.finos.cdm.model.BusinessDayAdjustments;
import org.finos.cdm.model.BusinessCenters;
import org.finos.cdm.model.PaymentDates;
import org.finos.cdm.model.PaymentFrequency;
import org.finos.cdm.model.RateSpecification;
import org.finos.cdm.model.FixedRateSpecification;
import org.finos.cdm.model.FloatingRateSpecification;
import org.finos.cdm.model.RateSchedule;
import org.finos.cdm.model.Schedule;
import org.finos.cdm.model.SpreadSchedule;
import org.finos.cdm.model.ResolvablePriceQuantity;
import org.finos.cdm.model.NonNegativeQuantity;
import org.finos.cdm.model.Address;

import com.rosetta.model.lib.meta.scope.Scope;

import static org.finos.cdm.model.CounterpartyRoleEnum.*;
import static org.finos.cdm.model.PartyRoleEnum.*;
import static org.finos.cdm.model.BusinessDayConventionEnum.*;
import static org.finos.cdm.model.RollConventionEnum.*;
import static org.finos.cdm.model.PeriodEnum.*;
import static org.finos.cdm.model.PeriodExtendedEnum.*;
import static org.finos.cdm.model.DayCountFractionEnum.*;
import static org.finos.cdm.model.FloatingRateIndexEnum.*;
import static org.finos.cdm.model.PriceTypeEnum.*;
import static org.finos.cdm.model.PriceExpressionEnum.*;
import static org.finos.cdm.model.TradeIdentifierTypeEnum.*;
import static org.finos.cdm.model.PartyIdentifierTypeEnum.*;
import static org.finos.cdm.model.ProductTaxonomyEnum.*;

public class IrsTransformer {

    public static Cdm transform(String xml) throws Exception {
        Document doc = parseXml(xml);
        XPath xpath = XPathFactory.newInstance().newXPath();
        setupFpmlNs(xpath);

        // ---- Trade Header ----
        String tradeDateStr = xpathEval(xpath, doc, "/f:dataDocument/f:trade/f:tradeHeader/f:tradeDate");
        Date tradeDate = Date.parse(tradeDateStr);

        // Party trade identifiers
        String ownTradeId = xpathEval(xpath, doc, "/f:dataDocument/f:trade/f:tradeHeader/f:partyTradeIdentifier[1]/f:tradeId");
        String cpTradeId = xpathEval(xpath, doc, "/f:dataDocument/f:trade/f:tradeHeader/f:partyTradeIdentifier[2]/f:tradeId");

        // ---- Parties ----
        Party party1 = buildParty(xpath, doc, "party1");
        Party party2 = buildParty(xpath, doc, "party2");

        // ---- Determine FRA vs Swap ----
        boolean isFra = xpathEval(xpath, doc, "/f:dataDocument/f:trade/f:forwardStartFuture") != null;

        if (isFra) {
            return buildFraCdm(xpath, doc, tradeDate, ownTradeId, cpTradeId, party1, party2);
        } else {
            return buildSwapCdm(xpath, doc, tradeDate, ownTradeId, cpTradeId, party1, party2);
        }
    }

    // ======================== FRA ========================

    private static Cdm buildFraCdm(XPath xpath, Document doc, Date tradeDate,
            String ownTradeId, String cpTradeId, Party party1, Party party2) throws Exception {

        // FRA structure in FpML: /trade/forwardStartFuture
        // It has: effectiveDate, terminationDate, fixedRate, floatingRateCalculation,
        //         notional, dayCountFraction, paymentDate, etc.

        // Effective date (start of the forward period)
        String effDateStr = xpathEval(xpath, doc,
                "/f:dataDocument/f:trade/f:forwardStartFuture/f:effectiveDate/f:unadjustedDate");
        Date effDate = Date.parse(effDateStr);

        // Termination date (end of the forward period)
        String termDateStr = xpathEval(xpath, doc,
                "/f:dataDocument/f:trade/f:forwardStartFuture/f:terminationDate/f:unadjustedDate");
        Date termDate = Date.parse(termDateStr);

        // Fixed rate
        String fixedRateStr = xpathEval(xpath, doc,
                "/f:dataDocument/f:trade/f:forwardStartFuture/f:fixedRate");
        BigDecimal fixedRate = new BigDecimal(fixedRateStr);

        // Floating rate index
        String floatIndexStr = xpathEval(xpath, doc,
                "/f:dataDocument/f:trade/f:forwardStartFuture/f:floatingRateCalculation/f:floatingRateIndex");
        FloatingRateIndexEnum floatIndex = mapFloatingRateIndex(floatIndexStr);

        // Index tenor
        String tenorMultStr = xpathEval(xpath, doc,
                "/f:dataDocument/f:trade/f:forwardStartFuture/f:floatingRateCalculation/f:indexTenor/f:periodMultiplier");
        String tenorPeriodStr = xpathEval(xpath, doc,
                "/f:dataDocument/f:trade/f:forwardStartFuture/f:floatingRateCalculation/f:indexTenor/f:period");
        int tenorMult = Integer.parseInt(tenorMultStr);
        PeriodEnum tenorPeriod = mapPeriod(tenorPeriodStr);

        // Spread (optional)
        String spreadStr = xpathEval(xpath, doc,
                "/f:dataDocument/f:trade/f:forwardStartFuture/f:floatingRateCalculation/f:spreadSchedule/f:initialValue");
        BigDecimal spread = spreadStr != null ? new BigDecimal(spreadStr) : null;

        // Notional
        String notionalStr = xpathEval(xpath, doc,
                "/f:dataDocument/f:trade/f:forwardStartFuture/f:notionalAmount/f:notional/f:amount");
        BigDecimal notional = new BigDecimal(notionalStr);
        String currency = xpathEval(xpath, doc,
                "/f:dataDocument/f:trade/f:forwardStartFuture/f:notionalAmount/f:notional/f:currency");

        // Day count fraction
        String dcfStr = xpathEval(xpath, doc,
                "/f:dataDocument/f:trade/f:forwardStartFuture/f:dayCountFraction");
        DayCountFractionEnum dcf = mapDayCountFraction(dcfStr);

        // Payer / Receiver
        String payerHref = xpathAttr(xpath, doc,
                "/f:dataDocument/f:trade/f:forwardStartFuture/f:payerPartyReference", "href");
        String receiverHref = xpathAttr(xpath, doc,
                "/f:dataDocument/f:trade/f:forwardStartFuture/f:receiverPartyReference", "href");
        CounterpartyRoleEnum payerRole = mapPartyRole(payerHref);
        CounterpartyRoleEnum receiverRole = mapPartyRole(receiverHref);

        // Payment date
        String payDateStr = xpathEval(xpath, doc,
                "/f:dataDocument/f:trade/f:forwardStartFuture/f:paymentDate/f:unadjustedDate");
        Date payDate = payDateStr != null ? Date.parse(payDateStr) : null;

        // Business day convention for payment date
        String bdcStr = xpathEval(xpath, doc,
                "/f:dataDocument/f:trade/f:forwardStartFuture/f:paymentDate/f:paymentDatesAdjustments/f:businessDayConvention");
        BusinessDayConventionEnum bdc = bdcStr != null ? mapBusinessDayConvention(bdcStr) : null;

        // Business centers for payment date
        List<String> payBizCenters = xpathList(xpath, doc,
                "/f:dataDocument/f:trade/f:forwardStartFuture/f:paymentDate/f:paymentDatesAdjustments/f:businessCenters/f:businessCenter");

        // ---- Build CDM ----

        // Trade identifiers
        List<TradeIdentifier> tradeIdentifiers = new ArrayList<>();
        if (ownTradeId != null) {
            TradeIdentifier ti1 = TradeIdentifier.builder()
                    .addAssignedIdentifier(AssignedIdentifier.builder()
                            .setIdentifier(ownTradeId)
                            .build())
                    .build();
            tradeIdentifiers.add(ti1);
        }
        if (cpTradeId != null) {
            TradeIdentifier ti2 = TradeIdentifier.builder()
                    .addAssignedIdentifier(AssignedIdentifier.builder()
                            .setIdentifier(cpTradeId)
                            .build())
                    .build();
            tradeIdentifiers.add(ti2);
        }

        // Parties
        List<Party> parties = new ArrayList<>();
        parties.add(party1);
        parties.add(party2);

        // Party roles
        List<PartyRole> partyRoles = new ArrayList<>();
        partyRoles.add(PartyRole.builder()
                .setPartyReference(party1)
                .setRole(CALCULATION_AGENT)
                .build());

        // Counterparties
        Counterparty cp1 = Counterparty.builder()
                .setRole(PARTY_1)
                .setPartyReference(party1)
                .build();
        Counterparty cp2 = Counterparty.builder()
                .setRole(PARTY_2)
                .setPartyReference(party2)
                .build();

        // ---- TradeLot with PriceQuantity ----
        // pq[0]: floating observable + floating notional
        // pq[1]: fixed rate + fixed notional

        // Floating observable
        FloatingRateIndex fri = FloatingRateIndex.builder()
                .setFloatingRateIndex(floatIndex)
                .setIndexTenor(com.rosetta.model.lib.records.Period.builder()
                        .setPeriodMultiplier(tenorMult)
                        .setPeriod(tenorPeriod)
                        .build())
                .build();
        Index idx = Index.builder().setFloatingRateIndex(fri).build();
        Asset asset = Asset.builder().setIndex(idx).build();
        Observable observable = Observable.builder().setAsset(asset).build();

        // Floating notional quantity
        NonNegativeQuantitySchedule floatQty = NonNegativeQuantitySchedule.builder()
                .setUnit(Unit.builder().setCurrency(currency).build())
                .setValue(notional)
                .build();

        // PriceQuantity for floating
        PriceQuantity pqFloat = PriceQuantity.builder()
                .setObservable(observable)
                .addQuantity(floatQty)
                .build();

        // Fixed rate price
        Price fixedPrice = Price.builder()
                .setValue(fixedRate)
                .setPriceType(InterestRate)
                .build();

        // Fixed notional quantity
        NonNegativeQuantitySchedule fixedQty = NonNegativeQuantitySchedule.builder()
                .setUnit(Unit.builder().setCurrency(currency).build())
                .setValue(notional)
                .build();

        // PriceQuantity for fixed
        PriceQuantity pqFixed = PriceQuantity.builder()
                .addPrice(fixedPrice)
                .addQuantity(fixedQty)
                .build();

        // TradeLot
        TradeLot tradeLot = TradeLot.builder()
                .addPriceQuantity(pqFloat)
                .addPriceQuantity(pqFixed)
                .build();

        // ---- InterestRatePayout for Fixed leg ----
        // Fixed leg: payer pays fixed
        AdjustableDate effAdjDate = AdjustableDate.builder()
                .setUnadjustedDate(effDate)
                .build();
        AdjustableDate termAdjDate = AdjustableDate.builder()
                .setUnadjustedDate(termDate)
                .build();

        CalculationPeriodDates cpdFixed = CalculationPeriodDates.builder()
                .setEffectiveDate(effAdjDate)
                .setTerminationDate(termAdjDate)
                .build();

        // Fixed rate specification
        RateSchedule rateSchedule = RateSchedule.builder()
                .addSchedule(Schedule.builder().setValue(fixedRate).build())
                .build();
        FixedRateSpecification fixedRateSpec = FixedRateSpecification.builder()
                .setRateSchedule(rateSchedule)
                .build();
        RateSpecification rateSpecFixed = RateSpecification.builder()
                .setFixedRateSpecification(fixedRateSpec)
                .build();

        // Payment date for FRA (single date, not PaymentDates)
        AdjustableDate payAdjDate = AdjustableDate.builder()
                .setUnadjustedDate(payDate)
                .build();
        if (bdc != null) {
            BusinessDayAdjustments bda = BusinessDayAdjustments.builder()
                    .setBusinessDayConvention(bdc)
                    .build();
            if (!payBizCenters.isEmpty()) {
                List<com.rosetta.model.lib.records.BusinessCenterEnum> bcList = new ArrayList<>();
                for (String bc : payBizCenters) {
                    bcList.add(com.rosetta.model.lib.records.BusinessCenterEnum.valueOf(bc));
                }
                bda = BusinessDayAdjustments.builder()
                        .setBusinessDayConvention(bdc)
                        .setBusinessCenters(BusinessCenters.builder().setBusinessCenter(bcList).build())
                        .build();
            }
            payAdjDate = AdjustableDate.builder()
                    .setUnadjustedDate(payDate)
                    .setDateAdjustments(bda)
                    .build();
        }

        // PayerReceiver for fixed leg
        PayerReceiver prFixed = PayerReceiver.builder()
                .setPayer(payerRole)
                .setReceiver(receiverRole)
                .build();

        // ResolvablePriceQuantity for fixed
        ResolvablePriceQuantity rpqFixed = ResolvablePriceQuantity.builder()
                .setQuantitySchedule(NonNegativeQuantitySchedule.builder()
                        .setUnit(Unit.builder().setCurrency(currency).build())
                        .setValue(notional)
                        .build())
                .build();

        InterestRatePayout fixedPayout = InterestRatePayout.builder()
                .setPayerReceiver(prFixed)
                .setRateSpecification(rateSpecFixed)
                .setDayCountFraction(dcf)
                .setCalculationPeriodDates(cpdFixed)
                .setPaymentDate(payAdjDate)
                .setPriceQuantity(rpqFixed)
                .build();

        // ---- InterestRatePayout for Floating leg ----
        // Floating leg: receiver receives floating
        PayerReceiver prFloat = PayerReceiver.builder()
                .setPayer(receiverRole)
                .setReceiver(payerRole)
                .build();

        CalculationPeriodDates cpdFloat = CalculationPeriodDates.builder()
                .setEffectiveDate(effAdjDate)
                .setTerminationDate(termAdjDate)
                .build();

        // Floating rate specification
        InterestRateIndex iri = InterestRateIndex.builder()
                .setFloatingRateIndex(fri)
                .build();

        FloatingRateSpecification floatRateSpec = FloatingRateSpecification.builder()
                .setRateOption(iri)
                .build();
        if (spread != null) {
            SpreadSchedule spreadSchedule = SpreadSchedule.builder()
                    .addSchedule(Schedule.builder().setValue(spread).build())
                    .build();
            floatRateSpec = FloatingRateSpecification.builder()
                    .setRateOption(iri)
                    .setSpreadSchedule(spreadSchedule)
                    .build();
        }
        RateSpecification rateSpecFloat = RateSpecification.builder()
                .setFloatingRateSpecification(floatRateSpec)
                .build();

        ResolvablePriceQuantity rpqFloat = ResolvablePriceQuantity.builder()
                .setQuantitySchedule(NonNegativeQuantitySchedule.builder()
                        .setUnit(Unit.builder().setCurrency(currency).build())
                        .setValue(notional)
                        .build())
                .build();

        InterestRatePayout floatPayout = InterestRatePayout.builder()
                .setPayerReceiver(prFloat)
                .setRateSpecification(rateSpecFloat)
                .setDayCountFraction(dcf)
                .setCalculationPeriodDates(cpdFloat)
                .setPaymentDate(payAdjDate)
                .setPriceQuantity(rpqFloat)
                .build();

        // ---- EconomicTerms ----
        List<Payout> payouts = new ArrayList<>();
        payouts.add(Payout.builder().setInterestRatePayout(fixedPayout).build());
        payouts.add(Payout.builder().setInterestRatePayout(floatPayout).build());

        EconomicTerms economicTerms = EconomicTerms.builder()
                .setPayout(payouts)
                .build();

        // ---- NonTransferableProduct ----
        ProductTaxonomy taxonomy = ProductTaxonomy.builder()
                .setProductTaxonomyEnum(FORWARD_START_FUTURE)
                .build();

        NonTransferableProduct product = NonTransferableProduct.builder()
                .addTaxonomy(taxonomy)
                .setEconomicTerms(economicTerms)
                .build();

        // ---- Trade ----
        Trade trade = Trade.builder()
                .setProduct(product)
                .setTradeDate(tradeDate)
                .setParty(parties)
                .setPartyRole(partyRoles)
                .setCounterparty(java.util.Arrays.asList(cp1, cp2))
                .setTradeLot(java.util.Collections.singletonList(tradeLot))
                .setTradeIdentifier(tradeIdentifiers)
                .build();

        // ---- TradeState ----
        TradeState tradeState = TradeState.builder()
                .setTrade(trade)
                .build();

        // ---- ExecutionInstruction ----
        ExecutionInstruction exec = ExecutionInstruction.builder()
                .setProduct(product)
                .setTradeDate(tradeDate)
                .setCounterparty(java.util.Arrays.asList(cp1, cp2))
                .setParties(parties)
                .setPartyRole(partyRoles)
                .setTradeIdentifier(tradeIdentifiers)
                .setTradeLot(java.util.Collections.singletonList(tradeLot))
                .build();

        // ---- PrimitiveInstruction ----
        PrimitiveInstruction prim = PrimitiveInstruction.builder()
                .setExecution(exec)
                .build();

        // ---- Instruction ----
        Instruction instruction = Instruction.builder()
                .setPrimitiveInstruction(prim)
                .build();

        // ---- Cdm ----
        Cdm cdm = Cdm.builder()
                .addInstruction(instruction)
                .build();

        return cdm;
    }

    // ======================== Swap (placeholder) ========================

    private static Cdm buildSwapCdm(XPath xpath, Document doc, Date tradeDate,
            String ownTradeId, String cpTradeId, Party party1, Party party2) throws Exception {
        // Not needed for FRA test, but keep for completeness
        throw new UnsupportedOperationException("Swap not yet implemented");
    }

    // ======================== Helpers ========================

    private static Document parseXml(String xml) throws Exception {
        org.w3c.dom.DocumentBuilderFactory dbf = org.w3c.dom.DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return dbf.newDocumentBuilder().parse(new InputSource(new java.io.StringReader(xml)));
    }

    private static void setupFpmlNs(XPath xpath) {
        xpath.setNamespaceContext(new XPathNamespaceContext());
    }

    private static String xpathEval(XPath xpath, Document doc, String expr) throws Exception {
        Object result = xpath.evaluate(expr, doc, XPathConstants.STRING);
        return result != null ? result.toString().trim() : null;
    }

    private static String xpathAttr(XPath xpath, Document doc, String expr, String attr) throws Exception {
        Object result = xpath.evaluate(expr + "/@" + attr, doc, XPathConstants.STRING);
        return result != null ? result.toString().trim() : null;
    }

    private static List<String> xpathList(XPath xpath, Document doc, String expr) throws Exception {
        NodeList nl = (NodeList) xpath.evaluate(expr, doc, XPathConstants.NODESET);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < nl.getLength(); i++) {
            result.add(nl.item(i).getTextContent().trim());
        }
        return result;
    }

    private static Party buildParty(XPath xpath, Document doc, String partyId) throws Exception {
        String name = xpathEval(xpath, doc,
                "/f:dataDocument/f:party[@id='" + partyId + "']/f:partyName");
        String lei = xpathEval(xpath, doc,
                "/f:dataDocument/f:party[@id='" + partyId + "']/f:partyId[contains(@partyIdScheme,'iso17442')]");

        PartyIdentifier pid = PartyIdentifier.builder()
                .setIdentifier(lei != null ? lei : partyId)
                .setIdentifierType(lei != null ? LEI : INTERNAL_ID)
                .build();

        return Party.builder()
                .setName(name)
                .addPartyId(pid)
                .build();
    }

    private static CounterpartyRoleEnum mapPartyRole(String href) {
        if (href == null) return PARTY_1;
        if (href.contains("party1")) return PARTY_1;
        if (href.contains("party2")) return PARTY_2;
        return PARTY_1;
    }

    private static DayCountFractionEnum mapDayCountFraction(String dcf) {
        if (dcf == null) return ACT_360;
        return DayCountFractionEnum.valueOf(dcf.replace("/", "_").replace(".", "_"));
    }

    private static FloatingRateIndexEnum mapFloatingRateIndex(String idx) {
        if (idx == null) return USD_SOFR;
        return FloatingRateIndexEnum.valueOf(idx.replace("-", "_").replace(" ", "_"));
    }

    private static PeriodEnum mapPeriod(String p) {
        if (p == null) return M;
        return PeriodEnum.valueOf(p);
    }

    private static BusinessDayConventionEnum mapBusinessDayConvention(String bdc) {
        if (bdc == null) return FOLLOWING;
        return BusinessDayConventionEnum.valueOf(bdc.replace("MODFOLLOWING", "MOD_FOLLOWING"));
    }

    // XPath namespace context for FpML
    static class XPathNamespaceContext implements javax.xml.namespace.NamespaceContext {
        public String getNamespaceURI(String prefix) {
            if ("f".equals(prefix)) return "http://www.fpml.org/FpML-5/confirmation";
            if ("xsi".equals(prefix)) return "http://www.w3.org/2001/XMLSchema-instance";
            return "";
        }
        public String getPrefix(String namespaceURI) { return "f"; }
        public java.util.Iterator<String> getPrefixes(String namespaceURI) { return null; }
    }
}
