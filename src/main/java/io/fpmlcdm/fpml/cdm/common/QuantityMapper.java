package io.fpmlcdm.fpml.cdm.common;

import cdm.base.math.NonNegativeQuantitySchedule;
import cdm.base.math.UnitType;
import cdm.base.math.metafields.FieldWithMetaNonNegativeQuantitySchedule;
import cdm.base.staticdata.asset.common.AssetClassEnum;
import cdm.base.staticdata.asset.common.AssetIdTypeEnum;
import cdm.base.staticdata.asset.common.AssetIdentifier;
import cdm.observable.asset.FloatingRateIndex;
import cdm.observable.asset.Index;
import cdm.observable.asset.InterestRateIndex;
import cdm.observable.asset.Observable;
import cdm.observable.asset.PriceSchedule;
import cdm.observable.asset.PriceTypeEnum;
import cdm.observable.asset.PriceQuantity;
import cdm.observable.asset.metafields.FieldWithMetaInterestRateIndex;
import cdm.observable.asset.metafields.FieldWithMetaObservable;
import cdm.observable.asset.metafields.FieldWithMetaPriceSchedule;
import cdm.base.datetime.Period;
import com.rosetta.model.lib.meta.Key;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds {@code tradeLot[0].priceQuantity[]} entries for an IRS:
 *   - Index 0: floating leg quantity + observable
 *   - Index 1: fixed leg quantity + price
 *
 * Cross-references back from the leg payouts target the meta.location values
 * placed on the inner quantity/price/observable/InterestRateIndex objects.
 */
public final class QuantityMapper {

    private QuantityMapper() {}

    /**
     * Build {@code tradeLot[0].priceQuantity[]} preserving FpML swapStream document order.
     * Each PriceQuantity has a {@code quantity-N} label matching the 1-based stream index.
     * Fixed legs also carry a {@code price-1} (the first fixed price encountered).
     * Floating legs carry an {@code observable} with {@code InterestRateIndex-1}.
     */
    public static List<PriceQuantity> map(List<Element> swapStreams, Map<Element, StreamLabels.Labels> labels) {
        List<PriceQuantity> out = new ArrayList<>();
        for (Element stream : swapStreams) {
            Element calc = XmlUtils.path(stream, "calculationPeriodAmount", "calculation");
            if (calc == null) continue;
            StreamLabels.Labels lbl = labels.get(stream);
            if (XmlUtils.child(calc, "floatingRateCalculation") != null) {
                Element fxLinked = XmlUtils.child(calc, "fxLinkedNotionalSchedule");
                if (fxLinked != null) {
                    // fxLinkedNotionalSchedule: replace the normal floating PQ with the fxLinked one
                    String varCcy = XmlUtils.childText(fxLinked, "varyingNotionalCurrency");
                    if (varCcy != null) {
                        out.add(buildFxLinkedPriceQuantity(stream, varCcy, lbl));
                    }
                } else {
                    out.add(buildFloating(stream, lbl, false));
                }
            } else if (XmlUtils.child(calc, "inflationRateCalculation") != null) {
                out.add(buildFloating(stream, lbl, true));
            } else {
                out.add(buildFixed(stream, lbl));
            }
        }
        return out;
    }

    private static PriceQuantity buildFloating(Element stream, StreamLabels.Labels lbl, boolean inflation) {
        PriceQuantity.PriceQuantityBuilder b = PriceQuantity.builder();

        // Quantity
        Element notional = XmlUtils.path(stream, "calculationPeriodAmount", "calculation",
                "notionalSchedule", "notionalStepSchedule");
        BigDecimal amount = decimalText(notional, "initialValue");
        Element ccyEl = XmlUtils.child(notional, "currency");
        UnitType unit = currencyUnit(ccyEl);
        NonNegativeQuantitySchedule.NonNegativeQuantityScheduleBuilder qsb = NonNegativeQuantitySchedule.builder()
                .setValue(amount).setUnit(unit);
        addDatedValues(qsb, notional);
        FieldWithMetaNonNegativeQuantitySchedule qty = FieldWithMetaNonNegativeQuantitySchedule.builder()
                .setValue(qsb.build())
                .setMeta(locationMeta(lbl.quantityLabel))
                .build();
        b.addQuantity(qty);

        // Observable (frc may actually be inflationRateCalculation)
        Element frc = XmlUtils.path(stream, "calculationPeriodAmount", "calculation",
                inflation ? "inflationRateCalculation" : "floatingRateCalculation");
        String idxName = XmlUtils.childText(frc, "floatingRateIndex");

        // Spread (optional, separate price entry — carries arithmeticOperator=Add per dataset)
        if (lbl.spreadPriceLabel != null) {
            Element ss = XmlUtils.child(frc, "spreadSchedule");
            BigDecimal spread = decimalText(ss, "initialValue");
            PriceSchedule.PriceScheduleBuilder psb = PriceSchedule.builder()
                    .setValue(spread)
                    .setUnit(unit)
                    .setPerUnitOf(currencyUnit(ccyEl))
                    .setPriceType(PriceTypeEnum.INTEREST_RATE)
                    .setArithmeticOperator(cdm.base.math.ArithmeticOperationEnum.ADD);
            addDatedValues(psb, ss);
            FieldWithMetaPriceSchedule spreadPrice = FieldWithMetaPriceSchedule.builder()
                    .setValue(psb.build())
                    .setMeta(locationMeta(lbl.spreadPriceLabel))
                    .build();
            b.addPrice(spreadPrice);
        }

        // Cap rate schedule (arithmeticOperator = Min)
        if (lbl.capPriceLabel != null) {
            Element cs = XmlUtils.child(frc, "capRateSchedule");
            BigDecimal cap = decimalText(cs, "initialValue");
            PriceSchedule.PriceScheduleBuilder psb = PriceSchedule.builder()
                    .setValue(cap)
                    .setUnit(unit)
                    .setPerUnitOf(currencyUnit(ccyEl))
                    .setPriceType(PriceTypeEnum.INTEREST_RATE)
                    .setArithmeticOperator(cdm.base.math.ArithmeticOperationEnum.MIN);
            addDatedValues(psb, cs);
            b.addPrice(FieldWithMetaPriceSchedule.builder()
                    .setValue(psb.build())
                    .setMeta(locationMeta(lbl.capPriceLabel))
                    .build());
        }
        // Floor rate schedule (arithmeticOperator = Max)
        if (lbl.floorPriceLabel != null) {
            Element fs = XmlUtils.child(frc, "floorRateSchedule");
            BigDecimal floor = decimalText(fs, "initialValue");
            PriceSchedule.PriceScheduleBuilder psb = PriceSchedule.builder()
                    .setValue(floor)
                    .setUnit(unit)
                    .setPerUnitOf(currencyUnit(ccyEl))
                    .setPriceType(PriceTypeEnum.INTEREST_RATE)
                    .setArithmeticOperator(cdm.base.math.ArithmeticOperationEnum.MAX);
            addDatedValues(psb, fs);
            b.addPrice(FieldWithMetaPriceSchedule.builder()
                    .setValue(psb.build())
                    .setMeta(locationMeta(lbl.floorPriceLabel))
                    .build());
        }

        InterestRateIndex.InterestRateIndexBuilder iriBld = InterestRateIndex.builder();
        Period indexTenor = null;
        Element tenor = XmlUtils.child(frc, "indexTenor");
        if (tenor != null) {
            String pm = XmlUtils.childText(tenor, "periodMultiplier");
            String pUnit = XmlUtils.childText(tenor, "period");
            Period.PeriodBuilder periodB = Period.builder();
            if (pm != null) periodB.setPeriodMultiplier(Integer.parseInt(pm));
            if (pUnit != null) periodB.setPeriod(EnumMappers.period(pUnit));
            indexTenor = periodB.build();
        }

        if (inflation) {
            cdm.observable.asset.InflationIndex.InflationIndexBuilder infl =
                    cdm.observable.asset.InflationIndex.builder()
                            .setAssetClass(AssetClassEnum.INTEREST_RATE)
                            .setInflationRateIndex(EnumMappers.inflationRateIndex(idxName));
            // Preserve the index identity (name) even when it maps to no InflationRateIndexEnum,
            // symmetric with the floating-rate branch — otherwise the CPI is unidentifiable.
            if (idxName != null && !idxName.isEmpty()) {
                infl.addIdentifier(AssetIdentifier.builder()
                        .setIdentifier(FieldWithMetaString.builder().setValue(idxName).build())
                        .setIdentifierType(AssetIdTypeEnum.OTHER)
                        .build());
            }
            if (indexTenor != null) infl.setIndexTenor(indexTenor);
            iriBld.setInflationIndex(infl.build());
        } else {
            FloatingRateIndex.FloatingRateIndexBuilder friBld = FloatingRateIndex.builder()
                    .setAssetClass(AssetClassEnum.INTEREST_RATE)
                    .setFloatingRateIndex(EnumMappers.floatingRateIndex(idxName))
                    .addIdentifier(AssetIdentifier.builder()
                            .setIdentifier(FieldWithMetaString.builder().setValue(idxName).build())
                            .setIdentifierType(AssetIdTypeEnum.OTHER)
                            .build());
            if (indexTenor != null) friBld.setIndexTenor(indexTenor);
            iriBld.setFloatingRateIndex(friBld.build());
        }

        InterestRateIndex iri = iriBld.build();

        FieldWithMetaInterestRateIndex iriField = FieldWithMetaInterestRateIndex.builder()
                .setValue(iri)
                .setMeta(locationMeta(lbl.indexLabel))
                .build();

        Index index = Index.builder()
                .setInterestRateIndex(iriField)
                .build();
        Observable observable = Observable.builder().setIndex(index).build();
        FieldWithMetaObservable observableField = FieldWithMetaObservable.builder()
                .setValue(observable)
                .setMeta(locationMeta(lbl.observableLabel))
                .build();
        b.setObservable(observableField);

        return b.build();
    }

    private static PriceQuantity buildFixed(Element stream, StreamLabels.Labels lbl) {
        PriceQuantity.PriceQuantityBuilder b = PriceQuantity.builder();

        // Price (fixed rate)
        Element calc = XmlUtils.path(stream, "calculationPeriodAmount", "calculation");
        Element frs = XmlUtils.child(calc, "fixedRateSchedule");
        BigDecimal rate = decimalText(frs, "initialValue");

        // Currency + notional amount: fxLinkedNotionalSchedule wins over normal notionalSchedule.
        // This handles CLP inflation swaps where the fixed leg notional is denominated in CLF.
        Element fxLinked = XmlUtils.child(calc, "fxLinkedNotionalSchedule");
        UnitType unit;
        UnitType perUnit;
        BigDecimal amount;
        Element notional;
        if (fxLinked != null) {
            String varCcy = XmlUtils.childText(fxLinked, "varyingNotionalCurrency");
            unit = UnitType.builder()
                    .setCurrency(FieldWithMetaString.builder().setValue(varCcy).build())
                    .build();
            perUnit = UnitType.builder()
                    .setCurrency(FieldWithMetaString.builder().setValue(varCcy).build())
                    .build();
            amount = decimalText(fxLinked, "initialValue");
            notional = fxLinked;
        } else {
            notional = XmlUtils.path(stream, "calculationPeriodAmount", "calculation",
                    "notionalSchedule", "notionalStepSchedule");
            Element ccyEl = XmlUtils.child(notional, "currency");
            unit = currencyUnit(ccyEl);
            perUnit = currencyUnit(ccyEl);
            amount = decimalText(notional, "initialValue");
        }

        PriceSchedule.PriceScheduleBuilder psb = PriceSchedule.builder()
                .setValue(rate)
                .setUnit(unit)
                .setPerUnitOf(perUnit)
                .setPriceType(PriceTypeEnum.INTEREST_RATE);
        addDatedValues(psb, frs);
        FieldWithMetaPriceSchedule price = FieldWithMetaPriceSchedule.builder()
                .setValue(psb.build())
                .setMeta(locationMeta(lbl.fixedPriceLabel))
                .build();
        b.addPrice(price);

        // Quantity
        NonNegativeQuantitySchedule.NonNegativeQuantityScheduleBuilder qsb = NonNegativeQuantitySchedule.builder()
                .setValue(amount).setUnit(unit);
        addDatedValues(qsb, notional);
        FieldWithMetaNonNegativeQuantitySchedule qty = FieldWithMetaNonNegativeQuantitySchedule.builder()
                .setValue(qsb.build())
                .setMeta(locationMeta(lbl.quantityLabel))
                .build();
        b.addQuantity(qty);

        return b.build();
    }

    /** Reads FpML {@code <step><stepDate>…<stepValue>…</step>} into MeasureSchedule.datedValue[]. */
    private static void addDatedValues(cdm.base.math.MeasureSchedule.MeasureScheduleBuilder builder, Element parent) {
        if (parent == null) return;
        for (Element step : XmlUtils.children(parent, "step")) {
            String date = XmlUtils.childText(step, "stepDate");
            String value = XmlUtils.childText(step, "stepValue");
            if (date == null || value == null) continue;
            builder.addDatedValue(cdm.base.math.DatedValue.builder()
                    .setDate(DateMapper.parse(date))
                    .setValue(new BigDecimal(value))
                    .build());
        }
    }

    /**
     * Builds a PriceQuantity for the fxLinked varying notional currency.
     * Contains a quantity with no value but with the currency unit, plus an observable.
     */
    private static PriceQuantity buildFxLinkedPriceQuantity(Element stream, String currency, StreamLabels.Labels lbl) {
        PriceQuantity.PriceQuantityBuilder b = PriceQuantity.builder();
        UnitType unit = UnitType.builder()
                .setCurrency(FieldWithMetaString.builder().setValue(currency).build())
                .build();
        // Quantity: pick up the initialValue from fxLinkedNotionalSchedule when present.
        Element fxLinked = XmlUtils.path(stream, "calculationPeriodAmount", "calculation", "fxLinkedNotionalSchedule");
        BigDecimal initialValue = fxLinked == null ? null : decimalText(fxLinked, "initialValue");
        NonNegativeQuantitySchedule.NonNegativeQuantityScheduleBuilder qsb = NonNegativeQuantitySchedule.builder()
                .setUnit(unit);
        if (initialValue != null) qsb.setValue(initialValue);
        b.addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder()
                .setValue(qsb.build())
                .setMeta(locationMeta(lbl.quantityLabel))
                .build());

        // Spread price (price-N) if present
        Element frcSpread = XmlUtils.path(stream, "calculationPeriodAmount", "calculation", "floatingRateCalculation");
        Element ss = frcSpread == null ? null : XmlUtils.child(frcSpread, "spreadSchedule");
        if (ss != null && lbl.spreadPriceLabel != null) {
            String spreadVal = XmlUtils.childText(ss, "initialValue");
            if (spreadVal != null) {
                PriceSchedule ps = PriceSchedule.builder()
                        .setValue(new BigDecimal(spreadVal))
                        .setUnit(unit)
                        .setPerUnitOf(unit)
                        .setPriceType(PriceTypeEnum.INTEREST_RATE)
                        .setArithmeticOperator(cdm.base.math.ArithmeticOperationEnum.ADD)
                        .build();
                b.addPrice(FieldWithMetaPriceSchedule.builder()
                        .setValue(ps)
                        .setMeta(locationMeta(lbl.spreadPriceLabel))
                        .build());
            }
        }

        // Include the floating rate observable (same as a regular floating leg)
        Element frc = XmlUtils.path(stream, "calculationPeriodAmount", "calculation", "floatingRateCalculation");
        String idxName = frc != null ? XmlUtils.childText(frc, "floatingRateIndex") : null;
        if (idxName != null) {
            cdm.base.staticdata.asset.rates.FloatingRateIndexEnum idx = null;
            try { idx = cdm.base.staticdata.asset.rates.FloatingRateIndexEnum.fromDisplayName(idxName); }
            catch (Exception ignored) {}
            FloatingRateIndex.FloatingRateIndexBuilder frib = FloatingRateIndex.builder()
                    .setAssetClass(AssetClassEnum.INTEREST_RATE);
            cdm.base.staticdata.asset.rates.metafields.FieldWithMetaFloatingRateIndexEnum friEnum = EnumMappers.floatingRateIndex(idxName);
            if (friEnum != null) frib.setFloatingRateIndex(friEnum);
            frib.addIdentifier(AssetIdentifier.builder()
                    .setIdentifier(FieldWithMetaString.builder().setValue(idxName).build())
                    .setIdentifierType(AssetIdTypeEnum.OTHER)
                    .build());
            Element tenor = XmlUtils.child(frc, "indexTenor");
            if (tenor != null) {
                String pm = XmlUtils.childText(tenor, "periodMultiplier");
                String per = XmlUtils.childText(tenor, "period");
                Period.PeriodBuilder tb = Period.builder();
                if (pm != null) tb.setPeriodMultiplier(Integer.parseInt(pm));
                if (per != null) tb.setPeriod(cdm.base.datetime.PeriodEnum.valueOf(per));
                frib.setIndexTenor(tb.build());
            }
            FieldWithMetaInterestRateIndex fwmIri = FieldWithMetaInterestRateIndex.builder()
                    .setValue(InterestRateIndex.builder()
                            .setFloatingRateIndex(frib.build())
                            .build())
                    .setMeta(locationMeta(lbl.indexLabel))
                    .build();
            Observable obs = Observable.builder()
                    .setIndex(Index.builder()
                            .setInterestRateIndex(fwmIri)
                            .build())
                    .build();
            b.setObservable(FieldWithMetaObservable.builder()
                    .setValue(obs)
                    .setMeta(locationMeta("observable-1"))
                    .build());
        }

        return b.build();
    }

    private static UnitType currencyUnit(Element ccyEl) {
        if (ccyEl == null) return null;
        String value = ccyEl.getTextContent().trim();
        String scheme = ccyEl.getAttribute("currencyScheme");
        FieldWithMetaString.FieldWithMetaStringBuilder ccyB = FieldWithMetaString.builder().setValue(value);
        if (scheme != null && !scheme.isEmpty()) {
            ccyB.setMeta(MetaFields.builder().setScheme(scheme).build());
        }
        return UnitType.builder().setCurrency(ccyB.build()).build();
    }

    private static BigDecimal decimalText(Element parent, String childName) {
        String t = XmlUtils.childText(parent, childName);
        return t == null ? null : new BigDecimal(t);
    }

    /** Build a MetaFields containing a single location key with scope=DOCUMENT and the given value. */
    public static MetaFields locationMeta(String value) {
        return MetaFields.builder()
                .addKey(Key.builder().setScope("DOCUMENT").setKeyValue(value).build())
                .build();
    }
}
