package io.fpmlcdm.core.conversion;

import io.fpmlcdm.core.error.ErrorHandler;

public interface ConversionContext {
    
    ErrorHandler getErrorHandler();
    
    void addError(ConversionError error);
    
    void addWarning(ConversionWarning warning);
    
    boolean hasErrors();
    
    boolean hasWarnings();
    
    String generateId(String prefix);
    
    <T> void register(String key, T value);
    
    <T> T lookup(String key);
    
    <T> T lookup(String key, Class<T> type);
}
