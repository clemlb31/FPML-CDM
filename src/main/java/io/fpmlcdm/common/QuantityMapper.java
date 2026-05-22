package io.fpmlcdm.common;

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
                out.add(buildFloating(stream, lbl));
            } else {
                out.add(buildFixed(stream, lbl));
            }
        }
        return out;
    }

    private static PriceQuantity buildFloating(Element stream, StreamLabels.Labels lbl) {
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

        // Observable
        Element frc = XmlUtils.path(stream, "calculationPeriodAmount", "calculation", "floatingRateCalculation");
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
            Period.PeriodBuilder periodB = Period.builder();
            if (pm != null) periodB.setPeriodMultiplier(Integer.parseInt(pm));
            if (pUnit != null) periodB.setPeriod(EnumMappers.period(pUnit));
            friBld.setIndexTenor(periodB.build());
        }

        InterestRateIndex iri = InterestRateIndex.builder()
                .setFloatingRateIndex(friBld.build())
                .build();

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

        // Currency from notional (same currency for unit and perUnitOf)
        Element notional = XmlUtils.path(stream, "calculationPeriodAmount", "calculation",
                "notionalSchedule", "notionalStepSchedule");
        Element ccyEl = XmlUtils.child(notional, "currency");
        UnitType unit = currencyUnit(ccyEl);
        UnitType perUnit = currencyUnit(ccyEl);

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
        BigDecimal amount = decimalText(notional, "initialValue");
        NonNegativeQuantitySchedule.NonNegativeQuantityScheduleBuilder qsb = NonNegativeQuantitySchedule.builder()
                .setValue(amount).setUnit(currencyUnit(ccyEl));
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
