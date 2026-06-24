package com.walpro.autobuilder.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "autobuilder.goal")
public class GoalPlannerConfigProperties {
    private boolean migrateToGraph = false;
    private boolean syncFromGraph = false;

    /**
     * T2-EG: the completion-edge coverage gate (leading "is this gap already implemented?" question
     * over a flat blob — the framing that false-positived 18/18 on the 2026-06-16 hollow artifact).
     * Demoted to off-by-default: T4-BP-3's inverse, per-criterion corroboration is the single home
     * for "is the work done?", and disabling the gate cuts an LLM call at closure. Flip to {@code true}
     * to re-arm the incumbent if T4-BP-3 ever underperforms in the field.
     */
    private boolean completionCoverageGateEnabled = false;

    /**
     * T2-EI: depth-first per-resource decomposition (experiment). When {@code true}, the planner
     * contract replaces the breadth-first {@code FULL_COVERAGE_REQUIRED} mandate (one subgoal per
     * feature up front — which immediately exceeds the 8-subgoal throttle cap for multi-resource
     * goals) with a depth-first rule: build ONE dependency-ordered resource fully, then decompose the
     * next uncovered resource on empty-backlog. Per [[project-t2ei-rule-interaction-map]], this MUST
     * be paired with {@code completionCoverageGateEnabled=true} — FULL_COVERAGE is the only thing that
     * prevents premature root completion at the empty-backlog edge, so depth-first moves that
     * enforcement to the closure edge (the coverage gate). Off by default; flip both to run the
     * experiment.
     */
    private boolean depthFirstDecomposition = false;
}
