package io.fpmlcdm.core.conversion;

public final class ConversionError {
    
    public enum Severity {
        ERROR,
        FATAL
    }
    
    public enum Category {
        PARSE_ERROR,
        VALIDATION_ERROR,
        MAPPING_ERROR,
        MISSING_REQUIRED_FIELD,
        INVALID_VALUE,
        TYPE_MISMATCH,
        REFERENCE_ERROR,
        INTERNAL_ERROR
    }
    
    private final Severity severity;
    private final Category category;
    private final String code;
    private final String message;
    private final String location;
    private final Throwable cause;
    
    private ConversionError(Severity severity, Category category, String code, 
                            String message, String location, Throwable cause) {
        this.severity = severity;
        this.category = category;
        this.code = code;
        this.message = message;
        this.location = location;
        this.cause = cause;
    }
    
    public static ConversionError of(Severity severity, Category category, 
                                     String code, String message) {
        return new ConversionError(severity, category, code, message, null, null);
    }
    
    public static ConversionError of(Severity severity, Category category, 
                                     String code, String message, String location) {
        return new ConversionError(severity, category, code, message, location, null);
    }
    
    public static ConversionError of(Severity severity, Category category, 
                                     String code, String message, String location, Throwable cause) {
        return new ConversionError(severity, category, code, message, location, cause);
    }
    
    public static ConversionError error(Category category, String message) {
        return of(Severity.ERROR, category, null, message);
    }
    
    public static ConversionError error(Category category, String message, String location) {
        return of(Severity.ERROR, category, null, message, location);
    }
    
    public static ConversionError fatal(Category category, String message) {
        return of(Severity.FATAL, category, null, message);
    }
    
    public static ConversionError fatal(Category category, String message, Throwable cause) {
        return of(Severity.FATAL, category, null, message, null, cause);
    }
    
    public static ConversionError parseError(String message) {
        return error(Category.PARSE_ERROR, message);
    }
    
    public static ConversionError parseError(String message, String location) {
        return error(Category.PARSE_ERROR, message, location);
    }
    
    public static ConversionError missingField(String fieldName, String location) {
        return of(Severity.ERROR, Category.MISSING_REQUIRED_FIELD, null, 
                  "Missing required field: " + fieldName, location);
    }
    
    public static ConversionError invalidValue(String fieldName, String value, String location) {
        return of(Severity.ERROR, Category.INVALID_VALUE, null,
                  "Invalid value for field '" + fieldName + "': " + value, location);
    }
    
    public static ConversionError mappingError(String message) {
        return error(Category.MAPPING_ERROR, message);
    }
    
    public static ConversionError mappingError(String message, Throwable cause) {
        return of(Severity.ERROR, Category.MAPPING_ERROR, null, message, null, cause);
    }
    
    public Severity getSeverity() { return severity; }
    public Category getCategory() { return category; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
    public String getLocation() { return location; }
    public Throwable getCause() { return cause; }
    
    public boolean isFatal() { return severity == Severity.FATAL; }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(severity).append("]");
        if (category != null) {
            sb.append(" [").append(category).append("]");
        }
        if (code != null) {
            sb.append(" [").append(code).append("]");
        }
        sb.append(" ").append(message);
        if (location != null) {
            sb.append(" (at ").append(location).append(")");
        }
        return sb.toString();
    }
}
