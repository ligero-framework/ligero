package com.ligero.spi;

/**
 * SPI for declarative request validation, so the core needs no validation
 * library. {@code ligero-validation} provides the Jakarta Bean Validation
 * implementation and registers it via {@link java.util.ServiceLoader}; typed
 * controllers call it for parameters marked {@code @Valid}.
 */
public interface RequestValidator {

    /**
     * Returns {@code value} when it is valid.
     *
     * @throws com.ligero.http.BadRequestException describing every violation otherwise
     */
    <T> T validate(T value);
}
