package io.fpmlcdm.mxml.fpml;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Maps a single MXML product subtree into the corresponding FpML product
 * element. Implementations are ported from the Murex XSLT specification
 * (see {@code knowledge_base/mxml-fpml/}). Pure XML→XML — no CDM dependency.
 */
public interface MxmlProductMapper {

    /**
     * @return the MXML product discriminator this mapper handles
     *         (e.g. {@code "SWAP"}, {@code "FRA"}, {@code "CF"}).
     */
    String mxmlProductType();

    /**
     * Builds the FpML product element from the MXML trade element.
     *
     * @param fpmlDoc the target FpML document (element factory)
     * @param mxmlTrade the source MXML trade element
     * @param context conversion state (ids, errors, warnings)
     * @return the produced FpML product element, attached to {@code fpmlDoc}
     */
    Element map(Document fpmlDoc, Element mxmlTrade, MxmlToFpmlContext context);
}
