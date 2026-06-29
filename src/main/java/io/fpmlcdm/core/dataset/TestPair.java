package io.fpmlcdm.core.dataset;

import java.nio.file.Path;

/**
 * One discovered test pair: a source input file and its expected-output file,
 * tagged with the dataset category and a stable stem (base file name).
 *
 * <p>Format-agnostic — used for FpML→CDM ({@code fpml}/{@code cdm}),
 * MXML→FpML ({@code mxml}/{@code fpml}) and MXML→CDM ({@code mxml}/{@code cdm}).
 */
public record TestPair(String category, String stem, Path input, Path expected) {

    /** @return {@code "<category>/<stem>"} — stable identifier for reports/JUnit display. */
    public String id() {
        return category + "/" + stem;
    }

    @Override
    public String toString() {
        return id();
    }
}
