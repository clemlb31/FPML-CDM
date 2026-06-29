package io.fpmlcdm.mxml.fpml;

import io.fpmlcdm.core.conversion.ConversionResult;
import io.fpmlcdm.core.xml.XmlUtils;
import io.fpmlcdm.mxml.fpml.detect.MxmlProductDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.InputStream;

/**
 * MXML → FpML converter (Java port of the Murex XSLT specification).
 *
 * <p>Pure XML→XML: it has <b>no CDM dependency</b>, so it compiles and runs
 * even when the CDM build is unavailable. The produced FpML can then feed the
 * mature FpML→CDM pipeline (see {@code io.fpmlcdm.mxml.cdm.MxmlToCdmConverter}).
 *
 * <p><b>Status: skeleton.</b> Product mappers are registered in
 * {@link #registerDefaultMappers()} as they are ported from the XSLT spec
 * (build order in docs/mxml-fpml.md: vanilla swap first).
 */
public final class MxmlToFpmlConverter {

    private static final Logger log = LoggerFactory.getLogger(MxmlToFpmlConverter.class);

    private final MxmlProductDetector detector = new MxmlProductDetector();

    public MxmlToFpmlConverter() {
        registerDefaultMappers();
    }

    /**
     * Registers the ported product mappers. Empty for now — each mapper is
     * added here as its XSLT module is ported to {@code products/}.
     */
    private void registerDefaultMappers() {
        detector.register(new io.fpmlcdm.mxml.fpml.products.SwapMapper());
        // detector.register(new io.fpmlcdm.mxml.fpml.products.FraMapper());
        // ...
    }

    /**
     * Converts an MXML document to an FpML document.
     *
     * @param mxml the MXML input stream
     * @return a {@link ConversionResult} wrapping the produced FpML document
     *         plus any errors/warnings collected during mapping
     */
    public ConversionResult<Document> convert(InputStream mxml) {
        MxmlToFpmlContext context = new MxmlToFpmlContext();
        try {
            Document mxmlDoc = XmlUtils.parse(mxml);
            Document fpmlDoc = XmlUtils.newDocument();

            Element mxmlRoot = mxmlDoc.getDocumentElement();
            if (mxmlRoot == null) {
                context.addError("Empty MXML document (no root element)");
                return ConversionResult.failure(context.getErrorHandler().getErrors(),
                                                 context.getErrorHandler().getWarnings());
            }

            MxmlProductMapper mapper = detector.dispatch(mxmlRoot);
            if (mapper == null) {
                context.addWarning("No MXML→FpML mapper registered for this product "
                    + "(detector recognized " + detector.registeredCount() + " types)");
                return ConversionResult.of(fpmlDoc,
                    context.getErrorHandler().getErrors(),
                    context.getErrorHandler().getWarnings());
            }

            Element product = mapper.map(fpmlDoc, mxmlRoot, context);
            if (product != null) {
                fpmlDoc.appendChild(product);
            }

            return ConversionResult.of(fpmlDoc,
                context.getErrorHandler().getErrors(),
                context.getErrorHandler().getWarnings());

        } catch (Exception e) {
            log.error("MXML→FpML conversion failed", e);
            context.addError("Conversion failed: " + e.getMessage());
            return ConversionResult.failure(context.getErrorHandler().getErrors(),
                                             context.getErrorHandler().getWarnings());
        }
    }

    public MxmlProductDetector getDetector() {
        return detector;
    }
}
