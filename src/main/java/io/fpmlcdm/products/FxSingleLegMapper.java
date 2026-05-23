package io.fpmlcdm.products;

import cdm.base.staticdata.asset.common.AssetClassEnum;
import cdm.base.staticdata.asset.common.AssetIdentifier;
import cdm.base.staticdata.asset.common.AssetIdTypeEnum;
import cdm.base.staticdata.asset.common.Cash;
import cdm.base.staticdata.party.Account;
import cdm.base.staticdata.party.AncillaryParty;
import cdm.base.staticdata.party.Counterparty;
import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.Party;
import cdm.base.staticdata.party.PartyRole;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.event.common.ContractDetails;
import cdm.event.common.Trade;
import cdm.event.common.TradeIdentifier;
import cdm.event.common.TradeState;
import cdm.observable.asset.Observable;
import cdm.observable.asset.PriceQuantity;
import cdm.observable.asset.PriceSchedule;
import cdm.observable.asset.PriceTypeEnum;
import cdm.base.math.NonNegativeQuantitySchedule;
import cdm.base.math.UnitType;
import cdm.base.staticdata.asset.common.AssetType;
import cdm.base.staticdata.asset.common.ProductTaxonomy;
import cdm.product.common.settlement.ResolvablePriceQuantity;
import cdm.product.common.settlement.SettlementDate;
import cdm.product.common.settlement.SettlementTerms;
import cdm.product.common.settlement.SettlementTypeEnum;
import cdm.product.template.EconomicTerms;
import cdm.product.template.NonTransferableProduct;
import cdm.product.template.Payout;
import cdm.product.template.SettlementPayout;
import cdm.product.template.TradeLot;
import cdm.product.template.Underlier;
import com.rosetta.model.lib.meta.Key;
import com.rosetta.model.lib.meta.Reference;
import com.rosetta.model.lib.records.Date;
import com.rosetta.model.metafields.FieldWithMetaDate;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
import io.fpmlcdm.common.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class FxSingleLegMapper implements ProductMapper {

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);

        Element fxSingleLeg = XmlUtils.child(trade, "fxSingleLeg");

        Element ec1 = XmlUtils.child(fxSingleLeg, "exchangedCurrency1");
        Element ec2 = XmlUtils.child(fxSingleLeg, "exchangedCurrency2");

        String ccy1 = XmlUtils.pathText(ec1, "paymentAmount", "currency");
        String ccy2 = XmlUtils.pathText(ec2, "paymentAmount", "currency");
        String amt1 = XmlUtils.pathText(ec1, "paymentAmount", "amount");
        String amt2 = XmlUtils.pathText(ec2, "paymentAmount", "amount");

        String ec1Payer = ec1 != null ? XmlUtils.child(ec1, "payerPartyReference").getAttribute("href") : null;

        String valueDate = XmlUtils.childText(fxSingleLeg, "valueDate");
        Element exchangeRate = XmlUtils.child(fxSingleLeg, "exchangeRate");
        String rate = XmlUtils.childText(exchangeRate, "rate");
        Element qcp = XmlUtils.child(exchangeRate, "quotedCurrencyPair");
        String quoteCcy1 = XmlUtils.childText(qcp, "currency1");
        String quoteCcy2 = XmlUtils.childText(qcp, "currency2");
        String quoteBasis = XmlUtils.childText(qcp, "quoteBasis");

        // PARTY_1 = payer of exchangedCurrency1
        assignRoles(ec1Payer, ctx);

        // Build SettlementPayout
        SettlementPayout sp = SettlementPayout.builder()
                .setPayerReceiver(cdm.base.staticdata.party.PayerReceiver.builder()
                        .setPayer(CounterpartyRoleEnum.PARTY_1)
                        .setReceiver(CounterpartyRoleEnum.PARTY_2)
                        .build())
                .setPriceQuantity(ResolvablePriceQuantity.builder()
                        .setQuantitySchedule(cdm.base.math.metafields.ReferenceWithMetaNonNegativeQuantitySchedule.builder()
                                .setReference(Reference.builder().setScope("DOCUMENT").setReference("quantity-1").build())
                                .build())
                        .addPriceSchedule(cdm.observable.asset.metafields.ReferenceWithMetaPriceSchedule.builder()
                                .setReference(Reference.builder().setScope("DOCUMENT").setReference("price-1").build())
                                .build())
                        .build())
                .setSettlementTerms(SettlementTerms.builder()
                        .setSettlementType(SettlementTypeEnum.CASH)
                        .setSettlementDate(SettlementDate.builder()
                                .setValueDate(DateMapper.parse(valueDate))
                                .build())
                        .build())
                .setUnderlier(Underlier.builder()
                        .setObservable(cdm.observable.asset.metafields.ReferenceWithMetaObservable.builder()
                                .setReference(Reference.builder().setScope("DOCUMENT").setReference("observable-1").build())
                                .build())
                        .build())
                .build();

        Payout payout = Payout.builder().setSettlementPayout(sp).build();
        EconomicTerms econ = EconomicTerms.builder().addPayout(payout).build();

        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder()
                .setEconomicTerms(econ);
        ntp.addTaxonomy(ProductTaxonomy.builder()
                .setSource(cdm.base.staticdata.asset.common.TaxonomySourceEnum.ISDA)
                .setProductQualifier("ForeignExchange_Spot_Forward")
                .build());

        // Build tradeLot with price + quantities + observable
        String priceCcy = "Currency2PerCurrency1".equals(quoteBasis) ? quoteCcy2 : quoteCcy1;
        String pricePerUnit = "Currency2PerCurrency1".equals(quoteBasis) ? quoteCcy1 : quoteCcy2;

        PriceSchedule price = PriceSchedule.builder()
                .setValue(new BigDecimal(rate))
                .setUnit(UnitType.builder().setCurrency(FieldWithMetaString.builder().setValue(priceCcy).build()).build())
                .setPerUnitOf(UnitType.builder().setCurrency(FieldWithMetaString.builder().setValue(pricePerUnit).build()).build())
                .setPriceType(PriceTypeEnum.EXCHANGE_RATE)
                .build();

        NonNegativeQuantitySchedule qty1 = NonNegativeQuantitySchedule.builder()
                .setValue(new BigDecimal(amt1))
                .setUnit(UnitType.builder().setCurrency(FieldWithMetaString.builder().setValue(ccy1).build()).build())
                .build();
        NonNegativeQuantitySchedule qty2 = NonNegativeQuantitySchedule.builder()
                .setValue(new BigDecimal(amt2))
                .setUnit(UnitType.builder().setCurrency(FieldWithMetaString.builder().setValue(ccy2).build()).build())
                .build();

        Cash cash = Cash.builder()
                .addIdentifier(AssetIdentifier.builder()
                        .setIdentifier(FieldWithMetaString.builder().setValue(ccy1).build())
                        .setIdentifierType(AssetIdTypeEnum.CURRENCY_CODE)
                        .build())
                .build();
        Observable observable = Observable.builder()
                .setAsset(cdm.base.staticdata.asset.common.Asset.builder().setCash(cash).build())
                .build();

        MetaFields priceMeta = MetaFields.builder().addKey(Key.builder().setScope("DOCUMENT").setKeyValue("price-1").build()).build();
        MetaFields qty1Meta = MetaFields.builder().addKey(Key.builder().setScope("DOCUMENT").setKeyValue("quantity-1").build()).build();
        MetaFields qty2Meta = MetaFields.builder().addKey(Key.builder().setScope("DOCUMENT").setKeyValue("quantity-2").build()).build();
        MetaFields obsMeta = MetaFields.builder().addKey(Key.builder().setScope("DOCUMENT").setKeyValue("observable-1").build()).build();

        PriceQuantity pq = PriceQuantity.builder()
                .addPrice(cdm.observable.asset.metafields.FieldWithMetaPriceSchedule.builder()
                        .setValue(price).setMeta(priceMeta).build())
                .addQuantity(cdm.base.math.metafields.FieldWithMetaNonNegativeQuantitySchedule.builder()
                        .setValue(qty1).setMeta(qty1Meta).build())
                .addQuantity(cdm.base.math.metafields.FieldWithMetaNonNegativeQuantitySchedule.builder()
                        .setValue(qty2).setMeta(qty2Meta).build())
                .setObservable(cdm.observable.asset.metafields.FieldWithMetaObservable.builder()
                        .setValue(observable).setMeta(obsMeta).build())
                .build();

        TradeLot tradeLot = TradeLot.builder().addPriceQuantity(pq).build();

        // Counterparties — PARTY_1 first
        List<Counterparty> counterparties = new ArrayList<>();
        ctx.partyOrder.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByValue())
                .forEach(entry -> counterparties.add(Counterparty.builder()
                        .setRole(entry.getValue() == 0 ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2)
                        .setPartyReference(ReferenceWithMetaParty.builder()
                                .setExternalReference(entry.getKey()).build())
                        .build()));

        // Trade identifiers
        List<TradeIdentifier> identifiers = new ArrayList<>();
        Element header = XmlUtils.child(trade, "tradeHeader");
        if (header != null) {
            for (Element pti : XmlUtils.children(header, "partyTradeIdentifier")) {
                identifiers.addAll(IdentifierMapper.mapWithSplit(pti));
            }
        }

        // Trade date
        FieldWithMetaDate tradeDate = null;
        String tradeDateText = XmlUtils.pathText(header, "tradeDate");
        if (tradeDateText != null) {
            tradeDate = FieldWithMetaDate.builder().setValue(DateMapper.parse(tradeDateText)).build();
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

        return TradeState.builder().setTrade(t.build()).build();
    }

    private void assignRoles(String ec1PayerHref, MappingContext ctx) {
        if (ec1PayerHref == null) return;
        int idx = 0;
        ctx.partyOrder.clear();
        for (String id : new ArrayList<>(ctx.parties.keySet())) {
            if (id.equals(ec1PayerHref)) {
                ctx.partyOrder.put(id, 0);
            } else {
                ctx.partyOrder.put(id, 1);
            }
        }
    }
}
