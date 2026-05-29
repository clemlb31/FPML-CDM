package io.fpmlcdm.validate;

import cdm.event.common.TradeState;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.regnosys.rosetta.common.validation.RosettaTypeValidator;
import com.regnosys.rosetta.common.validation.ValidationReport;
import com.rosetta.model.lib.validation.ValidationResult;
import org.finos.cdm.CdmRuntimeModule;

import java.util.List;

/**
 * Reference-free validation of a CDM {@link TradeState} using CDM's own data rules.
 *
 * Unlike {@link io.fpmlcdm.report.SemanticDiff} (which needs a golden reference JSON),
 * this validates the structural and conditional invariants defined in the CDM model
 * itself — cardinalities, choice constraints, and {@code condition} rules. This is the
 * validation signal that transposes to MXML→CDM, where no reference output exists.
 */
public final class CdmValidator {

    private final RosettaTypeValidator validator;

    public CdmValidator() {
        Injector injector = Guice.createInjector(new CdmRuntimeModule());
        this.validator = injector.getInstance(RosettaTypeValidator.class);
    }

    public Result validate(TradeState tradeState) {
        ValidationReport report = validator.runProcessStep(TradeState.class, tradeState);
        List<ValidationResult<?>> failures = report.validationFailures();
        List<String> messages = failures.stream()
                .map(f -> "[" + f.getValidationType() + "] " + f.getModelObjectName() + "." + f.getName()
                        + " -> " + f.getFailureReason().orElse(""))
                .toList();
        return new Result(report.success(), messages);
    }

    public record Result(boolean success, List<String> failures) {
        public int failureCount() { return failures.size(); }
        @Override public String toString() {
            return success ? "<valid>" : String.join("\n", failures);
        }
    }
}
