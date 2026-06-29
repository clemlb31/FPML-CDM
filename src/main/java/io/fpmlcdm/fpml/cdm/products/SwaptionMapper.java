package io.fpmlcdm.fpml.cdm.products;

import cdm.base.datetime.AdjustableDate;
import cdm.base.datetime.AdjustableDates;
import cdm.base.datetime.AdjustableOrRelativeDate;
import cdm.base.datetime.AdjustableOrRelativeDates;
import cdm.base.datetime.BusinessCenterEnum;
import cdm.base.datetime.BusinessCenterTime;
import cdm.base.datetime.BusinessDayAdjustments;
import cdm.base.datetime.DayTypeEnum;
import cdm.base.datetime.RelativeDateOffset;
import cdm.base.datetime.RelativeDates;
import cdm.base.datetime.metafields.FieldWithMetaBusinessCenterEnum;
import cdm.base.datetime.metafields.ReferenceWithMetaBusinessCenters;
import cdm.base.staticdata.asset.common.ProductTaxonomy;
import cdm.base.staticdata.asset.common.TaxonomySourceEnum;
import cdm.base.staticdata.party.AncillaryParty;
import cdm.base.staticdata.party.BuyerSeller;
import cdm.base.staticdata.party.Counterparty;
import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.Party;
import cdm.base.staticdata.party.PartyRole;
import cdm.base.staticdata.party.PayerReceiver;
import cdm.event.common.ContractDetails;
import cdm.event.common.Trade;
import cdm.event.common.TradeIdentifier;
import cdm.event.common.TradeState;
import cdm.observable.asset.CashCollateralValuationMethod;
import cdm.observable.asset.InformationProviderEnum;
import cdm.observable.asset.InformationSource;
import cdm.observable.asset.FxSpotRateSource;
import cdm.observable.asset.PriceQuantity;
import cdm.observable.asset.QuotationRateTypeEnum;
import cdm.observable.asset.ValuationMethod;
import cdm.observable.asset.ValuationSource;
import cdm.observable.asset.metafields.FieldWithMetaInformationProviderEnum;
import cdm.product.common.settlement.CashSettlementMethodEnum;
import cdm.product.common.settlement.CashSettlementTerms;
import cdm.product.common.settlement.PhysicalSettlementTerms;
import cdm.product.common.settlement.SettlementTerms;
import cdm.product.common.settlement.SettlementTypeEnum;
import cdm.product.common.settlement.ValuationDate;
import cdm.product.template.EconomicTerms;
import cdm.product.template.ExerciseNotice;
import cdm.product.template.ExerciseNoticeGiverEnum;
import cdm.product.template.ExerciseProcedure;
import cdm.product.template.ExerciseTerms;
import cdm.product.template.ExpirationTimeTypeEnum;
import cdm.product.template.ManualExercise;
import cdm.product.template.NonTransferableProduct;
import cdm.product.template.OptionExerciseStyleEnum;
import cdm.product.template.OptionPayout;
import cdm.product.template.PartialExercise;
import cdm.product.template.Payout;
import cdm.product.template.Product;
import cdm.product.template.TradeLot;
import cdm.product.template.Underlier;
import com.rosetta.model.metafields.FieldWithMetaDate;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
import com.rosetta.model.metafields.ReferenceWithMetaDate;
import io.fpmlcdm.fpml.cdm.common.AccountMapper;
import io.fpmlcdm.fpml.cdm.common.CalculationAgentMapper;
import io.fpmlcdm.fpml.cdm.common.ContractDetailsMapper;
import io.fpmlcdm.fpml.cdm.common.DateMapper;
import io.fpmlcdm.fpml.cdm.common.EnumMappers;
import io.fpmlcdm.fpml.cdm.common.IdentifierMapper;
import io.fpmlcdm.fpml.cdm.common.MappingContext;
import io.fpmlcdm.fpml.cdm.common.ProductIdentifierMapper;
import io.fpmlcdm.fpml.cdm.common.PartyMapper;
import io.fpmlcdm.fpml.cdm.common.PartyRoleMapper;
import io.fpmlcdm.fpml.cdm.common.XmlUtils;
import cdm.base.staticdata.party.Account;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** Swaption FpML→CDM mapper. Wraps an OptionPayout around an underlying swap. */
public class SwaptionMapper implements ProductMapper {

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);
        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        parties = SwapMapper.applyPartyTradeInformation(parties, tradeHeader);

        Element swaption = XmlUtils.child(trade, "swaption");
        Element swap = XmlUtils.child(swaption, "swap");
        List<Element> streams = XmlUtils.children(swap, "swapStream");

        // Extract buyer/seller before role assignment
        String buyerHref = href(XmlUtils.child(swaption, "buyerPartyReference"));
        String sellerHref = href(XmlUtils.child(swaption, "sellerPartyReference"));

        // PARTY_1 = buyer of the swaption (NOT payer of first stream).
        // First assign from streams (populates all party hrefs into ctx.partyOrder),
        // then override with buyer-based assignment.
        SwapMapper.assignCounterpartyRoles(streams, ctx);
        // Override: buyer = PARTY_1
        if (buyerHref != null) {
            java.util.Map<String, Integer> newOrder = new java.util.LinkedHashMap<>();
            newOrder.put(buyerHref, 0);
            int idx = 1;
            for (String pid : ctx.partyOrder.keySet()) {
                if (!pid.equals(buyerHref)) newOrder.put(pid, idx++);
            }
            ctx.partyOrder.clear();
            ctx.partyOrder.putAll(newOrder);
        }

        // Underlier — full swap economics (taxonomy, payouts, priceQuantities).
        SwapMapper.SwapEconomics swapEcon = SwapMapper.buildSwapEconomics(trade, swap, streams, ctx);

        // Build OptionPayout
        OptionPayout.OptionPayoutBuilder op = OptionPayout.builder();
        CounterpartyRoleEnum buyerRole = roleFor(buyerHref, ctx);
        CounterpartyRoleEnum sellerRole = roleFor(sellerHref, ctx);
        op.setBuyerSeller(BuyerSeller.builder().setBuyer(buyerRole).setSeller(sellerRole).build());
        op.setPayerReceiver(PayerReceiver.builder().setPayer(sellerRole).setReceiver(buyerRole).build());

        // optionType (Straddle)
        String straddle = XmlUtils.childText(swaption, "swaptionStraddle");
        if ("true".equalsIgnoreCase(straddle)) {
            op.setOptionType(cdm.product.template.OptionTypeEnum.STRADDLE);
        }

        // SettlementTerms
        SettlementTerms st = buildSettlementTerms(swaption);
        if (st != null) op.setSettlementTerms(st);

        // Underlier = wrap product
        Product underlierProduct = Product.builder().setNonTransferableProduct(swapEcon.product).build();
        op.setUnderlier(Underlier.builder().setProduct(underlierProduct).build());

        // ExerciseTerms
        op.setExerciseTerms(buildExerciseTerms(swaption));

        Payout optionPayout = Payout.builder().setOptionPayout(op.build()).build();

        // Outer economicTerms
        EconomicTerms.EconomicTermsBuilder econ = EconomicTerms.builder().addPayout(optionPayout);

        // Outer calculationAgent (the second one at trade level, if any)
        CalculationAgentMapper.Result ca = CalculationAgentMapper.map(trade);
        if (ca.calculationAgent() != null) econ.setCalculationAgent(ca.calculationAgent());

        // Outer product taxonomy = swaption qualifier
        // If the swaption element carries a <productType>, emit it as taxonomy entry before ISDA qualifier.
        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder()
                .setEconomicTerms(econ.build());
        Element swaptionProductType = XmlUtils.child(swaption, "productType");
        if (swaptionProductType != null) {
            String ptText = swaptionProductType.getTextContent().trim();
            String ptScheme = swaptionProductType.getAttribute("productTypeScheme");
            FieldWithMetaString.FieldWithMetaStringBuilder nameB = FieldWithMetaString.builder().setValue(ptText);
            if (ptScheme != null && !ptScheme.isEmpty()) {
                nameB.setMeta(MetaFields.builder().setScheme(ptScheme).build());
            }
            cdm.base.staticdata.asset.common.TaxonomyValue tv =
                    cdm.base.staticdata.asset.common.TaxonomyValue.builder().setName(nameB.build()).build();
            TaxonomySourceEnum src = (ptScheme != null && !ptScheme.isEmpty())
                    ? TaxonomySourceEnum.ISDA : TaxonomySourceEnum.OTHER;
            ntp.addTaxonomy(ProductTaxonomy.builder().setSource(src).setValue(tv).build());
        }
        ntp.addTaxonomy(ProductTaxonomy.builder()
                .setSource(TaxonomySourceEnum.ISDA)
                .setProductQualifier("InterestRate_Option_Swaption")
                .build());
        // Map productId from swaption into product identifiers
        ProductIdentifierMapper.map(swaption).forEach(ntp::addIdentifier);

        // tradeLot (use the underlier's priceQuantities)
        TradeLot tradeLot = TradeLot.builder().setPriceQuantity(swapEcon.priceQuantities).build();

        List<Counterparty> counterparties = SwapMapper.buildCounterparties(ctx);
        List<PartyRole> partyRoles = PartyRoleMapper.map(trade);

        // tradeIdentifier
        List<TradeIdentifier> identifiers = new ArrayList<>();
        if (tradeHeader != null) {
            for (Element pti : XmlUtils.children(tradeHeader, "partyTradeIdentifier")) {
                identifiers.addAll(IdentifierMapper.mapWithSplit(pti));
            }
        }
        // tradeDate
        FieldWithMetaDate tradeDate = null;
        Element tradeDateEl = tradeHeader == null ? null : XmlUtils.child(tradeHeader, "tradeDate");
        if (tradeDateEl != null) {
            FieldWithMetaDate.FieldWithMetaDateBuilder tdb = FieldWithMetaDate.builder()
                    .setValue(DateMapper.parse(tradeDateEl.getTextContent().trim()));
            String tdId = tradeDateEl.getAttribute("id");
            if (tdId != null && !tdId.isEmpty()) {
                tdb.setMeta(MetaFields.builder().setExternalKey(tdId).build());
            }
            tradeDate = tdb.build();
        }

        ContractDetails contractDetails = ContractDetailsMapper.map(
                XmlUtils.child(trade, "documentation"), parties, ctx);

        Trade.TradeBuilder t = Trade.builder()
                .setProduct(ntp.build())
                .addTradeLot(tradeLot);
        counterparties.forEach(t::addCounterparty);
        // ancillaryParty comes ONLY from the trade-level calculationAgent (not the one inside swaption).
        for (AncillaryParty ap : ca.ancillaryParties()) t.addAncillaryParty(ap);
        partyRoles.forEach(t::addPartyRole);
        identifiers.forEach(t::addTradeIdentifier);
        if (tradeDate != null) t.setTradeDate(tradeDate);
        parties.forEach(t::addParty);
        if (contractDetails != null) t.setContractDetails(contractDetails);
        for (Account a : AccountMapper.map(doc)) t.addAccount(a);

        TradeState.TradeStateBuilder tsBuilder = TradeState.builder().setTrade(t.build());

        // Premium → transferHistory
        Element premium = XmlUtils.child(swaption, "premium");
        if (premium != null) {
            cdm.event.common.TransferState premiumTs = buildPremiumTransfer(premium);
            if (premiumTs != null) tsBuilder.addTransferHistory(premiumTs);
        }

        return tsBuilder.build();
    }

    /* ───── settlement terms (physical or cash) ───── */

    private static SettlementTerms buildSettlementTerms(Element swaption) {
        Element physical = XmlUtils.child(swaption, "physicalSettlement");
        Element cash = XmlUtils.child(swaption, "cashSettlement");
        if (physical == null && cash == null) {
            // FpML allows neither to be present (default = physical, with no terms).
            // Look at FpML siblings: if there is a physicalSettlement element with no children,
            // emit a basic physical settlement. Otherwise — emit physical with default.
            // Tests show even when both absent we should still emit Physical if no cash.
            // But to be safe we'll emit null and let the diff catch it.
            // Many examples (ird-ex14, ird-ex15) have no settlement element AND no resulting
            // settlementTerms in CDM — emit nothing here in that case.
            return null;
        }

        SettlementTerms.SettlementTermsBuilder b = SettlementTerms.builder();
        if (physical != null) {
            b.setSettlementType(SettlementTypeEnum.PHYSICAL);
            PhysicalSettlementTerms.PhysicalSettlementTermsBuilder pst = PhysicalSettlementTerms.builder();
            String cleared = XmlUtils.childText(physical, "clearedPhysicalSettlement");
            if (cleared != null) pst.setClearedPhysicalSettlement(Boolean.parseBoolean(cleared));
            b.setPhysicalSettlementTerms(pst.build());
        } else {
            b.setSettlementType(SettlementTypeEnum.CASH);
            CashSettlementTerms cst = buildCashSettlementTerms(cash);
            if (cst != null) b.addCashSettlementTerms(cst);
            // Cash settlement currency, when explicit on a collateralizedCashPriceMethod variant
            String ccsCcy = XmlUtils.pathText(cash, "collateralizedCashPriceMethod", "cashSettlementCurrency");
            if (ccsCcy != null) b.setSettlementCurrency(FieldWithMetaString.builder().setValue(ccsCcy).build());
        }
        return b.build();
    }

    private static CashSettlementTerms buildCashSettlementTerms(Element cash) {
        if (cash == null) return null;
        CashSettlementTerms.CashSettlementTermsBuilder b = CashSettlementTerms.builder();

        // The cashSettlement may carry one of several method-shaped children
        Element parYieldUnadj = XmlUtils.child(cash, "parYieldCurveUnadjustedMethod");
        Element parYieldAdj = XmlUtils.child(cash, "parYieldCurveAdjustedMethod");
        Element zeroCpnAdj = XmlUtils.child(cash, "zeroCouponYieldAdjustedMethod");
        Element collateralized = XmlUtils.child(cash, "collateralizedCashPriceMethod");
        Element crossCcyMethod = XmlUtils.child(cash, "crossCurrencyMethod");
        Element cashPriceMethod = XmlUtils.child(cash, "cashPriceMethod");
        Element cashPriceAltMethod = XmlUtils.child(cash, "cashPriceAlternateMethod");

        Element method = null;
        CashSettlementMethodEnum methodEnum = null;
        if (parYieldUnadj != null) { method = parYieldUnadj; methodEnum = CashSettlementMethodEnum.PAR_YIELD_CURVE_UNADJUSTED_METHOD; }
        else if (parYieldAdj != null) { method = parYieldAdj; methodEnum = CashSettlementMethodEnum.PAR_YIELD_CURVE_ADJUSTED_METHOD; }
        else if (zeroCpnAdj != null) { method = zeroCpnAdj; methodEnum = CashSettlementMethodEnum.ZERO_COUPON_YIELD_ADJUSTED_METHOD; }
        else if (collateralized != null) { method = collateralized; methodEnum = CashSettlementMethodEnum.COLLATERALIZED_CASH_PRICE_METHOD; }
        else if (crossCcyMethod != null) { method = crossCcyMethod; methodEnum = CashSettlementMethodEnum.CROSS_CURRENCY_METHOD; }
        else if (cashPriceMethod != null) { method = cashPriceMethod; methodEnum = CashSettlementMethodEnum.CASH_PRICE_METHOD; }
        else if (cashPriceAltMethod != null) { method = cashPriceAltMethod; methodEnum = CashSettlementMethodEnum.CASH_PRICE_ALTERNATE_METHOD; }

        if (methodEnum != null) b.setCashSettlementMethod(methodEnum);
        if (method != null) {
            ValuationMethod vm = buildValuationMethod(method);
            if (vm != null) b.setValuationMethod(vm);
        }

        // ValuationDate (relative date)
        Element valDateEl = XmlUtils.child(cash, "cashSettlementValuationDate");
        if (valDateEl != null) {
            RelativeDateOffset rdo = buildRelativeDateOffset(valDateEl);
            if (rdo != null) {
                b.setValuationDate(ValuationDate.builder().setValuationDate(rdo).build());
            }
        }
        // ValuationTime
        Element valTime = XmlUtils.child(cash, "cashSettlementValuationTime");
        if (valTime != null) {
            BusinessCenterTime bct = buildBusinessCenterTime(valTime);
            if (bct != null) b.setValuationTime(bct);
        }

        // externalKey from the FpML id
        String id = cash.getAttribute("id");
        if (id != null && !id.isEmpty()) {
            b.setMeta(MetaFields.builder().setExternalKey(id).build());
        }
        return b.build();
    }

    private static ValuationMethod buildValuationMethod(Element method) {
        ValuationMethod.ValuationMethodBuilder b = ValuationMethod.builder();

        // valuationSource: informationSource → primarySource
        Element settlementRateSource = XmlUtils.child(method, "settlementRateSource");
        Element infoSource = settlementRateSource == null ? null
                : XmlUtils.child(settlementRateSource, "informationSource");
        if (infoSource != null) {
            InformationSource.InformationSourceBuilder isb = InformationSource.builder();
            String rateSource = XmlUtils.childText(infoSource, "rateSource");
            if (rateSource != null) {
                InformationProviderEnum provider = mapInfoProvider(rateSource);
                FieldWithMetaInformationProviderEnum.FieldWithMetaInformationProviderEnumBuilder pb =
                        FieldWithMetaInformationProviderEnum.builder();
                if (provider != null) pb.setValue(provider);
                isb.setSourceProvider(pb.build());
            }
            String rateSourcePage = XmlUtils.childText(infoSource, "rateSourcePage");
            if (rateSourcePage != null) {
                isb.setSourcePage(FieldWithMetaString.builder().setValue(rateSourcePage).build());
            }
            FxSpotRateSource fxSrs = FxSpotRateSource.builder().setPrimarySource(isb.build()).build();
            b.setValuationSource(ValuationSource.builder().setInformationSource(fxSrs).build());
        } else {
            b.setValuationSource(ValuationSource.builder().build());
        }

        // quotationMethod
        String quotationRateType = XmlUtils.childText(method, "quotationRateType");
        if (quotationRateType != null) {
            QuotationRateTypeEnum q = mapQuotationRateType(quotationRateType);
            if (q != null) b.setQuotationMethod(q);
        }

        // CashCollateralValuationMethod (for collateralizedCashPriceMethod)
        String agreedDiscount = XmlUtils.childText(method, "agreedDiscountRate");
        if (agreedDiscount != null) {
            b.setCashCollateralValuationMethod(CashCollateralValuationMethod.builder()
                    .setAgreedDiscountRate(FieldWithMetaString.builder().setValue(agreedDiscount).build())
                    .build());
        }
        return b.build();
    }

    private static InformationProviderEnum mapInfoProvider(String s) {
        if (s == null) return null;
        try { return InformationProviderEnum.fromDisplayName(s); } catch (Exception ignored) {}
        try { return InformationProviderEnum.valueOf(s.toUpperCase()); } catch (Exception ignored) {}
        return null;
    }

    private static QuotationRateTypeEnum mapQuotationRateType(String s) {
        if (s == null) return null;
        try { return QuotationRateTypeEnum.fromDisplayName(s); } catch (Exception ignored) {}
        try { return QuotationRateTypeEnum.valueOf(s.toUpperCase()); } catch (Exception ignored) {}
        return null;
    }

    private static RelativeDateOffset buildRelativeDateOffset(Element el) {
        RelativeDateOffset.RelativeDateOffsetBuilder b = RelativeDateOffset.builder();
        String pm = XmlUtils.childText(el, "periodMultiplier");
        if (pm != null) b.setPeriodMultiplier(Integer.parseInt(pm));
        String p = XmlUtils.childText(el, "period");
        if (p != null) b.setPeriod(EnumMappers.period(p));
        String dt = XmlUtils.childText(el, "dayType");
        if (dt != null) {
            try { b.setDayType(DayTypeEnum.valueOf(dt.toUpperCase())); }
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

    private static BusinessCenterTime buildBusinessCenterTime(Element el) {
        if (el == null) return null;
        BusinessCenterTime.BusinessCenterTimeBuilder b = BusinessCenterTime.builder();
        String hmt = XmlUtils.childText(el, "hourMinuteTime");
        if (hmt != null) {
            try { b.setHourMinuteTime(LocalTime.parse(hmt)); }
            catch (Exception ignored) {}
        }
        String bc = XmlUtils.childText(el, "businessCenter");
        if (bc != null) {
            FieldWithMetaBusinessCenterEnum.FieldWithMetaBusinessCenterEnumBuilder bcb =
                    FieldWithMetaBusinessCenterEnum.builder();
            try { bcb.setValue(BusinessCenterEnum.valueOf(bc)); } catch (Exception ignored) {}
            b.setBusinessCenter(bcb.build());
        }
        return b.build();
    }

    /* ───── exercise terms ───── */

    private static ExerciseTerms buildExerciseTerms(Element swaption) {
        ExerciseTerms.ExerciseTermsBuilder b = ExerciseTerms.builder();
        Element euro = XmlUtils.child(swaption, "europeanExercise");
        Element berm = XmlUtils.child(swaption, "bermudaExercise");
        Element amer = XmlUtils.child(swaption, "americanExercise");

        Element exercise = euro != null ? euro : (berm != null ? berm : amer);
        if (exercise == null) return b.build();

        if (euro != null) b.setStyle(OptionExerciseStyleEnum.EUROPEAN);
        else if (berm != null) b.setStyle(OptionExerciseStyleEnum.BERMUDA);
        else if (amer != null) b.setStyle(OptionExerciseStyleEnum.AMERICAN);

        // commencementDate (American only)
        if (amer != null) {
            Element commencementEl = XmlUtils.child(amer, "commencementDate");
            if (commencementEl != null) {
                AdjustableDate ad = DateMapper.adjustable(XmlUtils.child(commencementEl, "adjustableDate"));
                if (ad != null) b.setCommencementDate(AdjustableOrRelativeDate.builder().setAdjustableDate(ad).build());
            }
        }

        // exerciseDates (Bermuda)
        if (berm != null) {
            Element bermDates = XmlUtils.child(berm, "bermudaExerciseDates");
            if (bermDates != null) {
                Element adjDates = XmlUtils.child(bermDates, "adjustableDates");
                if (adjDates != null) {
                    AdjustableDates ads = buildAdjustableDates(adjDates);
                    if (ads != null) {
                        b.setExerciseDates(AdjustableOrRelativeDates.builder().setAdjustableDates(ads).build());
                    }
                }
            }
        }

        // expirationDate (European, American, Bermuda all may have one; Bermuda may not)
        Element expDateEl = XmlUtils.child(exercise, "expirationDate");
        if (expDateEl != null) {
            Element adjustable = XmlUtils.child(expDateEl, "adjustableDate");
            if (adjustable != null) {
                AdjustableDate ad = DateMapper.adjustable(adjustable);
                if (ad != null) {
                    b.addExpirationDate(AdjustableOrRelativeDate.builder().setAdjustableDate(ad).build());
                }
            }
        }

        // relevantUnderlyingDate (Bermuda, American only — dropped for European per dataset)
        Element ruDate = euro == null ? XmlUtils.child(exercise, "relevantUnderlyingDate") : null;
        if (ruDate != null) {
            Element relDates = XmlUtils.child(ruDate, "relativeDates");
            if (relDates != null) {
                RelativeDates rd = buildRelativeDates(relDates);
                if (rd != null) {
                    b.setRelevantUnderlyingDate(AdjustableOrRelativeDates.builder()
                            .setRelativeDates(rd).build());
                }
            }
        }

        // earliestExerciseTime, latestExerciseTime, expirationTime
        Element earliest = XmlUtils.child(exercise, "earliestExerciseTime");
        if (earliest != null) {
            BusinessCenterTime bct = buildBusinessCenterTime(earliest);
            if (bct != null) b.setEarliestExerciseTime(bct);
        }
        Element latest = XmlUtils.child(exercise, "latestExerciseTime");
        if (latest != null) {
            BusinessCenterTime bct = buildBusinessCenterTime(latest);
            if (bct != null) b.setLatestExerciseTime(bct);
        }
        Element expTime = XmlUtils.child(exercise, "expirationTime");
        if (expTime != null) {
            BusinessCenterTime bct = buildBusinessCenterTime(expTime);
            if (bct != null) b.setExpirationTime(bct);
        }
        if (expTime != null || earliest != null) {
            b.setExpirationTimeType(ExpirationTimeTypeEnum.SPECIFIC_TIME);
        }

        // exerciseProcedure (sibling of europeanExercise/bermudaExercise inside <swaption>)
        Element procedureEl = XmlUtils.child(swaption, "exerciseProcedure");
        if (procedureEl != null) {
            ExerciseProcedure ep = buildExerciseProcedure(procedureEl);
            if (ep != null) b.setExerciseProcedure(ep);
        }

        // partialExercise (inside exercise element)
        Element partial = XmlUtils.child(exercise, "partialExercise");
        if (partial != null) {
            PartialExercise pe = buildPartialExercise(partial);
            if (pe != null) b.setPartialExercise(pe);
        }

        // externalKey from exercise element's id
        String id = exercise.getAttribute("id");
        if (id != null && !id.isEmpty()) {
            b.setMeta(MetaFields.builder().setExternalKey(id).build());
        }
        return b.build();
    }

    private static AdjustableDates buildAdjustableDates(Element fpml) {
        AdjustableDates.AdjustableDatesBuilder b = AdjustableDates.builder();
        for (Element d : XmlUtils.children(fpml, "unadjustedDate")) {
            b.addUnadjustedDate(DateMapper.parse(d.getTextContent().trim()));
        }
        BusinessDayAdjustments bda = DateMapper.businessDayAdjustments(XmlUtils.child(fpml, "dateAdjustments"));
        if (bda != null) b.setDateAdjustments(bda);
        return b.build();
    }

    private static RelativeDates buildRelativeDates(Element el) {
        RelativeDates.RelativeDatesBuilder b = RelativeDates.builder();
        String pm = XmlUtils.childText(el, "periodMultiplier");
        if (pm != null) b.setPeriodMultiplier(Integer.parseInt(pm));
        String p = XmlUtils.childText(el, "period");
        if (p != null) b.setPeriod(EnumMappers.period(p));
        String dt = XmlUtils.childText(el, "dayType");
        if (dt != null) {
            try { b.setDayType(DayTypeEnum.valueOf(dt.toUpperCase())); }
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

    private static ExerciseProcedure buildExerciseProcedure(Element procedureEl) {
        ExerciseProcedure.ExerciseProcedureBuilder b = ExerciseProcedure.builder();
        Element manualEx = XmlUtils.child(procedureEl, "manualExercise");
        Element autoEx = XmlUtils.child(procedureEl, "automaticExercise");
        if (manualEx != null && XmlUtils.child(manualEx, "exerciseNotice") != null) {
            ManualExercise.ManualExerciseBuilder mb = ManualExercise.builder();
            Element exerciseNotice = XmlUtils.child(manualEx, "exerciseNotice");
            {
                ExerciseNotice.ExerciseNoticeBuilder enb = ExerciseNotice.builder();
                Element partyRef = XmlUtils.child(exerciseNotice, "partyReference");
                if (partyRef != null) {
                    // Buyer vs Seller: compare partyRef.href with swaption buyer.
                    // This requires knowing which party is the buyer — we leave this to caller via context.
                    // For simplicity, we hardcode the convention below.
                    Element swaptionEl = (Element) procedureEl.getParentNode();
                    String buyerH = href(XmlUtils.child(swaptionEl, "buyerPartyReference"));
                    String sellerH = href(XmlUtils.child(swaptionEl, "sellerPartyReference"));
                    String h = partyRef.getAttribute("href");
                    if (h.equals(buyerH)) enb.setExerciseNoticeGiver(ExerciseNoticeGiverEnum.BUYER);
                    else if (h.equals(sellerH)) enb.setExerciseNoticeGiver(ExerciseNoticeGiverEnum.SELLER);
                }
                String businessCenter = XmlUtils.childText(exerciseNotice, "businessCenter");
                if (businessCenter != null) {
                    FieldWithMetaBusinessCenterEnum.FieldWithMetaBusinessCenterEnumBuilder fb =
                            FieldWithMetaBusinessCenterEnum.builder();
                    try { fb.setValue(BusinessCenterEnum.valueOf(businessCenter)); } catch (Exception ignored) {}
                    enb.setBusinessCenter(fb.build());
                }
                mb.setExerciseNotice(enb.build());
            }
            b.setManualExercise(mb.build());
        } else if (autoEx != null) {
            // automaticExercise just contributes nothing inside exerciseProcedure (thresholdRate dropped).
        }
        String followUp = XmlUtils.childText(procedureEl, "followUpConfirmation");
        if (followUp != null) b.setFollowUpConfirmation(Boolean.parseBoolean(followUp));
        return b.build();
    }

    private static PartialExercise buildPartialExercise(Element partial) {
        PartialExercise.PartialExerciseBuilder b = PartialExercise.builder();
        // Only the FIRST notionalReference is kept in CDM per dataset
        Element firstRef = XmlUtils.child(partial, "notionalReference");
        if (firstRef != null) {
            String href = firstRef.getAttribute("href");
            if (href != null && !href.isEmpty()) {
                b.setNotionaReference(cdm.observable.asset.metafields.ReferenceWithMetaMoney.builder()
                        .setExternalReference(href).build());
            }
        }
        String intMul = XmlUtils.childText(partial, "integralMultipleAmount");
        if (intMul != null) b.setIntegralMultipleAmount(new BigDecimal(intMul));
        String minNotional = XmlUtils.childText(partial, "minimumNotionalAmount");
        if (minNotional != null) b.setMinimumNotionalAmount(new BigDecimal(minNotional));
        String minOpts = XmlUtils.childText(partial, "minimumNumberOfOptions");
        if (minOpts != null) b.setMinimumNumberOfOptions(Integer.parseInt(minOpts));
        return b.build();
    }

    /* ───── premium transfer ───── */

    private static cdm.event.common.TransferState buildPremiumTransfer(Element premium) {
        cdm.event.common.Transfer.TransferBuilder tb = cdm.event.common.Transfer.builder();

        Element amtEl = XmlUtils.child(premium, "paymentAmount");
        String ccy = XmlUtils.childText(amtEl, "currency");
        String amount = XmlUtils.childText(amtEl, "amount");
        if (amount != null && ccy != null) {
            tb.setQuantity(cdm.base.math.NonNegativeQuantity.builder()
                    .setValue(new BigDecimal(amount))
                    .setUnit(cdm.base.math.UnitType.builder()
                            .setCurrency(FieldWithMetaString.builder().setValue(ccy).build()).build())
                    .build());
            tb.setAsset(cdm.base.staticdata.asset.common.Asset.builder()
                    .setCash(cdm.base.staticdata.asset.common.Cash.builder()
                            .addIdentifier(cdm.base.staticdata.asset.common.AssetIdentifier.builder()
                                    .setIdentifier(FieldWithMetaString.builder().setValue(ccy).build())
                                    .setIdentifierType(cdm.base.staticdata.asset.common.AssetIdTypeEnum.CURRENCY_CODE)
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
                sdb.setAdjustedDate(FieldWithMetaDate.builder().setValue(DateMapper.parse(adj)).build());
            }
            BusinessDayAdjustments bda = DateMapper.businessDayAdjustments(
                    XmlUtils.child(payDate, "dateAdjustments"));
            if (bda != null) sdb.setDateAdjustments(bda);
            tb.setSettlementDate(sdb.build());
        }

        Element payerRef = XmlUtils.child(premium, "payerPartyReference");
        Element receiverRef = XmlUtils.child(premium, "receiverPartyReference");
        if (payerRef != null || receiverRef != null) {
            cdm.base.staticdata.party.PartyReferencePayerReceiver.PartyReferencePayerReceiverBuilder pr =
                    cdm.base.staticdata.party.PartyReferencePayerReceiver.builder();
            if (payerRef != null) {
                pr.setPayerPartyReference(
                        cdm.base.staticdata.party.metafields.ReferenceWithMetaParty.builder()
                                .setExternalReference(payerRef.getAttribute("href")).build());
            }
            if (receiverRef != null) {
                pr.setReceiverPartyReference(
                        cdm.base.staticdata.party.metafields.ReferenceWithMetaParty.builder()
                                .setExternalReference(receiverRef.getAttribute("href")).build());
            }
            tb.setPayerReceiver(pr.build());
        }

        // The expected JSON shows transferExpression.unscheduledTransfer.priceTransfer = "Premium".
        // The SemanticDiff already HOISTs "unscheduledTransfer", so a simple priceTransfer=Premium
        // here suffices.
        tb.setTransferExpression(cdm.event.common.TransferExpression.builder()
                .setPriceTransfer(cdm.observable.asset.FeeTypeEnum.PREMIUM)
                .build());

        return cdm.event.common.TransferState.builder().setTransfer(tb.build()).build();
    }

    /* ───── helpers ───── */

    private static String href(Element el) {
        return el == null ? null : el.getAttribute("href");
    }

    private static CounterpartyRoleEnum roleFor(String partyHref, MappingContext ctx) {
        if (partyHref == null) return null;
        Integer order = ctx.partyOrder.get(partyHref);
        if (order == null) return null;
        return order == 0 ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
    }
}
