package com.walpro.autobuilder.graph.core;

public record ArtifactEntity(
    String id,
    String type,        // "file", "score", "comment"
    String path,        // Optional file path
    String content,     // Optional, or could be hash
    Double score        // Optional for evaluations
) {}
