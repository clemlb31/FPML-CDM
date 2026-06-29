package io.fpmlcdm.core.conversion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ConversionResult<T> {
    
    private final T result;
    private final List<ConversionError> errors;
    private final List<ConversionWarning> warnings;
    
    private ConversionResult(T result, List<ConversionError> errors, List<ConversionWarning> warnings) {
        this.result = result;
        this.errors = errors != null ? new ArrayList<>(errors) : new ArrayList<>();
        this.warnings = warnings != null ? new ArrayList<>(warnings) : new ArrayList<>();
    }
    
    public static <T> ConversionResult<T> success(T result) {
        return new ConversionResult<>(result, Collections.emptyList(), Collections.emptyList());
    }
    
    public static <T> ConversionResult<T> success(T result, List<ConversionWarning> warnings) {
        return new ConversionResult<>(result, Collections.emptyList(), warnings);
    }
    
    public static <T> ConversionResult<T> failure(List<ConversionError> errors) {
        return new ConversionResult<>(null, errors, Collections.emptyList());
    }
    
    public static <T> ConversionResult<T> failure(List<ConversionError> errors, List<ConversionWarning> warnings) {
        return new ConversionResult<>(null, errors, warnings);
    }
    
    public static <T> ConversionResult<T> of(T result, List<ConversionError> errors, List<ConversionWarning> warnings) {
        if (errors != null && !errors.isEmpty()) {
            return failure(errors, warnings);
        }
        return success(result, warnings);
    }
    
    public boolean isSuccess() {
        return errors.isEmpty();
    }
    
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
    
    public T getResult() {
        return result;
    }
    
    public List<ConversionError> getErrors() {
        return Collections.unmodifiableList(errors);
    }
    
    public List<ConversionWarning> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }
    
    public int getErrorCount() {
        return errors.size();
    }
    
    public int getWarningCount() {
        return warnings.size();
    }
    
    @Override
    public String toString() {
        if (isSuccess()) {
            return String.format("Success(warnings=%d)", warnings.size());
        }
        return String.format("Failure(errors=%d, warnings=%d)", errors.size(), warnings.size());
    }
}
