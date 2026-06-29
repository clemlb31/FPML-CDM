package io.fpmlcdm.core.dataset;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Discovers {@link TestPair}s by walking a dataset's folder convention.
 *
 * <p>All datasets share the shape {@code <root>/<category>/<inputDir>/<stem>.<inputExt>}
 * with the expected output at {@code <root>/<category>/<expectedDir>/<stem>.<expectedExt>}.
 *
 * <pre>
 * FpML→CDM : root=data/ground_truth/fpml-cdm  input=fpml(.xml)  expected=cdm(.json)
 * MXML→FpML: root=data/ground_truth/mxml-fpml input=mxml(.xml)  expected=fpml(.xml)
 * MXML→CDM : root=data/generated/mxml-cdm     input=mxml(.xml)  expected=cdm(.json)
 * </pre>
 *
 * A pair is emitted only when <b>both</b> files exist and the expected file is
 * non-empty (mirrors the existing FpML→CDM test contract).
 */
public final class PairLoader {

    private final Path root;
    private final String inputDir;
    private final String inputExt;
    private final String expectedDir;
    private final String expectedExt;

    public PairLoader(Path root, String inputDir, String inputExt,
                      String expectedDir, String expectedExt) {
        this.root = root;
        this.inputDir = inputDir;
        this.inputExt = normalizeExt(inputExt);
        this.expectedDir = expectedDir;
        this.expectedExt = normalizeExt(expectedExt);
    }

    /* ──────────────── factory shortcuts ──────────────── */

    public static PairLoader fpmlToCdm(Path groundTruthRoot) {
        return new PairLoader(groundTruthRoot, "fpml", "xml", "cdm", "json");
    }

    public static PairLoader mxmlToFpml(Path groundTruthRoot) {
        return new PairLoader(groundTruthRoot, "mxml", "xml", "fpml", "xml");
    }

    public static PairLoader mxmlToCdm(Path generatedRoot) {
        return new PairLoader(generatedRoot, "mxml", "xml", "cdm", "json");
    }

    /* ──────────────── discovery ──────────────── */

    /** @return all pairs across every category, sorted by id, where both files exist
     *          and the expected file is non-empty. */
    public List<TestPair> load() {
        if (!Files.isDirectory(root)) {
            throw new UncheckedIOException(new IOException("Dataset root not found: " + root));
        }
        List<TestPair> pairs = new ArrayList<>();
        try (Stream<Path> categories = Files.list(root)) {
            List<Path> catDirs = categories.filter(Files::isDirectory)
                    .sorted()
                    .collect(Collectors.toList());
            for (Path catDir : catDirs) {
                pairs.addAll(loadCategory(catDir));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        pairs.sort(Comparator.comparing(TestPair::id));
        return pairs;
    }

    private List<TestPair> loadCategory(Path catDir) throws IOException {
        String category = catDir.getFileName().toString();
        Path inDir = catDir.resolve(inputDir);
        Path expDir = catDir.resolve(expectedDir);
        if (!Files.isDirectory(inDir) || !Files.isDirectory(expDir)) {
            return List.of();
        }
        List<TestPair> out = new ArrayList<>();
        try (Stream<Path> inputs = Files.list(inDir)) {
            List<Path> inFiles = inputs
                    .filter(p -> p.getFileName().toString().endsWith(inputExt))
                    .sorted()
                    .collect(Collectors.toList());
            for (Path in : inFiles) {
                String stem = stripExt(in.getFileName().toString(), inputExt);
                Path expected = expDir.resolve(stem + expectedExt);
                if (Files.exists(expected) && Files.size(expected) > 0) {
                    out.add(new TestPair(category, stem, in, expected));
                }
            }
        }
        return out;
    }

    /* ──────────────── helpers ──────────────── */

    private static String normalizeExt(String ext) {
        return ext.startsWith(".") ? ext : "." + ext;
    }

    private static String stripExt(String fileName, String ext) {
        return fileName.endsWith(ext)
                ? fileName.substring(0, fileName.length() - ext.length())
                : fileName;
    }
}
