package io.fpmlcdm.products;

import cdm.base.datetime.*;
import cdm.base.datetime.metafields.FieldWithMetaCommodityBusinessCalendarEnum;
import cdm.base.math.NonNegativeQuantitySchedule;
import cdm.base.math.UnitType;
import cdm.base.math.metafields.FieldWithMetaNonNegativeQuantitySchedule;
import cdm.base.math.metafields.ReferenceWithMetaNonNegativeQuantitySchedule;
import cdm.base.staticdata.asset.common.*;
import cdm.base.staticdata.party.*;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.event.common.*;
import cdm.observable.asset.*;
import cdm.observable.asset.metafields.*;
import cdm.product.asset.CommodityPayout;
import cdm.product.asset.DayDistributionEnum;
import cdm.product.common.schedule.*;
import cdm.product.common.settlement.*;
import cdm.product.template.*;
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

public class CommoditySwapMapper implements ProductMapper {

    @Override
    public TradeState map(Document doc, Element trade) {
        return map(doc, trade, null);
    }

    /** Like {@link #map(Document, Element)} but reuses a caller-supplied context.
     *  When ctx is non-null and {@code ctx.partyOrderLocked} is true, the inner party
     *  ordering is preserved exactly (used by CommoditySwaptionMapper for inner products). */
    public TradeState map(Document doc, Element trade, MappingContext externalCtx) {
        MappingContext ctx = externalCtx != null ? externalCtx : new MappingContext();
        List<Party> parties;
        if (externalCtx == null) {
            parties = PartyMapper.map(doc, ctx);
        } else {
            // ctx already has parties — rebuild Party list from ctx
            parties = new ArrayList<>(ctx.parties.values());
        }
        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        parties = SwapMapper.applyPartyTradeInformation(parties, tradeHeader);
        Element comSwap = XmlUtils.child(trade, "commoditySwap");
        if (comSwap == null) {
            // Inside a commoditySwaption wrapper
            Element swaption = XmlUtils.child(trade, "commoditySwaption");
            if (swaption != null) comSwap = XmlUtils.child(swaption, "commoditySwap");
        }
        if (comSwap == null) return null;
        List<Element> fixedLegs = XmlUtils.children(comSwap, "fixedLeg");
        List<Element> floatingLegs = XmlUtils.children(comSwap, "floatingLeg");
        List<Element> physicalLegs = new ArrayList<>();
        physicalLegs.addAll(XmlUtils.children(comSwap, "coalPhysicalLeg"));
        physicalLegs.addAll(XmlUtils.children(comSwap, "gasPhysicalLeg"));
        physicalLegs.addAll(XmlUtils.children(comSwap, "oilPhysicalLeg"));
        physicalLegs.addAll(XmlUtils.children(comSwap, "electricityPhysicalLeg"));
        physicalLegs.addAll(XmlUtils.children(comSwap, "environmentalPhysicalLeg"));
        // Weather-index leg has no CDM mapping target; treat as salvage trigger so that
        // the reference's minimal output (only effective/termination dates) is produced.
        List<Element> weatherLegs = XmlUtils.children(comSwap, "weatherLeg");
        boolean hasWeatherLeg = !weatherLegs.isEmpty();

        // Physical-leg salvage path: when there are NO floating legs but there is a physical
        // leg OR an environmental fixedLeg, the FINOS reference produces a minimal shape
        // (SettlementPayout for the physical side + FixedPricePayout(price-1) for the fixed,
        // tradeLot with just the fixed price). We emit that here and skip the richer build.
        boolean fixedHasEnvironmental = false;
        for (Element fl : fixedLegs) {
            if (XmlUtils.child(fl, "environmental") != null) { fixedHasEnvironmental = true; break; }
        }
        if (floatingLegs.isEmpty() && (!physicalLegs.isEmpty() || fixedHasEnvironmental)) {
            return buildPhysicalSalvage(doc, trade, ctx, comSwap, tradeHeader, parties,
                    physicalLegs, fixedLegs);
        }
        // Weather-index swap: only effective/termination dates make it into the reference.
        if (hasWeatherLeg) {
            return buildWeatherSalvage(doc, trade, ctx, comSwap, tradeHeader, parties);
        }

        // When physical legs coexist with floating legs (basis-swap with a physical side),
        // we treat physical legs as SettlementPayouts appended after the regular flow.
        List<Element> allFloatLike = new ArrayList<>(floatingLegs);
        // Anchor party order from the physical leg when present (reference convention) —
        // otherwise from the first floating/fixed leg in document order.
        Element firstLeg = !physicalLegs.isEmpty() ? physicalLegs.get(0)
                : (!allFloatLike.isEmpty() ? allFloatLike.get(0)
                : (!fixedLegs.isEmpty() ? fixedLegs.get(0) : null));
        if (firstLeg != null && !ctx.partyOrderLocked) assignCounterpartyRoles(firstLeg, ctx);
        String settlementCurrency = XmlUtils.childText(comSwap, "settlementCurrency");
        // Label layout (1-fixed-1-float case observed in commodity-5-10 references):
        //   payout[0] CommodityPayout       -> quantity-1 (float total), observable-1
        //   payout[1] FixedPricePayout      -> quantity-2 (fixed total), price-N (fixed-price label)
        //   priceQuantity[0] (Fixed PQ)     -> price-N + quantity-3 (per-day) + quantity-2 (total)
        //   priceQuantity[1] (Float PQ)     -> price-K (Asset/empty) + quantity-4 (per-day) + quantity-1 (total) + observable-1
        // For N float legs and M fixed legs we generalise: float-i total = quantity-i, fixed-j total = quantity-(N+j);
        // per-day labels are appended after totals.
        // Price labels are assigned in this order: first the float legs that carry a spread
        // (price-1..price-S), then the fixed legs (price-(S+1)..price-(S+M)).
        List<Payout> payouts = new ArrayList<>();
        List<PriceQuantity> priceQuantities = new ArrayList<>();
        int nFloat = allFloatLike.size();
        int nFixed = fixedLegs.size();
        int floatPerDayBase = nFloat + nFixed; // first per-day quantity index for float legs starts at nFloat+nFixed+1

        // Pre-count which floats carry a spread.
        // Convention observed in references: when ANY float carries a spread, all floats
        // get a leading price slot (price-1..price-nFloat), and fixed legs follow
        // (price-(nFloat+1)..). When NO float carries a spread, fixed legs come first
        // (price-1..price-nFixed) followed by float slots (price-(nFixed+1)..).
        boolean anyFloatSpread = false;
        for (int i = 0; i < nFloat; i++) {
            Element fleg = allFloatLike.get(i);
            Element calcEl = XmlUtils.child(fleg, "calculation");
            if (calcEl != null && XmlUtils.child(calcEl, "spread") != null) {
                anyFloatSpread = true;
                break;
            }
        }
        // floatPriceOffset is added to (i+1) to get the float's price label.
        // fixedPriceOffset is added to (j+1) to get the fixed's price label.
        final int floatPriceOffset = anyFloatSpread ? 0 : nFixed;
        final int fixedPriceOffset = anyFloatSpread ? nFloat : 0;

        // Build CommodityPayouts first (these are the float legs)
        for (int i = 0; i < nFloat; i++) {
            Element fleg = allFloatLike.get(i);
            String floatTotalLabel = "quantity-" + (i + 1); // float total -> quantity-(i+1)
            String floatPerDayLabel = "quantity-" + (floatPerDayBase + nFixed + i + 1); // float per-day comes after fixed per-days
            String obsLabel = "observable-" + (i + 1);
            String floatPriceLabel = "price-" + (floatPriceOffset + i + 1);
            CommodityPayout.CommodityPayoutBuilder cpb = CommodityPayout.builder();
            cpb.setPayerReceiver(buildPayerReceiver(fleg, ctx));
            // In basis-with-physical mode the reference suppresses priceQuantity on the
            // float leg (the document's only resolvable totals belong to the physical side).
            if (physicalLegs.isEmpty()) {
                cpb.setPriceQuantity(ResolvablePriceQuantity.builder().setQuantitySchedule(ReferenceWithMetaNonNegativeQuantitySchedule.builder().setReference(Reference.builder().setScope("DOCUMENT").setReference(floatTotalLabel).build()).build()).build());
            }
            if (settlementCurrency != null) cpb.setSettlementTerms(SettlementTerms.builder().setSettlementType(SettlementTypeEnum.CASH).setSettlementCurrency(FieldWithMetaString.builder().setValue(settlementCurrency).build()).build());
            Element pricingDates = XmlUtils.child(fleg, "pricingDates");
            Element calcEl = XmlUtils.child(fleg, "calculation");
            if (pricingDates == null && calcEl != null) pricingDates = XmlUtils.child(calcEl, "pricingDates");
            if (pricingDates != null) cpb.setPricingDates(buildPricingDates(pricingDates));
            // averagingFeature (from FpML calculation/averagingMethod)
            String averagingMethod = calcEl != null ? XmlUtils.childText(calcEl, "averagingMethod") : null;
            if (averagingMethod != null) {
                cdm.base.math.AveragingCalculationMethod.AveragingCalculationMethodBuilder amb =
                        cdm.base.math.AveragingCalculationMethod.builder()
                                .setIsWeighted("Weighted".equalsIgnoreCase(averagingMethod))
                                .setCalculationMethod(cdm.base.math.AveragingCalculationMethodEnum.ARITHMETIC);
                cpb.setAveragingFeature(cdm.product.template.AveragingCalculation.builder().setAveragingMethod(amb.build()).build());
            }
            Element calcSchedule = XmlUtils.child(fleg, "calculationPeriodsSchedule");
            if (calcSchedule != null) cpb.setCalculationPeriodDates(buildCalcPeriodDates(calcSchedule));
            Element relPayDates = XmlUtils.child(fleg, "relativePaymentDates");
            if (relPayDates != null) cpb.setPaymentDates(buildPaymentDates(relPayDates));
            cpb.setUnderlier(Underlier.builder().setObservable(ReferenceWithMetaObservable.builder().setReference(Reference.builder().setScope("DOCUMENT").setReference(obsLabel).build()).build()).build());
            // commodityPriceReturnTerms: spread (reference to the float price address) or conversionFactor
            Element spread = calcEl != null ? XmlUtils.child(calcEl, "spread") : null;
            String conversionFactor = calcEl != null ? XmlUtils.childText(calcEl, "conversionFactor") : null;
            if (spread != null || conversionFactor != null) {
                cdm.product.common.settlement.CommodityPriceReturnTerms.CommodityPriceReturnTermsBuilder cprt =
                        cdm.product.common.settlement.CommodityPriceReturnTerms.builder();
                if (spread != null) {
                    cprt.setSpread(cdm.product.asset.SpreadSchedule.builder()
                            .setPrice(ReferenceWithMetaPriceSchedule.builder()
                                    .setReference(Reference.builder().setScope("DOCUMENT").setReference(floatPriceLabel).build()).build())
                            .build());
                }
                if (conversionFactor != null) {
                    cprt.setConversionFactor(new BigDecimal(conversionFactor));
                }
                cpb.setCommodityPriceReturnTerms(cprt.build());
            }
            payouts.add(Payout.builder().setCommodityPayout(cpb.build()).build());
        }
        // Build FixedPricePayouts (and store them; their PQ will be added to priceQuantities first)
        List<Payout> fixedPayouts = new ArrayList<>();
        List<PriceQuantity> fixedPQs = new ArrayList<>();
        for (int j = 0; j < nFixed; j++) {
            Element fxleg = fixedLegs.get(j);
            String fixedTotalLabel = "quantity-" + (nFloat + j + 1); // fixed total -> quantity-(nFloat+j+1)
            String fixedPerDayLabel = "quantity-" + (floatPerDayBase + j + 1); // fixed per-day
            String fixedPriceLabel = "price-" + (fixedPriceOffset + j + 1);
            FixedPricePayout.FixedPricePayoutBuilder fpb = FixedPricePayout.builder();
            fpb.setPayerReceiver(buildPayerReceiver(fxleg, ctx));
            fpb.setPriceQuantity(ResolvablePriceQuantity.builder().setQuantitySchedule(ReferenceWithMetaNonNegativeQuantitySchedule.builder().setReference(Reference.builder().setScope("DOCUMENT").setReference(fixedTotalLabel).build()).build()).build());
            Element relPayDates = XmlUtils.child(fxleg, "relativePaymentDates");
            if (relPayDates != null) fpb.setPaymentDates(buildPaymentDates(relPayDates));
            fpb.setFixedPrice(FixedPrice.builder().setPrice(ReferenceWithMetaPriceSchedule.builder().setReference(Reference.builder().setScope("DOCUMENT").setReference(fixedPriceLabel).build()).build()).build());
            fixedPayouts.add(Payout.builder().setFixedPricePayout(fpb.build()).build());
            PriceQuantity.PriceQuantityBuilder pqb = PriceQuantity.builder();
            addFixedLegPQ(pqb, fxleg, fixedTotalLabel, fixedPerDayLabel, fixedPriceLabel);
            fixedPQs.add(pqb.build());
        }
        // tradeLot.priceQuantity ordering observed: [fixed..., float...]
        priceQuantities.addAll(fixedPQs);
        for (int i = 0; i < nFloat; i++) {
            Element fleg = allFloatLike.get(i);
            String floatTotalLabel = "quantity-" + (i + 1);
            String floatPerDayLabel = "quantity-" + (floatPerDayBase + nFixed + i + 1);
            String obsLabel = "observable-" + (i + 1);
            String floatPriceLabel = "price-" + (floatPriceOffset + i + 1);
            PriceQuantity.PriceQuantityBuilder pqb = PriceQuantity.builder();
            addFloatingPQ(pqb, fleg, floatTotalLabel, floatPerDayLabel, obsLabel, floatPriceLabel);
            priceQuantities.add(pqb.build());
        }
        // Payout order: [CommodityPayout(s), FixedPricePayout(s)]
        payouts.addAll(fixedPayouts);
        // Physical legs (basis-with-physical) → trailing SettlementPayout entries.
        for (Element physLeg : physicalLegs) {
            payouts.add(Payout.builder()
                    .setSettlementPayout(SettlementPayout.builder()
                            .setPayerReceiver(buildPayerReceiver(physLeg, ctx))
                            .build())
                    .build());
        }
        EconomicTerms.EconomicTermsBuilder econ = EconomicTerms.builder();
        for (Payout p : payouts) econ.addPayout(p);
        Element effectiveDateEl = XmlUtils.child(comSwap, "effectiveDate");
        if (effectiveDateEl != null) {
            Element adjDate = XmlUtils.child(effectiveDateEl, "adjustableDate");
            if (adjDate != null) {
                AdjustableOrRelativeDate.AdjustableOrRelativeDateBuilder ardb =
                        AdjustableOrRelativeDate.builder()
                                .setAdjustableDate(DateMapper.adjustable(adjDate));
                String eid = effectiveDateEl.getAttribute("id");
                if (eid != null && !eid.isEmpty()) {
                    ardb.setMeta(MetaFields.builder().setExternalKey(eid).build());
                }
                econ.setEffectiveDate(ardb.build());
            }
        }
        Element terminationDateEl = XmlUtils.child(comSwap, "terminationDate");
        if (terminationDateEl != null) {
            Element adjDate = XmlUtils.child(terminationDateEl, "adjustableDate");
            if (adjDate != null) {
                AdjustableOrRelativeDate.AdjustableOrRelativeDateBuilder ardb =
                        AdjustableOrRelativeDate.builder()
                                .setAdjustableDate(DateMapper.adjustable(adjDate));
                String tid = terminationDateEl.getAttribute("id");
                if (tid != null && !tid.isEmpty()) {
                    ardb.setMeta(MetaFields.builder().setExternalKey(tid).build());
                }
                econ.setTerminationDate(ardb.build());
            }
        }
        CalculationAgentMapper.Result ca = CalculationAgentMapper.map(trade);
        if (ca.calculationAgent() != null) econ.setCalculationAgent(ca.calculationAgent());
        String qualifier = determineCommoditySwapQualifier(comSwap);
        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder().setEconomicTerms(econ.build());
        // Optional productType taxonomy (source=Other for commodity)
        Element productType = XmlUtils.child(comSwap, "productType");
        if (productType != null) {
            String ptScheme = productType.getAttribute("productTypeScheme");
            String ptValue = productType.getTextContent().trim();
            FieldWithMetaString.FieldWithMetaStringBuilder name = FieldWithMetaString.builder().setValue(ptValue);
            if (ptScheme != null && !ptScheme.isEmpty()) {
                name.setMeta(MetaFields.builder().setScheme(ptScheme).build());
            }
            cdm.base.staticdata.asset.common.TaxonomyValue tv =
                    cdm.base.staticdata.asset.common.TaxonomyValue.builder().setName(name.build()).build();
            ntp.addTaxonomy(ProductTaxonomy.builder().setSource(TaxonomySourceEnum.OTHER).setValue(tv).build());
        }
        // Suppress ISDA qualifier when a physical leg is paired with floating legs — the
        // FINOS reference omits the inferred Commodity_Swap_Basis tag in that case.
        if (physicalLegs.isEmpty()) {
            ntp.addTaxonomy(ProductTaxonomy.builder().setSource(TaxonomySourceEnum.ISDA).setProductQualifier(qualifier).build());
        }
        TradeLot tradeLot = TradeLot.builder().setPriceQuantity(priceQuantities).build();
        List<Counterparty> counterparties = SwapMapper.buildCounterparties(ctx);
        List<TradeIdentifier> identifiers = new ArrayList<>();
        if (tradeHeader != null) { for (Element pti : XmlUtils.children(tradeHeader, "partyTradeIdentifier")) identifiers.addAll(IdentifierMapper.mapWithSplit(pti)); }
        FieldWithMetaDate tradeDate = buildTradeDate(tradeHeader);
        ContractDetails contractDetails = ContractDetailsMapper.map(XmlUtils.child(trade, "documentation"), parties, ctx);
        List<PartyRole> partyRoles = PartyRoleMapper.map(trade);
        Trade.TradeBuilder t = Trade.builder().setProduct(ntp.build()).addTradeLot(tradeLot);
        counterparties.forEach(t::addCounterparty);
        for (AncillaryParty ap : ca.ancillaryParties()) t.addAncillaryParty(ap);
        partyRoles.forEach(t::addPartyRole); identifiers.forEach(t::addTradeIdentifier);
        if (tradeDate != null) t.setTradeDate(tradeDate); parties.forEach(t::addParty);
        if (contractDetails != null) t.setContractDetails(contractDetails);
        for (Account a : AccountMapper.map(doc)) t.addAccount(a);
        TradeState.TradeStateBuilder tsBuilder = TradeState.builder().setTrade(t.build());
        for (TransferState ts : TransferMapper.map(trade, comSwap)) tsBuilder.addTransferHistory(ts);
        return tsBuilder.build();
    }
    private void assignCounterpartyRoles(Element leg, MappingContext ctx) {
        Element payerRef = XmlUtils.child(leg, "payerPartyReference"); if (payerRef == null) return;
        String payerHref = payerRef.getAttribute("href"); if (payerHref == null || payerHref.isEmpty()) return;
        Map<String, Integer> newOrder = new LinkedHashMap<>(); newOrder.put(payerHref, 0); int idx = 1;
        for (String pid : ctx.partyOrder.keySet()) { if (!pid.equals(payerHref)) newOrder.put(pid, idx++); }
        ctx.partyOrder.clear(); ctx.partyOrder.putAll(newOrder);
    }
    static PayerReceiver buildPayerReceiver(Element leg, MappingContext ctx) {
        Element payerRef = XmlUtils.child(leg, "payerPartyReference"); Element recvRef = XmlUtils.child(leg, "receiverPartyReference");
        String payerHref = payerRef != null ? payerRef.getAttribute("href") : null; String recvHref = recvRef != null ? recvRef.getAttribute("href") : null;
        CounterpartyRoleEnum payerRole = CounterpartyRoleEnum.PARTY_2; CounterpartyRoleEnum recvRole = CounterpartyRoleEnum.PARTY_1;
        if (payerHref != null && ctx.partyOrder.get(payerHref) != null) payerRole = ctx.partyOrder.get(payerHref) == 0 ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
        if (recvHref != null && ctx.partyOrder.get(recvHref) != null) recvRole = ctx.partyOrder.get(recvHref) == 0 ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
        return PayerReceiver.builder().setPayer(payerRole).setReceiver(recvRole).build();
    }
    private PricingDates buildPricingDates(Element fpml) {
        ParametricDates.ParametricDatesBuilder paramB = ParametricDates.builder();
        String dayType = XmlUtils.childText(fpml, "dayType");
        if (dayType != null) { switch (dayType) { case "Business": case "CommodityBusiness": paramB.setDayType(DayTypeEnum.BUSINESS); break; case "Calendar": paramB.setDayType(DayTypeEnum.CALENDAR); break; case "ScheduledTradingDay": paramB.setDayType(DayTypeEnum.SCHEDULED_TRADING_DAY); break; case "ExchangeBusiness": paramB.setDayType(DayTypeEnum.EXCHANGE_BUSINESS); break; case "CurrencyBusiness": paramB.setDayType(DayTypeEnum.CURRENCY_BUSINESS); break; } }
        String dayDist = XmlUtils.childText(fpml, "dayDistribution");
        if (dayDist != null) { try { paramB.setDayDistribution(DayDistributionEnum.valueOf(dayDist)); } catch (IllegalArgumentException e) { try { paramB.setDayDistribution(DayDistributionEnum.fromDisplayName(dayDist)); } catch (Exception ignored) {} } }
        List<Element> calendars = XmlUtils.children(fpml, "businessCalendar");
        if (!calendars.isEmpty()) { BusinessCenters.BusinessCentersBuilder bcb = BusinessCenters.builder(); for (Element cal : calendars) { FieldWithMetaCommodityBusinessCalendarEnum fwm = mapCommodityBusinessCalendar(cal.getTextContent().trim()); if (fwm != null) bcb.addCommodityBusinessCalendar(fwm); } paramB.setBusinessCenters(bcb.build()); }
        return PricingDates.builder().setParametricDates(paramB.build()).build();
    }
    private CalculationPeriodDates buildCalcPeriodDates(Element calcSchedule) {
        CalculationPeriodFrequency.CalculationPeriodFrequencyBuilder cpf = CalculationPeriodFrequency.builder();
        String pm = XmlUtils.childText(calcSchedule, "periodMultiplier"); if (pm != null) cpf.setPeriodMultiplier(Integer.parseInt(pm));
        String period = XmlUtils.childText(calcSchedule, "period"); if (period != null) { try { cpf.setPeriod(PeriodExtendedEnum.valueOf(period)); } catch (IllegalArgumentException ignored) {} }
        String bofp = XmlUtils.childText(calcSchedule, "balanceOfFirstPeriod"); if (bofp != null) cpf.setBalanceOfFirstPeriod(Boolean.parseBoolean(bofp));
        String id = calcSchedule.getAttribute("id"); if (id != null && !id.isEmpty()) cpf.setMeta(MetaFields.builder().setExternalKey(id).build());
        return CalculationPeriodDates.builder().setCalculationPeriodFrequency(cpf.build()).build();
    }
    static PaymentDates buildPaymentDates(Element relPayDates) {
        PaymentDates.PaymentDatesBuilder pd = PaymentDates.builder();
        String payRelTo = XmlUtils.childText(relPayDates, "payRelativeTo");
        if (payRelTo != null) { try { pd.setPayRelativeTo(PayRelativeToEnum.valueOf(payRelTo)); } catch (IllegalArgumentException e) { try { pd.setPayRelativeTo(PayRelativeToEnum.fromDisplayName(payRelTo)); } catch (Exception ignored) {} } }
        Element offset = XmlUtils.child(relPayDates, "paymentDaysOffset");
        if (offset != null) { Offset.OffsetBuilder ob = Offset.builder(); String pm = XmlUtils.childText(offset, "periodMultiplier"); if (pm != null) ob.setPeriodMultiplier(Integer.parseInt(pm)); String p = XmlUtils.childText(offset, "period"); if (p != null) { try { ob.setPeriod(PeriodEnum.valueOf(p)); } catch (IllegalArgumentException ignored) {} } String dt = XmlUtils.childText(offset, "dayType"); if (dt != null) { switch (dt) { case "Business": ob.setDayType(DayTypeEnum.BUSINESS); break; case "Calendar": ob.setDayType(DayTypeEnum.CALENDAR); break; case "CurrencyBusiness": ob.setDayType(DayTypeEnum.CURRENCY_BUSINESS); break; } } pd.setPaymentDaysOffset(ob.build()); }
        String bdcText = null; if (offset != null) bdcText = XmlUtils.childText(offset, "businessDayConvention");
        Element centersEl = XmlUtils.child(relPayDates, "businessCenters");
        if (bdcText != null || centersEl != null) { BusinessDayAdjustments.BusinessDayAdjustmentsBuilder bdab = BusinessDayAdjustments.builder(); if (bdcText != null) bdab.setBusinessDayConvention(EnumMappers.bdc(bdcText)); if (centersEl != null) bdab.setBusinessCenters(DateMapper.buildBusinessCenters(centersEl)); pd.setPaymentDatesAdjustments(bdab.build()); }
        return pd.build();
    }
    // Build the Float-leg PriceQuantity. Order observed in references: [price, quantity(per-day), quantity(total), observable].
    private void addFloatingPQ(PriceQuantity.PriceQuantityBuilder pqb, Element fleg, String totalLabel, String perDayLabel, String obsLabel, String priceLabel) {
        // 1. AssetPrice (per-unit). When the FpML <calculation><spread> exists, the AssetPrice
        //    also carries the spread's value + currency (e.g. -1.45 USD on a basis swap).
        Element notionalQty = XmlUtils.child(fleg, "notionalQuantity");
        String unitText = notionalQty != null ? XmlUtils.childText(notionalQty, "quantityUnit") : null;
        PriceSchedule.PriceScheduleBuilder psb = PriceSchedule.builder().setPriceType(PriceTypeEnum.ASSET_PRICE).setArithmeticOperator(cdm.base.math.ArithmeticOperationEnum.ADD);
        cdm.base.math.CapacityUnitEnum capUnit = mapCapacityUnit(unitText);
        if (capUnit != null) psb.setPerUnitOf(UnitType.builder().setCapacityUnit(capUnit).build());
        Element calcEl = XmlUtils.child(fleg, "calculation");
        Element spreadEl = calcEl != null ? XmlUtils.child(calcEl, "spread") : null;
        if (spreadEl != null) {
            String amount = XmlUtils.childText(spreadEl, "amount");
            String currency = XmlUtils.childText(spreadEl, "currency");
            if (amount != null) psb.setValue(new BigDecimal(amount));
            if (currency != null) psb.setUnit(UnitType.builder().setCurrency(FieldWithMetaString.builder().setValue(currency).build()).build());
        }
        pqb.addPrice(FieldWithMetaPriceSchedule.builder().setValue(psb.build()).setMeta(QuantityMapper.locationMeta(priceLabel)).build());

        // 2. Per-day notionalQuantity
        if (notionalQty != null) {
            String unit = XmlUtils.childText(notionalQty, "quantityUnit");
            String freq = XmlUtils.childText(notionalQty, "quantityFrequency");
            String qty = XmlUtils.childText(notionalQty, "quantity");
            if (qty != null) {
                NonNegativeQuantitySchedule.NonNegativeQuantityScheduleBuilder qb = NonNegativeQuantitySchedule.builder().setValue(new BigDecimal(qty));
                if (unit != null) {
                    cdm.base.math.CapacityUnitEnum cu = mapCapacityUnit(unit);
                    if (cu != null) qb.setUnit(UnitType.builder().setCapacityUnit(cu).build());
                }
                if (freq != null) qb.setFrequency(mapFrequency(freq));
                pqb.addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder().setValue(qb.build()).setMeta(QuantityMapper.locationMeta(perDayLabel)).build());
            }
        }
        // 3. Total notional
        String totalNotionalQty = XmlUtils.childText(fleg, "totalNotionalQuantity");
        if (totalNotionalQty != null) {
            NonNegativeQuantitySchedule.NonNegativeQuantityScheduleBuilder qb = NonNegativeQuantitySchedule.builder().setValue(new BigDecimal(totalNotionalQty));
            if (unitText != null) {
                cdm.base.math.CapacityUnitEnum cu = mapCapacityUnit(unitText);
                if (cu != null) qb.setUnit(UnitType.builder().setCapacityUnit(cu).build());
            }
            pqb.addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder().setValue(qb.build()).setMeta(QuantityMapper.locationMeta(totalLabel)).build());
        }
        // 4. Observable
        addFloatingObservable(pqb, fleg, obsLabel);
    }
    private void addFloatingObservable(PriceQuantity.PriceQuantityBuilder pqb, Element fleg, String obsLabel) {
        Element commodity = XmlUtils.child(fleg, "commodity"); if (commodity == null) return;
        cdm.base.staticdata.asset.common.Commodity.CommodityBuilder cb = cdm.base.staticdata.asset.common.Commodity.builder();
        Element instrumentId = XmlUtils.child(commodity, "instrumentId");
        if (instrumentId != null) { String idValue = instrumentId.getTextContent().trim(); String scheme = instrumentId.getAttribute("instrumentIdScheme"); AssetIdentifier.AssetIdentifierBuilder aid = AssetIdentifier.builder().setIdentifierType(AssetIdTypeEnum.ISDACRP); FieldWithMetaString.FieldWithMetaStringBuilder fms = FieldWithMetaString.builder().setValue(idValue); if (scheme != null && !scheme.isEmpty()) fms.setMeta(MetaFields.builder().setScheme(scheme).build()); aid.setIdentifier(fms.build()); cb.addIdentifier(aid.build()); }
        String specifiedPrice = XmlUtils.childText(commodity, "specifiedPrice");
        if (specifiedPrice != null) { try { cb.setPriceQuoteType(QuotationSideEnum.valueOf(specifiedPrice.toUpperCase())); } catch (IllegalArgumentException e) { try { cb.setPriceQuoteType(QuotationSideEnum.fromDisplayName(specifiedPrice)); } catch (Exception ignored) {} } }
        String deliveryDates = XmlUtils.childText(commodity, "deliveryDates");
        if (deliveryDates != null) { int nearby = 1; if (deliveryDates.startsWith("Second")) nearby = 2; else if (deliveryDates.startsWith("Third")) nearby = 3; cb.setDeliveryDateReference(DeliveryDateParameters.builder().setDeliveryNearby(Offset.builder().setPeriodMultiplier(nearby).setPeriod(PeriodEnum.M).build()).build()); }
        Observable obs = Observable.builder().setAsset(Asset.builder().setCommodity(cb.build()).build()).build();
        pqb.setObservable(FieldWithMetaObservable.builder().setValue(obs).setMeta(QuantityMapper.locationMeta(obsLabel)).build());
    }
    // Build the Fixed-leg PriceQuantity. Order observed: [price (CashPrice with value+unit), quantity(per-day), quantity(total)].
    private void addFixedLegPQ(PriceQuantity.PriceQuantityBuilder pqb, Element fxleg, String totalLabel, String perDayLabel, String priceLabel) {
        Element fixedPrice = XmlUtils.child(fxleg, "fixedPrice");
        if (fixedPrice != null) {
            String price = XmlUtils.childText(fixedPrice, "price");
            String priceCcy = XmlUtils.childText(fixedPrice, "priceCurrency");
            String priceUnit = XmlUtils.childText(fixedPrice, "priceUnit");
            if (price != null) {
                PriceSchedule.PriceScheduleBuilder psb = PriceSchedule.builder().setValue(new BigDecimal(price)).setPriceType(PriceTypeEnum.CASH_PRICE);
                if (priceCcy != null) psb.setUnit(UnitType.builder().setCurrency(FieldWithMetaString.builder().setValue(priceCcy).build()).build());
                if (priceUnit != null) {
                    cdm.base.math.CapacityUnitEnum pu = mapCapacityUnit(priceUnit);
                    if (pu != null) psb.setPerUnitOf(UnitType.builder().setCapacityUnit(pu).build());
                }
                pqb.addPrice(FieldWithMetaPriceSchedule.builder().setValue(psb.build()).setMeta(QuantityMapper.locationMeta(priceLabel)).build());
            }
        }
        Element notionalQty = XmlUtils.child(fxleg, "notionalQuantity");
        String totalNotionalQty = XmlUtils.childText(fxleg, "totalNotionalQuantity");
        String unitText = notionalQty != null ? XmlUtils.childText(notionalQty, "quantityUnit") : null;
        if (notionalQty != null) {
            String freq = XmlUtils.childText(notionalQty, "quantityFrequency");
            String qty = XmlUtils.childText(notionalQty, "quantity");
            if (qty != null) {
                NonNegativeQuantitySchedule.NonNegativeQuantityScheduleBuilder qb = NonNegativeQuantitySchedule.builder().setValue(new BigDecimal(qty));
                if (unitText != null) {
                    cdm.base.math.CapacityUnitEnum cu = mapCapacityUnit(unitText);
                    if (cu != null) qb.setUnit(UnitType.builder().setCapacityUnit(cu).build());
                }
                if (freq != null) qb.setFrequency(mapFrequency(freq));
                pqb.addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder().setValue(qb.build()).setMeta(QuantityMapper.locationMeta(perDayLabel)).build());
            }
        }
        if (totalNotionalQty != null) {
            NonNegativeQuantitySchedule.NonNegativeQuantityScheduleBuilder qb = NonNegativeQuantitySchedule.builder().setValue(new BigDecimal(totalNotionalQty));
            if (unitText != null) {
                cdm.base.math.CapacityUnitEnum cu = mapCapacityUnit(unitText);
                if (cu != null) qb.setUnit(UnitType.builder().setCapacityUnit(cu).build());
            }
            pqb.addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder().setValue(qb.build()).setMeta(QuantityMapper.locationMeta(totalLabel)).build());
        }
    }
    static FieldWithMetaDate buildTradeDate(Element tradeHeader) {
        if (tradeHeader == null) return null; Element tradeDateEl = XmlUtils.child(tradeHeader, "tradeDate"); if (tradeDateEl == null) return null;
        FieldWithMetaDate.FieldWithMetaDateBuilder tdb = FieldWithMetaDate.builder().setValue(DateMapper.parse(tradeDateEl.getTextContent().trim()));
        String tdId = tradeDateEl.getAttribute("id"); if (tdId != null && !tdId.isEmpty()) tdb.setMeta(MetaFields.builder().setExternalKey(tdId).build());
        return tdb.build();
    }
    private String determineCommoditySwapQualifier(Element comSwap) {
        int fixedCount = XmlUtils.children(comSwap, "fixedLeg").size();
        int floatCount = XmlUtils.children(comSwap, "floatingLeg").size() + XmlUtils.children(comSwap, "coalPhysicalLeg").size() + XmlUtils.children(comSwap, "gasPhysicalLeg").size() + XmlUtils.children(comSwap, "oilPhysicalLeg").size() + XmlUtils.children(comSwap, "electricityPhysicalLeg").size() + XmlUtils.children(comSwap, "environmentalPhysicalLeg").size();
        if (fixedCount > 0 && floatCount > 0) return "Commodity_Swap_FixedFloat";
        if (floatCount >= 2) return "Commodity_Swap_Basis";
        return "Commodity_Swap_FixedFloat";
    }
    /**
     * Salvage form for physical commodity swaps/forwards. Produces:
     *   payout[0] = SettlementPayout (payerReceiver from physical leg, or fixedLeg seller view)
     *   payout[1] = FixedPricePayout (payerReceiver from fixedLeg + price-1 ref)
     *   tradeLot[0].priceQuantity[0] = price-1 (CashPrice / Fee) with value+currency+perUnitOf
     *   product.taxonomy[0] = primaryAssetClass=Commodity (no ISDA qualifier)
     */
    private TradeState buildPhysicalSalvage(Document doc, Element trade, MappingContext ctx,
                                            Element comSwap, Element tradeHeader, List<Party> parties,
                                            List<Element> physicalLegs, List<Element> fixedLegs) {
        // Anchor counterparty role from the physical leg's payer (or fixedLeg payer if none).
        Element anchorLeg = !physicalLegs.isEmpty() ? physicalLegs.get(0)
                : (!fixedLegs.isEmpty() ? fixedLegs.get(0) : null);
        if (anchorLeg != null && !ctx.partyOrderLocked) assignCounterpartyRoles(anchorLeg, ctx);

        List<Payout> payouts = new ArrayList<>();
        // SettlementPayout for the physical side (payerReceiver mirrors the physical leg
        // when present, otherwise mirrors the fixed leg — emissions forwards have only fixedLeg).
        Element settlementSourceLeg = !physicalLegs.isEmpty() ? physicalLegs.get(0)
                : (!fixedLegs.isEmpty() ? fixedLegs.get(0) : null);
        if (settlementSourceLeg != null) {
            SettlementPayout sp = SettlementPayout.builder()
                    .setPayerReceiver(buildPayerReceiver(settlementSourceLeg, ctx))
                    .build();
            payouts.add(Payout.builder().setSettlementPayout(sp).build());
        }

        // FixedPricePayout per fixedLeg. When the FpML fixedLeg carries a <fixedPrice>
        // OR <fixedPriceSchedule> block we reference it via "price-1" and emit a matching
        // tradeLot priceQuantity. For exotic forms (settlementPeriodsPrice, totalPrice,
        // etc.) we just emit a bare FixedPricePayout with no price ref and no tradeLot.
        boolean anyFixedPriceBlock = false;
        boolean anyFixedPriceWithValue = false;
        for (Element fxleg : fixedLegs) {
            if (XmlUtils.child(fxleg, "fixedPrice") != null) {
                anyFixedPriceBlock = true;
                anyFixedPriceWithValue = true;
                break;
            }
            if (XmlUtils.child(fxleg, "fixedPriceSchedule") != null) {
                anyFixedPriceBlock = true;
            }
        }
        for (Element fxleg : fixedLegs) {
            FixedPricePayout.FixedPricePayoutBuilder fpb = FixedPricePayout.builder()
                    .setPayerReceiver(buildPayerReceiver(fxleg, ctx));
            if (anyFixedPriceBlock) {
                fpb.setFixedPrice(FixedPrice.builder()
                        .setPrice(ReferenceWithMetaPriceSchedule.builder()
                                .setReference(Reference.builder().setScope("DOCUMENT").setReference("price-1").build())
                                .build())
                        .build());
            }
            payouts.add(Payout.builder().setFixedPricePayout(fpb.build()).build());
        }

        // tradeLot priceQuantity[0]: just the fixed price (CashPrice). Skipped entirely
        // when no fixedLeg uses any fixed-price block.
        TradeLot tradeLot = null;
        if (anyFixedPriceBlock) {
            PriceQuantity pq = anyFixedPriceWithValue
                    ? buildSalvageFixedPriceQuantity(fixedLegs)
                    : buildBarePriceLocation();
            tradeLot = TradeLot.builder().addPriceQuantity(pq).build();
        }

        EconomicTerms.EconomicTermsBuilder econ = EconomicTerms.builder();
        for (Payout p : payouts) econ.addPayout(p);

        Element effectiveDateEl = XmlUtils.child(comSwap, "effectiveDate");
        if (effectiveDateEl != null) {
            Element adjDate = XmlUtils.child(effectiveDateEl, "adjustableDate");
            if (adjDate != null) {
                AdjustableOrRelativeDate.AdjustableOrRelativeDateBuilder ardb =
                        AdjustableOrRelativeDate.builder()
                                .setAdjustableDate(DateMapper.adjustable(adjDate));
                String eid = effectiveDateEl.getAttribute("id");
                if (eid != null && !eid.isEmpty()) {
                    ardb.setMeta(MetaFields.builder().setExternalKey(eid).build());
                }
                econ.setEffectiveDate(ardb.build());
            }
        }
        Element terminationDateEl = XmlUtils.child(comSwap, "terminationDate");
        if (terminationDateEl != null) {
            Element adjDate = XmlUtils.child(terminationDateEl, "adjustableDate");
            if (adjDate != null) {
                AdjustableOrRelativeDate.AdjustableOrRelativeDateBuilder ardb =
                        AdjustableOrRelativeDate.builder()
                                .setAdjustableDate(DateMapper.adjustable(adjDate));
                String tid = terminationDateEl.getAttribute("id");
                if (tid != null && !tid.isEmpty()) {
                    ardb.setMeta(MetaFields.builder().setExternalKey(tid).build());
                }
                econ.setTerminationDate(ardb.build());
            }
        }
        CalculationAgentMapper.Result ca = CalculationAgentMapper.map(trade);
        if (ca.calculationAgent() != null) econ.setCalculationAgent(ca.calculationAgent());

        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder()
                .setEconomicTerms(econ.build());
        // taxonomy: primaryAssetClass=Commodity only — no ISDA qualifier in salvage form.
        Element primaryAssetClassEl = XmlUtils.child(comSwap, "primaryAssetClass");
        if (primaryAssetClassEl != null) {
            cdm.base.staticdata.asset.common.metafields.FieldWithMetaAssetClassEnum ac =
                    cdm.base.staticdata.asset.common.metafields.FieldWithMetaAssetClassEnum.builder()
                            .setValue(AssetClassEnum.COMMODITY).build();
            ntp.addTaxonomy(ProductTaxonomy.builder().setPrimaryAssetClass(ac).build());
        }

        List<Counterparty> counterparties = SwapMapper.buildCounterparties(ctx);
        List<TradeIdentifier> identifiers = new ArrayList<>();
        if (tradeHeader != null) {
            for (Element pti : XmlUtils.children(tradeHeader, "partyTradeIdentifier")) {
                identifiers.addAll(IdentifierMapper.mapWithSplit(pti));
            }
        }
        FieldWithMetaDate tradeDate = buildTradeDate(tradeHeader);
        ContractDetails contractDetails = ContractDetailsMapper.map(
                XmlUtils.child(trade, "documentation"), parties, ctx);
        List<PartyRole> partyRoles = PartyRoleMapper.map(trade);

        Trade.TradeBuilder t = Trade.builder().setProduct(ntp.build());
        if (tradeLot != null) t.addTradeLot(tradeLot);
        counterparties.forEach(t::addCounterparty);
        for (AncillaryParty ap : ca.ancillaryParties()) t.addAncillaryParty(ap);
        partyRoles.forEach(t::addPartyRole);
        identifiers.forEach(t::addTradeIdentifier);
        if (tradeDate != null) t.setTradeDate(tradeDate);
        parties.forEach(t::addParty);
        if (contractDetails != null) t.setContractDetails(contractDetails);
        for (Account a : AccountMapper.map(doc)) t.addAccount(a);

        TradeState.TradeStateBuilder tsBuilder = TradeState.builder().setTrade(t.build());
        for (TransferState ts : TransferMapper.map(trade, comSwap)) tsBuilder.addTransferHistory(ts);
        return tsBuilder.build();
    }

    /**
     * Weather-index swap salvage form: only product.economicTerms.effectiveDate /
     * terminationDate and trade-level metadata. No payouts, no tradeLot.
     */
    private TradeState buildWeatherSalvage(Document doc, Element trade, MappingContext ctx,
                                           Element comSwap, Element tradeHeader, List<Party> parties) {
        EconomicTerms.EconomicTermsBuilder econ = EconomicTerms.builder();
        Element effectiveDateEl = XmlUtils.child(comSwap, "effectiveDate");
        if (effectiveDateEl != null) {
            Element adjDate = XmlUtils.child(effectiveDateEl, "adjustableDate");
            if (adjDate != null) {
                AdjustableOrRelativeDate.AdjustableOrRelativeDateBuilder ardb =
                        AdjustableOrRelativeDate.builder().setAdjustableDate(DateMapper.adjustable(adjDate));
                String eid = effectiveDateEl.getAttribute("id");
                if (eid != null && !eid.isEmpty()) {
                    ardb.setMeta(MetaFields.builder().setExternalKey(eid).build());
                }
                econ.setEffectiveDate(ardb.build());
            }
        }
        Element terminationDateEl = XmlUtils.child(comSwap, "terminationDate");
        if (terminationDateEl != null) {
            Element adjDate = XmlUtils.child(terminationDateEl, "adjustableDate");
            if (adjDate != null) {
                AdjustableOrRelativeDate.AdjustableOrRelativeDateBuilder ardb =
                        AdjustableOrRelativeDate.builder().setAdjustableDate(DateMapper.adjustable(adjDate));
                String tid = terminationDateEl.getAttribute("id");
                if (tid != null && !tid.isEmpty()) {
                    ardb.setMeta(MetaFields.builder().setExternalKey(tid).build());
                }
                econ.setTerminationDate(ardb.build());
            }
        }
        NonTransferableProduct ntp = NonTransferableProduct.builder().setEconomicTerms(econ.build()).build();

        List<TradeIdentifier> identifiers = new ArrayList<>();
        if (tradeHeader != null) {
            for (Element pti : XmlUtils.children(tradeHeader, "partyTradeIdentifier")) {
                identifiers.addAll(IdentifierMapper.mapWithSplit(pti));
            }
        }
        FieldWithMetaDate tradeDate = buildTradeDate(tradeHeader);
        ContractDetails contractDetails = ContractDetailsMapper.map(
                XmlUtils.child(trade, "documentation"), parties, ctx, null, true);

        Trade.TradeBuilder t = Trade.builder().setProduct(ntp);
        // Empty-role counterparties (no partyReference) — matches FINOS weather-swap output.
        t.addCounterparty(Counterparty.builder().setRole(CounterpartyRoleEnum.PARTY_1).build());
        t.addCounterparty(Counterparty.builder().setRole(CounterpartyRoleEnum.PARTY_2).build());
        identifiers.forEach(t::addTradeIdentifier);
        if (tradeDate != null) t.setTradeDate(tradeDate);
        parties.forEach(t::addParty);
        if (contractDetails != null) t.setContractDetails(contractDetails);
        return TradeState.builder().setTrade(t.build()).build();
    }

    /** Bare price-1 location with just priceType=CashPrice (used for shaped-volume legs). */
    private PriceQuantity buildBarePriceLocation() {
        PriceSchedule.PriceScheduleBuilder psb = PriceSchedule.builder()
                .setPriceType(PriceTypeEnum.CASH_PRICE);
        return PriceQuantity.builder()
                .addPrice(FieldWithMetaPriceSchedule.builder()
                        .setValue(psb.build())
                        .setMeta(QuantityMapper.locationMeta("price-1"))
                        .build())
                .build();
    }

    private PriceQuantity buildSalvageFixedPriceQuantity(List<Element> fixedLegs) {
        PriceQuantity.PriceQuantityBuilder pqb = PriceQuantity.builder();
        if (fixedLegs.isEmpty()) return pqb.build();
        Element fxleg = fixedLegs.get(0);
        Element fixedPrice = XmlUtils.child(fxleg, "fixedPrice");
        if (fixedPrice == null) return pqb.build();
        String priceStr = XmlUtils.childText(fixedPrice, "price");
        String priceCcy = XmlUtils.childText(fixedPrice, "priceCurrency");
        String priceUnit = XmlUtils.childText(fixedPrice, "priceUnit");
        // Note: the FINOS reference also carries priceSubType=Fee on this price, but
        // CDM 6.19's PriceSchedule has no priceSubType attribute, so we cannot represent it.
        PriceSchedule.PriceScheduleBuilder psb = PriceSchedule.builder()
                .setPriceType(PriceTypeEnum.CASH_PRICE);
        if (priceStr != null) psb.setValue(new BigDecimal(priceStr));
        if (priceCcy != null) {
            psb.setUnit(UnitType.builder()
                    .setCurrency(FieldWithMetaString.builder().setValue(priceCcy).build())
                    .build());
        }
        if (priceUnit != null) {
            cdm.base.math.CapacityUnitEnum cu = mapCapacityUnit(priceUnit);
            if (cu != null) {
                psb.setPerUnitOf(UnitType.builder().setCapacityUnit(cu).build());
            }
        }
        pqb.addPrice(FieldWithMetaPriceSchedule.builder()
                .setValue(psb.build())
                .setMeta(QuantityMapper.locationMeta("price-1"))
                .build());
        return pqb.build();
    }

    static FieldWithMetaCommodityBusinessCalendarEnum mapCommodityBusinessCalendar(String name) {
        if (name == null) return null; String enumName = name.replace("-", "_").replace(" ", "_").toUpperCase();
        try { return FieldWithMetaCommodityBusinessCalendarEnum.builder().setValue(CommodityBusinessCalendarEnum.valueOf(enumName)).build(); } catch (IllegalArgumentException ignored) {}
        try { return FieldWithMetaCommodityBusinessCalendarEnum.builder().setValue(CommodityBusinessCalendarEnum.fromDisplayName(name)).build(); } catch (Exception ignored) {}
        return FieldWithMetaCommodityBusinessCalendarEnum.builder().build();
    }
    static cdm.base.math.CapacityUnitEnum mapCapacityUnit(String unit) {
        if (unit == null) return null;
        try { return cdm.base.math.CapacityUnitEnum.valueOf(unit); } catch (IllegalArgumentException ignored) {}
        try { return cdm.base.math.CapacityUnitEnum.fromDisplayName(unit); } catch (Exception ignored) {}
        // Try upper-cased (FpML often differs in casing, e.g. "MWh" -> CDM "MWH")
        String upper = unit.toUpperCase();
        try { return cdm.base.math.CapacityUnitEnum.valueOf(upper); } catch (IllegalArgumentException ignored) {}
        try { return cdm.base.math.CapacityUnitEnum.fromDisplayName(upper); } catch (Exception ignored) {}
        // Common aliases: FpML "GAL" → CDM USGAL (US gallon is the FpML default).
        if ("GAL".equalsIgnoreCase(unit)) return cdm.base.math.CapacityUnitEnum.USGAL;
        return null;
    }
    static Frequency mapFrequency(String freq) {
        if (freq == null) return null; Frequency.FrequencyBuilder fb = Frequency.builder();
        switch (freq) { case "PerCalendarDay": fb.setPeriodMultiplier(1).setPeriod(PeriodExtendedEnum.D); break; case "PerCalculationPeriod": case "PerSettlementPeriod": fb.setPeriodMultiplier(1).setPeriod(PeriodExtendedEnum.C); break; case "Term": fb.setPeriod(PeriodExtendedEnum.T); break; default: return null; }
        return fb.build();
    }
}
