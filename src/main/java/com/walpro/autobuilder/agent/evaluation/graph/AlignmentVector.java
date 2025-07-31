package com.walpro.autobuilder.agent.evaluation.graph;

public record AlignmentVector(
    double centralityScore,         // From graph
    double editDistance,            // From diff
    double alternatePaths,          // From graph pathing
    double compressionGain,         // From summarizer/diff
    int deletedReferencedNodes,     // From historical reference/diff
    int structuralLayerSpanDelta    // From structural diff / class nesting depth
) {}
