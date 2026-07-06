package com.ligero.beans;

import com.ligero.beans.stereotype.Component;
import com.ligero.beans.stereotype.Controller;
import com.ligero.beans.stereotype.Repository;
import com.ligero.beans.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Ligero's dependency container — deliberately tiny and reflection-free.
 *
 * <p>Wiring is explicit lambdas the compiler verifies; resolution is lazy,
 * memoized (singletons) and cycle-safe; {@link Builder#start()} validates the
 * whole graph eagerly (fail-fast, in microseconds). Stereotype annotations
 * ({@code @Service}, {@code @Repository}, ...) are pure metadata read once to
 * tag the {@link #graph()} that powers the devtools dashboard — they never
 * drive injection.</p>
 *
 * <pre>{@code
 * Beans beans = Beans.builder()
 *     .bind(DataSource.class,        b -> pgDataSource())
 *     .bind(ProductRepository.class, b -> new JdbcProductRepository(b.get(DataSource.class)))
 *     .bind(ProductService.class,    b -> new ProductService(b.get(ProductRepository.class)))
 *     .start();
 * }</pre>
 */
public final class Beans implements AutoCloseable {

    /** Factory receiving the container to declare its dependencies via {@code get}. */
    @FunctionalInterface
    public interface Factory<T> extends Function<Beans, T> {
    }

    private final Map<Class<?>, Factory<?>> factories;
    private final BeanDecorator decorator;
    private final Map<Class<?>, Object> singletons = new LinkedHashMap<>();
    private final Deque<Class<?>> resolving = new ArrayDeque<>();
    private final Set<BeanGraph.Edge> edges = new LinkedHashSet<>();

    private Beans(Map<Class<?>, Factory<?>> factories, BeanDecorator decorator) {
        this.factories = factories;
        this.decorator = decorator;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Resolves (and caches) the bean for {@code type}. */
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type) {
        if (!resolving.isEmpty()) {
            // real dependency observed while another bean resolves — recorded
            // even on cache hits, because the dependency exists regardless
            edges.add(new BeanGraph.Edge(resolving.peek().getName(), type.getName()));
        }
        Object existing = singletons.get(type);
        if (existing != null) {
            return (T) existing;
        }
        Factory<?> factory = factories.get(type);
        if (factory == null) {
            throw new IllegalStateException("No binding for " + type.getName()
                + (resolving.isEmpty() ? "" : " (needed by " + resolving.peek().getName() + ")")
                + ". Add it with Beans.builder().bind(" + type.getSimpleName() + ".class, ...)");
        }
        if (resolving.contains(type)) {
            throw new IllegalStateException("Dependency cycle: " + describeCycle(type));
        }
        resolving.push(type);
        try {
            T bean = (T) factory.apply(this);
            if (decorator != null) {
                bean = decorator.decorate(type, bean);
            }
            singletons.put(type, bean);
            return bean;
        } finally {
            resolving.pop();
        }
    }

    /** All beans assignable to {@code supertype} (e.g. every {@code Controller}). */
    @SuppressWarnings("unchecked")
    public <T> List<T> all(Class<T> supertype) {
        return factories.keySet().stream()
            .filter(supertype::isAssignableFrom)
            .map(type -> (T) get(type))
            .collect(Collectors.toList());
    }

    /** Every bound type, in registration order. */
    public Set<Class<?>> types() {
        return factories.keySet();
    }

    /** Typed dependency graph (nodes tagged by stereotype, edges from real resolution). */
    public BeanGraph graph() {
        List<BeanGraph.Node> nodes = factories.keySet().stream()
            .map(type -> new BeanGraph.Node(type.getName(), stereotypeFor(type)))
            .toList();
        return new BeanGraph(nodes, List.copyOf(edges));
    }

    /**
     * Stereotype annotations usually live on the implementation class, not on
     * the interface the bean is bound as — prefer the instantiated class when
     * it carries one (decorator proxies don't, hence the fallback).
     */
    private String stereotypeFor(Class<?> bindingType) {
        Object instance = singletons.get(bindingType);
        if (instance != null) {
            String stereotype = stereotypeOf(instance.getClass());
            if (!"bean".equals(stereotype)) {
                return stereotype;
            }
        }
        return stereotypeOf(bindingType);
    }
 
    /** Closes instantiated {@link AutoCloseable} beans in reverse creation order. */
    @Override
    public void close() {
        List<Object> created = new ArrayList<>(singletons.values());
        for (int i = created.size() - 1; i >= 0; i--) {
            if (created.get(i) instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // best-effort shutdown
                }
            }
        }
    }

    private static String stereotypeOf(Class<?> type) {
        if (type.isAnnotationPresent(Controller.class)) {
            return "controller";
        }
        if (type.isAnnotationPresent(Service.class)) {
            return "service";
        }
        if (type.isAnnotationPresent(Repository.class)) {
            return "repository";
        }
        if (type.isAnnotationPresent(Component.class)) {
            return "component";
        }
        return "bean";
    }

    private String describeCycle(Class<?> repeated) {
        List<String> chain = new ArrayList<>();
        resolving.descendingIterator().forEachRemaining(c -> chain.add(c.getSimpleName()));
        chain.add(repeated.getSimpleName());
        return String.join(" -> ", chain);
    }

    public static final class Builder {

        private final Map<Class<?>, Factory<?>> factories = new LinkedHashMap<>();
        private BeanDecorator decorator;

        public <T> Builder bind(Class<T> type, Factory<T> factory) {
            if (factories.putIfAbsent(type, factory) != null) {
                throw new IllegalStateException("Duplicate binding: " + type.getName());
            }
            return this;
        }

        /** Binds an already-built instance (configuration values, external clients...). */
        public <T> Builder bindInstance(Class<T> type, T instance) {
            return bind(type, b -> instance);
        }

        /** Instrumentation hook (devtools); at most one. */
        public Builder instrument(BeanDecorator decorator) {
            this.decorator = decorator;
            return this;
        }

        /** Builds the container WITHOUT instantiating anything (fully lazy). */
        public Beans buildLazy() {
            // LinkedHashMap preserva el orden de registro (start(), types(), close())
            return new Beans(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(factories)), decorator);
        }

        /**
         * Builds and eagerly resolves every binding: a missing dependency or a
         * cycle fails HERE, at startup, with a readable message — Spring-style
         * fail-fast at microsecond cost.
         */
        public Beans start() {
            Beans beans = buildLazy();
            beans.factories.keySet().forEach(beans::get);
            return beans;
        }
    }
}
