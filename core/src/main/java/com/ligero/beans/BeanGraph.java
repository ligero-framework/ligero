package com.ligero.beans;

import java.util.List;

/**
 * Dependency graph captured while the container resolves beans: nodes are
 * bound types (tagged with their stereotype), edges are real resolution
 * dependencies observed when factories call {@code get(...)}. This is the
 * data model behind the devtools dashboard.
 */
public record BeanGraph(List<Node> nodes, List<Edge> edges) {

    /** @param stereotype one of component/service/repository/controller, or "bean" */
    public record Node(String type, String stereotype) {
    }

    public record Edge(String from, String to) {
    }
}
