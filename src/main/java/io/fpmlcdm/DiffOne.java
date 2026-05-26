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

/** Diff one FpML test case against its reference. Usage: <category> <basename> */
public final class DiffOne {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: DiffOne <category> <basename>");
            System.exit(2);
        }
        String cat = args[0];
        String base = args[1];
        Path xml = Paths.get("data/train").resolve(cat).resolve("fpml").resolve(base + ".xml");
        Path ref = Paths.get("data/train").resolve(cat).resolve("cdm").resolve(base + ".json");
        ObjectMapper json = RosettaObjectMapper.getNewRosettaObjectMapper();
        FpmlToCdmConverter converter = new FpmlToCdmConverter();
        try (InputStream in = Files.newInputStream(xml)) {
            List<TradeState> tradeStates = converter.convert(in);
            if (tradeStates.size() != 1) {
                System.out.println("trade count = " + tradeStates.size());
                return;
            }
            String expected = Files.readString(ref);
            String actual = json.writerWithDefaultPrettyPrinter().writeValueAsString(tradeStates.get(0));
            SemanticDiff.Result diff = SemanticDiff.compare(expected, actual);
            System.out.println("DIFF COUNT: " + diff.size());
            System.out.println(diff.toString());
            if (args.length > 2 && args[2].equals("--actual")) {
                System.out.println("\n===== ACTUAL =====");
                System.out.println(actual);
            }
            if (args.length > 2 && args[2].equals("--expected")) {
                System.out.println("\n===== EXPECTED =====");
                System.out.println(expected);
            }
        }
    }
}
