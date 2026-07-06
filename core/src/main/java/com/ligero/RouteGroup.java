package com.ligero;

import com.ligero.http.Handler;
import com.ligero.middleware.Middleware;
import com.ligero.router.PathNormalizer;

import java.util.function.Consumer;

/**
 * Route group sharing a path prefix (and optionally middleware), replacing
 * ad-hoc context-path tricks:
 *
 * <pre>{@code
 * app.group("/api/v1", api -> {
 *     api.use(authMiddleware);
 *     api.get("/users", listUsers);
 *     api.group("/admin", admin -> admin.get("/stats", stats));
 * });
 * }</pre>
 */
public final class RouteGroup {

    private final Ligero app;
    private final String prefix;

    RouteGroup(Ligero app, String prefix) {
        this.app = app;
        this.prefix = PathNormalizer.normalize(prefix);
    }

    public RouteGroup get(String path, Handler handler) {
        return route("GET", path, handler);
    }

    public RouteGroup post(String path, Handler handler) {
        return route("POST", path, handler);
    }

    public RouteGroup put(String path, Handler handler) {
        return route("PUT", path, handler);
    }

    public RouteGroup patch(String path, Handler handler) {
        return route("PATCH", path, handler);
    }

    public RouteGroup delete(String path, Handler handler) {
        return route("DELETE", path, handler);
    }

    public RouteGroup head(String path, Handler handler) {
        return route("HEAD", path, handler);
    }

    public RouteGroup options(String path, Handler handler) {
        return route("OPTIONS", path, handler);
    }

    public RouteGroup route(String method, String path, Handler handler) {
        app.route(method, join(path), handler);
        return this;
    }

    /** Middleware scoped to this group's prefix. */
    public RouteGroup use(Middleware middleware) {
        app.use(prefix, middleware);
        return this;
    }

    public RouteGroup group(String subPrefix, Consumer<RouteGroup> routes) {
        routes.accept(new RouteGroup(app, join(subPrefix)));
        return this;
    }

    private String join(String path) {
        return PathNormalizer.normalize(prefix + PathNormalizer.normalize(path));
    }
}
