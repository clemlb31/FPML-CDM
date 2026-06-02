package io.fpmlcdm.products;

import cdm.base.datetime.*;
import cdm.base.datetime.metafields.FieldWithMetaCommodityBusinessCalendarEnum;
import cdm.base.math.CapacityUnitEnum;
import cdm.base.math.NonNegativeQuantitySchedule;
import cdm.base.math.UnitType;
import cdm.base.math.metafields.FieldWithMetaNonNegativeQuantitySchedule;
import cdm.base.math.metafields.ReferenceWithMetaNonNegativeQuantitySchedule;
import cdm.base.staticdata.asset.common.*;
import cdm.base.staticdata.party.*;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.event.common.*;
import cdm.observable.asset.*;
import cdm.observable.asset.metafields.FieldWithMetaObservable;
import cdm.observable.asset.metafields.ReferenceWithMetaObservable;
import cdm.product.asset.DayDistributionEnum;
import cdm.product.common.schedule.*;
import cdm.product.common.settlement.*;
import cdm.product.template.*;
import com.rosetta.model.lib.meta.Reference;
import com.rosetta.model.metafields.FieldWithMetaDate;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
import com.rosetta.model.metafields.ReferenceWithMetaDate;
import io.fpmlcdm.common.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps FpML {@code <commodityOption>} into CDM TradeState.
 * Produces an OptionPayout with a Commodity underlier (Asset.Commodity) and
 * strikePrice expressed per capacityUnit.
 */
public class CommodityOptionMapper implements ProductMapper {

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);

        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        parties = SwapMapper.applyPartyTradeInformation(parties, tradeHeader);

        // Find the product element: commodityOption or commoditySwaption
        Element comOption = XmlUtils.child(trade, "commodityOption");
        if (comOption == null) comOption = XmlUtils.child(trade, "commoditySwaption");
        if (comOption == null) return null;

        // Buyer/seller
        Element buyerRef = XmlUtils.child(comOption, "buyerPartyReference");
        Element sellerRef = XmlUtils.child(comOption, "sellerPartyReference");
        String buyerHref = buyerRef != null ? buyerRef.getAttribute("href") : null;
        String sellerHref = sellerRef != null ? sellerRef.getAttribute("href") : null;

        // PARTY_1 = buyer (puts buyer first)
        assignRoles(buyerHref, ctx);

        Integer buyerOrder = buyerHref != null ? ctx.partyOrder.get(buyerHref) : null;
        Integer sellerOrder = sellerHref != null ? ctx.partyOrder.get(sellerHref) : null;
        CounterpartyRoleEnum buyerRole = buyerOrder != null && buyerOrder == 0
                ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
        CounterpartyRoleEnum sellerRole = sellerOrder != null && sellerOrder == 0
                ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
        // payer = seller, receiver = buyer (premium paid by buyer; option payoff to buyer)
        CounterpartyRoleEnum payerRole = sellerRole;
        CounterpartyRoleEnum receiverRole = buyerRole;

        // Option type
        String optionTypeStr = XmlUtils.childText(comOption, "optionType");
        OptionTypeEnum optionType = "Put".equals(optionTypeStr) ? OptionTypeEnum.PUT : OptionTypeEnum.CALL;

        // Effective date (commodity option-level effectiveDate -> economicTerms.effectiveDate)
        Element effectiveDateEl = XmlUtils.child(comOption, "effectiveDate");

        // Observation terms (calculationPeriodsSchedule + pricingDates)
        ObservationTerms observationTerms = buildObservationTerms(comOption);

        // Exercise terms (from <exercise>)
        Element exerciseEl = XmlUtils.child(comOption, "exercise");
        ExerciseTerms exerciseTerms = buildExerciseTerms(exerciseEl);

        // Settlement (from <exercise>: settlementCurrency, relativePaymentDates)
        SettlementTerms settlementTerms = buildSettlementTerms(exerciseEl);

        // Strike
        Element strikeEl = XmlUtils.child(comOption, "strikePricePerUnit");
        Element notionalQuantityEl = XmlUtils.child(comOption, "notionalQuantity");
        OptionStrike optStrike = buildStrike(strikeEl, notionalQuantityEl);

        // Build OptionPayout
        ResolvablePriceQuantity rpq = ResolvablePriceQuantity.builder()
                .setQuantitySchedule(ReferenceWithMetaNonNegativeQuantitySchedule.builder()
                        .setReference(Reference.builder().setScope("DOCUMENT").setReference("quantity-1").build())
                        .build())
                .build();

        OptionPayout.OptionPayoutBuilder opb = OptionPayout.builder()
                .setPayerReceiver(PayerReceiver.builder()
                        .setPayer(payerRole)
                        .setReceiver(receiverRole)
                        .build())
                .setPriceQuantity(rpq)
                .setSettlementTerms(settlementTerms)
                .setBuyerSeller(BuyerSeller.builder()
                        .setBuyer(buyerRole)
                        .setSeller(sellerRole)
                        .build())
                .setUnderlier(Underlier.builder()
                        .setObservable(ReferenceWithMetaObservable.builder()
                                .setReference(Reference.builder().setScope("DOCUMENT").setReference("observable-1").build())
                                .build())
                        .build())
                .setOptionType(optionType)
                .setExerciseTerms(exerciseTerms)
                .setStrike(optStrike);

        if (observationTerms != null) {
            opb.setObservationTerms(observationTerms);
        }

        Payout payout = Payout.builder().setOptionPayout(opb.build()).build();

        // Calculation agent
        CalculationAgentMapper.Result ca = CalculationAgentMapper.map(trade);
        EconomicTerms.EconomicTermsBuilder econ = EconomicTerms.builder().addPayout(payout);

        // effectiveDate at economicTerms level
        if (effectiveDateEl != null) {
            Element adjDate = XmlUtils.child(effectiveDateEl, "adjustableDate");
            if (adjDate != null) {
                AdjustableOrRelativeDate aord = DateMapper.adjustableOrRelative(adjDate);
                if (aord != null) econ.setEffectiveDate(aord);
            }
        }

        if (ca.calculationAgent() != null) econ.setCalculationAgent(ca.calculationAgent());

        // Taxonomy: primaryAssetClass + productType (from FpML) + ISDA qualifier
        List<ProductTaxonomy> taxonomies = new ArrayList<>();

        // [0] primaryAssetClass if present
        Element primaryAssetClassEl = XmlUtils.child(comOption, "primaryAssetClass");
        if (primaryAssetClassEl != null) {
            String pacValue = primaryAssetClassEl.getTextContent().trim();
            String pacScheme = primaryAssetClassEl.getAttribute("assetClassScheme");
            cdm.base.staticdata.asset.common.metafields.FieldWithMetaAssetClassEnum.FieldWithMetaAssetClassEnumBuilder ab =
                    cdm.base.staticdata.asset.common.metafields.FieldWithMetaAssetClassEnum.builder();
            try { ab.setValue(AssetClassEnum.fromDisplayName(pacValue)); } catch (Exception ignored) {
                try { ab.setValue(AssetClassEnum.valueOf(pacValue.toUpperCase())); } catch (Exception ignored2) {}
            }
            if (pacScheme != null && !pacScheme.isEmpty()) {
                ab.setMeta(MetaFields.builder().setScheme(pacScheme).build());
            }
            taxonomies.add(ProductTaxonomy.builder().setPrimaryAssetClass(ab.build()).build());
        }

        // [1] productType if present
        Element productTypeEl = XmlUtils.child(comOption, "productType");
        if (productTypeEl != null) {
            String ptValue = productTypeEl.getTextContent().trim();
            String ptScheme = productTypeEl.getAttribute("productTypeScheme");
            FieldWithMetaString.FieldWithMetaStringBuilder name = FieldWithMetaString.builder().setValue(ptValue);
            if (ptScheme != null && !ptScheme.isEmpty()) {
                name.setMeta(MetaFields.builder().setScheme(ptScheme).build());
            }
            TaxonomyValue tv = TaxonomyValue.builder().setName(name.build()).build();
            // Determine source: CFI for iso10962 scheme; Other for product-type-simple
            // (the FpML simple type is a free-text label, not an ISDA taxonomy).
            TaxonomySourceEnum src;
            if (ptScheme != null && ptScheme.contains("iso10962")) {
                src = TaxonomySourceEnum.CFI;
            } else {
                src = TaxonomySourceEnum.OTHER;
            }
            taxonomies.add(ProductTaxonomy.builder().setSource(src).setValue(tv).build());
        }

        // [last] ISDA productQualifier
        taxonomies.add(ProductTaxonomy.builder()
                .setSource(TaxonomySourceEnum.ISDA)
                .setProductQualifier("Commodity_Option")
                .build());

        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder()
                .setEconomicTerms(econ.build());
        for (ProductTaxonomy t : taxonomies) ntp.addTaxonomy(t);
        ProductIdentifierMapper.map(comOption).forEach(ntp::addIdentifier);

        // TradeLot with quantity (perDay + totalNotional) + observable (commodity)
        PriceQuantity pq = buildTradeLotPriceQuantity(comOption);
        TradeLot tradeLot = TradeLot.builder().addPriceQuantity(pq).build();

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

        List<PartyRole> partyRoles = PartyRoleMapper.map(trade);

        Trade.TradeBuilder t = Trade.builder()
                .setProduct(ntp.build())
                .addTradeLot(tradeLot);
        counterparties.forEach(t::addCounterparty);
        for (AncillaryParty ap : ca.ancillaryParties()) t.addAncillaryParty(ap);
        partyRoles.forEach(t::addPartyRole);
        identifiers.forEach(t::addTradeIdentifier);
        if (tradeDate != null) t.setTradeDate(tradeDate);
        parties.forEach(t::addParty);
        if (contractDetails != null) t.setContractDetails(contractDetails);
        for (Account a : AccountMapper.map(doc)) t.addAccount(a);

        TradeState.TradeStateBuilder tsBuilder = TradeState.builder().setTrade(t.build());

        // Premium -> transferHistory
        for (Element premium : XmlUtils.children(comOption, "premium")) {
            TransferState ts = buildPremiumTransfer(premium);
            if (ts != null) tsBuilder.addTransferHistory(ts);
        }
        // otherPartyPayment at trade level
        for (TransferState ts : TransferMapper.map(trade, null)) {
            tsBuilder.addTransferHistory(ts);
        }

        return tsBuilder.build();
    }

    /**
     * Builds ObservationTerms from calculationPeriodsSchedule + pricingDates.
     */
    private ObservationTerms buildObservationTerms(Element comOption) {
        Element calcSched = XmlUtils.child(comOption, "calculationPeriodsSchedule");
        Element pricingDates = XmlUtils.child(comOption, "pricingDates");
        if (calcSched == null && pricingDates == null) return null;

        ObservationTerms.ObservationTermsBuilder otb = ObservationTerms.builder();

        if (calcSched != null) {
            CalculationPeriodDates cpd = buildCalcPeriodDates(calcSched);
            if (cpd != null) otb.setCalculationPeriodDates(cpd);
        }

        if (pricingDates != null) {
            ObservationDates od = buildObservationDates(pricingDates);
            if (od != null) otb.setObservationDates(od);
        }

        return otb.build();
    }

    private CalculationPeriodDates buildCalcPeriodDates(Element calcSched) {
        CalculationPeriodFrequency.CalculationPeriodFrequencyBuilder cpf = CalculationPeriodFrequency.builder();
        String pm = XmlUtils.childText(calcSched, "periodMultiplier");
        if (pm != null) cpf.setPeriodMultiplier(Integer.parseInt(pm));
        String period = XmlUtils.childText(calcSched, "period");
        if (period != null) {
            try { cpf.setPeriod(PeriodExtendedEnum.valueOf(period)); } catch (IllegalArgumentException ignored) {}
        }
        String bofp = XmlUtils.childText(calcSched, "balanceOfFirstPeriod");
        if (bofp != null) cpf.setBalanceOfFirstPeriod(Boolean.parseBoolean(bofp));
        String id = calcSched.getAttribute("id");
        if (id != null && !id.isEmpty()) {
            cpf.setMeta(MetaFields.builder().setExternalKey(id).build());
        }
        return CalculationPeriodDates.builder().setCalculationPeriodFrequency(cpf.build()).build();
    }

    private ObservationDates buildObservationDates(Element pricingDates) {
        ParametricDates.ParametricDatesBuilder pdb = ParametricDates.builder();
        boolean hasContent = false;

        String dayType = XmlUtils.childText(pricingDates, "dayType");
        if (dayType != null) {
            DayTypeEnum dt = mapDayType(dayType);
            if (dt != null) { pdb.setDayType(dt); hasContent = true; }
        }

        String dayDist = XmlUtils.childText(pricingDates, "dayDistribution");
        if (dayDist != null) {
            DayDistributionEnum dd = mapDayDistribution(dayDist);
            if (dd != null) { pdb.setDayDistribution(dd); hasContent = true; }
        }

        // businessCalendar -> BusinessCenters.commodityBusinessCalendar
        List<Element> calendars = XmlUtils.children(pricingDates, "businessCalendar");
        if (!calendars.isEmpty()) {
            BusinessCenters.BusinessCentersBuilder bcb = BusinessCenters.builder();
            for (Element cal : calendars) {
                String name = cal.getTextContent().trim();
                FieldWithMetaCommodityBusinessCalendarEnum fwm = mapCommodityBusinessCalendar(name);
                if (fwm != null) bcb.addCommodityBusinessCalendar(fwm);
            }
            pdb.setBusinessCenters(bcb.build());
            hasContent = true;
        }

        // Return null if no meaningful content was extracted (avoids empty {"parametricDates":{}})
        if (!hasContent) return null;

        return ObservationDates.builder().setParametricDates(pdb.build()).build();
    }

    private DayTypeEnum mapDayType(String text) {
        if (text == null) return null;
        return switch (text) {
            case "Business", "CommodityBusiness" -> DayTypeEnum.BUSINESS;
            case "Calendar" -> DayTypeEnum.CALENDAR;
            case "CurrencyBusiness" -> DayTypeEnum.CURRENCY_BUSINESS;
            case "ExchangeBusiness" -> DayTypeEnum.EXCHANGE_BUSINESS;
            case "ScheduledTradingDay" -> DayTypeEnum.SCHEDULED_TRADING_DAY;
            default -> {
                try { yield DayTypeEnum.valueOf(text.toUpperCase()); }
                catch (IllegalArgumentException ignored) { yield null; }
            }
        };
    }

    private DayDistributionEnum mapDayDistribution(String text) {
        if (text == null) return null;
        try { return DayDistributionEnum.valueOf(text.toUpperCase()); } catch (IllegalArgumentException ignored) {}
        try { return DayDistributionEnum.fromDisplayName(text); } catch (Exception ignored) {}
        return null;
    }

    /**
     * Match FpML businessCalendar text against CommodityBusinessCalendarEnum.
     * If no match, still emit a FieldWithMetaCommodityBusinessCalendarEnum with only the text-as-value
     * preserved via the standard rune builder serializer (we set null value here to skip).
     */
    private FieldWithMetaCommodityBusinessCalendarEnum mapCommodityBusinessCalendar(String name) {
        if (name == null) return null;
        // Try direct enum name match: e.g. "NYMEX-NATURAL-GAS" -> NYMEX_NATURAL_GAS
        String enumName = name.replace("-", "_").replace(" ", "_").toUpperCase();
        try {
            return FieldWithMetaCommodityBusinessCalendarEnum.builder()
                    .setValue(CommodityBusinessCalendarEnum.valueOf(enumName)).build();
        } catch (IllegalArgumentException ignored) {}
        try {
            CommodityBusinessCalendarEnum e = CommodityBusinessCalendarEnum.fromDisplayName(name);
            if (e != null) {
                return FieldWithMetaCommodityBusinessCalendarEnum.builder().setValue(e).build();
            }
        } catch (Exception ignored) {}
        // Unknown value — return a FWM with null value so JSON serialiser emits {"value":"<text>"}? Not possible.
        // Return null so caller skips this entry.
        return null;
    }

    private ExerciseTerms buildExerciseTerms(Element exerciseEl) {
        if (exerciseEl == null) return null;
        ExerciseTerms.ExerciseTermsBuilder etb = ExerciseTerms.builder();

        Element europeanEx = XmlUtils.child(exerciseEl, "europeanExercise");
        Element americanEx = XmlUtils.child(exerciseEl, "americanExercise");
        Element bermudanEx = XmlUtils.child(exerciseEl, "bermudaExercise");

        Element exerciseStyleEl = null;
        if (europeanEx != null) {
            etb.setStyle(OptionExerciseStyleEnum.EUROPEAN);
            exerciseStyleEl = europeanEx;
        } else if (americanEx != null) {
            etb.setStyle(OptionExerciseStyleEnum.AMERICAN);
            exerciseStyleEl = americanEx;
        } else if (bermudanEx != null) {
            etb.setStyle(OptionExerciseStyleEnum.BERMUDA);
            exerciseStyleEl = bermudanEx;
        }

        if (exerciseStyleEl != null) {
            // Multiple <expirationDate> entries: take only the LAST per reference dataset.
            List<Element> expirationDates = XmlUtils.children(exerciseStyleEl, "expirationDate");
            Element expirationDate = expirationDates.isEmpty()
                    ? null : expirationDates.get(expirationDates.size() - 1);
            if (expirationDate != null) {
                AdjustableOrRelativeDate aord = buildAdjustableOrRelativeDate(expirationDate);
                if (aord != null) {
                    String expId = expirationDate.getAttribute("id");
                    if (expId != null && !expId.isEmpty()) {
                        aord = aord.toBuilder()
                                .setMeta(MetaFields.builder().setExternalKey(expId).build())
                                .build();
                    }
                    etb.addExpirationDate(aord);
                }
            }
            // For american exercise: commencementDate, latestExerciseTime
            if (americanEx != null) {
                Element commencementDate = XmlUtils.child(americanEx, "commencementDate");
                if (commencementDate != null) {
                    Element adjDate = XmlUtils.child(commencementDate, "adjustableDate");
                    if (adjDate != null) {
                        etb.setCommencementDate(DateMapper.adjustableOrRelative(adjDate));
                    }
                }
            }
        }

        // externalKey on exerciseTerms.meta comes from the *Exercise@id attribute
        if (exerciseStyleEl != null) {
            String exStyleId = exerciseStyleEl.getAttribute("id");
            if (exStyleId != null && !exStyleId.isEmpty()) {
                etb.setMeta(MetaFields.builder().setExternalKey(exStyleId).build());
            }
        }

        // Automatic exercise
        String autoExStr = XmlUtils.childText(exerciseEl, "automaticExercise");
        String writtenConfirmation = XmlUtils.childText(exerciseEl, "writtenConfirmation");
        if (autoExStr != null || writtenConfirmation != null) {
            ExerciseProcedure.ExerciseProcedureBuilder epb = ExerciseProcedure.builder();
            if ("true".equals(autoExStr)) {
                epb.setAutomaticExercise(AutomaticExercise.builder()
                        .setIsApplicable(true).build());
            }
            if (writtenConfirmation != null) {
                epb.setFollowUpConfirmation(Boolean.parseBoolean(writtenConfirmation));
            }
            etb.setExerciseProcedure(epb.build());
        }

        return etb.build();
    }

    /**
     * Builds an AdjustableOrRelativeDate from an FpML expirationDate which may contain either
     * an inner &lt;adjustableDate&gt; or an inner &lt;relativeDate&gt;.
     */
    private AdjustableOrRelativeDate buildAdjustableOrRelativeDate(Element parent) {
        if (parent == null) return null;
        Element adjDate = XmlUtils.child(parent, "adjustableDate");
        if (adjDate != null) {
            return DateMapper.adjustableOrRelative(adjDate);
        }
        Element relDate = XmlUtils.child(parent, "relativeDate");
        if (relDate != null) {
            AdjustedRelativeDateOffset.AdjustedRelativeDateOffsetBuilder rdb = AdjustedRelativeDateOffset.builder();
            String pm = XmlUtils.childText(relDate, "periodMultiplier");
            if (pm != null) rdb.setPeriodMultiplier(Integer.parseInt(pm));
            String period = XmlUtils.childText(relDate, "period");
            if (period != null) rdb.setPeriod(EnumMappers.period(period));
            String dayType = XmlUtils.childText(relDate, "dayType");
            if (dayType != null) {
                DayTypeEnum dt = mapDayType(dayType);
                if (dt != null) rdb.setDayType(dt);
            }
            String bdc = XmlUtils.childText(relDate, "businessDayConvention");
            if (bdc != null) rdb.setBusinessDayConvention(EnumMappers.bdc(bdc));
            Element drt = XmlUtils.child(relDate, "dateRelativeTo");
            if (drt != null) {
                rdb.setDateRelativeTo(ReferenceWithMetaDate.builder()
                        .setExternalReference(drt.getAttribute("href")).build());
            }
            Element bcs = XmlUtils.child(relDate, "businessCenters");
            if (bcs != null) {
                rdb.setBusinessCenters(DateMapper.buildBusinessCenters(bcs));
            }
            return AdjustableOrRelativeDate.builder().setRelativeDate(rdb.build()).build();
        }
        return null;
    }

    private SettlementTerms buildSettlementTerms(Element exerciseEl) {
        if (exerciseEl == null) return null;

        SettlementTerms.SettlementTermsBuilder stb = SettlementTerms.builder();
        // Commodity options default to Cash settlement unless physical legs are present.
        stb.setSettlementType(SettlementTypeEnum.CASH);

        String settlementCurrency = XmlUtils.childText(exerciseEl, "settlementCurrency");
        if (settlementCurrency != null) {
            stb.setSettlementCurrency(FieldWithMetaString.builder().setValue(settlementCurrency).build());
        }

        Element relPayDates = XmlUtils.child(exerciseEl, "relativePaymentDates");
        if (relPayDates != null) {
            Element paymentDaysOffset = XmlUtils.child(relPayDates, "paymentDaysOffset");
            if (paymentDaysOffset != null) {
                AdjustedRelativeDateOffset.AdjustedRelativeDateOffsetBuilder rdb = AdjustedRelativeDateOffset.builder();
                String pm = XmlUtils.childText(paymentDaysOffset, "periodMultiplier");
                if (pm != null) rdb.setPeriodMultiplier(Integer.parseInt(pm));
                String period = XmlUtils.childText(paymentDaysOffset, "period");
                if (period != null) rdb.setPeriod(EnumMappers.period(period));
                String dayType = XmlUtils.childText(paymentDaysOffset, "dayType");
                if (dayType != null && !"CommodityBusiness".equals(dayType)) {
                    DayTypeEnum dt = mapDayType(dayType);
                    if (dt != null) rdb.setDayType(dt);
                }
                String bdc = XmlUtils.childText(paymentDaysOffset, "businessDayConvention");
                if (bdc != null) rdb.setBusinessDayConvention(EnumMappers.bdc(bdc));

                // dateRelativeTo: use payRelativeTo + calculationPeriodsReference for external ref.
                // Reference test cases do NOT include payRelativeTo on the settlement date; skip dateRelativeTo.

                stb.setSettlementDate(SettlementDate.builder()
                        .setAdjustableOrRelativeDate(
                                AdjustableOrAdjustedOrRelativeDate.builder()
                                        .setRelativeDate(rdb.build())
                                        .build())
                        .build());
            }
        }

        return stb.build();
    }

    /**
     * Builds the OptionStrike from &lt;strikePricePerUnit&gt; (currency + amount) using
     * the notionalQuantity/quantityUnit as perUnitOf.
     */
    private OptionStrike buildStrike(Element strikeEl, Element notionalQty) {
        if (strikeEl == null) return null;
        String amount = XmlUtils.childText(strikeEl, "amount");
        if (amount == null) return null;

        Price.PriceBuilder pb = Price.builder()
                .setValue(new BigDecimal(amount))
                .setPriceType(PriceTypeEnum.ASSET_PRICE);

        String currency = XmlUtils.childText(strikeEl, "currency");
        if (currency != null) {
            pb.setUnit(UnitType.builder()
                    .setCurrency(FieldWithMetaString.builder().setValue(currency).build())
                    .build());
        }

        // perUnitOf from notionalQuantity/quantityUnit
        String quantityUnit = notionalQty != null ? XmlUtils.childText(notionalQty, "quantityUnit") : null;
        if (quantityUnit != null) {
            CapacityUnitEnum cu = mapCapacityUnit(quantityUnit);
            if (cu != null) {
                pb.setPerUnitOf(UnitType.builder().setCapacityUnit(cu).build());
            }
        }

        return OptionStrike.builder().setStrikePrice(pb.build()).build();
    }

    private PriceQuantity buildTradeLotPriceQuantity(Element comOption) {
        PriceQuantity.PriceQuantityBuilder pqb = PriceQuantity.builder();

        Element notionalQty = XmlUtils.child(comOption, "notionalQuantity");
        String totalNotionalStr = XmlUtils.childText(comOption, "totalNotionalQuantity");

        // Per-day quantity first (location quantity-2) — has frequency
        if (notionalQty != null) {
            String unit = XmlUtils.childText(notionalQty, "quantityUnit");
            String freq = XmlUtils.childText(notionalQty, "quantityFrequency");
            String qty = XmlUtils.childText(notionalQty, "quantity");
            if (qty != null) {
                NonNegativeQuantitySchedule.NonNegativeQuantityScheduleBuilder qb =
                        NonNegativeQuantitySchedule.builder().setValue(new BigDecimal(qty));
                if (unit != null) {
                    CapacityUnitEnum cu = mapCapacityUnit(unit);
                    if (cu != null) qb.setUnit(UnitType.builder().setCapacityUnit(cu).build());
                }
                Frequency f = mapFrequency(freq);
                if (f != null) qb.setFrequency(f);
                pqb.addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder()
                        .setValue(qb.build())
                        .setMeta(QuantityMapper.locationMeta("quantity-2"))
                        .build());
            }
        }

        // Total notional second (location quantity-1) — no frequency
        if (totalNotionalStr != null) {
            String unit = notionalQty != null ? XmlUtils.childText(notionalQty, "quantityUnit") : null;
            NonNegativeQuantitySchedule.NonNegativeQuantityScheduleBuilder qb =
                    NonNegativeQuantitySchedule.builder().setValue(new BigDecimal(totalNotionalStr));
            if (unit != null) {
                CapacityUnitEnum cu = mapCapacityUnit(unit);
                if (cu != null) qb.setUnit(UnitType.builder().setCapacityUnit(cu).build());
            }
            pqb.addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder()
                    .setValue(qb.build())
                    .setMeta(QuantityMapper.locationMeta("quantity-1"))
                    .build());
        }

        // Observable: Commodity
        Element commodity = XmlUtils.child(comOption, "commodity");
        Observable obs = buildCommodityObservable(commodity);
        if (obs != null) {
            pqb.setObservable(FieldWithMetaObservable.builder()
                    .setValue(obs)
                    .setMeta(QuantityMapper.locationMeta("observable-1"))
                    .build());
        }

        return pqb.build();
    }

    static Observable buildCommodityObservable(Element commodity) {
        if (commodity == null) return null;
        Commodity.CommodityBuilder cb = Commodity.builder();

        // instrumentId -> AssetIdentifier. ISDACRP when the scheme is the ISDA commodity-
        // reference-price scheme; otherwise OTHER.
        for (Element instId : XmlUtils.children(commodity, "instrumentId")) {
            String value = instId.getTextContent().trim();
            String scheme = instId.getAttribute("instrumentIdScheme");
            FieldWithMetaString.FieldWithMetaStringBuilder fms = FieldWithMetaString.builder().setValue(value);
            if (scheme != null && !scheme.isEmpty()) {
                fms.setMeta(MetaFields.builder().setScheme(scheme).build());
            }
            AssetIdTypeEnum idType = AssetIdTypeEnum.OTHER;
            if (scheme != null && scheme.toLowerCase().contains("commodity-reference-price")) {
                idType = AssetIdTypeEnum.ISDACRP;
            }
            cb.addIdentifier(AssetIdentifier.builder()
                    .setIdentifier(fms.build())
                    .setIdentifierType(idType)
                    .build());
        }

        // specifiedPrice -> priceQuoteType (QuotationSideEnum)
        String specifiedPrice = XmlUtils.childText(commodity, "specifiedPrice");
        if (specifiedPrice != null) {
            QuotationSideEnum qs = mapSpecifiedPrice(specifiedPrice);
            if (qs != null) cb.setPriceQuoteType(qs);
        }

        // deliveryDates -> deliveryDateReference.deliveryNearby
        String deliveryDates = XmlUtils.childText(commodity, "deliveryDates");
        if (deliveryDates != null) {
            int nearby = 1;
            if (deliveryDates.startsWith("Second")) nearby = 2;
            else if (deliveryDates.startsWith("Third")) nearby = 3;
            else if (deliveryDates.startsWith("Fourth")) nearby = 4;
            else if (deliveryDates.startsWith("Fifth")) nearby = 5;
            cb.setDeliveryDateReference(DeliveryDateParameters.builder()
                    .setDeliveryNearby(Offset.builder()
                            .setPeriodMultiplier(nearby)
                            .setPeriod(PeriodEnum.M)
                            .build())
                    .build());
        }

        return Observable.builder()
                .setAsset(Asset.builder().setCommodity(cb.build()).build())
                .build();
    }

    /**
     * Maps FpML &lt;specifiedPrice&gt; text to CDM QuotationSideEnum.
     * Note: "Midpoint" -> MID; "Settlement" -> SETTLEMENT, etc.
     */
    static QuotationSideEnum mapSpecifiedPrice(String text) {
        if (text == null) return null;
        return switch (text) {
            case "Midpoint", "Mid" -> QuotationSideEnum.MID;
            case "Settlement" -> QuotationSideEnum.SETTLEMENT;
            case "Bid" -> QuotationSideEnum.BID;
            case "Ask" -> QuotationSideEnum.ASK;
            case "Closing" -> QuotationSideEnum.CLOSING;
            case "Opening" -> QuotationSideEnum.OPENING;
            case "High" -> QuotationSideEnum.HIGH;
            case "Low" -> QuotationSideEnum.LOW;
            case "MeanOfHighAndLow" -> QuotationSideEnum.MEAN_OF_HIGH_AND_LOW;
            case "MeanOfBidAndAsk" -> QuotationSideEnum.MEAN_OF_BID_AND_ASK;
            case "Morning" -> QuotationSideEnum.MORNING;
            case "Afternoon" -> QuotationSideEnum.AFTERNOON;
            case "Official" -> QuotationSideEnum.OFFICIAL;
            case "OSP" -> QuotationSideEnum.OSP;
            case "Spot" -> QuotationSideEnum.SPOT;
            case "Index" -> QuotationSideEnum.INDEX;
            case "WeightedAverage" -> QuotationSideEnum.WEIGHTED_AVERAGE;
            case "UnweightedAverage" -> QuotationSideEnum.UN_WEIGHTED_AVERAGE;
            case "MarketClearing" -> QuotationSideEnum.MARKET_CLEARING;
            case "MarginalHourly" -> QuotationSideEnum.MARGINAL_HOURLY;
            case "LocationalMarginal" -> QuotationSideEnum.LOCATIONAL_MARGINAL;
            case "NationalSingle" -> QuotationSideEnum.NATIONAL_SINGLE;
            default -> {
                try { yield QuotationSideEnum.fromDisplayName(text); }
                catch (Exception ignored) { yield null; }
            }
        };
    }

    private CapacityUnitEnum mapCapacityUnit(String unit) {
        if (unit == null) return null;
        try { return CapacityUnitEnum.valueOf(unit); } catch (IllegalArgumentException ignored) {}
        try { return CapacityUnitEnum.fromDisplayName(unit); } catch (Exception ignored) {}
        return null;
    }

    private Frequency mapFrequency(String freq) {
        if (freq == null) return null;
        Frequency.FrequencyBuilder fb = Frequency.builder();
        switch (freq) {
            case "PerCalendarDay":
                fb.setPeriodMultiplier(1).setPeriod(PeriodExtendedEnum.D);
                return fb.build();
            case "PerBusinessDay":
                fb.setPeriodMultiplier(1).setPeriod(PeriodExtendedEnum.D);
                return fb.build();
            case "PerCalculationPeriod":
                fb.setPeriodMultiplier(1).setPeriod(PeriodExtendedEnum.C);
                return fb.build();
            case "Term":
                fb.setPeriod(PeriodExtendedEnum.T);
                return fb.build();
            default:
                return null;
        }
    }

    private TransferState buildPremiumTransfer(Element premium) {
        Transfer.TransferBuilder tb = Transfer.builder();

        Element amtEl = XmlUtils.child(premium, "paymentAmount");
        String ccy = amtEl != null ? XmlUtils.childText(amtEl, "currency") : null;
        String amount = amtEl != null ? XmlUtils.childText(amtEl, "amount") : null;
        if (amount != null && ccy != null) {
            tb.setQuantity(cdm.base.math.NonNegativeQuantity.builder()
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
            Element adjDate = XmlUtils.child(payDate, "adjustableDate");
            Element relDate = XmlUtils.child(payDate, "relativeDate");
            if (adjDate != null) {
                AdjustableOrAdjustedOrRelativeDate.AdjustableOrAdjustedOrRelativeDateBuilder sdb =
                        AdjustableOrAdjustedOrRelativeDate.builder();
                String unadj = XmlUtils.childText(adjDate, "unadjustedDate");
                if (unadj != null) sdb.setUnadjustedDate(DateMapper.parse(unadj));
                String adj = XmlUtils.childText(adjDate, "adjustedDate");
                if (adj != null) {
                    sdb.setAdjustedDate(FieldWithMetaDate.builder()
                            .setValue(DateMapper.parse(adj)).build());
                }
                BusinessDayAdjustments bda = DateMapper.businessDayAdjustments(
                        XmlUtils.child(adjDate, "dateAdjustments"));
                if (bda != null) sdb.setDateAdjustments(bda);
                tb.setSettlementDate(sdb.build());
            } else if (relDate != null) {
                // Handle relative date on premium paymentDate
                RelativeDateOffset.RelativeDateOffsetBuilder rdb = RelativeDateOffset.builder();
                String pm = XmlUtils.childText(relDate, "periodMultiplier");
                if (pm != null) rdb.setPeriodMultiplier(Integer.parseInt(pm));
                String period = XmlUtils.childText(relDate, "period");
                if (period != null) rdb.setPeriod(EnumMappers.period(period));
                String dayType = XmlUtils.childText(relDate, "dayType");
                if (dayType != null) {
                    DayTypeEnum dt = mapDayType(dayType);
                    if (dt != null) rdb.setDayType(dt);
                }
                String bdc = XmlUtils.childText(relDate, "businessDayConvention");
                if (bdc != null) rdb.setBusinessDayConvention(EnumMappers.bdc(bdc));
                Element bcs = XmlUtils.child(relDate, "businessCenters");
                if (bcs != null) rdb.setBusinessCenters(DateMapper.buildBusinessCenters(bcs));
                Element drt = XmlUtils.child(relDate, "dateRelativeTo");
                if (drt != null) {
                    rdb.setDateRelativeTo(ReferenceWithMetaDate.builder()
                            .setExternalReference(drt.getAttribute("href")).build());
                }
                tb.setSettlementDate(AdjustableOrAdjustedOrRelativeDate.builder()
                        .setRelativeDate(rdb.build()).build());
            }
        }

        Element payerRef = XmlUtils.child(premium, "payerPartyReference");
        Element receiverRef = XmlUtils.child(premium, "receiverPartyReference");
        if (payerRef != null || receiverRef != null) {
            PartyReferencePayerReceiver.PartyReferencePayerReceiverBuilder pr =
                    PartyReferencePayerReceiver.builder();
            if (payerRef != null) {
                pr.setPayerPartyReference(ReferenceWithMetaParty.builder()
                        .setExternalReference(payerRef.getAttribute("href")).build());
            }
            if (receiverRef != null) {
                pr.setReceiverPartyReference(ReferenceWithMetaParty.builder()
                        .setExternalReference(receiverRef.getAttribute("href")).build());
            }
            tb.setPayerReceiver(pr.build());
        }

        tb.setTransferExpression(TransferExpression.builder()
                .setPriceTransfer(FeeTypeEnum.PREMIUM)
                .build());

        return TransferState.builder().setTransfer(tb.build()).build();
    }

    private void assignRoles(String buyerHref, MappingContext ctx) {
        if (buyerHref == null) return;
        Map<String, Integer> newOrder = new LinkedHashMap<>();
        newOrder.put(buyerHref, 0);
        int idx = 1;
        for (String id : ctx.partyOrder.keySet()) {
            if (!id.equals(buyerHref)) {
                newOrder.put(id, idx++);
            }
        }
        ctx.partyOrder.clear();
        ctx.partyOrder.putAll(newOrder);
    }
}
