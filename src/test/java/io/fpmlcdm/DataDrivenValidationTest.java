package io.fpmlcdm;

import cdm.event.common.TradeState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regnosys.rosetta.common.serialisation.RosettaObjectMapper;
import io.fpmlcdm.report.SemanticDiff;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
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

    static Stream<Arguments> pairs() throws Exception {
        boolean includeIncomplete = Boolean.getBoolean("includeincomplete");
        try (Stream<Path> categories = Files.list(TRAIN)) {
            return categories
                    .filter(Files::isDirectory)
                    .filter(p -> includeIncomplete
                            || (!p.getFileName().toString().contains("incomplete")
                                && !p.getFileName().toString().startsWith("invalid-")))
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
                "Single-trade dataset entry but got " + tradeStates.size());

        String actual = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tradeStates.get(0));
        String expected = Files.readString(expectedJson);

        SemanticDiff.Result diff = SemanticDiff.compare(expected, actual);
        assertTrue(diff.isEqual(),
                () -> "Diffs (" + diff.size() + "):\n" + diff);
    }
}
