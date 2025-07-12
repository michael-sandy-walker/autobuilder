package com.walpro.autobuilder.analyzer;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;

public interface SemanticDiffAnalyzer {

    /**
     * Determines whether the given file change is considered structurally significant.
     * This can include annotation changes, endpoint routing, dependency additions, etc.
     */
    boolean isStructurallySignificant(FileMutation oldMutation, FileMutation newMutation);
}