package io.fpmlcdm.mxml.cdm;

import cdm.event.common.TradeState;
import io.fpmlcdm.core.conversion.ConversionResult;
import io.fpmlcdm.core.xml.XmlUtils;
import io.fpmlcdm.fpml.cdm.FpmlToCdmConverter;
import io.fpmlcdm.mxml.fpml.MxmlToFpmlConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * MXML → CDM converter by <b>chaining</b>: {@code MXML → FpML → CDM}.
 *
 * <p>Reuses the mature FpML→CDM pipeline ({@link FpmlToCdmConverter}, 530/530)
 * on top of the MXML→FpML port ({@link MxmlToFpmlConverter}). No CDM mapping
 * logic is duplicated — see docs/mxml-cdm.md.
 *
 * <pre>
 * MXML ──(MxmlToFpmlConverter)──► FpML ──(FpmlToCdmConverter)──► List&lt;TradeState&gt;
 * </pre>
 *
 * <p>Errors/warnings from the MXML→FpML leg are propagated; the FpML→CDM leg
 * throws on hard failure (kept as-is to preserve the existing pipeline contract).
 */
public final class MxmlToCdmConverter {

    private static final Logger log = LoggerFactory.getLogger(MxmlToCdmConverter.class);

    private final MxmlToFpmlConverter mxmlToFpml = new MxmlToFpmlConverter();
    private final FpmlToCdmConverter fpmlToCdm = new FpmlToCdmConverter();

    /**
     * Converts MXML directly to CDM TradeStates via the FpML intermediate.
     *
     * @param mxml the MXML input stream
     * @return a {@link ConversionResult} wrapping the produced TradeStates plus
     *         any errors/warnings from the MXML→FpML leg
     */
    public ConversionResult<List<TradeState>> convert(InputStream mxml) {
        // Leg 1: MXML → FpML
        ConversionResult<Document> fpmlResult = mxmlToFpml.convert(mxml);
        if (!fpmlResult.isSuccess()) {
            return ConversionResult.failure(fpmlResult.getErrors(), fpmlResult.getWarnings());
        }

        // Leg 2: FpML → CDM (reuse the mature pipeline)
        try {
            String fpmlXml = XmlUtils.serialize(fpmlResult.getResult());
            try (InputStream fpmlStream =
                     new ByteArrayInputStream(fpmlXml.getBytes(StandardCharsets.UTF_8))) {
                List<TradeState> tradeStates = fpmlToCdm.convert(fpmlStream);
                return ConversionResult.success(tradeStates, fpmlResult.getWarnings());
            }
        } catch (Exception e) {
            log.error("FpML→CDM leg failed during MXML→CDM chaining", e);
            return ConversionResult.failure(
                Collections.singletonList(
                    io.fpmlcdm.core.conversion.ConversionError.mappingError(
                        "FpML→CDM leg failed: " + e.getMessage(), e)),
                fpmlResult.getWarnings());
        }
    }

    /** Exposes the intermediate FpML document (debugging / inspection). */
    public ConversionResult<Document> convertToFpml(InputStream mxml) {
        return mxmlToFpml.convert(mxml);
    }
}
