package io.fpmlcdm.products;

import cdm.base.datetime.*;
import cdm.base.math.*;
import cdm.base.math.metafields.FieldWithMetaNonNegativeQuantitySchedule;
import cdm.base.math.metafields.ReferenceWithMetaNonNegativeQuantitySchedule;
import cdm.base.staticdata.asset.common.*;
import cdm.base.staticdata.party.*;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.event.common.*;
import cdm.observable.asset.*;
import cdm.observable.asset.metafields.*;
import cdm.product.asset.*;
import cdm.product.common.settlement.*;
import cdm.product.template.*;
import com.rosetta.model.lib.meta.Key;
import com.rosetta.model.lib.meta.Reference;
import com.rosetta.model.metafields.*;
import io.fpmlcdm.common.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps FpML {@code <dividendSwapTransactionSupplement>} into CDM TradeState.
 * Produces a PerformancePayout (dividend return only) + FixedPricePayout.
 */
public class DividendSwapMapper implements ProductMapper {

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);

        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        parties = SwapMapper.applyPartyTradeInformation(parties, tradeHeader);

        Element product = XmlUtils.child(trade, "dividendSwapTransactionSupplement");

        Element dividendLeg = XmlUtils.child(product, "dividendLeg");
        Element fixedLeg = XmlUtils.child(product, "fixedLeg");

        // Assign counterparty roles: PARTY_1 = payer of dividendLeg
        Element payerRef = XmlUtils.child(dividendLeg, "payerPartyReference");
        if (payerRef != null) {
            assignRoles(payerRef.getAttribute("href"), ctx);
        }

        // Build payouts
        EconomicTerms.EconomicTermsBuilder econ = EconomicTerms.builder();
        econ.addPayout(buildPerformancePayout(dividendLeg, ctx));
        if (fixedLeg != null) {
            econ.addPayout(buildFixedPricePayout(fixedLeg, ctx));
        }

        // Taxonomy
        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder()
                .setEconomicTerms(econ.build());
        buildTaxonomy(dividendLeg).forEach(ntp::addTaxonomy);

        // TradeLot
        List<PriceQuantity> priceQuantities = buildTradeLotPriceQuantities(dividendLeg, fixedLeg);
        TradeLot tradeLot = TradeLot.builder().setPriceQuantity(priceQuantities).build();

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

        // Contract details
        ContractDetails contractDetails = ContractDetailsMapper.map(
                XmlUtils.child(trade, "documentation"), parties, ctx, trade);

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

    /**
     * Build just the inner economic terms (payouts) for a dividendSwapTransactionSupplement element.
     * Used by {@link DividendSwapOptionMapper} to embed the dividend swap as the underlier
     * of an option.
     */
    static EconomicTerms buildInnerEconomicTerms(Element supplement, MappingContext ctx) {
        Element dividendLeg = XmlUtils.child(supplement, "dividendLeg");
        Element fixedLeg = XmlUtils.child(supplement, "fixedLeg");
        EconomicTerms.EconomicTermsBuilder econ = EconomicTerms.builder();
        DividendSwapMapper m = new DividendSwapMapper();
        econ.addPayout(m.buildPerformancePayout(dividendLeg, ctx));
        if (fixedLeg != null) {
            econ.addPayout(m.buildFixedPricePayout(fixedLeg, ctx));
        }
        return econ.build();
    }

    /** Inner-product taxonomy (no productType, just the qualifier) for embedding under an option. */
    static List<ProductTaxonomy> buildInnerTaxonomy(Element supplement) {
        Element dividendLeg = XmlUtils.child(supplement, "dividendLeg");
        DividendSwapMapper m = new DividendSwapMapper();
        return m.buildTaxonomy(dividendLeg);
    }

    /** Inner-product priceQuantities for embedding under an option. */
    static List<PriceQuantity> buildInnerTradeLotPriceQuantities(Element supplement) {
        Element dividendLeg = XmlUtils.child(supplement, "dividendLeg");
        Element fixedLeg = XmlUtils.child(supplement, "fixedLeg");
        DividendSwapMapper m = new DividendSwapMapper();
        return m.buildTradeLotPriceQuantities(dividendLeg, fixedLeg);
    }

    private Payout buildPerformancePayout(Element dividendLeg, MappingContext ctx) {
        PerformancePayout.PerformancePayoutBuilder ppb = PerformancePayout.builder();

        // Payer/receiver
        Element payerRef = XmlUtils.child(dividendLeg, "payerPartyReference");
        Element receiverRef = XmlUtils.child(dividendLeg, "receiverPartyReference");
        ppb.setPayerReceiver(buildPayerReceiver(payerRef, receiverRef, ctx));

        // Settlement terms
        String settlementType = XmlUtils.childText(dividendLeg, "settlementType");
        String settlementCurrency = XmlUtils.childText(dividendLeg, "settlementCurrency");
        if (settlementType != null || settlementCurrency != null) {
            SettlementTerms.SettlementTermsBuilder stb = SettlementTerms.builder();
            if (settlementType != null) {
                if ("Cash".equals(settlementType)) stb.setSettlementType(SettlementTypeEnum.CASH);
                else if ("Physical".equals(settlementType)) stb.setSettlementType(SettlementTypeEnum.PHYSICAL);
            }
            if (settlementCurrency != null) {
                stb.setSettlementCurrency(FieldWithMetaString.builder().setValue(settlementCurrency).build());
            }
            ppb.setSettlementTerms(stb.build());
        }

        // Underlier
        ppb.setUnderlier(Underlier.builder()
                .setObservable(ReferenceWithMetaObservable.builder()
                        .setReference(Reference.builder().setScope("DOCUMENT").setReference("observable-1").build())
                        .build())
                .build());

        // Return terms - dividend only
        DividendReturnTerms.DividendReturnTermsBuilder drt = DividendReturnTerms.builder();

        // Dividend payout ratios
        String cashPct = XmlUtils.childText(dividendLeg, "declaredCashDividendPercentage");
        String nonCashPct = XmlUtils.childText(dividendLeg, "declaredCashEquivalentDividendPercentage");
        if (cashPct != null || nonCashPct != null) {
            DividendPayoutRatio.DividendPayoutRatioBuilder dpr = DividendPayoutRatio.builder();
            if (cashPct != null) dpr.setCashRatio(new BigDecimal(cashPct));
            if (nonCashPct != null) dpr.setNonCashRatio(new BigDecimal(nonCashPct));
            drt.addDividendPayoutRatio(dpr.build());
        }

        // Dividend periods
        for (Element dp : XmlUtils.children(dividendLeg, "dividendPeriod")) {
            drt.addDividendPeriod(buildDividendPeriod(dp));
        }

        ppb.setReturnTerms(ReturnTerms.builder()
                .setDividendReturnTerms(drt.build())
                .build());

        return Payout.builder().setPerformancePayout(ppb.build()).build();
    }

    private DividendPeriod buildDividendPeriod(Element dp) {
        DividendPeriod.DividendPeriodBuilder dpb = DividendPeriod.builder();

        // Start date
        Element startDateEl = XmlUtils.child(dp, "unadjustedStartDate");
        if (startDateEl != null) {
            String startDate = startDateEl.getTextContent().trim();
            String startDateId = startDateEl.getAttribute("id");

            AdjustableOrRelativeDate.AdjustableOrRelativeDateBuilder aordb = AdjustableOrRelativeDate.builder()
                    .setAdjustableDate(AdjustableDate.builder()
                            .setUnadjustedDate(DateMapper.parse(startDate))
                            .build());
            if (startDateId != null && !startDateId.isEmpty()) {
                aordb.setMeta(MetaFields.builder().setExternalKey(startDateId).build());
            }

            dpb.setStartDate(DividendPaymentDate.builder()
                    .setDividendDate(
                            cdm.base.datetime.metafields.ReferenceWithMetaAdjustableOrRelativeDate.builder()
                                    .setValue(aordb.build())
                                    .build())
                    .build());
        }

        // End date
        Element endDateEl = null;
        for (Element child : XmlUtils.children(dp, "unadjustedEndDate")) {
            endDateEl = child;
        }
        if (endDateEl != null) {
            String endDate = endDateEl.getTextContent().trim();
            String endDateId = endDateEl.getAttribute("id");

            AdjustableOrRelativeDate.AdjustableOrRelativeDateBuilder aordb = AdjustableOrRelativeDate.builder()
                    .setAdjustableDate(AdjustableDate.builder()
                            .setUnadjustedDate(DateMapper.parse(endDate))
                            .build());
            if (endDateId != null && !endDateId.isEmpty()) {
                aordb.setMeta(MetaFields.builder().setExternalKey(endDateId).build());
            }

            dpb.setEndDate(DividendPaymentDate.builder()
                    .setDividendDate(
                            cdm.base.datetime.metafields.ReferenceWithMetaAdjustableOrRelativeDate.builder()
                                    .setValue(aordb.build())
                                    .build())
                    .build());
        }

        // Date adjustments
        BusinessDayAdjustments bda = DateMapper.businessDayAdjustments(
                XmlUtils.child(dp, "dateAdjustments"));
        if (bda != null) dpb.setDateAdjustments(bda);

        // Payment date
        Element paymentDate = XmlUtils.child(dp, "paymentDate");
        if (paymentDate != null) {
            Element relDate = XmlUtils.child(paymentDate, "relativeDate");
            if (relDate != null) {
                String payDateId = paymentDate.getAttribute("id");
                AdjustedRelativeDateOffset.AdjustedRelativeDateOffsetBuilder rdb =
                        AdjustedRelativeDateOffset.builder();
                String pm = XmlUtils.childText(relDate, "periodMultiplier");
                if (pm != null) rdb.setPeriodMultiplier(Integer.parseInt(pm));
                String period = XmlUtils.childText(relDate, "period");
                if (period != null) rdb.setPeriod(EnumMappers.period(period));
                String dayType = XmlUtils.childText(relDate, "dayType");
                if (dayType != null) rdb.setDayType(EquityOptionMapper.mapDayType(dayType));
                String bdcStr = XmlUtils.childText(relDate, "businessDayConvention");
                if (bdcStr != null) rdb.setBusinessDayConvention(EnumMappers.bdc(bdcStr));
                Element drt = XmlUtils.child(relDate, "dateRelativeTo");
                if (drt != null) {
                    rdb.setDateRelativeTo(ReferenceWithMetaDate.builder()
                            .setExternalReference(drt.getAttribute("href")).build());
                }

                AdjustableOrRelativeDate.AdjustableOrRelativeDateBuilder aordb =
                        AdjustableOrRelativeDate.builder().setRelativeDate(rdb.build());
                if (payDateId != null && !payDateId.isEmpty()) {
                    aordb.setMeta(MetaFields.builder().setExternalKey(payDateId).build());
                }

                dpb.setDividendPaymentDate(DividendPaymentDate.builder()
                        .setDividendDate(
                                cdm.base.datetime.metafields.ReferenceWithMetaAdjustableOrRelativeDate.builder()
                                        .setValue(aordb.build())
                                        .build())
                        .build());
            }
        }

        // Valuation date
        Element valuationDate = XmlUtils.child(dp, "valuationDate");
        if (valuationDate != null) {
            Element relDate = XmlUtils.child(valuationDate, "relativeDate");
            if (relDate != null) {
                AdjustedRelativeDateOffset.AdjustedRelativeDateOffsetBuilder rdb =
                        AdjustedRelativeDateOffset.builder();
                String pm2 = XmlUtils.childText(relDate, "periodMultiplier");
                if (pm2 != null) rdb.setPeriodMultiplier(Integer.parseInt(pm2));
                String period2 = XmlUtils.childText(relDate, "period");
                if (period2 != null) rdb.setPeriod(EnumMappers.period(period2));
                String dayType2 = XmlUtils.childText(relDate, "dayType");
                if (dayType2 != null) rdb.setDayType(EquityOptionMapper.mapDayType(dayType2));
                String bdc2 = XmlUtils.childText(relDate, "businessDayConvention");
                if (bdc2 != null) rdb.setBusinessDayConvention(EnumMappers.bdc(bdc2));
                Element drt2 = XmlUtils.child(relDate, "dateRelativeTo");
                if (drt2 != null) {
                    rdb.setDateRelativeTo(ReferenceWithMetaDate.builder()
                            .setExternalReference(drt2.getAttribute("href")).build());
                }
                dpb.setDividendValuationDate(AdjustableOrRelativeDate.builder()
                        .setRelativeDate(rdb.build())
                        .build());
            }
        }

        return dpb.build();
    }

    private Payout buildFixedPricePayout(Element fixedLeg, MappingContext ctx) {
        FixedPricePayout.FixedPricePayoutBuilder fpb = FixedPricePayout.builder();

        Element payerRef = XmlUtils.child(fixedLeg, "payerPartyReference");
        Element receiverRef = XmlUtils.child(fixedLeg, "receiverPartyReference");
        fpb.setPayerReceiver(buildPayerReceiver(payerRef, receiverRef, ctx));

        // PriceQuantity reference
        fpb.setPriceQuantity(ResolvablePriceQuantity.builder()
                .setQuantitySchedule(ReferenceWithMetaNonNegativeQuantitySchedule.builder()
                        .setReference(Reference.builder().setScope("DOCUMENT").setReference("quantity-1").build())
                        .build())
                .build());

        // Fixed price
        fpb.setFixedPrice(FixedPrice.builder()
                .setPrice(ReferenceWithMetaPriceSchedule.builder()
                        .setValue(PriceSchedule.builder()
                                .setPriceType(PriceTypeEnum.DIVIDEND)
                                .build())
                        .build())
                .build());

        return Payout.builder().setFixedPricePayout(fpb.build()).build();
    }

    private List<PriceQuantity> buildTradeLotPriceQuantities(Element dividendLeg, Element fixedLeg) {
        List<PriceQuantity> out = new ArrayList<>();

        // First PQ: fixed payment amount (quantity-1)
        if (fixedLeg != null) {
            List<Element> fixedPayments = XmlUtils.children(fixedLeg, "fixedPayment");
            if (!fixedPayments.isEmpty()) {
                Element firstPayment = fixedPayments.get(0);
                Element paymentAmount = XmlUtils.child(firstPayment, "paymentAmount");
                String ccy = XmlUtils.childText(paymentAmount, "currency");
                String amount = XmlUtils.childText(paymentAmount, "amount");

                PriceQuantity.PriceQuantityBuilder pq1 = PriceQuantity.builder();
                if (amount != null) {
                    pq1.addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder()
                            .setValue(NonNegativeQuantitySchedule.builder()
                                    .setValue(new BigDecimal(amount))
                                    .setUnit(ccy != null ? UnitType.builder()
                                            .setCurrency(FieldWithMetaString.builder().setValue(ccy).build())
                                            .build() : null)
                                    .build())
                            .setMeta(QuantityMapper.locationMeta("quantity-1"))
                            .build());
                }
                out.add(pq1.build());
            }
        }

        // Second PQ: underlyer observable + openUnits (quantity-2)
        Element underlyer = XmlUtils.child(dividendLeg, "underlyer");
        if (underlyer != null) {
            PriceQuantity.PriceQuantityBuilder pq2 = PriceQuantity.builder();

            Element singleUnderlyer = XmlUtils.child(underlyer, "singleUnderlyer");
            String openUnits = singleUnderlyer != null ? XmlUtils.childText(singleUnderlyer, "openUnits") : null;
            if (openUnits != null) {
                // Determine unit type
                boolean isIndex = singleUnderlyer != null && XmlUtils.child(singleUnderlyer, "index") != null;
                UnitType unit = UnitType.builder()
                        .setFinancialUnit(isIndex ? FinancialUnitEnum.INDEX_UNIT : FinancialUnitEnum.SHARE)
                        .build();

                pq2.addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder()
                        .setValue(NonNegativeQuantitySchedule.builder()
                                .setValue(new BigDecimal(openUnits))
                                .setUnit(unit)
                                .build())
                        .setMeta(QuantityMapper.locationMeta("quantity-2"))
                        .build());
            }

            Observable observable = EquityOptionMapper.buildEquityObservable(underlyer);
            if (observable != null) {
                pq2.setObservable(FieldWithMetaObservable.builder()
                        .setValue(observable)
                        .setMeta(QuantityMapper.locationMeta("observable-1"))
                        .build());
            }

            out.add(pq2.build());
        }

        return out;
    }

    private List<ProductTaxonomy> buildTaxonomy(Element dividendLeg) {
        List<ProductTaxonomy> out = new ArrayList<>();

        // Determine underlyer type
        Element underlyer = XmlUtils.child(dividendLeg, "underlyer");
        boolean isIndex = false;
        if (underlyer != null) {
            Element su = XmlUtils.child(underlyer, "singleUnderlyer");
            if (su != null) isIndex = XmlUtils.child(su, "index") != null;
        }

        String qualifier = isIndex
                ? "EquitySwap_ParameterReturnDividend_Index"
                : "EquitySwap_ParameterReturnDividend_SingleName";

        out.add(ProductTaxonomy.builder()
                .setSource(TaxonomySourceEnum.ISDA)
                .setProductQualifier(qualifier)
                .build());

        return out;
    }

    private PayerReceiver buildPayerReceiver(Element payerRef, Element receiverRef, MappingContext ctx) {
        PayerReceiver.PayerReceiverBuilder prb = PayerReceiver.builder();
        if (payerRef != null) {
            String href = payerRef.getAttribute("href");
            Integer order = ctx.partyOrder.get(href);
            prb.setPayer(order != null && order == 0
                    ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2);
        }
        if (receiverRef != null) {
            String href = receiverRef.getAttribute("href");
            Integer order = ctx.partyOrder.get(href);
            prb.setReceiver(order != null && order == 0
                    ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2);
        }
        return prb.build();
    }

    private void assignRoles(String payerHref, MappingContext ctx) {
        if (payerHref == null) return;
        Map<String, Integer> newOrder = new LinkedHashMap<>();
        newOrder.put(payerHref, 0);
        int idx = 1;
        for (String id : ctx.partyOrder.keySet()) {
            if (!id.equals(payerHref)) {
                newOrder.put(id, idx++);
            }
        }
        ctx.partyOrder.clear();
        ctx.partyOrder.putAll(newOrder);
    }
}
