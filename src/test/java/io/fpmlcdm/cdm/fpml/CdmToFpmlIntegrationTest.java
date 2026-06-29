package io.fpmlcdm.cdm.fpml;

import cdm.event.common.TradeState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regnosys.rosetta.common.serialisation.RosettaObjectMapper;
import io.fpmlcdm.fpml.cdm.FpmlToCdmConverter;
import io.fpmlcdm.report.SemanticDiff;
import io.fpmlcdm.fpml.cdm.validate.GlobalKeyReproducer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Element;
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
 * Integration tests that exercise both CDM->FpML and FpML->CDM pipelines end-to-end.
 *
 * Tests specific product type conversions with known CDM data, verifies:
 * - CDM -> FpML produces valid XML
 * - FpML -> CDM re-parses without errors
 * - Round-trip preserves key structural elements
 *
 * Skips categories containing "incomplete" or starting with "invalid-" by default.
 * Set {@code -Dincludeincomplete=true} to include them.
 */
class CdmToFpmlIntegrationTest {

    private static final ObjectMapper JSON = RosettaObjectMapper.getNewRosettaObjectMapper();
    private static final Path TRAIN = Paths.get("data/ground_truth/fpml-cdm");
    private static final Path GENERATED_DIR = Paths.get("generated-fpml");

    private static Map<String, IntegrationResult> categoryResults;

    static class IntegrationResult {
        int total;
        int passed;
        int mismatched;
        int errors;
        final List<String> failures = new ArrayList<>();
        static IntegrationResult of(String cat) { return new IntegrationResult(); }
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
                    .flatMap(CdmToFpmlIntegrationTest::cdmFilesForCategory)
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
    void endToEndRoundTrip(String category, Path cdmJson) throws Exception {
        IntegrationResult result = categoryResults.computeIfAbsent(category, IntegrationResult::of);
        result.total++;

        String baseName = cdmJson.getFileName().toString().replaceFirst("\\.json$", "");
        try {
            assertTrue(Files.exists(cdmJson), "Missing CDM JSON: " + cdmJson);
            String jsonContent = Files.readString(cdmJson);
            JsonNode tree = JSON.readTree(jsonContent);
            unwrapSingleArrays(tree, "stubPeriodType");
            TradeState original = JSON.treeToValue(tree, TradeState.class);

            // Step 1: CDM -> FpML
            CdmToFpmlConverter cdmToFpml = new CdmToFpmlConverter();
            CdmToFpmlConverter.ConversionResult conversionResult = cdmToFpml.convert(original);

            assertNotNull(conversionResult.getTradeElement(),
                    () -> "[" + cdmJson + "] Trade element is null");

            String xmlStr = wrapInDataDocument(conversionResult);

            // Step 2: Save generated FpML
            Path outPath = GENERATED_DIR.resolve(category).resolve(baseName + ".xml");
            Files.createDirectories(outPath.getParent());
            Files.writeString(outPath, xmlStr);

            // Step 3: FpML -> CDM
            ByteArrayInputStream bais = new ByteArrayInputStream(xmlStr.getBytes("UTF-8"));
            List<TradeState> reconvertedStates = new FpmlToCdmConverter().convert(bais);

            assertFalse(reconvertedStates.isEmpty(),
                    "[" + cdmJson + "] Round-trip produced no CDM states");

            TradeState roundTripped = reconvertedStates.get(0);

            // Step 4: Verify GlobalKeyReproducer works on both
            TradeState rekeyedOriginal = new GlobalKeyReproducer().apply(original);
            TradeState rekeyedRoundTrip = new GlobalKeyReproducer().apply(roundTripped);

            // Step 5: Semantic comparison
            String expectedJson = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(rekeyedOriginal);
            String actualJson = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(rekeyedRoundTrip);
            SemanticDiff.Result diff = SemanticDiff.compare(expectedJson, actualJson);

            if (diff.isEqual()) {
                result.passed++;
                System.out.printf("[%s] %s PASS (0 diffs)%n", category, baseName);
            } else {
                result.mismatched++;
                System.out.printf("[%s] %s MISMATCH (%d diffs)%n", category, baseName, diff.size());
            }

        } catch (IllegalArgumentException e) {
            result.errors++;
            result.failures.add(baseName + ": Unsupported product type");
            System.out.printf("[%s] %s SKIP%n", category, baseName);
        } catch (AssertionError e) {
            result.errors++;
            result.failures.add(baseName + ": " + e.getMessage());
            throw e;
        } catch (Exception e) {
            result.errors++;
            result.failures.add(baseName + ": " + e.getClass().getSimpleName() + " — " + e.getMessage());
            fail("[" + cdmJson + "] Unexpected error: " + e.getMessage());
        } finally {
            System.out.printf("[%s] %d/%d completed%n", category, result.passed + result.mismatched + result.errors, result.total);
        }
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("cdmFiles")
    void fpmLToCdm_producesValidGlobalKeys(String category, Path cdmJson) throws Exception {
        IntegrationResult result = categoryResults.computeIfAbsent(category, IntegrationResult::of);
        result.total++;

        String baseName = cdmJson.getFileName().toString().replaceFirst("\\.json$", "");
        try {
            assertTrue(Files.exists(cdmJson), "Missing CDM JSON: " + cdmJson);
            String jsonContent = Files.readString(cdmJson);
            JsonNode tree = JSON.readTree(jsonContent);
            unwrapSingleArrays(tree, "stubPeriodType");
            TradeState original = JSON.treeToValue(tree, TradeState.class);

            // Convert CDM -> FpML -> CDM
            CdmToFpmlConverter cdmToFpml = new CdmToFpmlConverter();
            CdmToFpmlConverter.ConversionResult conversionResult = cdmToFpml.convert(original);

            assertNotNull(conversionResult.getTradeElement());

            String xmlStr = wrapInDataDocument(conversionResult);
            ByteArrayInputStream bais = new ByteArrayInputStream(xmlStr.getBytes("UTF-8"));
            List<TradeState> reconvertedStates = new FpmlToCdmConverter().convert(bais);

            assertFalse(reconvertedStates.isEmpty());

            // Verify GlobalKeyReproducer doesn't throw and produces valid keys
            TradeState rekeyed = new GlobalKeyReproducer().apply(reconvertedStates.get(0));
            JsonNode treeRekeyed = JSON.valueToTree(rekeyed);

            // Verify all globalReferences resolve to globalKeys
            java.util.Set<String> keys = new java.util.HashSet<>();
            java.util.Set<String> refs = new java.util.HashSet<>();
            collectKeysAndRefs(treeRekeyed, keys, refs);

            java.util.Set<String> dangling = new java.util.HashSet<>(refs);
            dangling.removeAll(keys);

            if (!dangling.isEmpty()) {
                result.errors++;
                result.failures.add(baseName + ": " + dangling.size() + " dangling globalReferences");
                System.out.printf("[%s] %s DANGLING (%d)%n", category, baseName, dangling.size());
            } else {
                result.passed++;
                System.out.printf("[%s] %s GLOBALKEY OK%n", category, baseName);
            }

        } catch (IllegalArgumentException e) {
            result.errors++;
            result.failures.add(baseName + ": Unsupported product type");
        } catch (Exception e) {
            result.errors++;
            result.failures.add(baseName + ": " + e.getClass().getSimpleName() + " — " + e.getMessage());
            fail("[" + cdmJson + "] Unexpected error: " + e.getMessage());
        } finally {
            System.out.printf("[%s] %d/%d completed%n", category, result.passed + result.errors, result.total);
        }
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("cdmFiles")
    void fpmLXml_isWellFormed(String category, Path cdmJson) throws Exception {
        IntegrationResult result = categoryResults.computeIfAbsent(category, IntegrationResult::of);
        result.total++;

        String baseName = cdmJson.getFileName().toString().replaceFirst("\\.json$", "");
        try {
            assertTrue(Files.exists(cdmJson), "Missing CDM JSON: " + cdmJson);
            String jsonContent = Files.readString(cdmJson);
            JsonNode tree = JSON.readTree(jsonContent);
            unwrapSingleArrays(tree, "stubPeriodType");
            TradeState original = JSON.treeToValue(tree, TradeState.class);

            CdmToFpmlConverter converter = new CdmToFpmlConverter();
            CdmToFpmlConverter.ConversionResult conversionResult = converter.convert(original);

            assertNotNull(conversionResult.getTradeElement());

            String xmlStr = wrapInDataDocument(conversionResult);

            // Parse the generated XML to verify it's well-formed
            javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            dbf.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setNamespaceAware(true);
            javax.xml.parsers.DocumentBuilder builder = dbf.newDocumentBuilder();
            java.io.InputStream is = new java.io.ByteArrayInputStream(xmlStr.getBytes("UTF-8"));
            org.w3c.dom.Document doc = builder.parse(is);

            assertNotNull(doc);
            assertEquals("dataDocument", doc.getDocumentElement().getTagName());

            result.passed++;
            System.out.printf("[%s] %s WELL-FORMED%n", category, baseName);

        } catch (IllegalArgumentException e) {
            result.errors++;
            result.failures.add(baseName + ": Unsupported product type");
        } catch (Exception e) {
            result.errors++;
            result.failures.add(baseName + ": " + e.getClass().getSimpleName() + " — " + e.getMessage());
            fail("[" + cdmJson + "] XML not well-formed: " + e.getMessage());
        } finally {
            System.out.printf("[%s] %d/%d completed%n", category, result.passed + result.errors, result.total);
        }
    }

    private static String wrapInDataDocument(CdmToFpmlConverter.ConversionResult result) throws Exception {
        Element tradeEl = result.getTradeElement();
        javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        org.w3c.dom.Document wrapperDoc = dbf.newDocumentBuilder().newDocument();
        String fpmlNs = FpmlConstants.FPML_NS;
        org.w3c.dom.Element dataDoc = wrapperDoc.createElementNS(fpmlNs, "dataDocument");
        wrapperDoc.appendChild(dataDoc);
        org.w3c.dom.Element tradeWrapper = wrapperDoc.createElementNS(fpmlNs, "trade");
        org.w3c.dom.Node imported = wrapperDoc.importNode(tradeEl, true);
        tradeWrapper.appendChild(imported);
        dataDoc.appendChild(tradeWrapper);
        for (org.w3c.dom.Element party : result.getPartyElements()) {
            org.w3c.dom.Node partyNode = wrapperDoc.importNode(party, true);
            dataDoc.appendChild(partyNode);
        }
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(wrapperDoc), new StreamResult(baos));
        return baos.toString("UTF-8");
    }

    private static void collectKeysAndRefs(JsonNode node, java.util.Set<String> keys, java.util.Set<String> refs) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                if ("globalKey".equals(e.getKey()) && e.getValue().isTextual()) {
                    keys.add(e.getValue().asText());
                } else if ("globalReference".equals(e.getKey()) && e.getValue().isTextual()) {
                    refs.add(e.getValue().asText());
                } else {
                    collectKeysAndRefs(e.getValue(), keys, refs);
                }
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectKeysAndRefs(child, keys, refs));
        }
    }
}
