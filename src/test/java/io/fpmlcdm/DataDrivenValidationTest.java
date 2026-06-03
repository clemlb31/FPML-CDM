package io.fpmlcdm;

import cdm.event.common.TradeState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regnosys.rosetta.common.serialisation.RosettaObjectMapper;
import io.fpmlcdm.report.SemanticDiff;
import io.fpmlcdm.validate.CdmValidator;
import io.fpmlcdm.validate.GlobalKeyReproducer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Walks {@code data/train/<category>/{fpml,cdm}/*.{xml,json}} and asserts that
 * our converter produces a semantically-equal CDM JSON for each pair.
 *
 * Pairs from {@code *-incomplete} or {@code invalid-products-*} categories are
 * skipped by default — they contain multi-trade FpML documents and are best-effort.
 * Set {@code -Dincludeincomplete=true} to include them.
 */
class DataDrivenValidationTest {

    private static final ObjectMapper JSON = RosettaObjectMapper.getNewRosettaObjectMapper();
    private static final Path TRAIN = Paths.get("data/train");

    /** Shared validator — Guice setup (CdmRuntimeModule) is heavy, do it once. */
    private static CdmValidator validator;

    @BeforeAll
    static void initValidator() {
        validator = new CdmValidator();
    }

    static Stream<Arguments> pairs() throws Exception {
        boolean includeIncomplete = Boolean.getBoolean("includeincomplete");
        try (Stream<Path> categories = Files.list(TRAIN)) {
            return categories
                    .filter(Files::isDirectory)
                    .filter(p -> includeIncomplete
                            || (!p.getFileName().toString().contains("incomplete")
                                && !p.getFileName().toString().startsWith("invalid-")))
                    // invalid-products-* contain intentionally invalid FpML used as
                    // negative-tests in CDM. Their reference JSON represents what the
                    // ingester salvages from broken input; we treat them as out of scope.
                    .filter(p -> !p.getFileName().toString().startsWith("invalid-"))
                    .flatMap(DataDrivenValidationTest::pairsForCategory)
                    .toList()
                    .stream();
        }
    }

    private static Stream<Arguments> pairsForCategory(Path category) {
        Path fpml = category.resolve("fpml");
        Path cdm = category.resolve("cdm");
        if (!Files.isDirectory(fpml) || !Files.isDirectory(cdm)) return Stream.empty();
        try {
            return Files.list(fpml)
                    .filter(p -> p.toString().endsWith(".xml"))
                    .map(xml -> {
                        String base = xml.getFileName().toString().replaceFirst("\\.xml$", "");
                        Path json = cdm.resolve(base + ".json");
                        return Arguments.of(category.getFileName().toString(), xml, json);
                    })
                    // Skip pairs whose reference JSON is empty (no salvageable structure).
                    // Currently affects the two loan_trade_ex003/004 FpML notifications
                    // which carry no <trade> element.
                    .filter(args -> {
                        Path json = (Path) args.get()[2];
                        try {
                            return Files.size(json) > 0;
                        } catch (Exception e) {
                            return false;
                        }
                    });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("pairs")
    void semanticallyEqual(String category, Path xml, Path expectedJson) throws Exception {
        assertTrue(Files.exists(expectedJson),
                "Missing reference CDM JSON: " + expectedJson);

        FpmlToCdmConverter converter = new FpmlToCdmConverter();
        List<TradeState> tradeStates;
        try (InputStream in = Files.newInputStream(xml)) {
            tradeStates = converter.convert(in);
        }
        assertEquals(1, tradeStates.size(),
                () -> "[" + xml + "] Single-trade dataset entry but got " + tradeStates.size());

        String actual = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tradeStates.get(0));
        String expected = Files.readString(expectedJson);

        SemanticDiff.Result diff = SemanticDiff.compare(expected, actual);
        assertTrue(diff.isEqual(),
                () -> "[" + xml + "] Diffs (" + diff.size() + "):\n" + diff);
    }

    /**
     * Second, independent validation signal: our converter must not introduce CDM
     * data-rule violations beyond what the reference itself has.
     *
     * The FINOS reference dataset is itself only partially CDM-valid (we discovered
     * during knowledge_base/knowledge/validation_findings.md that it carries quirks
     * like issuer-less UTI tradeIdentifiers that violate IdentifierIssuerChoice).
     * So we can't assert absolute validity — we assert that the set of violations
     * in our output is a subset of (or equal to) the reference's violations. Any
     * NEW violation we introduce is a regression even if SemanticDiff still passes.
     */
    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("pairs")
    void noNewCdmViolations(String category, Path xml, Path expectedJson) throws Exception {
        assertTrue(Files.exists(expectedJson), "Missing reference CDM JSON: " + expectedJson);

        FpmlToCdmConverter converter = new FpmlToCdmConverter();
        List<TradeState> tradeStates;
        try (InputStream in = Files.newInputStream(xml)) {
            tradeStates = converter.convert(in);
        }
        assertEquals(1, tradeStates.size());
        TradeState ours = tradeStates.get(0);

        // The reference dataset serialises a few enum-valued fields as single-element arrays
        // that the CDM Java model exposes as scalars; unwrap before deserialising.
        JsonNode tree = JSON.readTree(Files.readString(expectedJson));
        unwrapSingleArrays(tree, "stubPeriodType");
        TradeState reference = JSON.treeToValue(tree, TradeState.class);

        Set<String> refFailures = new HashSet<>(validator.validate(reference).failures());
        Set<String> ourFailures = new HashSet<>(validator.validate(ours).failures());

        Set<String> newOnes = new HashSet<>(ourFailures);
        newOnes.removeAll(refFailures);

        assertTrue(newOnes.isEmpty(), () ->
                "Our converter introduces " + newOnes.size() + " new CDM data-rule violation(s)"
                + " not present in the reference:\n  " + String.join("\n  ", newOnes));
    }

    /**
     * Third independent validation signal: after applying the Regnosys content-hash
     * pipeline to our output, every {@code globalReference} must resolve to a
     * {@code globalKey} elsewhere in the same document.
     *
     * Catches mapper bugs where we emit cross-references pointing nowhere (the kind
     * of bug that SemanticDiff can't see because both globalKey and globalReference
     * are masked). The reproduced hashes won't exactly match the reference values
     * (our content differs slightly), but the integrity property still holds.
     */
    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("pairs")
    void globalKeyIntegrity(String category, Path xml, Path expectedJson) throws Exception {
        FpmlToCdmConverter converter = new FpmlToCdmConverter();
        List<TradeState> tradeStates;
        try (InputStream in = Files.newInputStream(xml)) {
            tradeStates = converter.convert(in);
        }
        assertEquals(1, tradeStates.size());

        TradeState rekeyed = new GlobalKeyReproducer().apply(tradeStates.get(0));
        JsonNode tree = JSON.valueToTree(rekeyed);

        Set<String> keys = new HashSet<>();
        Set<String> refs = new HashSet<>();
        collectKeysAndRefs(tree, keys, refs);

        Set<String> dangling = new HashSet<>(refs);
        dangling.removeAll(keys);
        assertTrue(dangling.isEmpty(),
                () -> "Dangling globalReference(s) — no matching globalKey: " + dangling);
        // Sanity: we should produce some keys for a non-trivial trade.
        assertFalse(keys.isEmpty(), "GlobalKeyReproducer produced no globalKeys");
    }

    private static void collectKeysAndRefs(JsonNode node, Set<String> keys, Set<String> refs) {
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
}
