package io.fpmlcdm.products;

import cdm.base.staticdata.asset.common.ProductTaxonomy;
import cdm.base.staticdata.asset.common.TaxonomySourceEnum;
import cdm.base.staticdata.party.Counterparty;
import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.Party;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.event.common.ContractDetails;
import cdm.event.common.Trade;
import cdm.event.common.TradeIdentifier;
import cdm.event.common.TradeState;
import cdm.observable.asset.PriceQuantity;
import cdm.product.template.EconomicTerms;
import cdm.product.template.NonTransferableProduct;
import cdm.product.template.Payout;
import cdm.product.template.TradeLot;
import com.rosetta.model.metafields.FieldWithMetaDate;
import com.rosetta.model.metafields.MetaFields;
import io.fpmlcdm.common.ContractDetailsMapper;
import io.fpmlcdm.common.DateMapper;
import io.fpmlcdm.common.IdentifierMapper;
import io.fpmlcdm.common.MappingContext;
import io.fpmlcdm.common.PartyMapper;
import io.fpmlcdm.common.QuantityMapper;
import io.fpmlcdm.common.StreamLabels;
import io.fpmlcdm.common.XmlUtils;
import io.fpmlcdm.payouts.InterestRatePayoutMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cap / Floor / Collar FpML→CDM mapper.
 *
 * The FpML {@code <capFloor>} has 1 or more {@code <capFloorStream>} (1 = cap or floor,
 * 2 = collar). Each capFloorStream is essentially a floating-leg swapStream that also carries
 * {@code <capRateSchedule>} / {@code <floorRateSchedule>} inside its floatingRateCalculation.
 */
public class CapFloorMapper implements ProductMapper {

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);

        Element capFloor = XmlUtils.child(trade, "capFloor");
        List<Element> streams = XmlUtils.children(capFloor, "capFloorStream");

        // PARTY_1 = payer of first stream
        assignCounterpartyRoles(streams, ctx);

        Map<Element, StreamLabels.Labels> labels = StreamLabels.compute(streams);

        List<Payout> payouts = new ArrayList<>();
        for (Element s : streams) {
            payouts.add(InterestRatePayoutMapper.map(s, true, false, labels.get(s), ctx));
        }

        EconomicTerms econ = EconomicTerms.builder()
                .setPayout(payouts)
                .build();

        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder()
                .setEconomicTerms(econ);
        // Simple taxonomy: Cap, Floor, or Collar
        String qualifier = qualifier(streams);
        ntp.addTaxonomy(ProductTaxonomy.builder()
                .setSource(TaxonomySourceEnum.ISDA)
                .setProductQualifier(qualifier)
                .build());

        List<PriceQuantity> priceQuantities = QuantityMapper.map(streams, labels);
        TradeLot tradeLot = TradeLot.builder().setPriceQuantity(priceQuantities).build();

        List<Counterparty> counterparties = buildCounterparties(ctx);

        List<TradeIdentifier> identifiers = new ArrayList<>();
        Element header = XmlUtils.child(trade, "tradeHeader");
        if (header != null) {
            for (Element pti : XmlUtils.children(header, "partyTradeIdentifier")) {
                identifiers.addAll(IdentifierMapper.mapWithSplit(pti));
            }
        }

        Element tradeDateEl = XmlUtils.child(header, "tradeDate");
        FieldWithMetaDate tradeDate = null;
        if (tradeDateEl != null) {
            FieldWithMetaDate.FieldWithMetaDateBuilder tdb = FieldWithMetaDate.builder()
                    .setValue(DateMapper.parse(tradeDateEl.getTextContent().trim()));
            String tdId = tradeDateEl.getAttribute("id");
            if (tdId != null && !tdId.isEmpty()) {
                tdb.setMeta(MetaFields.builder().setExternalKey(tdId).build());
            }
            tradeDate = tdb.build();
        }

        ContractDetails contractDetails = ContractDetailsMapper.map(
                XmlUtils.child(trade, "documentation"), parties, ctx);

        Trade.TradeBuilder t = Trade.builder()
                .setProduct(ntp.build())
                .addTradeLot(tradeLot);
        counterparties.forEach(t::addCounterparty);
        identifiers.forEach(t::addTradeIdentifier);
        if (tradeDate != null) t.setTradeDate(tradeDate);
        parties.forEach(t::addParty);
        if (contractDetails != null) t.setContractDetails(contractDetails);

        return TradeState.builder().setTrade(t.build()).build();
    }

    private static String qualifier(List<Element> streams) {
        // Reference dataset consistently uses just "InterestRate_CapFloor" for cap, floor, collar.
        return "InterestRate_CapFloor";
    }

    private static void assignCounterpartyRoles(List<Element> streams, MappingContext ctx) {
        if (streams.isEmpty() || ctx.partyOrder.isEmpty()) return;
        Element first = streams.get(0);
        Element payerRef = XmlUtils.child(first, "payerPartyReference");
        if (payerRef == null) return;
        String payerHref = payerRef.getAttribute("href");
        if (payerHref == null || payerHref.isEmpty()) return;
        Map<String, Integer> newOrder = new LinkedHashMap<>();
        newOrder.put(payerHref, 0);
        int idx = 1;
        for (String pid : ctx.partyOrder.keySet()) {
            if (!pid.equals(payerHref)) newOrder.put(pid, idx++);
        }
        ctx.partyOrder.clear();
        ctx.partyOrder.putAll(newOrder);
    }

    private static List<Counterparty> buildCounterparties(MappingContext ctx) {
        List<Counterparty> out = new ArrayList<>();
        for (var entry : ctx.partyOrder.entrySet()) {
            int order = entry.getValue();
            if (order > 1) continue;
            CounterpartyRoleEnum role = order == 0 ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
            out.add(Counterparty.builder()
                    .setRole(role)
                    .setPartyReference(ReferenceWithMetaParty.builder()
                            .setExternalReference(entry.getKey()).build())
                    .build());
        }
        return out;
    }
}
