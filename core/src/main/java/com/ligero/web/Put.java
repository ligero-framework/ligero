package com.ligero.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maps a controller method to {@code PUT} requests. The path is appended to
 * the prefix declared on the enclosing
 * {@link com.ligero.beans.stereotype.Controller @Controller}.
 *
 * <p>Read at compile time by {@code ligero-processor}, which generates the
 * plain {@code app.route(...)} registration — nothing is discovered or
 * invoked reflectively at runtime. See {@link com.ligero.web} for the whole
 * model.</p>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Put {

    /** Path relative to the controller prefix, e.g. {@code "/{id}"}; empty for the prefix itself. */
    String value() default "";
}
