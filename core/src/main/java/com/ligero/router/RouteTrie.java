package com.ligero.router;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Segment trie for route matching in O(path length), replacing the previous
 * linear scan. Supports three segment kinds with strictly decreasing
 * priority: static ({@code /users}), parameter ({@code /{id}}) and trailing
 * wildcard ({@code /*rest}), with backtracking between them.
 */
final class RouteTrie<T> {

    private final Node<T> root = new Node<>();

    private static final class Node<T> {
        final Map<String, Node<T>> staticChildren = new LinkedHashMap<>();
        Node<T> paramChild;
        String paramName;
        Node<T> wildcardChild;
        String wildcardName;
        T value;
        String routePath;
    }

    record Match<T>(T value, Map<String, String> params) {
    }

    /**
     * Inserts a route. Parameter segments use {@code {name}}; a trailing
     * {@code *name} captures the rest of the path.
     *
     * @throws IllegalArgumentException if an equivalent route already exists
     */
    void insert(String path, T value) {
        String[] segments = PathNormalizer.segments(path);
        Node<T> node = root;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.startsWith("{") && segment.endsWith("}") && segment.length() > 2) {
                String name = segment.substring(1, segment.length() - 1);
                if (node.paramChild == null) {
                    node.paramChild = new Node<>();
                    node.paramName = name;
                } else if (!node.paramName.equals(name)) {
                    throw new IllegalArgumentException(
                        "Conflicting parameter names at the same position: {" + node.paramName
                        + "} vs {" + name + "} in route " + path);
                }
                node = node.paramChild;
            } else if (segment.startsWith("*")) {
                if (i != segments.length - 1) {
                    throw new IllegalArgumentException(
                        "Wildcard segment must be the last one in route " + path);
                }
                if (node.wildcardChild == null) {
                    node.wildcardChild = new Node<>();
                    node.wildcardName = segment.length() > 1 ? segment.substring(1) : "*";
                }
                node = node.wildcardChild;
            } else {
                node = node.staticChildren.computeIfAbsent(segment, s -> new Node<>());
            }
        }
        if (node.value != null) {
            throw new IllegalArgumentException("Route already registered: " + node.routePath);
        }
        node.value = value;
        node.routePath = path;
    }

    /** Finds the best match for a normalized path, or {@code null}. */
    Match<T> find(String path) {
        String[] segments = PathNormalizer.segments(path);
        Map<String, String> params = new HashMap<>();
        T value = find(root, segments, 0, params);
        return value == null ? null : new Match<>(value, params);
    }

    private T find(Node<T> node, String[] segments, int index, Map<String, String> params) {
        if (index == segments.length) {
            return node.value;
        }
        String segment = segments[index];

        Node<T> staticChild = node.staticChildren.get(segment);
        if (staticChild != null) {
            T value = find(staticChild, segments, index + 1, params);
            if (value != null) {
                return value;
            }
        }

        if (node.paramChild != null) {
            params.put(node.paramName, segment);
            T value = find(node.paramChild, segments, index + 1, params);
            if (value != null) {
                return value;
            }
            params.remove(node.paramName);
        }

        if (node.wildcardChild != null && node.wildcardChild.value != null) {
            params.put(node.wildcardName, String.join("/",
                java.util.Arrays.copyOfRange(segments, index, segments.length)));
            return node.wildcardChild.value;
        }

        return null;
    }

    /** All registered route paths, in registration order per branch. */
    List<String> registeredPaths() {
        List<String> paths = new ArrayList<>();
        collect(root, paths);
        return paths;
    }

    private void collect(Node<T> node, List<String> paths) {
        if (node.value != null) {
            paths.add(node.routePath);
        }
        node.staticChildren.values().forEach(child -> collect(child, paths));
        if (node.paramChild != null) {
            collect(node.paramChild, paths);
        }
        if (node.wildcardChild != null) {
            collect(node.wildcardChild, paths);
        }
    }
}
