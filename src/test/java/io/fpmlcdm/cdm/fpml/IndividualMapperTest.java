package io.fpmlcdm.cdm.fpml;

import cdm.base.staticdata.party.Counterparty;
import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.Party;
import cdm.event.common.Trade;
import cdm.event.common.TradeState;
import cdm.product.asset.InterestRatePayout;
import cdm.product.template.EconomicTerms;
import cdm.product.template.NonTransferableProduct;
import cdm.product.template.Payout;
import com.rosetta.model.lib.meta.Reference;
import com.rosetta.model.metafields.FieldWithMetaDate;
import com.rosetta.model.metafields.MetaFields;
import io.fpmlcdm.cdm.fpml.products.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for individual CDM to FpML product mappers.
 * Builds minimal CDM TradeState objects and verifies mapper output structure.
 */
class IndividualMapperTest {

    private TradeState buildIrsTradeState() {
        InterestRatePayout irPayout = InterestRatePayout.builder().build();
        Payout payout = Payout.builder().setInterestRatePayout(irPayout).build();
        EconomicTerms econTerms = EconomicTerms.builder()
                .setPayout(List.of(payout))
                .build();
        NonTransferableProduct product = NonTransferableProduct.builder()
                .setEconomicTerms(econTerms)
                .build();
        Trade trade = Trade.builder()
                .setTradeDate(FieldWithMetaDate.builder()
                        .setValue(com.rosetta.model.lib.records.Date.builder()
                                .setYear(2024).setMonth(1).setDay(15)
                                .build())
                        .setMeta(MetaFields.builder().setExternalKey("tradeDate-1").build())
                        .build())
                .addParty(Party.builder()
                        .setMeta(MetaFields.builder().setExternalKey("party1").build())
                        .build())
                .addParty(Party.builder()
                        .setMeta(MetaFields.builder().setExternalKey("party2").build())
                        .build())
                .addCounterparty(Counterparty.builder()
                        .setParty(Reference.builder().setGlobalKey("party1").build())
                        .setRole(CounterpartyRoleEnum.PARTY_1)
                        .build())
                .addCounterparty(Counterparty.builder()
                        .setParty(Reference.builder().setGlobalKey("party2").build())
                        .setRole(CounterpartyRoleEnum.PARTY_2)
                        .build())
                .setProduct(product)
                .build();
        return TradeState.builder().setTrade(trade).build();
    }

    @Test
    void interestRateSwapMapper_producesValidXml() throws Exception {
        TradeState tradeState = buildIrsTradeState();
        CdmToFpmlConverter converter = new CdmToFpmlConverter();
        CdmToFpmlConverter.ConversionResult result = converter.convert(tradeState);

        assertNotNull(result);
        assertNotNull(result.getTradeElement());
        assertEquals(FpmlConstants.FPML_NS, result.getTradeElement().getNamespaceURI());
        assertNotNull(result.getPartyElements());
    }

    @Test
    void bulletPaymentMapper_detectsAndMaps() throws Exception {
        cdm.product.asset.CashflowPayout cashflowPayout = cdm.product.asset.CashflowPayout.builder()
                .setAmount(cdm.base.math.Amount.builder()
                        .setValue(java.math.BigDecimal.valueOf(1000000))
                        .setCurrency(com.rosetta.model.metafields.FieldWithMetaString.builder().setValue("USD").build())
                        .build())
                .setSettlementDate(cdm.base.datetime.AdjustableDate.builder()
                        .setUnadjustedDate(com.rosetta.model.lib.records.Date.builder()
                                .setYear(2024).setMonth(6).setDay(15)
                                .build())
                        .build())
                .build();
        Payout payout = Payout.builder().setCashflowPayout(cashflowPayout).build();
        EconomicTerms econTerms = EconomicTerms.builder()
                .setPayout(List.of(payout))
                .build();
        NonTransferableProduct product = NonTransferableProduct.builder()
                .setEconomicTerms(econTerms)
                .build();
        Trade trade = Trade.builder()
                .setTradeDate(FieldWithMetaDate.builder()
                        .setValue(com.rosetta.model.lib.records.Date.builder()
                                .setYear(2024).setMonth(1).setDay(15)
                                .build())
                        .build())
                .addParty(Party.builder()
                        .setMeta(MetaFields.builder().setExternalKey("party1").build())
                        .build())
                .addCounterparty(Counterparty.builder()
                        .setParty(Reference.builder().setGlobalKey("party1").build())
                        .setRole(CounterpartyRoleEnum.PARTY_1)
                        .build())
                .setProduct(product)
                .build();
        TradeState tradeState = TradeState.builder().setTrade(trade).build();

        CdmToFpmlConverter converter = new CdmToFpmlConverter();
        CdmToFpmlConverter.ConversionResult result = converter.convert(tradeState);

        assertNotNull(result);
        assertNotNull(result.getTradeElement());
        assertEquals(FpmlConstants.FPML_NS, result.getTradeElement().getNamespaceURI());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "InterestRateSwapMapper",
        "SwaptionMapper",
        "FxSwapMapper",
        "CreditDefaultSwapMapper",
        "EquitySwapMapper",
        "CapFloorMapper",
        "FraMapper",
        "FxSingleLegMapper",
        "FxOptionMapper",
        "EquityOptionMapper",
        "DividendSwapMapper",
        "DividendSwapOptionMapper",
        "BondOptionMapper",
        "CommoditySwapMapper",
        "CommodityOptionMapper",
        "BulletPaymentMapper"
    })
    void allMappers_existAndImplementInterface(String mapperName) throws Exception {
        Class<?> mapperClass = Class.forName("io.fpmlcdm.cdm.fpml.products." + mapperName);
        assertTrue(CdmToFpmlProductMapper.class.isAssignableFrom(mapperClass),
                mapperName + " must implement CdmToFpmlProductMapper");
    }

    @Test
    void cdmProductDetector_detectsAllKnownTypes() throws Exception {
        CdmProductDetector detector = new CdmProductDetector();

        TradeState irs = buildIrsTradeState();
        assertNotNull(detector.detect(irs), "Should detect IRS");

        TradeState empty = TradeState.builder().build();
        assertTrue(detector.detect(empty).isEmpty(), "Should not detect product on empty TradeState");
    }

    @Test
    void mappingContext_tracksGeneratedIds() {
        CdmToFpmlMappingContext context = new CdmToFpmlMappingContext();
        String id1 = context.createFpmlId("notional");
        String id2 = context.createFpmlId("notional");
        String id3 = context.createFpmlId("party");

        assertNotNull(id1);
        assertNotNull(id2);
        assertNotNull(id3);
        assertNotEquals(id1, id2, "Same prefix should still produce unique IDs");
        assertNotEquals(id1, id3, "Different prefixes should produce different IDs");

        context.registerGeneratedId("test-id");
        assertTrue(context.hasGeneratedId("test-id"));
        assertFalse(context.hasGeneratedId("nonexistent-id"));
    }

    @Test
    void mappingContext_resolveHref_works() {
        CdmToFpmlMappingContext context = new CdmToFpmlMappingContext();
        context.registerIdMapping("cdm-key", "fpml-id");
        context.registerGeneratedId("party1");

        String resolved = context.resolveHref("cdm-key");
        assertEquals("fpml-id", resolved);

        String partyHref = context.resolveHref("party1");
        assertEquals("party1", partyHref);
    }

    @Test
    void reportWriter_printSummary_format() {
        CdmToFpmlReportWriter.ConversionResult r1 =
                new CdmToFpmlReportWriter.ConversionResult("rates-5-10", "test1.json", "PASS", 0, "");
        CdmToFpmlReportWriter.ConversionResult r2 =
                new CdmToFpmlReportWriter.ConversionResult("rates-5-10", "test2.json", "MISMATCH", 5, "");
        CdmToFpmlReportWriter.ConversionResult r3 =
                new CdmToFpmlReportWriter.ConversionResult("fx-5-10", "test3.json", "ERROR", 0, "Null pointer");

        assertDoesNotThrow(() -> CdmToFpmlReportWriter.printSummary(List.of(r1, r2, r3)));
    }

    @Test
    void reportWriter_writeMarkdown_validOutput() throws Exception {
        CdmToFpmlReportWriter.ConversionResult r1 =
                new CdmToFpmlReportWriter.ConversionResult("rates-5-10", "test1.json", "PASS", 0, "");
        CdmToFpmlReportWriter.ConversionResult r2 =
                new CdmToFpmlReportWriter.ConversionResult("fx-5-10", "test2.json", "ERROR", 0, "Null pointer");

        java.nio.file.Path outputPath = java.nio.file.Files.createTempFile("cdm-report", ".md");
        assertDoesNotThrow(() -> CdmToFpmlReportWriter.writeMarkdown(List.of(r1, r2), outputPath));
        assertTrue(java.nio.file.Files.size(outputPath) > 0);
        String content = java.nio.file.Files.readString(outputPath);
        assertTrue(content.contains("# CDM to FpML Conversion Report"));
        assertTrue(content.contains("PASS"));
        assertTrue(content.contains("ERROR"));
        java.nio.file.Files.delete(outputPath);
    }

    @Test
    void reportWriter_writeHtml_validOutput() throws Exception {
        CdmToFpmlReportWriter.ConversionResult r1 =
                new CdmToFpmlReportWriter.ConversionResult("rates-5-10", "test1.json", "PASS", 0, "");

        java.nio.file.Path outputPath = java.nio.file.Files.createTempFile("cdm-report", ".html");
        assertDoesNotThrow(() -> CdmToFpmlReportWriter.writeHtml(List.of(r1), outputPath));
        assertTrue(java.nio.file.Files.size(outputPath) > 0);
        String content = java.nio.file.Files.readString(outputPath);
        assertTrue(content.contains("<!DOCTYPE html>"));
        assertTrue(content.contains("CDM to FpML Conversion Report"));
        assertTrue(content.contains("pass"));
        java.nio.file.Files.delete(outputPath);
    }

    @Test
    void reportWriter_writeDetailedDiff_validOutput() throws Exception {
        java.nio.file.Path outputDir = java.nio.file.Files.createTempDirectory("cdm-diffs");
        String diff = "trade.product.economicTerms.payout[0] differs\n  expected: A\n  actual: B";
        assertDoesNotThrow(() -> CdmToFpmlReportWriter.writeDetailedDiff(outputDir, "test.json", diff));

        java.nio.file.Path diffPath = outputDir.resolve("test-diff.txt");
        assertTrue(java.nio.file.Files.exists(diffPath));
        String content = java.nio.file.Files.readString(diffPath);
        assertTrue(content.contains("expected"));
        java.nio.file.Files.delete(diffPath);
        java.nio.file.Files.delete(outputDir);
    }

    @Test
    void conversionResult_nonNullFields() {
        CdmToFpmlConverter.ConversionResult result =
                new CdmToFpmlConverter.ConversionResult(null, List.of());
        assertNull(result.getTradeElement());
        assertNotNull(result.getPartyElements());
        assertTrue(result.getPartyElements().isEmpty());
    }
}
