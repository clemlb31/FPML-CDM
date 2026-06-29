package io.fpmlcdm.cdm.fpml;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates HTML and Markdown reports for CDM-to-FpML conversion results.
 * Mirrors the existing ReportWriter.java for the FpML-to-CDM direction.
 */
public class CdmToFpmlReportWriter {

    public static class ConversionResult {
        private final String category;
        private final String filename;
        private final String status; // PASS, MISMATCH, ERROR
        private final int diffCount;
        private final String errorMessage;

        public ConversionResult(String category, String filename, String status, int diffCount, String errorMessage) {
            this.category = category;
            this.filename = filename;
            this.status = status;
            this.diffCount = diffCount;
            this.errorMessage = errorMessage != null ? errorMessage : "";
        }

        public String getCategory() { return category; }
        public String getFilename() { return filename; }
        public String getStatus() { return status; }
        public int getDiffCount() { return diffCount; }
        public String getErrorMessage() { return errorMessage; }
    }

    /**
     * Generate a Markdown report from conversion results.
     */
    public static void writeMarkdown(List<ConversionResult> results, Path outputPath) throws IOException {
        Map<String, int[]> categoryStats = new LinkedHashMap<>();
        int totalPass = 0;
        int totalMismatch = 0;
        int totalError = 0;

        for (ConversionResult r : results) {
            categoryStats.computeIfAbsent(r.getCategory(), k -> new int[]{0, 0, 0});
            int[] stats = categoryStats.get(r.getCategory());
            if (r.getStatus().equals("PASS")) {
                stats[0]++;
                totalPass++;
            } else if (r.getStatus().equals("MISMATCH")) {
                stats[1]++;
                totalMismatch++;
            } else {
                stats[2]++;
                totalError++;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# CDM to FpML Conversion Report\n\n");
        sb.append("## Summary\n\n");
        sb.append("| Metric | Count |\n");
        sb.append("|--------|------|\n");
        sb.append("| Total Files | ").append(results.size()).append(" |\n");
        sb.append("| Passed (0 diffs) | ").append(totalPass).append(" |\n");
        sb.append("| Mismatched (diffs) | ").append(totalMismatch).append(" |\n");
        sb.append("| Errors | ").append(totalError).append(" |\n");
        sb.append("| Conversion Rate | ").append(String.format("%.1f%%", 100.0 * totalPass / Math.max(1, results.size()))).append(" |\n\n");

        sb.append("## Results by Category\n\n");
        sb.append("| Category | Pass | Mismatch | Error | Rate |\n");
        sb.append("|----------|------|----------|-------|------|\n");
        for (Map.Entry<String, int[]> entry : categoryStats.entrySet()) {
            int[] stats = entry.getValue();
            int catTotal = stats[0] + stats[1] + stats[2];
            sb.append("| ").append(entry.getKey())
              .append(" | ").append(stats[0])
              .append(" | ").append(stats[1])
              .append(" | ").append(stats[2])
              .append(" | ").append(String.format("%.1f%%", 100.0 * stats[0] / Math.max(1, catTotal)))
              .append(" |\n");
        }

        sb.append("\n## Detailed Results\n\n");
        sb.append("| Category | File | Status | Diffs | Error |\n");
        sb.append("|----------|------|--------|-------|-------|\n");
        for (ConversionResult r : results) {
            String errorDisplay = r.getErrorMessage().isEmpty() ? "" : r.getErrorMessage().substring(0, Math.min(50, r.getErrorMessage().length()));
            sb.append("| ").append(r.getCategory())
              .append(" | ").append(r.getFilename())
              .append(" | ").append(r.getStatus())
              .append(" | ").append(r.getDiffCount())
              .append(" | ").append(errorDisplay)
              .append(" |\n");
        }

        Files.writeString(outputPath, sb.toString());
    }

    /**
     * Generate an HTML report from conversion results.
     */
    public static void writeHtml(List<ConversionResult> results, Path outputPath) throws IOException {
        Map<String, int[]> categoryStats = new LinkedHashMap<>();
        int totalPass = 0;
        int totalMismatch = 0;
        int totalError = 0;

        for (ConversionResult r : results) {
            categoryStats.computeIfAbsent(r.getCategory(), k -> new int[]{0, 0, 0});
            int[] stats = categoryStats.get(r.getCategory());
            if (r.getStatus().equals("PASS")) {
                stats[0]++;
                totalPass++;
            } else if (r.getStatus().equals("MISMATCH")) {
                stats[1]++;
                totalMismatch++;
            } else {
                stats[2]++;
                totalError++;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html><head><title>CDM to FpML Conversion Report</title>");
        sb.append("<style>table{border-collapse:collapse;width:100%;font-family:monospace}");
        sb.append("th,td{border:1px solid #ddd;padding:8px;text-align:left}");
        sb.append("th{background-color:#4CAF50;color:white}");
        sb.append(".pass{color:green} .mismatch{color:orange} .error{color:red}");
        sb.append("</style></head><body>");
        sb.append("<h1>CDM to FpML Conversion Report</h1>");
        sb.append("<h2>Summary</h2>");
        sb.append("<table><tr><th>Metric</th><th>Count</th></tr>");
        sb.append("<tr><td>Total Files</td><td>").append(results.size()).append("</td></tr>");
        sb.append("<tr><td>Passed (0 diffs)</td><td class='pass'>").append(totalPass).append("</td></tr>");
        sb.append("<tr><td>Mismatched (diffs)</td><td class='mismatch'>").append(totalMismatch).append("</td></tr>");
        sb.append("<tr><td>Errors</td><td class='error'>").append(totalError).append("</td></tr>");
        sb.append("<tr><td>Conversion Rate</td><td>").append(String.format("%.1f%%", 100.0 * totalPass / Math.max(1, results.size()))).append("</td></tr>");
        sb.append("</table>");

        sb.append("<h2>Results by Category</h2>");
        sb.append("<table><tr><th>Category</th><th>Pass</th><th>Mismatch</th><th>Error</th><th>Rate</th></tr>");
        for (Map.Entry<String, int[]> entry : categoryStats.entrySet()) {
            int[] stats = entry.getValue();
            int catTotal = stats[0] + stats[1] + stats[2];
            sb.append("<tr><td>").append(entry.getKey()).append("</td>");
            sb.append("<td class='pass'>").append(stats[0]).append("</td>");
            sb.append("<td class='mismatch'>").append(stats[1]).append("</td>");
            sb.append("<td class='error'>").append(stats[2]).append("</td>");
            sb.append("<td>").append(String.format("%.1f%%", 100.0 * stats[0] / Math.max(1, catTotal))).append("</td></tr>");
        }
        sb.append("</table>");

        sb.append("<h2>Detailed Results</h2>");
        sb.append("<table><tr><th>Category</th><th>File</th><th>Status</th><th>Diffs</th><th>Error</th></tr>");
        for (ConversionResult r : results) {
            String cssClass = r.getStatus().equals("PASS") ? "pass" : (r.getStatus().equals("MISMATCH") ? "mismatch" : "error");
            String errorDisplay = r.getErrorMessage().isEmpty() ? "" : r.getErrorMessage().substring(0, Math.min(50, r.getErrorMessage().length()));
            sb.append("<tr><td>").append(r.getCategory()).append("</td>");
            sb.append("<td>").append(r.getFilename()).append("</td>");
            sb.append("<td class='").append(cssClass).append("'>").append(r.getStatus()).append("</td>");
            sb.append("<td>").append(r.getDiffCount()).append("</td>");
            sb.append("<td>").append(errorDisplay).append("</td></tr>");
        }
        sb.append("</table></body></html>");

        Files.writeString(outputPath, sb.toString());
    }

    /**
     * Write detailed diff for a single file.
     */
    public static void writeDetailedDiff(Path outputDir, String filename, String diff) throws IOException {
        Path diffPath = outputDir.resolve(filename.replace(".json", "") + "-diff.txt");
        Files.writeString(diffPath, diff, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Print a summary table to stdout.
     */
    public static void printSummary(List<ConversionResult> results) {
        Map<String, int[]> categoryStats = new LinkedHashMap<>();
        int totalPass = 0;
        int totalMismatch = 0;
        int totalError = 0;

        for (ConversionResult r : results) {
            categoryStats.computeIfAbsent(r.getCategory(), k -> new int[]{0, 0, 0});
            int[] stats = categoryStats.get(r.getCategory());
            if (r.getStatus().equals("PASS")) {
                stats[0]++;
                totalPass++;
            } else if (r.getStatus().equals("MISMATCH")) {
                stats[1]++;
                totalMismatch++;
            } else {
                stats[2]++;
                totalError++;
            }
        }

        System.out.println("\n=== CDM to FpML Conversion Summary ===");
        System.out.printf("Total: %d | Pass: %d | Mismatch: %d | Error: %d | Rate: %.1f%%\n\n",
                results.size(), totalPass, totalMismatch, totalError,
                100.0 * totalPass / Math.max(1, results.size()));

        System.out.printf("%-35s %6s %8s %6s %8s\n", "Category", "Pass", "Mismatch", "Error", "Rate");
        System.out.println("-".repeat(70));
        for (Map.Entry<String, int[]> entry : categoryStats.entrySet()) {
            int[] stats = entry.getValue();
            int catTotal = stats[0] + stats[1] + stats[2];
            System.out.printf("%-35s %6d %8d %6d %7.1f%%\n",
                    entry.getKey(), stats[0], stats[1], stats[2],
                    100.0 * stats[0] / Math.max(1, catTotal));
        }
        System.out.println();
    }
}
