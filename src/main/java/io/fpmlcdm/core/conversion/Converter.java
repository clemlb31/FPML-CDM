package io.fpmlcdm.core.conversion;

import java.nio.file.Path;
import java.util.List;

public interface Converter<I, O> {
    
    String getName();
    
    String getInputFormat();
    
    String getOutputFormat();
    
    ConversionResult<O> convert(I input);
    
    ConversionResult<O> convert(I input, ConversionContext context);
    
    ConversionResult<List<ConversionResult<O>>> convertBatch(List<I> inputs);
    
    ConversionResult<List<ConversionResult<O>>> convertBatch(List<I> inputs, ConversionContext parentContext);
    
    boolean canConvert(I input);
    
    String describe();
    
    default Converter<I, O> withValidation(boolean enabled) {
        return this;
    }
    
    default Converter<I, O> withStrictMode(boolean enabled) {
        return this;
    }
}
