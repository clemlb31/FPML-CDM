package io.fpmlcdm;

import cdm.event.common.TradeState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regnosys.rosetta.common.serialisation.RosettaObjectMapper;
import io.fpmlcdm.report.SemanticDiff;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Prints the diff between expected CDM JSON and our actual output for a single sample.
 * Usage: mvn -q exec:java -Dexec.mainClass=io.fpmlcdm.DiffOne -Dexec.args="<category> <base>"
 */
public final class DiffOne {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: DiffOne <category> <baseName>");
            System.exit(2);
        }
        ObjectMapper json = RosettaObjectMapper.getNewRosettaObjectMapper();
        FpmlToCdmConverter converter = new FpmlToCdmConverter();
        Path train = Paths.get("data/train");
        Path xml = train.resolve(args[0]).resolve("fpml").resolve(args[1] + ".xml");
        Path ref = train.resolve(args[0]).resolve("cdm").resolve(args[1] + ".json");
        try (InputStream in = Files.newInputStream(xml)) {
            List<TradeState> states = converter.convert(in);
            if (states.size() != 1) {
                System.out.println("tradeStates=" + states.size());
                return;
            }
            String expected = Files.readString(ref);
            String actual = json.writerWithDefaultPrettyPrinter().writeValueAsString(states.get(0));
            SemanticDiff.Result diff = SemanticDiff.compare(expected, actual);
            System.out.println("DIFFS=" + diff.size());
            System.out.println(diff);
            if (System.getProperty("printActual") != null) {
                System.out.println("\n=== ACTUAL ===\n" + actual);
            }
            if (System.getProperty("printExpected") != null) {
                System.out.println("\n=== EXPECTED ===\n" + expected);
            }
        }
    }
}
