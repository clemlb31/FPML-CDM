package io.fpmlcdm.core.conversion;

public final class ConversionWarning {
    
    public enum Category {
        MISSING_OPTIONAL_FIELD,
        DEFAULT_VALUE_USED,
        VALUE_TRUNCATED,
        VALUE_CONVERTED,
        INFORMATION_DISCARDED,
        NON_STANDARD_VALUE,
        DEPRECATED_FEATURE,
        POTENTIAL_ISSUE
    }
    
    private final Category category;
    private final String code;
    private final String message;
    private final String location;
    
    private ConversionWarning(Category category, String code, String message, String location) {
        this.category = category;
        this.code = code;
        this.message = message;
        this.location = location;
    }
    
    public static ConversionWarning of(Category category, String code, String message) {
        return new ConversionWarning(category, code, message, null);
    }
    
    public static ConversionWarning of(Category category, String code, String message, String location) {
        return new ConversionWarning(category, code, message, location);
    }
    
    public static ConversionWarning warn(Category category, String message) {
        return of(category, null, message);
    }
    
    public static ConversionWarning warn(Category category, String message, String location) {
        return of(category, null, message, location);
    }
    
    public static ConversionWarning missingOptionalField(String fieldName, String location) {
        return warn(Category.MISSING_OPTIONAL_FIELD, 
                    "Optional field not provided: " + fieldName, location);
    }
    
    public static ConversionWarning defaultValueUsed(String fieldName, Object defaultValue, String location) {
        return warn(Category.DEFAULT_VALUE_USED,
                    "Using default value for '" + fieldName + "': " + defaultValue, location);
    }
    
    public static ConversionWarning valueConverted(String fieldName, String fromValue, String toValue, String location) {
        return warn(Category.VALUE_CONVERTED,
                    "Converted '" + fieldName + "' from '" + fromValue + "' to '" + toValue + "'", location);
    }
    
    public static ConversionWarning informationDiscarded(String what, String reason, String location) {
        return warn(Category.INFORMATION_DISCARDED,
                    "Discarded " + what + ": " + reason, location);
    }
    
    public static ConversionWarning nonStandardValue(String fieldName, String value, String location) {
        return warn(Category.NON_STANDARD_VALUE,
                    "Non-standard value for '" + fieldName + "': " + value, location);
    }
    
    public Category getCategory() { return category; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
    public String getLocation() { return location; }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[WARNING]");
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
