package com.ligero.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares an interface as a client of a remote HTTP API. With
 * {@code ligero-processor} on the annotation processor path and
 * {@code ligero-http-client} on the classpath, the compiler generates
 * {@code <Interface>Client}, an implementation that makes the calls — no
 * proxies, no reflection. The same annotations as typed controllers describe
 * each call:
 *
 * <pre>{@code
 * @ClientApi("/api/products")
 * public interface ProductsApi {
 *
 *     @Get("/{id}")
 *     Optional<Product> find(@PathParam long id);          // 404 -> Optional.empty()
 *
 *     @Get
 *     List<Product> search(@QueryParam String name, @HeaderParam("X-Tenant") String tenant);
 *
 *     @Post
 *     Product create(@Body NewProduct product);
 *
 *     @Delete("/{id}")
 *     void delete(@PathParam long id);
 * }
 *
 * ProductsApi products = new ProductsApiClient(RestClient.builder().baseUrl("https://catalog.internal").build());
 * }</pre>
 *
 * <p>Return types: {@code void}, a class or record (read as JSON),
 * {@code String}, {@code byte[]}, {@code Optional<T>}, {@code List<T>},
 * {@code Set<T>} and {@code ClientResponse} (the raw response, whatever its
 * status). Error statuses throw {@code HttpStatusException}. Mistakes — a
 * path parameter the path does not have, a parameter without an annotation,
 * an unsupported return type — are compile errors.</p>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface ClientApi {

    /** Path prefix of every call, relative to the client's base URL. */
    String value() default "";
}
