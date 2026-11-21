/**
 * Typed controllers: declare an endpoint as an ordinary Java method and let
 * the compiler check it.
 *
 * <pre>{@code
 * @Controller("/api/products")
 * public final class ProductController {
 *
 *     private final ProductService products;
 *
 *     public ProductController(ProductService products) {
 *         this.products = products;
 *     }
 *
 *     @Get
 *     List<Product> list(@QueryParam(defaultValue = "1") int page) {
 *         return products.list(page);
 *     }
 *
 *     @Get("/{id}")
 *     Product get(@PathParam long id) {
 *         return products.get(id);
 *     }
 *
 *     @Post
 *     @Status(201)
 *     Product create(@Body @Valid NewProduct product) {
 *         return products.create(product);
 *     }
 *
 *     @Delete("/{id}")
 *     void delete(@PathParam long id) {
 *         products.delete(id);
 *     }
 * }
 * }</pre>
 *
 * <p>Inputs are parameters with a declared type, the output is the return
 * type, and the method can be unit-tested by calling it. What a method
 * returns is written by {@link com.ligero.http.Context#respond(Object)}:
 * objects as JSON, {@code String} as text, {@code void}/{@code null} as
 * {@code 204}, an empty {@code Optional} as {@code 404}, and a
 * {@link com.ligero.http.Response} when status or headers are decided at
 * runtime.</p>
 *
 * <p>There is no runtime magic behind the annotations. They have
 * {@code SOURCE} retention: {@code ligero-processor} reads them while
 * compiling and writes a {@code <Controller>Routes} class next to the
 * controller containing the same {@code app.route(...)} calls you would have
 * written by hand — open it to see exactly what runs. Mistakes such as a
 * {@code @PathParam} the route does not declare, an unsupported parameter
 * type or a private handler method are compile errors, not surprises in
 * production.</p>
 *
 * <p>With the processor's generated modules the routes are mounted for you;
 * with explicit wiring, call {@code ProductControllerRoutes.mount(app, controller)}.
 * The functional style ({@code app.get("/x", ctx -> ...)}) keeps working and
 * can be mixed freely with typed controllers.</p>
 */
package com.ligero.web;
