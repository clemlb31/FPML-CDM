package io.fpmlcdm.core.validation;

import io.fpmlcdm.core.error.ErrorHandler;

public interface ValidationContext {
    
    ErrorHandler getErrorHandler();
    
    boolean isStrictMode();
    
    ValidationContext strictMode(boolean strict);
    
    boolean isValidatorEnabled(String validatorName);
    
    ValidationContext enableValidator(String validatorName, boolean enabled);
    
    <T> void setProperty(String key, T value);
    
    <T> T getProperty(String key);
    
    <T> T getProperty(String key, Class<T> type);
    
    <T> T getProperty(String key, T defaultValue);
}
