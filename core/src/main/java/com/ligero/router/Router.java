package com.ligero.router;

import com.ligero.http.Handler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Route registry and matcher. Single responsibility: mapping
 * {@code (method, path)} pairs to handlers — dispatching, error handling and
 * logging live in the pipeline.
 */
public final class Router {

    private final Map<String, RouteTrie<Handler>> perMethod = new HashMap<>();

    /** Result of a successful match. */
    public record RouteMatch(Handler handler, Map<String, String> params, String routePath) {
    }

    /**
     * Registers a handler.
     *
     * @param path route pattern; supports {@code {param}} segments and a
     *             trailing {@code *wildcard}
     * @throws IllegalArgumentException on duplicate or malformed routes
     */
    public void add(String method, String path, Handler handler) {
        String normalized = PathNormalizer.normalize(path);
        perMethod.computeIfAbsent(method.toUpperCase(), m -> new RouteTrie<>())
                 .insert(normalized, handler);
    }

    /** Matches a normalized path for the given method, or returns {@code null}. */
    public RouteMatch match(String method, String path) {
        RouteTrie<Handler> trie = perMethod.get(method.toUpperCase());
        if (trie == null) {
            return null;
        }
        RouteTrie.Match<Handler> match = trie.find(path);
        return match == null ? null : new RouteMatch(match.value(), match.params(), match.routePath());
    }

    /** Methods that would match the path — used to produce 405 responses. */
    public Set<String> allowedMethods(String path) {
        Set<String> allowed = new TreeSet<>();
        perMethod.forEach((method, trie) -> {
            if (trie.find(path) != null) {
                allowed.add(method);
            }
        });
        return allowed;
    }

    /** Registered routes per method, for startup logging and diagnostics. */
    public Map<String, List<String>> routes() {
        Map<String, List<String>> result = new TreeMap<>();
        perMethod.forEach((method, trie) -> result.put(method, trie.registeredPaths()));
        return result;
    }
}
