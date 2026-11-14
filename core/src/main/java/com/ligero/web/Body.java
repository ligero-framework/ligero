package com.ligero.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a method parameter to the request body, deserialized from JSON into
 * the parameter's type (a record or class). {@code String} receives the raw
 * body and {@code byte[]} the raw bytes. An absent or malformed body answers
 * {@code 400}. Combine with {@link Valid} to validate it declaratively.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.PARAMETER)
public @interface Body {
}
