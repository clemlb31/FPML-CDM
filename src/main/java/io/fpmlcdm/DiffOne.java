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
 * Diff a single (category, base) pair.
 *
 * Usage:
 *   mvn -q exec:java -Dexec.mainClass=io.fpmlcdm.DiffOne \
 *       -Dexec.args="equity-5-12 trs-ex02-single-equity"
 */
public final class DiffOne {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: DiffOne <category> <base>");
            System.exit(2);
        }
        String cat = args[0];
        String base = args[1];

        Path train = Paths.get("data/train");
        Path xml = train.resolve(cat).resolve("fpml").resolve(base + ".xml");
        Path ref = train.resolve(cat).resolve("cdm").resolve(base + ".json");
        if (!Files.exists(xml)) { System.err.println("no xml: " + xml); System.exit(3); }
        if (!Files.exists(ref)) { System.err.println("no ref: " + ref); System.exit(3); }

        ObjectMapper json = RosettaObjectMapper.getNewRosettaObjectMapper();
        FpmlToCdmConverter converter = new FpmlToCdmConverter();
        try (InputStream in = Files.newInputStream(xml)) {
            List<TradeState> tradeStates = converter.convert(in);
            if (tradeStates.size() != 1) {
                System.out.println("no/multi tradeState=" + tradeStates.size());
                return;
            }
            String expected = Files.readString(ref);
            String actual = json.writerWithDefaultPrettyPrinter().writeValueAsString(tradeStates.get(0));
            SemanticDiff.Result diff = SemanticDiff.compare(expected, actual);
            if (diff.isEqual()) {
                System.out.println("EQUAL");
            } else {
                System.out.println("diff size: " + diff.size());
                diff.diffs().forEach(System.out::println);
            }
        }
    }
}
