package io.fpmlcdm.fpml.cdm.products;

import cdm.base.datetime.*;
import cdm.base.datetime.metafields.FieldWithMetaBusinessCenterEnum;
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
import cdm.product.common.settlement.*;
import cdm.product.template.*;
import com.rosetta.model.lib.meta.Key;
import com.rosetta.model.lib.meta.Reference;
import com.rosetta.model.metafields.FieldWithMetaDate;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
import com.rosetta.model.metafields.ReferenceWithMetaDate;
import io.fpmlcdm.fpml.cdm.common.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps FpML {@code <equityOption>} and {@code <brokerEquityOption>} into CDM TradeState.
 * Produces an OptionPayout with equity underlier (stock or index).
 */
public class EquityOptionMapper implements ProductMapper {

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);

        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        parties = SwapMapper.applyPartyTradeInformation(parties, tradeHeader);

        // Find the product element: equityOption or brokerEquityOption
        Element eqOption = XmlUtils.child(trade, "equityOption");
        if (eqOption == null) eqOption = XmlUtils.child(trade, "brokerEquityOption");
        if (eqOption == null) eqOption = XmlUtils.child(trade, "equityOptionTransactionSupplement");

        // Buyer/seller
        Element buyerRef = XmlUtils.child(eqOption, "buyerPartyReference");
        Element sellerRef = XmlUtils.child(eqOption, "sellerPartyReference");
        String buyerHref = buyerRef != null ? buyerRef.getAttribute("href") : null;
        String sellerHref = sellerRef != null ? sellerRef.getAttribute("href") : null;

        // PARTY_1 = buyer
        assignRoles(buyerHref, ctx);

        Integer buyerOrder = buyerHref != null ? ctx.partyOrder.get(buyerHref) : null;
        Integer sellerOrder = sellerHref != null ? ctx.partyOrder.get(sellerHref) : null;
        CounterpartyRoleEnum buyerRole = buyerOrder != null && buyerOrder == 0
                ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
        CounterpartyRoleEnum sellerRole = sellerOrder != null && sellerOrder == 0
                ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
        // payer = seller, receiver = buyer
        CounterpartyRoleEnum payerRole = sellerRole;
        CounterpartyRoleEnum receiverRole = buyerRole;

        // Option type
        String optionTypeStr = XmlUtils.childText(eqOption, "optionType");
        OptionTypeEnum optionType = "Put".equals(optionTypeStr) ? OptionTypeEnum.PUT : OptionTypeEnum.CALL;

        // Exercise terms
        Element equityExercise = XmlUtils.child(eqOption, "equityExercise");
        ExerciseTerms exerciseTerms = buildExerciseTerms(equityExercise);

        // Settlement
        SettlementTerms settlementTerms = buildSettlementTerms(equityExercise);

        // Underlyer
        Element underlyer = XmlUtils.child(eqOption, "underlyer");

        // Strike
        Element strikeEl = XmlUtils.child(eqOption, "strike");
        boolean isSupplement = "equityOptionTransactionSupplement".equals(eqOption.getLocalName());
        OptionStrike optStrike = buildStrike(strikeEl, underlyer, equityExercise, isSupplement);

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

        // Feature: passThrough (single-name only — for basket the reference omits the feature
        // even though FpML includes it; cf. eqd-ex15 vs eqd-ex14)
        OptionFeature passThroughFeature = buildPassThroughFeature(eqOption, underlyer, ctx);
        if (passThroughFeature != null) opb.setFeature(passThroughFeature);

        Payout payout = Payout.builder().setOptionPayout(opb.build()).build();

        // Calculation agent
        CalculationAgentMapper.Result ca = CalculationAgentMapper.map(trade);
        EconomicTerms.EconomicTermsBuilder econ = EconomicTerms.builder().addPayout(payout);
        if (ca.calculationAgent() != null) econ.setCalculationAgent(ca.calculationAgent());

        // Taxonomy
        List<ProductTaxonomy> taxonomies = buildEquityOptionTaxonomy(eqOption, underlyer);

        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder()
                .setEconomicTerms(econ.build());
        for (ProductTaxonomy t : taxonomies) ntp.addTaxonomy(t);
        ProductIdentifierMapper.map(eqOption).forEach(ntp::addIdentifier);

        // TradeLot with quantity + observable
        PriceQuantity pq = buildTradeLotPriceQuantity(eqOption, underlyer);
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

        // Contract details (pass trade for governingLaw)
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

        // Equity premium -> transferHistory
        for (Element premium : XmlUtils.children(eqOption, "equityPremium")) {
            TransferState ts = buildPremiumTransfer(premium);
            if (ts != null) tsBuilder.addTransferHistory(ts);
        }
        // otherPartyPayment at trade level
        for (TransferState ts : TransferMapper.map(trade, null)) {
            tsBuilder.addTransferHistory(ts);
        }

        return tsBuilder.build();
    }

    private ExerciseTerms buildExerciseTerms(Element equityExercise) {
        if (equityExercise == null) return null;
        ExerciseTerms.ExerciseTermsBuilder etb = ExerciseTerms.builder();

        Element europeanEx = XmlUtils.child(equityExercise, "equityEuropeanExercise");
        Element americanEx = XmlUtils.child(equityExercise, "equityAmericanExercise");
        Element bermudanEx = XmlUtils.child(equityExercise, "equityBermudaExercise");
        boolean isEuropean = europeanEx != null;
        boolean isAmerican = americanEx != null;

        if (isEuropean) {
            etb.setStyle(OptionExerciseStyleEnum.EUROPEAN);
            buildExerciseDates(etb, europeanEx);
        } else if (isAmerican) {
            etb.setStyle(OptionExerciseStyleEnum.AMERICAN);
            buildExerciseDates(etb, americanEx);
            // Commencement date
            Element commencementDate = XmlUtils.child(americanEx, "commencementDate");
            if (commencementDate != null) {
                Element adjDate = XmlUtils.child(commencementDate, "adjustableDate");
                if (adjDate != null) {
                    etb.setCommencementDate(DateMapper.adjustableOrRelative(adjDate));
                }
            }
            // Latest exercise time
            Element latestExTime = XmlUtils.child(americanEx, "latestExerciseTime");
            if (latestExTime != null) {
                etb.setLatestExerciseTime(buildBusinessCenterTime(latestExTime));
            }
            // Multiple exercise
            Element multipleExercise = XmlUtils.child(americanEx, "equityMultipleExercise");
            if (multipleExercise != null) {
                MultipleExercise.MultipleExerciseBuilder meb = MultipleExercise.builder();
                String integral = XmlUtils.childText(multipleExercise, "integralMultipleExercise");
                if (integral != null) meb.setIntegralMultipleAmount(new BigDecimal(integral));
                String minOptions = XmlUtils.childText(multipleExercise, "minimumNumberOfOptions");
                if (minOptions != null) meb.setMinimumNumberOfOptions(Integer.parseInt(minOptions));
                String maxOptions = XmlUtils.childText(multipleExercise, "maximumNumberOfOptions");
                if (maxOptions != null) meb.setMaximumNumberOfOptions(Integer.parseInt(maxOptions));
                etb.setMultipleExercise(meb.build());
            }
        } else if (bermudanEx != null) {
            etb.setStyle(OptionExerciseStyleEnum.BERMUDA);
            buildExerciseDates(etb, bermudanEx);
            // commencementDate
            Element commencementDate = XmlUtils.child(bermudanEx, "commencementDate");
            if (commencementDate != null) {
                Element adjDate = XmlUtils.child(commencementDate, "adjustableDate");
                if (adjDate != null) {
                    etb.setCommencementDate(DateMapper.adjustableOrRelative(adjDate));
                }
            }
            // bermudaExerciseDates → AdjustableOrRelativeDates with adjustedDate[]
            Element bermudaDatesEl = XmlUtils.child(bermudanEx, "bermudaExerciseDates");
            if (bermudaDatesEl != null) {
                cdm.base.datetime.AdjustableDates.AdjustableDatesBuilder adb =
                        cdm.base.datetime.AdjustableDates.builder();
                for (Element d : XmlUtils.children(bermudaDatesEl, "date")) {
                    adb.addAdjustedDate(com.rosetta.model.metafields.FieldWithMetaDate.builder()
                            .setValue(DateMapper.parse(d.getTextContent().trim()))
                            .build());
                }
                etb.setExerciseDates(cdm.base.datetime.AdjustableOrRelativeDates.builder()
                        .setAdjustableDates(adb.build()).build());
            }
            // equityMultipleExercise
            Element multipleExercise = XmlUtils.child(bermudanEx, "equityMultipleExercise");
            if (multipleExercise != null) {
                MultipleExercise.MultipleExerciseBuilder meb = MultipleExercise.builder();
                String integral = XmlUtils.childText(multipleExercise, "integralMultipleExercise");
                if (integral != null) meb.setIntegralMultipleAmount(new BigDecimal(integral));
                String minOptions = XmlUtils.childText(multipleExercise, "minimumNumberOfOptions");
                if (minOptions != null) meb.setMinimumNumberOfOptions(Integer.parseInt(minOptions));
                String maxOptions = XmlUtils.childText(multipleExercise, "maximumNumberOfOptions");
                if (maxOptions != null) meb.setMaximumNumberOfOptions(Integer.parseInt(maxOptions));
                etb.setMultipleExercise(meb.build());
            }
        }

        // Automatic exercise
        String autoExStr = XmlUtils.childText(equityExercise, "automaticExercise");
        if ("true".equals(autoExStr)) {
            etb.setExerciseProcedure(ExerciseProcedure.builder()
                    .setAutomaticExercise(AutomaticExercise.builder()
                            .setIsApplicable(true)
                            .build())
                    .build());
        }

        // Expiration time type from equityValuation
        Element valuation = XmlUtils.child(equityExercise, "equityValuation");
        if (valuation != null) {
            String valuationTimeType = XmlUtils.childText(valuation, "valuationTimeType");
            if (valuationTimeType != null) {
                etb.setExpirationTimeType(mapExpirationTimeType(valuationTimeType));
            }
        }

        // Expiration time type from exercise element
        Element exerciseEl = isEuropean ? europeanEx : (isAmerican ? americanEx : bermudanEx);
        if (exerciseEl != null) {
            String equityExpTimeType = XmlUtils.childText(exerciseEl, "equityExpirationTimeType");
            if (equityExpTimeType != null) {
                etb.setExpirationTimeType(mapExpirationTimeType(equityExpTimeType));
            }
        }

        return etb.build();
    }

    private void buildExerciseDates(ExerciseTerms.ExerciseTermsBuilder etb, Element exerciseEl) {
        Element expirationDate = XmlUtils.child(exerciseEl, "expirationDate");
        if (expirationDate != null) {
            Element adjDate = XmlUtils.child(expirationDate, "adjustableDate");
            if (adjDate != null) {
                AdjustableOrRelativeDate aord = DateMapper.adjustableOrRelative(adjDate);
                if (aord != null) {
                    // Preserve externalKey from expirationDate id attribute
                    String expId = expirationDate.getAttribute("id");
                    if (expId != null && !expId.isEmpty()) {
                        aord = aord.toBuilder()
                                .setMeta(MetaFields.builder().setExternalKey(expId).build())
                                .build();
                    }
                    etb.addExpirationDate(aord);
                }
            }
        }
    }

    private ExpirationTimeTypeEnum mapExpirationTimeType(String text) {
        if (text == null) return null;
        return switch (text) {
            case "Close" -> ExpirationTimeTypeEnum.CLOSE;
            case "OSP" -> ExpirationTimeTypeEnum.OSP;
            case "SpecificTime" -> ExpirationTimeTypeEnum.SPECIFIC_TIME;
            case "XETRA" -> ExpirationTimeTypeEnum.XETRA;
            case "DerivativesClose" -> ExpirationTimeTypeEnum.DERIVATIVES_CLOSE;
            case "AsSpecifiedInMasterConfirmation" -> ExpirationTimeTypeEnum.AS_SPECIFIED_IN_MASTER_CONFIRMATION;
            default -> {
                try { yield ExpirationTimeTypeEnum.valueOf(text.toUpperCase()); }
                catch (IllegalArgumentException ignored) { yield null; }
            }
        };
    }

    private BusinessCenterTime buildBusinessCenterTime(Element el) {
        if (el == null) return null;
        BusinessCenterTime.BusinessCenterTimeBuilder bctb = BusinessCenterTime.builder();
        String hmt = XmlUtils.childText(el, "hourMinuteTime");
        if (hmt != null) bctb.setHourMinuteTime(LocalTime.parse(hmt));
        String bc = XmlUtils.childText(el, "businessCenter");
        if (bc != null) {
            try {
                bctb.setBusinessCenter(FieldWithMetaBusinessCenterEnum.builder()
                        .setValue(BusinessCenterEnum.valueOf(bc))
                        .build());
            } catch (IllegalArgumentException ignored) {}
        }
        return bctb.build();
    }

    private SettlementTerms buildSettlementTerms(Element equityExercise) {
        if (equityExercise == null) return null;
        SettlementTerms.SettlementTermsBuilder stb = SettlementTerms.builder();

        String settlementType = XmlUtils.childText(equityExercise, "settlementType");
        if (settlementType != null) {
            stb.setSettlementType(mapSettlementType(settlementType));
        }

        String settlementCurrency = XmlUtils.childText(equityExercise, "settlementCurrency");
        if (settlementCurrency != null) {
            stb.setSettlementCurrency(FieldWithMetaString.builder().setValue(settlementCurrency).build());
        }

        // Settlement date (from equityExercise/settlementDate)
        Element settlementDate = XmlUtils.child(equityExercise, "settlementDate");
        if (settlementDate != null) {
            Element relDate = XmlUtils.child(settlementDate, "relativeDate");
            if (relDate != null) {
                RelativeDateOffset.RelativeDateOffsetBuilder rdb = RelativeDateOffset.builder();
                String pm = XmlUtils.childText(relDate, "periodMultiplier");
                if (pm != null) rdb.setPeriodMultiplier(Integer.parseInt(pm));
                String period = XmlUtils.childText(relDate, "period");
                if (period != null) rdb.setPeriod(EnumMappers.period(period));
                String dayType = XmlUtils.childText(relDate, "dayType");
                if (dayType != null) {
                    rdb.setDayType(mapDayType(dayType));
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

                stb.setSettlementDate(SettlementDate.builder()
                        .setAdjustableOrRelativeDate(
                                cdm.base.datetime.AdjustableOrAdjustedOrRelativeDate.builder()
                                        .setRelativeDate(rdb.build())
                                        .build())
                        .build());
            }
        }

        return stb.build();
    }

    private SettlementTypeEnum mapSettlementType(String text) {
        if (text == null) return null;
        return switch (text) {
            case "Cash" -> SettlementTypeEnum.CASH;
            case "Physical" -> SettlementTypeEnum.PHYSICAL;
            case "Election" -> SettlementTypeEnum.ELECTION;
            case "CashOrPhysical" -> SettlementTypeEnum.CASH_OR_PHYSICAL;
            default -> {
                try { yield SettlementTypeEnum.valueOf(text.toUpperCase()); }
                catch (IllegalArgumentException ignored) { yield null; }
            }
        };
    }

    private OptionStrike buildStrike(Element strikeEl, Element underlyer, Element equityExercise,
                                     boolean isSupplement) {
        if (strikeEl == null) return null;
        String strikePriceStr = XmlUtils.childText(strikeEl, "strikePrice");
        String strikePercentageStr = XmlUtils.childText(strikeEl, "strikePercentage");
        if (strikePriceStr == null && strikePercentageStr == null) return null;
        if (strikePriceStr == null) strikePriceStr = strikePercentageStr;

        BigDecimal strikeValue = new BigDecimal(strikePriceStr);

        // Determine if underlyer is index or equity
        boolean isIndex = false;
        if (underlyer != null) {
            Element singleUnderlyer = XmlUtils.child(underlyer, "singleUnderlyer");
            if (singleUnderlyer != null) {
                isIndex = XmlUtils.child(singleUnderlyer, "index") != null;
            }
        }

        // Currency precedence: explicit <strike><currency> (used for ND-share with
        // local-currency strike) > settlementCurrency. For equityOptionTransactionSupplement
        // the FINOS reference omits the currency unit entirely unless an explicit
        // <strike><currency> overrides — the settlement currency from equityExercise is
        // not promoted into the strike.
        String currency = XmlUtils.childText(strikeEl, "currency");
        if (currency == null && equityExercise != null && !isSupplement) {
            currency = XmlUtils.childText(equityExercise, "settlementCurrency");
        }

        Price.PriceBuilder pb = Price.builder()
                .setValue(strikeValue)
                .setPriceType(PriceTypeEnum.ASSET_PRICE);

        if (currency != null) {
            pb.setUnit(UnitType.builder()
                    .setCurrency(FieldWithMetaString.builder().setValue(currency).build())
                    .build());
        }

        if (isIndex) {
            pb.setPerUnitOf(UnitType.builder()
                    .setFinancialUnit(cdm.base.math.FinancialUnitEnum.INDEX_UNIT)
                    .build());
        } else {
            pb.setPerUnitOf(UnitType.builder()
                    .setFinancialUnit(cdm.base.math.FinancialUnitEnum.SHARE)
                    .build());
        }

        return OptionStrike.builder().setStrikePrice(pb.build()).build();
    }

    private PriceQuantity buildTradeLotPriceQuantity(Element eqOption, Element underlyer) {
        PriceQuantity.PriceQuantityBuilder pqb = PriceQuantity.builder();

        // Number of options OR notional amount. When both are present (typical for
        // equityOptionTransactionSupplement index/cliquet examples), emit notional as
        // quantity-1 and numberOfOptions as quantity-2 to match the FINOS reference.
        String numberOfOptions = XmlUtils.childText(eqOption, "numberOfOptions");
        String optionEntitlement = XmlUtils.childText(eqOption, "optionEntitlement");
        Element notionalEl = XmlUtils.child(eqOption, "notional");

        int qIdx = 1;
        if (notionalEl != null) {
            String notionalCcy = XmlUtils.childText(notionalEl, "currency");
            String notionalAmt = XmlUtils.childText(notionalEl, "amount");
            if (notionalAmt != null) {
                NonNegativeQuantitySchedule.NonNegativeQuantityScheduleBuilder qsb =
                        NonNegativeQuantitySchedule.builder()
                                .setValue(new BigDecimal(notionalAmt));
                if (notionalCcy != null) {
                    qsb.setUnit(UnitType.builder()
                            .setCurrency(FieldWithMetaString.builder().setValue(notionalCcy).build())
                            .build());
                }
                pqb.addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder()
                        .setValue(qsb.build())
                        .setMeta(QuantityMapper.locationMeta("quantity-" + (qIdx++)))
                        .build());
            }
        }
        if (numberOfOptions != null) {
            NonNegativeQuantitySchedule.NonNegativeQuantityScheduleBuilder qsb =
                    NonNegativeQuantitySchedule.builder()
                            .setValue(new BigDecimal(numberOfOptions))
                            .setUnit(UnitType.builder()
                                    .setFinancialUnit(cdm.base.math.FinancialUnitEnum.CONTRACT)
                                    .build());
            if (optionEntitlement != null) {
                qsb.setMultiplier(cdm.base.math.Measure.builder()
                        .setValue(new BigDecimal(optionEntitlement))
                        .setUnit(UnitType.builder()
                                .setFinancialUnit(cdm.base.math.FinancialUnitEnum.SHARE)
                                .build())
                        .build());
            }
            pqb.addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder()
                    .setValue(qsb.build())
                    .setMeta(QuantityMapper.locationMeta("quantity-" + (qIdx++)))
                    .build());
        }

        // Observable: equity or index
        Observable observable = buildEquityObservable(underlyer);
        if (observable != null) {
            pqb.setObservable(FieldWithMetaObservable.builder()
                    .setValue(observable)
                    .setMeta(QuantityMapper.locationMeta("observable-1"))
                    .build());
        }

        return pqb.build();
    }

    static Observable buildEquityObservable(Element underlyer) {
        if (underlyer == null) return null;
        Element singleUnderlyer = XmlUtils.child(underlyer, "singleUnderlyer");
        if (singleUnderlyer != null) {
            // Try equity (single stock)
            Element equity = XmlUtils.child(singleUnderlyer, "equity");
            if (equity != null) {
                return buildStockObservable(equity);
            }
            // Try index
            Element index = XmlUtils.child(singleUnderlyer, "index");
            if (index != null) {
                return buildIndexObservable(index);
            }
        }
        // Basket underlyer
        Element basket = XmlUtils.child(underlyer, "basket");
        if (basket != null) {
            return buildBasketObservable(basket);
        }
        return null;
    }

    static Observable buildBondObservable(Element bond) {
        Security.SecurityBuilder secb = Security.builder();
        for (Element instId : XmlUtils.children(bond, "instrumentId")) {
            String value = instId.getTextContent().trim();
            String scheme = instId.getAttribute("instrumentIdScheme");
            AssetIdentifier.AssetIdentifierBuilder aib = AssetIdentifier.builder()
                    .setIdentifier(FieldWithMetaString.builder()
                            .setValue(value)
                            .setMeta(scheme != null && !scheme.isEmpty()
                                    ? MetaFields.builder().setScheme(scheme).build() : null)
                            .build())
                    .setIdentifierType(mapAssetIdType(scheme));
            secb.addIdentifier(aib.build());
        }
        String description = XmlUtils.childText(bond, "description");
        if (description != null) {
            secb.addIdentifier(AssetIdentifier.builder()
                    .setIdentifier(FieldWithMetaString.builder().setValue(description).build())
                    .setIdentifierType(AssetIdTypeEnum.NAME)
                    .build());
        }
        return Observable.builder()
                .setAsset(Asset.builder()
                        .setInstrument(Instrument.builder()
                                .setSecurity(secb.build())
                                .build())
                        .build())
                .build();
    }

    static Observable buildBasketObservable(Element basket) {
        cdm.observable.asset.Basket.BasketBuilder bb = cdm.observable.asset.Basket.builder();
        int idx = 0;
        for (Element bcEl : XmlUtils.children(basket, "basketConstituent")) {
            idx++;
            cdm.observable.asset.BasketConstituent.BasketConstituentBuilder cb =
                    cdm.observable.asset.BasketConstituent.builder();
            Element eq = XmlUtils.child(bcEl, "equity");
            Element ix = XmlUtils.child(bcEl, "index");
            Element bd = XmlUtils.child(bcEl, "bond");
            Element cm = XmlUtils.child(bcEl, "commodity");
            if (eq != null) {
                Observable inner = buildStockObservable(eq);
                if (inner != null && inner.getAsset() != null) cb.setAsset(inner.getAsset());
            } else if (bd != null) {
                Observable inner = buildBondObservable(bd);
                if (inner != null && inner.getAsset() != null) cb.setAsset(inner.getAsset());
            } else if (cm != null) {
                Observable inner = CommodityOptionMapper.buildCommodityObservable(cm);
                if (inner != null && inner.getAsset() != null) cb.setAsset(inner.getAsset());
            } else if (ix != null) {
                Observable inner = buildIndexObservable(ix);
                if (inner != null && inner.getIndex() != null) cb.setIndex(inner.getIndex());
            }
            // constituentWeight/basketPercentage → quantity[0] with financialUnit=Weight
            Element weight = XmlUtils.child(bcEl, "constituentWeight");
            if (weight != null) {
                String pct = XmlUtils.childText(weight, "basketPercentage");
                if (pct != null) {
                    cdm.base.math.NonNegativeQuantitySchedule qs =
                            cdm.base.math.NonNegativeQuantitySchedule.builder()
                                    .setValue(new BigDecimal(pct))
                                    .setUnit(UnitType.builder()
                                            .setFinancialUnit(cdm.base.math.FinancialUnitEnum.WEIGHT)
                                            .build())
                                    .build();
                    cb.addQuantity(cdm.base.math.metafields.ReferenceWithMetaNonNegativeQuantitySchedule.builder()
                            .setValue(qs)
                            .build());
                }
            }
            cdm.observable.asset.metafields.FieldWithMetaBasketConstituent fwm =
                    cdm.observable.asset.metafields.FieldWithMetaBasketConstituent.builder()
                            .setValue(cb.build())
                            .setMeta(QuantityMapper.locationMeta("basketConstituent-" + idx))
                            .build();
            bb.addBasketConstituent(fwm);
        }
        return Observable.builder().setBasket(bb.build()).build();
    }

    static Observable buildStockObservable(Element equity) {
        Security.SecurityBuilder secb = Security.builder();
        // Only mark as exchange-listed when the FpML provides an exchangeId
        if (XmlUtils.child(equity, "exchangeId") != null) {
            secb.setIsExchangeListed(true);
        }

        // Identifiers
        for (Element instId : XmlUtils.children(equity, "instrumentId")) {
            String value = instId.getTextContent().trim();
            String scheme = instId.getAttribute("instrumentIdScheme");

            AssetIdentifier.AssetIdentifierBuilder aib = AssetIdentifier.builder()
                    .setIdentifier(FieldWithMetaString.builder()
                            .setValue(value)
                            .setMeta(scheme != null && !scheme.isEmpty()
                                    ? MetaFields.builder().setScheme(scheme).build() : null)
                            .build())
                    .setIdentifierType(mapAssetIdType(scheme));
            secb.addIdentifier(aib.build());
        }

        // Description as Name identifier
        String description = XmlUtils.childText(equity, "description");
        if (description != null) {
            secb.addIdentifier(AssetIdentifier.builder()
                    .setIdentifier(FieldWithMetaString.builder().setValue(description).build())
                    .setIdentifierType(AssetIdTypeEnum.NAME)
                    .build());
        }

        // Exchange
        Element exchangeId = XmlUtils.child(equity, "exchangeId");
        if (exchangeId != null) {
            String exchangeValue = exchangeId.getTextContent().trim();
            String exchangeScheme = exchangeId.getAttribute("exchangeIdScheme");
            FieldWithMetaString.FieldWithMetaStringBuilder nameB = FieldWithMetaString.builder().setValue(exchangeValue);
            if (exchangeScheme != null && !exchangeScheme.isEmpty()) {
                nameB.setMeta(MetaFields.builder().setScheme(exchangeScheme).build());
            }
            secb.setExchange(cdm.base.staticdata.party.LegalEntity.builder()
                    .setName(nameB.build())
                    .build());
        }

        // Related exchanges
        for (Element relExId : XmlUtils.children(equity, "relatedExchangeId")) {
            String relExValue = relExId.getTextContent().trim();
            String relExScheme = relExId.getAttribute("exchangeIdScheme");
            FieldWithMetaString.FieldWithMetaStringBuilder nameB = FieldWithMetaString.builder().setValue(relExValue);
            if (relExScheme != null && !relExScheme.isEmpty()) {
                nameB.setMeta(MetaFields.builder().setScheme(relExScheme).build());
            }
            secb.addRelatedExchange(cdm.base.staticdata.party.LegalEntity.builder()
                    .setName(nameB.build())
                    .build());
        }

        return Observable.builder()
                .setAsset(Asset.builder()
                        .setInstrument(Instrument.builder()
                                .setSecurity(secb.build())
                                .build())
                        .build())
                .build();
    }

    static Observable buildIndexObservable(Element index) {
        EquityIndex.EquityIndexBuilder eib = EquityIndex.builder()
                .setAssetClass(AssetClassEnum.EQUITY);
        if (XmlUtils.child(index, "exchangeId") != null) {
            eib.setIsExchangeListed(true);
        }

        // Identifiers
        for (Element instId : XmlUtils.children(index, "instrumentId")) {
            String value = instId.getTextContent().trim();
            String scheme = instId.getAttribute("instrumentIdScheme");

            AssetIdentifier.AssetIdentifierBuilder aib = AssetIdentifier.builder()
                    .setIdentifier(FieldWithMetaString.builder()
                            .setValue(value)
                            .setMeta(scheme != null && !scheme.isEmpty()
                                    ? MetaFields.builder().setScheme(scheme).build() : null)
                            .build())
                    .setIdentifierType(mapAssetIdType(scheme));
            eib.addIdentifier(aib.build());
        }

        // Description as Name identifier + name field
        String description = XmlUtils.childText(index, "description");
        if (description != null) {
            eib.addIdentifier(AssetIdentifier.builder()
                    .setIdentifier(FieldWithMetaString.builder().setValue(description).build())
                    .setIdentifierType(AssetIdTypeEnum.NAME)
                    .build());
            eib.setName(FieldWithMetaString.builder().setValue(description).build());
        }

        // Exchange
        Element exchangeId = XmlUtils.child(index, "exchangeId");
        if (exchangeId != null) {
            String exchangeValue = exchangeId.getTextContent().trim();
            String exchangeScheme = exchangeId.getAttribute("exchangeIdScheme");
            FieldWithMetaString.FieldWithMetaStringBuilder nameB = FieldWithMetaString.builder().setValue(exchangeValue);
            if (exchangeScheme != null && !exchangeScheme.isEmpty()) {
                nameB.setMeta(MetaFields.builder().setScheme(exchangeScheme).build());
            }
            eib.setExchange(cdm.base.staticdata.party.LegalEntity.builder()
                    .setName(nameB.build())
                    .build());
        }

        // Related exchanges
        for (Element relExId : XmlUtils.children(index, "relatedExchangeId")) {
            String relExValue = relExId.getTextContent().trim();
            String relExScheme = relExId.getAttribute("exchangeIdScheme");
            FieldWithMetaString.FieldWithMetaStringBuilder nameB = FieldWithMetaString.builder().setValue(relExValue);
            if (relExScheme != null && !relExScheme.isEmpty()) {
                nameB.setMeta(MetaFields.builder().setScheme(relExScheme).build());
            }
            eib.addRelatedExchange(cdm.base.staticdata.party.LegalEntity.builder()
                    .setName(nameB.build())
                    .build());
        }

        return Observable.builder()
                .setIndex(Index.builder()
                        .setEquityIndex(eib.build())
                        .build())
                .build();
    }

    static AssetIdTypeEnum mapAssetIdType(String scheme) {
        if (scheme == null || scheme.isEmpty()) return AssetIdTypeEnum.OTHER;
        String lower = scheme.toLowerCase();
        if (lower.contains("cusip")) return AssetIdTypeEnum.CUSIP;
        if (lower.contains("isin")) return AssetIdTypeEnum.ISIN;
        if (lower.contains("sedol")) return AssetIdTypeEnum.SEDOL;
        if (lower.contains("bloomberg-ticker") || lower.contains("bloombergticker")) return AssetIdTypeEnum.BBGTICKER;
        if (lower.contains("instrument-id-bloomberg")) return AssetIdTypeEnum.BBGID;
        if (lower.contains("figi")) return AssetIdTypeEnum.FIGI;
        if (lower.contains("valoren")) return AssetIdTypeEnum.VALOREN;
        if (lower.contains("wertpapier")) return AssetIdTypeEnum.WERTPAPIER;
        if (lower.contains("sicovam")) return AssetIdTypeEnum.SICOVAM;
        if (lower.contains("instrument-id-red")) return AssetIdTypeEnum.REDID;
        if (lower.contains("ric")) return AssetIdTypeEnum.RIC;
        return AssetIdTypeEnum.OTHER;
    }

    private List<ProductTaxonomy> buildEquityOptionTaxonomy(Element eqOption, Element underlyer) {
        List<ProductTaxonomy> out = new ArrayList<>();

        // productType from FpML
        Element productType = XmlUtils.child(eqOption, "productType");
        if (productType != null) {
            String ptValue = productType.getTextContent().trim();
            String ptScheme = productType.getAttribute("productTypeScheme");
            FieldWithMetaString.FieldWithMetaStringBuilder name = FieldWithMetaString.builder().setValue(ptValue);
            if (ptScheme != null && !ptScheme.isEmpty()) {
                name.setMeta(MetaFields.builder().setScheme(ptScheme).build());
            }
            TaxonomyValue tv = TaxonomyValue.builder().setName(name.build()).build();
            TaxonomySourceEnum src = (ptScheme != null && !ptScheme.isEmpty())
                    ? TaxonomySourceEnum.ISDA : TaxonomySourceEnum.OTHER;
            out.add(ProductTaxonomy.builder().setSource(src).setValue(tv).build());
        }

        // ISDA product qualifier — suppressed for single-name passThrough features (eqd-ex14 reference omits it)
        if (!isSingleNamePassThrough(eqOption, underlyer)) {
            String qualifier = computeEquityOptionQualifier(eqOption, underlyer);
            out.add(ProductTaxonomy.builder()
                    .setSource(TaxonomySourceEnum.ISDA)
                    .setProductQualifier(qualifier)
                    .build());
        }

        return out;
    }

    private boolean isSingleNamePassThrough(Element eqOption, Element underlyer) {
        Element feature = XmlUtils.child(eqOption, "feature");
        if (feature == null) return false;
        if (XmlUtils.child(feature, "passThrough") == null) return false;
        // Determine if single name (not basket, not index)
        if (underlyer == null) return false;
        Element basket = XmlUtils.child(underlyer, "basket");
        if (basket != null) return false;
        Element singleUnderlyer = XmlUtils.child(underlyer, "singleUnderlyer");
        if (singleUnderlyer == null) return false;
        if (XmlUtils.child(singleUnderlyer, "index") != null) return false;
        return true;
    }

    private OptionFeature buildPassThroughFeature(Element eqOption, Element underlyer, MappingContext ctx) {
        if (!isSingleNamePassThrough(eqOption, underlyer)) return null;
        Element feature = XmlUtils.child(eqOption, "feature");
        Element passThroughEl = XmlUtils.child(feature, "passThrough");
        PassThrough.PassThroughBuilder ptb = PassThrough.builder();
        for (Element item : XmlUtils.children(passThroughEl, "passThroughItem")) {
            PassThroughItem.PassThroughItemBuilder ptiB = PassThroughItem.builder();
            // payerPartyReference + receiverPartyReference
            Element payerRef = XmlUtils.child(item, "payerPartyReference");
            Element receiverRef = XmlUtils.child(item, "receiverPartyReference");
            PayerReceiver.PayerReceiverBuilder prb = PayerReceiver.builder();
            if (payerRef != null) {
                Integer ord = ctx.partyOrder.get(payerRef.getAttribute("href"));
                prb.setPayer(ord != null && ord == 0 ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2);
            }
            if (receiverRef != null) {
                Integer ord = ctx.partyOrder.get(receiverRef.getAttribute("href"));
                prb.setReceiver(ord != null && ord == 0 ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2);
            }
            ptiB.setPayerReceiver(prb.build());
            String pct = XmlUtils.childText(item, "passThroughPercentage");
            if (pct != null) ptiB.setPassThroughPercentage(new BigDecimal(pct));
            ptb.addPassThroughItem(ptiB.build());
        }
        return OptionFeature.builder().setPassThrough(ptb.build()).build();
    }

    private String computeEquityOptionQualifier(Element eqOption, Element underlyer) {
        // Determine if single name or index
        boolean isIndex = false;
        boolean isBasket = false;
        if (underlyer != null) {
            Element singleUnderlyer = XmlUtils.child(underlyer, "singleUnderlyer");
            if (singleUnderlyer != null) {
                isIndex = XmlUtils.child(singleUnderlyer, "index") != null;
            }
            Element basket = XmlUtils.child(underlyer, "basket");
            if (basket != null) isBasket = true;
        }

        // Check the productType text for hints
        String productTypeText = XmlUtils.childText(eqOption, "productType");
        if (productTypeText != null) {
            // e.g. "Equity:Option:PriceReturnBasicPerformance:SingleName"
            //      "Equity:Option:PriceReturnBasicPerformance:SingleIndex"
            if (productTypeText.contains("SingleIndex") || isIndex) {
                return "EquityOption_PriceReturnBasicPerformance_Index";
            }
            if (productTypeText.contains("Basket") || isBasket) {
                return "EquityOption_PriceReturnBasicPerformance_Basket";
            }
            return "EquityOption_PriceReturnBasicPerformance_SingleName";
        }

        if (isIndex) return "EquityOption_PriceReturnBasicPerformance_Index";
        if (isBasket) return "EquityOption_PriceReturnBasicPerformance_Basket";
        return "EquityOption_PriceReturnBasicPerformance_SingleName";
    }

    private TransferState buildPremiumTransfer(Element premium) {
        Transfer.TransferBuilder tb = Transfer.builder();

        Element amtEl = XmlUtils.child(premium, "paymentAmount");
        String ccy = XmlUtils.childText(amtEl, "currency");
        String amount = XmlUtils.childText(amtEl, "amount");
        Element ccyEl = amtEl == null ? null : XmlUtils.child(amtEl, "currency");
        String ccyScheme = ccyEl == null ? null : ccyEl.getAttribute("currencyScheme");
        if (amount != null && ccy != null) {
            FieldWithMetaString.FieldWithMetaStringBuilder ccyField = FieldWithMetaString.builder().setValue(ccy);
            if (ccyScheme != null && !ccyScheme.isEmpty()) {
                ccyField.setMeta(MetaFields.builder().setScheme(ccyScheme).build());
            }
            tb.setQuantity(cdm.base.math.NonNegativeQuantity.builder()
                    .setValue(new BigDecimal(amount))
                    .setUnit(UnitType.builder().setCurrency(ccyField.build()).build())
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
            cdm.base.datetime.AdjustableOrAdjustedOrRelativeDate.AdjustableOrAdjustedOrRelativeDateBuilder sdb =
                    cdm.base.datetime.AdjustableOrAdjustedOrRelativeDate.builder();
            String unadj = XmlUtils.childText(payDate, "unadjustedDate");
            if (unadj != null) sdb.setUnadjustedDate(DateMapper.parse(unadj));
            String adj = XmlUtils.childText(payDate, "adjustedDate");
            if (adj != null) {
                sdb.setAdjustedDate(FieldWithMetaDate.builder()
                        .setValue(DateMapper.parse(adj)).build());
            }
            BusinessDayAdjustments bda = DateMapper.businessDayAdjustments(
                    XmlUtils.child(payDate, "dateAdjustments"));
            if (bda != null) sdb.setDateAdjustments(bda);
            tb.setSettlementDate(sdb.build());
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
                .setPriceTransfer(cdm.observable.asset.FeeTypeEnum.PREMIUM)
                .build());

        return TransferState.builder().setTransfer(tb.build()).build();
    }

    static DayTypeEnum mapDayType(String text) {
        if (text == null) return null;
        return switch (text) {
            case "Business" -> DayTypeEnum.BUSINESS;
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
