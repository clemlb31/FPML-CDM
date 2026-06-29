package io.fpmlcdm.fpml.cdm;

import cdm.event.common.TradeState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regnosys.rosetta.common.serialisation.RosettaObjectMapper;
import io.fpmlcdm.report.SemanticDiff;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs the actual {@link io.fpmlcdm.report.SemanticDiff} (the same one JUnit uses) across one
 * or more category directories under {@code data/ground_truth/fpml-cdm}.
 *
 * Usage: {@code mvn exec:java -Dexec.mainClass=io.fpmlcdm.fpml.cdm.CategoryReport -Dexec.args="rates-5-10"}
 */
public final class CategoryReport {

    public static void main(String[] args) throws Exception {
        ObjectMapper json = RosettaObjectMapper.getNewRosettaObjectMapper();
        FpmlToCdmConverter converter = new FpmlToCdmConverter();
        Path train = Paths.get("data/ground_truth/fpml-cdm");
        List<String> cats = args.length == 0
                ? List.of("rates-5-10", "rates-5-12", "interest-rate-derivatives-5-13")
                : List.of(args);

        int grandPass = 0, grandTotal = 0;
        for (String cat : cats) {
            Path fpmlDir = train.resolve(cat).resolve("fpml");
            Path cdmDir = train.resolve(cat).resolve("cdm");
            if (!Files.isDirectory(fpmlDir) || !Files.isDirectory(cdmDir)) {
                System.out.println(cat + ": skipped (no fpml/ or cdm/)");
                continue;
            }
            int pass = 0, total = 0;
            List<String> failures = new ArrayList<>();
            try (Stream<Path> s = Files.list(fpmlDir)) {
                for (Path xml : s.filter(p -> p.toString().endsWith(".xml")).sorted().toList()) {
                    String base = xml.getFileName().toString().replaceFirst("\\.xml$", "");
                    Path ref = cdmDir.resolve(base + ".json");
                    if (!Files.exists(ref)) continue;
                    total++;
                    try (InputStream in = Files.newInputStream(xml)) {
                        List<TradeState> tradeStates = converter.convert(in);
                        if (tradeStates.size() != 1) {
                            failures.add(String.format("%-3d  %s   (no/multi tradeState=%d)",
                                    9999, base, tradeStates.size()));
                            continue;
                        }
                        String expected = Files.readString(ref);
                        String actual = json.writerWithDefaultPrettyPrinter().writeValueAsString(tradeStates.get(0));
                        SemanticDiff.Result diff = SemanticDiff.compare(expected, actual);
                        if (diff.isEqual()) {
                            pass++;
                        } else {
                            failures.add(String.format("%-3d  %s", diff.size(), base));
                        }
                    } catch (Exception e) {
                        failures.add(String.format("ERR  %s   %s", base, e.getMessage()));
                    }
                }
            }
            System.out.printf("%n%s: %d/%d pass (%.0f%%)%n", cat, pass, total, total == 0 ? 0.0 : 100.0 * pass / total);
            failures.stream().sorted().forEach(System.out::println);
            grandPass += pass;
            grandTotal += total;
        }
        System.out.printf("%nTOTAL: %d/%d pass (%.0f%%)%n", grandPass, grandTotal,
                grandTotal == 0 ? 0.0 : 100.0 * grandPass / grandTotal);
    }
}
