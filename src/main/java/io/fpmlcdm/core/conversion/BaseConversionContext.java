package io.fpmlcdm.core.conversion;

import io.fpmlcdm.core.error.ErrorHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class BaseConversionContext implements ConversionContext {
    
    private final ErrorHandler errorHandler;
    private final Map<String, Object> registry;
    private final Map<String, AtomicInteger> idCounters;
    
    protected BaseConversionContext() {
        this.errorHandler = new ErrorHandler();
        this.registry = new HashMap<>();
        this.idCounters = new HashMap<>();
    }
    
    protected BaseConversionContext(ErrorHandler errorHandler) {
        this.errorHandler = errorHandler != null ? errorHandler : new ErrorHandler();
        this.registry = new HashMap<>();
        this.idCounters = new HashMap<>();
    }
    
    @Override
    public ErrorHandler getErrorHandler() {
        return errorHandler;
    }
    
    @Override
    public void addError(ConversionError error) {
        errorHandler.addError(error);
    }
    
    @Override
    public void addWarning(ConversionWarning warning) {
        errorHandler.addWarning(warning);
    }
    
    @Override
    public boolean hasErrors() {
        return errorHandler.hasErrors();
    }
    
    @Override
    public boolean hasWarnings() {
        return errorHandler.hasWarnings();
    }
    
    @Override
    public synchronized String generateId(String prefix) {
        AtomicInteger counter = idCounters.computeIfAbsent(prefix, k -> new AtomicInteger(0));
        int id = counter.incrementAndGet();
        return prefix + "_" + id;
    }
    
    @Override
    public synchronized <T> void register(String key, T value) {
        registry.put(key, value);
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> T lookup(String key) {
        return (T) registry.get(key);
    }
    
    @Override
    public synchronized <T> T lookup(String key, Class<T> type) {
        Object value = registry.get(key);
        if (value != null && type.isInstance(value)) {
            return type.cast(value);
        }
        return null;
    }
    
    public synchronized void clear() {
        registry.clear();
        idCounters.clear();
        errorHandler.clear();
    }
}
