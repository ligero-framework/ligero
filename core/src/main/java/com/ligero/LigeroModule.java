package com.ligero;

import com.ligero.beans.Beans;

/**
 * A feature module: one vertical slice of the application (its controllers,
 * services, repositories and routes) declared in a single place, in plain
 * code. Inspired by Angular modules, minus the magic — a module is just a
 * class you instantiate and list.
 *
 * <pre>{@code
 * public final class ProductsModule implements LigeroModule {
 *
 *     @Override
 *     public void beans(Beans.Builder builder) {
 *         builder.bind(ProductRepository.class, b -> new JdbcProductRepository(b.get(DataSource.class)))
 *                .bind(ProductService.class,    b -> new DefaultProductService(b.get(ProductRepository.class)))
 *                .bind(ProductController.class, b -> new ProductController(b.get(ProductService.class)));
 *     }
 *
 *     @Override
 *     public void routes(Ligero app, Beans beans) {
 *         beans.get(ProductController.class).register(app);
 *     }
 * }
 *
 * // App startup stays free of wiring:
 * Modules.install(app, new DbModule(), new ProductsModule(), new UsersModule());
 * }</pre>
 *
 * <p>All modules contribute to a <em>single</em> {@link Beans} container, so
 * a module can depend on beans bound by another (e.g. {@code DataSource}
 * from an infrastructure module). Binding the same type twice — even from
 * different modules — fails fast with a duplicate-binding error.</p>
 */
public interface LigeroModule {

    /** Contributes this module's bindings to the shared container. */
    void beans(Beans.Builder builder);

    /**
     * Mounts this module's routes. Called after the whole container has been
     * built and validated, so {@code beans.get(...)} is safe here.
     */
    void routes(Ligero app, Beans beans);
}
