package io.fpmlcdm.mxml.fpml;

import io.fpmlcdm.core.conversion.ConversionResult;
import io.fpmlcdm.core.dataset.PairLoader;
import io.fpmlcdm.core.dataset.TestPair;
import io.fpmlcdm.core.xml.XmlUtils;
import io.fpmlcdm.report.XmlSemanticDiff;
import org.w3c.dom.Document;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Runnable validation report for the MXML→FpML dataset.
 *
 * <p>Walks {@code data/ground_truth/mxml-fpml/<category>/{mxml,fpml}}, runs
 * {@link MxmlToFpmlConverter} on each MXML input, and compares the produced FpML
 * against the committed {@code _expected.xml} using {@link XmlSemanticDiff}
 * (timestamp-tolerant, numeric-tolerant — see the comparator's javadoc).
 *
 * <p>Status classes per pair:
 * <ul>
 *   <li><b>EQUAL</b>   — output semantically matches expected;</li>
 *   <li><b>DIFF</b>    — output produced but differs;</li>
 *   <li><b>NOMAP</b>   — no product mapper registered (converter returned empty);</li>
 *   <li><b>ERROR</b>   — conversion threw / hard failure.</li>
 * </ul>
 *
 * <p>Until the XSLT mappers are ported, the expected state is NOMAP for all pairs —
 * the harness then measures real progress as each mapper lands.
 *
 * <pre>
 * java io.fpmlcdm.mxml.fpml.MxmlToFpmlReport [datasetRoot]
 * </pre>
 */
public final class MxmlToFpmlReport {

    enum Status { EQUAL, DIFF, NOMAP, ERROR }

    public static void main(String[] args) {
        Path root = Path.of(args.length > 0 ? args[0] : "data/ground_truth/mxml-fpml");
        MxmlToFpmlReport report = new MxmlToFpmlReport();
        int exit = report.run(root);
        System.exit(exit);
    }

    int run(Path root) {
        List<TestPair> pairs = PairLoader.mxmlToFpml(root).load();
        MxmlToFpmlConverter converter = new MxmlToFpmlConverter();

        // category -> [equal, diff, nomap, error, total]
        Map<String, int[]> byCat = new TreeMap<>();
        int equal = 0, diff = 0, nomap = 0, error = 0;

        for (TestPair pair : pairs) {
            Status st = classify(converter, pair);
            int[] row = byCat.computeIfAbsent(pair.category(), k -> new int[5]);
            row[4]++;
            switch (st) {
                case EQUAL -> { row[0]++; equal++; }
                case DIFF  -> { row[1]++; diff++; }
                case NOMAP -> { row[2]++; nomap++; }
                case ERROR -> { row[3]++; error++; }
            }
        }

        printReport(byCat, pairs.size(), equal, diff, nomap, error);
        // Exit non-zero only on hard ERRORs (NOMAP/DIFF are expected while porting).
        return error == 0 ? 0 : 1;
    }

    private Status classify(MxmlToFpmlConverter converter, TestPair pair) {
        try (InputStream in = Files.newInputStream(pair.input())) {
            ConversionResult<Document> result = converter.convert(in);
            if (!result.isSuccess()) {
                return Status.ERROR;
            }
            Document produced = result.getResult();
            if (produced == null || produced.getDocumentElement() == null) {
                return Status.NOMAP; // no mapper produced output
            }
            String producedXml = XmlUtils.serialize(produced);
            String expectedXml = Files.readString(pair.expected());
            XmlSemanticDiff.Result d = XmlSemanticDiff.compare(expectedXml, producedXml);
            return d.isEqual() ? Status.EQUAL : Status.DIFF;
        } catch (Exception e) {
            return Status.ERROR;
        }
    }

    private void printReport(Map<String, int[]> byCat, int total,
                             int equal, int diff, int nomap, int error) {
        System.out.println("# MXML→FpML validation report");
        System.out.println();
        System.out.printf("%-16s %6s %6s %6s %6s %6s%n",
                "Category", "EQUAL", "DIFF", "NOMAP", "ERROR", "Total");
        System.out.println("-".repeat(58));
        for (Map.Entry<String, int[]> e : byCat.entrySet()) {
            int[] r = e.getValue();
            System.out.printf("%-16s %6d %6d %6d %6d %6d%n",
                    e.getKey(), r[0], r[1], r[2], r[3], r[4]);
        }
        System.out.println("-".repeat(58));
        System.out.printf("%-16s %6d %6d %6d %6d %6d%n",
                "TOTAL", equal, diff, nomap, error, total);
        System.out.println();
        double pct = total == 0 ? 0.0 : (100.0 * equal / total);
        System.out.printf("EQUAL: %d/%d (%.1f%%)  DIFF: %d  NOMAP: %d  ERROR: %d%n",
                equal, total, pct, diff, nomap, error);
    }
}
