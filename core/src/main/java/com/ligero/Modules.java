package com.ligero;

import com.ligero.beans.BeanDecorator;
import com.ligero.beans.Beans;

/**
 * Assembles an application from {@link LigeroModule}s: collects every
 * module's bindings into one {@link Beans} container, starts it (fail-fast
 * validation of the whole graph), exposes it to handlers via
 * {@link Ligero#beans(Beans)} and mounts every module's routes.
 */
public final class Modules {

    private Modules() {
    }

    /** Installs the modules on the app and returns the started container. */
    public static Beans install(Ligero app, LigeroModule... modules) {
        return install(app, null, modules);
    }

    /**
     * Variant with an instrumentation hook (e.g. the devtools recorder),
     * applied to every bean of every module.
     */
    public static Beans install(Ligero app, BeanDecorator decorator, LigeroModule... modules) {
        Beans.Builder builder = Beans.builder();
        for (LigeroModule module : modules) {
            module.beans(builder);
        }
        if (decorator != null) {
            builder.instrument(decorator);
        }
        Beans beans = builder.start();
        app.beans(beans);
        for (LigeroModule module : modules) {
            module.routes(app, beans);
        }
        return beans;
    }
}
