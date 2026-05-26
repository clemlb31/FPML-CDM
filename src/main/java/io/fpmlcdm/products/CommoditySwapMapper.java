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
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);
        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        parties = SwapMapper.applyPartyTradeInformation(parties, tradeHeader);
        Element comSwap = XmlUtils.child(trade, "commoditySwap");
        List<Element> fixedLegs = XmlUtils.children(comSwap, "fixedLeg");
        List<Element> allFloatLike = new ArrayList<>(XmlUtils.children(comSwap, "floatingLeg"));
        allFloatLike.addAll(XmlUtils.children(comSwap, "coalPhysicalLeg"));
        allFloatLike.addAll(XmlUtils.children(comSwap, "gasPhysicalLeg"));
        allFloatLike.addAll(XmlUtils.children(comSwap, "oilPhysicalLeg"));
        allFloatLike.addAll(XmlUtils.children(comSwap, "electricityPhysicalLeg"));
        Element firstLeg = !allFloatLike.isEmpty() ? allFloatLike.get(0) : (!fixedLegs.isEmpty() ? fixedLegs.get(0) : null);
        if (firstLeg != null) assignCounterpartyRoles(firstLeg, ctx);
        String settlementCurrency = XmlUtils.childText(comSwap, "settlementCurrency");
        // Label layout (1-fixed-1-float case observed in commodity-5-10 references):
        //   payout[0] CommodityPayout       -> quantity-1 (float total), observable-1
        //   payout[1] FixedPricePayout      -> quantity-2 (fixed total), price-1
        //   priceQuantity[0] (Fixed PQ)     -> price-1 + quantity-3 (per-day) + quantity-2 (total)
        //   priceQuantity[1] (Float PQ)     -> price-2 (Asset/empty) + quantity-4 (per-day) + quantity-1 (total) + observable-1
        // For N float legs and M fixed legs we generalise: float-i total = quantity-i, fixed-j total = quantity-(N+j);
        // per-day labels are appended after totals. Prices: fixed-j -> price-j, float-i -> price-(M+i).
        List<Payout> payouts = new ArrayList<>();
        List<PriceQuantity> priceQuantities = new ArrayList<>();
        int nFloat = allFloatLike.size();
        int nFixed = fixedLegs.size();
        int floatPerDayBase = nFloat + nFixed; // first per-day quantity index for float legs starts at nFloat+nFixed+1
        // Build CommodityPayouts first (these are the float legs)
        for (int i = 0; i < nFloat; i++) {
            Element fleg = allFloatLike.get(i);
            String floatTotalLabel = "quantity-" + (i + 1); // float total -> quantity-(i+1)
            String floatPerDayLabel = "quantity-" + (floatPerDayBase + nFixed + i + 1); // float per-day comes after fixed per-days
            String obsLabel = "observable-" + (i + 1);
            String floatPriceLabel = "price-" + (nFixed + i + 1);
            CommodityPayout.CommodityPayoutBuilder cpb = CommodityPayout.builder();
            cpb.setPayerReceiver(buildPayerReceiver(fleg, ctx));
            cpb.setPriceQuantity(ResolvablePriceQuantity.builder().setQuantitySchedule(ReferenceWithMetaNonNegativeQuantitySchedule.builder().setReference(Reference.builder().setScope("DOCUMENT").setReference(floatTotalLabel).build()).build()).build());
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
            payouts.add(Payout.builder().setCommodityPayout(cpb.build()).build());
        }
        // Build FixedPricePayouts (and store them; their PQ will be added to priceQuantities first)
        List<Payout> fixedPayouts = new ArrayList<>();
        List<PriceQuantity> fixedPQs = new ArrayList<>();
        for (int j = 0; j < nFixed; j++) {
            Element fxleg = fixedLegs.get(j);
            String fixedTotalLabel = "quantity-" + (nFloat + j + 1); // fixed total -> quantity-(nFloat+j+1)
            String fixedPerDayLabel = "quantity-" + (floatPerDayBase + j + 1); // fixed per-day
            String fixedPriceLabel = "price-" + (j + 1);
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
            String floatPriceLabel = "price-" + (nFixed + i + 1);
            PriceQuantity.PriceQuantityBuilder pqb = PriceQuantity.builder();
            addFloatingPQ(pqb, fleg, floatTotalLabel, floatPerDayLabel, obsLabel, floatPriceLabel);
            priceQuantities.add(pqb.build());
        }
        // Payout order: [CommodityPayout(s), FixedPricePayout(s)]
        payouts.addAll(fixedPayouts);
        EconomicTerms.EconomicTermsBuilder econ = EconomicTerms.builder();
        for (Payout p : payouts) econ.addPayout(p);
        Element effectiveDateEl = XmlUtils.child(comSwap, "effectiveDate");
        if (effectiveDateEl != null) { Element adjDate = XmlUtils.child(effectiveDateEl, "adjustableDate"); if (adjDate != null) econ.setEffectiveDate(AdjustableOrRelativeDate.builder().setAdjustableDate(DateMapper.adjustable(adjDate)).build()); }
        Element terminationDateEl = XmlUtils.child(comSwap, "terminationDate");
        if (terminationDateEl != null) { Element adjDate = XmlUtils.child(terminationDateEl, "adjustableDate"); if (adjDate != null) econ.setTerminationDate(AdjustableOrRelativeDate.builder().setAdjustableDate(DateMapper.adjustable(adjDate)).build()); }
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
        ntp.addTaxonomy(ProductTaxonomy.builder().setSource(TaxonomySourceEnum.ISDA).setProductQualifier(qualifier).build());
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
        // 1. AssetPrice (no value, just unit + arithmeticOperator=Add)
        Element notionalQty = XmlUtils.child(fleg, "notionalQuantity");
        String unitText = notionalQty != null ? XmlUtils.childText(notionalQty, "quantityUnit") : null;
        PriceSchedule.PriceScheduleBuilder psb = PriceSchedule.builder().setPriceType(PriceTypeEnum.ASSET_PRICE).setArithmeticOperator(cdm.base.math.ArithmeticOperationEnum.ADD);
        cdm.base.math.CapacityUnitEnum capUnit = mapCapacityUnit(unitText);
        if (capUnit != null) psb.setPerUnitOf(UnitType.builder().setCapacityUnit(capUnit).build());
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
        int floatCount = XmlUtils.children(comSwap, "floatingLeg").size() + XmlUtils.children(comSwap, "coalPhysicalLeg").size() + XmlUtils.children(comSwap, "gasPhysicalLeg").size() + XmlUtils.children(comSwap, "oilPhysicalLeg").size() + XmlUtils.children(comSwap, "electricityPhysicalLeg").size();
        if (fixedCount > 0 && floatCount > 0) return "Commodity_Swap_FixedFloat";
        if (floatCount >= 2) return "Commodity_Swap_FloatFloat";
        return "Commodity_Swap_FixedFloat";
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
        return null;
    }
    static Frequency mapFrequency(String freq) {
        if (freq == null) return null; Frequency.FrequencyBuilder fb = Frequency.builder();
        switch (freq) { case "PerCalendarDay": fb.setPeriodMultiplier(1).setPeriod(PeriodExtendedEnum.D); break; case "PerCalculationPeriod": fb.setPeriodMultiplier(1).setPeriod(PeriodExtendedEnum.C); break; case "Term": fb.setPeriod(PeriodExtendedEnum.T); break; default: return null; }
        return fb.build();
    }
}
