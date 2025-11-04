package com.walpro.autobuilder.graph.core;

import java.util.*;

public class Graph {

    private final Map<String, GraphNode> nodes = new HashMap<>();
    private final List<GraphEdge> edges = new ArrayList<>();

    public void addNode(GraphNode node) {
        nodes.put(node.id(), node);
    }

    public void addEdge(GraphEdge edge) {
        edges.add(edge);
    }

    public Optional<GraphNode> getNode(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    public List<GraphEdge> getEdges() {
        return List.copyOf(edges);
    }

    public Collection<GraphNode> getAllNodes() {
        return nodes.values();
    }

    public List<GraphEdge> getEdgesFrom(String nodeId) {
        return edges.stream().filter(e -> e.from().equals(nodeId)).toList();
    }

    public List<GraphEdge> getEdgesTo(String nodeId) {
        return edges.stream().filter(e -> e.to().equals(nodeId)).toList();
    }
}
