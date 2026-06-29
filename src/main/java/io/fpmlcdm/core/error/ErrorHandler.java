package io.fpmlcdm.core.error;

import io.fpmlcdm.core.conversion.ConversionError;
import io.fpmlcdm.core.conversion.ConversionWarning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ErrorHandler {
    
    private final List<ConversionError> errors = new ArrayList<>();
    private final List<ConversionWarning> warnings = new ArrayList<>();
    private final int maxErrors;
    private final int maxWarnings;
    
    public ErrorHandler() {
        this(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }
    
    public ErrorHandler(int maxErrors, int maxWarnings) {
        this.maxErrors = maxErrors;
        this.maxWarnings = maxWarnings;
    }
    
    public void addError(ConversionError error) {
        if (errors.size() < maxErrors) {
            errors.add(error);
        }
    }
    
    public void addWarning(ConversionWarning warning) {
        if (warnings.size() < maxWarnings) {
            warnings.add(warning);
        }
    }
    
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
    
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
    
    public boolean hasFatalErrors() {
        return errors.stream().anyMatch(ConversionError::isFatal);
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
    
    public void clear() {
        errors.clear();
        warnings.clear();
    }
    
    public void clearErrors() {
        errors.clear();
    }
    
    public void clearWarnings() {
        warnings.clear();
    }
    
    public void mergeFrom(ErrorHandler other) {
        other.errors.forEach(this::addError);
        other.warnings.forEach(this::addWarning);
    }
    
    public String getSummary() {
        return String.format("Errors: %d, Warnings: %d", errors.size(), warnings.size());
    }
    
    public String getDetailedReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Conversion Report ===\n");
        sb.append(String.format("Errors: %d\n", errors.size()));
        sb.append(String.format("Warnings: %d\n", warnings.size()));
        
        if (!errors.isEmpty()) {
            sb.append("\n--- Errors ---\n");
            for (int i = 0; i < errors.size(); i++) {
                sb.append(String.format("%d. %s\n", i + 1, errors.get(i)));
            }
        }
        
        if (!warnings.isEmpty()) {
            sb.append("\n--- Warnings ---\n");
            for (int i = 0; i < warnings.size(); i++) {
                sb.append(String.format("%d. %s\n", i + 1, warnings.get(i)));
            }
        }
        
        return sb.toString();
    }
}
