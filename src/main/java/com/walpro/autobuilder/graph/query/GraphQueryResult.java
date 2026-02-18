package com.walpro.autobuilder.graph.query;

public record GraphQueryResult(
        GraphQueryRequest request,
        String renderedBlock,
        String error
) {
    public static GraphQueryResult empty(GraphQueryRequest request, String error) {
        return new GraphQueryResult(request, "", error);
    }

    public boolean success() {
        return error == null || error.isBlank();
    }
}