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
     * @param floatingStream swapStream with floatingRateCalculation, or null
     * @param fixedStream    swapStream with fixedRateSchedule, or null
     */
    public static List<PriceQuantity> map(Element floatingStream, Element fixedStream) {
        List<PriceQuantity> out = new ArrayList<>();
        if (floatingStream != null) {
            out.add(buildFloating(floatingStream));
        }
        if (fixedStream != null) {
            out.add(buildFixed(fixedStream));
        }
        return out;
    }

    private static PriceQuantity buildFloating(Element stream) {
        PriceQuantity.PriceQuantityBuilder b = PriceQuantity.builder();

        // Quantity
        Element notional = XmlUtils.path(stream, "calculationPeriodAmount", "calculation",
                "notionalSchedule", "notionalStepSchedule");
        BigDecimal amount = decimalText(notional, "initialValue");
        UnitType unit = currencyUnit(XmlUtils.child(notional, "currency"));
        FieldWithMetaNonNegativeQuantitySchedule qty = FieldWithMetaNonNegativeQuantitySchedule.builder()
                .setValue(NonNegativeQuantitySchedule.builder()
                        .setValue(amount)
                        .setUnit(unit)
                        .build())
                .setMeta(locationMeta("quantity-1"))
                .build();
        b.addQuantity(qty);

        // Observable
        Element frc = XmlUtils.path(stream, "calculationPeriodAmount", "calculation", "floatingRateCalculation");
        String idxName = XmlUtils.childText(frc, "floatingRateIndex");

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
                .setMeta(locationMeta("InterestRateIndex-1"))
                .build();

        Index index = Index.builder()
                .setInterestRateIndex(iriField)
                .build();
        Observable observable = Observable.builder().setIndex(index).build();
        FieldWithMetaObservable observableField = FieldWithMetaObservable.builder()
                .setValue(observable)
                .setMeta(locationMeta("observable-1"))
                .build();
        b.setObservable(observableField);

        return b.build();
    }

    private static PriceQuantity buildFixed(Element stream) {
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

        FieldWithMetaPriceSchedule price = FieldWithMetaPriceSchedule.builder()
                .setValue(PriceSchedule.builder()
                        .setValue(rate)
                        .setUnit(unit)
                        .setPerUnitOf(perUnit)
                        .setPriceType(PriceTypeEnum.INTEREST_RATE)
                        .build())
                .setMeta(locationMeta("price-1"))
                .build();
        b.addPrice(price);

        // Quantity
        BigDecimal amount = decimalText(notional, "initialValue");
        FieldWithMetaNonNegativeQuantitySchedule qty = FieldWithMetaNonNegativeQuantitySchedule.builder()
                .setValue(NonNegativeQuantitySchedule.builder()
                        .setValue(amount)
                        .setUnit(currencyUnit(ccyEl))
                        .build())
                .setMeta(locationMeta("quantity-2"))
                .build();
        b.addQuantity(qty);

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
