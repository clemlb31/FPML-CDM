package io.fpmlcdm.fpml.cdm.products;

import cdm.base.datetime.AdjustableDate;
import cdm.base.datetime.AdjustableOrRelativeDate;
import cdm.base.datetime.BusinessDayAdjustments;
import cdm.base.datetime.Frequency;
import cdm.base.datetime.PeriodExtendedEnum;
import cdm.base.datetime.RelativeDateOffset;
import cdm.base.math.NonNegativeQuantitySchedule;
import cdm.base.math.UnitType;
import cdm.base.math.metafields.FieldWithMetaNonNegativeQuantitySchedule;
import cdm.base.math.metafields.ReferenceWithMetaNonNegativeQuantitySchedule;
import cdm.base.staticdata.asset.common.AssetClassEnum;
import cdm.base.staticdata.asset.common.AssetIdTypeEnum;
import cdm.base.staticdata.asset.common.AssetIdentifier;
import cdm.base.staticdata.asset.common.ProductTaxonomy;
import cdm.base.staticdata.asset.common.TaxonomySourceEnum;
import cdm.base.staticdata.asset.common.TaxonomyValue;
import cdm.base.staticdata.asset.common.metafields.FieldWithMetaAssetClassEnum;
import cdm.base.staticdata.party.Counterparty;
import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.Party;
import cdm.base.staticdata.party.PayerReceiver;
import cdm.event.common.ContractDetails;
import cdm.event.common.Trade;
import cdm.event.common.TradeIdentifier;
import cdm.event.common.TradeState;
import cdm.observable.asset.FloatingRateIndex;
import cdm.observable.asset.Index;
import cdm.observable.asset.InterestRateIndex;
import cdm.observable.asset.Observable;
import cdm.observable.asset.PriceSchedule;
import cdm.observable.asset.PriceTypeEnum;
import cdm.observable.asset.PriceQuantity;
import cdm.observable.asset.metafields.FieldWithMetaInterestRateIndex;
import cdm.observable.asset.metafields.FieldWithMetaObservable;
import cdm.observable.asset.metafields.FieldWithMetaPriceSchedule;
import cdm.observable.asset.metafields.ReferenceWithMetaInterestRateIndex;
import cdm.observable.asset.metafields.ReferenceWithMetaPriceSchedule;
import cdm.product.asset.DiscountingMethod;
import cdm.product.asset.DiscountingTypeEnum;
import cdm.product.asset.FixedRateSpecification;
import cdm.product.asset.FloatingRateSpecification;
import cdm.product.asset.InterestRatePayout;
import cdm.product.asset.RateSpecification;
import cdm.product.common.schedule.CalculationPeriodDates;
import cdm.product.common.schedule.PaymentDates;
import cdm.product.common.schedule.RateSchedule;
import cdm.product.common.schedule.ResetDates;
import cdm.product.common.settlement.ResolvablePriceQuantity;
import cdm.product.template.EconomicTerms;
import cdm.product.template.NonTransferableProduct;
import cdm.product.template.Payout;
import cdm.product.template.TradeLot;
import com.rosetta.model.lib.meta.Key;
import com.rosetta.model.lib.meta.Reference;
import com.rosetta.model.metafields.FieldWithMetaDate;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
import cdm.base.staticdata.party.AncillaryParty;
import io.fpmlcdm.fpml.cdm.common.CalculationAgentMapper;
import io.fpmlcdm.fpml.cdm.common.ContractDetailsMapper;
import io.fpmlcdm.fpml.cdm.common.DateMapper;
import io.fpmlcdm.fpml.cdm.common.EnumMappers;
import io.fpmlcdm.fpml.cdm.common.IdentifierMapper;
import io.fpmlcdm.fpml.cdm.common.MappingContext;
import io.fpmlcdm.fpml.cdm.common.PartyMapper;
import io.fpmlcdm.fpml.cdm.common.ProductIdentifierMapper;
import io.fpmlcdm.fpml.cdm.common.QuantityMapper;
import io.fpmlcdm.fpml.cdm.common.TaxonomyMapper;
import io.fpmlcdm.fpml.cdm.common.XmlUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Forward Rate Agreement FpML→CDM mapper.
 *
 * FpML {@code <fra>} → CDM {@code NonTransferableProduct} with TWO {@link InterestRatePayout}:
 *   - fixed leg: buyer pays, seller receives (rate = fpml:fixedRate)
 *   - floating leg: seller pays, buyer receives (index = fpml:floatingRateIndex)
 */
public class FraMapper implements ProductMapper {

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);

        Element fra = XmlUtils.child(trade, "fra");

        // PARTY_1 = buyer (matches reference)
        Element buyerRef = XmlUtils.child(fra, "buyerPartyReference");
        Element sellerRef = XmlUtils.child(fra, "sellerPartyReference");
        String buyer = buyerRef == null ? null : buyerRef.getAttribute("href");
        String seller = sellerRef == null ? null : sellerRef.getAttribute("href");
        assignBuyerAsParty1(buyer, ctx);

        // tradeLot[0].priceQuantity[]:
        //   [0]: quantity-1 + price-2 (fixed rate)
        //   [1]: quantity-2 + observable/InterestRateIndex-1
        List<PriceQuantity> priceQuantities = buildPriceQuantities(fra);
        TradeLot tradeLot = TradeLot.builder().setPriceQuantity(priceQuantities).build();

        // Two payouts
        Payout fixedPayout = buildFixedPayout(fra, buyer, seller, ctx);
        Payout floatingPayout = buildFloatingPayout(fra, buyer, seller, ctx);

        EconomicTerms.EconomicTermsBuilder econB = EconomicTerms.builder()
                .addPayout(fixedPayout)
                .addPayout(floatingPayout);
        CalculationAgentMapper.Result ca = CalculationAgentMapper.map(trade);
        if (ca.calculationAgent() != null) econB.setCalculationAgent(ca.calculationAgent());
        EconomicTerms econ = econB.build();

        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder()
                .setEconomicTerms(econ);
        ProductIdentifierMapper.map(fra).forEach(ntp::addIdentifier);
        buildFraTaxonomy(fra).forEach(ntp::addTaxonomy);

        List<Counterparty> counterparties = SwapMapper.buildCounterparties(ctx);

        // tradeIdentifier
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
            tradeDate = FieldWithMetaDate.builder()
                    .setValue(DateMapper.parse(tradeDateEl.getTextContent().trim()))
                    .build();
        }

        ContractDetails contractDetails = ContractDetailsMapper.map(
                XmlUtils.child(trade, "documentation"), parties, ctx);

        Trade.TradeBuilder t = Trade.builder()
                .setProduct(ntp.build())
                .addTradeLot(tradeLot);
        counterparties.forEach(t::addCounterparty);
        for (AncillaryParty ap : ca.ancillaryParties()) t.addAncillaryParty(ap);
        identifiers.forEach(t::addTradeIdentifier);
        if (tradeDate != null) t.setTradeDate(tradeDate);
        parties.forEach(t::addParty);
        if (contractDetails != null) t.setContractDetails(contractDetails);

        return TradeState.builder().setTrade(t.build()).build();
    }

    /** Build the two priceQuantity entries with the labels observed in the dataset:
     *  PQ[0] : price-2 (fixed) + quantity-1 ; PQ[1] : quantity-2 + observable-1/InterestRateIndex-1. */
    private static List<PriceQuantity> buildPriceQuantities(Element fra) {
        List<PriceQuantity> out = new ArrayList<>();
        Element notional = XmlUtils.child(fra, "notional");
        BigDecimal amount = decimalText(notional, "amount");
        String ccy = XmlUtils.childText(notional, "currency");
        UnitType unit = currencyUnit(ccy);

        // PQ[0]: fixed price + quantity-1
        PriceQuantity.PriceQuantityBuilder pq0 = PriceQuantity.builder();
        BigDecimal fixedRate = textDecimal(XmlUtils.childText(fra, "fixedRate"));
        if (fixedRate != null) {
            pq0.addPrice(FieldWithMetaPriceSchedule.builder()
                    .setValue(PriceSchedule.builder()
                            .setValue(fixedRate)
                            .setUnit(unit)
                            .setPerUnitOf(unit)
                            .setPriceType(PriceTypeEnum.INTEREST_RATE)
                            .build())
                    .setMeta(QuantityMapper.locationMeta("price-2"))
                    .build());
        }
        if (amount != null) {
            pq0.addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder()
                    .setValue(NonNegativeQuantitySchedule.builder()
                            .setValue(amount).setUnit(unit).build())
                    .setMeta(QuantityMapper.locationMeta("quantity-1"))
                    .build());
        }
        out.add(pq0.build());

        // PQ[1]: quantity-2 + observable
        PriceQuantity.PriceQuantityBuilder pq1 = PriceQuantity.builder();
        if (amount != null) {
            pq1.addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder()
                    .setValue(NonNegativeQuantitySchedule.builder()
                            .setValue(amount).setUnit(unit).build())
                    .setMeta(QuantityMapper.locationMeta("quantity-2"))
                    .build());
        }
        String idxName = XmlUtils.childText(fra, "floatingRateIndex");
        if (idxName != null) {
            FloatingRateIndex.FloatingRateIndexBuilder friBld = FloatingRateIndex.builder()
                    .setAssetClass(AssetClassEnum.INTEREST_RATE)
                    .setFloatingRateIndex(EnumMappers.floatingRateIndex(idxName))
                    .addIdentifier(AssetIdentifier.builder()
                            .setIdentifier(FieldWithMetaString.builder().setValue(idxName).build())
                            .setIdentifierType(AssetIdTypeEnum.OTHER)
                            .build());
            Element tenor = XmlUtils.child(fra, "indexTenor");
            if (tenor != null) {
                cdm.base.datetime.Period.PeriodBuilder periodB = cdm.base.datetime.Period.builder();
                String pm = XmlUtils.childText(tenor, "periodMultiplier");
                String p = XmlUtils.childText(tenor, "period");
                if (pm != null) periodB.setPeriodMultiplier(Integer.parseInt(pm));
                if (p != null) periodB.setPeriod(EnumMappers.period(p));
                friBld.setIndexTenor(periodB.build());
            }
            FieldWithMetaInterestRateIndex iriField = FieldWithMetaInterestRateIndex.builder()
                    .setValue(InterestRateIndex.builder().setFloatingRateIndex(friBld.build()).build())
                    .setMeta(QuantityMapper.locationMeta("InterestRateIndex-1"))
                    .build();
            Observable observable = Observable.builder()
                    .setIndex(Index.builder().setInterestRateIndex(iriField).build())
                    .build();
            pq1.setObservable(FieldWithMetaObservable.builder()
                    .setValue(observable)
                    .setMeta(QuantityMapper.locationMeta("observable-1"))
                    .build());
        }
        out.add(pq1.build());
        return out;
    }

    private static Payout buildFixedPayout(Element fra, String buyer, String seller, MappingContext ctx) {
        InterestRatePayout.InterestRatePayoutBuilder irp = InterestRatePayout.builder();
        // Buyer pays fixed, seller receives
        irp.setPayerReceiver(PayerReceiver.builder()
                .setPayer(roleFor(buyer, ctx))
                .setReceiver(roleFor(seller, ctx))
                .build());
        irp.setPriceQuantity(ResolvablePriceQuantity.builder()
                .setQuantitySchedule(ReferenceWithMetaNonNegativeQuantitySchedule.builder()
                        .setReference(addressRef("quantity-1")).build())
                .build());
        // Note: the dataset has a quirk — the rate spec address points to "price-1"
        // while the priceQuantity uses "price-2". We mirror that quirk to match.
        irp.setRateSpecification(RateSpecification.builder()
                .setFixedRateSpecification(FixedRateSpecification.builder()
                        .setRateSchedule(RateSchedule.builder()
                                .setPrice(ReferenceWithMetaPriceSchedule.builder()
                                        .setReference(addressRef("price-1")).build())
                                .build())
                        .build())
                .build());
        addCommonIrpFields(irp, fra);
        return Payout.builder().setInterestRatePayout(irp.build()).build();
    }

    private static Payout buildFloatingPayout(Element fra, String buyer, String seller, MappingContext ctx) {
        InterestRatePayout.InterestRatePayoutBuilder irp = InterestRatePayout.builder();
        irp.setPayerReceiver(PayerReceiver.builder()
                .setPayer(roleFor(seller, ctx))
                .setReceiver(roleFor(buyer, ctx))
                .build());
        irp.setPriceQuantity(ResolvablePriceQuantity.builder()
                .setQuantitySchedule(ReferenceWithMetaNonNegativeQuantitySchedule.builder()
                        .setReference(addressRef("quantity-2")).build())
                .build());
        irp.setRateSpecification(RateSpecification.builder()
                .setFloatingRateSpecification(FloatingRateSpecification.builder()
                        .setRateOption(ReferenceWithMetaInterestRateIndex.builder()
                                .setReference(addressRef("InterestRateIndex-1")).build())
                        .build())
                .build());
        addCommonIrpFields(irp, fra);
        // Reference omits paymentDates on the floating leg (kept only on fixed).
        irp.setPaymentDates(null);
        // ResetDates from fixingDateOffset
        Element fixing = XmlUtils.child(fra, "fixingDateOffset");
        if (fixing != null) {
            irp.setResetDates(ResetDates.builder()
                    .setFixingDates(buildRelative(fixing))
                    .build());
        }
        return Payout.builder().setInterestRatePayout(irp.build()).build();
    }

    private static void addCommonIrpFields(InterestRatePayout.InterestRatePayoutBuilder irp, Element fra) {
        String dcf = XmlUtils.childText(fra, "dayCountFraction");
        if (dcf != null) irp.setDayCountFraction(EnumMappers.dayCount(dcf));

        String effDate = XmlUtils.childText(fra, "adjustedEffectiveDate");
        Element effDateEl = XmlUtils.child(fra, "adjustedEffectiveDate");
        String termDate = XmlUtils.childText(fra, "adjustedTerminationDate");
        CalculationPeriodDates.CalculationPeriodDatesBuilder cpd = CalculationPeriodDates.builder();
        if (effDate != null) {
            AdjustableDate.AdjustableDateBuilder adj = AdjustableDate.builder()
                    .setAdjustedDate(FieldWithMetaDate.builder().setValue(DateMapper.parse(effDate)).build());
            AdjustableOrRelativeDate.AdjustableOrRelativeDateBuilder aor =
                    AdjustableOrRelativeDate.builder().setAdjustableDate(adj.build());
            String id = effDateEl == null ? null : effDateEl.getAttribute("id");
            if (id != null && !id.isEmpty()) {
                aor.setMeta(MetaFields.builder().setExternalKey(id).build());
            }
            cpd.setEffectiveDate(aor.build());
        }
        if (termDate != null) {
            AdjustableDate adj = AdjustableDate.builder()
                    .setAdjustedDate(FieldWithMetaDate.builder().setValue(DateMapper.parse(termDate)).build())
                    .build();
            cpd.setTerminationDate(AdjustableOrRelativeDate.builder().setAdjustableDate(adj).build());
        }
        irp.setCalculationPeriodDates(cpd.build());

        // paymentDates
        Element payDate = XmlUtils.child(fra, "paymentDate");
        Element tenor = XmlUtils.child(fra, "indexTenor");
        PaymentDates.PaymentDatesBuilder pdb = PaymentDates.builder();
        if (tenor != null) {
            String pm = XmlUtils.childText(tenor, "periodMultiplier");
            String p = XmlUtils.childText(tenor, "period");
            Frequency.FrequencyBuilder fb = Frequency.builder();
            if (pm != null) fb.setPeriodMultiplier(Integer.parseInt(pm));
            if (p != null) fb.setPeriod(EnumMappers.periodExtended(p));
            pdb.setPaymentFrequency(fb.build());
        }
        irp.setPaymentDates(pdb.build());
        // paymentDate (adjustable form) → InterestRatePayout.paymentDate
        if (payDate != null) {
            AdjustableDate adj = DateMapper.adjustable(payDate);
            if (adj != null) irp.setPaymentDate(adj);
        }

        // discountingMethod from fraDiscounting code (ISDA → FRA, AFMA → AFMA). NONE → absent.
        String fd = XmlUtils.childText(fra, "fraDiscounting");
        if (fd != null && !"NONE".equalsIgnoreCase(fd)) {
            DiscountingTypeEnum dt = switch (fd) {
                case "ISDA" -> DiscountingTypeEnum.FRA;
                case "AFMA" -> DiscountingTypeEnum.AFMA;
                default -> {
                    try { yield DiscountingTypeEnum.fromDisplayName(fd); } catch (Exception e) { yield null; }
                }
            };
            if (dt != null) irp.setDiscountingMethod(DiscountingMethod.builder().setDiscountingType(dt).build());
        }
    }

    private static RelativeDateOffset buildRelative(Element fpml) {
        RelativeDateOffset.RelativeDateOffsetBuilder b = RelativeDateOffset.builder();
        String pm = XmlUtils.childText(fpml, "periodMultiplier");
        if (pm != null) b.setPeriodMultiplier(Integer.parseInt(pm));
        String p = XmlUtils.childText(fpml, "period");
        if (p != null) b.setPeriod(EnumMappers.period(p));
        String dt = XmlUtils.childText(fpml, "dayType");
        if (dt != null) {
            try { b.setDayType(cdm.base.datetime.DayTypeEnum.valueOf(dt.toUpperCase())); }
            catch (IllegalArgumentException ignored) {}
        }
        String bdc = XmlUtils.childText(fpml, "businessDayConvention");
        if (bdc != null) b.setBusinessDayConvention(EnumMappers.bdc(bdc));
        Element centers = XmlUtils.child(fpml, "businessCenters");
        if (centers != null) b.setBusinessCenters(DateMapper.buildBusinessCenters(centers));
        Element drt = XmlUtils.child(fpml, "dateRelativeTo");
        if (drt != null) {
            b.setDateRelativeTo(com.rosetta.model.metafields.ReferenceWithMetaDate.builder()
                    .setExternalReference(drt.getAttribute("href")).build());
        }
        return b.build();
    }

    private static List<ProductTaxonomy> buildFraTaxonomy(Element fra) {
        List<ProductTaxonomy> out = new ArrayList<>();
        Element primaryAssetClass = XmlUtils.child(fra, "primaryAssetClass");
        Element productType = XmlUtils.child(fra, "productType");

        if (primaryAssetClass != null) {
            String pacScheme = primaryAssetClass.getAttribute("assetClassScheme");
            String pacValue = primaryAssetClass.getTextContent().trim();
            AssetClassEnum ac = null;
            try { ac = AssetClassEnum.fromDisplayName(pacValue); } catch (Exception ignored) {}
            FieldWithMetaAssetClassEnum.FieldWithMetaAssetClassEnumBuilder ab =
                    FieldWithMetaAssetClassEnum.builder();
            if (ac != null) ab.setValue(ac);
            if (pacScheme != null && !pacScheme.isEmpty()) {
                ab.setMeta(MetaFields.builder().setScheme(pacScheme).build());
            }
            out.add(ProductTaxonomy.builder().setPrimaryAssetClass(ab.build()).build());
        }
        if (productType != null) {
            String ptScheme = productType.getAttribute("productTypeScheme");
            String ptValue = productType.getTextContent().trim();
            FieldWithMetaString.FieldWithMetaStringBuilder name = FieldWithMetaString.builder().setValue(ptValue);
            if (ptScheme != null && !ptScheme.isEmpty()) {
                name.setMeta(MetaFields.builder().setScheme(ptScheme).build());
            }
            TaxonomyValue tv = TaxonomyValue.builder().setName(name.build()).build();
            // FRA uses CFI source if scheme contains iso10962, else ISDA or Other
            TaxonomySourceEnum src = TaxonomySourceEnum.OTHER;
            if (ptScheme != null) {
                String low = ptScheme.toLowerCase();
                if (low.contains("iso10962")) src = TaxonomySourceEnum.CFI;
                else if (low.contains("product-taxonomy")) src = TaxonomySourceEnum.ISDA;
            }
            out.add(ProductTaxonomy.builder().setSource(src).setValue(tv).build());
        }
        out.add(ProductTaxonomy.builder()
                .setSource(TaxonomySourceEnum.ISDA)
                .setProductQualifier("InterestRate_Fra")
                .build());
        return out;
    }

    private static void assignBuyerAsParty1(String buyer, MappingContext ctx) {
        if (buyer == null || buyer.isEmpty() || ctx.partyOrder.isEmpty()) return;
        Map<String, Integer> newOrder = new LinkedHashMap<>();
        newOrder.put(buyer, 0);
        int idx = 1;
        for (String pid : ctx.partyOrder.keySet()) {
            if (!pid.equals(buyer)) newOrder.put(pid, idx++);
        }
        ctx.partyOrder.clear();
        ctx.partyOrder.putAll(newOrder);
    }

    private static CounterpartyRoleEnum roleFor(String partyHref, MappingContext ctx) {
        if (partyHref == null) return null;
        Integer order = ctx.partyOrder.get(partyHref);
        if (order == null) return null;
        return order == 0 ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
    }

    private static Reference addressRef(String value) {
        return Reference.builder().setScope("DOCUMENT").setReference(value).build();
    }

    private static UnitType currencyUnit(String ccy) {
        if (ccy == null) return null;
        return UnitType.builder()
                .setCurrency(FieldWithMetaString.builder().setValue(ccy).build())
                .build();
    }

    private static BigDecimal decimalText(Element parent, String childName) {
        String t = XmlUtils.childText(parent, childName);
        return t == null ? null : new BigDecimal(t);
    }

    private static BigDecimal textDecimal(String t) {
        return t == null ? null : new BigDecimal(t);
    }
}
