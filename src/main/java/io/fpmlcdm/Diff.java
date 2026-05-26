package io.fpmlcdm;

import cdm.event.common.TradeState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regnosys.rosetta.common.serialisation.RosettaObjectMapper;
import io.fpmlcdm.report.SemanticDiff;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Diff one XML against its expected JSON, printing all diffs. */
public final class Diff {
    public static void main(String[] args) throws Exception {
        Path xml = Path.of(args[0]);
        Path ref = Path.of(args[1]);
        ObjectMapper json = RosettaObjectMapper.getNewRosettaObjectMapper();
        FpmlToCdmConverter c = new FpmlToCdmConverter();
        try (InputStream in = Files.newInputStream(xml)) {
            List<TradeState> ts = c.convert(in);
            String expected = Files.readString(ref);
            String actual = json.writerWithDefaultPrettyPrinter().writeValueAsString(ts.get(0));
            SemanticDiff.Result r = SemanticDiff.compare(expected, actual);
            System.out.println("Diff count: " + r.size());
            System.out.println(r);
            if (args.length > 2 && "--actual".equals(args[2])) {
                Files.writeString(Path.of("/tmp/actual.json"), actual);
                Files.writeString(Path.of("/tmp/expected.json"), expected);
                System.out.println("Wrote /tmp/actual.json and /tmp/expected.json");
            }
        }
    }
}
