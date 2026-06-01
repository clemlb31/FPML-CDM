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
        Element swap = XmlUtils.child(trade, "swap");
        if (swap == null) return new ArrayList<>();
        return mapForSwap(swap);
    }

    /** Maps taxonomy directly from a {@code <swap>} element (e.g. the swap inside a swaption). */
    public static List<ProductTaxonomy> mapForSwap(Element swap) {
        List<ProductTaxonomy> out = new ArrayList<>();
        if (swap == null) return out;

        Element primaryAssetClass = XmlUtils.child(swap, "primaryAssetClass");
        Element productType = XmlUtils.child(swap, "productType");  // first only (for legacy fallback checks)

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

        // Multiple productType elements: CFI (iso10962), EMIR (emir-contract-type), Other for the rest.
        // A single colon-form productType ("InterestRate:IRSwap:Foo") is emitted with source=Other.
        List<Element> productTypes = XmlUtils.children(swap, "productType");
        for (Element ptEl : productTypes) {
            String ptScheme = ptEl.getAttribute("productTypeScheme");
            String ptValue = ptEl.getTextContent().trim();
            FieldWithMetaString.FieldWithMetaStringBuilder name = FieldWithMetaString.builder().setValue(ptValue);
            if (ptScheme != null && !ptScheme.isEmpty()) {
                name.setMeta(MetaFields.builder().setScheme(ptScheme).build());
            }
            TaxonomyValue tv = TaxonomyValue.builder().setName(name.build()).build();
            String schemeLower = ptScheme == null ? "" : ptScheme.toLowerCase();
            TaxonomySourceEnum src;
            if (schemeLower.contains("iso10962")) src = TaxonomySourceEnum.CFI;
            else if (schemeLower.contains("emir-contract-type")) src = TaxonomySourceEnum.EMIR;
            else if (ptScheme == null || ptScheme.isEmpty()) src = TaxonomySourceEnum.OTHER;
            else if (productTypes.size() == 1) src = TaxonomySourceEnum.ISDA;  // single legacy productType
            else src = TaxonomySourceEnum.OTHER;
            ProductTaxonomy.ProductTaxonomyBuilder b = ProductTaxonomy.builder()
                    .setSource(src)
                    .setValue(tv);
            out.add(b.build());
        }

        // For inflation swaps, when XML already carries a structured productType (e.g.
        // "InterestRate:IRSwap:Inflation" colon-separated, or a scheme attr), we don't synthesise
        // an additional ISDA productQualifier — the productType already serves the same purpose.
        boolean hasInflationLeg = hasInflationLeg(swap);
        boolean skipQualifier = false;
        if (hasInflationLeg && productType != null) {
            String ptText = productType.getTextContent() == null ? "" : productType.getTextContent();
            String ptScheme = productType.getAttribute("productTypeScheme");
            if (ptText.contains(":") || (ptScheme != null && !ptScheme.isEmpty())) {
                skipQualifier = true;
            }
        }
        // Same rule for known-amount swaps with a colon-form productType: skip the auto qualifier.
        boolean hasKnownAmount = false;
        for (Element s : XmlUtils.children(swap, "swapStream")) {
            if (XmlUtils.path(s, "calculationPeriodAmount", "knownAmountSchedule") != null) {
                hasKnownAmount = true;
                break;
            }
        }
        if (hasKnownAmount && productType != null) {
            String ptText = productType.getTextContent() == null ? "" : productType.getTextContent();
            if (ptText.contains(":")) {
                skipQualifier = true;
            }
        }

        if (!skipQualifier) {
            String qualifier = computeQualifier(swap);
            if (qualifier != null) {
                out.add(ProductTaxonomy.builder()
                        .setSource(TaxonomySourceEnum.ISDA)
                        .setProductQualifier(qualifier)
                        .build());
            }
        }
        return out;
    }

    private static boolean hasInflationLeg(Element swap) {
        for (Element s : XmlUtils.children(swap, "swapStream")) {
            Element calc = XmlUtils.path(s, "calculationPeriodAmount", "calculation");
            if (calc != null && XmlUtils.child(calc, "inflationRateCalculation") != null) return true;
        }
        return false;
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
        boolean hasInflation = false;
        boolean hasOis = false;
        int floatingCount = 0;
        for (Element s : streams) {
            // knownAmountSchedule = pre-computed fixed payments (counts as fixed leg for qualifier)
            if (XmlUtils.path(s, "calculationPeriodAmount", "knownAmountSchedule") != null) {
                hasFixed = true;
            }
            Element calc = XmlUtils.path(s, "calculationPeriodAmount", "calculation");
            if (calc == null) continue;
            if (XmlUtils.child(calc, "fixedRateSchedule") != null) hasFixed = true;
            if (XmlUtils.child(calc, "inflationRateCalculation") != null) {
                hasInflation = true;
                floatingCount++;  // inflation legs count as floating for qualifier purposes
            }
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
        // With knownAmountSchedule, payments are pre-computed; don't tag as ZC even if period=T
        boolean hasKnownAmount = false;
        for (Element s : streams) {
            if (XmlUtils.path(s, "calculationPeriodAmount", "knownAmountSchedule") != null) {
                hasKnownAmount = true;
                break;
            }
        }
        boolean isZeroCoupon = !hasKnownAmount && isZeroCouponSwap(streams);
        boolean crossCurrency = isCrossCurrency(streams);

        boolean isInflationZc = hasInflation && isInflationZeroCoupon(streams);

        // Inflation handling (only when NOT cross-currency — cross-currency falls back below).
        if (hasInflation && !crossCurrency) {
            if (hasFixed) {
                // fixed vs inflation: ZC has no qualifier, YoY/recurring uses YearOn_Year
                if (isInflationZc) return null;
                return "InterestRate_InflationSwap_FixedFloat_YearOn_Year";
            }
            // float-vs-float-inflation basis
            if (floatingCount >= 2) {
                if (isInflationZc) return "InterestRate_InflationSwap_Basis_ZeroCoupon";
                return "InterestRate_InflationSwap_Basis_YearOn_Year";
            }
        }

        String family = crossCurrency ? "InterestRate_CrossCurrency" : "InterestRate_IRSwap";

        if (hasFixed && (hasFloating || hasInflation)) {
            // OIS takes precedence (its payment frequency is also a single period).
            if (hasOis && !crossCurrency) return family + "_FixedFloat_OIS";
            if (isZeroCoupon && !crossCurrency) return family + "_FixedFloat_ZeroCoupon";
            return family + "_FixedFloat";
        }
        if (floatingCount >= 2 && !hasFixed) {
            if (crossCurrency) return "InterestRate_CrossCurrency_Basis";
            return hasOis ? "InterestRate_IRSwap_Basis_OIS" : "InterestRate_IRSwap_Basis";
        }
        if (hasFixed && !hasFloating && !hasInflation) {
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
            // knownAmountSchedule has its own currency
            String kaCcy = XmlUtils.pathText(s,
                    "calculationPeriodAmount", "knownAmountSchedule", "currency");
            if (kaCcy != null) ccys.add(kaCcy);
            // FX-linked variable notional: collect varyingNotionalCurrency too.
            // fxLinkedNotionalSchedule is a sibling of notionalSchedule under <calculation>,
            // not a child of notionalSchedule.
            String vc = XmlUtils.pathText(s,
                    "calculationPeriodAmount", "calculation",
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

    /** For inflation swaps: ZC iff none of the legs has a sub-annual payment frequency. */
    private static boolean isInflationZeroCoupon(List<Element> streams) {
        if (streams.isEmpty()) return false;
        for (Element s : streams) {
            String period = XmlUtils.pathText(s, "paymentDates", "paymentFrequency", "period");
            String mult = XmlUtils.pathText(s, "paymentDates", "paymentFrequency", "periodMultiplier");
            if ("T".equals(period)) continue;
            if (period == null || mult == null) return false;
            int m;
            try { m = Integer.parseInt(mult); } catch (NumberFormatException e) { return false; }
            // YoY: yearly (1Y) or sub-annual (M, W, D). ZC: multi-year payments.
            if ("Y".equals(period) && m <= 1) return false;
            if ("M".equals(period) || "W".equals(period) || "D".equals(period)) return false;
        }
        return true;
    }
}
