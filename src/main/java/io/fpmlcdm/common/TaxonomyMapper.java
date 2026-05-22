package io.fpmlcdm.common;

import cdm.base.staticdata.asset.common.ProductTaxonomy;
import cdm.base.staticdata.asset.common.TaxonomySourceEnum;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * Produces the {@code product.taxonomy[]} list (ISDA productQualifier).
 *
 * Heuristic: inspect FpML swapStreams under {@code <swap>}:
 *   - fixed leg + floating leg → InterestRate_IRSwap_FixedFloat
 *   - both floating legs       → InterestRate_IRSwap_BasisSwap
 *   - floating index name contains "OIS" → InterestRate_IRSwap_FixedFloat_OIS
 */
public final class TaxonomyMapper {

    private TaxonomyMapper() {}

    public static List<ProductTaxonomy> map(Element trade) {
        List<ProductTaxonomy> out = new ArrayList<>();
        String qualifier = computeQualifier(trade);
        if (qualifier == null) return out;
        ProductTaxonomy pt = ProductTaxonomy.builder()
                .setSource(TaxonomySourceEnum.ISDA)
                .setProductQualifier(qualifier)
                .build();
        out.add(pt);
        return out;
    }

    private static String computeQualifier(Element trade) {
        Element swap = XmlUtils.child(trade, "swap");
        if (swap == null) return null;
        List<Element> streams = XmlUtils.children(swap, "swapStream");
        boolean hasFixed = false;
        boolean hasFloating = false;
        boolean hasOis = false;
        for (Element s : streams) {
            Element calc = XmlUtils.path(s, "calculationPeriodAmount", "calculation");
            if (calc == null) continue;
            if (XmlUtils.child(calc, "fixedRateSchedule") != null) hasFixed = true;
            Element frc = XmlUtils.child(calc, "floatingRateCalculation");
            if (frc != null) {
                hasFloating = true;
                String idx = XmlUtils.childText(frc, "floatingRateIndex");
                if (idx != null && idx.toUpperCase().contains("OIS")) hasOis = true;
            }
        }
        if (hasFixed && hasFloating) {
            return hasOis ? "InterestRate_IRSwap_FixedFloat_OIS" : "InterestRate_IRSwap_FixedFloat";
        }
        if (hasFloating && !hasFixed) {
            return "InterestRate_IRSwap_BasisSwap";
        }
        return "InterestRate_IRSwap_FixedFloat";
    }
}
