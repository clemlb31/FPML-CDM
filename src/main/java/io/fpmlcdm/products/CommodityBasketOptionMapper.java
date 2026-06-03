package io.fpmlcdm.products;

import cdm.base.math.NonNegativeQuantity;
import cdm.base.math.UnitType;
import cdm.base.staticdata.asset.common.*;
import cdm.base.staticdata.party.*;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.event.common.*;
import cdm.observable.asset.FeeTypeEnum;
import cdm.product.common.settlement.*;
import com.rosetta.model.metafields.FieldWithMetaDate;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
import io.fpmlcdm.common.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal mapper for FpML {@code <commodityBasketOption>}. The reference CDM
 * for the available example carries only tradeIdentifier, tradeDate, parties
 * and an equityPremium-style transferHistory entry.
 */
public class CommodityBasketOptionMapper implements ProductMapper {

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);
        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        parties = SwapMapper.applyPartyTradeInformation(parties, tradeHeader);

        Element cbo = XmlUtils.child(trade, "commodityBasketOption");
        if (cbo == null) return null;

        // Trade identifiers
        List<TradeIdentifier> identifiers = new ArrayList<>();
        if (tradeHeader != null) {
            for (Element pti : XmlUtils.children(tradeHeader, "partyTradeIdentifier")) {
                identifiers.addAll(IdentifierMapper.mapWithSplit(pti));
            }
        }

        // Trade date
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

        Trade.TradeBuilder t = Trade.builder();
        identifiers.forEach(t::addTradeIdentifier);
        if (tradeDate != null) t.setTradeDate(tradeDate);
        parties.forEach(t::addParty);
        for (Account a : AccountMapper.map(doc)) t.addAccount(a);

        TradeState.TradeStateBuilder tsBuilder = TradeState.builder().setTrade(t.build());

        for (Element premium : XmlUtils.children(cbo, "premium")) {
            TransferState ts = buildPremiumTransfer(premium);
            if (ts != null) tsBuilder.addTransferHistory(ts);
        }

        return tsBuilder.build();
    }

    private TransferState buildPremiumTransfer(Element premium) {
        Transfer.TransferBuilder tb = Transfer.builder();
        Element amtEl = XmlUtils.child(premium, "paymentAmount");
        String ccy = XmlUtils.childText(amtEl, "currency");
        String amount = XmlUtils.childText(amtEl, "amount");
        if (amount != null && ccy != null) {
            tb.setQuantity(NonNegativeQuantity.builder()
                    .setValue(new BigDecimal(amount))
                    .setUnit(UnitType.builder()
                            .setCurrency(FieldWithMetaString.builder().setValue(ccy).build()).build())
                    .build());
            tb.setAsset(Asset.builder()
                    .setCash(Cash.builder()
                            .addIdentifier(AssetIdentifier.builder()
                                    .setIdentifier(FieldWithMetaString.builder().setValue(ccy).build())
                                    .setIdentifierType(AssetIdTypeEnum.CURRENCY_CODE)
                                    .build())
                            .build())
                    .build());
        }
        Element payDate = XmlUtils.child(premium, "paymentDate");
        if (payDate != null) {
            cdm.base.datetime.AdjustableOrAdjustedOrRelativeDate.AdjustableOrAdjustedOrRelativeDateBuilder sdb =
                    cdm.base.datetime.AdjustableOrAdjustedOrRelativeDate.builder();
            Element adjDate = XmlUtils.child(payDate, "adjustableDate");
            if (adjDate != null) {
                String unadj = XmlUtils.childText(adjDate, "unadjustedDate");
                if (unadj != null) sdb.setUnadjustedDate(DateMapper.parse(unadj));
                cdm.base.datetime.BusinessDayAdjustments bda = DateMapper.businessDayAdjustments(
                        XmlUtils.child(adjDate, "dateAdjustments"));
                if (bda != null) sdb.setDateAdjustments(bda);
            }
            tb.setSettlementDate(sdb.build());
        }
        Element payerRef = XmlUtils.child(premium, "payerPartyReference");
        Element receiverRef = XmlUtils.child(premium, "receiverPartyReference");
        if (payerRef != null || receiverRef != null) {
            PartyReferencePayerReceiver.PartyReferencePayerReceiverBuilder pr =
                    PartyReferencePayerReceiver.builder();
            if (payerRef != null) pr.setPayerPartyReference(ReferenceWithMetaParty.builder()
                    .setExternalReference(payerRef.getAttribute("href")).build());
            if (receiverRef != null) pr.setReceiverPartyReference(ReferenceWithMetaParty.builder()
                    .setExternalReference(receiverRef.getAttribute("href")).build());
            tb.setPayerReceiver(pr.build());
        }
        tb.setTransferExpression(TransferExpression.builder()
                .setPriceTransfer(FeeTypeEnum.PREMIUM).build());
        return TransferState.builder().setTransfer(tb.build()).build();
    }
}
