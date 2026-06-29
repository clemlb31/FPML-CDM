package io.fpmlcdm.fpml.cdm.products;

import cdm.base.staticdata.party.AncillaryParty;
import cdm.base.staticdata.party.Party;
import cdm.event.common.Trade;
import cdm.event.common.TradeIdentifier;
import cdm.event.common.TradeState;
import cdm.product.template.EconomicTerms;
import cdm.product.template.NonTransferableProduct;
import com.rosetta.model.metafields.FieldWithMetaDate;
import com.rosetta.model.metafields.MetaFields;
import io.fpmlcdm.fpml.cdm.common.CalculationAgentMapper;
import io.fpmlcdm.fpml.cdm.common.DateMapper;
import io.fpmlcdm.fpml.cdm.common.IdentifierMapper;
import io.fpmlcdm.fpml.cdm.common.MappingContext;
import io.fpmlcdm.fpml.cdm.common.PartyMapper;
import io.fpmlcdm.fpml.cdm.common.XmlUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps FpML {@code <fxForwardVolatilityAgreement>} into CDM TradeState.
 *
 * The FINOS reference for this exotic FX product is a stripped-down trade carrying
 * just the calculation-agent role plus standard trade-level metadata (identifiers,
 * tradeDate, parties). No payouts or tradeLot are emitted.
 */
public class FxForwardVolatilityAgreementMapper implements ProductMapper {

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);
        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        parties = SwapMapper.applyPartyTradeInformation(parties, tradeHeader);

        // Calculation agent â†’ economicTerms.calculationAgent + trade.ancillaryParty.
        CalculationAgentMapper.Result ca = CalculationAgentMapper.map(trade);

        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder();
        if (ca.calculationAgent() != null) {
            ntp.setEconomicTerms(EconomicTerms.builder()
                    .setCalculationAgent(ca.calculationAgent())
                    .build());
        }

        List<TradeIdentifier> identifiers = new ArrayList<>();
        if (tradeHeader != null) {
            for (Element pti : XmlUtils.children(tradeHeader, "partyTradeIdentifier")) {
                identifiers.addAll(IdentifierMapper.mapWithSplit(pti));
            }
        }
        FieldWithMetaDate tradeDate = null;
        if (tradeHeader != null) {
            Element tradeDateEl = XmlUtils.child(tradeHeader, "tradeDate");
            if (tradeDateEl != null) {
                FieldWithMetaDate.FieldWithMetaDateBuilder tdb = FieldWithMetaDate.builder()
                        .setValue(DateMapper.parse(tradeDateEl.getTextContent().trim()));
                String tdId = tradeDateEl.getAttribute("id");
                if (tdId != null && !tdId.isEmpty()) {
                    tdb.setMeta(MetaFields.builder().setExternalKey(tdId).build());
                }
                tradeDate = tdb.build();
            }
        }

        Trade.TradeBuilder t = Trade.builder().setProduct(ntp.build());
        for (AncillaryParty ap : ca.ancillaryParties()) t.addAncillaryParty(ap);
        identifiers.forEach(t::addTradeIdentifier);
        if (tradeDate != null) t.setTradeDate(tradeDate);
        parties.forEach(t::addParty);

        return TradeState.builder().setTrade(t.build()).build();
    }
}
