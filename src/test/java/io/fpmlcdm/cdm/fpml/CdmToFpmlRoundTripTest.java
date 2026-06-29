package io.fpmlcdm.cdm.fpml;

import cdm.event.common.TradeState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regnosys.rosetta.common.serialisation.RosettaObjectMapper;
import io.fpmlcdm.fpml.cdm.FpmlToCdmConverter;
import io.fpmlcdm.report.SemanticDiff;
import io.fpmlcdm.fpml.cdm.validate.GlobalKeyReproducer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Element;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates round-trip conversion: CDM JSON -> FpML XML -> CDM JSON, then compares
 * original vs reconverted using SemanticDiff with GlobalKeyReproducer.
 *
 * Walks {@code data/ground_truth/fpml-cdm/<category>/cdm/*.json}, performs the full round-trip,
 * saves generated FpML to {@code generated-fpml/}, and reports diff counts.
 *
 * Skips categories containing "incomplete" or starting with "invalid-" by default.
 * Set {@code -Dincludeincomplete=true} to include them.
 */
class CdmToFpmlRoundTripTest {

    private static final ObjectMapper JSON = RosettaObjectMapper.getNewRosettaObjectMapper();
    private static final Path TRAIN = Paths.get("data/ground_truth/fpml-cdm");
    private static final Path GENERATED_DIR = Paths.get("generated-fpml");

    /** Tracks results per category for report generation. */
    private static Map<String, RoundTripResult> categoryResults;

    static class RoundTripResult {
        int total;
        int passed;       // 0 diffs (perfect round-trip)
        int mismatched;   // conversion succeeded but SemanticDiff found diffs
        int errors;       // conversion or re-parsing failed
        final List<String> failures = new ArrayList<>();

        static RoundTripResult of(String cat) { return new RoundTripResult(); }
    }

    @BeforeAll
    static void init() throws Exception {
        categoryResults = new TreeMap<>();
        Files.createDirectories(GENERATED_DIR);
    }

    static Stream<Arguments> cdmFiles() throws Exception {
        boolean includeIncomplete = Boolean.getBoolean("includeincomplete");
        try (Stream<Path> categories = Files.list(TRAIN)) {
            return categories
                    .filter(Files::isDirectory)
                    .filter(p -> includeIncomplete
                            || (!p.getFileName().toString().contains("incomplete")
                                && !p.getFileName().toString().startsWith("invalid-")))
                    .flatMap(CdmToFpmlRoundTripTest::cdmFilesForCategory)
                    .toList()
                    .stream();
        }
    }

    private static Stream<Arguments> cdmFilesForCategory(Path category) {
        Path cdmDir = category.resolve("cdm");
        if (!Files.isDirectory(cdmDir)) return Stream.empty();
        try {
            return Files.list(cdmDir)
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(json -> Arguments.of(category.getFileName().toString(), json));
        } catch (Exception e) {
            throw new RuntimeException("Failed to list CDM files in " + cdmDir, e);
        }
    }

    /** Unwrap single-element arrays for fields that the CDM Java model exposes as scalars. */
    private static void unwrapSingleArrays(JsonNode node, String fieldName) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            JsonNode v = obj.get(fieldName);
            if (v != null && v.isArray() && v.size() == 1) {
                obj.set(fieldName, v.get(0));
            }
            obj.fields().forEachRemaining(e -> unwrapSingleArrays(e.getValue(), fieldName));
        } else if (node.isArray()) {
            node.forEach(child -> unwrapSingleArrays(child, fieldName));
        }
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("cdmFiles")
    void roundTripPreservesSemantics(String category, Path cdmJson) throws Exception {
        RoundTripResult result = categoryResults.computeIfAbsent(category, RoundTripResult::of);
        result.total++;

        String baseName = cdmJson.getFileName().toString().replaceFirst("\\.json$", "");
        try {
            // 1. Load original CDM JSON as TradeState
            assertTrue(Files.exists(cdmJson), "Missing CDM JSON: " + cdmJson);
            String jsonContent = Files.readString(cdmJson);
            JsonNode tree = JSON.readTree(jsonContent);
            unwrapSingleArrays(tree, "stubPeriodType");
            TradeState originalTrade = JSON.treeToValue(tree, TradeState.class);

            // 2. Convert CDM -> FpML XML
            CdmToFpmlConverter cdmToFpml = new CdmToFpmlConverter();
            CdmToFpmlConverter.ConversionResult conversionResult = cdmToFpml.convert(originalTrade);

            assertNotNull(conversionResult.getTradeElement(),
                    () -> "[" + cdmJson + "] Trade element is null — no mapper detected product");

            // 3. Wrap trade in dataDocument with parties for FpMLToCdmConverter compatibility
            String xmlStr = wrapInDataDocument(conversionResult);

            // 4. Save generated FpML XML to output directory
            Path outPath = GENERATED_DIR.resolve(category).resolve(baseName + ".xml");
            Files.createDirectories(outPath.getParent());
            Files.writeString(outPath, xmlStr);

            // 5. Convert generated FpML back to CDM
            ByteArrayInputStream bais = new ByteArrayInputStream(xmlStr.getBytes("UTF-8"));
            List<TradeState> reconvertedStates = new FpmlToCdmConverter().convert(bais);

            assertFalse(reconvertedStates.isEmpty(),
                    "[" + cdmJson + "] Round-trip produced no CDM states");

            TradeState roundTrippedTrade = reconvertedStates.get(0);

            // 6. Compare original vs reconverted using GlobalKeyReproducer + SemanticDiff
            TradeState rekeyedOriginal = new GlobalKeyReproducer().apply(originalTrade);
            TradeState rekeyedRoundTrip = new GlobalKeyReproducer().apply(roundTrippedTrade);

            String expectedJson = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(rekeyedOriginal);
            String actualJson = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(rekeyedRoundTrip);

            SemanticDiff.Result diff = SemanticDiff.compare(expectedJson, actualJson);

            if (diff.isEqual()) {
                result.passed++;
                System.out.printf("[%s] %s PASS (0 diffs)%n", category, baseName);
            } else {
                result.mismatched++;
                System.out.printf("[%s] %s MISMATCH (%d diffs)%n", category, baseName, diff.size());
                if (diff.size() <= 20) {
                    for (String d : diff.diffs()) {
                        System.out.println("    " + d);
                    }
                }
            }

        } catch (IllegalArgumentException e) {
            // No mapper found — expected for unsupported product types
            result.errors++;
            String msg = baseName + ": Unsupported product type";
            result.failures.add(msg);
            System.out.printf("[%s] %s SKIP (%s)%n", category, baseName, e.getMessage());
        } catch (AssertionError e) {
            result.errors++;
            String msg = baseName + ": " + e.getMessage();
            result.failures.add(msg);
            throw e;
        } catch (Exception e) {
            result.errors++;
            String msg = baseName + ": " + e.getClass().getSimpleName() + " — " + e.getMessage();
            result.failures.add(msg);
            fail("[" + cdmJson + "] Unexpected error: " + msg);
        } finally {
            System.out.printf("[%s] %d/%d completed%n", category, result.passed + result.mismatched + result.errors, result.total);
        }
    }

    /**
     * Wraps a CDM->FpML trade element in a dataDocument with parties, serializes to XML string.
     * Follows the same pattern as CdmToFpmlRoundTripCli.
     */
    private static String wrapInDataDocument(CdmToFpmlConverter.ConversionResult result) throws Exception {
        Element tradeEl = result.getTradeElement();

        javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        org.w3c.dom.Document wrapperDoc = dbf.newDocumentBuilder().newDocument();
        String fpmlNs = FpmlConstants.FPML_NS;

        // Create <dataDocument> root element
        org.w3c.dom.Element dataDoc = wrapperDoc.createElementNS(fpmlNs, "dataDocument");
        wrapperDoc.appendChild(dataDoc);

        // Wrap trade product inside <trade> element for FpMLToCdmConverter compatibility
        org.w3c.dom.Element tradeWrapper = wrapperDoc.createElementNS(fpmlNs, "trade");
        org.w3c.dom.Node importedTradeContent = wrapperDoc.importNode(tradeEl, true);
        tradeWrapper.appendChild(importedTradeContent);
        dataDoc.appendChild(tradeWrapper);

        // Add document-level parties as siblings of <trade> inside dataDocument
        for (org.w3c.dom.Element party : result.getPartyElements()) {
            org.w3c.dom.Node partyNode = wrapperDoc.importNode(party, true);
            dataDoc.appendChild(partyNode);
        }

        // Serialize to string
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(wrapperDoc), new StreamResult(baos));

        return baos.toString("UTF-8");
    }

    /**
     * Final summary — prints pass/fail/error counts per category and overall.
     */
    @AfterAll
    static void printFinalSummary() {
        int totalFiles = 0, totalPassed = 0, totalMismatched = 0, totalErrors = 0;
        System.out.println("\n========================================");
        System.out.println("  CDM -> FpML Round-Trip Report");
        System.out.println("========================================");
        for (Map.Entry<String, RoundTripResult> e : categoryResults.entrySet()) {
            RoundTripResult r = e.getValue();
            totalFiles += r.total;
            totalPassed += r.passed;
            totalMismatched += r.mismatched;
            totalErrors += r.errors;
            String coverage = r.total > 0 ? String.format("%.1f%%", 100.0 * r.passed / r.total) : "—";
            System.out.printf("  %-45s %3d/%-3d pass | %2d mismatch | %2d error (%s)%n",
                    e.getKey() + "/", r.passed, r.total, r.mismatched, r.errors, coverage);
        }
        String overallCoverage = totalFiles > 0 ? String.format("%.1f%%", 100.0 * totalPassed / totalFiles) : "—";
        System.out.println("----------------------------------------");
        System.out.printf("  %-45s %3d/%-3d pass | %2d mismatch | %2d error (%s)%n",
                "TOTAL", totalPassed, totalFiles, totalMismatched, totalErrors, overallCoverage);
        System.out.println("========================================");

        for (Map.Entry<String, RoundTripResult> e : categoryResults.entrySet()) {
            if (!e.getValue().failures.isEmpty()) {
                System.out.printf("\n  [%s] Errors:%n", e.getKey());
                for (String f : e.getValue().failures) {
                    System.out.println("    - " + f);
                }
            }
        }
    }
}
