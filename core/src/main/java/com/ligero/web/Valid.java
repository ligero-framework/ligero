package com.ligero.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates a {@link Body} parameter before the method runs, through the
 * {@link com.ligero.spi.RequestValidator} on the classpath
 * ({@code ligero-validation} provides one backed by Jakarta Bean Validation).
 * Invalid input answers {@code 400} listing every violation.
 *
 * <pre>{@code
 * record NewUser(@NotBlank String name, @Email String email) {}
 *
 * @Post
 * @Status(201)
 * User create(@Body @Valid NewUser user) { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.PARAMETER)
public @interface Valid {
}
