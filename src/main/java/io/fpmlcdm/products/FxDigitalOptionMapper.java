package io.fpmlcdm.products;

import cdm.base.datetime.*;
import cdm.base.datetime.metafields.FieldWithMetaBusinessCenterEnum;
import cdm.base.staticdata.asset.common.ProductTaxonomy;
import cdm.base.staticdata.asset.common.TaxonomySourceEnum;
import cdm.base.staticdata.asset.common.TaxonomyValue;
import cdm.base.staticdata.party.*;
import cdm.event.common.*;
import com.rosetta.model.metafields.FieldWithMetaDate;
import com.rosetta.model.metafields.MetaFields;
import cdm.product.common.settlement.*;
import cdm.product.template.*;
import io.fpmlcdm.common.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps FpML {@code <fxDigitalOption>} (euro-binary, range-binary, one-touch,
 * no-touch, double-touch) into CDM TradeState.
 *
 * The FINOS reference for these products carries a minimal OptionPayout
 * (payerReceiver, settlementDate, buyerSeller, exerciseTerms) plus the
 * "OneTouch"/"EuroBinary"/etc. product-type taxonomy. No tradeLot, no strike,
 * no underlier.
 */
public class FxDigitalOptionMapper implements ProductMapper {

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);
        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        parties = SwapMapper.applyPartyTradeInformation(parties, tradeHeader);

        Element digital = XmlUtils.child(trade, "fxDigitalOption");

        Element buyerRef = XmlUtils.child(digital, "buyerPartyReference");
        Element sellerRef = XmlUtils.child(digital, "sellerPartyReference");
        String buyerHref = buyerRef != null ? buyerRef.getAttribute("href") : null;
        String sellerHref = sellerRef != null ? sellerRef.getAttribute("href") : null;
        FxOptionMapper.assignRoles(buyerHref, ctx);

        Integer buyerOrder = buyerHref != null ? ctx.partyOrder.get(buyerHref) : null;
        Integer sellerOrder = sellerHref != null ? ctx.partyOrder.get(sellerHref) : null;
        CounterpartyRoleEnum payerRole = sellerOrder != null && sellerOrder == 0
                ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
        CounterpartyRoleEnum receiverRole = buyerOrder != null && buyerOrder == 0
                ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
        CounterpartyRoleEnum buyerRole = receiverRole;
        CounterpartyRoleEnum sellerRole = payerRole;

        // Exercise: European or American
        Element europeanEx = XmlUtils.child(digital, "europeanExercise");
        Element americanEx = XmlUtils.child(digital, "americanExercise");
        boolean isEuropean = europeanEx != null;
        Element exercise = isEuropean ? europeanEx : americanEx;

        ExerciseTerms exerciseTerms = exercise == null ? null
                : buildExerciseTerms(exercise, isEuropean);

        // Settlement: valueDate
        String valueDate = exercise == null ? null : (isEuropean
                ? XmlUtils.childText(exercise, "valueDate")
                : XmlUtils.childText(exercise, "latestValueDate"));
        SettlementTerms.SettlementTermsBuilder stb = SettlementTerms.builder();
        if (valueDate != null) {
            stb.setSettlementDate(SettlementDate.builder()
                    .setValueDate(DateMapper.parse(valueDate))
                    .build());
        }

        OptionPayout.OptionPayoutBuilder opb = OptionPayout.builder()
                .setPayerReceiver(PayerReceiver.builder()
                        .setPayer(payerRole)
                        .setReceiver(receiverRole)
                        .build())
                .setSettlementTerms(stb.build())
                .setBuyerSeller(BuyerSeller.builder()
                        .setBuyer(buyerRole)
                        .setSeller(sellerRole)
                        .build());
        if (exerciseTerms != null) opb.setExerciseTerms(exerciseTerms);

        Payout payout = Payout.builder().setOptionPayout(opb.build()).build();
        EconomicTerms econ = EconomicTerms.builder().addPayout(payout).build();

        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder()
                .setEconomicTerms(econ);
        // productType → taxonomy (Other)
        for (Element pt : XmlUtils.children(digital, "productType")) {
            String value = pt.getTextContent().trim();
            String scheme = pt.getAttribute("productTypeScheme");
            com.rosetta.model.metafields.FieldWithMetaString.FieldWithMetaStringBuilder name =
                    com.rosetta.model.metafields.FieldWithMetaString.builder().setValue(value);
            if (scheme != null && !scheme.isEmpty()) {
                name.setMeta(MetaFields.builder().setScheme(scheme).build());
            }
            ntp.addTaxonomy(ProductTaxonomy.builder()
                    .setSource(TaxonomySourceEnum.OTHER)
                    .setValue(TaxonomyValue.builder().setName(name.build()).build())
                    .build());
        }

        // Build Trade
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
        List<Counterparty> counterparties = SwapMapper.buildCounterparties(ctx);

        Trade.TradeBuilder t = Trade.builder().setProduct(ntp.build());
        counterparties.forEach(t::addCounterparty);
        identifiers.forEach(t::addTradeIdentifier);
        if (tradeDate != null) t.setTradeDate(tradeDate);
        parties.forEach(t::addParty);

        TradeState.TradeStateBuilder tsBuilder = TradeState.builder().setTrade(t.build());
        // Premium → transferHistory
        for (Element premium : XmlUtils.children(digital, "premium")) {
            TransferState ts = FxOptionMapper.buildPremiumTransfer(premium);
            if (ts != null) tsBuilder.addTransferHistory(ts);
        }
        return tsBuilder.build();
    }

    private static ExerciseTerms buildExerciseTerms(Element exercise, boolean isEuropean) {
        ExerciseTerms.ExerciseTermsBuilder etb = ExerciseTerms.builder()
                .setStyle(isEuropean ? OptionExerciseStyleEnum.EUROPEAN : OptionExerciseStyleEnum.AMERICAN);

        // expirationDate (adjusted, plain date)
        String expiryDateStr = XmlUtils.childText(exercise, "expiryDate");
        if (expiryDateStr != null) {
            AdjustableOrRelativeDate ed = AdjustableOrRelativeDate.builder()
                    .setAdjustableDate(AdjustableDate.builder()
                            .setAdjustedDate(FieldWithMetaDate.builder()
                                    .setValue(DateMapper.parse(expiryDateStr))
                                    .build())
                            .build())
                    .build();
            etb.addExpirationDate(ed);
        }

        // expirationTime
        Element expiryTime = XmlUtils.child(exercise, "expiryTime");
        if (expiryTime != null) {
            String hourMinute = XmlUtils.childText(expiryTime, "hourMinuteTime");
            String bc = XmlUtils.childText(expiryTime, "businessCenter");
            if (hourMinute != null) {
                BusinessCenterTime.BusinessCenterTimeBuilder bctb = BusinessCenterTime.builder()
                        .setHourMinuteTime(LocalTime.parse(hourMinute));
                if (bc != null) {
                    try {
                        bctb.setBusinessCenter(FieldWithMetaBusinessCenterEnum.builder()
                                .setValue(BusinessCenterEnum.valueOf(bc))
                                .build());
                    } catch (IllegalArgumentException ignored) {}
                }
                etb.setExpirationTime(bctb.build());
                etb.setExpirationTimeType(ExpirationTimeTypeEnum.SPECIFIC_TIME);
            }
        }

        // commencementDate (americanExercise)
        if (!isEuropean) {
            Element commencement = XmlUtils.child(exercise, "commencementDate");
            if (commencement != null) {
                etb.setCommencementDate(DateMapper.adjustableOrRelative(commencement));
            }
        }
        return etb.build();
    }
}
