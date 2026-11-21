package com.ligero.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a method parameter to a request header (case-insensitive), with the
 * same conversion and optionality rules as {@link QueryParam}.
 *
 * <pre>{@code
 * @Get("/me")
 * Profile me(@HeaderParam("X-Tenant") String tenant) { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.PARAMETER)
public @interface HeaderParam {

    /** Header name, e.g. {@code "X-Tenant"}. */
    String value();

    /** Value used when the header is absent; empty means "no default". */
    String defaultValue() default "";
}
