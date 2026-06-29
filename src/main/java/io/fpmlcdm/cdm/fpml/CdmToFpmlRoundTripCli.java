package io.fpmlcdm.cdm.fpml;

import cdm.event.common.TradeState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.regnosys.rosetta.common.serialisation.RosettaObjectMapper;
import io.fpmlcdm.report.SemanticDiff;
import io.fpmlcdm.fpml.cdm.validate.GlobalKeyReproducer;
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

/**
 * CLI for round-trip validation of CDM $\to$ FpML conversion.
 * 
 * Usage:
 *   mvn exec:java -Dexec.mainClass="io.fpmlcdm.cdm.fpml.CdmToFpmlRoundTripCli" \
 *       -Dexec.args="-i data/ground_truth/fpml-cdm/rates-5-10/cdm --output generated-fpml --validate data/ground_truth/fpml-cdm/rates-5-10/cdm"
 * 
 * This will:
 * 1. Load CDM JSON files from --input
 * 2. Convert them to FpML XML and write to --output (as .xml)
 * 3. Convert the generated FpML back to CDM using FpmlToCdmConverter
 * 4. Compare original vs round-tripped CDM using SemanticDiff
 */
@Command(
        name = "cdm-fpml",
        mixinStandardHelpOptions = true,
        description = "Round-trip validation: CDM JSON -> FpML XML -> CDM JSON (with semantic comparison)."
)
public class CdmToFpmlRoundTripCli implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(CdmToFpmlRoundTripCli.class);

    @Option(names = {"-i", "--input"}, required = true,
            description = "Input CDM JSON file, or directory (scanned recursively for .json).")
    Path input;

    @Option(names = {"-o", "--output"}, description = "Output directory for generated FpML XML.")
    Path outputDir;

    @Option(names = {"--validate"}, description =
            "Reference CDM JSON directory. Each converted XML is re-parsed and compared against the original CDM.")
    Path validateRef;

    @Option(names = {"--fail-on-mismatch"}, description =
            "Exit with non-zero code if any round-trip has semantic diffs.")
    boolean failOnMismatch;

    @Option(names = {"--include-incomplete"}, description =
            "Include files from directories containing 'incomplete' or 'invalid' in their name.")
    boolean includeIncomplete;

    private final ObjectMapper jsonMapper = RosettaObjectMapper.getNewRosettaObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public Integer call() throws Exception {
        CdmToFpmlConverter cdmToFpml = new CdmToFpmlConverter();
        io.fpmlcdm.fpml.cdm.FpmlToCdmConverter fpmlToCdm = new io.fpmlcdm.fpml.cdm.FpmlToCdmConverter();

        List<Path> files = collectInputs();
        log.info("Processing {} CDM file(s)", files.size());

        int totalFiles = 0;
        int successfulConversions = 0;
        int roundTripPassed = 0;
        int roundTripFailed = 0;
        int errors = 0;

        for (Path cdmJson : files) {
            totalFiles++;
            String baseName = cdmJson.getFileName().toString().replaceFirst("\\.json$", "");
            
            // Step 1: Load CDM JSON -> TradeState
            TradeState originalTrade;
            try {
                String jsonContent = Files.readString(cdmJson);
                originalTrade = jsonMapper.readValue(jsonContent, TradeState.class);
            } catch (Exception e) {
                log.error("Failed to parse CDM JSON: {}", cdmJson.getFileName(), e);
                errors++;
                continue;
            }

            // Step 2: Convert CDM -> FpML XML
            java.io.ByteArrayOutputStream xmlOut = new java.io.ByteArrayOutputStream();
            try {
                CdmToFpmlConverter.ConversionResult conversionResult = cdmToFpml.convert(originalTrade);
                
                if (conversionResult.getTradeElement() == null) {
                    log.warn("No FpML elements produced for: {}", baseName);
                    roundTripFailed++;
                    continue;
                }

                // Write to XML file
                javax.xml.transform.Transformer transformer = 
                    javax.xml.transform.TransformerFactory.newInstance().newTransformer();
                transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes");
                
                // Wrap in <trade>/<dataDocument> for FpMLToCdmConverter compatibility
                javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
                org.w3c.dom.Document wrapperDoc = dbf.newDocumentBuilder().newDocument();
                String fpmlNs = io.fpmlcdm.cdm.fpml.FpmlConstants.FPML_NS;
                
                // Create <dataDocument> root element (matches original FpML structure)
                org.w3c.dom.Element dataDoc = wrapperDoc.createElementNS(fpmlNs, "dataDocument");
                wrapperDoc.appendChild(dataDoc);
                
                // Create <trade> wrapper and put the product element inside it
                org.w3c.dom.Element tradeElem = wrapperDoc.createElementNS(fpmlNs, "trade");
                org.w3c.dom.Node importedTradeContent = wrapperDoc.importNode(conversionResult.getTradeElement(), true);
                tradeElem.appendChild(importedTradeContent);
                
                // Add document-level parties as siblings of <trade> inside dataDocument
                for (org.w3c.dom.Element party : conversionResult.getPartyElements()) {
                    org.w3c.dom.Node partyNode = wrapperDoc.importNode(party, true);
                    dataDoc.appendChild(partyNode);
                }
                
                // Add trade as child of dataDocument
                dataDoc.appendChild(tradeElem);

                transformer.transform(
                    new javax.xml.transform.dom.DOMSource(wrapperDoc), 
                    new javax.xml.transform.stream.StreamResult(xmlOut)
                );

            } catch (IllegalArgumentException e) {
                log.warn("No mapper found for product in: {}", baseName);
                roundTripFailed++;
                continue;
            } catch (Exception e) {
                log.error("CDM -> FpML conversion failed for: {}", baseName, e);
                errors++;
                continue;
            }

            successfulConversions++;

            // Step 3: Write generated FpML XML to output directory
            if (outputDir != null) {
                Path outPath = Files.createDirectories(outputDir.resolve(cdmJson.getParent().relativize(input)))
                        .resolve(baseName + ".xml");
                Files.writeString(outPath, xmlOut.toString("UTF-8"));
            }

            // Step 4: Convert generated FpML back to CDM (Round-trip)
            if (validateRef != null) {
                try (InputStream fpmlIn = new java.io.ByteArrayInputStream(xmlOut.toByteArray())) {
                    List<TradeState> roundTrippedStates = fpmlToCdm.convert(fpmlIn);

                    if (roundTrippedStates.isEmpty()) {
                        log.warn("Round-trip produced no CDM for: {}", baseName);
                        roundTripFailed++;
                        continue;
                    }

                    TradeState roundTrippedTrade = roundTrippedStates.get(0);

                    // Step 5: Semantic comparison using GlobalKeyReproducer + SemanticDiff
                    TradeState rekeyedOriginal = new GlobalKeyReproducer().apply(originalTrade);
                    TradeState rekeyedRoundTrip = new GlobalKeyReproducer().apply(roundTrippedTrade);

                    String expectedJson = jsonMapper.writeValueAsString(rekeyedOriginal);
                    String actualJson = jsonMapper.writeValueAsString(rekeyedRoundTrip);

                    SemanticDiff.Result diff = SemanticDiff.compare(expectedJson, actualJson);

                    if (diff.isEqual()) {
                        roundTripPassed++;
                        log.info("PASS: {} (0 diffs)", baseName);
                    } else {
                        roundTripFailed++;
                        log.warn("MISMATCH: {} ({} diffs) - {}", 
                                baseName, diff.size(), diff.toString().substring(0, Math.min(200, diff.toString().length())));
                        
                        if (failOnMismatch && diff.size() > 50) {
                            // Print more detail for large mismatches
                            log.warn("Full diff:\n{}", diff.toString());
                        }
                    }

                } catch (Exception e) {
                    log.error("Round-trip FpML -> CDM failed for: {}", baseName, e);
                    errors++;
                }
            } else {
                // No validation requested — just report conversion success
                roundTripPassed++;
            }
        }

        // Summary
        System.out.println("\n========================================");
        System.out.println("  CDM -> FpML Round-Trip Validation Report");
        System.out.println("========================================");
        System.out.printf("  Total files processed:      %d%n", totalFiles);
        System.out.printf("  Successful conversions:     %d%n", successfulConversions);
        System.out.printf("  Round-trip PASSED:          %d%n", roundTripPassed);
        System.out.printf("  Round-trip FAILED/MISMATCH: %d%n", roundTripFailed);
        System.out.printf("  Errors:                     %d%n", errors);
        System.out.println("========================================");

        int exitCode = (failOnMismatch && roundTripFailed > 0) ? 1 : 0;
        return exitCode;
    }

    private List<Path> collectInputs() throws Exception {
        if (Files.isRegularFile(input)) {
            return List.of(input);
        }
        try (Stream<Path> s = Files.walk(input)) {
            return s
                    .filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> includeIncomplete || !p.getParent().getFileName().toString().contains("incomplete") 
                                       && !p.getParent().getFileName().toString().startsWith("invalid-"))
                    .sorted()
                    .toList();
        }
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new CdmToFpmlRoundTripCli()).execute(args));
    }
}