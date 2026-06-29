package io.fpmlcdm.cdm.fpml;

import cdm.event.common.TradeState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regnosys.rosetta.common.serialisation.RosettaObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Element;

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
 * Validates CDM -> FpML conversion produces valid XML structure for known CDM files.
 *
 * Walks {@code data/ground_truth/fpml-cdm/<category>/cdm/*.json}, runs each through CdmToFpmlConverter,
 * and asserts: no exception thrown, trade element non-null, XML has proper namespace.
 *
 * Skips categories containing "incomplete" or starting with "invalid-" by default.
 * Set {@code -Dincludeincomplete=true} to include them.
 */
class CdmToFpmlConverterTest {

    private static final ObjectMapper JSON = RosettaObjectMapper.getNewRosettaObjectMapper();
    private static final Path TRAIN = Paths.get("data/ground_truth/fpml-cdm");

    /** Tracks results per category for report generation at the end. */
    private static Map<String, CategoryResult> categoryResults;

    static class CategoryResult {
        int total;
        int pass;
        int fail;
        final List<String> failures = new ArrayList<>();

        static CategoryResult of(String cat) { return new CategoryResult(); }
    }

    @BeforeAll
    static void init() {
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
                    .flatMap(CdmToFpmlConverterTest::cdmFilesForCategory)
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
    void convertsWithoutException(String category, Path jsonFile) throws Exception {
        CategoryResult result = categoryResults.computeIfAbsent(category, CategoryResult::of);
        result.total++;

        String baseName = jsonFile.getFileName().toString();
        try {
            assertTrue(Files.exists(jsonFile), "Missing CDM JSON: " + jsonFile);
            String jsonContent = Files.readString(jsonFile);
            JsonNode tree = JSON.readTree(jsonContent);
            // Reference dataset serialises stubPeriodType as single-element array;
            // CDM Java model exposes it as scalar — unwrap before deserialising.
            unwrapSingleArrays(tree, "stubPeriodType");
            TradeState tradeState = JSON.treeToValue(tree, TradeState.class);

            CdmToFpmlConverter converter = new CdmToFpmlConverter();
            CdmToFpmlConverter.ConversionResult conversionResult = converter.convert(tradeState);

            // Assert: no exception thrown (already passed), trade element non-null
            assertNotNull(conversionResult,
                    () -> "[" + jsonFile + "] ConversionResult is null");
            Element tradeEl = conversionResult.getTradeElement();
            assertNotNull(tradeEl,
                    () -> "[" + jsonFile + "] Trade element is null — no mapper detected product");

            // Assert: XML has proper FpML namespace
            String ns = tradeEl.getNamespaceURI();
            assertEquals(FpmlConstants.FPML_NS, ns,
                    () -> "[" + jsonFile + "] Trade element missing FpML namespace. "
                            + "Expected: " + FpmlConstants.FPML_NS + ", Got: " + ns);

            // Assert: parties exist as siblings (non-null list, may be empty)
            assertNotNull(conversionResult.getPartyElements(),
                    () -> "[" + jsonFile + "] Party elements list is null");

            result.pass++;

        } catch (AssertionError e) {
            result.fail++;
            result.failures.add(baseName + ": " + e.getMessage());
            throw e;
        } catch (IllegalArgumentException e) {
            // No mapper found — expected for unsupported product types
            result.fail++;
            String msg = baseName + ": Unsupported product type — " + e.getMessage();
            result.failures.add(msg);
            fail("[" + jsonFile + "] " + msg);
        } catch (Exception e) {
            result.fail++;
            String msg = baseName + ": " + e.getClass().getSimpleName() + " — " + e.getMessage();
            result.failures.add(msg);
            fail("[" + jsonFile + "] Unexpected error: " + msg);
        } finally {
            // Print per-file progress
            System.out.printf("[%s] %d/%d passed%n", category, result.pass, result.total);
        }
    }

    /**
     * Final summary — prints pass/fail counts per category and overall.
     */
    @AfterAll
    static void printFinalSummary() {
        int totalFiles = 0, totalPass = 0, totalFail = 0;
        System.out.println("\n========================================");
        System.out.println("  CDM -> FpML Conversion Report");
        System.out.println("========================================");
        for (Map.Entry<String, CategoryResult> e : categoryResults.entrySet()) {
            CategoryResult r = e.getValue();
            totalFiles += r.total;
            totalPass += r.pass;
            totalFail += r.fail;
            String coverage = r.total > 0 ? String.format("%.1f%%", 100.0 * r.pass / r.total) : "—";
            System.out.printf("  %-45s %3d/%-3d (%s)%n", e.getKey() + "/", r.pass, r.total, coverage);
        }
        String overallCoverage = totalFiles > 0 ? String.format("%.1f%%", 100.0 * totalPass / totalFiles) : "—";
        System.out.println("----------------------------------------");
        System.out.printf("  %-45s %3d/%-3d (%s)%n", "TOTAL", totalPass, totalFiles, overallCoverage);
        System.out.println("========================================");

        for (Map.Entry<String, CategoryResult> e : categoryResults.entrySet()) {
            if (!e.getValue().failures.isEmpty()) {
                System.out.printf("\n  [%s] Failures:%n", e.getKey());
                for (String f : e.getValue().failures) {
                    System.out.println("    - " + f);
                }
            }
        }
    }
}
