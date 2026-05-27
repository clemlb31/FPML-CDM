package io.fpmlcdm.products;

import cdm.base.datetime.AdjustableDate;
import cdm.base.datetime.AdjustableOrAdjustedOrRelativeDate;
import cdm.base.datetime.AdjustableOrRelativeDate;
import cdm.base.datetime.BusinessCenterEnum;
import cdm.base.datetime.BusinessCenterTime;
import cdm.base.datetime.BusinessDayAdjustments;
import cdm.base.datetime.Period;
import cdm.base.datetime.RelativeDateOffset;
import cdm.base.datetime.metafields.FieldWithMetaBusinessCenterEnum;
import cdm.base.datetime.metafields.ReferenceWithMetaBusinessCenters;
import cdm.base.math.FinancialUnitEnum;
import cdm.base.math.Measure;
import cdm.base.math.NonNegativeQuantitySchedule;
import cdm.base.math.UnitType;
import cdm.base.math.metafields.FieldWithMetaNonNegativeQuantitySchedule;
import cdm.base.math.metafields.ReferenceWithMetaNonNegativeQuantitySchedule;
import cdm.base.staticdata.asset.common.Asset;
import cdm.base.staticdata.asset.common.AssetIdTypeEnum;
import cdm.base.staticdata.asset.common.AssetIdentifier;
import cdm.base.staticdata.asset.common.Instrument;
import cdm.base.staticdata.asset.common.ProductTaxonomy;
import cdm.base.staticdata.asset.common.Security;
import cdm.base.staticdata.asset.common.TaxonomySourceEnum;
import cdm.base.staticdata.asset.rates.FloatingRateIndexEnum;
import cdm.base.staticdata.party.Account;
import cdm.base.staticdata.party.AncillaryParty;
import cdm.base.staticdata.party.BuyerSeller;
import cdm.base.staticdata.party.Counterparty;
import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.Party;
import cdm.base.staticdata.party.PartyRole;
import cdm.base.staticdata.party.PayerReceiver;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.event.common.ContractDetails;
import cdm.event.common.Trade;
import cdm.event.common.TradeIdentifier;
import cdm.event.common.TradeState;
import cdm.event.common.Transfer;
import cdm.event.common.TransferExpression;
import cdm.event.common.TransferState;
import cdm.observable.asset.FeeTypeEnum;
import cdm.observable.asset.InterpolationMethodEnum;
import cdm.observable.asset.MakeWholeAmount;
import cdm.observable.asset.Observable;
import cdm.observable.asset.Price;
import cdm.observable.asset.PriceQuantity;
import cdm.observable.asset.PriceTypeEnum;
import cdm.observable.asset.QuotationSideEnum;
import cdm.observable.asset.ReferenceSwapCurve;
import cdm.observable.asset.SwapCurveValuation;
import cdm.observable.asset.metafields.FieldWithMetaObservable;
import cdm.product.common.settlement.ResolvablePriceQuantity;
import cdm.product.common.settlement.SettlementDate;
import cdm.product.common.settlement.SettlementTerms;
import cdm.product.common.settlement.SettlementTypeEnum;
import cdm.product.template.EconomicTerms;
import cdm.product.template.ExerciseNotice;
import cdm.product.template.ExerciseNoticeGiverEnum;
import cdm.product.template.ExerciseProcedure;
import cdm.product.template.ExerciseTerms;
import cdm.product.template.ExpirationTimeTypeEnum;
import cdm.product.template.ManualExercise;
import cdm.product.template.MultipleExercise;
import cdm.product.template.NonTransferableProduct;
import cdm.product.template.OptionExerciseStyleEnum;
import cdm.product.template.OptionPayout;
import cdm.product.template.OptionStrike;
import cdm.product.template.OptionTypeEnum;
import cdm.product.template.Payout;
import cdm.product.template.TradeLot;
import cdm.product.template.Underlier;
import com.rosetta.model.lib.meta.Reference;
import com.rosetta.model.metafields.FieldWithMetaDate;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
import com.rosetta.model.metafields.ReferenceWithMetaDate;
import io.fpmlcdm.common.AccountMapper;
import io.fpmlcdm.common.CalculationAgentMapper;
import io.fpmlcdm.common.ContractDetailsMapper;
import io.fpmlcdm.common.DateMapper;
import io.fpmlcdm.common.EnumMappers;
import io.fpmlcdm.common.IdentifierMapper;
import io.fpmlcdm.common.MappingContext;
import io.fpmlcdm.common.PartyMapper;
import io.fpmlcdm.common.PartyRoleMapper;
import io.fpmlcdm.common.ProductIdentifierMapper;
import io.fpmlcdm.common.QuantityMapper;
import io.fpmlcdm.common.TransferMapper;
import io.fpmlcdm.common.XmlUtils;
import cdm.observable.asset.metafields.ReferenceWithMetaObservable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps FpML {@code <bondOption>} (covering both bond options and convertible bond options)
 * into a CDM {@link TradeState} with an {@link OptionPayout} whose underlier is a debt Security.
 *
 * Patterns covered (from bond-options-5-13):
 *  - {@code <bond>}/{@code <convertibleBond>} → Security with instrumentId
 *  - {@code <strike><price><strikePrice>} → OptionStrike.strikePrice (priceType AssetPrice)
 *  - {@code <strike><referenceSwapCurve>}    → OptionStrike.referenceSwapCurve (typical for CB)
 *  - American/European exercise with optional multipleExercise
 *  - notionalAmount + optionEntitlement + numberOfOptions
 */
public class BondOptionMapper implements ProductMapper {

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);

        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        parties = SwapMapper.applyPartyTradeInformation(parties, tradeHeader);

        Element bondOption = XmlUtils.child(trade, "bondOption");

        // Buyer/seller
        Element buyerRef = XmlUtils.child(bondOption, "buyerPartyReference");
        Element sellerRef = XmlUtils.child(bondOption, "sellerPartyReference");
        String buyerHref = buyerRef != null ? buyerRef.getAttribute("href") : null;
        String sellerHref = sellerRef != null ? sellerRef.getAttribute("href") : null;

        // PARTY_1 = buyer (matches reference convention)
        assignRoles(buyerHref, ctx);

        Integer buyerOrder = buyerHref != null ? ctx.partyOrder.get(buyerHref) : null;
        Integer sellerOrder = sellerHref != null ? ctx.partyOrder.get(sellerHref) : null;
        CounterpartyRoleEnum buyerRole = (buyerOrder != null && buyerOrder == 0)
                ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
        CounterpartyRoleEnum sellerRole = (sellerOrder != null && sellerOrder == 0)
                ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;

        // optionType (Put/Call)
        String optionTypeStr = XmlUtils.childText(bondOption, "optionType");
        OptionTypeEnum optionType = "Put".equalsIgnoreCase(optionTypeStr)
                ? OptionTypeEnum.PUT : OptionTypeEnum.CALL;

        // Exercise terms (European or American)
        ExerciseTerms exerciseTerms = buildExerciseTerms(bondOption, buyerHref, sellerHref);

        // Settlement terms
        SettlementTerms settlementTerms = buildSettlementTerms(bondOption);

        // Strike
        OptionStrike strike = buildStrike(XmlUtils.child(bondOption, "strike"));

        // Observable: <bond> or <convertibleBond>
        Element bondEl = XmlUtils.child(bondOption, "bond");
        if (bondEl == null) bondEl = XmlUtils.child(bondOption, "convertibleBond");
        Observable observable = buildBondObservable(bondEl);

        // Build OptionPayout
        ResolvablePriceQuantity rpq = ResolvablePriceQuantity.builder()
                .setQuantitySchedule(ReferenceWithMetaNonNegativeQuantitySchedule.builder()
                        .setReference(Reference.builder().setScope("DOCUMENT").setReference("quantity-1").build())
                        .build())
                .build();

        OptionPayout.OptionPayoutBuilder opb = OptionPayout.builder()
                .setPayerReceiver(PayerReceiver.builder()
                        .setPayer(sellerRole)
                        .setReceiver(buyerRole)
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
                .setStrike(strike);

        Payout payout = Payout.builder().setOptionPayout(opb.build()).build();

        // EconomicTerms with optional calculationAgent
        CalculationAgentMapper.Result ca = CalculationAgentMapper.map(trade);
        EconomicTerms.EconomicTermsBuilder econ = EconomicTerms.builder().addPayout(payout);
        if (ca.calculationAgent() != null) econ.setCalculationAgent(ca.calculationAgent());

        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder()
                .setEconomicTerms(econ.build());
        ntp.addTaxonomy(ProductTaxonomy.builder()
                .setSource(TaxonomySourceEnum.ISDA)
                .setProductQualifier("InterestRate_Option_DebtOption")
                .build());
        ProductIdentifierMapper.map(bondOption).forEach(ntp::addIdentifier);

        // TradeLot priceQuantity entries.  When the FpML carries an explicit notionalAmount,
        // the reference output adds a "notional" quantity as quantity-1 and a "contract"
        // multiplier quantity as quantity-2.  Otherwise only the contract+multiplier quantity
        // is emitted (as quantity-1).
        PriceQuantity pq = buildTradeLotPriceQuantity(bondOption, observable);
        TradeLot tradeLot = TradeLot.builder().addPriceQuantity(pq).build();

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
        for (Element premium : XmlUtils.children(bondOption, "premium")) {
            TransferState ts = buildPremiumTransfer(premium);
            if (ts != null) tsBuilder.addTransferHistory(ts);
        }
        // otherPartyPayment at trade level
        for (TransferState ts : TransferMapper.map(trade, null)) {
            tsBuilder.addTransferHistory(ts);
        }

        return tsBuilder.build();
    }

    /* ─────── PARTY_1 / PARTY_2 assignment ─────── */

    private static void assignRoles(String firstHref, MappingContext ctx) {
        if (firstHref == null) return;
        Map<String, Integer> newOrder = new LinkedHashMap<>();
        newOrder.put(firstHref, 0);
        int idx = 1;
        for (String id : ctx.partyOrder.keySet()) {
            if (!id.equals(firstHref)) newOrder.put(id, idx++);
        }
        ctx.partyOrder.clear();
        ctx.partyOrder.putAll(newOrder);
    }

    /* ─────── exercise terms ─────── */

    private static ExerciseTerms buildExerciseTerms(Element bondOption, String buyerHref, String sellerHref) {
        Element european = XmlUtils.child(bondOption, "europeanExercise");
        Element american = XmlUtils.child(bondOption, "americanExercise");
        Element exercise = european != null ? european : american;
        if (exercise == null) return null;

        ExerciseTerms.ExerciseTermsBuilder etb = ExerciseTerms.builder();
        etb.setStyle(european != null
                ? OptionExerciseStyleEnum.EUROPEAN
                : OptionExerciseStyleEnum.AMERICAN);

        // American commencementDate
        if (american != null) {
            Element commencement = XmlUtils.child(american, "commencementDate");
            if (commencement != null) {
                Element adj = XmlUtils.child(commencement, "adjustableDate");
                if (adj != null) {
                    AdjustableDate ad = DateMapper.adjustable(adj);
                    if (ad != null) {
                        etb.setCommencementDate(AdjustableOrRelativeDate.builder()
                                .setAdjustableDate(ad).build());
                    }
                }
            }
        }

        // expirationDate (adjustableDate wrapper)
        Element expDateEl = XmlUtils.child(exercise, "expirationDate");
        if (expDateEl != null) {
            Element adj = XmlUtils.child(expDateEl, "adjustableDate");
            if (adj != null) {
                AdjustableDate ad = DateMapper.adjustable(adj);
                if (ad != null) {
                    etb.addExpirationDate(AdjustableOrRelativeDate.builder()
                            .setAdjustableDate(ad).build());
                }
            }
        }

        // earliestExerciseTime
        Element earliestTime = XmlUtils.child(exercise, "earliestExerciseTime");
        if (earliestTime != null) {
            BusinessCenterTime bct = buildBusinessCenterTime(earliestTime);
            if (bct != null) etb.setEarliestExerciseTime(bct);
        }
        // expirationTime
        Element expTime = XmlUtils.child(exercise, "expirationTime");
        if (expTime != null) {
            BusinessCenterTime bct = buildBusinessCenterTime(expTime);
            if (bct != null) etb.setExpirationTime(bct);
        }
        if (expTime != null || earliestTime != null) {
            etb.setExpirationTimeType(ExpirationTimeTypeEnum.SPECIFIC_TIME);
        }

        // multipleExercise (only American)
        Element multipleEx = american != null ? XmlUtils.child(american, "multipleExercise") : null;
        if (multipleEx != null) {
            MultipleExercise.MultipleExerciseBuilder meb = MultipleExercise.builder();
            String integral = XmlUtils.childText(multipleEx, "integralMultipleAmount");
            if (integral != null) meb.setIntegralMultipleAmount(new BigDecimal(integral));
            String minOpts = XmlUtils.childText(multipleEx, "minimumNumberOfOptions");
            if (minOpts != null) meb.setMinimumNumberOfOptions(Integer.parseInt(minOpts));
            String maxOpts = XmlUtils.childText(multipleEx, "maximumNumberOfOptions");
            if (maxOpts != null) meb.setMaximumNumberOfOptions(Integer.parseInt(maxOpts));
            etb.setMultipleExercise(meb.build());
        }

        // exerciseProcedure (sibling)
        Element procedure = XmlUtils.child(bondOption, "exerciseProcedure");
        if (procedure != null) {
            ExerciseProcedure ep = buildExerciseProcedure(procedure, buyerHref, sellerHref);
            if (ep != null) etb.setExerciseProcedure(ep);
        }

        // externalKey from exercise element's id
        String id = exercise.getAttribute("id");
        if (id != null && !id.isEmpty()) {
            etb.setMeta(MetaFields.builder().setExternalKey(id).build());
        }

        return etb.build();
    }

    private static ExerciseProcedure buildExerciseProcedure(Element procedure, String buyerHref, String sellerHref) {
        ExerciseProcedure.ExerciseProcedureBuilder b = ExerciseProcedure.builder();
        Element manual = XmlUtils.child(procedure, "manualExercise");
        if (manual != null) {
            ManualExercise.ManualExerciseBuilder mb = ManualExercise.builder();
            Element exerciseNotice = XmlUtils.child(manual, "exerciseNotice");
            if (exerciseNotice != null) {
                ExerciseNotice.ExerciseNoticeBuilder enb = ExerciseNotice.builder();
                Element pref = XmlUtils.child(exerciseNotice, "partyReference");
                if (pref != null) {
                    String h = pref.getAttribute("href");
                    if (h != null) {
                        if (h.equals(buyerHref)) enb.setExerciseNoticeGiver(ExerciseNoticeGiverEnum.BUYER);
                        else if (h.equals(sellerHref)) enb.setExerciseNoticeGiver(ExerciseNoticeGiverEnum.SELLER);
                    }
                }
                String bc = XmlUtils.childText(exerciseNotice, "businessCenter");
                if (bc != null) {
                    FieldWithMetaBusinessCenterEnum.FieldWithMetaBusinessCenterEnumBuilder fb =
                            FieldWithMetaBusinessCenterEnum.builder();
                    try { fb.setValue(BusinessCenterEnum.valueOf(bc)); } catch (Exception ignored) {}
                    enb.setBusinessCenter(fb.build());
                }
                mb.setExerciseNotice(enb.build());
            }
            b.setManualExercise(mb.build());
        }
        String followUp = XmlUtils.childText(procedure, "followUpConfirmation");
        if (followUp != null) b.setFollowUpConfirmation(Boolean.parseBoolean(followUp));
        return b.build();
    }

    private static BusinessCenterTime buildBusinessCenterTime(Element el) {
        BusinessCenterTime.BusinessCenterTimeBuilder b = BusinessCenterTime.builder();
        String hmt = XmlUtils.childText(el, "hourMinuteTime");
        if (hmt != null) {
            try { b.setHourMinuteTime(LocalTime.parse(hmt)); }
            catch (Exception ignored) {}
        }
        String bc = XmlUtils.childText(el, "businessCenter");
        if (bc != null) {
            FieldWithMetaBusinessCenterEnum.FieldWithMetaBusinessCenterEnumBuilder fb =
                    FieldWithMetaBusinessCenterEnum.builder();
            try { fb.setValue(BusinessCenterEnum.valueOf(bc)); } catch (Exception ignored) {}
            b.setBusinessCenter(fb.build());
        }
        return b.build();
    }

    /* ─────── settlement terms ─────── */

    private static SettlementTerms buildSettlementTerms(Element bondOption) {
        SettlementTerms.SettlementTermsBuilder stb = SettlementTerms.builder();

        String settlementType = XmlUtils.childText(bondOption, "settlementType");
        if ("Physical".equalsIgnoreCase(settlementType)) {
            stb.setSettlementType(SettlementTypeEnum.PHYSICAL);
        } else if ("Cash".equalsIgnoreCase(settlementType)) {
            stb.setSettlementType(SettlementTypeEnum.CASH);
        }

        Element settlementDateEl = XmlUtils.child(bondOption, "settlementDate");
        if (settlementDateEl != null) {
            Element adjustable = XmlUtils.child(settlementDateEl, "adjustableDate");
            Element relative = XmlUtils.child(settlementDateEl, "relativeDate");
            AdjustableOrAdjustedOrRelativeDate.AdjustableOrAdjustedOrRelativeDateBuilder ab =
                    AdjustableOrAdjustedOrRelativeDate.builder();

            boolean hasAny = false;
            if (adjustable != null) {
                String unadj = XmlUtils.childText(adjustable, "unadjustedDate");
                if (unadj != null) {
                    ab.setUnadjustedDate(DateMapper.parse(unadj));
                    hasAny = true;
                }
                String adj = XmlUtils.childText(adjustable, "adjustedDate");
                if (adj != null) {
                    ab.setAdjustedDate(FieldWithMetaDate.builder()
                            .setValue(DateMapper.parse(adj)).build());
                    hasAny = true;
                }
                BusinessDayAdjustments bda = DateMapper.businessDayAdjustments(
                        XmlUtils.child(adjustable, "dateAdjustments"));
                if (bda != null) {
                    ab.setDateAdjustments(bda);
                    hasAny = true;
                }
            } else if (relative != null) {
                RelativeDateOffset rdo = buildRelativeDateOffset(relative);
                if (rdo != null) {
                    ab.setRelativeDate(rdo);
                    hasAny = true;
                }
            }
            if (hasAny) {
                stb.setSettlementDate(SettlementDate.builder()
                        .setAdjustableOrRelativeDate(ab.build())
                        .build());
            }
        }

        return stb.build();
    }

    private static RelativeDateOffset buildRelativeDateOffset(Element el) {
        RelativeDateOffset.RelativeDateOffsetBuilder b = RelativeDateOffset.builder();
        String pm = XmlUtils.childText(el, "periodMultiplier");
        if (pm != null) b.setPeriodMultiplier(Integer.parseInt(pm));
        String p = XmlUtils.childText(el, "period");
        if (p != null) b.setPeriod(EnumMappers.period(p));
        String dt = XmlUtils.childText(el, "dayType");
        if (dt != null) {
            try { b.setDayType(cdm.base.datetime.DayTypeEnum.valueOf(dt.toUpperCase())); }
            catch (Exception ignored) {}
        }
        String bdc = XmlUtils.childText(el, "businessDayConvention");
        if (bdc != null) b.setBusinessDayConvention(EnumMappers.bdc(bdc));

        Element centers = XmlUtils.child(el, "businessCenters");
        Element centersRef = XmlUtils.child(el, "businessCentersReference");
        if (centers != null) {
            b.setBusinessCenters(DateMapper.buildBusinessCenters(centers));
        } else if (centersRef != null) {
            b.setBusinessCentersReference(ReferenceWithMetaBusinessCenters.builder()
                    .setExternalReference(centersRef.getAttribute("href")).build());
        }
        Element drt = XmlUtils.child(el, "dateRelativeTo");
        if (drt != null) {
            b.setDateRelativeTo(ReferenceWithMetaDate.builder()
                    .setExternalReference(drt.getAttribute("href")).build());
        }
        return b.build();
    }

    /* ─────── strike (price or referenceSwapCurve) ─────── */

    private static OptionStrike buildStrike(Element strike) {
        if (strike == null) return null;

        // Form A: <strike><price><strikePrice>99.7</strikePrice></price></strike>
        Element priceEl = XmlUtils.child(strike, "price");
        if (priceEl != null) {
            String spStr = XmlUtils.childText(priceEl, "strikePrice");
            if (spStr != null) {
                Price.PriceBuilder pb = Price.builder()
                        .setValue(new BigDecimal(spStr))
                        .setPriceType(PriceTypeEnum.ASSET_PRICE);
                return OptionStrike.builder().setStrikePrice(pb.build()).build();
            }
        }

        // Form B: <strike><referenceSwapCurve>...</referenceSwapCurve></strike>
        Element refCurveEl = XmlUtils.child(strike, "referenceSwapCurve");
        if (refCurveEl != null) {
            ReferenceSwapCurve rsc = buildReferenceSwapCurve(refCurveEl);
            if (rsc != null) {
                return OptionStrike.builder().setReferenceSwapCurve(rsc).build();
            }
        }
        return null;
    }

    private static ReferenceSwapCurve buildReferenceSwapCurve(Element el) {
        ReferenceSwapCurve.ReferenceSwapCurveBuilder b = ReferenceSwapCurve.builder();
        Element swapUnwind = XmlUtils.child(el, "swapUnwindValue");
        if (swapUnwind != null) {
            SwapCurveValuation scv = buildSwapCurveValuation(swapUnwind);
            if (scv != null) b.setSwapUnwindValue(scv);
        }
        Element mwa = XmlUtils.child(el, "makeWholeAmount");
        if (mwa != null) {
            MakeWholeAmount.MakeWholeAmountBuilder mwb = MakeWholeAmount.builder();
            // MakeWholeAmount extends SwapCurveValuation; populate base fields then specifics
            String fri = XmlUtils.childText(mwa, "floatingRateIndex");
            if (fri != null) {
                FloatingRateIndexEnum e = floatingRateIndexEnum(fri);
                if (e != null) mwb.setFloatingRateIndex(e);
            }
            Element tenor = XmlUtils.child(mwa, "indexTenor");
            if (tenor != null) {
                Period period = buildPeriod(tenor);
                if (period != null) mwb.setIndexTenor(period);
            }
            String spread = XmlUtils.childText(mwa, "spread");
            if (spread != null) mwb.setSpread(new BigDecimal(spread));
            String side = XmlUtils.childText(mwa, "side");
            if (side != null) {
                QuotationSideEnum qs = parseQuotationSide(side);
                if (qs != null) mwb.setSide(qs);
            }
            String interp = XmlUtils.childText(mwa, "interpolationMethod");
            if (interp != null) {
                InterpolationMethodEnum interpEnum = null;
                try { interpEnum = InterpolationMethodEnum.valueOf(interp); } catch (Exception ignored) {}
                if (interpEnum == null) {
                    try { interpEnum = InterpolationMethodEnum.fromDisplayName(interp); } catch (Exception ignored) {}
                }
                if (interpEnum == null) {
                    // Convert camelCase like "LinearZeroYield" to UPPER_SNAKE_CASE
                    String snake = interp.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase();
                    try { interpEnum = InterpolationMethodEnum.valueOf(snake); } catch (Exception ignored) {}
                }
                if (interpEnum != null) mwb.setInterpolationMethod(interpEnum);
            }
            String earlyCall = XmlUtils.childText(mwa, "earlyCallDate");
            if (earlyCall != null) {
                mwb.setEarlyCallDate(FieldWithMetaDate.builder()
                        .setValue(DateMapper.parse(earlyCall)).build());
            }
            b.setMakeWholeAmount(mwb.build());
        }
        return b.build();
    }

    private static SwapCurveValuation buildSwapCurveValuation(Element el) {
        SwapCurveValuation.SwapCurveValuationBuilder b = SwapCurveValuation.builder();
        String fri = XmlUtils.childText(el, "floatingRateIndex");
        if (fri != null) {
            FloatingRateIndexEnum e = floatingRateIndexEnum(fri);
            if (e != null) b.setFloatingRateIndex(e);
        }
        Element tenor = XmlUtils.child(el, "indexTenor");
        if (tenor != null) {
            Period period = buildPeriod(tenor);
            if (period != null) b.setIndexTenor(period);
        }
        String spread = XmlUtils.childText(el, "spread");
        if (spread != null) b.setSpread(new BigDecimal(spread));
        String side = XmlUtils.childText(el, "side");
        if (side != null) {
            QuotationSideEnum qs = parseQuotationSide(side);
            if (qs != null) b.setSide(qs);
        }
        return b.build();
    }

    private static QuotationSideEnum parseQuotationSide(String text) {
        if (text == null) return null;
        // Try a few naming styles: "Bid", "BID", "Bid" via fromDisplayName, etc.
        try { return QuotationSideEnum.valueOf(text); } catch (Exception ignored) {}
        try { return QuotationSideEnum.valueOf(text.toUpperCase()); } catch (Exception ignored) {}
        try { return QuotationSideEnum.fromDisplayName(text); } catch (Exception ignored) {}
        try { return QuotationSideEnum.fromDisplayName(capitalize(text)); } catch (Exception ignored) {}
        return null;
    }

    private static FloatingRateIndexEnum floatingRateIndexEnum(String text) {
        if (text == null) return null;
        try { return FloatingRateIndexEnum.fromDisplayName(text); } catch (Exception ignored) {}
        String alt = text.replace('-', '_').replace('.', '_');
        for (FloatingRateIndexEnum c : FloatingRateIndexEnum.values()) {
            if (c.name().equalsIgnoreCase(alt)) return c;
        }
        return null;
    }

    private static Period buildPeriod(Element el) {
        Period.PeriodBuilder b = Period.builder();
        String pm = XmlUtils.childText(el, "periodMultiplier");
        if (pm != null) b.setPeriodMultiplier(Integer.parseInt(pm));
        String p = XmlUtils.childText(el, "period");
        if (p != null) {
            try { b.setPeriod(EnumMappers.period(p)); }
            catch (Exception ignored) {}
        }
        return b.build();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /* ─────── observable (bond Security) ─────── */

    private static Observable buildBondObservable(Element bondEl) {
        if (bondEl == null) return null;
        Security.SecurityBuilder secb = Security.builder();
        for (Element instId : XmlUtils.children(bondEl, "instrumentId")) {
            String value = instId.getTextContent().trim();
            String scheme = instId.getAttribute("instrumentIdScheme");
            AssetIdentifier.AssetIdentifierBuilder aib = AssetIdentifier.builder()
                    .setIdentifier(FieldWithMetaString.builder()
                            .setValue(value)
                            .setMeta(scheme != null && !scheme.isEmpty()
                                    ? MetaFields.builder().setScheme(scheme).build() : null)
                            .build())
                    .setIdentifierType(EquityOptionMapper.mapAssetIdType(scheme));
            secb.addIdentifier(aib.build());
        }
        return Observable.builder()
                .setAsset(Asset.builder()
                        .setInstrument(Instrument.builder()
                                .setSecurity(secb.build())
                                .build())
                        .build())
                .build();
    }

    /* ─────── tradeLot priceQuantity ─────── */

    private static PriceQuantity buildTradeLotPriceQuantity(Element bondOption, Observable observable) {
        PriceQuantity.PriceQuantityBuilder pqb = PriceQuantity.builder();

        Element notionalEl = XmlUtils.child(bondOption, "notionalAmount");
        String numberOfOptions = XmlUtils.childText(bondOption, "numberOfOptions");
        String optionEntitlement = XmlUtils.childText(bondOption, "optionEntitlement");
        String entitlementCurrency = XmlUtils.childText(bondOption, "entitlementCurrency");

        boolean notionalAvail = notionalEl != null
                && XmlUtils.childText(notionalEl, "amount") != null
                && XmlUtils.childText(notionalEl, "currency") != null;

        if (notionalAvail) {
            // quantity-1: notional currency amount
            String ccy = XmlUtils.childText(notionalEl, "currency");
            String amt = XmlUtils.childText(notionalEl, "amount");
            NonNegativeQuantitySchedule.NonNegativeQuantityScheduleBuilder n1 = NonNegativeQuantitySchedule.builder()
                    .setValue(new BigDecimal(amt))
                    .setUnit(UnitType.builder()
                            .setCurrency(FieldWithMetaString.builder().setValue(ccy).build())
                            .build());
            pqb.addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder()
                    .setValue(n1.build())
                    .setMeta(QuantityMapper.locationMeta("quantity-1"))
                    .build());

            // quantity-2: contract + multiplier(contract+ccy)
            if (numberOfOptions != null && optionEntitlement != null) {
                NonNegativeQuantitySchedule.NonNegativeQuantityScheduleBuilder n2 = buildContractQty(
                        numberOfOptions, optionEntitlement,
                        entitlementCurrency != null ? entitlementCurrency : ccy);
                pqb.addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder()
                        .setValue(n2.build())
                        .setMeta(QuantityMapper.locationMeta("quantity-2"))
                        .build());
            }
        } else if (numberOfOptions != null && optionEntitlement != null) {
            // Only the contract+multiplier quantity (as quantity-1)
            NonNegativeQuantitySchedule.NonNegativeQuantityScheduleBuilder n1 = buildContractQty(
                    numberOfOptions, optionEntitlement, entitlementCurrency);
            pqb.addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder()
                    .setValue(n1.build())
                    .setMeta(QuantityMapper.locationMeta("quantity-1"))
                    .build());
        }

        if (observable != null) {
            pqb.setObservable(FieldWithMetaObservable.builder()
                    .setValue(observable)
                    .setMeta(QuantityMapper.locationMeta("observable-1"))
                    .build());
        }

        return pqb.build();
    }

    private static NonNegativeQuantitySchedule.NonNegativeQuantityScheduleBuilder buildContractQty(
            String numberOfOptions, String optionEntitlement, String entitlementCurrency) {
        NonNegativeQuantitySchedule.NonNegativeQuantityScheduleBuilder qb = NonNegativeQuantitySchedule.builder()
                .setValue(new BigDecimal(numberOfOptions))
                .setUnit(UnitType.builder()
                        .setFinancialUnit(FinancialUnitEnum.CONTRACT)
                        .build());
        UnitType.UnitTypeBuilder multUnit = UnitType.builder()
                .setFinancialUnit(FinancialUnitEnum.CONTRACT);
        if (entitlementCurrency != null) {
            multUnit.setCurrency(FieldWithMetaString.builder().setValue(entitlementCurrency).build());
        }
        qb.setMultiplier(Measure.builder()
                .setValue(new BigDecimal(optionEntitlement))
                .setUnit(multUnit.build())
                .build());
        return qb;
    }

    /* ─────── premium transfer ─────── */

    private static TransferState buildPremiumTransfer(Element premium) {
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
                    .setCash(cdm.base.staticdata.asset.common.Cash.builder()
                            .addIdentifier(AssetIdentifier.builder()
                                    .setIdentifier(FieldWithMetaString.builder().setValue(ccy).build())
                                    .setIdentifierType(AssetIdTypeEnum.CURRENCY_CODE)
                                    .build())
                            .build())
                    .build());
        }

        Element payDate = XmlUtils.child(premium, "paymentDate");
        if (payDate != null) {
            AdjustableOrAdjustedOrRelativeDate.AdjustableOrAdjustedOrRelativeDateBuilder sdb =
                    AdjustableOrAdjustedOrRelativeDate.builder();
            Element adjDate = XmlUtils.child(payDate, "adjustableDate");
            if (adjDate != null) {
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
            cdm.base.staticdata.party.PartyReferencePayerReceiver.PartyReferencePayerReceiverBuilder pr =
                    cdm.base.staticdata.party.PartyReferencePayerReceiver.builder();
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
}
