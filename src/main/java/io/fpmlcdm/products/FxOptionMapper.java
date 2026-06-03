package io.fpmlcdm.products;

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
import cdm.product.common.schedule.*;
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
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps FpML {@code <fxOption>} into CDM TradeState.
 * Covers European and American vanilla options, non-deliverable options, and average-rate options.
 */
public class FxOptionMapper implements ProductMapper {

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);

        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        parties = SwapMapper.applyPartyTradeInformation(parties, tradeHeader);

        Element fxOption = XmlUtils.child(trade, "fxOption");

        // Buyer/seller
        Element buyerRef = XmlUtils.child(fxOption, "buyerPartyReference");
        Element sellerRef = XmlUtils.child(fxOption, "sellerPartyReference");
        String buyerHref = buyerRef != null ? buyerRef.getAttribute("href") : null;
        String sellerHref = sellerRef != null ? sellerRef.getAttribute("href") : null;

        // PARTY_1 = buyer
        assignRoles(buyerHref, ctx);

        // Exercise (European or American)
        Element europeanEx = XmlUtils.child(fxOption, "europeanExercise");
        Element americanEx = XmlUtils.child(fxOption, "americanExercise");
        boolean isEuropean = europeanEx != null;
        Element exercise = isEuropean ? europeanEx : americanEx;

        String expiryDateStr = XmlUtils.childText(exercise, "expiryDate");
        Element expiryTime = XmlUtils.child(exercise, "expiryTime");
        String hourMinuteTime = XmlUtils.childText(expiryTime, "hourMinuteTime");
        String businessCenter = XmlUtils.childText(expiryTime, "businessCenter");

        // Value date: europeanExercise/valueDate or americanExercise/latestValueDate
        String valueDate = isEuropean
                ? XmlUtils.childText(exercise, "valueDate")
                : XmlUtils.childText(exercise, "latestValueDate");

        // Put/Call amounts
        Element putAmount = XmlUtils.child(fxOption, "putCurrencyAmount");
        Element callAmount = XmlUtils.child(fxOption, "callCurrencyAmount");
        String putCcy = XmlUtils.childText(putAmount, "currency");
        String putAmt = XmlUtils.childText(putAmount, "amount");
        String callCcy = XmlUtils.childText(callAmount, "currency");
        String callAmt = XmlUtils.childText(callAmount, "amount");

        // Strike
        Element strike = XmlUtils.child(fxOption, "strike");
        String strikeRate = XmlUtils.childText(strike, "rate");
        String strikeQuoteBasis = XmlUtils.childText(strike, "strikeQuoteBasis");

        // Determine option type (Put or Call) and observable currency.
        // When strikeQuoteBasis is CallCurrencyPerPutCurrency → Put on putCurrency, observable=putCurrency
        // When strikeQuoteBasis is PutCurrencyPerCallCurrency → Call on callCurrency, observable=callCurrency
        //
        // Quantity ordering in the tradeLot is always: putAmt first, callAmt second.
        // But the label assignment differs:
        //   CallCurrencyPerPutCurrency: putCcy→quantity-1, callCcy→quantity-2
        //   PutCurrencyPerCallCurrency: callCcy→quantity-1, putCcy→quantity-2
        OptionTypeEnum optionType;
        String obsCcy;
        String putQtyLabel, callQtyLabel;
        if ("PutCurrencyPerCallCurrency".equals(strikeQuoteBasis)) {
            optionType = OptionTypeEnum.CALL;
            obsCcy = callCcy;
            putQtyLabel = "quantity-2";
            callQtyLabel = "quantity-1";
        } else {
            // Default: CallCurrencyPerPutCurrency → Put
            optionType = OptionTypeEnum.PUT;
            obsCcy = putCcy;
            putQtyLabel = "quantity-1";
            callQtyLabel = "quantity-2";
        }

        // payerReceiver: payer=seller, receiver=buyer
        Integer buyerOrder = buyerHref != null ? ctx.partyOrder.get(buyerHref) : null;
        Integer sellerOrder = sellerHref != null ? ctx.partyOrder.get(sellerHref) : null;
        CounterpartyRoleEnum payerRole = sellerOrder != null && sellerOrder == 0
                ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
        CounterpartyRoleEnum receiverRole = buyerOrder != null && buyerOrder == 0
                ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
        CounterpartyRoleEnum buyerRole = buyerOrder != null && buyerOrder == 0
                ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
        CounterpartyRoleEnum sellerRole = sellerOrder != null && sellerOrder == 0
                ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;

        // Build ExerciseTerms
        ExerciseTerms.ExerciseTermsBuilder etb = ExerciseTerms.builder()
                .setStyle(isEuropean ? OptionExerciseStyleEnum.EUROPEAN : OptionExerciseStyleEnum.AMERICAN);

        // Expiration date
        AdjustableOrRelativeDate expirationDate = AdjustableOrRelativeDate.builder()
                .setAdjustableDate(AdjustableDate.builder()
                        .setAdjustedDate(FieldWithMetaDate.builder()
                                .setValue(DateMapper.parse(expiryDateStr))
                                .build())
                        .build())
                .build();
        etb.addExpirationDate(expirationDate);

        // Expiration time
        if (hourMinuteTime != null) {
            BusinessCenterTime.BusinessCenterTimeBuilder bctb = BusinessCenterTime.builder()
                    .setHourMinuteTime(LocalTime.parse(hourMinuteTime));
            if (businessCenter != null) {
                try {
                    bctb.setBusinessCenter(FieldWithMetaBusinessCenterEnum.builder()
                            .setValue(BusinessCenterEnum.valueOf(businessCenter))
                            .build());
                } catch (IllegalArgumentException ignored) {}
            }
            etb.setExpirationTime(bctb.build());
            etb.setExpirationTimeType(ExpirationTimeTypeEnum.SPECIFIC_TIME);
        }

        // American commencement date
        if (!isEuropean) {
            Element commencementDate = XmlUtils.child(exercise, "commencementDate");
            if (commencementDate != null) {
                etb.setCommencementDate(DateMapper.adjustableOrRelative(commencementDate));
            }
        }

        // Build strike price
        // strikeQuoteBasis: CallCurrencyPerPutCurrency or PutCurrencyPerCallCurrency
        String strikePriceCcy, strikePerUnitCcy;
        if ("CallCurrencyPerPutCurrency".equals(strikeQuoteBasis)) {
            strikePriceCcy = callCcy;
            strikePerUnitCcy = putCcy;
        } else {
            strikePriceCcy = putCcy;
            strikePerUnitCcy = callCcy;
        }

        Price.PriceBuilder strikePriceBuilder = Price.builder()
                .setValue(new BigDecimal(strikeRate))
                .setUnit(UnitType.builder().setCurrency(FieldWithMetaString.builder().setValue(strikePriceCcy).build()).build())
                .setPerUnitOf(UnitType.builder().setCurrency(FieldWithMetaString.builder().setValue(strikePerUnitCcy).build()).build())
                .setPriceType(PriceTypeEnum.EXCHANGE_RATE);
        // spotRate (when present on the option) → strikePrice.composite.baseValue.
        String spotRate = XmlUtils.childText(fxOption, "spotRate");
        if (spotRate != null) {
            strikePriceBuilder.setComposite(cdm.observable.asset.PriceComposite.builder()
                    .setBaseValue(new BigDecimal(spotRate))
                    .build());
        }
        OptionStrike optStrike = OptionStrike.builder().setStrikePrice(strikePriceBuilder.build()).build();

        // Settlement terms
        SettlementTerms.SettlementTermsBuilder stb = SettlementTerms.builder();
        if (valueDate != null) {
            stb.setSettlementDate(SettlementDate.builder()
                    .setValueDate(DateMapper.parse(valueDate))
                    .build());
        }

        // Cash settlement for non-deliverable options
        Element cashSettlement = XmlUtils.child(fxOption, "cashSettlement");
        if (cashSettlement != null) {
            stb.setSettlementType(SettlementTypeEnum.CASH);
            String settlCcy = XmlUtils.childText(cashSettlement, "settlementCurrency");
            if (settlCcy != null) {
                stb.setSettlementCurrency(FieldWithMetaString.builder().setValue(settlCcy).build());
            }
            CashSettlementTerms cst = buildCashSettlementTerms(cashSettlement);
            if (cst != null) stb.addCashSettlementTerms(cst);
        }

        // Build OptionPayout priceQuantity (only quantitySchedule reference, no priceSchedule)
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
                .setSettlementTerms(stb.build())
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
                .setExerciseTerms(etb.build())
                .setStrike(optStrike);

        // Asian (average-rate) features
        Element features = XmlUtils.child(fxOption, "features");
        Element asian = features != null ? XmlUtils.child(features, "asian") : null;
        if (asian != null) {
            opb.setObservationTerms(buildObservationTerms(asian));
        }

        Payout payout = Payout.builder().setOptionPayout(opb.build()).build();

        // Build tradeLot PriceQuantity (no price, just quantities + observable)
        // Order: putAmount first, callAmount second (always)
        FieldWithMetaNonNegativeQuantitySchedule putQtyField = FieldWithMetaNonNegativeQuantitySchedule.builder()
                .setValue(NonNegativeQuantitySchedule.builder()
                        .setValue(new BigDecimal(putAmt))
                        .setUnit(UnitType.builder().setCurrency(FieldWithMetaString.builder().setValue(putCcy).build()).build())
                        .build())
                .setMeta(QuantityMapper.locationMeta(putQtyLabel))
                .build();

        FieldWithMetaNonNegativeQuantitySchedule callQtyField = FieldWithMetaNonNegativeQuantitySchedule.builder()
                .setValue(NonNegativeQuantitySchedule.builder()
                        .setValue(new BigDecimal(callAmt))
                        .setUnit(UnitType.builder().setCurrency(FieldWithMetaString.builder().setValue(callCcy).build()).build())
                        .build())
                .setMeta(QuantityMapper.locationMeta(callQtyLabel))
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

        PriceQuantity pq = PriceQuantity.builder()
                .addQuantity(putQtyField)
                .addQuantity(callQtyField)
                .setObservable(FieldWithMetaObservable.builder()
                        .setValue(observable)
                        .setMeta(QuantityMapper.locationMeta("observable-1"))
                        .build())
                .build();

        // Taxonomy
        List<ProductTaxonomy> extraTaxonomy = buildFxOptionTaxonomy(fxOption);

        // Determine qualifier
        String qualifier = determineQualifier(fxOption);

        // Build TradeState
        EconomicTerms econ = EconomicTerms.builder().addPayout(payout).build();

        CalculationAgentMapper.Result ca = CalculationAgentMapper.map(trade);

        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder()
                .setEconomicTerms(econ);
        for (ProductTaxonomy t : extraTaxonomy) ntp.addTaxonomy(t);
        ntp.addTaxonomy(ProductTaxonomy.builder()
                .setSource(TaxonomySourceEnum.ISDA)
                .setProductQualifier(qualifier)
                .build());
        ProductIdentifierMapper.map(fxOption).forEach(ntp::addIdentifier);

        TradeLot tradeLot = TradeLot.builder().addPriceQuantity(pq).build();
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

        // Premium → transferHistory
        for (Element premium : XmlUtils.children(fxOption, "premium")) {
            TransferState ts = buildPremiumTransfer(premium);
            if (ts != null) tsBuilder.addTransferHistory(ts);
        }
        // Also otherPartyPayment at trade level
        for (TransferState ts : TransferMapper.map(trade, null)) {
            tsBuilder.addTransferHistory(ts);
        }

        return tsBuilder.build();
    }

    static TransferState buildPremiumTransfer(Element premium) {
        Transfer.TransferBuilder tb = Transfer.builder();

        Element amtEl = XmlUtils.child(premium, "paymentAmount");
        String ccy = XmlUtils.childText(amtEl, "currency");
        String amount = XmlUtils.childText(amtEl, "amount");
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
            // paymentDate wraps adjustableDate or is a simple date
            Element adjDate = XmlUtils.child(payDate, "adjustableDate");
            if (adjDate != null) {
                cdm.base.datetime.AdjustableOrAdjustedOrRelativeDate.AdjustableOrAdjustedOrRelativeDateBuilder sdb =
                        cdm.base.datetime.AdjustableOrAdjustedOrRelativeDate.builder();
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

    private CashSettlementTerms buildCashSettlementTerms(Element cashSettlement) {
        CashSettlementTerms.CashSettlementTermsBuilder cstb = CashSettlementTerms.builder();

        for (Element fixing : XmlUtils.children(cashSettlement, "fixing")) {
            // Build valuation method
            ValuationMethod.ValuationMethodBuilder vmb = ValuationMethod.builder();
            ValuationSource.ValuationSourceBuilder vsb = ValuationSource.builder();

            Element qcp = XmlUtils.child(fixing, "quotedCurrencyPair");
            if (qcp != null) {
                QuotedCurrencyPair.QuotedCurrencyPairBuilder qcpb = QuotedCurrencyPair.builder();
                String c1 = XmlUtils.childText(qcp, "currency1");
                String c2 = XmlUtils.childText(qcp, "currency2");
                String qb = XmlUtils.childText(qcp, "quoteBasis");
                if (c1 != null) qcpb.setCurrency1(FieldWithMetaString.builder().setValue(c1).build());
                if (c2 != null) qcpb.setCurrency2(FieldWithMetaString.builder().setValue(c2).build());
                if (qb != null) {
                    if ("Currency1PerCurrency2".equals(qb)) {
                        qcpb.setQuoteBasis(QuoteBasisEnum.CURRENCY_1_PER_CURRENCY_2);
                    } else if ("Currency2PerCurrency1".equals(qb)) {
                        qcpb.setQuoteBasis(QuoteBasisEnum.CURRENCY_2_PER_CURRENCY_1);
                    }
                }
                vsb.setQuotedCurrencyPair(ReferenceWithMetaQuotedCurrencyPair.builder()
                        .setValue(qcpb.build()).build());
            }

            Element fxSource = XmlUtils.child(fixing, "fxSpotRateSource");
            if (fxSource != null) {
                vsb.setInformationSource(buildFxSpotRateSource(fxSource));
            }

            vmb.setValuationSource(vsb.build());
            cstb.setValuationMethod(vmb.build());

            // Fixing date
            String fixingDateStr = XmlUtils.childText(fixing, "fixingDate");
            if (fixingDateStr != null) {
                FxFixingDate fxFixingDate = FxFixingDate.builder()
                        .setFxFixingDate(AdjustableOrRelativeDate.builder()
                                .setAdjustableDate(AdjustableDate.builder()
                                        .setAdjustedDate(FieldWithMetaDate.builder()
                                                .setValue(DateMapper.parse(fixingDateStr))
                                                .build())
                                        .build())
                                .build())
                        .build();
                cstb.setValuationDate(ValuationDate.builder()
                        .setFxFixingDate(fxFixingDate)
                        .build());
            }

            // Fixing time → valuation time
            Element fixingTime = XmlUtils.child(fxSource, "fixingTime");
            if (fixingTime != null) {
                BusinessCenterTime.BusinessCenterTimeBuilder bctb = BusinessCenterTime.builder();
                String hmt = XmlUtils.childText(fixingTime, "hourMinuteTime");
                if (hmt != null) bctb.setHourMinuteTime(LocalTime.parse(hmt));
                String bc = XmlUtils.childText(fixingTime, "businessCenter");
                if (bc != null) {
                    try {
                        bctb.setBusinessCenter(FieldWithMetaBusinessCenterEnum.builder()
                                .setValue(BusinessCenterEnum.valueOf(bc))
                                .build());
                    } catch (IllegalArgumentException ignored) {}
                }
                cstb.setValuationTime(bctb.build());
            }
        }

        return cstb.build();
    }

    private FxSpotRateSource buildFxSpotRateSource(Element fxSource) {
        FxSpotRateSource.FxSpotRateSourceBuilder fsb = FxSpotRateSource.builder();
        Element primary = XmlUtils.child(fxSource, "primaryRateSource");
        if (primary != null) {
            fsb.setPrimarySource(buildInformationSource(primary));
        }
        Element secondary = XmlUtils.child(fxSource, "secondaryRateSource");
        if (secondary != null) {
            fsb.setSecondarySource(buildInformationSource(secondary));
        }
        return fsb.build();
    }

    private InformationSource buildInformationSource(Element source) {
        InformationSource.InformationSourceBuilder isb = InformationSource.builder();
        String rateSource = XmlUtils.childText(source, "rateSource");
        if (rateSource != null) {
            try {
                InformationProviderEnum ipe = InformationProviderEnum.valueOf(rateSource.toUpperCase());
                isb.setSourceProvider(FieldWithMetaInformationProviderEnum.builder().setValue(ipe).build());
            } catch (IllegalArgumentException e) {
                isb.setSourceProvider(FieldWithMetaInformationProviderEnum.builder()
                        .setValue(InformationProviderEnum.REFINITIV).build());
            }
        }
        String page = XmlUtils.childText(source, "rateSourcePage");
        if (page != null) {
            isb.setSourcePage(FieldWithMetaString.builder().setValue(page).build());
        }
        return isb.build();
    }

    private ObservationTerms buildObservationTerms(Element asian) {
        ObservationTerms.ObservationTermsBuilder otb = ObservationTerms.builder();

        // Observation time (from asian/fixingTime)
        Element fixingTime = XmlUtils.child(asian, "fixingTime");
        if (fixingTime != null) {
            BusinessCenterTime.BusinessCenterTimeBuilder bctb = BusinessCenterTime.builder();
            String hmt = XmlUtils.childText(fixingTime, "hourMinuteTime");
            if (hmt != null) bctb.setHourMinuteTime(LocalTime.parse(hmt));
            String bc = XmlUtils.childText(fixingTime, "businessCenter");
            if (bc != null) {
                try {
                    bctb.setBusinessCenter(FieldWithMetaBusinessCenterEnum.builder()
                            .setValue(BusinessCenterEnum.valueOf(bc))
                            .build());
                } catch (IllegalArgumentException ignored) {}
            }
            otb.setObservationTime(bctb.build());
        }

        // Information source
        Element primarySource = XmlUtils.child(asian, "primaryRateSource");
        if (primarySource != null) {
            otb.setInformationSource(FxSpotRateSource.builder()
                    .setPrimarySource(buildInformationSource(primarySource))
                    .build());
        }

        // Observation schedule
        Element obsSchedule = XmlUtils.child(asian, "observationSchedule");
        if (obsSchedule != null) {
            String startDate = XmlUtils.childText(obsSchedule, "startDate");
            String endDate = XmlUtils.childText(obsSchedule, "endDate");
            Element cpf = XmlUtils.child(obsSchedule, "calculationPeriodFrequency");

            PeriodicDates.PeriodicDatesBuilder pdb = PeriodicDates.builder();
            if (startDate != null) {
                pdb.setStartDate(AdjustableOrRelativeDate.builder()
                        .setAdjustableDate(AdjustableDate.builder()
                                .setUnadjustedDate(DateMapper.parse(startDate))
                                .build())
                        .build());
            }
            if (endDate != null) {
                pdb.setEndDate(AdjustableOrRelativeDate.builder()
                        .setAdjustableDate(AdjustableDate.builder()
                                .setUnadjustedDate(DateMapper.parse(endDate))
                                .build())
                        .build());
            }
            if (cpf != null) {
                CalculationPeriodFrequency.CalculationPeriodFrequencyBuilder cpfb =
                        CalculationPeriodFrequency.builder();
                String pm = XmlUtils.childText(cpf, "periodMultiplier");
                if (pm != null) cpfb.setPeriodMultiplier(Integer.parseInt(pm));
                String p = XmlUtils.childText(cpf, "period");
                if (p != null) {
                    try { cpfb.setPeriod(cdm.base.datetime.PeriodExtendedEnum.valueOf(p)); }
                    catch (IllegalArgumentException ignored) {}
                }
                String rc = XmlUtils.childText(cpf, "rollConvention");
                if (rc != null) {
                    try {
                        cpfb.setRollConvention(RollConventionEnum.valueOf(rc));
                    } catch (IllegalArgumentException ignored) {}
                }
                pdb.setPeriodFrequency(cpfb.build());
            }

            otb.setObservationDates(ObservationDates.builder()
                    .setPeriodicSchedule(pdb.build())
                    .build());
        }

        // Note: <rateObservation> elements are historical fixings, not scheduling terms.
        // They are NOT mapped to observationDates in CDM.

        return otb.build();
    }

    private List<ProductTaxonomy> buildFxOptionTaxonomy(Element fxOption) {
        List<ProductTaxonomy> out = new ArrayList<>();
        Element productType = XmlUtils.child(fxOption, "productType");
        if (productType != null) {
            String ptValue = productType.getTextContent().trim();
            String ptScheme = productType.getAttribute("productTypeScheme");
            FieldWithMetaString.FieldWithMetaStringBuilder name = FieldWithMetaString.builder().setValue(ptValue);
            if (ptScheme != null && !ptScheme.isEmpty()) {
                name.setMeta(MetaFields.builder().setScheme(ptScheme).build());
            }
            TaxonomyValue tv = TaxonomyValue.builder().setName(name.build()).build();
            TaxonomySourceEnum src = TaxonomySourceEnum.OTHER;
            out.add(ProductTaxonomy.builder().setSource(src).setValue(tv).build());
        }
        return out;
    }

    private String determineQualifier(Element fxOption) {
        Element cashSettlement = XmlUtils.child(fxOption, "cashSettlement");
        if (cashSettlement != null) {
            return "ForeignExchange_NDO";
        }
        return "ForeignExchange_VanillaOption";
    }

    static void assignRoles(String buyerHref, MappingContext ctx) {
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
