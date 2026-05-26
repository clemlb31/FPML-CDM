package io.fpmlcdm.products;

import cdm.base.datetime.*;
import cdm.base.datetime.metafields.FieldWithMetaBusinessCenterEnum;
import cdm.base.math.*;
import cdm.base.math.metafields.FieldWithMetaNonNegativeQuantitySchedule;
import cdm.base.math.metafields.ReferenceWithMetaNonNegativeQuantitySchedule;
import cdm.base.staticdata.asset.common.*;
import cdm.base.staticdata.asset.rates.metafields.FieldWithMetaFloatingRateIndexEnum;
import cdm.base.staticdata.party.*;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.event.common.*;
import cdm.observable.asset.*;
import cdm.observable.asset.metafields.*;
import cdm.observable.common.DeterminationMethodEnum;
import cdm.observable.common.TimeTypeEnum;
import cdm.product.asset.*;
import cdm.product.common.schedule.*;
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
 * Maps FpML {@code <returnSwap>} and {@code <equitySwapTransactionSupplement>}
 * into CDM TradeState with PerformancePayout + InterestRatePayout.
 */
public class ReturnSwapMapper implements ProductMapper {

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);

        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        parties = SwapMapper.applyPartyTradeInformation(parties, tradeHeader);

        // Find product element
        Element product = XmlUtils.child(trade, "returnSwap");
        if (product == null) product = XmlUtils.child(trade, "equitySwapTransactionSupplement");

        Element returnLeg = XmlUtils.child(product, "returnLeg");
        Element interestLeg = XmlUtils.child(product, "interestLeg");

        // Assign counterparty roles: PARTY_1 = payer of returnLeg
        Element payerRef = XmlUtils.child(returnLeg, "payerPartyReference");
        if (payerRef != null) {
            String payerHref = payerRef.getAttribute("href");
            assignRoles(payerHref, ctx);
        }

        // Build economic terms
        EconomicTerms.EconomicTermsBuilder econ = EconomicTerms.builder();

        // Effective date from returnLeg
        Element effectiveDate = XmlUtils.child(returnLeg, "effectiveDate");
        if (effectiveDate != null) {
            AdjustableOrRelativeDate aord = buildDateWithId(effectiveDate);
            if (aord != null) econ.setEffectiveDate(aord);
        }

        // Termination date from returnLeg
        Element terminationDate = XmlUtils.child(returnLeg, "terminationDate");
        if (terminationDate != null) {
            AdjustableOrRelativeDate aord = buildDateWithId(terminationDate);
            if (aord != null) econ.setTerminationDate(aord);
        }

        // Build payouts (interest leg first if present, then return leg — order varies by file)
        // CDM reference order: InterestRatePayout first for equitySwapTransactionSupplement,
        // PerformancePayout first for returnSwap
        boolean isEqSwapSupplement = XmlUtils.child(trade, "equitySwapTransactionSupplement") != null;

        Payout perfPayout = buildPerformancePayout(returnLeg, ctx);
        // Use quantityReference when interest leg has no own notional amount (uses relativeNotionalAmount or none)
        // AND the return-leg notional has an id we can reference
        Element notionalForRef = XmlUtils.child(returnLeg, "notional");
        String notionalIdForRef = notionalForRef != null ? notionalForRef.getAttribute("id") : null;
        Element notionalAmtForRef = notionalForRef != null ? XmlUtils.child(notionalForRef, "notionalAmount") : null;
        String notionalAmtIdForRef = notionalAmtForRef != null ? notionalAmtForRef.getAttribute("id") : null;
        boolean equityNotionalHasId = (notionalIdForRef != null && !notionalIdForRef.isEmpty())
                || (notionalAmtIdForRef != null && !notionalAmtIdForRef.isEmpty());

        boolean interestLegHasOwnNotionalAmount = false;
        if (interestLeg != null) {
            Element intNotional = XmlUtils.child(interestLeg, "notional");
            if (intNotional != null) {
                Element intNotionalAmount = XmlUtils.child(intNotional, "notionalAmount");
                if (intNotionalAmount != null) {
                    String intAmt = XmlUtils.childText(intNotionalAmount, "amount");
                    interestLegHasOwnNotionalAmount = intAmt != null;
                }
            }
        }
        boolean useQtyRef = equityNotionalHasId && !interestLegHasOwnNotionalAmount;
        Payout irPayout = interestLeg != null ? buildInterestRatePayout(interestLeg, returnLeg, ctx, useQtyRef) : null;

        if (isEqSwapSupplement && irPayout != null) {
            econ.addPayout(irPayout);
            econ.addPayout(perfPayout);
        } else {
            econ.addPayout(perfPayout);
            if (irPayout != null) econ.addPayout(irPayout);
        }

        // Calculation agent
        CalculationAgentMapper.Result ca = CalculationAgentMapper.map(trade);
        if (ca.calculationAgent() != null) econ.setCalculationAgent(ca.calculationAgent());

        // Taxonomy
        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder()
                .setEconomicTerms(econ.build());
        buildTaxonomy(product, returnLeg).forEach(ntp::addTaxonomy);
        ProductIdentifierMapper.map(product).forEach(ntp::addIdentifier);

        // TradeLot
        List<PriceQuantity> priceQuantities = buildTradeLotPriceQuantities(returnLeg, interestLeg);
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

        List<PartyRole> partyRoles = PartyRoleMapper.map(trade);

        // notionalAdjustments → trade.adjustment
        String notionalAdjustments = XmlUtils.childText(returnLeg, "notionalAdjustments");

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

        // notionalAdjustments → adjustment (Execution, Standard, etc.)
        if (notionalAdjustments != null) {
            try {
                t.setAdjustment(cdm.product.common.NotionalAdjustmentEnum.valueOf(
                        notionalAdjustments.toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                try {
                    t.setAdjustment(cdm.product.common.NotionalAdjustmentEnum.fromDisplayName(
                            notionalAdjustments));
                } catch (Exception ignored2) {}
            }
        }

        TradeState.TradeStateBuilder tsBuilder = TradeState.builder().setTrade(t.build());

        // otherPartyPayment at trade level
        for (TransferState ts : TransferMapper.map(trade, null)) {
            tsBuilder.addTransferHistory(ts);
        }

        return tsBuilder.build();
    }

    private AdjustableOrRelativeDate buildDateWithId(Element dateEl) {
        if (dateEl == null) return null;
        String id = dateEl.getAttribute("id");
        Element adjDate = XmlUtils.child(dateEl, "adjustableDate");
        Element relDate = XmlUtils.child(dateEl, "relativeDate");

        if (adjDate != null) {
            AdjustableDate adj = DateMapper.adjustable(adjDate);
            AdjustableOrRelativeDate.AdjustableOrRelativeDateBuilder b =
                    AdjustableOrRelativeDate.builder().setAdjustableDate(adj);
            if (id != null && !id.isEmpty()) {
                b.setMeta(MetaFields.builder().setExternalKey(id).build());
            }
            return b.build();
        }

        if (relDate != null) {
            AdjustedRelativeDateOffset.AdjustedRelativeDateOffsetBuilder rdb =
                    AdjustedRelativeDateOffset.builder();
            String pm = XmlUtils.childText(relDate, "periodMultiplier");
            if (pm != null) rdb.setPeriodMultiplier(Integer.parseInt(pm));
            String period = XmlUtils.childText(relDate, "period");
            if (period != null) rdb.setPeriod(EnumMappers.period(period));
            String dayType = XmlUtils.childText(relDate, "dayType");
            if (dayType != null) rdb.setDayType(EquityOptionMapper.mapDayType(dayType));
            String bdc = XmlUtils.childText(relDate, "businessDayConvention");
            if (bdc != null) rdb.setBusinessDayConvention(EnumMappers.bdc(bdc));
            Element drt = XmlUtils.child(relDate, "dateRelativeTo");
            if (drt != null) {
                rdb.setDateRelativeTo(ReferenceWithMetaDate.builder()
                        .setExternalReference(drt.getAttribute("href")).build());
            }
            Element bcs = XmlUtils.child(relDate, "businessCenters");
            if (bcs != null) rdb.setBusinessCenters(DateMapper.buildBusinessCenters(bcs));
            Element bcsRef = XmlUtils.child(relDate, "businessCentersReference");
            if (bcsRef != null) {
                rdb.setBusinessCentersReference(
                        cdm.base.datetime.metafields.ReferenceWithMetaBusinessCenters.builder()
                                .setExternalReference(bcsRef.getAttribute("href")).build());
            }

            AdjustableOrRelativeDate.AdjustableOrRelativeDateBuilder b =
                    AdjustableOrRelativeDate.builder().setRelativeDate(rdb.build());
            if (id != null && !id.isEmpty()) {
                b.setMeta(MetaFields.builder().setExternalKey(id).build());
            }
            return b.build();
        }
        return null;
    }

    private Payout buildPerformancePayout(Element returnLeg, MappingContext ctx) {
        PerformancePayout.PerformancePayoutBuilder ppb = PerformancePayout.builder();

        // Payer/receiver
        Element payerRef = XmlUtils.child(returnLeg, "payerPartyReference");
        Element receiverRef = XmlUtils.child(returnLeg, "receiverPartyReference");
        ppb.setPayerReceiver(buildPayerReceiver(payerRef, receiverRef, ctx));

        // PriceQuantity reference
        Element notional = XmlUtils.child(returnLeg, "notional");
        String notionalId = null;
        if (notional != null) {
            String v = notional.getAttribute("id");
            if (v != null && !v.isEmpty()) notionalId = v;
        }
        String notionalAmtId = null;
        Element notionalAmount = notional != null ? XmlUtils.child(notional, "notionalAmount") : null;
        if (notionalAmount != null) {
            String v = notionalAmount.getAttribute("id");
            if (v != null && !v.isEmpty()) notionalAmtId = v;
        }

        // notionalReset
        Element rateOfReturn = XmlUtils.child(returnLeg, "rateOfReturn");
        Boolean notionalReset = null;
        if (rateOfReturn != null) {
            String notReset = XmlUtils.childText(rateOfReturn, "notionalReset");
            if (notReset != null) notionalReset = "true".equals(notReset);
        }

        // Check if we should use quantityReference or quantitySchedule address
        String effectiveNotionalId = notionalAmtId != null ? notionalAmtId : notionalId;

        ResolvablePriceQuantity.ResolvablePriceQuantityBuilder rpq = ResolvablePriceQuantity.builder();
        rpq.setQuantitySchedule(ReferenceWithMetaNonNegativeQuantitySchedule.builder()
                .setReference(Reference.builder().setScope("DOCUMENT").setReference("quantity-1").build())
                .build());
        // Emit reset whenever notionalReset is explicitly specified (true OR false)
        if (notionalReset != null) rpq.setReset(notionalReset);
        // Emit externalKey only when notionalAmount (the inner element) carries the id.
        // The outer <notional id="..."> is used for cross-references, not the priceQuantity meta.
        if (notionalAmtId != null) {
            rpq.setMeta(MetaFields.builder().setExternalKey(notionalAmtId).build());
        }
        ppb.setPriceQuantity(rpq.build());

        // Valuation dates
        if (rateOfReturn != null) {
            ppb.setValuationDates(buildValuationDates(rateOfReturn));
        }

        // Payment dates
        if (rateOfReturn != null) {
            Element paymentDates = XmlUtils.child(rateOfReturn, "paymentDates");
            if (paymentDates != null) {
                ppb.setPaymentDates(buildEquityPaymentDates(paymentDates));
            }
        }

        // Settlement terms - either from explicit <settlementType> or from <amount> block with cashSettlement.
        // For the <amount>+cashSettlement form, only emit when interestLeg uses relativeNotionalAmount AND
        // dividendConditions is present (matches reference CDM output convention).
        String settlementType = XmlUtils.childText(returnLeg, "settlementType");
        Element amountEl = XmlUtils.child(returnLeg, "amount");
        boolean cashSettlement = false;
        String settlementCcy = null;
        Element settlementCcyEl = null;
        if (amountEl != null) {
            String cs = XmlUtils.childText(amountEl, "cashSettlement");
            cashSettlement = "true".equalsIgnoreCase(cs);
            settlementCcyEl = XmlUtils.child(amountEl, "currency");
            if (settlementCcyEl != null) settlementCcy = settlementCcyEl.getTextContent().trim();
        }

        if (settlementType != null) {
            SettlementTerms.SettlementTermsBuilder stb = SettlementTerms.builder();
            if ("Cash".equals(settlementType)) stb.setSettlementType(SettlementTypeEnum.CASH);
            else if ("Physical".equals(settlementType)) stb.setSettlementType(SettlementTypeEnum.PHYSICAL);
            else if ("Election".equals(settlementType)) stb.setSettlementType(SettlementTypeEnum.ELECTION);
            ppb.setSettlementTerms(stb.build());
        } else if (cashSettlement && shouldEmitSettlementTermsFromAmount(returnLeg)) {
            SettlementTerms.SettlementTermsBuilder stb = SettlementTerms.builder()
                    .setSettlementType(SettlementTypeEnum.CASH);
            if (settlementCcy != null && !settlementCcy.isEmpty()) {
                FieldWithMetaString.FieldWithMetaStringBuilder ccyB = FieldWithMetaString.builder().setValue(settlementCcy);
                // Carry scheme if present
                String scheme = settlementCcyEl != null ? settlementCcyEl.getAttribute("currencyScheme") : null;
                if (scheme != null && !scheme.isEmpty()) {
                    ccyB.setMeta(MetaFields.builder().setScheme(scheme).build());
                }
                stb.setSettlementCurrency(ccyB.build());
            }
            ppb.setSettlementTerms(stb.build());
        }

        // Underlier
        ppb.setUnderlier(Underlier.builder()
                .setObservable(ReferenceWithMetaObservable.builder()
                        .setReference(Reference.builder().setScope("DOCUMENT").setReference("observable-1").build())
                        .build())
                .build());

        // fxFeature: parse <fxFeature> with referenceCurrency, quanto, composite, crossCurrency
        Element fxFeatureEl = XmlUtils.child(returnLeg, "fxFeature");
        if (fxFeatureEl != null) {
            FxFeature feature = buildFxFeature(fxFeatureEl);
            if (feature != null) ppb.addFxFeature(feature);
        }

        // ReturnTerms
        Element returnEl = XmlUtils.child(returnLeg, "return");
        if (returnEl != null) {
            ppb.setReturnTerms(buildReturnTerms(returnEl, returnLeg));
        }

        return Payout.builder().setPerformancePayout(ppb.build()).build();
    }

    /**
     * Heuristic to decide whether to emit settlementTerms from a returnLeg/amount/cashSettlement block.
     * Reference CDM output only includes settlementTerms when the interest leg uses relativeNotionalAmount
     * AND either dividendConditions is present on the return OR the amount block uses a currencyReference
     * (rather than a literal currency).
     */
    private boolean shouldEmitSettlementTermsFromAmount(Element returnLeg) {
        Element product = (Element) returnLeg.getParentNode();
        Element interestLeg = product != null ? XmlUtils.child(product, "interestLeg") : null;
        if (interestLeg == null) return false;
        Element intNotional = XmlUtils.child(interestLeg, "notional");
        if (intNotional == null) return false;
        Element rel = XmlUtils.child(intNotional, "relativeNotionalAmount");
        if (rel == null) return false;
        Element ret = XmlUtils.child(returnLeg, "return");
        if (ret != null && XmlUtils.child(ret, "dividendConditions") != null) return true;
        // currencyReference inside the amount block also triggers emission
        Element amt = XmlUtils.child(returnLeg, "amount");
        return amt != null && XmlUtils.child(amt, "currencyReference") != null;
    }

    private FxFeature buildFxFeature(Element fxFeatureEl) {
        FxFeature.FxFeatureBuilder fb = FxFeature.builder();
        Element refCcy = XmlUtils.child(fxFeatureEl, "referenceCurrency");
        if (refCcy != null) {
            String id = refCcy.getAttribute("id");
            FieldWithMetaString.FieldWithMetaStringBuilder rc = FieldWithMetaString.builder()
                    .setValue(refCcy.getTextContent().trim());
            if (id != null && !id.isEmpty()) {
                rc.setMeta(MetaFields.builder().setExternalKey(id).build());
            }
            fb.setReferenceCurrency(rc.build());
        }

        Element quanto = XmlUtils.child(fxFeatureEl, "quanto");
        if (quanto != null) {
            Quanto.QuantoBuilder qb = Quanto.builder();
            for (Element fxRateEl : XmlUtils.children(quanto, "fxRate")) {
                cdm.observable.asset.FxRate.FxRateBuilder frb = cdm.observable.asset.FxRate.builder();
                Element qcp = XmlUtils.child(fxRateEl, "quotedCurrencyPair");
                if (qcp != null) {
                    cdm.observable.asset.QuotedCurrencyPair.QuotedCurrencyPairBuilder qcpb =
                            cdm.observable.asset.QuotedCurrencyPair.builder();
                    String c1 = XmlUtils.childText(qcp, "currency1");
                    String c2 = XmlUtils.childText(qcp, "currency2");
                    String qb2 = XmlUtils.childText(qcp, "quoteBasis");
                    if (c1 != null) qcpb.setCurrency1(FieldWithMetaString.builder().setValue(c1).build());
                    if (c2 != null) qcpb.setCurrency2(FieldWithMetaString.builder().setValue(c2).build());
                    if (qb2 != null) {
                        try {
                            qcpb.setQuoteBasis(cdm.observable.asset.QuoteBasisEnum.valueOf(
                                    qb2.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase()));
                        } catch (Exception ignored) {
                            try { qcpb.setQuoteBasis(cdm.observable.asset.QuoteBasisEnum.fromDisplayName(qb2)); }
                            catch (Exception ignored2) {}
                        }
                    }
                    frb.setQuotedCurrencyPair(qcpb.build());
                }
                String rate = XmlUtils.childText(fxRateEl, "rate");
                if (rate != null) frb.setRate(new BigDecimal(rate));
                qb.addFxRate(frb.build());
            }
            fb.setQuanto(qb.build());
        }

        Element composite = XmlUtils.child(fxFeatureEl, "composite");
        if (composite != null) {
            Composite.CompositeBuilder cb = Composite.builder();
            String dm = XmlUtils.childText(composite, "determinationMethod");
            if (dm != null) cb.setDeterminationMethod(mapDeterminationMethod(dm));
            fb.setComposite(cb.build());
        }

        Element crossCcy = XmlUtils.child(fxFeatureEl, "crossCurrency");
        // crossCurrency is empty in FpML; not represented in CDM output (per reference data)
        // Only emit feature when there's at least referenceCurrency or quanto or composite
        if (refCcy != null || quanto != null || composite != null || crossCcy != null) {
            return fb.build();
        }
        return null;
    }

    private ReturnTerms buildReturnTerms(Element returnEl, Element returnLeg) {
        ReturnTerms.ReturnTermsBuilder rtb = ReturnTerms.builder();

        String returnType = XmlUtils.childText(returnEl, "returnType");
        if (returnType != null) {
            ReturnTypeEnum rte = "Total".equals(returnType) ? ReturnTypeEnum.TOTAL : ReturnTypeEnum.PRICE;
            rtb.setPriceReturnTerms(PriceReturnTerms.builder().setReturnType(rte).build());
        }

        Element dividendConditions = XmlUtils.child(returnEl, "dividendConditions");
        if (dividendConditions != null) {
            rtb.setDividendReturnTerms(buildDividendReturnTerms(dividendConditions, returnLeg));
        }

        return rtb.build();
    }

    private DividendReturnTerms buildDividendReturnTerms(Element divCond, Element returnLeg) {
        DividendReturnTerms.DividendReturnTermsBuilder drt = DividendReturnTerms.builder();

        // Dividend payout ratio from underlyer
        Element underlyer = XmlUtils.child(returnLeg, "underlyer");
        BigDecimal totalRatio = null;
        if (underlyer != null) {
            Element singleUnderlyer = XmlUtils.child(underlyer, "singleUnderlyer");
            if (singleUnderlyer != null) {
                Element dividendPayout = XmlUtils.child(singleUnderlyer, "dividendPayout");
                if (dividendPayout != null) {
                    String ratio = XmlUtils.childText(dividendPayout, "dividendPayoutRatio");
                    if (ratio != null) {
                        totalRatio = new BigDecimal(ratio);
                    }
                }
            }
        }

        // declaredCashDividendPercentage from dividendConditions → cashRatio
        String cashPct = XmlUtils.childText(divCond, "declaredCashDividendPercentage");
        String nonCashPct = XmlUtils.childText(divCond, "declaredCashEquivalentDividendPercentage");

        if (totalRatio != null || cashPct != null || nonCashPct != null) {
            DividendPayoutRatio.DividendPayoutRatioBuilder dprb = DividendPayoutRatio.builder();
            if (totalRatio != null) dprb.setTotalRatio(totalRatio);
            if (cashPct != null) dprb.setCashRatio(new BigDecimal(cashPct));
            if (nonCashPct != null) dprb.setNonCashRatio(new BigDecimal(nonCashPct));
            drt.addDividendPayoutRatio(dprb.build());
        }

        // dividendReinvestment
        String reinvest = XmlUtils.childText(divCond, "dividendReinvestment");
        if (reinvest != null) drt.setDividendReinvestment(Boolean.parseBoolean(reinvest));

        // dividendEntitlement
        String entitlement = XmlUtils.childText(divCond, "dividendEntitlement");
        if (entitlement != null) {
            if ("ExDate".equals(entitlement)) drt.setDividendEntitlement(DividendEntitlementEnum.EX_DATE);
            else if ("RecordDate".equals(entitlement)) drt.setDividendEntitlement(DividendEntitlementEnum.RECORD_DATE);
        }

        // excessDividendAmount (only the FpML <excessDividendAmount>; <dividendAmount> is informational
        // and not represented in the CDM dividendReturnTerms model).
        String excessDiv = XmlUtils.childText(divCond, "excessDividendAmount");
        if (excessDiv != null) {
            drt.setExcessDividendAmount(mapDividendAmountType(excessDiv));
        }

        // dividendCurrency: determinationMethod, currency value, or currency/currencyReference href
        String detMethod = XmlUtils.childText(divCond, "determinationMethod");
        Element divCcyEl = XmlUtils.child(divCond, "currency");
        Element divCcyRefEl = XmlUtils.child(divCond, "currencyReference");
        if (detMethod != null || divCcyEl != null || divCcyRefEl != null) {
            DividendCurrency.DividendCurrencyBuilder dcb = DividendCurrency.builder();
            if (detMethod != null) dcb.setDeterminationMethod(mapDeterminationMethod(detMethod));
            if (divCcyRefEl != null) {
                String href = divCcyRefEl.getAttribute("href");
                if (href != null && !href.isEmpty()) {
                    dcb.setCurrencyReference(com.rosetta.model.metafields.ReferenceWithMetaString.builder()
                            .setExternalReference(href).build());
                }
            } else if (divCcyEl != null) {
                String href = divCcyEl.getAttribute("href");
                if (href != null && !href.isEmpty()) {
                    dcb.setCurrencyReference(com.rosetta.model.metafields.ReferenceWithMetaString.builder()
                            .setExternalReference(href).build());
                } else {
                    String ccyVal = divCcyEl.getTextContent().trim();
                    if (!ccyVal.isEmpty()) {
                        dcb.setCurrency(FieldWithMetaString.builder().setValue(ccyVal).build());
                    }
                }
            }
            drt.setDividendCurrency(dcb.build());
        }

        // firstOrSecondPeriod: <dividendPeriod>FirstPeriod</dividendPeriod> (value, not the nested struct)
        String firstOrSecond = XmlUtils.childText(divCond, "dividendPeriod");
        if (firstOrSecond != null) {
            try {
                if ("FirstPeriod".equals(firstOrSecond)) drt.setFirstOrSecondPeriod(DividendPeriodEnum.FIRST_PERIOD);
                else if ("SecondPeriod".equals(firstOrSecond)) drt.setFirstOrSecondPeriod(DividendPeriodEnum.SECOND_PERIOD);
            } catch (Exception ignored) {}
        }

        // Dividend period (structured)
        Element divPeriodEffDate = XmlUtils.child(divCond, "dividendPeriodEffectiveDate");
        Element divPeriodEndDate = XmlUtils.child(divCond, "dividendPeriodEndDate");
        Element divPaymentDate = XmlUtils.child(divCond, "dividendPaymentDate");

        if (divPeriodEffDate != null || divPeriodEndDate != null || divPaymentDate != null) {
            DividendPeriod.DividendPeriodBuilder dpb = DividendPeriod.builder();
            boolean any = false;

            if (divPeriodEffDate != null) {
                String href = divPeriodEffDate.getAttribute("href");
                if (href != null && !href.isEmpty()) {
                    dpb.setStartDate(DividendPaymentDate.builder()
                            .setDividendDate(
                                    cdm.base.datetime.metafields.ReferenceWithMetaAdjustableOrRelativeDate.builder()
                                            .setExternalReference(href)
                                            .build())
                            .build());
                    any = true;
                }
            }

            if (divPeriodEndDate != null) {
                String href = divPeriodEndDate.getAttribute("href");
                if (href != null && !href.isEmpty()) {
                    dpb.setEndDate(DividendPaymentDate.builder()
                            .setDividendDate(
                                    cdm.base.datetime.metafields.ReferenceWithMetaAdjustableOrRelativeDate.builder()
                                            .setExternalReference(href)
                                            .build())
                            .build());
                    any = true;
                }
            }

            if (divPaymentDate != null) {
                String divDateRef = XmlUtils.childText(divPaymentDate, "dividendDateReference");
                if (divDateRef != null) {
                    DividendDateReference.DividendDateReferenceBuilder ddrb = DividendDateReference.builder()
                            .setDateReference(mapDividendDateReference(divDateRef));
                    // paymentDateOffset
                    Element pdo = XmlUtils.child(divPaymentDate, "paymentDateOffset");
                    if (pdo != null) {
                        cdm.base.datetime.Offset.OffsetBuilder ob = cdm.base.datetime.Offset.builder();
                        String pm = XmlUtils.childText(pdo, "periodMultiplier");
                        String period = XmlUtils.childText(pdo, "period");
                        String dayType = XmlUtils.childText(pdo, "dayType");
                        if (pm != null) ob.setPeriodMultiplier(Integer.parseInt(pm));
                        if (period != null) ob.setPeriod(EnumMappers.period(period));
                        if (dayType != null) ob.setDayType(EquityOptionMapper.mapDayType(dayType));
                        ddrb.setPaymentDateOffset(ob.build());
                    }
                    dpb.setDividendPaymentDate(DividendPaymentDate.builder()
                            .setDividendDateReference(ddrb.build())
                            .build());
                    any = true;
                }
            }

            if (any) drt.addDividendPeriod(dpb.build());
        }

        return drt.build();
    }

    private DividendAmountTypeEnum mapDividendAmountType(String text) {
        if (text == null) return null;
        return switch (text) {
            case "RecordAmount" -> DividendAmountTypeEnum.RECORD_AMOUNT;
            case "ExAmount" -> DividendAmountTypeEnum.EX_AMOUNT;
            case "PaidAmount" -> DividendAmountTypeEnum.PAID_AMOUNT;
            case "AsSpecifiedInMasterConfirmation" -> DividendAmountTypeEnum.AS_SPECIFIED_IN_MASTER_CONFIRMATION;
            default -> null;
        };
    }

    private DeterminationMethodEnum mapDeterminationMethod(String text) {
        if (text == null) return null;
        return switch (text) {
            case "DividendCurrency" -> DeterminationMethodEnum.DIVIDEND_CURRENCY;
            case "ValuationTime" -> DeterminationMethodEnum.VALUATION_TIME;
            case "HedgeExecution" -> DeterminationMethodEnum.HEDGE_EXECUTION;
            case "ClosingPrice" -> DeterminationMethodEnum.CLOSING_PRICE;
            case "OSPPrice" -> DeterminationMethodEnum.OSP_PRICE;
            case "CalculationAgent" -> DeterminationMethodEnum.CALCULATION_AGENT;
            case "SettlementCurrency" -> DeterminationMethodEnum.SETTLEMENT_CURRENCY;
            case "IssuerPaymentCurrency" -> DeterminationMethodEnum.ISSUER_PAYMENT_CURRENCY;
            case "AgreedInitialPrice" -> DeterminationMethodEnum.AGREED_INITIAL_PRICE;
            case "AsSpecifiedInMasterConfirmation" -> DeterminationMethodEnum.AS_SPECIFIED_IN_MASTER_CONFIRMATION;
            default -> {
                try { yield DeterminationMethodEnum.valueOf(text.toUpperCase().replace(" ", "_")); }
                catch (IllegalArgumentException ignored) { yield null; }
            }
        };
    }

    private DividendDateReferenceEnum mapDividendDateReference(String text) {
        if (text == null) return null;
        return switch (text) {
            case "EquityPaymentDate" -> DividendDateReferenceEnum.EQUITY_PAYMENT_DATE;
            case "DividendPaymentDate" -> DividendDateReferenceEnum.DIVIDEND_PAYMENT_DATE;
            case "DividendValuationDate" -> DividendDateReferenceEnum.DIVIDEND_VALUATION_DATE;
            case "FloatingAmountPaymentDate" -> DividendDateReferenceEnum.FLOATING_AMOUNT_PAYMENT_DATE;
            case "CashSettlementPaymentDate" -> DividendDateReferenceEnum.CASH_SETTLEMENT_PAYMENT_DATE;
            case "AdHocDate" -> DividendDateReferenceEnum.AD_HOC_DATE;
            case "ExDate" -> DividendDateReferenceEnum.EX_DATE;
            case "SharePayment" -> DividendDateReferenceEnum.SHARE_PAYMENT;
            case "TerminationDate" -> DividendDateReferenceEnum.TERMINATION_DATE;
            case "TradeDate" -> DividendDateReferenceEnum.TRADE_DATE;
            case "FollowingPaymentDate" -> DividendDateReferenceEnum.FOLLOWING_PAYMENT_DATE;
            case "RecordDate" -> DividendDateReferenceEnum.RECORD_DATE;
            default -> {
                try { yield DividendDateReferenceEnum.valueOf(
                        text.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase()); }
                catch (Exception ignored) {
                    try { yield DividendDateReferenceEnum.fromDisplayName(text); }
                    catch (Exception ignored2) { yield null; }
                }
            }
        };
    }

    private ValuationDates buildValuationDates(Element rateOfReturn) {
        ValuationDates.ValuationDatesBuilder vdb = ValuationDates.builder();

        // Interim valuation
        Element vpInterim = XmlUtils.child(rateOfReturn, "valuationPriceInterim");
        if (vpInterim != null) {
            vdb.setInterimValuationDate(buildPerformanceValuationDates(vpInterim));
        }

        // Final valuation
        Element vpFinal = XmlUtils.child(rateOfReturn, "valuationPriceFinal");
        if (vpFinal != null) {
            vdb.setFinalValuationDate(buildPerformanceValuationDates(vpFinal));
        }

        return vdb.build();
    }

    private PerformanceValuationDates buildPerformanceValuationDates(Element vp) {
        PerformanceValuationDates.PerformanceValuationDatesBuilder pvdb =
                PerformanceValuationDates.builder();

        String detMethod = XmlUtils.childText(vp, "determinationMethod");
        if (detMethod != null) pvdb.setDeterminationMethod(mapDeterminationMethod(detMethod));

        Element valuationRules = XmlUtils.child(vp, "valuationRules");
        if (valuationRules != null) {
            // Multiple dates
            Element valuationDates = XmlUtils.child(valuationRules, "valuationDates");
            if (valuationDates != null) {
                pvdb.setValuationDates(buildAdjustableRelativeOrPeriodicDates(valuationDates));
            }

            // Single date
            Element valuationDate = XmlUtils.child(valuationRules, "valuationDate");
            if (valuationDate != null) {
                pvdb.setValuationDate(buildDateWithId(valuationDate));
            }

            String timeType = XmlUtils.childText(valuationRules, "valuationTimeType");
            if (timeType != null) pvdb.setValuationTimeType(mapTimeType(timeType));
        }

        return pvdb.build();
    }

    private AdjustableRelativeOrPeriodicDates buildAdjustableRelativeOrPeriodicDates(Element el) {
        if (el == null) return null;
        String id = el.getAttribute("id");

        AdjustableRelativeOrPeriodicDates.AdjustableRelativeOrPeriodicDatesBuilder b =
                AdjustableRelativeOrPeriodicDates.builder();

        Element adjDates = XmlUtils.child(el, "adjustableDates");
        if (adjDates != null) {
            AdjustableDates.AdjustableDatesBuilder adb = AdjustableDates.builder();
            for (Element unadjEl : XmlUtils.children(adjDates, "unadjustedDate")) {
                String dateStr = unadjEl.getTextContent().trim();
                adb.addUnadjustedDate(DateMapper.parse(dateStr));
            }
            BusinessDayAdjustments bda = DateMapper.businessDayAdjustments(
                    XmlUtils.child(adjDates, "dateAdjustments"));
            if (bda != null) adb.setDateAdjustments(bda);
            b.setAdjustableDates(adb.build());
        }

        Element relDates = XmlUtils.child(el, "relativeDates");
        if (relDates != null) {
            b.setRelativeDates(buildRelativeDates(relDates));
        }

        Element periodicDates = XmlUtils.child(el, "periodicDates");
        if (periodicDates != null) {
            b.setPeriodicDates(buildPeriodicDates(periodicDates));
        }

        if (id != null && !id.isEmpty()) {
            b.setMeta(MetaFields.builder().setExternalKey(id).build());
        }

        return b.build();
    }

    private cdm.base.datetime.PeriodicDates buildPeriodicDates(Element el) {
        if (el == null) return null;
        cdm.base.datetime.PeriodicDates.PeriodicDatesBuilder b = cdm.base.datetime.PeriodicDates.builder();

        Element startDate = XmlUtils.child(el, "calculationStartDate");
        if (startDate != null) b.setStartDate(buildDateWithId(startDate));

        Element endDate = XmlUtils.child(el, "calculationEndDate");
        if (endDate != null) b.setEndDate(buildDateWithId(endDate));

        Element freq = XmlUtils.child(el, "calculationPeriodFrequency");
        if (freq != null) {
            cdm.base.datetime.CalculationPeriodFrequency.CalculationPeriodFrequencyBuilder fb =
                    cdm.base.datetime.CalculationPeriodFrequency.builder();
            String pm = XmlUtils.childText(freq, "periodMultiplier");
            if (pm != null) fb.setPeriodMultiplier(Integer.parseInt(pm));
            String period = XmlUtils.childText(freq, "period");
            if (period != null) {
                try { fb.setPeriod(cdm.base.datetime.PeriodExtendedEnum.valueOf(period.toUpperCase())); }
                catch (Exception ignored) {}
            }
            String rc = XmlUtils.childText(freq, "rollConvention");
            if (rc != null) {
                try {
                    String norm = rc.matches("\\d+") ? "_" + rc : rc.toUpperCase();
                    fb.setRollConvention(cdm.base.datetime.RollConventionEnum.valueOf(norm));
                } catch (Exception ignored) {
                    try { fb.setRollConvention(cdm.base.datetime.RollConventionEnum.fromDisplayName(rc)); }
                    catch (Exception ignored2) {}
                }
            }
            b.setPeriodFrequency(fb.build());
        }

        Element pda = XmlUtils.child(el, "calculationPeriodDatesAdjustments");
        if (pda != null) {
            BusinessDayAdjustments bda = DateMapper.businessDayAdjustments(pda);
            if (bda != null) b.setPeriodDatesAdjustments(bda);
        }

        return b.build();
    }

    private RelativeDates buildRelativeDates(Element el) {
        if (el == null) return null;
        RelativeDates.RelativeDatesBuilder b = RelativeDates.builder();
        String pm = XmlUtils.childText(el, "periodMultiplier");
        if (pm != null) b.setPeriodMultiplier(Integer.parseInt(pm));
        String period = XmlUtils.childText(el, "period");
        if (period != null) b.setPeriod(EnumMappers.period(period));
        String dayType = XmlUtils.childText(el, "dayType");
        if (dayType != null) b.setDayType(EquityOptionMapper.mapDayType(dayType));
        String bdc = XmlUtils.childText(el, "businessDayConvention");
        if (bdc != null) b.setBusinessDayConvention(EnumMappers.bdc(bdc));
        Element drt = XmlUtils.child(el, "dateRelativeTo");
        if (drt != null) {
            b.setDateRelativeTo(ReferenceWithMetaDate.builder()
                    .setExternalReference(drt.getAttribute("href")).build());
        }
        Element bcs = XmlUtils.child(el, "businessCenters");
        if (bcs != null) b.setBusinessCenters(DateMapper.buildBusinessCenters(bcs));
        Element bcsRef = XmlUtils.child(el, "businessCentersReference");
        if (bcsRef != null) {
            b.setBusinessCentersReference(
                    cdm.base.datetime.metafields.ReferenceWithMetaBusinessCenters.builder()
                            .setExternalReference(bcsRef.getAttribute("href")).build());
        }
        return b.build();
    }

    private TimeTypeEnum mapTimeType(String text) {
        if (text == null) return null;
        return switch (text) {
            case "Close" -> TimeTypeEnum.CLOSE;
            case "OSP" -> TimeTypeEnum.OSP;
            case "SpecificTime" -> TimeTypeEnum.SPECIFIC_TIME;
            case "XETRA" -> TimeTypeEnum.XETRA;
            case "DerivativesClose" -> TimeTypeEnum.DERIVATIVES_CLOSE;
            case "AsSpecifiedInMasterConfirmation" -> TimeTypeEnum.AS_SPECIFIED_IN_MASTER_CONFIRMATION;
            default -> null;
        };
    }

    private PaymentDates buildEquityPaymentDates(Element paymentDatesEl) {
        PaymentDates.PaymentDatesBuilder pdb = PaymentDates.builder();
        PaymentDateSchedule.PaymentDateScheduleBuilder pds = PaymentDateSchedule.builder();

        // Interim payment dates
        Element interimDates = XmlUtils.child(paymentDatesEl, "paymentDatesInterim");
        if (interimDates != null) {
            pds.addInterimPaymentDates(buildAdjustableRelativeOrPeriodicDates(interimDates));
        }

        // Final payment date
        Element finalDate = XmlUtils.child(paymentDatesEl, "paymentDateFinal");
        if (finalDate != null) {
            AdjustableOrRelativeDate aord = buildDateWithId(finalDate);
            if (aord != null) pds.setFinalPaymentDate(aord);
        }

        pdb.setPaymentDateSchedule(pds.build());
        return pdb.build();
    }

    private Payout buildInterestRatePayout(Element interestLeg, Element returnLeg, MappingContext ctx) {
        return buildInterestRatePayout(interestLeg, returnLeg, ctx, false);
    }

    private Payout buildInterestRatePayout(Element interestLeg, Element returnLeg, MappingContext ctx, boolean useQuantityReference) {
        cdm.product.asset.InterestRatePayout.InterestRatePayoutBuilder irpb =
                cdm.product.asset.InterestRatePayout.builder();

        // Payer/receiver
        Element payerRef = XmlUtils.child(interestLeg, "payerPartyReference");
        Element receiverRef = XmlUtils.child(interestLeg, "receiverPartyReference");
        irpb.setPayerReceiver(buildPayerReceiver(payerRef, receiverRef, ctx));

        // PriceQuantity - reference to equity notional
        Element notional = XmlUtils.child(returnLeg, "notional");
        String notionalId = null;
        if (notional != null) {
            String v = notional.getAttribute("id");
            if (v != null && !v.isEmpty()) notionalId = v;
        }
        String notionalAmtId = null;
        Element notionalAmount = notional != null ? XmlUtils.child(notional, "notionalAmount") : null;
        if (notionalAmount != null) {
            String v = notionalAmount.getAttribute("id");
            if (v != null && !v.isEmpty()) notionalAmtId = v;
        }
        String effectiveNotionalId = notionalAmtId != null ? notionalAmtId : notionalId;

        // Prefer the explicit relativeNotionalAmount href from the interest leg (more reliable)
        String refTarget = effectiveNotionalId;
        Element intNotionalOpt = XmlUtils.child(interestLeg, "notional");
        if (intNotionalOpt != null) {
            Element relRef = XmlUtils.child(intNotionalOpt, "relativeNotionalAmount");
            if (relRef != null) {
                String href = relRef.getAttribute("href");
                if (href != null && !href.isEmpty()) refTarget = href;
            }
        }
        if (useQuantityReference && refTarget != null) {
            // quantityReference to the equity notional (equitySwapTransactionSupplement pattern)
            ResolvablePriceQuantity rpq = ResolvablePriceQuantity.builder()
                    .setQuantityReference(
                            cdm.product.common.settlement.metafields.ReferenceWithMetaResolvablePriceQuantity.builder()
                                    .setExternalReference(refTarget)
                                    .build())
                    .build();
            irpb.setPriceQuantity(rpq);
        } else {
            // Use address reference (returnSwap pattern)
            ResolvablePriceQuantity rpq = ResolvablePriceQuantity.builder()
                    .setQuantitySchedule(
                            ReferenceWithMetaNonNegativeQuantitySchedule.builder()
                                    .setReference(Reference.builder().setScope("DOCUMENT").setReference("quantity-2").build())
                                    .build())
                    .build();
            irpb.setPriceQuantity(rpq);
        }

        // Interest calculation
        Element interestCalc = XmlUtils.child(interestLeg, "interestCalculation");
        if (interestCalc != null) {
            Element frc = XmlUtils.child(interestCalc, "floatingRateCalculation");
            if (frc != null) {
                String idxName = XmlUtils.childText(frc, "floatingRateIndex");
                cdm.product.asset.FloatingRateSpecification.FloatingRateSpecificationBuilder frsb =
                        cdm.product.asset.FloatingRateSpecification.builder();

                // Rate option reference
                frsb.setRateOption(
                        cdm.observable.asset.metafields.ReferenceWithMetaInterestRateIndex.builder()
                                .setReference(Reference.builder().setScope("DOCUMENT")
                                        .setReference("InterestRateIndex-1").build())
                                .build());

                // Spread
                Element spreadSchedule = XmlUtils.child(frc, "spreadSchedule");
                if (spreadSchedule != null) {
                    frsb.setSpreadSchedule(cdm.product.asset.SpreadSchedule.builder()
                            .setPrice(cdm.observable.asset.metafields.ReferenceWithMetaPriceSchedule.builder()
                                    .setReference(Reference.builder().setScope("DOCUMENT")
                                            .setReference("price-1").build())
                                    .build())
                            .build());
                }

                irpb.setRateSpecification(cdm.product.asset.RateSpecification.builder()
                        .setFloatingRateSpecification(frsb.build())
                        .build());
            }

            // Day count fraction
            String dcf = XmlUtils.childText(interestCalc, "dayCountFraction");
            if (dcf != null) {
                irpb.setDayCountFraction(EnumMappers.dayCount(dcf));
            }
        }

        // Calculation period dates
        Element calcPeriodDates = XmlUtils.child(interestLeg, "interestLegCalculationPeriodDates");
        if (calcPeriodDates != null) {
            irpb.setCalculationPeriodDates(buildCalcPeriodDates(calcPeriodDates));

            // Payment dates from interest leg
            Element interestPayDates = XmlUtils.child(calcPeriodDates, "interestLegPaymentDates");
            if (interestPayDates != null) {
                irpb.setPaymentDates(buildInterestLegPaymentDates(interestPayDates));
            }

            // Reset dates from interest leg
            Element resetDates = XmlUtils.child(calcPeriodDates, "interestLegResetDates");
            if (resetDates != null) {
                irpb.setResetDates(buildResetDates(resetDates, calcPeriodDates));
            }
        }

        return Payout.builder().setInterestRatePayout(irpb.build()).build();
    }

    private cdm.product.common.schedule.CalculationPeriodDates buildCalcPeriodDates(Element calcPeriodDatesEl) {
        cdm.product.common.schedule.CalculationPeriodDates.CalculationPeriodDatesBuilder cpdb =
                cdm.product.common.schedule.CalculationPeriodDates.builder();

        String id = calcPeriodDatesEl.getAttribute("id");

        Element effectiveDate = XmlUtils.child(calcPeriodDatesEl, "effectiveDate");
        if (effectiveDate != null) {
            cpdb.setEffectiveDate(buildDateWithId(effectiveDate));
        }

        Element terminationDate = XmlUtils.child(calcPeriodDatesEl, "terminationDate");
        if (terminationDate != null) {
            cpdb.setTerminationDate(buildDateWithId(terminationDate));
        }

        if (id != null && !id.isEmpty()) {
            cpdb.setMeta(MetaFields.builder().setExternalKey(id).build());
        }

        return cpdb.build();
    }

    private PaymentDates buildInterestLegPaymentDates(Element interestPayDates) {
        PaymentDates.PaymentDatesBuilder pdb = PaymentDates.builder();
        PaymentDateSchedule.PaymentDateScheduleBuilder pds = PaymentDateSchedule.builder();

        Element adjDates = XmlUtils.child(interestPayDates, "adjustableDates");
        if (adjDates != null) {
            pds.addInterimPaymentDates(buildAdjustableRelativeOrPeriodicDates(interestPayDates));
        }

        Element relDates = XmlUtils.child(interestPayDates, "relativeDates");
        if (relDates != null) {
            pds.addInterimPaymentDates(buildAdjustableRelativeOrPeriodicDates(interestPayDates));
        }

        pdb.setPaymentDateSchedule(pds.build());
        // ExternalKey from the interestLegPaymentDates id attribute
        String id = interestPayDates.getAttribute("id");
        if (id != null && !id.isEmpty()) {
            pdb.setMeta(MetaFields.builder().setExternalKey(id).build());
        }
        return pdb.build();
    }

    private cdm.product.common.schedule.ResetDates buildResetDates(Element resetDatesEl, Element calcPeriodDatesEl) {
        cdm.product.common.schedule.ResetDates.ResetDatesBuilder rdb =
                cdm.product.common.schedule.ResetDates.builder();

        Element cpRef = XmlUtils.child(resetDatesEl, "calculationPeriodDatesReference");
        if (cpRef != null) {
            String href = cpRef.getAttribute("href");
            rdb.setCalculationPeriodDatesReference(
                    cdm.product.common.schedule.metafields.ReferenceWithMetaCalculationPeriodDates.builder()
                            .setExternalReference(href)
                            .build());
        }

        String resetRelativeTo = XmlUtils.childText(resetDatesEl, "resetRelativeTo");
        if (resetRelativeTo != null) {
            try {
                rdb.setResetRelativeTo(cdm.product.common.schedule.ResetRelativeToEnum.valueOf(
                        resetRelativeTo.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                try {
                    rdb.setResetRelativeTo(cdm.product.common.schedule.ResetRelativeToEnum.fromDisplayName(resetRelativeTo));
                } catch (Exception ignored2) {}
            }
        }

        return rdb.build();
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

    private List<PriceQuantity> buildTradeLotPriceQuantities(Element returnLeg, Element interestLeg) {
        List<PriceQuantity> out = new ArrayList<>();

        // First PQ: equity leg with observable + initial price + quantities
        PriceQuantity.PriceQuantityBuilder pq1 = PriceQuantity.builder();

        Element underlyer = XmlUtils.child(returnLeg, "underlyer");
        Observable observable = EquityOptionMapper.buildEquityObservable(underlyer);
        if (observable != null) {
            pq1.setObservable(FieldWithMetaObservable.builder()
                    .setValue(observable)
                    .setMeta(QuantityMapper.locationMeta("observable-1"))
                    .build());
        }

        // Initial price from rateOfReturn
        Element rateOfReturn = XmlUtils.child(returnLeg, "rateOfReturn");
        if (rateOfReturn != null) {
            Element initialPrice = XmlUtils.child(rateOfReturn, "initialPrice");
            if (initialPrice != null) {
                Element netPrice = XmlUtils.child(initialPrice, "netPrice");
                if (netPrice != null) {
                    String amount = XmlUtils.childText(netPrice, "amount");
                    String ccy = XmlUtils.childText(netPrice, "currency");
                    String priceExpr = XmlUtils.childText(netPrice, "priceExpression");
                    if (amount != null) {
                        PriceSchedule.PriceScheduleBuilder psb = PriceSchedule.builder()
                                .setValue(new BigDecimal(amount))
                                .setPriceType(PriceTypeEnum.ASSET_PRICE);
                        if (ccy != null) {
                            psb.setUnit(UnitType.builder()
                                    .setCurrency(FieldWithMetaString.builder().setValue(ccy).build())
                                    .build());
                        }
                        // perUnitOf: always Share for return swap initial price
                        psb.setPerUnitOf(UnitType.builder()
                                .setFinancialUnit(FinancialUnitEnum.SHARE)
                                .build());
                        if ("AbsoluteTerms".equals(priceExpr)) {
                            psb.setPriceExpression(PriceExpressionEnum.ABSOLUTE_TERMS);
                        } else if ("PercentageOfNotional".equals(priceExpr)) {
                            psb.setPriceExpression(PriceExpressionEnum.PERCENTAGE_OF_NOTIONAL);
                        }
                        pq1.addPrice(FieldWithMetaPriceSchedule.builder()
                                .setValue(psb.build())
                                .setMeta(QuantityMapper.locationMeta("price-2"))
                                .build());
                    }
                }
            }
        }

        // Quantities from underlyer (openUnits) and notional
        Element singleUnderlyer = underlyer != null ? XmlUtils.child(underlyer, "singleUnderlyer") : null;
        String openUnits = singleUnderlyer != null ? XmlUtils.childText(singleUnderlyer, "openUnits") : null;

        Element notional = XmlUtils.child(returnLeg, "notional");
        Element notionalAmount = notional != null ? XmlUtils.child(notional, "notionalAmount") : null;
        Element notionalCcyEl = notionalAmount != null ? XmlUtils.child(notionalAmount, "currency") : null;
        String notionalCcy = notionalCcyEl != null ? notionalCcyEl.getTextContent().trim() : null;
        String notionalCcyScheme = notionalCcyEl != null ? notionalCcyEl.getAttribute("currencyScheme") : null;
        String notionalAmt = notionalAmount != null ? XmlUtils.childText(notionalAmount, "amount") : null;

        // Determine currency for openUnits: use notional currency (USD), not equity currency (EUR)
        String unitCcy = notionalCcy;
        String unitCcyScheme = notionalCcyScheme;

        // openUnits label: quantity-3 if interest leg has separate notional, quantity-2 otherwise
        boolean interestHasNotional = false;
        if (interestLeg != null) {
            Element intNotional = XmlUtils.child(interestLeg, "notional");
            Element intNotionalAmt = intNotional != null ? XmlUtils.child(intNotional, "notionalAmount") : null;
            String intAmt = intNotionalAmt != null ? XmlUtils.childText(intNotionalAmt, "amount") : null;
            interestHasNotional = intAmt != null;
        }
        String openUnitsLabel = interestHasNotional ? "quantity-3" : "quantity-2";

        if (openUnits != null) {
            pq1.addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder()
                    .setValue(NonNegativeQuantitySchedule.builder()
                            .setValue(new BigDecimal(openUnits))
                            .setUnit(unitCcy != null ? UnitType.builder()
                                    .setCurrency(buildCcyField(unitCcy, unitCcyScheme))
                                    .build() : null)
                            .build())
                    .setMeta(QuantityMapper.locationMeta(openUnitsLabel))
                    .build());
        }

        if (notionalAmt != null) {
            pq1.addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder()
                    .setValue(NonNegativeQuantitySchedule.builder()
                            .setValue(new BigDecimal(notionalAmt))
                            .setUnit(notionalCcy != null ? UnitType.builder()
                                    .setCurrency(buildCcyField(notionalCcy, notionalCcyScheme))
                                    .build() : null)
                            .build())
                    .setMeta(QuantityMapper.locationMeta("quantity-1"))
                    .build());
        }

        out.add(pq1.build());

        // Second PQ: interest leg with spread/rate + quantity + observable
        if (interestLeg != null) {
            PriceQuantity.PriceQuantityBuilder pq2 = PriceQuantity.builder();

            Element interestCalc = XmlUtils.child(interestLeg, "interestCalculation");
            if (interestCalc != null) {
                Element frc = XmlUtils.child(interestCalc, "floatingRateCalculation");
                if (frc != null) {
                    String idxName = XmlUtils.childText(frc, "floatingRateIndex");

                    // Interest leg notional (may be in interestLeg or fall back to equity notional)
                    Element intNotional = XmlUtils.child(interestLeg, "notional");
                    Element intNotionalAmt = intNotional != null ? XmlUtils.child(intNotional, "notionalAmount") : null;
                    Element intCcyEl = intNotionalAmt != null ? XmlUtils.child(intNotionalAmt, "currency") : null;
                    String intCcy = intCcyEl != null ? intCcyEl.getTextContent().trim() : null;
                    String intCcyScheme = intCcyEl != null ? intCcyEl.getAttribute("currencyScheme") : null;
                    String intAmt = intNotionalAmt != null ? XmlUtils.childText(intNotionalAmt, "amount") : null;

                    // Fall back to equity notional currency if interest leg has no notional
                    if (intCcy == null) {
                        intCcy = notionalCcy;
                        intCcyScheme = notionalCcyScheme;
                    }

                    // Spread price
                    Element spreadSchedule = XmlUtils.child(frc, "spreadSchedule");
                    if (spreadSchedule != null) {
                        String spreadVal = XmlUtils.childText(spreadSchedule, "initialValue");
                        if (spreadVal != null) {
                            PriceSchedule.PriceScheduleBuilder psb = PriceSchedule.builder()
                                    .setValue(new BigDecimal(spreadVal))
                                    .setPriceType(PriceTypeEnum.INTEREST_RATE)
                                    .setArithmeticOperator(ArithmeticOperationEnum.ADD);
                            if (intCcy != null) {
                                UnitType ccyUnit = UnitType.builder()
                                        .setCurrency(buildCcyField(intCcy, intCcyScheme))
                                        .build();
                                psb.setUnit(ccyUnit);
                                psb.setPerUnitOf(ccyUnit);
                            }
                            pq2.addPrice(FieldWithMetaPriceSchedule.builder()
                                    .setValue(psb.build())
                                    .setMeta(QuantityMapper.locationMeta("price-1"))
                                    .build());
                        }
                    }

                    if (intAmt != null) {
                        pq2.addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder()
                                .setValue(NonNegativeQuantitySchedule.builder()
                                        .setValue(new BigDecimal(intAmt))
                                        .setUnit(intCcy != null ? UnitType.builder()
                                                .setCurrency(buildCcyField(intCcy, intCcyScheme))
                                                .build() : null)
                                        .build())
                                .setMeta(QuantityMapper.locationMeta("quantity-2"))
                                .build());
                    }

                    // Interest rate index observable
                    FloatingRateIndex.FloatingRateIndexBuilder friBld = FloatingRateIndex.builder()
                            .setAssetClass(AssetClassEnum.INTEREST_RATE)
                            .setFloatingRateIndex(EnumMappers.floatingRateIndex(idxName))
                            .addIdentifier(AssetIdentifier.builder()
                                    .setIdentifier(FieldWithMetaString.builder().setValue(idxName).build())
                                    .setIdentifierType(AssetIdTypeEnum.OTHER)
                                    .build());

                    Element tenor = XmlUtils.child(frc, "indexTenor");
                    if (tenor != null) {
                        String pm = XmlUtils.childText(tenor, "periodMultiplier");
                        String pUnit = XmlUtils.childText(tenor, "period");
                        cdm.base.datetime.Period.PeriodBuilder periodB = cdm.base.datetime.Period.builder();
                        if (pm != null) periodB.setPeriodMultiplier(Integer.parseInt(pm));
                        if (pUnit != null) periodB.setPeriod(EnumMappers.period(pUnit));
                        friBld.setIndexTenor(periodB.build());
                    }

                    InterestRateIndex iri = InterestRateIndex.builder()
                            .setFloatingRateIndex(friBld.build())
                            .build();

                    FieldWithMetaInterestRateIndex iriField = FieldWithMetaInterestRateIndex.builder()
                            .setValue(iri)
                            .setMeta(QuantityMapper.locationMeta("InterestRateIndex-1"))
                            .build();

                    Index index = Index.builder()
                            .setInterestRateIndex(iriField)
                            .build();
                    Observable obs = Observable.builder().setIndex(index).build();
                    pq2.setObservable(FieldWithMetaObservable.builder()
                            .setValue(obs)
                            .setMeta(QuantityMapper.locationMeta("observable-2"))
                            .build());
                }
            }

            out.add(pq2.build());
        }

        return out;
    }

    private List<ProductTaxonomy> buildTaxonomy(Element product, Element returnLeg) {
        List<ProductTaxonomy> out = new ArrayList<>();

        // productType from FpML
        Element productType = XmlUtils.child(product, "productType");
        if (productType != null) {
            String ptValue = productType.getTextContent().trim();
            String ptScheme = productType.getAttribute("productTypeScheme");
            FieldWithMetaString.FieldWithMetaStringBuilder name = FieldWithMetaString.builder().setValue(ptValue);
            if (ptScheme != null && !ptScheme.isEmpty()) {
                name.setMeta(MetaFields.builder().setScheme(ptScheme).build());
            }
            TaxonomyValue tv = TaxonomyValue.builder().setName(name.build()).build();
            out.add(ProductTaxonomy.builder().setSource(TaxonomySourceEnum.OTHER).setValue(tv).build());
        }

        // Compute qualifier
        String qualifier = computeReturnSwapQualifier(product, returnLeg);
        out.add(ProductTaxonomy.builder()
                .setSource(TaxonomySourceEnum.ISDA)
                .setProductQualifier(qualifier)
                .build());

        return out;
    }

    private static FieldWithMetaString buildCcyField(String ccy, String scheme) {
        FieldWithMetaString.FieldWithMetaStringBuilder b = FieldWithMetaString.builder().setValue(ccy);
        if (scheme != null && !scheme.isEmpty()) {
            b.setMeta(MetaFields.builder().setScheme(scheme).build());
        }
        return b.build();
    }

    private String computeReturnSwapQualifier(Element product, Element returnLeg) {
        // Determine return type — TotalReturn taxonomy only when dividendConditions is present;
        // <returnType>Total</returnType> alone (no dividend terms) yields PriceReturn taxonomy.
        Element returnEl = XmlUtils.child(returnLeg, "return");
        String returnType = returnEl != null ? XmlUtils.childText(returnEl, "returnType") : null;
        boolean hasDivCond = returnEl != null && XmlUtils.child(returnEl, "dividendConditions") != null;
        boolean isTotal = "Total".equals(returnType) && hasDivCond;

        // Determine underlyer type
        Element underlyer = XmlUtils.child(returnLeg, "underlyer");
        boolean isIndex = false;
        boolean isBasket = false;
        if (underlyer != null) {
            Element su = XmlUtils.child(underlyer, "singleUnderlyer");
            if (su != null) isIndex = XmlUtils.child(su, "index") != null;
            if (XmlUtils.child(underlyer, "basket") != null) isBasket = true;
        }

        String prefix = isTotal ? "EquitySwap_TotalReturnBasicPerformance" : "EquitySwap_PriceReturnBasicPerformance";

        if (isIndex) return prefix + "_Index";
        if (isBasket) return prefix + "_Basket";
        return prefix + "_SingleName";
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
