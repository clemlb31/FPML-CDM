package io.fpmlcdm.mxml.fpml.detect;

import io.fpmlcdm.mxml.fpml.MxmlProductMapper;
import io.fpmlcdm.core.xml.XmlUtils;
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
     * Extracts the MXML product discriminator from the {@code <MxML>} root by
     * reading {@code trades/trade/tradeHeader/tradeCategory/typology}
     * (e.g. {@code IRS}, {@code FRA}, {@code CF}, {@code OSWP}).
     *
     * <p>Accepts either the {@code <MxML>} root or a {@code <trade>} element.
     */
    String detectProductType(Element node) {
        if (node == null) return null;

        Element trade;
        if ("trade".equals(node.getLocalName()) || "trade".equals(node.getNodeName())) {
            trade = node;
        } else {
            Element trades = XmlUtils.getFirstChildElement(node, "trades");
            trade = trades != null ? XmlUtils.getFirstChildElement(trades, "trade") : null;
        }
        if (trade == null) return null;

        Element tradeHeader = XmlUtils.getFirstChildElement(trade, "tradeHeader");
        Element tradeCategory = tradeHeader != null
                ? XmlUtils.getFirstChildElement(tradeHeader, "tradeCategory") : null;
        if (tradeCategory == null) return null;

        String typology = XmlUtils.getTextContent(tradeCategory, "typology");
        return (typology != null && !typology.isEmpty()) ? typology : null;
    }

    public boolean hasMapperFor(String productType) {
        return productType != null && mappers.containsKey(productType.toUpperCase());
    }

    public int registeredCount() {
        return mappers.size();
    }
}
