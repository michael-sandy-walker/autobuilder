package com.walpro.autobuilder.agent.evaluation.heuristic;

import com.walpro.autobuilder.agent.evaluation.graph.AlignmentVector;

public class TrueAlignmentAnalyzer {

    public boolean isAligned(AlignmentVector v) { // TODO: this is just a template which needs to be expanded (regarding dimensions, values, etc.)
        return v.centralityScore() <= 0.7 &&
               v.editDistance() <= 0.3 &&
               v.alternatePaths() >= 2 &&
               v.compressionGain() >= 0.1 &&
               v.deletedReferencedNodes() == 0 &&
               Math.abs(v.structuralLayerSpanDelta()) <= 2;
    }
}
