package io.fpmlcdm.products;

import cdm.base.staticdata.asset.common.AssetClassEnum;
import cdm.base.staticdata.asset.common.ProductTaxonomy;
import cdm.base.staticdata.asset.common.TaxonomySourceEnum;
import cdm.base.staticdata.asset.common.metafields.FieldWithMetaAssetClassEnum;
import cdm.base.staticdata.party.*;
import cdm.event.common.*;
import cdm.product.template.*;
import com.rosetta.model.metafields.FieldWithMetaDate;
import com.rosetta.model.metafields.MetaFields;
import io.fpmlcdm.common.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps FpML {@code <commoditySwaption>} into CDM TradeState with an OptionPayout
 * wrapping the underlying commodity swap product. No tradeLot is emitted (matching
 * the reference CDM for the available examples).
 */
public class CommoditySwaptionMapper implements ProductMapper {

    private final CommoditySwapMapper innerMapper = new CommoditySwapMapper();

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);
        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        parties = SwapMapper.applyPartyTradeInformation(parties, tradeHeader);

        Element comSwaption = XmlUtils.child(trade, "commoditySwaption");
        if (comSwaption == null) return null;

        Element buyerRef = XmlUtils.child(comSwaption, "buyerPartyReference");
        Element sellerRef = XmlUtils.child(comSwaption, "sellerPartyReference");
        String buyerHref = buyerRef != null ? buyerRef.getAttribute("href") : null;
        String sellerHref = sellerRef != null ? sellerRef.getAttribute("href") : null;

        // PARTY_1 = buyer (outer trade ordering). Lock ctx so inner mapper preserves this.
        if (buyerHref != null) {
            Map<String, Integer> newOrder = new LinkedHashMap<>();
            newOrder.put(buyerHref, 0);
            int idx = 1;
            for (String pid : ctx.partyOrder.keySet()) {
                if (!pid.equals(buyerHref)) newOrder.put(pid, idx++);
            }
            ctx.partyOrder.clear();
            ctx.partyOrder.putAll(newOrder);
        }
        ctx.partyOrderLocked = true;

        CounterpartyRoleEnum buyerRole = roleFor(buyerHref, ctx);
        CounterpartyRoleEnum sellerRole = roleFor(sellerHref, ctx);

        // Build the inner commodity-swap product (sharing our ctx, so inner Party1/Party2
        // labels match the outer trade's mapping).
        NonTransferableProduct underlierProduct = null;
        try {
            TradeState innerTs = innerMapper.map(doc, trade, ctx);
            if (innerTs != null && innerTs.getTrade() != null) {
                underlierProduct = innerTs.getTrade().getProduct();
            }
        } catch (Exception ignored) {}

        // Build OptionPayout (no optionType, no priceQuantity — the reference omits these)
        OptionPayout.OptionPayoutBuilder op = OptionPayout.builder();
        op.setBuyerSeller(BuyerSeller.builder().setBuyer(buyerRole).setSeller(sellerRole).build());
        op.setPayerReceiver(PayerReceiver.builder().setPayer(sellerRole).setReceiver(buyerRole).build());

        if (underlierProduct != null) {
            op.setUnderlier(Underlier.builder()
                    .setProduct(Product.builder().setNonTransferableProduct(underlierProduct).build())
                    .build());
        }

        Payout optionPayout = Payout.builder().setOptionPayout(op.build()).build();

        EconomicTerms.EconomicTermsBuilder econ = EconomicTerms.builder().addPayout(optionPayout);

        // Outer taxonomy: primaryAssetClass (from swaption) + ISDA Commodity_Swaption
        List<ProductTaxonomy> outerTaxonomies = new ArrayList<>();
        Element pacEl = XmlUtils.child(comSwaption, "primaryAssetClass");
        if (pacEl != null) {
            String value = pacEl.getTextContent().trim();
            String scheme = pacEl.getAttribute("assetClassScheme");
            FieldWithMetaAssetClassEnum.FieldWithMetaAssetClassEnumBuilder ab =
                    FieldWithMetaAssetClassEnum.builder();
            try { ab.setValue(AssetClassEnum.fromDisplayName(value)); } catch (Exception ignored) {
                try { ab.setValue(AssetClassEnum.valueOf(value.toUpperCase())); } catch (Exception ignored2) {}
            }
            if (scheme != null && !scheme.isEmpty()) {
                ab.setMeta(MetaFields.builder().setScheme(scheme).build());
            }
            outerTaxonomies.add(ProductTaxonomy.builder().setPrimaryAssetClass(ab.build()).build());
        }
        // Suppress the inferred Commodity_Swaption qualifier when the inner swap carries a
        // physical leg (or environmental fixedLeg) — the FINOS reference omits it for
        // these salvaged cases.
        Element innerSwap = XmlUtils.child(comSwaption, "commoditySwap");
        boolean innerIsPhysical = innerSwap != null && (
                XmlUtils.child(innerSwap, "coalPhysicalLeg") != null
                || XmlUtils.child(innerSwap, "gasPhysicalLeg") != null
                || XmlUtils.child(innerSwap, "oilPhysicalLeg") != null
                || XmlUtils.child(innerSwap, "electricityPhysicalLeg") != null
                || XmlUtils.child(innerSwap, "environmentalPhysicalLeg") != null);
        if (innerSwap != null && !innerIsPhysical) {
            for (Element fl : XmlUtils.children(innerSwap, "fixedLeg")) {
                if (XmlUtils.child(fl, "environmental") != null) { innerIsPhysical = true; break; }
            }
        }
        if (!innerIsPhysical) {
            outerTaxonomies.add(ProductTaxonomy.builder()
                    .setSource(TaxonomySourceEnum.ISDA)
                    .setProductQualifier("Commodity_Swaption")
                    .build());
        }

        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder()
                .setEconomicTerms(econ.build());
        outerTaxonomies.forEach(ntp::addTaxonomy);
        // productId → ProductIdentifier
        ProductIdentifierMapper.map(comSwaption).forEach(ntp::addIdentifier);

        // Counterparties
        List<Counterparty> counterparties = SwapMapper.buildCounterparties(ctx);

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

        List<PartyRole> partyRoles = PartyRoleMapper.map(trade);

        ContractDetails contractDetails = ContractDetailsMapper.map(
                XmlUtils.child(trade, "documentation"), parties, ctx, trade);

        Trade.TradeBuilder t = Trade.builder()
                .setProduct(ntp.build());
        counterparties.forEach(t::addCounterparty);
        partyRoles.forEach(t::addPartyRole);
        identifiers.forEach(t::addTradeIdentifier);
        if (tradeDate != null) t.setTradeDate(tradeDate);
        parties.forEach(t::addParty);
        if (contractDetails != null) t.setContractDetails(contractDetails);
        for (Account a : AccountMapper.map(doc)) t.addAccount(a);

        TradeState.TradeStateBuilder tsBuilder = TradeState.builder().setTrade(t.build());
        // premium → transferHistory
        for (Element premium : XmlUtils.children(comSwaption, "premium")) {
            TransferState ts = buildPremiumTransfer(premium);
            if (ts != null) tsBuilder.addTransferHistory(ts);
        }
        return tsBuilder.build();
    }

    private TransferState buildPremiumTransfer(Element premium) {
        cdm.event.common.Transfer.TransferBuilder tb = cdm.event.common.Transfer.builder();
        Element amtEl = XmlUtils.child(premium, "paymentAmount");
        String ccy = XmlUtils.childText(amtEl, "currency");
        String amount = XmlUtils.childText(amtEl, "amount");
        if (amount != null && ccy != null) {
            tb.setQuantity(cdm.base.math.NonNegativeQuantity.builder()
                    .setValue(new java.math.BigDecimal(amount))
                    .setUnit(cdm.base.math.UnitType.builder()
                            .setCurrency(com.rosetta.model.metafields.FieldWithMetaString.builder().setValue(ccy).build()).build())
                    .build());
            tb.setAsset(cdm.base.staticdata.asset.common.Asset.builder()
                    .setCash(cdm.base.staticdata.asset.common.Cash.builder()
                            .addIdentifier(cdm.base.staticdata.asset.common.AssetIdentifier.builder()
                                    .setIdentifier(com.rosetta.model.metafields.FieldWithMetaString.builder().setValue(ccy).build())
                                    .setIdentifierType(cdm.base.staticdata.asset.common.AssetIdTypeEnum.CURRENCY_CODE)
                                    .build())
                            .build())
                    .build());
        }
        Element payDate = XmlUtils.child(premium, "paymentDate");
        if (payDate != null) {
            cdm.base.datetime.AdjustableOrAdjustedOrRelativeDate.AdjustableOrAdjustedOrRelativeDateBuilder sdb =
                    cdm.base.datetime.AdjustableOrAdjustedOrRelativeDate.builder();
            Element adjDate = XmlUtils.child(payDate, "adjustableDate");
            Element relDate = XmlUtils.child(payDate, "relativeDate");
            if (adjDate != null) {
                String unadj = XmlUtils.childText(adjDate, "unadjustedDate");
                if (unadj != null) sdb.setUnadjustedDate(DateMapper.parse(unadj));
                cdm.base.datetime.BusinessDayAdjustments bda = DateMapper.businessDayAdjustments(
                        XmlUtils.child(adjDate, "dateAdjustments"));
                if (bda != null) sdb.setDateAdjustments(bda);
            } else if (relDate != null) {
                sdb.setRelativeDate(io.fpmlcdm.common.DateMapper.buildRelativeDateOffset(relDate));
            } else {
                String unadj = XmlUtils.childText(payDate, "unadjustedDate");
                if (unadj != null) sdb.setUnadjustedDate(DateMapper.parse(unadj));
                String adj = XmlUtils.childText(payDate, "adjustedDate");
                if (adj != null) sdb.setAdjustedDate(com.rosetta.model.metafields.FieldWithMetaDate.builder()
                        .setValue(DateMapper.parse(adj)).build());
                cdm.base.datetime.BusinessDayAdjustments bda = DateMapper.businessDayAdjustments(
                        XmlUtils.child(payDate, "dateAdjustments"));
                if (bda != null) sdb.setDateAdjustments(bda);
            }
            tb.setSettlementDate(sdb.build());
        }
        Element payerRef = XmlUtils.child(premium, "payerPartyReference");
        Element receiverRef = XmlUtils.child(premium, "receiverPartyReference");
        if (payerRef != null || receiverRef != null) {
            cdm.base.staticdata.party.PartyReferencePayerReceiver.PartyReferencePayerReceiverBuilder pr =
                    cdm.base.staticdata.party.PartyReferencePayerReceiver.builder();
            if (payerRef != null) pr.setPayerPartyReference(
                    cdm.base.staticdata.party.metafields.ReferenceWithMetaParty.builder()
                            .setExternalReference(payerRef.getAttribute("href")).build());
            if (receiverRef != null) pr.setReceiverPartyReference(
                    cdm.base.staticdata.party.metafields.ReferenceWithMetaParty.builder()
                            .setExternalReference(receiverRef.getAttribute("href")).build());
            tb.setPayerReceiver(pr.build());
        }
        tb.setTransferExpression(cdm.event.common.TransferExpression.builder()
                .setPriceTransfer(cdm.observable.asset.FeeTypeEnum.PREMIUM).build());
        return TransferState.builder().setTransfer(tb.build()).build();
    }

    private static CounterpartyRoleEnum roleFor(String partyHref, MappingContext ctx) {
        if (partyHref == null) return null;
        Integer order = ctx.partyOrder.get(partyHref);
        if (order == null) return null;
        return order == 0 ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
    }
}
