package com.ligero.validate;

import com.ligero.spi.RequestValidator;

/**
 * {@link RequestValidator} backed by Jakarta Bean Validation. Registered via
 * {@code ServiceLoader}, so adding {@code ligero-validation} to the classpath
 * is all a typed controller needs for {@code @Body @Valid} parameters.
 */
public final class BeanValidationRequestValidator implements RequestValidator {

    @Override
    public <T> T validate(T value) {
        return Validate.orThrow(value);
    }
}
