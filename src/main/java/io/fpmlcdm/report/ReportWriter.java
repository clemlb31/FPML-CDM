package io.fpmlcdm.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Writes Markdown and HTML reports summarising a validation run. */
public final class ReportWriter {

    public static class Row {
        public final String path;
        public int tradeCount;
        public int diffCount;
        public String diffSummary = "";
        public String error;

        public Row(String path) { this.path = path; }

        public String category() {
            int dataIdx = path.indexOf("/data/train/");
            if (dataIdx < 0) return "uncategorised";
            String rest = path.substring(dataIdx + "/data/train/".length());
            int slash = rest.indexOf('/');
            return slash < 0 ? rest : rest.substring(0, slash);
        }

        public boolean passed() {
            return error == null && diffCount == 0;
        }
    }

    private ReportWriter() {}

    public static void writeMarkdown(Path target, List<Row> rows) throws IOException {
        Map<String, Counter> per = aggregate(rows);
        StringBuilder sb = new StringBuilder();
        sb.append("# FpML → CDM validation report\n\n");
        sb.append("## Summary by category\n\n");
        sb.append("| Category | Pairs | Pass | Fail | Coverage |\n");
        sb.append("|---|---:|---:|---:|---:|\n");
        int totalPair = 0, totalPass = 0;
        for (Map.Entry<String, Counter> e : per.entrySet()) {
            int n = e.getValue().total;
            int p = e.getValue().pass;
            totalPair += n;
            totalPass += p;
            sb.append("| ").append(e.getKey())
              .append(" | ").append(n)
              .append(" | ").append(p)
              .append(" | ").append(n - p)
              .append(" | ").append(pct(p, n)).append(" |\n");
        }
        sb.append("| **TOTAL** | **").append(totalPair)
          .append("** | **").append(totalPass)
          .append("** | **").append(totalPair - totalPass)
          .append("** | **").append(pct(totalPass, totalPair)).append("** |\n\n");

        sb.append("## Failures\n\n");
        for (Row r : rows) {
            if (r.passed()) continue;
            sb.append("### ").append(r.path).append("\n\n");
            if (r.error != null) {
                sb.append("Error: `").append(r.error).append("`\n\n");
            } else {
                sb.append("```\n").append(r.diffSummary).append("\n```\n\n");
            }
        }
        Files.writeString(target, sb.toString());
    }

    public static void writeHtml(Path target, List<Row> rows) throws IOException {
        Map<String, Counter> per = aggregate(rows);
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><meta charset='utf-8'>");
        sb.append("<title>FpML → CDM validation report</title>");
        sb.append("<style>body{font:14px/1.4 -apple-system,Segoe UI,sans-serif;margin:32px;}");
        sb.append("table{border-collapse:collapse;margin:8px 0;}");
        sb.append("th,td{border:1px solid #ddd;padding:6px 10px;text-align:left;}");
        sb.append("th{background:#f4f4f4;}");
        sb.append(".pass{background:#dfe;}.fail{background:#fdd;}");
        sb.append("pre{background:#f7f7f7;padding:8px;overflow:auto;}</style>");
        sb.append("<h1>FpML → CDM validation report</h1>");

        sb.append("<h2>Summary by category</h2><table>");
        sb.append("<tr><th>Category</th><th>Pairs</th><th>Pass</th><th>Fail</th><th>Coverage</th></tr>");
        int totalPair = 0, totalPass = 0;
        for (Map.Entry<String, Counter> e : per.entrySet()) {
            int n = e.getValue().total;
            int p = e.getValue().pass;
            totalPair += n;
            totalPass += p;
            String cls = p == n ? "pass" : "fail";
            sb.append("<tr class='").append(cls).append("'>")
              .append("<td>").append(e.getKey()).append("</td>")
              .append("<td>").append(n).append("</td>")
              .append("<td>").append(p).append("</td>")
              .append("<td>").append(n - p).append("</td>")
              .append("<td>").append(pct(p, n)).append("</td></tr>");
        }
        sb.append("<tr><th>TOTAL</th><th>").append(totalPair)
          .append("</th><th>").append(totalPass)
          .append("</th><th>").append(totalPair - totalPass)
          .append("</th><th>").append(pct(totalPass, totalPair)).append("</th></tr>");
        sb.append("</table>");

        sb.append("<h2>Failures</h2>");
        for (Row r : rows) {
            if (r.passed()) continue;
            sb.append("<h3>").append(escape(r.path)).append("</h3>");
            if (r.error != null) {
                sb.append("<p>Error: <code>").append(escape(r.error)).append("</code></p>");
            } else {
                sb.append("<pre>").append(escape(r.diffSummary)).append("</pre>");
            }
        }
        Files.writeString(target, sb.toString());
    }

    /* ──────────────── helpers ──────────────── */

    private static class Counter { int total; int pass; }

    private static Map<String, Counter> aggregate(List<Row> rows) {
        Map<String, Counter> per = new TreeMap<>();
        for (Row r : rows) {
            Counter c = per.computeIfAbsent(r.category(), k -> new Counter());
            c.total++;
            if (r.passed()) c.pass++;
        }
        return per;
    }

    private static String pct(int p, int n) {
        if (n == 0) return "—";
        return String.format("%.1f%%", 100.0 * p / n);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
