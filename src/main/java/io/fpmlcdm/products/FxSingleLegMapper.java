package io.fpmlcdm.products;

import cdm.base.math.ArithmeticOperationEnum;
import cdm.base.math.NonNegativeQuantitySchedule;
import cdm.base.math.UnitType;
import cdm.base.math.metafields.FieldWithMetaNonNegativeQuantitySchedule;
import cdm.base.math.metafields.ReferenceWithMetaNonNegativeQuantitySchedule;
import cdm.base.staticdata.asset.common.Asset;
import cdm.base.staticdata.asset.common.AssetIdTypeEnum;
import cdm.base.staticdata.asset.common.AssetIdentifier;
import cdm.base.staticdata.asset.common.Cash;
import cdm.base.staticdata.asset.common.ProductTaxonomy;
import cdm.base.staticdata.asset.common.TaxonomySourceEnum;
import cdm.base.staticdata.asset.common.TaxonomyValue;
import cdm.base.staticdata.party.Account;
import cdm.base.staticdata.party.Counterparty;
import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.Party;
import cdm.base.staticdata.party.PayerReceiver;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.event.common.ContractDetails;
import cdm.event.common.Trade;
import cdm.event.common.TradeIdentifier;
import cdm.event.common.TradeState;
import cdm.observable.asset.Observable;
import cdm.observable.asset.PriceComposite;
import cdm.observable.asset.PriceOperandEnum;
import cdm.observable.asset.PriceQuantity;
import cdm.observable.asset.PriceSchedule;
import cdm.observable.asset.PriceTypeEnum;
import cdm.observable.asset.metafields.FieldWithMetaObservable;
import cdm.observable.asset.metafields.FieldWithMetaPriceSchedule;
import cdm.observable.asset.metafields.ReferenceWithMetaObservable;
import cdm.observable.asset.metafields.ReferenceWithMetaPriceSchedule;
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
import com.rosetta.model.metafields.FieldWithMetaDate;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
import io.fpmlcdm.common.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps FpML {@code <fxSingleLeg>} into CDM TradeState.
 * Covers FX spot, FX forward, and non-deliverable forward (NDF).
 */
public class FxSingleLegMapper implements ProductMapper {

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);

        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        parties = SwapMapper.applyPartyTradeInformation(parties, tradeHeader);

        Element fxSingleLeg = XmlUtils.child(trade, "fxSingleLeg");
        return mapLeg(doc, trade, fxSingleLeg, parties, ctx, tradeHeader, 1,
                "ForeignExchange_Spot_Forward", null);
    }

    /**
     * Build a single FX leg payout + tradeLot PriceQuantity.
     * Returns a {@link LegResult} so callers (FxSwapMapper) can aggregate multiple legs.
     */
    public static LegResult buildLegResult(Element leg, MappingContext ctx, int legIndex, int totalLegs) {
        Element ec1 = XmlUtils.child(leg, "exchangedCurrency1");
        Element ec2 = XmlUtils.child(leg, "exchangedCurrency2");

        String ccy1 = XmlUtils.pathText(ec1, "paymentAmount", "currency");
        String ccy2 = XmlUtils.pathText(ec2, "paymentAmount", "currency");
        BigDecimal amt1 = new BigDecimal(XmlUtils.pathText(ec1, "paymentAmount", "amount"));
        BigDecimal amt2 = new BigDecimal(XmlUtils.pathText(ec2, "paymentAmount", "amount"));

        String valueDate = XmlUtils.childText(leg, "valueDate");
        Element exchangeRate = XmlUtils.child(leg, "exchangeRate");
        String rateStr = XmlUtils.childText(exchangeRate, "rate");
        Element qcp = XmlUtils.child(exchangeRate, "quotedCurrencyPair");
        String quoteCcy1 = XmlUtils.childText(qcp, "currency1");
        String quoteCcy2 = XmlUtils.childText(qcp, "currency2");
        String quoteBasis = XmlUtils.childText(qcp, "quoteBasis");

        // NDF settlement currency
        Element nds = XmlUtils.child(leg, "nonDeliverableSettlement");
        String settlementCcy = nds != null ? XmlUtils.childText(nds, "settlementCurrency") : null;

        // Determine payer/receiver for the leg.
        // For the near leg (legIndex=1), ec1 payer is PARTY_1
        // For far leg (legIndex=2), the far leg ec1 payer role must be determined
        Element ec1PayerRef = XmlUtils.child(ec1, "payerPartyReference");
        String ec1PayerHref = ec1PayerRef != null ? ec1PayerRef.getAttribute("href") : null;

        // Determine counterparty role of ec1 payer for THIS leg
        Integer ec1PayerOrder = ec1PayerHref != null ? ctx.partyOrder.get(ec1PayerHref) : null;
        CounterpartyRoleEnum payerRole = (ec1PayerOrder != null && ec1PayerOrder == 0)
                ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
        CounterpartyRoleEnum receiverRole = (payerRole == CounterpartyRoleEnum.PARTY_1)
                ? CounterpartyRoleEnum.PARTY_2 : CounterpartyRoleEnum.PARTY_1;

        String qLabel = "quantity-" + legIndex;
        String pLabel = "price-" + legIndex;
        String oLabel = "observable-" + legIndex;

        // For FX swap: quantity labels are quantity-1, quantity-3 (near) and quantity-2, quantity-4 (far)
        // Actually from the reference data: near leg → quantity-1, quantity-3; far leg → quantity-2, quantity-4
        // Wait, let me re-check: the reference shows quantity-1 + quantity-3 for near, quantity-2 + quantity-4 for far
        // No - looking at it again: price-1/quantity-1/observable-1 for near, price-2/quantity-2/observable-2 for far
        // And the second quantity of each leg uses quantity-3 and quantity-4 respectively

        // Build SettlementPayout
        SettlementTerms.SettlementTermsBuilder stb = SettlementTerms.builder()
                .setSettlementType(SettlementTypeEnum.CASH)
                .setSettlementDate(SettlementDate.builder()
                        .setValueDate(DateMapper.parse(valueDate))
                        .build());
        if (settlementCcy != null) {
            stb.setSettlementCurrency(FieldWithMetaString.builder().setValue(settlementCcy).build());
        }

        SettlementPayout sp = SettlementPayout.builder()
                .setPayerReceiver(PayerReceiver.builder()
                        .setPayer(payerRole)
                        .setReceiver(receiverRole)
                        .build())
                .setPriceQuantity(ResolvablePriceQuantity.builder()
                        .setQuantitySchedule(ReferenceWithMetaNonNegativeQuantitySchedule.builder()
                                .setReference(Reference.builder().setScope("DOCUMENT").setReference(qLabel).build())
                                .build())
                        .addPriceSchedule(ReferenceWithMetaPriceSchedule.builder()
                                .setReference(Reference.builder().setScope("DOCUMENT").setReference(pLabel).build())
                                .build())
                        .build())
                .setSettlementTerms(stb.build())
                .setUnderlier(Underlier.builder()
                        .setObservable(ReferenceWithMetaObservable.builder()
                                .setReference(Reference.builder().setScope("DOCUMENT").setReference(oLabel).build())
                                .build())
                        .build())
                .build();

        Payout payout = Payout.builder().setSettlementPayout(sp).build();

        // Build price
        String priceCcy = "Currency2PerCurrency1".equals(quoteBasis) ? quoteCcy2 : quoteCcy1;
        String pricePerUnit = "Currency2PerCurrency1".equals(quoteBasis) ? quoteCcy1 : quoteCcy2;

        PriceSchedule.PriceScheduleBuilder priceBuilder = PriceSchedule.builder()
                .setValue(new BigDecimal(rateStr))
                .setUnit(UnitType.builder().setCurrency(FieldWithMetaString.builder().setValue(priceCcy).build()).build())
                .setPerUnitOf(UnitType.builder().setCurrency(FieldWithMetaString.builder().setValue(pricePerUnit).build()).build())
                .setPriceType(PriceTypeEnum.EXCHANGE_RATE);

        String spotRateStr = XmlUtils.childText(exchangeRate, "spotRate");
        String fwdPointsStr = XmlUtils.childText(exchangeRate, "forwardPoints");
        if (spotRateStr != null && fwdPointsStr != null) {
            priceBuilder.setComposite(PriceComposite.builder()
                    .setBaseValue(new BigDecimal(spotRateStr))
                    .setOperand(new BigDecimal(fwdPointsStr))
                    .setArithmeticOperator(ArithmeticOperationEnum.ADD)
                    .setOperandType(PriceOperandEnum.FORWARD_POINT)
                    .build());
        }

        // Observable currency = quoteCcy1 (currency1 of quotedCurrencyPair)
        String obsCcy = quoteCcy1;

        // Quantities: first in ccy1, second in ccy2
        BigDecimal qty1Amount, qty2Amount;
        String qty1Ccy, qty2Ccy;
        if (ccy1.equals(quoteCcy1)) {
            qty1Amount = amt1; qty1Ccy = ccy1;
            qty2Amount = amt2; qty2Ccy = ccy2;
        } else {
            qty1Amount = amt2; qty1Ccy = ccy2;
            qty2Amount = amt1; qty2Ccy = ccy1;
        }

        FieldWithMetaPriceSchedule priceField = FieldWithMetaPriceSchedule.builder()
                .setValue(priceBuilder.build())
                .setMeta(QuantityMapper.locationMeta(pLabel))
                .build();

        FieldWithMetaNonNegativeQuantitySchedule qty1Field = FieldWithMetaNonNegativeQuantitySchedule.builder()
                .setValue(NonNegativeQuantitySchedule.builder()
                        .setValue(qty1Amount)
                        .setUnit(UnitType.builder().setCurrency(FieldWithMetaString.builder().setValue(qty1Ccy).build()).build())
                        .build())
                .setMeta(QuantityMapper.locationMeta(qLabel))
                .build();

        // Second quantity label
        // For standalone fxSingleLeg (totalLegs=1): quantity-1, quantity-2
        // For FX swap near leg (legIndex=1, totalLegs=2): quantity-1, quantity-3
        // For FX swap far leg (legIndex=2, totalLegs=2): quantity-2, quantity-4
        String q2Label = "quantity-" + (legIndex + totalLegs);

        FieldWithMetaNonNegativeQuantitySchedule qty2Field = FieldWithMetaNonNegativeQuantitySchedule.builder()
                .setValue(NonNegativeQuantitySchedule.builder()
                        .setValue(qty2Amount)
                        .setUnit(UnitType.builder().setCurrency(FieldWithMetaString.builder().setValue(qty2Ccy).build()).build())
                        .build())
                .setMeta(QuantityMapper.locationMeta(q2Label))
                .build();

        Observable observable = Observable.builder()
                .setAsset(Asset.builder()
                        .setCash(Cash.builder()
                                .addIdentifier(AssetIdentifier.builder()
                                        .setIdentifier(FieldWithMetaString.builder().setValue(obsCcy).build())
                                        .setIdentifierType(AssetIdTypeEnum.CURRENCY_CODE)
                                        .build())
                                .build())
                        .build())
                .build();

        FieldWithMetaObservable observableField = FieldWithMetaObservable.builder()
                .setValue(observable)
                .setMeta(QuantityMapper.locationMeta(oLabel))
                .build();

        PriceQuantity pq = PriceQuantity.builder()
                .addPrice(priceField)
                .addQuantity(qty1Field)
                .addQuantity(qty2Field)
                .setObservable(observableField)
                .build();

        return new LegResult(payout, pq);
    }

    /**
     * Builds a complete TradeState for an fxSingleLeg.
     */
    static TradeState mapLeg(Document doc, Element trade, Element leg,
                              List<Party> parties, MappingContext ctx,
                              Element tradeHeader, int legIndex,
                              String qualifier, List<ProductTaxonomy> extraTaxonomy) {
        // Assign roles: PARTY_1 = payer of exchangedCurrency1 on the first leg
        Element ec1 = XmlUtils.child(leg, "exchangedCurrency1");
        Element ec1PayerRef = ec1 != null ? XmlUtils.child(ec1, "payerPartyReference") : null;
        String ec1PayerHref = ec1PayerRef != null ? ec1PayerRef.getAttribute("href") : null;
        assignRoles(ec1PayerHref, ctx);

        LegResult lr = buildLegResult(leg, ctx, legIndex, 1);

        return buildTradeState(doc, trade, tradeHeader, parties, ctx,
                List.of(lr.payout), List.of(lr.priceQuantity),
                leg, qualifier, extraTaxonomy);
    }

    /**
     * Build the full TradeState from FX payouts and priceQuantity entries.
     */
    public static TradeState buildTradeState(Document doc, Element trade, Element tradeHeader,
                                              List<Party> parties, MappingContext ctx,
                                              List<Payout> payoutList, List<PriceQuantity> pqList,
                                              Element productEl, String qualifier,
                                              List<ProductTaxonomy> extraTaxonomy) {
        EconomicTerms.EconomicTermsBuilder econ = EconomicTerms.builder();
        for (Payout p : payoutList) econ.addPayout(p);

        CalculationAgentMapper.Result ca = CalculationAgentMapper.map(trade);
        if (ca.calculationAgent() != null) econ.setCalculationAgent(ca.calculationAgent());

        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder()
                .setEconomicTerms(econ.build());

        if (extraTaxonomy != null) {
            for (ProductTaxonomy t : extraTaxonomy) ntp.addTaxonomy(t);
        }
        if (qualifier != null) {
            ntp.addTaxonomy(ProductTaxonomy.builder()
                    .setSource(TaxonomySourceEnum.ISDA)
                    .setProductQualifier(qualifier)
                    .build());
        }
        if (productEl != null) {
            ProductIdentifierMapper.map(productEl).forEach(ntp::addIdentifier);
        }

        TradeLot tradeLot = TradeLot.builder().setPriceQuantity(pqList).build();
        List<Counterparty> counterparties = SwapMapper.buildCounterparties(ctx);

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

        ContractDetails contractDetails = ContractDetailsMapper.map(
                XmlUtils.child(trade, "documentation"), parties, ctx);

        List<cdm.base.staticdata.party.PartyRole> partyRoles = PartyRoleMapper.map(trade);

        Trade.TradeBuilder t = Trade.builder()
                .setProduct(ntp.build())
                .addTradeLot(tradeLot);
        counterparties.forEach(t::addCounterparty);
        for (cdm.base.staticdata.party.AncillaryParty ap : ca.ancillaryParties()) t.addAncillaryParty(ap);
        partyRoles.forEach(t::addPartyRole);
        identifiers.forEach(t::addTradeIdentifier);
        if (tradeDate != null) t.setTradeDate(tradeDate);
        parties.forEach(t::addParty);
        if (contractDetails != null) t.setContractDetails(contractDetails);
        for (Account a : AccountMapper.map(doc)) t.addAccount(a);

        TradeState.TradeStateBuilder tsBuilder = TradeState.builder().setTrade(t.build());
        for (cdm.event.common.TransferState ts : TransferMapper.map(trade, null)) {
            tsBuilder.addTransferHistory(ts);
        }
        return tsBuilder.build();
    }

    static void assignRoles(String ec1PayerHref, MappingContext ctx) {
        if (ec1PayerHref == null) return;
        Map<String, Integer> newOrder = new LinkedHashMap<>();
        newOrder.put(ec1PayerHref, 0);
        int idx = 1;
        for (String id : ctx.partyOrder.keySet()) {
            if (!id.equals(ec1PayerHref)) {
                newOrder.put(id, idx++);
            }
        }
        ctx.partyOrder.clear();
        ctx.partyOrder.putAll(newOrder);
    }

    /** Result of building one FX leg (for reuse in FxSwapMapper). */
    public static final class LegResult {
        public final Payout payout;
        public final PriceQuantity priceQuantity;
        public LegResult(Payout payout, PriceQuantity priceQuantity) {
            this.payout = payout;
            this.priceQuantity = priceQuantity;
        }
    }
}
