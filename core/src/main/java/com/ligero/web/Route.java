package com.ligero.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maps a controller method to an arbitrary HTTP method — for the verbs that
 * have no dedicated annotation ({@code HEAD}, {@code OPTIONS}, {@code QUERY},
 * ...).
 *
 * <pre>{@code
 * @Route(method = "QUERY", path = "/search")
 * List<Product> search(@Body SearchRequest request) { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Route {

    /** HTTP method, e.g. {@code "QUERY"}. */
    String method();

    /** Path relative to the controller prefix; empty for the prefix itself. */
    String path() default "";
}
