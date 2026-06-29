package io.fpmlcdm.core.validation;

import io.fpmlcdm.core.conversion.ConversionError;
import io.fpmlcdm.core.conversion.ConversionWarning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ValidationResult {
    
    private final boolean valid;
    private final List<ConversionError> errors;
    private final List<ConversionWarning> warnings;
    private final String validatorName;
    
    private ValidationResult(boolean valid, List<ConversionError> errors, 
                             List<ConversionWarning> warnings, String validatorName) {
        this.valid = valid;
        this.errors = errors != null ? new ArrayList<>(errors) : new ArrayList<>();
        this.warnings = warnings != null ? new ArrayList<>(warnings) : new ArrayList<>();
        this.validatorName = validatorName;
    }
    
    public static ValidationResult valid() {
        return new ValidationResult(true, Collections.emptyList(), Collections.emptyList(), null);
    }
    
    public static ValidationResult valid(String validatorName) {
        return new ValidationResult(true, Collections.emptyList(), Collections.emptyList(), validatorName);
    }
    
    public static ValidationResult invalid(List<ConversionError> errors) {
        return new ValidationResult(false, errors, Collections.emptyList(), null);
    }
    
    public static ValidationResult invalid(List<ConversionError> errors, String validatorName) {
        return new ValidationResult(false, errors, Collections.emptyList(), validatorName);
    }
    
    public static ValidationResult of(List<ConversionError> errors, List<ConversionWarning> warnings) {
        return new ValidationResult(errors == null || errors.isEmpty(), errors, warnings, null);
    }
    
    public static ValidationResult of(List<ConversionError> errors, List<ConversionWarning> warnings, 
                                       String validatorName) {
        return new ValidationResult(errors == null || errors.isEmpty(), errors, warnings, validatorName);
    }
    
    public static ValidationResult error(ConversionError error) {
        return new ValidationResult(false, List.of(error), Collections.emptyList(), null);
    }
    
    public static ValidationResult warning(ConversionWarning warning) {
        return new ValidationResult(true, Collections.emptyList(), List.of(warning), null);
    }
    
    public boolean isValid() { return valid; }
    public boolean hasErrors() { return !errors.isEmpty(); }
    public boolean hasWarnings() { return !warnings.isEmpty(); }
    
    public List<ConversionError> getErrors() { return Collections.unmodifiableList(errors); }
    public List<ConversionWarning> getWarnings() { return Collections.unmodifiableList(warnings); }
    public String getValidatorName() { return validatorName; }
    
    public int getErrorCount() { return errors.size(); }
    public int getWarningCount() { return warnings.size(); }
    
    public ValidationResult merge(ValidationResult other) {
        List<ConversionError> mergedErrors = new ArrayList<>(this.errors);
        mergedErrors.addAll(other.errors);
        
        List<ConversionWarning> mergedWarnings = new ArrayList<>(this.warnings);
        mergedWarnings.addAll(other.warnings);
        
        return new ValidationResult(
            mergedErrors.isEmpty(),
            mergedErrors,
            mergedWarnings,
            this.validatorName != null ? this.validatorName : other.validatorName
        );
    }
    
    @Override
    public String toString() {
        if (valid && warnings.isEmpty()) {
            return "Valid";
        }
        if (valid) {
            return String.format("Valid (warnings=%d)", warnings.size());
        }
        return String.format("Invalid (errors=%d, warnings=%d)", errors.size(), warnings.size());
    }
}
