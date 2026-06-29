package io.fpmlcdm.fpml.cdm.validate;

import cdm.event.common.TradeState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regnosys.rosetta.common.serialisation.RosettaObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

/** Validates the REFERENCE CDM JSONs directly against CDM data rules. */
public final class ValidateRefCli {

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args[0]);
        ObjectMapper mapper = RosettaObjectMapper.getNewRosettaObjectMapper();
        CdmValidator validator = new CdmValidator();

        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }

        int total = 0, valid = 0;
        for (Path json : files) {
            total++;
            try {
                TradeState ts = mapper.readValue(Files.readString(json), TradeState.class);
                CdmValidator.Result r = validator.validate(ts);
                if (r.success()) valid++;
                else System.out.println(json.getFileName() + " : " + r.failureCount() + " rule failures");
            } catch (Exception e) {
                System.out.println(json.getFileName() + " : ERROR " + e.getMessage());
            }
        }
        System.out.println("\n=== " + valid + "/" + total + " REFERENCE CDM JSONs are CDM-valid ===");
    }
}
