package com.walpro.autobuilder.observability.behaviour;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * T5-H: sealed type hierarchy for the behaviour trace. Every event carries the common
 * envelope fields ({@code timestamp}, {@code cycleId}, {@code event}); the remaining
 * record components form the event-specific payload that {@link BehaviourLogger}
 * serialises under the {@code payload} JSON key.
 */
public sealed interface BehaviourEvent {

    Instant timestamp();
    String cycleId();
    String event();

    // ---------------------------------------------------------------------------------
    // Core loop
    // ---------------------------------------------------------------------------------

    record CycleStart(
            Instant timestamp,
            String cycleId,
            String currentGoalId,
            String currentSubGoalId,
            String deliberationMode
    ) implements BehaviourEvent {
        public String event() { return "cycle_start"; }
    }

    record AgentRanking(String role, double score, Map<String, Double> breakdown) {}

    record AgentFitnessRanked(
            Instant timestamp,
            String cycleId,
            List<AgentRanking> rankings
    ) implements BehaviourEvent {
        public String event() { return "agent_fitness_ranked"; }
    }

    record AgentSelected(
            Instant timestamp,
            String cycleId,
            String role,
            double fitness
    ) implements BehaviourEvent {
        public String event() { return "agent_selected"; }
    }

    record AgentSkipped(
            Instant timestamp,
            String cycleId,
            String role,
            String reason
    ) implements BehaviourEvent {
        public String event() { return "agent_skipped"; }
    }

    record CycleEnd(
            Instant timestamp,
            String cycleId,
            String resultState,
            boolean merged,
            String endReason
    ) implements BehaviourEvent {
        public String event() { return "cycle_end"; }
    }

    // ---------------------------------------------------------------------------------
    // Mutation flow
    // ---------------------------------------------------------------------------------

    record MutationProposed(
            Instant timestamp,
            String cycleId,
            String mutationId,
            List<String> files
    ) implements BehaviourEvent {
        public String event() { return "mutation_proposed"; }
    }

    record MutationScored(
            Instant timestamp,
            String cycleId,
            String mutationId,
            Double llmScore,
            String llmState,
            List<String> criteriaAssessments
    ) implements BehaviourEvent {
        public String event() { return "mutation_scored"; }
    }

    record HeuristicResult(
            Instant timestamp,
            String cycleId,
            String mutationId,
            String heuristic,
            Double score,
            String state,
            double weight
    ) implements BehaviourEvent {
        public String event() { return "heuristic_result"; }
    }

    record MutationNegotiated(
            Instant timestamp,
            String cycleId,
            String mutationId,
            Double finalScore,
            String finalState,
            List<String> overrides
    ) implements BehaviourEvent {
        public String event() { return "mutation_negotiated"; }
    }

    record MutationMerged(
            Instant timestamp,
            String cycleId,
            String mutationId,
            List<String> files
    ) implements BehaviourEvent {
        public String event() { return "mutation_merged"; }
    }

    record MutationRejected(
            Instant timestamp,
            String cycleId,
            String mutationId,
            String reason
    ) implements BehaviourEvent {
        public String event() { return "mutation_rejected"; }
    }

    // T1-DW: emitted when a post-merge COMPILE_ERROR triggers filesystem rollback.
    // T1-DX: payload extended with stderrSummary + errorKind so post-hoc analysis doesn't
    // need to cross-reference the build log to know what triggered the rollback.
    record MergeRolledBack(
            Instant timestamp,
            String cycleId,
            List<String> restoredPaths,
            String stderrSummary,
            String errorKind
    ) implements BehaviourEvent {
        public String event() { return "merge_rolled_back"; }
    }

    // ---------------------------------------------------------------------------------
    // Invariant guards — structural regression checks
    // ---------------------------------------------------------------------------------

    record GuardViolated(
            Instant timestamp,
            String cycleId,
            String guard,
            String detail
    ) implements BehaviourEvent {
        public String event() { return "guard_violated"; }
    }

    // ---------------------------------------------------------------------------------
    // Steering
    // ---------------------------------------------------------------------------------

    // T2-DF: emitted when a meta plan step is dropped because its targetRole is not eligible
    // this cycle. High filter rates mean the eligibility-block instruction needs strengthening.
    record MetaStepFiltered(
            Instant timestamp,
            String cycleId,
            int step,
            String originalRole,
            String reason
    ) implements BehaviourEvent {
        public String event() { return "meta_step_filtered"; }
    }

    record DeliberationOutcome(
            Instant timestamp,
            String cycleId,
            String mode,
            List<String> signalAdjustments,
            List<String> agentSteps
    ) implements BehaviourEvent {
        public String event() { return "deliberation_outcome"; }
    }

    // T1-ED: emitted when EvaluationAgent or CycleExecutor fails to persist mutationState to
    // meta.json. High rates indicate a structural ordering problem (evaluator runs before
    // mutation files are on disk) or a filesystem issue.
    record MutationMetaPersistFailed(
            Instant timestamp,
            String cycleId,
            String metaPath,
            String reason
    ) implements BehaviourEvent {
        public String event() { return "mutation_meta_persist_failed"; }
    }
}
