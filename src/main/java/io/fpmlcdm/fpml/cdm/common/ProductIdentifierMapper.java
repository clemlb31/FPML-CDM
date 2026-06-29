package io.fpmlcdm.fpml.cdm.common;

import cdm.base.staticdata.asset.common.ProductIdentifier;
import cdm.base.staticdata.asset.common.ProductIdTypeEnum;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps FpML {@code <swap>/<productId>} elements into CDM {@link ProductIdentifier} entries.
 * Each {@code <productId>} becomes one entry with {@code source = "Other"}.
 */
public final class ProductIdentifierMapper {

    private ProductIdentifierMapper() {}

    public static List<ProductIdentifier> map(Element swap) {
        List<ProductIdentifier> out = new ArrayList<>();
        if (swap == null) return out;
        for (Element pid : XmlUtils.children(swap, "productId")) {
            String value = pid.getTextContent().trim();
            String scheme = pid.getAttribute("productIdScheme");
            FieldWithMetaString.FieldWithMetaStringBuilder fwms = FieldWithMetaString.builder().setValue(value);
            if (scheme != null && !scheme.isEmpty()) {
                fwms.setMeta(MetaFields.builder().setScheme(scheme).build());
            }
            ProductIdTypeEnum source = ProductIdTypeEnum.OTHER;
            if (scheme != null) {
                String s = scheme.toLowerCase();
                if (s.contains("isin")) source = ProductIdTypeEnum.ISIN;
                else if (s.contains("cusip")) source = ProductIdTypeEnum.CUSIP;
            }
            ProductIdentifier.ProductIdentifierBuilder b = ProductIdentifier.builder()
                    .setIdentifier(fwms.build())
                    .setSource(source);
            out.add(b.build());
        }
        return out;
    }
}
