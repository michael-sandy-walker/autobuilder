package com.walpro.autobuilder.graph.query;

public final class GraphQueryRequestParser {

    public GraphQueryRequest parse(String raw) {
        if (raw == null || raw.isBlank()) return GraphQueryRequest.none();

        // Expected:
        // #GraphQuery
        // Type: ORCH_SNAPSHOT
        // Limit: 20
        boolean in = false;
        String type = null;
        String limit = null;

        for (String line : raw.split("\\R")) {
            String t = line.trim();
            if (t.equalsIgnoreCase("#GraphQuery")) {
                in = true;
                continue;
            }
            if (!in) continue;

            if (t.startsWith("Type:")) type = t.substring("Type:".length()).trim();
            else if (t.startsWith("Limit:")) limit = t.substring("Limit:".length()).trim();

            if (t.isBlank()) break;
        }

        if (type == null || type.isBlank()) return GraphQueryRequest.none();

        GraphQueryRequest.GraphQueryType qt;
        try {
            qt = GraphQueryRequest.GraphQueryType.valueOf(type.trim());
        } catch (Exception e) {
            return GraphQueryRequest.none();
        }

        int lim = 20;
        try {
            if (limit != null && !limit.isBlank()) lim = Integer.parseInt(limit.trim());
        } catch (Exception ignored) {}

        return GraphQueryRequest.of(qt, lim);
    }
}