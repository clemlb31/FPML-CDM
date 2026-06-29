package io.fpmlcdm.mxml.fpml;

import io.fpmlcdm.core.conversion.BaseConversionContext;

/**
 * Per-conversion state for MXML→FpML. Tracks generated FpML ids, party
 * registrations and collected errors/warnings via the shared core context.
 */
public final class MxmlToFpmlContext extends BaseConversionContext {

    public MxmlToFpmlContext() {
        super();
    }

    /** Convenience: add a plain warning message. */
    public void addWarning(String message) {
        addWarning(io.fpmlcdm.core.conversion.ConversionWarning.warn(
            io.fpmlcdm.core.conversion.ConversionWarning.Category.POTENTIAL_ISSUE, message));
    }

    /** Convenience: add a plain mapping-error message. */
    public void addError(String message) {
        addError(io.fpmlcdm.core.conversion.ConversionError.mappingError(message));
    }
}
