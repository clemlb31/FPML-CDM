package io.fpmlcdm.validate;

import cdm.event.common.TradeState;
import io.fpmlcdm.FpmlToCdmConverter;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs the converter over a directory of FpML and reports CDM data-rule validity
 * (reference-free). Usage: {@code ValidateCli <dir-or-file>}.
 */
public final class ValidateCli {

    public static void main(String[] args) throws Exception {
        Path input = Paths.get(args[0]);
        FpmlToCdmConverter converter = new FpmlToCdmConverter();
        CdmValidator validator = new CdmValidator();

        List<Path> files;
        if (Files.isRegularFile(input)) {
            files = List.of(input);
        } else {
            try (Stream<Path> s = Files.walk(input)) {
                files = s.filter(p -> p.toString().endsWith(".xml")).sorted().toList();
            }
        }

        int total = 0, produced = 0, valid = 0;
        for (Path xml : files) {
            total++;
            try (InputStream in = Files.newInputStream(xml)) {
                List<TradeState> states = converter.convert(in);
                if (states.isEmpty()) continue;
                produced++;
                CdmValidator.Result r = validator.validate(states.get(0));
                if (r.success()) {
                    valid++;
                } else {
                    System.out.println(xml.getFileName() + " : " + r.failureCount() + " rule failures");
                    r.failures().stream().limit(3).forEach(f -> System.out.println("    " + f));
                }
            } catch (Exception e) {
                System.out.println(xml.getFileName() + " : ERROR " + e.getMessage());
            }
        }
        System.out.println("\n=== " + valid + "/" + produced + " produced TradeStates are CDM-valid"
                + " (" + total + " files scanned) ===");
    }
}
