package io.fpmlcdm;

import cdm.event.common.TradeState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regnosys.rosetta.common.serialisation.RosettaObjectMapper;
import io.fpmlcdm.report.SemanticDiff;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class DebugDiff {
    public static void main(String[] args) throws Exception {
        ObjectMapper json = RosettaObjectMapper.getNewRosettaObjectMapper();
        FpmlToCdmConverter converter = new FpmlToCdmConverter();
        try (InputStream in = Files.newInputStream(Paths.get(args[0]))) {
            List<TradeState> ts = converter.convert(in);
            String actual = json.writerWithDefaultPrettyPrinter().writeValueAsString(ts.get(0));
            String expected = Files.readString(Paths.get(args[1]));
            SemanticDiff.Result diff = SemanticDiff.compare(expected, actual);
            System.out.println("=== DIFF COUNT: " + diff.size() + " ===");
            System.out.println(diff);
        }
    }
}
