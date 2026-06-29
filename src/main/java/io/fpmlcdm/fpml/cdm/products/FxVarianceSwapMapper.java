package io.fpmlcdm.fpml.cdm.products;

import cdm.base.datetime.*;
import cdm.base.datetime.metafields.FieldWithMetaBusinessCenterEnum;
import cdm.base.math.NonNegativeQuantitySchedule;
import cdm.base.math.UnitType;
import cdm.base.math.metafields.FieldWithMetaNonNegativeQuantitySchedule;
import cdm.base.staticdata.asset.common.*;
import cdm.base.staticdata.party.*;
import cdm.event.common.*;
import cdm.observable.asset.*;
import cdm.observable.asset.metafields.*;
import cdm.product.asset.VarianceReturnTerms;
import cdm.product.common.schedule.*;
import cdm.product.common.settlement.*;
import cdm.product.template.*;
import com.rosetta.model.lib.meta.Reference;
import com.rosetta.model.metafields.FieldWithMetaDate;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
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
 * Maps FpML {@code <fxVarianceSwap>} into CDM TradeState with a PerformancePayout
 * carrying varianceReturnTerms. Modelled after {@link FxVolatilitySwapMapper}; the structural
 * differences are:
 *  - quantity-1 is the notional (instead of vegaNotional)
 *  - quantity-2 is the vegaNotional
 *  - VarianceReturnTerms carries varianceStrikePrice (priceType=InterestRate) and vegaNotionalAmount
 *  - ISDA productQualifier is {@code ForeignExchange_ParameterReturnVariance}
 */
public class FxVarianceSwapMapper implements ProductMapper {

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);
        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        parties = SwapMapper.applyPartyTradeInformation(parties, tradeHeader);

        Element fxv = XmlUtils.child(trade, "fxVarianceSwap");
        if (fxv == null) return null;

        Element fixedLeg = XmlUtils.child(fxv, "fixedLeg");
        Element floatingLeg = XmlUtils.child(fxv, "floatingLeg");

        // PARTY_1 = floating leg payer
        Element flPayer = floatingLeg != null ? XmlUtils.child(floatingLeg, "payerPartyReference") : null;
        if (flPayer != null) {
            String href = flPayer.getAttribute("href");
            Map<String, Integer> newOrder = new LinkedHashMap<>();
            newOrder.put(href, 0);
            int idx = 1;
            for (String pid : ctx.partyOrder.keySet()) {
                if (!pid.equals(href)) newOrder.put(pid, idx++);
            }
            ctx.partyOrder.clear();
            ctx.partyOrder.putAll(newOrder);
        }

        PerformancePayout.PerformancePayoutBuilder ppb = PerformancePayout.builder();

        // payerReceiver: payer = floatLeg payer (Party1), receiver = fixedLeg payer (Party2)
        Element fxPayer = fixedLeg != null ? XmlUtils.child(fixedLeg, "payerPartyReference") : null;
        if (flPayer != null && fxPayer != null) {
            CounterpartyRoleEnum payer = roleFor(flPayer.getAttribute("href"), ctx);
            CounterpartyRoleEnum receiver = roleFor(fxPayer.getAttribute("href"), ctx);
            ppb.setPayerReceiver(PayerReceiver.builder().setPayer(payer).setReceiver(receiver).build());
        }

        // priceQuantity reference â†’ quantity-1 (the notional)
        ppb.setPriceQuantity(ResolvablePriceQuantity.builder()
                .setQuantitySchedule(cdm.base.math.metafields.ReferenceWithMetaNonNegativeQuantitySchedule.builder()
                        .setReference(Reference.builder().setScope("DOCUMENT").setReference("quantity-1").build())
                        .build())
                .build());

        // settlementTerms (cashSettlement.settlementCurrency + settlementDate from fxv)
        SettlementTerms.SettlementTermsBuilder stb = SettlementTerms.builder();
        Element cashSettlement = XmlUtils.child(fxv, "cashSettlement");
        String settlementCcy = cashSettlement != null ? XmlUtils.childText(cashSettlement, "settlementCurrency") : null;
        if (settlementCcy != null) {
            stb.setSettlementType(SettlementTypeEnum.CASH);
            stb.setSettlementCurrency(FieldWithMetaString.builder().setValue(settlementCcy).build());
        }
        Element settlementDateEl = XmlUtils.child(fxv, "settlementDate");
        if (settlementDateEl != null) {
            String adj = XmlUtils.childText(settlementDateEl, "adjustedDate");
            if (adj != null) {
                AdjustableOrAdjustedOrRelativeDate aaord = AdjustableOrAdjustedOrRelativeDate.builder()
                        .setAdjustedDate(FieldWithMetaDate.builder().setValue(DateMapper.parse(adj)).build())
                        .build();
                stb.setSettlementDate(SettlementDate.builder().setAdjustableOrRelativeDate(aaord).build());
            }
        }
        ppb.setSettlementTerms(stb.build());

        // observationTerms
        ObservationTerms ot = buildObservationTerms(fxv);
        if (ot != null) ppb.setObservationTerms(ot);

        // valuationDates.finalValuationDate.valuationDates.relativeDates (from valuationDateOffset)
        Element valOffset = XmlUtils.child(fxv, "valuationDateOffset");
        if (valOffset != null) {
            RelativeDates.RelativeDatesBuilder rdb = RelativeDates.builder();
            String pm = XmlUtils.childText(valOffset, "periodMultiplier");
            if (pm != null) rdb.setPeriodMultiplier(Integer.parseInt(pm));
            String period = XmlUtils.childText(valOffset, "period");
            if (period != null) rdb.setPeriod(EnumMappers.period(period));
            String dayType = XmlUtils.childText(valOffset, "dayType");
            if (dayType != null) rdb.setDayType(EquityOptionMapper.mapDayType(dayType));
            Element bcs = XmlUtils.child(valOffset, "businessCenters");
            if (bcs != null) rdb.setBusinessCenters(DateMapper.buildBusinessCenters(bcs));

            cdm.observable.asset.PerformanceValuationDates pvd =
                    cdm.observable.asset.PerformanceValuationDates.builder()
                            .setValuationDates(AdjustableRelativeOrPeriodicDates.builder()
                                    .setRelativeDates(rdb.build())
                                    .build())
                            .build();
            ppb.setValuationDates(ValuationDates.builder().setFinalValuationDate(pvd).build());
        }

        // underlier reference to observable-1
        ppb.setUnderlier(Underlier.builder()
                .setObservable(ReferenceWithMetaObservable.builder()
                        .setReference(Reference.builder().setScope("DOCUMENT").setReference("observable-1").build())
                        .build())
                .build());

        // returnTerms.varianceReturnTerms
        VarianceReturnTerms.VarianceReturnTermsBuilder vrtb = VarianceReturnTerms.builder();
        String annualizationFactor = XmlUtils.childText(fxv, "annualizationFactor");
        if (annualizationFactor != null) vrtb.setAnnualizationFactor(Integer.parseInt(annualizationFactor));
        String meanAdjustment = XmlUtils.childText(fxv, "meanAdjustment");
        if (meanAdjustment != null) vrtb.setMeanAdjustment(Boolean.parseBoolean(meanAdjustment));

        // varianceStrikePrice (from fixedLeg.fixedRate), unit=currency2 perUnitOf=currency1, priceType=InterestRate
        String fixedRate = fixedLeg != null ? XmlUtils.childText(fixedLeg, "fixedRate") : null;
        Element qcp = XmlUtils.child(fxv, "quotedCurrencyPair");
        String currency1 = qcp != null ? XmlUtils.childText(qcp, "currency1") : null;
        String currency2 = qcp != null ? XmlUtils.childText(qcp, "currency2") : null;
        if (fixedRate != null) {
            cdm.observable.asset.Price.PriceBuilder pb = cdm.observable.asset.Price.builder()
                    .setValue(new BigDecimal(fixedRate))
                    .setPriceType(cdm.observable.asset.PriceTypeEnum.INTEREST_RATE);
            if (currency2 != null) {
                pb.setUnit(UnitType.builder()
                        .setCurrency(FieldWithMetaString.builder().setValue(currency2).build())
                        .build());
            }
            if (currency1 != null) {
                pb.setPerUnitOf(UnitType.builder()
                        .setCurrency(FieldWithMetaString.builder().setValue(currency1).build())
                        .build());
            }
            vrtb.setVarianceStrikePrice(pb.build());
        }

        // vegaNotionalAmount on the return terms (value+currency from fxv.vegaNotional)
        Element vegaEl = XmlUtils.child(fxv, "vegaNotional");
        if (vegaEl != null) {
            String vegaAmt = XmlUtils.childText(vegaEl, "amount");
            String vegaCcy = XmlUtils.childText(vegaEl, "currency");
            if (vegaAmt != null) {
                NonNegativeQuantitySchedule.NonNegativeQuantityScheduleBuilder qsb =
                        NonNegativeQuantitySchedule.builder().setValue(new BigDecimal(vegaAmt));
                if (vegaCcy != null) {
                    qsb.setUnit(UnitType.builder()
                            .setCurrency(FieldWithMetaString.builder().setValue(vegaCcy).build())
                            .build());
                }
                vrtb.setVegaNotionalAmount(qsb.build());
            }
        }

        ppb.setReturnTerms(cdm.product.template.ReturnTerms.builder().setVarianceReturnTerms(vrtb.build()).build());

        Payout payout = Payout.builder().setPerformancePayout(ppb.build()).build();

        EconomicTerms.EconomicTermsBuilder econ = EconomicTerms.builder().addPayout(payout);

        // CalculationAgent
        CalculationAgentMapper.Result ca = CalculationAgentMapper.map(trade);
        if (ca.calculationAgent() != null) econ.setCalculationAgent(ca.calculationAgent());

        // Taxonomy
        List<ProductTaxonomy> taxonomies = new ArrayList<>();
        Element ptEl = XmlUtils.child(fxv, "productType");
        if (ptEl != null) {
            String ptValue = ptEl.getTextContent().trim();
            String ptScheme = ptEl.getAttribute("productTypeScheme");
            FieldWithMetaString.FieldWithMetaStringBuilder name = FieldWithMetaString.builder().setValue(ptValue);
            if (ptScheme != null && !ptScheme.isEmpty()) {
                name.setMeta(MetaFields.builder().setScheme(ptScheme).build());
            }
            TaxonomyValue tv = TaxonomyValue.builder().setName(name.build()).build();
            taxonomies.add(ProductTaxonomy.builder().setSource(TaxonomySourceEnum.OTHER).setValue(tv).build());
        }
        taxonomies.add(ProductTaxonomy.builder()
                .setSource(TaxonomySourceEnum.ISDA)
                .setProductQualifier("ForeignExchange_ParameterReturnVariance")
                .build());

        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder()
                .setEconomicTerms(econ.build());
        taxonomies.forEach(ntp::addTaxonomy);

        // TradeLot: quantity-2 = vegaNotional, quantity-1 = notional, observable = FX rate index
        TradeLot tradeLot = buildTradeLot(fxv);

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
                XmlUtils.child(trade, "documentation"), parties, ctx, trade);

        Trade.TradeBuilder t = Trade.builder()
                .setProduct(ntp.build())
                .addTradeLot(tradeLot);
        counterparties.forEach(t::addCounterparty);
        for (AncillaryParty ap : ca.ancillaryParties()) t.addAncillaryParty(ap);
        identifiers.forEach(t::addTradeIdentifier);
        if (tradeDate != null) t.setTradeDate(tradeDate);
        parties.forEach(t::addParty);
        if (contractDetails != null) t.setContractDetails(contractDetails);
        for (Account a : AccountMapper.map(doc)) t.addAccount(a);

        TradeState.TradeStateBuilder tsBuilder = TradeState.builder().setTrade(t.build());

        // additionalPayment â†’ transferHistory
        for (Element ap : XmlUtils.children(fxv, "additionalPayment")) {
            for (TransferState ts : TransferMapper.map(trade, fxv)) {
                tsBuilder.addTransferHistory(ts);
            }
            break;
        }

        return tsBuilder.build();
    }

    private ObservationTerms buildObservationTerms(Element fxv) {
        ObservationTerms.ObservationTermsBuilder otb = ObservationTerms.builder();
        boolean any = false;

        // fixingInformationSource â†’ observationTime + informationSource
        Element fixingInfo = XmlUtils.child(fxv, "fixingInformationSource");
        if (fixingInfo != null) {
            Element fixingTime = XmlUtils.child(fixingInfo, "fixingTime");
            if (fixingTime != null) {
                BusinessCenterTime.BusinessCenterTimeBuilder bctb = BusinessCenterTime.builder();
                String hmt = XmlUtils.childText(fixingTime, "hourMinuteTime");
                if (hmt != null) try { bctb.setHourMinuteTime(LocalTime.parse(hmt)); } catch (Exception ignored) {}
                String bc = XmlUtils.childText(fixingTime, "businessCenter");
                if (bc != null) try {
                    bctb.setBusinessCenter(FieldWithMetaBusinessCenterEnum.builder()
                            .setValue(BusinessCenterEnum.valueOf(bc)).build());
                } catch (Exception ignored) {}
                otb.setObservationTime(bctb.build());
                any = true;
            }
            Element primaryRateSource = XmlUtils.child(fixingInfo, "primaryRateSource");
            if (primaryRateSource != null) {
                String sourcePage = XmlUtils.childText(primaryRateSource, "rateSourcePage");
                if (sourcePage != null) {
                    InformationSource.InformationSourceBuilder isb = InformationSource.builder()
                            .setSourcePage(FieldWithMetaString.builder().setValue(sourcePage).build());
                    otb.setInformationSource(FxSpotRateSource.builder().setPrimarySource(isb.build()).build());
                    any = true;
                }
            }
        }

        // fixingSchedule â†’ observationDates.periodicSchedule
        Element fixingSchedule = XmlUtils.child(fxv, "fixingSchedule");
        if (fixingSchedule != null) {
            String startDate = XmlUtils.childText(fixingSchedule, "startDate");
            String endDate = XmlUtils.childText(fixingSchedule, "endDate");
            String dayType = XmlUtils.childText(fixingSchedule, "dayType");
            Element bcs = XmlUtils.child(fixingSchedule, "businessCenters");

            PeriodicDates.PeriodicDatesBuilder pdb = PeriodicDates.builder();
            if (startDate != null) {
                pdb.setStartDate(AdjustableOrRelativeDate.builder()
                        .setAdjustableDate(AdjustableDate.builder()
                                .setUnadjustedDate(DateMapper.parse(startDate)).build())
                        .build());
            }
            if (endDate != null) {
                pdb.setEndDate(AdjustableOrRelativeDate.builder()
                        .setAdjustableDate(AdjustableDate.builder()
                                .setUnadjustedDate(DateMapper.parse(endDate)).build())
                        .build());
            }
            if (bcs != null) {
                pdb.setPeriodDatesAdjustments(BusinessDayAdjustments.builder()
                        .setBusinessCenters(DateMapper.buildBusinessCenters(bcs))
                        .build());
            }
            if (dayType != null) {
                pdb.setDayType(EquityOptionMapper.mapDayType(dayType));
            }
            otb.setObservationDates(ObservationDates.builder().setPeriodicSchedule(pdb.build()).build());
            any = true;
        }

        // numberOfReturns â†’ numberOfObservationDates
        String numReturns = XmlUtils.childText(fxv, "numberOfReturns");
        if (numReturns != null) {
            otb.setNumberOfObservationDates(Integer.parseInt(numReturns));
            any = true;
        }
        return any ? otb.build() : null;
    }

    private TradeLot buildTradeLot(Element fxv) {
        cdm.observable.asset.PriceQuantity.PriceQuantityBuilder pqb =
                cdm.observable.asset.PriceQuantity.builder();

        // vegaNotional â†’ quantity-2
        Element vega = XmlUtils.child(fxv, "vegaNotional");
        if (vega != null) {
            String ccy = XmlUtils.childText(vega, "currency");
            String amount = XmlUtils.childText(vega, "amount");
            if (amount != null) {
                NonNegativeQuantitySchedule.NonNegativeQuantityScheduleBuilder qsb =
                        NonNegativeQuantitySchedule.builder().setValue(new BigDecimal(amount));
                if (ccy != null) {
                    qsb.setUnit(UnitType.builder()
                            .setCurrency(FieldWithMetaString.builder().setValue(ccy).build())
                            .build());
                }
                pqb.addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder()
                        .setValue(qsb.build())
                        .setMeta(QuantityMapper.locationMeta("quantity-2"))
                        .build());
            }
        }

        // notional â†’ quantity-1
        Element notional = XmlUtils.child(fxv, "notional");
        if (notional != null) {
            String ccy = XmlUtils.childText(notional, "currency");
            String amount = XmlUtils.childText(notional, "amount");
            if (amount != null) {
                NonNegativeQuantitySchedule.NonNegativeQuantityScheduleBuilder qsb =
                        NonNegativeQuantitySchedule.builder().setValue(new BigDecimal(amount));
                if (ccy != null) {
                    qsb.setUnit(UnitType.builder()
                            .setCurrency(FieldWithMetaString.builder().setValue(ccy).build())
                            .build());
                }
                pqb.addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder()
                        .setValue(qsb.build())
                        .setMeta(QuantityMapper.locationMeta("quantity-1"))
                        .build());
            }
        }

        // quotedCurrencyPair â†’ observable.Index.ForeignExchangeRateIndex
        Element qcp = XmlUtils.child(fxv, "quotedCurrencyPair");
        if (qcp != null) {
            String c1 = XmlUtils.childText(qcp, "currency1");
            String c2 = XmlUtils.childText(qcp, "currency2");
            String qb = XmlUtils.childText(qcp, "quoteBasis");

            QuotedCurrencyPair.QuotedCurrencyPairBuilder qcpb = QuotedCurrencyPair.builder();
            if (c1 != null) qcpb.setCurrency1(FieldWithMetaString.builder().setValue(c1).build());
            if (c2 != null) qcpb.setCurrency2(FieldWithMetaString.builder().setValue(c2).build());
            if (qb != null) {
                try {
                    qcpb.setQuoteBasis(QuoteBasisEnum.fromDisplayName(qb));
                } catch (Exception ignored) {
                    try { qcpb.setQuoteBasis(QuoteBasisEnum.valueOf(qb.toUpperCase())); }
                    catch (Exception ignored2) {}
                }
            }
            ForeignExchangeRateIndex.ForeignExchangeRateIndexBuilder fxrib =
                    ForeignExchangeRateIndex.builder()
                            .setAssetClass(AssetClassEnum.FOREIGN_EXCHANGE)
                            .setQuotedCurrencyPair(FieldWithMetaQuotedCurrencyPair.builder()
                                    .setValue(qcpb.build())
                                    .setMeta(QuantityMapper.locationMeta("quotedCurrencyPair-1"))
                                    .build());

            // primaryFxSpotRateSource from fixingInformationSource.primaryRateSource
            Element fixingInfo = XmlUtils.child(fxv, "fixingInformationSource");
            if (fixingInfo != null) {
                Element primaryRateSource = XmlUtils.child(fixingInfo, "primaryRateSource");
                if (primaryRateSource != null) {
                    String sourcePage = XmlUtils.childText(primaryRateSource, "rateSourcePage");
                    if (sourcePage != null) {
                        fxrib.setPrimaryFxSpotRateSource(InformationSource.builder()
                                .setSourcePage(FieldWithMetaString.builder().setValue(sourcePage).build())
                                .build());
                    }
                }
            }

            Observable observable = Observable.builder()
                    .setIndex(Index.builder().setForeignExchangeRateIndex(fxrib.build()).build())
                    .build();
            pqb.setObservable(FieldWithMetaObservable.builder()
                    .setValue(observable)
                    .setMeta(QuantityMapper.locationMeta("observable-1"))
                    .build());
        }
        return TradeLot.builder().addPriceQuantity(pqb.build()).build();
    }

    private static CounterpartyRoleEnum roleFor(String partyHref, MappingContext ctx) {
        if (partyHref == null) return null;
        Integer order = ctx.partyOrder.get(partyHref);
        if (order == null) return null;
        return order == 0 ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
    }
}
