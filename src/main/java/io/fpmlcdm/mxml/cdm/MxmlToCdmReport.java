package io.fpmlcdm.mxml.cdm;

import cdm.event.common.TradeState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regnosys.rosetta.common.serialisation.RosettaObjectMapper;
import io.fpmlcdm.core.conversion.ConversionResult;
import io.fpmlcdm.core.dataset.PairLoader;
import io.fpmlcdm.core.dataset.TestPair;
import io.fpmlcdm.report.SemanticDiff;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Runnable validation report for the MXML→CDM dataset (the project's end goal).
 *
 * <p>Walks {@code data/generated/mxml-cdm/<category>/{mxml,cdm}}, runs
 * {@link MxmlToCdmConverter} (MXML→FpML→CDM chaining) on each MXML input, and
 * compares the produced CDM {@code TradeState} (serialized with
 * {@link RosettaObjectMapper}) against the committed reference {@code cdm/*.json}
 * via {@link SemanticDiff} (globalKey-tolerant, numeric-tolerant — the same
 * comparator the FpML→CDM 530/530 suite uses).
 *
 * <p>Status classes per pair:
 * <ul>
 *   <li><b>EQUAL</b> — produced CDM semantically matches the reference;</li>
 *   <li><b>DIFF</b>  — CDM produced but differs;</li>
 *   <li><b>NOMAP</b> — MXML→FpML produced nothing (no product mapper yet);</li>
 *   <li><b>MULTI</b> — chaining produced != 1 TradeState (dataset assumes one);</li>
 *   <li><b>ERROR</b> — a leg threw / hard failure.</li>
 * </ul>
 *
 * <p>MXML→CDM EQUAL ⊆ MXML→FpML EQUAL (the FpML→CDM leg is mature at 563/563), so
 * this report tracks how many of the curated pairs convert end-to-end today and
 * grows automatically as MXML→FpML mappers land.
 *
 * <pre>
 * java io.fpmlcdm.mxml.cdm.MxmlToCdmReport [datasetRoot]
 * </pre>
 */
public final class MxmlToCdmReport {

    private static final ObjectMapper JSON = RosettaObjectMapper.getNewRosettaObjectMapper();

    enum Status { EQUAL, DIFF, NOMAP, MULTI, ERROR }

    public static void main(String[] args) {
        Path root = Path.of(args.length > 0 ? args[0] : "data/generated/mxml-cdm");
        int exit = new MxmlToCdmReport().run(root);
        System.exit(exit);
    }

    int run(Path root) {
        List<TestPair> pairs = PairLoader.mxmlToCdm(root).load();
        MxmlToCdmConverter converter = new MxmlToCdmConverter();

        // category -> [equal, diff, nomap, multi, error, total]
        Map<String, int[]> byCat = new TreeMap<>();
        int equal = 0, diff = 0, nomap = 0, multi = 0, error = 0;

        for (TestPair pair : pairs) {
            Status st = classify(converter, pair);
            int[] row = byCat.computeIfAbsent(pair.category(), k -> new int[6]);
            row[5]++;
            switch (st) {
                case EQUAL -> { row[0]++; equal++; }
                case DIFF  -> { row[1]++; diff++; }
                case NOMAP -> { row[2]++; nomap++; }
                case MULTI -> { row[3]++; multi++; }
                case ERROR -> { row[4]++; error++; }
            }
        }

        printReport(byCat, pairs.size(), equal, diff, nomap, multi, error);
        // Exit non-zero only on hard ERRORs (NOMAP/DIFF/MULTI are expected while porting).
        return error == 0 ? 0 : 1;
    }

    private Status classify(MxmlToCdmConverter converter, TestPair pair) {
        try (InputStream in = Files.newInputStream(pair.input())) {
            ConversionResult<List<TradeState>> result = converter.convert(in);
            if (!result.isSuccess()) {
                // A failed FpML→CDM leg is an ERROR; a NOMAP MXML→FpML leg surfaces
                // as success with an empty document, handled below — but the
                // converter returns failure on hard MXML→FpML errors too.
                return Status.ERROR;
            }
            List<TradeState> tradeStates = result.getResult();
            if (tradeStates == null || tradeStates.isEmpty()) {
                return Status.NOMAP; // MXML→FpML produced nothing → no CDM
            }
            if (tradeStates.size() != 1) {
                return Status.MULTI; // dataset assumes exactly one TradeState
            }
            String actual = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tradeStates.get(0));
            String expected = Files.readString(pair.expected());
            // Normalize party anonymization before comparison (harness-local only;
            // the shared SemanticDiff / FpML->CDM 530/530 guard is untouched).
            JsonNode exp = normalizeParties(JSON.readTree(expected));
            JsonNode act = normalizeParties(JSON.readTree(actual));
            SemanticDiff.Result d = SemanticDiff.compare(exp, act);
            return d.isEqual() ? Status.EQUAL : Status.DIFF;
        } catch (Exception e) {
            return Status.ERROR;
        }
    }

    /**
     * Neutralizes party-anonymization noise that is not economically meaningful:
     * the reference CDM was generated from the anonymized {@code _expected.xml}
     * (partyId BARCLAYS/party1), while the chain converts the raw MXML
     * (MXpress/MUREX). Mirrors {@code XmlSemanticDiff}'s party handling:
     * <ul>
     *   <li>sort {@code trade.party[]} by {@code meta.externalKey} (order-independent);</li>
     *   <li>blank {@code partyId[].identifier.value} (the anonymized display name).</li>
     * </ul>
     * The {@code externalKey} and all {@code href}/reference values are left intact,
     * so a counterparty collapse (payer==receiver) is still caught. Applied to both
     * sides; no change to the shared comparator.
     */
    private static JsonNode normalizeParties(JsonNode root) {
        JsonNode trade = root.get("trade");
        if (trade == null || !trade.has("party")) return root;
        JsonNode partyNode = trade.get("party");
        if (!(partyNode instanceof ArrayNode)) return root;
        ArrayNode parties = (ArrayNode) partyNode;

        for (JsonNode p : parties) {
            JsonNode ids = p.get("partyId");
            if (ids instanceof ArrayNode) {
                for (JsonNode id : ids) {
                    JsonNode ident = id.get("identifier");
                    if (ident instanceof ObjectNode && ident.has("value")) {
                        ((ObjectNode) ident).put("value", "");
                    }
                }
            }
        }

        // sort by externalKey so party ordering is irrelevant
        List<JsonNode> sorted = new java.util.ArrayList<>();
        parties.forEach(sorted::add);
        sorted.sort(java.util.Comparator.comparing(MxmlToCdmReport::externalKeyOf));
        ArrayNode rebuilt = parties.removeAll();
        sorted.forEach(rebuilt::add);
        return root;
    }

    private static String externalKeyOf(JsonNode party) {
        JsonNode meta = party.get("meta");
        JsonNode k = meta != null ? meta.get("externalKey") : null;
        return k != null ? k.asText() : "";
    }

    private void printReport(Map<String, int[]> byCat, int total,
                             int equal, int diff, int nomap, int multi, int error) {
        System.out.println("# MXML->CDM validation report (chaining MXML->FpML->CDM)");
        System.out.println();
        System.out.printf("%-16s %6s %6s %6s %6s %6s %6s%n",
                "Category", "EQUAL", "DIFF", "NOMAP", "MULTI", "ERROR", "Total");
        System.out.println("-".repeat(64));
        for (Map.Entry<String, int[]> e : byCat.entrySet()) {
            int[] r = e.getValue();
            System.out.printf("%-16s %6d %6d %6d %6d %6d %6d%n",
                    e.getKey(), r[0], r[1], r[2], r[3], r[4], r[5]);
        }
        System.out.println("-".repeat(64));
        System.out.printf("%-16s %6d %6d %6d %6d %6d %6d%n",
                "TOTAL", equal, diff, nomap, multi, error, total);
        System.out.println();
        double pct = total == 0 ? 0.0 : (100.0 * equal / total);
        System.out.printf("EQUAL: %d/%d (%.1f%%)  DIFF: %d  NOMAP: %d  MULTI: %d  ERROR: %d%n",
                equal, total, pct, diff, nomap, multi, error);
    }
}
