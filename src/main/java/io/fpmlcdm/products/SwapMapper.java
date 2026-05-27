package io.fpmlcdm.products;

import cdm.base.staticdata.party.AncillaryParty;
import cdm.base.staticdata.party.Counterparty;
import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.Party;
import cdm.base.staticdata.party.PartyRole;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.event.common.Trade;
import cdm.event.common.TradeState;
import cdm.event.common.TradeIdentifier;
import cdm.observable.asset.PriceQuantity;
import cdm.event.common.ContractDetails;
import cdm.product.template.EconomicTerms;
import cdm.product.template.NonTransferableProduct;
import cdm.product.template.Payout;
import cdm.product.template.TradeLot;
import com.rosetta.model.metafields.FieldWithMetaDate;
import cdm.base.staticdata.party.Account;
import io.fpmlcdm.common.AccountMapper;
import io.fpmlcdm.common.CalculationAgentMapper;
import io.fpmlcdm.common.ContractDetailsMapper;
import io.fpmlcdm.common.IdentifierMapper;
import io.fpmlcdm.common.MappingContext;
import io.fpmlcdm.common.PartyMapper;
import io.fpmlcdm.common.PartyRoleMapper;
import io.fpmlcdm.common.ProductIdentifierMapper;
import io.fpmlcdm.common.QuantityMapper;
import io.fpmlcdm.common.TransferMapper;
import io.fpmlcdm.common.StreamLabels;
import io.fpmlcdm.common.TerminationProvisionMapper;
import io.fpmlcdm.common.TaxonomyMapper;
import io.fpmlcdm.common.XmlUtils;
import io.fpmlcdm.common.DateMapper;
import io.fpmlcdm.payouts.InterestRatePayoutMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Interest Rate Swap (and Basis Swap) FpML→CDM mapper. */
public class SwapMapper implements ProductMapper {

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);

        // Apply personRoles & businessUnits from tradeHeader/partyTradeInformation back onto parties.
        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        parties = applyPartyTradeInformation(parties, tradeHeader);

        Element swap = XmlUtils.child(trade, "swap");
        List<Element> streams = XmlUtils.children(swap, "swapStream");

        // Determine PARTY_1 = payer of FIRST stream (in document order).
        assignCounterpartyRoles(streams, ctx);

        // Build the underlier economics (taxonomy, payouts, tradeLot priceQuantities).
        SwapEconomics swapEcon = buildSwapEconomics(trade, swap, streams, ctx);

        EconomicTerms.EconomicTermsBuilder econ = swapEcon.economicTerms.toBuilder();

        // calculationAgent + ancillaryParty (trade-level)
        CalculationAgentMapper.Result ca = CalculationAgentMapper.map(trade);
        if (ca.calculationAgent() != null) econ.setCalculationAgent(ca.calculationAgent());

        // partyRole entries from determiningParty, hedgingParty, brokerPartyReference, etc.
        List<PartyRole> partyRoles = PartyRoleMapper.map(trade);

        NonTransferableProduct.NonTransferableProductBuilder ntp = swapEcon.product.toBuilder()
                .setEconomicTerms(econ.build());

        // tradeLot (priceQuantity[] in stream document order)
        TradeLot tradeLot = TradeLot.builder().setPriceQuantity(swapEcon.priceQuantities).build();

        // counterparty list
        List<Counterparty> counterparties = buildCounterparties(ctx);

        // tradeIdentifier list
        List<TradeIdentifier> identifiers = new ArrayList<>();
        Element header = XmlUtils.child(trade, "tradeHeader");
        if (header != null) {
            for (Element pti : XmlUtils.children(header, "partyTradeIdentifier")) {
                identifiers.addAll(IdentifierMapper.mapWithSplit(pti));
            }
        }

        // tradeDate
        Element tradeDateEl = XmlUtils.child(header, "tradeDate");
        FieldWithMetaDate tradeDate = null;
        if (tradeDateEl != null) {
            FieldWithMetaDate.FieldWithMetaDateBuilder tdb = FieldWithMetaDate.builder()
                    .setValue(DateMapper.parse(tradeDateEl.getTextContent().trim()));
            String tdId = tradeDateEl.getAttribute("id");
            if (tdId != null && !tdId.isEmpty()) {
                tdb.setMeta(com.rosetta.model.metafields.MetaFields.builder().setExternalKey(tdId).build());
            }
            tradeDate = tdb.build();
        }

        // contractDetails
        ContractDetails contractDetails = ContractDetailsMapper.map(XmlUtils.child(trade, "documentation"), parties, ctx);

        Trade.TradeBuilder t = Trade.builder()
                .setProduct(ntp.build())
                .addTradeLot(tradeLot);
        counterparties.forEach(t::addCounterparty);
        for (AncillaryParty ap : ca.ancillaryParties()) t.addAncillaryParty(ap);
        partyRoles.forEach(t::addPartyRole);
        identifiers.forEach(t::addTradeIdentifier);
        if (tradeDate != null) t.setTradeDate(tradeDate);
        parties.forEach(t::addParty);
        if (contractDetails != null) t.setContractDetails(contractDetails);
        for (Account a : AccountMapper.map(doc)) t.addAccount(a);

        TradeState.TradeStateBuilder tsBuilder = TradeState.builder().setTrade(t.build());
        for (cdm.event.common.TransferState transferState : TransferMapper.map(trade, swap)) {
            tsBuilder.addTransferHistory(transferState);
        }
        return tsBuilder.build();
    }

    /**
     * Result of building the "swap economics" (taxonomy, payouts, tradeLot priceQuantities) —
     * useful both for top-level swap trades and as the underlier of a swaption.
     */
    public static final class SwapEconomics {
        public final NonTransferableProduct product;          // taxonomy + economicTerms.payouts
        public final EconomicTerms economicTerms;             // econ with payout[] only
        public final List<PriceQuantity> priceQuantities;     // tradeLot.priceQuantity[]
        public SwapEconomics(NonTransferableProduct product, EconomicTerms econ, List<PriceQuantity> pqs) {
            this.product = product;
            this.economicTerms = econ;
            this.priceQuantities = pqs;
        }
    }

    /**
     * Build the swap's economic terms (taxonomy, payouts, priceQuantity[]).
     *
     * Public so {@link io.fpmlcdm.products.SwaptionMapper} can reuse it for the underlier.
     *
     * @param trade   parent {@code <trade>} (used for taxonomy)
     * @param swap    the {@code <swap>} element
     * @param streams the {@code <swapStream>} children of {@code swap}
     * @param ctx     mapping context with party role assignment
     */
    public static SwapEconomics buildSwapEconomics(
            Element trade, Element swap, List<Element> streams, MappingContext ctx) {
        // Pre-compute address-ref labels (quantity-N, price-K, observable-N, InterestRateIndex-N).
        java.util.Map<Element, StreamLabels.Labels> labels = StreamLabels.compute(streams);

        // Build payouts in document order (preserve FpML order).
        List<Payout> payouts = new ArrayList<>();
        for (Element s : streams) {
            boolean isFloating = isFloating(s);
            boolean isInflation = isInflation(s);
            payouts.add(InterestRatePayoutMapper.map(s, isFloating, isInflation, labels.get(s), ctx));
        }

        EconomicTerms.EconomicTermsBuilder econ = EconomicTerms.builder();
        for (Payout p : payouts) econ.addPayout(p);

        // terminationProvision inside swap (needed for swaption underlier too)
        cdm.product.template.TerminationProvision swapTermProv = TerminationProvisionMapper.map(swap, ctx);
        if (swapTermProv != null) econ.setTerminationProvision(swapTermProv);

        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder()
                .setEconomicTerms(econ.build());
        ProductIdentifierMapper.map(swap).forEach(ntp::addIdentifier);
        TaxonomyMapper.mapForSwap(swap).forEach(ntp::addTaxonomy);

        List<PriceQuantity> priceQuantities = QuantityMapper.map(streams, labels);
        return new SwapEconomics(ntp.build(), econ.build(), priceQuantities);
    }

    /**
     * Scans {@code <tradeHeader>/<partyTradeInformation>} for related persons and attaches them
     * to the matching Party (matched by {@code partyReference/@href}). Returns a new list of
     * Parties (built with toBuilder() so we don't mutate the originals).
     */
    public static List<Party> applyPartyTradeInformation(List<Party> parties, Element tradeHeader) {
        if (tradeHeader == null) return parties;
        List<Element> ptis = XmlUtils.children(tradeHeader, "partyTradeInformation");
        if (ptis.isEmpty()) return parties;

        java.util.Map<String, Party.PartyBuilder> builders = new java.util.LinkedHashMap<>();
        for (Party p : parties) {
            String key = p.getMeta() == null ? null : p.getMeta().getExternalKey();
            builders.put(key, p.toBuilder());
        }
        for (Element pti : ptis) {
            Element ref = XmlUtils.child(pti, "partyReference");
            if (ref == null) continue;
            String href = ref.getAttribute("href");
            Party.PartyBuilder pb = builders.get(href);
            if (pb == null) continue;
            for (Element rp : XmlUtils.children(pti, "relatedPerson")) {
                PartyMapper.attachPersonRoles(pb, rp);
            }
        }
        List<Party> out = new java.util.ArrayList<>();
        for (Party.PartyBuilder b : builders.values()) out.add(b.build());
        return out;
    }

    public static boolean isFloating(Element swapStream) {
        Element calc = XmlUtils.path(swapStream, "calculationPeriodAmount", "calculation");
        if (calc == null) return false;
        return XmlUtils.child(calc, "floatingRateCalculation") != null;
    }

    public static boolean isInflation(Element swapStream) {
        Element calc = XmlUtils.path(swapStream, "calculationPeriodAmount", "calculation");
        if (calc == null) return false;
        return XmlUtils.child(calc, "inflationRateCalculation") != null;
    }

    /**
     * PARTY_1 = payer of FIRST stream. PARTY_2 = the other party.
     * This overrides the default insertion-order role assignment in {@link PartyMapper}.
     */
    public static void assignCounterpartyRoles(List<Element> streams, MappingContext ctx) {
        if (streams.isEmpty() || ctx.partyOrder.isEmpty()) return;
        Element first = streams.get(0);
        Element payerRef = XmlUtils.child(first, "payerPartyReference");
        if (payerRef == null) return;
        String payerHref = payerRef.getAttribute("href");
        if (payerHref == null || payerHref.isEmpty()) return;

        // Re-key partyOrder: payerHref → 0, others → 1, 2 …
        Map<String, Integer> newOrder = new LinkedHashMap<>();
        newOrder.put(payerHref, 0);
        int idx = 1;
        for (String pid : ctx.partyOrder.keySet()) {
            if (!pid.equals(payerHref)) {
                newOrder.put(pid, idx++);
            }
        }
        ctx.partyOrder.clear();
        ctx.partyOrder.putAll(newOrder);
    }

    public static List<Counterparty> buildCounterparties(MappingContext ctx) {
        // Only the first two parties become counterparties (PARTY_1, PARTY_2). Additional
        // parties are emitted as partyRole entries elsewhere.
        List<Counterparty> out = new ArrayList<>();
        for (var entry : ctx.partyOrder.entrySet()) {
            int order = entry.getValue();
            if (order > 1) continue;
            CounterpartyRoleEnum role = order == 0 ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
            out.add(Counterparty.builder()
                    .setRole(role)
                    .setPartyReference(ReferenceWithMetaParty.builder()
                            .setExternalReference(entry.getKey())
                            .build())
                    .build());
        }
        return out;
    }
}
