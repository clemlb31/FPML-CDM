package io.fpmlcdm.common;

import cdm.base.staticdata.asset.common.AssetClassEnum;
import cdm.base.staticdata.asset.common.ProductTaxonomy;
import cdm.base.staticdata.asset.common.TaxonomySourceEnum;
import cdm.base.staticdata.asset.common.TaxonomyValue;
import cdm.base.staticdata.asset.common.metafields.FieldWithMetaAssetClassEnum;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * Produces the {@code product.taxonomy[]} list.
 *
 * Depending on what FpML &lt;swap&gt; reveals, we emit up to 3 entries:
 *   [0] primaryAssetClass (e.g. InterestRate)  – present iff FpML &lt;primaryAssetClass&gt;
 *   [1] productType (ISDA source, wrapped TaxonomyValue) – present iff FpML &lt;productType&gt;
 *   [2] productQualifier (ISDA source) – always (derived heuristically)
 */
public final class TaxonomyMapper {

    private TaxonomyMapper() {}

    public static List<ProductTaxonomy> map(Element trade) {
        List<ProductTaxonomy> out = new ArrayList<>();
        Element swap = XmlUtils.child(trade, "swap");
        if (swap == null) return out;

        Element primaryAssetClass = XmlUtils.child(swap, "primaryAssetClass");
        Element productType = XmlUtils.child(swap, "productType");

        if (primaryAssetClass != null) {
            String pacScheme = primaryAssetClass.getAttribute("assetClassScheme");
            String pacValue = primaryAssetClass.getTextContent().trim();
            AssetClassEnum ac = mapAssetClass(pacValue);
            FieldWithMetaAssetClassEnum.FieldWithMetaAssetClassEnumBuilder ab =
                    FieldWithMetaAssetClassEnum.builder();
            if (ac != null) ab.setValue(ac);
            if (pacScheme != null && !pacScheme.isEmpty()) {
                ab.setMeta(MetaFields.builder().setScheme(pacScheme).build());
            }
            out.add(ProductTaxonomy.builder().setPrimaryAssetClass(ab.build()).build());
        }

        if (productType != null) {
            String ptScheme = productType.getAttribute("productTypeScheme");
            String ptValue = productType.getTextContent().trim();
            FieldWithMetaString.FieldWithMetaStringBuilder name = FieldWithMetaString.builder().setValue(ptValue);
            if (ptScheme != null && !ptScheme.isEmpty()) {
                name.setMeta(MetaFields.builder().setScheme(ptScheme).build());
            }
            TaxonomyValue tv = TaxonomyValue.builder().setName(name.build()).build();
            // Source = ISDA when the productType carries an FpML scheme; Other otherwise.
            TaxonomySourceEnum src = (ptScheme != null && !ptScheme.isEmpty())
                    ? TaxonomySourceEnum.ISDA : TaxonomySourceEnum.OTHER;
            ProductTaxonomy.ProductTaxonomyBuilder b = ProductTaxonomy.builder()
                    .setSource(src)
                    .setValue(tv);
            out.add(b.build());
        }

        String qualifier = computeQualifier(swap);
        if (qualifier != null) {
            out.add(ProductTaxonomy.builder()
                    .setSource(TaxonomySourceEnum.ISDA)
                    .setProductQualifier(qualifier)
                    .build());
        }
        return out;
    }

    private static AssetClassEnum mapAssetClass(String value) {
        if (value == null) return null;
        try { return AssetClassEnum.fromDisplayName(value); }
        catch (Exception ignored) {}
        try { return AssetClassEnum.valueOf(value.toUpperCase()); }
        catch (Exception ignored) {}
        return null;
    }

    private static String computeQualifier(Element swap) {
        List<Element> streams = XmlUtils.children(swap, "swapStream");
        boolean hasFixed = false;
        boolean hasFloating = false;
        boolean hasOis = false;
        int floatingCount = 0;
        for (Element s : streams) {
            Element calc = XmlUtils.path(s, "calculationPeriodAmount", "calculation");
            if (calc == null) continue;
            if (XmlUtils.child(calc, "fixedRateSchedule") != null) hasFixed = true;
            Element frc = XmlUtils.child(calc, "floatingRateCalculation");
            if (frc != null) {
                hasFloating = true;
                floatingCount++;
                String idx = XmlUtils.childText(frc, "floatingRateIndex");
                if (idx != null) {
                    String up = idx.toUpperCase();
                    // Treat as OIS only when the index name explicitly mentions OIS;
                    // overnight indices (EONIA, SOFR, ESTR, SONIA, …) alone use FixedFloat.
                    if (up.contains("OIS")) {
                        hasOis = true;
                    }
                }
            }
        }
        boolean isZeroCoupon = isZeroCouponSwap(streams);
        boolean crossCurrency = isCrossCurrency(streams);
        String family = crossCurrency ? "InterestRate_CrossCurrency" : "InterestRate_IRSwap";

        if (hasFixed && hasFloating) {
            // OIS takes precedence (its payment frequency is also a single period).
            if (hasOis && !crossCurrency) return family + "_FixedFloat_OIS";
            if (isZeroCoupon && !crossCurrency) return family + "_FixedFloat_ZeroCoupon";
            return family + "_FixedFloat";
        }
        if (floatingCount >= 2 && !hasFixed) {
            return crossCurrency ? "InterestRate_CrossCurrency_FloatFloat" : "InterestRate_IRSwap_Basis";
        }
        if (hasFixed && !hasFloating) {
            return crossCurrency ? "InterestRate_CrossCurrency_FixedFixed" : "InterestRate_IRSwap_FixedFixed";
        }
        return family + "_FixedFloat";
    }

    private static boolean isCrossCurrency(List<Element> streams) {
        java.util.Set<String> ccys = new java.util.HashSet<>();
        for (Element s : streams) {
            String ccy = XmlUtils.pathText(s,
                    "calculationPeriodAmount", "calculation", "notionalSchedule",
                    "notionalStepSchedule", "currency");
            if (ccy != null) ccys.add(ccy);
            // FX-linked variable notional: collect varyingNotionalCurrency too
            String vc = XmlUtils.pathText(s,
                    "calculationPeriodAmount", "calculation", "notionalSchedule",
                    "fxLinkedNotionalSchedule", "varyingNotionalCurrency");
            if (vc != null) ccys.add(vc);
        }
        return ccys.size() > 1;
    }

    /** A zero-coupon swap has payment frequency of one period of {@code T} (term) on every leg. */
    private static boolean isZeroCouponSwap(List<Element> streams) {
        if (streams.isEmpty()) return false;
        for (Element s : streams) {
            String period = XmlUtils.pathText(s, "paymentDates", "paymentFrequency", "period");
            if (!"T".equals(period)) return false;
        }
        return true;
    }
}
