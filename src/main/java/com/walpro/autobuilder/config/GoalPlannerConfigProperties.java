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

    /**
     * T2-EI (D2 structure query): deterministic endpoint-surface check at the completion edge.
     * When {@code true}, closure compares the spec's verb-qualified endpoint mentions (intended)
     * against the output's request-mapping annotations (built, derived live) and refuses to
     * complete the root while spec-named endpoints have no mapping — injecting them as work items
     * instead. The deterministic half of the coverage invariant (P5: "what's next" is a query);
     * the LLM coverage gate above it remains the broader, optional revision layer. No LLM call,
     * no minted paraphrases; abstains whenever the spec names no endpoints or the built set
     * cannot be grounded. Off by default pending field validation.
     */
    private boolean structureQueryEnabled = false;

    /**
     * T4-BT: criteria-traceability refinement of the required/extra origin stamp. When {@code true}
     * (default), the closure and retry-budget consumers classify a subgoal against the root spec's
     * evidence — test-flavored subgoals on a spec that never asks for tests become EXTRA (the
     * T2-EJ field-run-1 fatal mis-bin), subgoals naming a spec-declared endpoint become REQUIRED —
     * falling back to the origin stamp when no evidence exists. Off-switch for field attribution.
     */
    private boolean specTraceabilityEnabled = true;

    /**
     * Spec-grounding of acceptance criteria (run 19, 2026-07-07). When {@code true}, criteria
     * written through the {@code setAcceptanceCriteria} choke point are classified against the
     * root specification (BUILD_FIX goals deterministically, others via one cached LLM pass) and
     * stamped SPEC or AUTHORED. Authored criteria stay visible as guidance but cannot block root
     * closure, burn retry budget, or join the completion-verification set — the loop can no
     * longer kill a run over a requirement it invented from transient state (runs 17/18/19 all
     * died this way). Abstain keeps the SPEC default. Off by default pending the corpus
     * precision eval.
     */
    private boolean criteriaGroundingEnabled = false;

    /**
     * T2-EI (count-based admission queue): maximum number of active subgoal LEAVES that may be
     * surfaced as the workable set at any time. Excess active leaves are HELD — they keep their
     * ACTIVE status (so they still block premature root completion) but are not selectable as the
     * current subgoal, so no agent works them and their files never reach the shared output/ disk.
     * As admitted leaves terminate, held ones are surfaced. This deterministically bounds the
     * working set the model must keep coherent, independent of how many subgoals the planner (or
     * the coverage gate) proposes in one wave — the lever the 3-run experiment pinned (run-B's
     * small coherent set delivered by construction, not by luck). 0 = disabled (default). A value
     * around one resource's worth of layers (~6) reproduces run-B behaviour. See
     * [[project-t2ei-design-note]] (P1/P2). Independent of {@link #depthFirstDecomposition}.
     */
    private int admissionCap = 0;
}
