package io.fpmlcdm.products;

import cdm.base.staticdata.party.Party;
import cdm.event.common.Trade;
import cdm.event.common.TradeIdentifier;
import cdm.event.common.TradeState;
import com.rosetta.model.metafields.FieldWithMetaDate;
import com.rosetta.model.metafields.MetaFields;
import io.fpmlcdm.common.DateMapper;
import io.fpmlcdm.common.IdentifierMapper;
import io.fpmlcdm.common.MappingContext;
import io.fpmlcdm.common.PartyMapper;
import io.fpmlcdm.common.XmlUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * BulletPayment FpML→CDM mapper.
 *
 * Reference CDM JSON shows only minimal trade-level fields (tradeIdentifier,
 * tradeDate, party). No product/economicTerms/counterparty/tradeLot are emitted.
 */
public class BulletPaymentMapper implements ProductMapper {

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);

        // tradeIdentifier list
        List<TradeIdentifier> identifiers = new ArrayList<>();
        Element header = XmlUtils.child(trade, "tradeHeader");
        if (header != null) {
            for (Element pti : XmlUtils.children(header, "partyTradeIdentifier")) {
                identifiers.addAll(IdentifierMapper.mapWithSplit(pti));
            }
        }

        // tradeDate
        FieldWithMetaDate tradeDate = null;
        Element tradeDateEl = header == null ? null : XmlUtils.child(header, "tradeDate");
        if (tradeDateEl != null) {
            FieldWithMetaDate.FieldWithMetaDateBuilder tdb = FieldWithMetaDate.builder()
                    .setValue(DateMapper.parse(tradeDateEl.getTextContent().trim()));
            String tdId = tradeDateEl.getAttribute("id");
            if (tdId != null && !tdId.isEmpty()) {
                tdb.setMeta(MetaFields.builder().setExternalKey(tdId).build());
            }
            tradeDate = tdb.build();
        }

        Trade.TradeBuilder t = Trade.builder();
        identifiers.forEach(t::addTradeIdentifier);
        if (tradeDate != null) t.setTradeDate(tradeDate);
        parties.forEach(t::addParty);

        return TradeState.builder().setTrade(t.build()).build();
    }
}
