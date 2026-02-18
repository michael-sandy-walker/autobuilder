package com.walpro.autobuilder.graph.query;

public record GraphQueryRequest(
        GraphQueryType type,
        int limit
) {
    public boolean requested() {
        return type != null;
    }

    public static GraphQueryRequest none() {
        return new GraphQueryRequest(null, 0);
    }

    public static GraphQueryRequest of(GraphQueryType type, int limit) {
        return new GraphQueryRequest(type, limit);
    }

    public enum GraphQueryType {
        ORCH_SNAPSHOT,
        DIAGNOSTIC_SCAN
    }
}