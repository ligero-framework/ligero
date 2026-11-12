package com.ligero.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a method parameter to a {@code {name}} (or {@code *name}) segment of
 * the route. The value is converted to the parameter's type — {@code String},
 * {@code int}, {@code long}, {@code double}, {@code boolean}, their boxed
 * forms, {@code UUID}, {@code BigDecimal} or an enum — and a value that does
 * not convert answers {@code 400} before the method runs.
 *
 * <p>The name defaults to the Java parameter name, and the processor checks
 * at compile time that the route actually declares it.</p>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.PARAMETER)
public @interface PathParam {

    /** Segment name; defaults to the parameter name. */
    String value() default "";
}
