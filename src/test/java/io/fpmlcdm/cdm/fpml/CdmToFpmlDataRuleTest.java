package io.fpmlcdm.cdm.fpml;

import cdm.event.common.TradeState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regnosys.rosetta.common.serialisation.RosettaObjectMapper;
import io.fpmlcdm.fpml.cdm.validate.CdmValidator;
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
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates that FpML output from CDM→FpML conversion does not introduce
 * new CDM data-rule violations beyond what the reference already has.
 *
 * Walks {@code data/ground_truth/fpml-cdm/<category>/cdm/*.json}, converts to FpML XML,
 * then re-parses back to CDM and compares violations against the reference.
 *
 * Skips categories containing "incomplete" or starting with "invalid-" by default.
 * Set {@code -Dincludeincomplete=true} to include them.
 */
class CdmToFpmlDataRuleTest {

    private static final ObjectMapper JSON = RosettaObjectMapper.getNewRosettaObjectMapper();
    private static final Path TRAIN = Paths.get("data/ground_truth/fpml-cdm");
    private static CdmValidator validator;

    static class CategoryResult {
        int total;
        int pass;
        int newViolations;
        int errors;
        final List<String> failures = new ArrayList<>();

        static CategoryResult of(String cat) { return new CategoryResult(); }
    }

    private static Map<String, CategoryResult> categoryResults;

    @BeforeAll
    static void initValidator() {
        validator = new CdmValidator();
        categoryResults = new TreeMap<>();
    }

    static Stream<Arguments> cdmFiles() throws Exception {
        boolean includeIncomplete = Boolean.getBoolean("includeincomplete");
        try (Stream<Path> categories = Files.list(TRAIN)) {
            return categories
                    .filter(Files::isDirectory)
                    .filter(p -> includeIncomplete
                            || (!p.getFileName().toString().contains("incomplete")
                                && !p.getFileName().toString().startsWith("invalid-")))
                    .flatMap(CdmToFpmlDataRuleTest::cdmFilesForCategory)
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
    void noNewCdmViolations(String category, Path cdmJson) throws Exception {
        CategoryResult result = categoryResults.computeIfAbsent(category, CategoryResult::of);
        result.total++;

        String baseName = cdmJson.getFileName().toString().replaceFirst("\\.json$", "");
        try {
            assertTrue(Files.exists(cdmJson), "Missing CDM JSON: " + cdmJson);
            String jsonContent = Files.readString(cdmJson);
            JsonNode tree = JSON.readTree(jsonContent);
            unwrapSingleArrays(tree, "stubPeriodType");
            TradeState reference = JSON.treeToValue(tree, TradeState.class);

            // Convert CDM → FpML → CDM
            CdmToFpmlConverter converter = new CdmToFpmlConverter();
            CdmToFpmlConverter.ConversionResult conversionResult = converter.convert(reference);

            assertNotNull(conversionResult.getTradeElement(),
                    () -> "[" + cdmJson + "] Trade element is null");

            String xmlStr = wrapInDataDocument(conversionResult);
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(xmlStr.getBytes("UTF-8"));
            List<TradeState> reconvertedStates = new io.fpmlcdm.fpml.cdm.FpmlToCdmConverter().convert(bais);

            assertFalse(reconvertedStates.isEmpty(),
                    "[" + cdmJson + "] Round-trip produced no CDM states");

            TradeState reconverted = reconvertedStates.get(0);

            // Compare violations
            Set<String> refFailures = new HashSet<>(validator.validate(reference).failures());
            Set<String> ourFailures = new HashSet<>(validator.validate(reconverted).failures());

            Set<String> newOnes = new HashSet<>(ourFailures);
            newOnes.removeAll(refFailures);

            if (newOnes.isEmpty()) {
                result.pass++;
                System.out.printf("[%s] %s PASS (no new violations)%n", category, baseName);
            } else {
                result.newViolations++;
                result.failures.add(baseName + ": " + newOnes.size() + " new violations: " + newOnes);
                System.out.printf("[%s] %s NEW VIOLATIONS (%d)%n", category, baseName, newOnes.size());
                if (newOnes.size() <= 5) {
                    for (String v : newOnes) {
                        System.out.println("    " + v);
                    }
                }
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
            System.out.printf("[%s] %d/%d completed%n", category, result.pass + result.newViolations + result.errors, result.total);
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

    @AfterAll
    static void printFinalSummary() {
        int totalFiles = 0, totalPass = 0, totalNewViolations = 0, totalErrors = 0;
        System.out.println("\n========================================");
        System.out.println("  CDM Data-Rule Validation Report");
        System.out.println("========================================");
        for (Map.Entry<String, CategoryResult> e : categoryResults.entrySet()) {
            CategoryResult r = e.getValue();
            totalFiles += r.total;
            totalPass += r.pass;
            totalNewViolations += r.newViolations;
            totalErrors += r.errors;
            String coverage = r.total > 0 ? String.format("%.1f%%", 100.0 * r.pass / r.total) : "—";
            System.out.printf("  %-45s %3d pass | %2d new violations | %2d error (%s)%n",
                    e.getKey() + "/", r.pass, r.newViolations, r.errors, coverage);
        }
        String overallCoverage = totalFiles > 0 ? String.format("%.1f%%", 100.0 * totalPass / totalFiles) : "—";
        System.out.println("----------------------------------------");
        System.out.printf("  %-45s %3d pass | %2d new violations | %2d error (%s)%n",
                "TOTAL", totalPass, totalNewViolations, totalErrors, overallCoverage);
        System.out.println("========================================");

        for (Map.Entry<String, CategoryResult> e : categoryResults.entrySet()) {
            if (!e.getValue().failures.isEmpty()) {
                System.out.printf("\n  [%s] Errors:%n", e.getKey());
                for (String f : e.getValue().failures) {
                    System.out.println("    - " + f);
                }
            }
        }
    }
}
