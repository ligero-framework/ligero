package com.ligero.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The API version a controller method serves, for applications that use
 * {@link com.ligero.versioning.ApiVersioning}. {@code "2"} serves exactly
 * version 2; {@code "2+"} serves 2 and every later version without a more
 * specific method. Several methods may share a verb and path when their
 * versions differ; a method on that route without {@code @ApiVersion} serves
 * the requests no versioned method covers. On the controller class, it is
 * the default for every method that does not say otherwise.
 *
 * <pre>{@code
 * @Get("/{id}") @ApiVersion("1")
 * ProductV1 getV1(@PathParam long id) { ... }
 *
 * @Get("/{id}") @ApiVersion("2+")
 * Product get(@PathParam long id) { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface ApiVersion {

    String value();
}
