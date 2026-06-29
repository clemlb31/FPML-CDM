package io.fpmlcdm.core.validation;

import io.fpmlcdm.core.conversion.ConversionResult;
import io.fpmlcdm.core.conversion.ConversionError;
import io.fpmlcdm.core.conversion.ConversionWarning;

import java.util.List;

public interface Validator<T> {
    
    String getName();
    
    String getDescription();
    
    ValidationResult validate(T input);
    
    ValidationResult validate(T input, ValidationContext context);
    
    boolean isEnabled();
    
    Validator<T> enabled(boolean enabled);
    
    default Validator<T> withSeverity(Severity severity) {
        return this;
    }
    
    enum Severity {
        ERROR,
        WARNING,
        INFO
    }
}
