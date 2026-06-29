package io.fpmlcdm.mxml.fpml.detect;

import io.fpmlcdm.mxml.fpml.MxmlProductMapper;
import org.w3c.dom.Element;

import java.util.HashMap;
import java.util.Map;

/**
 * Routes an MXML trade element to the matching {@link MxmlProductMapper}
 * based on the MXML product discriminator.
 *
 * <p>Mappers are registered as they are ported from the Murex XSLT spec.
 * Build order (per docs/mxml-fpml.md): vanilla swap → FRA, cap/floor,
 * swaption, asset swap, cross-currency.
 */
public final class MxmlProductDetector {

    private final Map<String, MxmlProductMapper> mappers = new HashMap<>();

    public void register(MxmlProductMapper mapper) {
        mappers.put(mapper.mxmlProductType().toUpperCase(), mapper);
    }

    /**
     * @return the mapper for this MXML trade, or {@code null} if no product
     *         type is recognized (caller should record a warning).
     */
    public MxmlProductMapper dispatch(Element mxmlTrade) {
        String type = detectProductType(mxmlTrade);
        if (type == null) return null;
        return mappers.get(type.toUpperCase());
    }

    /**
     * Extracts the MXML product discriminator from a trade element.
     * Placeholder heuristic — to be refined against the real MXML schema
     * (see knowledge_base/mxml-fpml/).
     */
    String detectProductType(Element mxmlTrade) {
        if (mxmlTrade == null) return null;
        String tag = mxmlTrade.getAttribute("productType");
        return (tag != null && !tag.isEmpty()) ? tag : null;
    }

    public boolean hasMapperFor(String productType) {
        return productType != null && mappers.containsKey(productType.toUpperCase());
    }

    public int registeredCount() {
        return mappers.size();
    }
}
