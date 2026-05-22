package io.fpmlcdm.products;

import cdm.base.staticdata.party.Counterparty;
import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.Party;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.event.common.Trade;
import cdm.event.common.TradeState;
import cdm.event.common.TradeIdentifier;
import cdm.observable.asset.PriceQuantity;
import cdm.product.template.EconomicTerms;
import cdm.product.template.NonTransferableProduct;
import cdm.product.template.Payout;
import cdm.product.template.TradeLot;
import com.rosetta.model.metafields.FieldWithMetaDate;
import io.fpmlcdm.common.IdentifierMapper;
import io.fpmlcdm.common.MappingContext;
import io.fpmlcdm.common.PartyMapper;
import io.fpmlcdm.common.QuantityMapper;
import io.fpmlcdm.common.TaxonomyMapper;
import io.fpmlcdm.common.XmlUtils;
import io.fpmlcdm.common.DateMapper;
import io.fpmlcdm.payouts.InterestRatePayoutMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;

/** Interest Rate Swap (and Basis Swap) FpML→CDM mapper. */
public class SwapMapper implements ProductMapper {

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);

        // Locate fixed & floating swapStreams
        Element swap = XmlUtils.child(trade, "swap");
        List<Element> streams = XmlUtils.children(swap, "swapStream");
        Element floatingStream = null;
        Element fixedStream = null;
        for (Element s : streams) {
            Element calc = XmlUtils.path(s, "calculationPeriodAmount", "calculation");
            if (calc == null) continue;
            if (XmlUtils.child(calc, "floatingRateCalculation") != null) {
                if (floatingStream == null) floatingStream = s;
            } else if (XmlUtils.child(calc, "fixedRateSchedule") != null) {
                if (fixedStream == null) fixedStream = s;
            }
        }

        // Build the two payouts: floating leg first, then fixed leg (matches reference order)
        List<Payout> payouts = new ArrayList<>();
        if (floatingStream != null) {
            payouts.add(InterestRatePayoutMapper.map(floatingStream, true, ctx));
        }
        if (fixedStream != null) {
            payouts.add(InterestRatePayoutMapper.map(fixedStream, false, ctx));
        }

        EconomicTerms.EconomicTermsBuilder econ = EconomicTerms.builder();
        for (Payout p : payouts) econ.addPayout(p);

        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder()
                .setEconomicTerms(econ.build());
        TaxonomyMapper.map(trade).forEach(ntp::addTaxonomy);

        // tradeLot
        List<PriceQuantity> priceQuantities = QuantityMapper.map(floatingStream, fixedStream);
        TradeLot tradeLot = TradeLot.builder().setPriceQuantity(priceQuantities).build();

        // counterparty list (party order = role assignment)
        List<Counterparty> counterparties = buildCounterparties(ctx);

        // tradeIdentifier list
        List<TradeIdentifier> identifiers = new ArrayList<>();
        Element header = XmlUtils.child(trade, "tradeHeader");
        if (header != null) {
            for (Element pti : XmlUtils.children(header, "partyTradeIdentifier")) {
                TradeIdentifier ti = IdentifierMapper.map(pti);
                if (ti != null) identifiers.add(ti);
            }
        }

        // tradeDate
        String tradeDateText = XmlUtils.pathText(header, "tradeDate");
        FieldWithMetaDate tradeDate = null;
        if (tradeDateText != null) {
            tradeDate = FieldWithMetaDate.builder().setValue(DateMapper.parse(tradeDateText)).build();
        }

        Trade.TradeBuilder t = Trade.builder()
                .setProduct(ntp.build())
                .addTradeLot(tradeLot);
        counterparties.forEach(t::addCounterparty);
        identifiers.forEach(t::addTradeIdentifier);
        if (tradeDate != null) t.setTradeDate(tradeDate);
        parties.forEach(t::addParty);

        return TradeState.builder().setTrade(t.build()).build();
    }

    private static List<Counterparty> buildCounterparties(MappingContext ctx) {
        List<Counterparty> out = new ArrayList<>();
        for (var entry : ctx.partyOrder.entrySet()) {
            String partyId = entry.getKey();
            int order = entry.getValue();
            CounterpartyRoleEnum role = order == 0 ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
            out.add(Counterparty.builder()
                    .setRole(role)
                    .setPartyReference(ReferenceWithMetaParty.builder()
                            .setExternalReference(partyId)
                            .build())
                    .build());
        }
        return out;
    }
}
