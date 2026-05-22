package io.fpmlcdm;

import cdm.event.common.TradeState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.regnosys.rosetta.common.serialisation.RosettaObjectMapper;
import io.fpmlcdm.report.ReportWriter;
import io.fpmlcdm.report.SemanticDiff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

@Command(
        name = "fpml-cdm",
        mixinStandardHelpOptions = true,
        description = "Convert FpML 5.x XML into FINOS CDM 6.x TradeState JSON."
)
public class Cli implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(Cli.class);

    @Option(names = {"-i", "--input"}, required = true,
            description = "Input FpML XML file, or directory (scanned recursively for .xml).")
    Path input;

    @Option(names = {"-o", "--output"}, description = "Output directory for generated CDM JSON.")
    Path output;

    @Option(names = "--validate",
            description = "Reference directory containing expected CDM JSON files. " +
                    "Each .xml input is matched to <name>.json in this directory tree.")
    Path validate;

    @Option(names = "--report-html", description = "Write an HTML validation report at this path.")
    Path reportHtml;

    @Option(names = "--report-md", description = "Write a Markdown validation report at this path.")
    Path reportMd;

    @Option(names = "--fail-on-mismatch", description = "Exit with non-zero code if any pair has diffs.")
    boolean failOnMismatch;

    @Override
    public Integer call() throws Exception {
        FpmlToCdmConverter converter = new FpmlToCdmConverter();
        ObjectMapper json = RosettaObjectMapper.getNewRosettaObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);

        List<Path> files = collectInputs();
        log.info("Processing {} FpML file(s)", files.size());

        List<ReportWriter.Row> rows = new ArrayList<>();
        int mismatches = 0;

        for (Path xml : files) {
            ReportWriter.Row row = new ReportWriter.Row(xml.toString());
            try (InputStream in = Files.newInputStream(xml)) {
                List<TradeState> tradeStates = converter.convert(in);
                row.tradeCount = tradeStates.size();

                if (output != null) {
                    Path outDir = Files.isDirectory(input)
                            ? output.resolve(input.relativize(xml.getParent()))
                            : output;
                    Files.createDirectories(outDir);
                    String base = xml.getFileName().toString().replaceFirst("\\.xml$", "");
                    if (tradeStates.size() == 1) {
                        Files.writeString(outDir.resolve(base + ".json"),
                                json.writeValueAsString(tradeStates.get(0)));
                    } else {
                        for (int i = 0; i < tradeStates.size(); i++) {
                            Files.writeString(outDir.resolve(base + "-trade-" + i + ".json"),
                                    json.writeValueAsString(tradeStates.get(i)));
                        }
                    }
                }

                if (validate != null && tradeStates.size() == 1) {
                    Path ref = locateReference(xml);
                    if (ref != null && Files.exists(ref)) {
                        String expected = Files.readString(ref);
                        String actual = json.writeValueAsString(tradeStates.get(0));
                        SemanticDiff.Result diff = SemanticDiff.compare(expected, actual);
                        row.diffCount = diff.size();
                        row.diffSummary = diff.toString();
                        if (!diff.isEqual()) mismatches++;
                    } else {
                        row.diffSummary = "[no reference found]";
                    }
                }
            } catch (Exception e) {
                row.error = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.error("Failed to convert {}", xml, e);
                mismatches++;
            }
            rows.add(row);
        }

        if (reportHtml != null) ReportWriter.writeHtml(reportHtml, rows);
        if (reportMd != null) ReportWriter.writeMarkdown(reportMd, rows);

        return failOnMismatch && mismatches > 0 ? 1 : 0;
    }

    private List<Path> collectInputs() throws Exception {
        if (Files.isRegularFile(input)) {
            return List.of(input);
        }
        try (Stream<Path> s = Files.walk(input)) {
            return s.filter(p -> p.toString().endsWith(".xml")).sorted().toList();
        }
    }

    /**
     * Reference lookup: replaces {@code /fpml/} with {@code /cdm/} in the path and
     * swaps {@code .xml} for {@code .json}. Matches the layout of {@code data/train}.
     */
    private Path locateReference(Path xml) {
        if (validate == null) return null;
        String rel;
        if (Files.isDirectory(input)) {
            rel = input.relativize(xml).toString().replace("/fpml/", "/cdm/");
        } else {
            // Single-file input — assume the xml lives under .../fpml/<name>.xml
            // and the matching ref is .../cdm/<name>.json relative to validate.
            String base = xml.getFileName().toString().replaceFirst("\\.xml$", "");
            rel = "cdm/" + base + ".json";
        }
        if (!rel.endsWith(".json")) rel = rel.replaceFirst("\\.xml$", ".json");
        return validate.resolve(rel);
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Cli()).execute(args));
    }
}
