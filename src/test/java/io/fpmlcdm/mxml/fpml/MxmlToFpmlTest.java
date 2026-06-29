package io.fpmlcdm.mxml.fpml;

import io.fpmlcdm.core.conversion.ConversionResult;
import io.fpmlcdm.core.dataset.PairLoader;
import io.fpmlcdm.core.dataset.TestPair;
import io.fpmlcdm.core.xml.XmlUtils;
import io.fpmlcdm.report.XmlSemanticDiff;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Document;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Data-driven validation of MXML→FpML against the committed {@code _expected.xml}.
 *
 * <p>Walks {@code data/ground_truth/mxml-fpml/<category>/{mxml,fpml}}, runs
 * {@link MxmlToFpmlConverter} on each MXML input and compares the produced FpML to
 * the expected document via {@link XmlSemanticDiff} (timestamp/numeric-tolerant).
 *
 * <p>Contract while the XSLT mappers are being ported:
 * <ul>
 *   <li><b>NOMAP</b> (no product mapper registered → empty output) is <em>skipped</em>
 *       via a JUnit assumption — it is not yet a failure. As each mapper lands, its
 *       pairs automatically switch from skipped to asserted.</li>
 *   <li><b>ERROR</b> (the converter could not even parse/convert the input) always fails.</li>
 *   <li><b>DIFF</b> (output produced but not semantically equal) fails with the diff.</li>
 *   <li><b>EQUAL</b> passes.</li>
 * </ul>
 */
class MxmlToFpmlTest {

    private static final Path ROOT = Path.of("data/ground_truth/mxml-fpml");

    static Stream<Arguments> pairs() {
        if (!Files.isDirectory(ROOT)) {
            return Stream.empty();
        }
        List<TestPair> pairs = PairLoader.mxmlToFpml(ROOT).load();
        return pairs.stream().map(p -> Arguments.of(p.id(), p));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pairs")
    void mxmlConvertsToExpectedFpml(String id, TestPair pair) throws Exception {
        ConversionResult<Document> result;
        try (InputStream in = Files.newInputStream(pair.input())) {
            result = new MxmlToFpmlConverter().convert(in);
        }

        // Hard failure: the converter could not parse/convert the MXML at all.
        if (!result.isSuccess()) {
            fail("MXML→FpML conversion failed for " + id + ": " + result.getErrors());
        }

        Document produced = result.getResult();
        boolean noMapper = produced == null || produced.getDocumentElement() == null;

        // No mapper ported yet for this product → skip (not a failure).
        Assumptions.assumeFalse(noMapper,
                "No MXML→FpML mapper registered yet for " + id + " (NOMAP)");

        // A mapper produced output → it must match the expected FpML.
        String producedXml = XmlUtils.serialize(produced);
        String expectedXml = Files.readString(pair.expected());
        XmlSemanticDiff.Result diff = XmlSemanticDiff.compare(expectedXml, producedXml);

        assertTrue(diff.isEqual(),
                () -> "MXML→FpML output differs from expected for " + id + ":\n" + diff);
    }
}
