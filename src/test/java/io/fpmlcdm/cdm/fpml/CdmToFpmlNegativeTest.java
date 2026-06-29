package io.fpmlcdm.cdm.fpml;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Negative tests for CDM to FpML conversion.
 * Verifies proper error handling for invalid/edge-case inputs.
 */
class CdmToFpmlNegativeTest {

    @Test
    void nullTradeState_throwsOrReturnsEmpty() {
        CdmToFpmlConverter converter = new CdmToFpmlConverter();
        assertThrows(Exception.class, () -> converter.convert(null));
    }

    @Test
    void emptyTradeState_returnsEmptyDetection() throws Exception {
        CdmProductDetector detector = new CdmProductDetector();
        TradeState empty = TradeState.builder().build();
        assertTrue(detector.detect(empty).isEmpty());
    }

    @Test
    void tradeStateWithNoEconomicTerms_returnsEmptyDetection() throws Exception {
        CdmProductDetector detector = new CdmProductDetector();
        cdm.event.common.Trade trade = cdm.event.common.Trade.builder()
                .setTradeDate(com.rosetta.model.metafields.FieldWithMetaDate.builder()
                        .setValue(com.rosetta.model.lib.records.Date.builder()
                                .setYear(2024).setMonth(1).setDay(1)
                                .build())
                        .build())
                .build();
        TradeState tradeState = TradeState.builder()
                .setTrade(trade)
                .build();
        assertTrue(detector.detect(tradeState).isEmpty());
    }

    @Test
    void tradeStateWithEmptyPayoutList_returnsEmptyDetection() throws Exception {
        CdmProductDetector detector = new CdmProductDetector();
        cdm.product.template.EconomicTerms econTerms = cdm.product.template.EconomicTerms.builder()
                .setPayout(List.of())
                .build();
        cdm.product.template.NonTransferableProduct product = cdm.product.template.NonTransferableProduct.builder()
                .setEconomicTerms(econTerms)
                .build();
        cdm.event.common.Trade trade = cdm.event.common.Trade.builder()
                .setTradeDate(com.rosetta.model.metafields.FieldWithMetaDate.builder()
                        .setValue(com.rosetta.model.lib.records.Date.builder()
                                .setYear(2024).setMonth(1).setDay(1)
                                .build())
                        .build())
                .setProduct(product)
                .build();
        TradeState tradeState = TradeState.builder()
                .setTrade(trade)
                .build();
        assertTrue(detector.detect(tradeState).isEmpty());
    }

    @Test
    void mappingContext_handlesNullRegistrations() {
        CdmToFpmlMappingContext context = new CdmToFpmlMappingContext();
        assertDoesNotThrow(() -> context.registerIdMapping(null, "id"));
        assertDoesNotThrow(() -> context.registerIdMapping("key", null));
        assertDoesNotThrow(() -> context.registerOriginalParty(null));
        assertDoesNotThrow(() -> context.registerOriginalCounterparties(null));
        assertDoesNotThrow(() -> context.resolveHref(null));
    }

    @Test
    void converter_wrapsErrorsInResult() throws Exception {
        // Build a TradeState that will trigger an error during conversion
        // (e.g., missing required fields)
        cdm.event.common.Trade trade = cdm.event.common.Trade.builder()
                .setTradeDate(com.rosetta.model.metafields.FieldWithMetaDate.builder()
                        .setValue(com.rosetta.model.lib.records.Date.builder()
                                .setYear(2024).setMonth(1).setDay(1)
                                .build())
                        .build())
                .addParty(cdm.base.staticdata.party.Party.builder()
                        .setMeta(com.rosetta.model.metafields.MetaFields.builder()
                                .setExternalKey("party1").build())
                        .build())
                .build();
        TradeState tradeState = TradeState.builder().setTrade(trade).build();

        CdmToFpmlConverter converter = new CdmToFpmlConverter();
        // Should not throw for partially-structured TradeState
        // (may return empty detection or minimal XML)
        assertDoesNotThrow(() -> {
            CdmToFpmlConverter.ConversionResult result = converter.convert(tradeState);
            // Result may be empty detection — that's acceptable
        });
    }

    @Test
    void reportWriter_handlesEmptyResults() throws Exception {
        java.nio.file.Path mdPath = Files.createTempFile("empty-md", ".md");
        assertDoesNotThrow(() -> CdmToFpmlReportWriter.writeMarkdown(List.of(), mdPath));
        assertTrue(Files.size(mdPath) > 0);
        Files.delete(mdPath);

        java.nio.file.Path htmlPath = Files.createTempFile("empty-html", ".html");
        assertDoesNotThrow(() -> CdmToFpmlReportWriter.writeHtml(List.of(), htmlPath));
        assertTrue(Files.size(htmlPath) > 0);
        Files.delete(htmlPath);

        assertDoesNotThrow(() -> CdmToFpmlReportWriter.printSummary(List.of()));
    }

    @Test
    void reportWriter_handlesLongErrorMessage() throws Exception {
        String longError = "x".repeat(500);
        CdmToFpmlReportWriter.ConversionResult r =
                new CdmToFpmlReportWriter.ConversionResult("test", "test.json", "ERROR", 0, longError);

        java.nio.file.Path mdPath = Files.createTempFile("long-error-md", ".md");
        assertDoesNotThrow(() -> CdmToFpmlReportWriter.writeMarkdown(List.of(r), mdPath));
        Files.delete(mdPath);

        java.nio.file.Path htmlPath = Files.createTempFile("long-error-html", ".html");
        assertDoesNotThrow(() -> CdmToFpmlReportWriter.writeHtml(List.of(r), htmlPath));
        Files.delete(htmlPath);
    }

    @Test
    void conversionResult_partyElements_canBeNullInConstructor() {
        // Test that null partyElements is handled gracefully
        CdmToFpmlConverter.ConversionResult result =
                new CdmToFpmlConverter.ConversionResult(null, null);
        assertNotNull(result.getPartyElements(), "Null partyElements should default to empty list");
        assertTrue(result.getPartyElements().isEmpty());
    }

    @Test
    void productDetector_handlesExceptionDuringDetection() throws Exception {
        CdmProductDetector detector = new CdmProductDetector();
        // Build a TradeState that might cause reflection issues
        cdm.event.common.Trade trade = cdm.event.common.Trade.builder()
                .setTradeDate(com.rosetta.model.metafields.FieldWithMetaDate.builder()
                        .setValue(com.rosetta.model.lib.records.Date.builder()
                                .setYear(2024).setMonth(1).setDay(1)
                                .build())
                        .build())
                .build();
        TradeState tradeState = TradeState.builder().setTrade(trade).build();

        // Should not throw — detector catches all exceptions
        assertDoesNotThrow(() -> detector.detect(tradeState));
    }

    @Test
    void roundTripWithMalformedXml_failsGracefully() throws Exception {
        String malformedXml = "<dataDocument><trade><swap></swap></trade></dataDocument>";
        java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(malformedXml.getBytes("UTF-8"));

        // Should not throw — FpMLToCdmConverter handles malformed input
        assertDoesNotThrow(() -> {
            List<cdm.event.common.TradeState> states = new io.fpmlcdm.fpml.cdm.FpmlToCdmConverter().convert(bais);
            // May return empty list for malformed XML
        });
    }

    @Test
    void detailedDiff_handlesEmptyDiff() throws Exception {
        java.nio.file.Path outputDir = Files.createTempDirectory("empty-diff");
        assertDoesNotThrow(() -> CdmToFpmlReportWriter.writeDetailedDiff(outputDir, "test.json", ""));
        Path diffPath = outputDir.resolve("test-diff.txt");
        assertTrue(Files.exists(diffPath));
        Files.delete(diffPath);
        Files.delete(outputDir);
    }
}
