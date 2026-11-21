package com.ligero.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a method parameter to a query-string parameter, converted to the
 * parameter's type (same types as {@link PathParam}, plus
 * {@code List<String>} for repeated parameters).
 *
 * <p>A primitive parameter is required unless {@link #defaultValue()} is
 * set — a missing value answers {@code 400}. A reference-typed parameter is
 * optional and receives {@code null} when absent.</p>
 *
 * <pre>{@code
 * @Get
 * List<Product> list(@QueryParam(defaultValue = "1") int page,
 *                    @QueryParam String category) { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.PARAMETER)
public @interface QueryParam {

    /** Query parameter name; defaults to the Java parameter name. */
    String value() default "";

    /** Value used when the parameter is absent; empty means "no default". */
    String defaultValue() default "";
}
