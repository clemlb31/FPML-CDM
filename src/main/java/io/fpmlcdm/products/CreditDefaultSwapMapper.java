package io.fpmlcdm.products;

import cdm.base.math.NonNegativeQuantitySchedule;
import cdm.base.math.UnitType;
import cdm.base.math.metafields.FieldWithMetaNonNegativeQuantitySchedule;
import cdm.base.staticdata.asset.common.AssetClassEnum;
import cdm.base.staticdata.party.*;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.event.common.*;
import cdm.observable.asset.CreditIndex;
import cdm.observable.asset.PriceQuantity;
import cdm.product.asset.CreditDefaultPayout;
import cdm.product.asset.GeneralTerms;
import cdm.product.asset.ReferenceInformation;
import cdm.product.asset.ReferenceObligation;
import cdm.product.asset.CreditSeniorityEnum;
import cdm.product.asset.ProtectionTerms;
import cdm.product.common.settlement.ResolvablePriceQuantity;
import cdm.product.template.*;
import com.rosetta.model.lib.meta.Key;
import com.rosetta.model.lib.meta.Reference;
import com.rosetta.model.metafields.FieldWithMetaDate;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
import io.fpmlcdm.common.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class CreditDefaultSwapMapper implements ProductMapper {

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);

        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        Element cds = XmlUtils.child(trade, "creditDefaultSwap");
        Element generalTerms = XmlUtils.child(cds, "generalTerms");

        // PARTY_1 = seller of protection
        String sellerHref = null;
        Element sellerRef = XmlUtils.child(generalTerms, "sellerPartyReference");
        if (sellerRef != null) sellerHref = sellerRef.getAttribute("href");
        assignRoles(sellerHref, ctx);

        // economicTerms
        EconomicTerms.EconomicTermsBuilder econ = EconomicTerms.builder();

        // terminationDate from scheduledTerminationDate
        Element schedTerm = XmlUtils.child(generalTerms, "scheduledTerminationDate");
        if (schedTerm != null) {
            econ.setTerminationDate(DateMapper.adjustableOrRelative(schedTerm));
        }

        // effectiveDate
        Element effectiveDate = XmlUtils.child(generalTerms, "effectiveDate");
        if (effectiveDate != null) {
            econ.setEffectiveDate(DateMapper.adjustableOrRelative(effectiveDate));
        }

        // CreditDefaultPayout
        CreditDefaultPayout.CreditDefaultPayoutBuilder cdpBuilder = CreditDefaultPayout.builder();

        // payerReceiver: buyer = payer, seller = receiver
        String buyerHref = null;
        Element buyerRef = XmlUtils.child(generalTerms, "buyerPartyReference");
        if (buyerRef != null) buyerHref = buyerRef.getAttribute("href");
        // CreditDefaultPayout: payer = seller (pays contingent), receiver = buyer
        Integer sellerOrder = sellerHref != null ? ctx.partyOrder.get(sellerHref) : null;
        CounterpartyRoleEnum sellerRole = (sellerOrder != null && sellerOrder == 0)
                ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
        CounterpartyRoleEnum buyerRole = sellerRole == CounterpartyRoleEnum.PARTY_1
                ? CounterpartyRoleEnum.PARTY_2 : CounterpartyRoleEnum.PARTY_1;
        cdpBuilder.setPayerReceiver(PayerReceiver.builder()
                .setPayer(sellerRole).setReceiver(buyerRole).build());

        // priceQuantity address ref
        cdpBuilder.setPriceQuantity(ResolvablePriceQuantity.builder()
                .setQuantitySchedule(cdm.base.math.metafields.ReferenceWithMetaNonNegativeQuantitySchedule.builder()
                        .setReference(Reference.builder().setScope("DOCUMENT").setReference("quantity-1").build())
                        .build())
                .build());

        // generalTerms
        GeneralTerms.GeneralTermsBuilder gtBuilder = GeneralTerms.builder();
        Element indexRefInfo = XmlUtils.child(generalTerms, "indexReferenceInformation");
        Element refInfo = XmlUtils.child(generalTerms, "referenceInformation");

        if (indexRefInfo != null) {
            gtBuilder.setIndexReferenceInformation(buildCreditIndex(indexRefInfo));
        }
        if (refInfo != null) {
            gtBuilder.setReferenceInformation(buildReferenceInformation(refInfo));
        }
        cdpBuilder.setGeneralTerms(gtBuilder.build());

        Payout payout = Payout.builder().setCreditDefaultPayout(cdpBuilder.build()).build();
        econ.addPayout(payout);

        // Product
        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder()
                .setEconomicTerms(econ.build());

        // Taxonomy
        String qualifier = indexRefInfo != null ? "CreditDefaultSwap_Index" : "CreditDefaultSwap_SingleName";
        ntp.addTaxonomy(cdm.base.staticdata.asset.common.ProductTaxonomy.builder()
                .setSource(cdm.base.staticdata.asset.common.TaxonomySourceEnum.ISDA)
                .setProductQualifier(qualifier)
                .build());

        // TradeLot — quantity from protectionTerms/calculationAmount
        Element protTerms = XmlUtils.child(cds, "protectionTerms");
        Element calcAmount = XmlUtils.path(protTerms, "calculationAmount");
        List<PriceQuantity> pqs = new ArrayList<>();
        if (calcAmount != null) {
            String ccy = XmlUtils.childText(calcAmount, "currency");
            String amt = XmlUtils.childText(calcAmount, "amount");
            NonNegativeQuantitySchedule qty = NonNegativeQuantitySchedule.builder()
                    .setValue(new BigDecimal(amt))
                    .setUnit(UnitType.builder().setCurrency(FieldWithMetaString.builder().setValue(ccy).build()).build())
                    .build();
            pqs.add(PriceQuantity.builder()
                    .addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder()
                            .setValue(qty)
                            .setMeta(MetaFields.builder().addKey(
                                    Key.builder().setScope("DOCUMENT").setKeyValue("quantity-1").build()).build())
                            .build())
                    .build());
        }
        TradeLot tradeLot = TradeLot.builder().setPriceQuantity(pqs).build();

        // Counterparties
        List<Counterparty> counterparties = new ArrayList<>();
        ctx.partyOrder.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByValue())
                .forEach(e -> counterparties.add(Counterparty.builder()
                        .setRole(e.getValue() == 0 ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2)
                        .setPartyReference(ReferenceWithMetaParty.builder()
                                .setExternalReference(e.getKey()).build())
                        .build()));

        // Trade identifiers
        List<TradeIdentifier> identifiers = new ArrayList<>();
        if (tradeHeader != null) {
            for (Element pti : XmlUtils.children(tradeHeader, "partyTradeIdentifier")) {
                identifiers.addAll(IdentifierMapper.mapWithSplit(pti));
            }
        }

        // Trade date
        FieldWithMetaDate tradeDate = null;
        String tdText = XmlUtils.pathText(tradeHeader, "tradeDate");
        if (tdText != null) {
            tradeDate = FieldWithMetaDate.builder().setValue(DateMapper.parse(tdText)).build();
        }

        // Contract details
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
        for (Account a : AccountMapper.map(doc)) t.addAccount(a);

        // transferHistory from feeLeg/initialPayment
        TradeState.TradeStateBuilder tsb = TradeState.builder().setTrade(t.build());
        for (TransferState ts : TransferMapper.map(trade, cds)) {
            tsb.addTransferHistory(ts);
        }
        return tsb.build();
    }

    private CreditIndex buildCreditIndex(Element el) {
        CreditIndex.CreditIndexBuilder b = CreditIndex.builder();
        String name = XmlUtils.childText(el, "indexName");
        if (name != null) b.setName(FieldWithMetaString.builder().setValue(name).build());
        b.setAssetClass(AssetClassEnum.CREDIT);
        String series = XmlUtils.childText(el, "indexSeries");
        if (series != null) b.setIndexSeries(Integer.parseInt(series));
        String version = XmlUtils.childText(el, "indexAnnexVersion");
        if (version != null) b.setIndexAnnexVersion(Integer.parseInt(version));

        for (Element excl : XmlUtils.children(el, "excludedReferenceEntity")) {
            b.addExcludedReferenceEntity(buildReferenceInformation(excl));
        }

        String seniority = XmlUtils.childText(el, "seniority");
        if (seniority != null) {
            try { b.setSeniority(CreditSeniorityEnum.fromDisplayName(seniority)); }
            catch (Exception ignored) {}
        }
        return b.build();
    }

    private ReferenceInformation buildReferenceInformation(Element el) {
        ReferenceInformation.ReferenceInformationBuilder b = ReferenceInformation.builder();
        Element refEntity = XmlUtils.child(el, "referenceEntity");
        if (refEntity != null) {
            LegalEntity.LegalEntityBuilder le = LegalEntity.builder();
            String eName = XmlUtils.childText(refEntity, "entityName");
            if (eName != null) le.setName(FieldWithMetaString.builder().setValue(eName).build());
            Element entityId = XmlUtils.child(refEntity, "entityId");
            if (entityId != null) {
                le.addEntityId(FieldWithMetaString.builder()
                        .setValue(entityId.getTextContent().trim())
                        .setMeta(MetaFields.builder()
                                .setScheme(entityId.getAttribute("entityIdScheme")).build())
                        .build());
            }
            b.setReferenceEntity(le.build());
        }
        // simple referenceEntity without nesting (e.g. excludedReferenceEntity has entityName directly)
        String entityName = XmlUtils.childText(el, "entityName");
        if (entityName != null && refEntity == null) {
            b.setReferenceEntity(LegalEntity.builder()
                    .setName(FieldWithMetaString.builder().setValue(entityName).build())
                    .build());
        }

        for (Element ro : XmlUtils.children(el, "referenceObligation")) {
            ReferenceObligation.ReferenceObligationBuilder rob = ReferenceObligation.builder();
            Element bond = XmlUtils.child(ro, "bond");
            if (bond != null) {
                String bondId = XmlUtils.childText(bond, "instrumentId");
                // TODO: map bond details
            }
            b.addReferenceObligation(rob.build());
        }

        String noRefObl = XmlUtils.childText(el, "noReferenceObligation");
        if ("true".equals(noRefObl)) b.setNoReferenceObligation(true);

        return b.build();
    }

    private void assignRoles(String sellerHref, MappingContext ctx) {
        ctx.partyOrder.clear();
        for (String id : new ArrayList<>(ctx.parties.keySet())) {
            if (id.equals(sellerHref)) {
                ctx.partyOrder.put(id, 0); // PARTY_1 = seller
            } else {
                ctx.partyOrder.put(id, 1);
            }
        }
    }
}
