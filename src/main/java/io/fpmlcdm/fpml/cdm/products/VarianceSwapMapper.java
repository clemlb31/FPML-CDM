package io.fpmlcdm.fpml.cdm.products;

import cdm.base.datetime.*;
import cdm.base.math.*;
import cdm.base.math.metafields.FieldWithMetaNonNegativeQuantitySchedule;
import cdm.base.staticdata.asset.common.*;
import cdm.base.staticdata.party.*;
import cdm.event.common.*;
import cdm.observable.asset.*;
import cdm.observable.asset.metafields.*;
import cdm.product.asset.*;
import cdm.product.common.schedule.*;
import cdm.product.common.settlement.*;
import cdm.product.template.*;
import com.rosetta.model.lib.meta.Reference;
import com.rosetta.model.metafields.*;
import io.fpmlcdm.fpml.cdm.common.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps FpML {@code <varianceSwap>}, {@code <varianceSwapTransactionSupplement>},
 * {@code <volatilitySwap>}, {@code <volatilitySwapTransactionSupplement>},
 * {@code <correlationSwap>} into CDM TradeState with a PerformancePayout
 * carrying varianceReturnTerms / volatilityReturnTerms / correlationReturnTerms.
 */
public class VarianceSwapMapper implements ProductMapper {

    private enum Kind { VARIANCE, VOLATILITY, CORRELATION }

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);

        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        parties = SwapMapper.applyPartyTradeInformation(parties, tradeHeader);

        // Identify product + leg(s)
        Kind kind;
        Element product;
        List<Element> legs;

        Element variance = XmlUtils.child(trade, "varianceSwap");
        Element varianceTx = XmlUtils.child(trade, "varianceSwapTransactionSupplement");
        Element varianceOptTx = XmlUtils.child(trade, "varianceOptionTransactionSupplement");
        Element volatility = XmlUtils.child(trade, "volatilitySwap");
        Element volatilityTx = XmlUtils.child(trade, "volatilitySwapTransactionSupplement");
        Element correlation = XmlUtils.child(trade, "correlationSwap");

        if (variance != null || varianceTx != null || varianceOptTx != null) {
            kind = Kind.VARIANCE;
            product = variance != null ? variance : (varianceTx != null ? varianceTx : varianceOptTx);
            if (varianceOptTx != null && variance == null && varianceTx == null) {
                Element inner = XmlUtils.child(varianceOptTx, "varianceSwapTransactionSupplement");
                if (inner != null) product = inner;
            }
            legs = XmlUtils.children(product, "varianceLeg");
        } else if (volatility != null || volatilityTx != null) {
            kind = Kind.VOLATILITY;
            product = volatility != null ? volatility : volatilityTx;
            legs = XmlUtils.children(product, "volatilityLeg");
        } else if (correlation != null) {
            kind = Kind.CORRELATION;
            product = correlation;
            legs = XmlUtils.children(product, "correlationLeg");
        } else {
            return null;
        }

        if (legs.isEmpty()) return null;
        Element leg = legs.get(0);
        boolean isDispersion = (kind == Kind.VARIANCE) && legs.size() > 1;

        // PARTY_1 = payer of (first) leg
        Element payerRef = XmlUtils.child(leg, "payerPartyReference");
        if (payerRef != null) {
            assignRoles(payerRef.getAttribute("href"), ctx);
        }

        // Build economic terms
        EconomicTerms.EconomicTermsBuilder econ = EconomicTerms.builder();

        // Effective/termination date — only emit when both present
        Element effectiveDate = XmlUtils.child(leg, "effectiveDate");
        Element terminationDate = XmlUtils.child(leg, "terminationDate");
        if (effectiveDate != null && terminationDate != null) {
            AdjustableOrRelativeDate aordEff = buildDateWithId(effectiveDate);
            if (aordEff != null) econ.setEffectiveDate(aordEff);
            AdjustableOrRelativeDate aordTerm = buildDateWithId(terminationDate);
            if (aordTerm != null) econ.setTerminationDate(aordTerm);
        }

        // Performance payouts (one per leg)
        for (int i = 0; i < legs.size(); i++) {
            econ.addPayout(buildPerformancePayout(legs.get(i), kind, ctx, i, isDispersion));
        }

        // Calculation agent
        CalculationAgentMapper.Result ca = CalculationAgentMapper.map(trade);
        if (ca.calculationAgent() != null) econ.setCalculationAgent(ca.calculationAgent());

        // Taxonomy
        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder()
                .setEconomicTerms(econ.build());
        buildTaxonomy(leg, kind, isDispersion).forEach(ntp::addTaxonomy);

        // TradeLot
        List<PriceQuantity> allPq = new ArrayList<>();
        for (int i = 0; i < legs.size(); i++) {
            allPq.addAll(buildTradeLotPriceQuantities(legs.get(i), kind, i));
        }
        TradeLot tradeLot = TradeLot.builder()
                .setPriceQuantity(allPq)
                .build();

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
        for (AncillaryParty ap : ca.ancillaryParties()) t.addAncillaryParty(ap);
        identifiers.forEach(t::addTradeIdentifier);
        if (tradeDate != null) t.setTradeDate(tradeDate);
        parties.forEach(t::addParty);
        if (contractDetails != null) t.setContractDetails(contractDetails);
        for (Account a : AccountMapper.map(doc)) t.addAccount(a);

        return TradeState.builder().setTrade(t.build()).build();
    }

    private Payout buildPerformancePayout(Element leg, Kind kind, MappingContext ctx, int legIdx, boolean isDispersion) {
        PerformancePayout.PerformancePayoutBuilder ppb = PerformancePayout.builder();
        int oneBased = legIdx + 1;

        // Payer/receiver
        Element payerRef = XmlUtils.child(leg, "payerPartyReference");
        Element receiverRef = XmlUtils.child(leg, "receiverPartyReference");
        ppb.setPayerReceiver(buildPayerReceiver(payerRef, receiverRef, ctx));

        // PriceQuantity ref
        ppb.setPriceQuantity(ResolvablePriceQuantity.builder()
                .setQuantitySchedule(cdm.base.math.metafields.ReferenceWithMetaNonNegativeQuantitySchedule.builder()
                        .setReference(Reference.builder().setScope("DOCUMENT").setReference("quantity-" + oneBased).build())
                        .build())
                .build());

        // Settlement terms (only on first leg)
        if (legIdx == 0) {
            SettlementTerms settlementTerms = buildSettlementTerms(leg);
            if (settlementTerms != null) ppb.setSettlementTerms(settlementTerms);
        }

        // observationStartDate (only on first leg) → observationTerms.observationDates.periodicSchedule.startDate
        Element amount = XmlUtils.child(leg, "amount");
        if (legIdx == 0) {
            Element observationStartDate = amount != null ? XmlUtils.child(amount, "observationStartDate") : null;
            if (observationStartDate != null) {
                AdjustableOrRelativeDate aord = buildDateWithId(observationStartDate);
                if (aord != null) {
                    ObservationTerms.ObservationTermsBuilder otb = ObservationTerms.builder()
                            .setObservationDates(ObservationDates.builder()
                                    .setPeriodicSchedule(PeriodicDates.builder()
                                            .setStartDate(aord)
                                            .build())
                                    .build());
                    ppb.setObservationTerms(otb.build());
                }
            }
        }

        // valuation → valuationDates.finalValuationDate (only on first leg)
        Element valuation = XmlUtils.child(leg, "valuation");
        if (legIdx == 0 && valuation != null) {
            ValuationDates vd = buildFinalValuationDates(valuation);
            if (vd != null) ppb.setValuationDates(vd);
        }

        // Underlier - reference to observable-N
        ppb.setUnderlier(Underlier.builder()
                .setObservable(ReferenceWithMetaObservable.builder()
                        .setReference(Reference.builder().setScope("DOCUMENT").setReference("observable-" + oneBased).build())
                        .build())
                .build());

        // Return terms (subsequent dispersion legs skip valuationTerms)
        ReturnTerms returnTerms = buildReturnTerms(leg, kind, legIdx == 0 ? valuation : null);
        if (returnTerms != null) ppb.setReturnTerms(returnTerms);

        return Payout.builder().setPerformancePayout(ppb.build()).build();
    }

    private SettlementTerms buildSettlementTerms(Element leg) {
        String settlementType = XmlUtils.childText(leg, "settlementType");
        String settlementCurrency = XmlUtils.childText(leg, "settlementCurrency");
        Element settlementDate = XmlUtils.child(leg, "settlementDate");

        if (settlementType == null && settlementCurrency == null && settlementDate == null) {
            return null;
        }

        SettlementTerms.SettlementTermsBuilder stb = SettlementTerms.builder();
        if (settlementType != null) {
            if ("Cash".equals(settlementType)) stb.setSettlementType(SettlementTypeEnum.CASH);
            else if ("Physical".equals(settlementType)) stb.setSettlementType(SettlementTypeEnum.PHYSICAL);
            else if ("Election".equals(settlementType)) stb.setSettlementType(SettlementTypeEnum.ELECTION);
        }
        if (settlementCurrency != null) {
            stb.setSettlementCurrency(FieldWithMetaString.builder().setValue(settlementCurrency).build());
        }
        if (settlementDate != null) {
            AdjustableOrAdjustedOrRelativeDate aaord = buildAdjustableOrAdjustedOrRelativeDate(settlementDate);
            if (aaord != null) {
                stb.setSettlementDate(SettlementDate.builder()
                        .setAdjustableOrRelativeDate(aaord)
                        .build());
            }
        }
        return stb.build();
    }

    private ValuationDates buildFinalValuationDates(Element valuation) {
        Element valuationDate = XmlUtils.child(valuation, "valuationDate");
        if (valuationDate == null) return null;

        AdjustableOrRelativeDate aord = buildDateWithId(valuationDate);
        if (aord == null) return null;

        PerformanceValuationDates.PerformanceValuationDatesBuilder pvdb =
                PerformanceValuationDates.builder().setValuationDate(aord);

        String timeType = XmlUtils.childText(valuation, "valuationTimeType");
        if (timeType != null) {
            try {
                pvdb.setValuationTimeType(cdm.observable.common.TimeTypeEnum.valueOf(
                        timeType.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                try {
                    pvdb.setValuationTimeType(cdm.observable.common.TimeTypeEnum.fromDisplayName(timeType));
                } catch (Exception ignored2) {}
            }
        }

        return ValuationDates.builder().setFinalValuationDate(pvdb.build()).build();
    }

    private ReturnTerms buildReturnTerms(Element leg, Kind kind, Element valuation) {
        Element amount = XmlUtils.child(leg, "amount");
        if (amount == null) return null;

        ReturnTerms.ReturnTermsBuilder rtb = ReturnTerms.builder();

        switch (kind) {
            case VARIANCE -> {
                Element variance = XmlUtils.child(amount, "variance");
                if (variance == null) return null;
                rtb.setVarianceReturnTerms(buildVarianceReturnTerms(variance, amount, valuation));
            }
            case VOLATILITY -> {
                Element volatility = XmlUtils.child(amount, "volatility");
                if (volatility == null) return null;
                rtb.setVolatilityReturnTerms(buildVolatilityReturnTerms(volatility, amount, valuation));
            }
            case CORRELATION -> {
                Element correlation = XmlUtils.child(amount, "correlation");
                if (correlation == null) return null;
                rtb.setCorrelationReturnTerms(buildCorrelationReturnTerms(correlation, amount, valuation));
            }
        }
        return rtb.build();
    }

    private VarianceReturnTerms buildVarianceReturnTerms(Element variance, Element amount, Element valuation) {
        VarianceReturnTerms.VarianceReturnTermsBuilder vrtb = VarianceReturnTerms.builder();

        // valuationTerms (only when valuation passed in, ie. first leg)
        ValuationTerms valTerms = buildValuationTerms(valuation);
        if (valTerms != null) vrtb.setValuationTerms(valTerms);

        // dividendApplicability from amount-level fields
        DividendApplicability divApp = buildDividendApplicability(amount);
        if (divApp != null) vrtb.setDividendApplicability(divApp);

        // initialLevel
        String initialLevel = XmlUtils.childText(variance, "initialLevel");
        if (initialLevel != null) vrtb.setInitialLevel(new BigDecimal(initialLevel));

        // varianceStrikePrice
        String strikePrice = XmlUtils.childText(variance, "varianceStrikePrice");
        if (strikePrice != null) {
            vrtb.setVarianceStrikePrice(Price.builder()
                    .setValue(new BigDecimal(strikePrice))
                    .setPriceType(PriceTypeEnum.VARIANCE)
                    .build());
        }

        // exchangeTradedContractNearest → observable wrapping ListedDerivative
        Element etcn = XmlUtils.child(variance, "exchangeTradedContractNearest");
        if (etcn != null) {
            Observable etcnObs = buildListedDerivativeObservable(etcn);
            if (etcnObs != null) {
                vrtb.setExchangeTradedContractNearest(
                        ReferenceWithMetaObservable.builder().setValue(etcnObs).build());
            }
        }

        // expectedN
        String expN = XmlUtils.childText(variance, "expectedN");
        if (expN != null) vrtb.setExpectedN(Integer.parseInt(expN));

        // varianceCap → varianceCapFloor.varianceCap (boolean)
        String varCap = XmlUtils.childText(variance, "varianceCap");
        if (varCap != null) {
            vrtb.setVarianceCapFloor(cdm.product.asset.VarianceCapFloor.builder()
                    .setVarianceCap(Boolean.parseBoolean(varCap))
                    .build());
        }
        // boundedVariance (conditional variance swap) → varianceCapFloor.boundedVariance
        Element bvEl = XmlUtils.child(variance, "boundedVariance");
        if (bvEl != null) {
            cdm.product.asset.BoundedVariance.BoundedVarianceBuilder bvb =
                    cdm.product.asset.BoundedVariance.builder();
            String rvm = XmlUtils.childText(bvEl, "realisedVarianceMethod");
            if (rvm != null) {
                cdm.product.asset.RealisedVarianceMethodEnum rvme = null;
                try { rvme = cdm.product.asset.RealisedVarianceMethodEnum.valueOf(rvm); }
                catch (IllegalArgumentException ignored) {}
                if (rvme == null) {
                    try { rvme = cdm.product.asset.RealisedVarianceMethodEnum.fromDisplayName(rvm); }
                    catch (Exception ignored) {}
                }
                if (rvme == null) {
                    try { rvme = cdm.product.asset.RealisedVarianceMethodEnum.valueOf(rvm.toUpperCase()); }
                    catch (IllegalArgumentException ignored) {}
                }
                if (rvme != null) bvb.setRealisedVarianceMethod(rvme);
            }
            String dira = XmlUtils.childText(bvEl, "daysInRangeAdjustment");
            if (dira != null) bvb.setDaysInRangeAdjustment(Boolean.parseBoolean(dira));
            String upper = XmlUtils.childText(bvEl, "upperBarrier");
            if (upper != null) bvb.setUpperBarrier(new BigDecimal(upper));
            String lower = XmlUtils.childText(bvEl, "lowerBarrier");
            if (lower != null) bvb.setLowerBarrier(new BigDecimal(lower));
            vrtb.setVarianceCapFloor(cdm.product.asset.VarianceCapFloor.builder()
                    .setBoundedVariance(bvb.build())
                    .build());
        }

        // vegaNotionalAmount → vegaNotionalAmount.value + unit.currency (from amount->varianceAmount->currency)
        String vegaStr = XmlUtils.childText(variance, "vegaNotionalAmount");
        if (vegaStr != null) {
            String vegaCcy = null;
            Element varAmt = XmlUtils.child(variance, "varianceAmount");
            if (varAmt != null) vegaCcy = XmlUtils.childText(varAmt, "currency");
            NonNegativeQuantitySchedule.NonNegativeQuantityScheduleBuilder vegaSchedule =
                    NonNegativeQuantitySchedule.builder().setValue(new BigDecimal(vegaStr));
            if (vegaCcy != null) {
                vegaSchedule.setUnit(UnitType.builder()
                        .setCurrency(FieldWithMetaString.builder().setValue(vegaCcy).build())
                        .build());
            }
            vrtb.setVegaNotionalAmount(vegaSchedule.build());
        }

        return vrtb.build();
    }

    private VolatilityReturnTerms buildVolatilityReturnTerms(Element volatility, Element amount, Element valuation) {
        VolatilityReturnTerms.VolatilityReturnTermsBuilder vrtb = VolatilityReturnTerms.builder();

        DividendApplicability divApp = buildDividendApplicability(amount);
        if (divApp != null) vrtb.setDividendApplicability(divApp);

        // expectedN
        String expN = XmlUtils.childText(volatility, "expectedN");
        if (expN != null) vrtb.setExpectedN(Integer.parseInt(expN));

        // volatilityStrikePrice
        String strikePrice = XmlUtils.childText(volatility, "volatilityStrikePrice");
        if (strikePrice != null) {
            vrtb.setVolatilityStrikePrice(Price.builder()
                    .setValue(new BigDecimal(strikePrice))
                    .setPriceType(PriceTypeEnum.VOLATILITY)
                    .build());
        }

        // volatilityCap
        Element volCap = XmlUtils.child(volatility, "volatilityCap");
        if (volCap != null) {
            VolatilityCapFloor.VolatilityCapFloorBuilder vcfb = VolatilityCapFloor.builder();
            String applicable = XmlUtils.childText(volCap, "applicable");
            if (applicable != null) vcfb.setApplicable(Boolean.parseBoolean(applicable));
            String totalCap = XmlUtils.childText(volCap, "totalVolatilityCap");
            if (totalCap != null) vcfb.setTotalVolatilityCap(new BigDecimal(totalCap));
            String capFactor = XmlUtils.childText(volCap, "volatilityCapFactor");
            if (capFactor != null) vcfb.setVolatilityCapFactor(new BigDecimal(capFactor));
            vrtb.setVolatilityCapFloor(vcfb.build());
        }

        return vrtb.build();
    }

    private CorrelationReturnTerms buildCorrelationReturnTerms(Element correlation, Element amount, Element valuation) {
        CorrelationReturnTerms.CorrelationReturnTermsBuilder crtb = CorrelationReturnTerms.builder();

        ValuationTerms valTerms = buildValuationTerms(valuation);
        if (valTerms != null) crtb.setValuationTerms(valTerms);

        DividendApplicability divApp = buildDividendApplicability(amount);
        if (divApp != null) crtb.setDividendApplicability(divApp);

        // expectedN
        String expN = XmlUtils.childText(correlation, "expectedN");
        if (expN != null) crtb.setExpectedN(Integer.parseInt(expN));

        // correlationStrikePrice
        String strikePrice = XmlUtils.childText(correlation, "correlationStrikePrice");
        if (strikePrice != null) {
            crtb.setCorrelationStrikePrice(Price.builder()
                    .setValue(new BigDecimal(strikePrice))
                    .setPriceType(PriceTypeEnum.CORRELATION)
                    .build());
        }

        // boundedCorrelation
        Element bounded = XmlUtils.child(correlation, "boundedCorrelation");
        if (bounded != null) {
            String minBound = XmlUtils.childText(bounded, "minimumBoundaryPercent");
            String maxBound = XmlUtils.childText(bounded, "maximumBoundaryPercent");
            NumberRange.NumberRangeBuilder nrb = NumberRange.builder();
            if (minBound != null) {
                nrb.setLowerBound(NumberBound.builder().setNumber(new BigDecimal(minBound)).build());
            }
            if (maxBound != null) {
                nrb.setUpperBound(NumberBound.builder().setNumber(new BigDecimal(maxBound)).build());
            }
            crtb.setBoundedCorrelation(nrb.build());
        }

        // numberOfDataSeries
        String nds = XmlUtils.childText(correlation, "numberOfDataSeries");
        if (nds != null) crtb.setNumberOfDataSeries(Integer.parseInt(nds));

        return crtb.build();
    }

    private ValuationTerms buildValuationTerms(Element valuation) {
        if (valuation == null) return null;
        String futures = XmlUtils.childText(valuation, "futuresPriceValuation");
        String options = XmlUtils.childText(valuation, "optionsPriceValuation");
        if (futures == null && options == null) return null;
        ValuationTerms.ValuationTermsBuilder vtb = ValuationTerms.builder();
        if (futures != null) vtb.setFuturesPriceValuation(Boolean.parseBoolean(futures));
        if (options != null) vtb.setOptionsPriceValuation(Boolean.parseBoolean(options));
        return vtb.build();
    }

    private DividendApplicability buildDividendApplicability(Element amount) {
        if (amount == null) return null;
        String optExchDiv = XmlUtils.childText(amount, "optionsExchangeDividends");
        String addDiv = XmlUtils.childText(amount, "additionalDividends");
        String allDiv = XmlUtils.childText(amount, "allDividends");
        if (optExchDiv == null && addDiv == null && allDiv == null) return null;
        DividendApplicability.DividendApplicabilityBuilder dab = DividendApplicability.builder();
        if (optExchDiv != null) dab.setOptionsExchangeDividends(Boolean.parseBoolean(optExchDiv));
        if (addDiv != null) dab.setAdditionalDividends(Boolean.parseBoolean(addDiv));
        if (allDiv != null) dab.setAllDividends(Boolean.parseBoolean(allDiv));
        return dab.build();
    }

    private Observable buildListedDerivativeObservable(Element etcn) {
        ListedDerivative.ListedDerivativeBuilder ldb = ListedDerivative.builder();

        // Identifiers
        for (Element instId : XmlUtils.children(etcn, "instrumentId")) {
            String value = instId.getTextContent().trim();
            String scheme = instId.getAttribute("instrumentIdScheme");
            AssetIdentifier.AssetIdentifierBuilder aib = AssetIdentifier.builder()
                    .setIdentifier(FieldWithMetaString.builder()
                            .setValue(value)
                            .setMeta(scheme != null && !scheme.isEmpty()
                                    ? MetaFields.builder().setScheme(scheme).build() : null)
                            .build())
                    .setIdentifierType(EquityOptionMapper.mapAssetIdType(scheme));
            ldb.addIdentifier(aib.build());
        }

        // Description as Name identifier
        String description = XmlUtils.childText(etcn, "description");
        if (description != null) {
            ldb.addIdentifier(AssetIdentifier.builder()
                    .setIdentifier(FieldWithMetaString.builder().setValue(description).build())
                    .setIdentifierType(AssetIdTypeEnum.NAME)
                    .build());
        }

        // isExchangeListed
        ldb.setIsExchangeListed(true);

        // Exchange
        Element exchangeId = XmlUtils.child(etcn, "exchangeId");
        if (exchangeId != null) {
            ldb.setExchange(buildLegalEntity(exchangeId));
        }

        // Related exchanges
        for (Element relExId : XmlUtils.children(etcn, "relatedExchangeId")) {
            ldb.addRelatedExchange(buildLegalEntity(relExId));
        }

        return Observable.builder()
                .setAsset(Asset.builder()
                        .setInstrument(Instrument.builder()
                                .setListedDerivative(ldb.build())
                                .build())
                        .build())
                .build();
    }

    private LegalEntity buildLegalEntity(Element fpml) {
        String value = fpml.getTextContent().trim();
        String scheme = fpml.getAttribute("exchangeIdScheme");
        FieldWithMetaString.FieldWithMetaStringBuilder nameB = FieldWithMetaString.builder().setValue(value);
        if (scheme != null && !scheme.isEmpty()) {
            nameB.setMeta(MetaFields.builder().setScheme(scheme).build());
        }
        return LegalEntity.builder().setName(nameB.build()).build();
    }

    private List<PriceQuantity> buildTradeLotPriceQuantities(Element leg, Kind kind, int legIdx) {
        List<PriceQuantity> out = new ArrayList<>();
        PriceQuantity.PriceQuantityBuilder pq = PriceQuantity.builder();
        int oneBased = legIdx + 1;

        // Quantity (varianceAmount / vegaNotionalAmount / notionalAmount)
        Element amount = XmlUtils.child(leg, "amount");
        QuantityInfo qty = extractQuantity(amount, kind);
        if (qty != null) {
            NonNegativeQuantitySchedule.NonNegativeQuantityScheduleBuilder nqs =
                    NonNegativeQuantitySchedule.builder().setValue(qty.amount);
            if (qty.currency != null) {
                nqs.setUnit(UnitType.builder()
                        .setCurrency(FieldWithMetaString.builder().setValue(qty.currency).build())
                        .build());
            }
            pq.addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder()
                    .setValue(nqs.build())
                    .setMeta(QuantityMapper.locationMeta("quantity-" + oneBased))
                    .build());
        }

        // Observable (underlier)
        Element underlyer = XmlUtils.child(leg, "underlyer");
        if (underlyer != null) {
            Observable obs;
            if (kind == Kind.CORRELATION) {
                obs = buildBasketObservable(underlyer);
            } else {
                obs = EquityOptionMapper.buildEquityObservable(underlyer);
            }
            if (obs != null) {
                pq.setObservable(FieldWithMetaObservable.builder()
                        .setValue(obs)
                        .setMeta(QuantityMapper.locationMeta("observable-" + oneBased))
                        .build());
            }
        }

        out.add(pq.build());
        return out;
    }

    private record QuantityInfo(BigDecimal amount, String currency) {}

    private QuantityInfo extractQuantity(Element amount, Kind kind) {
        if (amount == null) return null;
        Element amountEl = null;
        switch (kind) {
            case VARIANCE -> {
                Element variance = XmlUtils.child(amount, "variance");
                if (variance != null) amountEl = XmlUtils.child(variance, "varianceAmount");
            }
            case VOLATILITY -> {
                Element volatility = XmlUtils.child(amount, "volatility");
                if (volatility != null) {
                    // CDM uses vegaNotionalAmount as quantity for volatility swaps
                    String vega = XmlUtils.childText(volatility, "vegaNotionalAmount");
                    if (vega != null) {
                        // need to find a sibling currency from somewhere; look at leg.settlementCurrency
                        return new QuantityInfo(new BigDecimal(vega), inferCurrencyFromLeg(amount));
                    }
                }
            }
            case CORRELATION -> {
                Element correlation = XmlUtils.child(amount, "correlation");
                if (correlation != null) amountEl = XmlUtils.child(correlation, "notionalAmount");
            }
        }
        if (amountEl == null) return null;
        String amt = XmlUtils.childText(amountEl, "amount");
        String ccy = XmlUtils.childText(amountEl, "currency");
        if (amt == null) return null;
        return new QuantityInfo(new BigDecimal(amt), ccy);
    }

    private String inferCurrencyFromLeg(Element amount) {
        // amount.parent is leg
        if (amount == null) return null;
        org.w3c.dom.Node parent = amount.getParentNode();
        if (parent instanceof Element legEl) {
            String ccy = XmlUtils.childText(legEl, "settlementCurrency");
            if (ccy != null) return ccy;
        }
        return null;
    }

    private Observable buildBasketObservable(Element underlyer) {
        Element basket = XmlUtils.child(underlyer, "basket");
        if (basket == null) return null;

        Basket.BasketBuilder bb = Basket.builder();
        int idx = 1;
        for (Element bc : XmlUtils.children(basket, "basketConstituent")) {
            Observable obs = buildEquityFromConstituent(bc);
            if (obs == null) continue;
            BasketConstituent.BasketConstituentBuilder bcb = BasketConstituent.builder()
                    .setAsset(obs.getAsset())
                    .setBasket(obs.getBasket())
                    .setIndex(obs.getIndex());
            FieldWithMetaBasketConstituent fwm = FieldWithMetaBasketConstituent.builder()
                    .setValue(bcb.build())
                    .setMeta(QuantityMapper.locationMeta("basketConstituent-" + idx))
                    .build();
            bb.addBasketConstituent(fwm);
            idx++;
        }
        return Observable.builder().setBasket(bb.build()).build();
    }

    private Observable buildEquityFromConstituent(Element bc) {
        Element equity = XmlUtils.child(bc, "equity");
        if (equity != null) return EquityOptionMapper.buildStockObservable(equity);
        Element index = XmlUtils.child(bc, "index");
        if (index != null) return EquityOptionMapper.buildIndexObservable(index);
        return null;
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
                rdb.setDateRelativeTo(com.rosetta.model.metafields.ReferenceWithMetaDate.builder()
                        .setExternalReference(drt.getAttribute("href")).build());
            }
            Element bcs = XmlUtils.child(relDate, "businessCenters");
            if (bcs != null) rdb.setBusinessCenters(DateMapper.buildBusinessCenters(bcs));

            AdjustableOrRelativeDate.AdjustableOrRelativeDateBuilder b =
                    AdjustableOrRelativeDate.builder().setRelativeDate(rdb.build());
            if (id != null && !id.isEmpty()) {
                b.setMeta(MetaFields.builder().setExternalKey(id).build());
            }
            return b.build();
        }
        return null;
    }

    private AdjustableOrAdjustedOrRelativeDate buildAdjustableOrAdjustedOrRelativeDate(Element dateEl) {
        if (dateEl == null) return null;
        Element adjDate = XmlUtils.child(dateEl, "adjustableDate");
        Element relDate = XmlUtils.child(dateEl, "relativeDate");

        AdjustableOrAdjustedOrRelativeDate.AdjustableOrAdjustedOrRelativeDateBuilder b =
                AdjustableOrAdjustedOrRelativeDate.builder();
        if (adjDate != null) {
            String iso = XmlUtils.childText(adjDate, "unadjustedDate");
            if (iso != null) b.setUnadjustedDate(DateMapper.parse(iso));
            BusinessDayAdjustments bda = DateMapper.businessDayAdjustments(
                    XmlUtils.child(adjDate, "dateAdjustments"));
            if (bda != null) b.setDateAdjustments(bda);
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
                rdb.setDateRelativeTo(com.rosetta.model.metafields.ReferenceWithMetaDate.builder()
                        .setExternalReference(drt.getAttribute("href")).build());
            }
            Element bcs = XmlUtils.child(relDate, "businessCenters");
            if (bcs != null) rdb.setBusinessCenters(DateMapper.buildBusinessCenters(bcs));
            b.setRelativeDate(rdb.build());
            return b.build();
        }
        return null;
    }

    private List<ProductTaxonomy> buildTaxonomy(Element leg, Kind kind, boolean isDispersion) {
        List<ProductTaxonomy> out = new ArrayList<>();

        String qualifier;
        if (isDispersion) {
            qualifier = "EquitySwap_ParameterReturnDispersion";
        } else {
            String suffix;
            if (kind == Kind.CORRELATION) {
                suffix = "Basket";
            } else {
                Element underlyer = XmlUtils.child(leg, "underlyer");
                boolean isIndex = false;
                if (underlyer != null) {
                    Element su = XmlUtils.child(underlyer, "singleUnderlyer");
                    if (su != null) isIndex = XmlUtils.child(su, "index") != null;
                }
                suffix = isIndex ? "Index" : "SingleName";
            }
            String paramType = switch (kind) {
                case VARIANCE -> "Variance";
                case VOLATILITY -> "Volatility";
                case CORRELATION -> "Correlation";
            };
            qualifier = "EquitySwap_ParameterReturn" + paramType + "_" + suffix;
        }

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

    private static String camelToUpper(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase();
    }
}
