package com.walpro.autobuilder.core.deliberation;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedDeliberationProposerTest {

    private RuleBasedDeliberationProposer proposer;

    @BeforeEach
    void setUp() {
        proposer = new RuleBasedDeliberationProposer();
    }

    @Test
    void propose_whenPendingEvaluation_returnsEvaluateModeWithRoleEvaluationSignal() {
        // lastEvolutionFingerprint != lastEvaluatedEvolutionFingerprint → notYetEvaluated=true
        // EVOLUTION_OCCURRED present → hasCandidate=true
        CycleContextData ctx = CycleContextData.builder()
                .parentCycleName("prev-cycle")
                .lastEvolutionFingerprint("fp-1")
                .lastEvaluatedEvolutionFingerprint("fp-0")
                .build();
        ctx = ctx.withSignal(Signal.of(BasicSignals.EVOLUTION_OCCURRED, 1.0, 0.10, "test"));

        DeliberationResult result = proposer.propose(ctx);

        assertThat(result.mode()).isEqualTo(DeliberationMode.EVALUATE);
        assertThat(result.proposedSignals()).isNotEmpty();

        Signal evalSignal = result.proposedSignals().stream()
                .filter(s -> BasicSignals.ROLE_EVALUATION.equals(s.type()))
                .findFirst()
                .orElse(null);

        assertThat(evalSignal).as("ROLE_EVALUATION floor signal must be proposed").isNotNull();
        assertThat(evalSignal.strength()).isGreaterThanOrEqualTo(0.5);
    }

    @Test
    void propose_whenPendingEvaluationButNoDiffAndReplanStrong_fallsBackToReplan() {
        // hasCandidate=true (EVOLUTION_OCCURRED), notYetEvaluated=true, projectDiff=null, REPLAN_REQUESTED strong
        CycleContextData ctx = CycleContextData.builder()
                .parentCycleName("prev-cycle")
                .lastEvolutionFingerprint("fp-1")
                .lastEvaluatedEvolutionFingerprint("fp-0")
                .build();
        ctx = ctx.withSignal(Signal.of(BasicSignals.EVOLUTION_OCCURRED, 0.40, 0.10, "test"));
        ctx = ctx.withSignal(Signal.of(BasicSignals.REPLAN_REQUESTED, 0.75, 0.10, "test"));

        DeliberationResult result = proposer.propose(ctx);

        assertThat(result.mode()).isEqualTo(DeliberationMode.REPLAN);
        boolean hasPlanner = result.proposedSignals().stream()
                .anyMatch(s -> BasicSignals.ROLE_PLANNER.equals(s.type()));
        assertThat(hasPlanner).as("ROLE_PLANNER must be proposed in replan fallback").isTrue();
    }

    @Test
    void propose_whenPendingEvaluationButNoDiffAndReplanWeak_stillReturnsEvaluate() {
        // hasCandidate=true, notYetEvaluated=true, projectDiff=null, but REPLAN_REQUESTED below threshold
        CycleContextData ctx = CycleContextData.builder()
                .parentCycleName("prev-cycle")
                .lastEvolutionFingerprint("fp-1")
                .lastEvaluatedEvolutionFingerprint("fp-0")
                .build();
        ctx = ctx.withSignal(Signal.of(BasicSignals.EVOLUTION_OCCURRED, 0.80, 0.10, "test"));
        ctx = ctx.withSignal(Signal.of(BasicSignals.REPLAN_REQUESTED, 0.20, 0.10, "test"));

        DeliberationResult result = proposer.propose(ctx);

        assertThat(result.mode()).isEqualTo(DeliberationMode.EVALUATE);
    }

    // ── T2-AN: REPETITION_DETECTED consistent-pattern escalation ─────────────

    @Test
    void propose_whenRepetitionSustained_triggersReplan() {
        // strength=2.0 → reinforced across ≥2 consecutive cycles → REPLAN
        CycleContextData ctx = CycleContextData.builder()
                .parentCycleName("prev-cycle")
                .build();
        ctx = ctx.withSignal(Signal.of(BasicSignals.REPETITION_DETECTED, 2.0, 0.05, "test"));

        DeliberationResult result = proposer.propose(ctx);

        assertThat(result.mode()).isEqualTo(DeliberationMode.REPLAN);
        assertThat(result.proposedRequests()).anyMatch(r -> BasicSignals.REPLAN_REQUESTED.equals(r.kind()));
        assertThat(result.proposedSignals()).anyMatch(s -> BasicSignals.ROLE_PLANNER.equals(s.type()));
        assertThat(result.proposedRequests()).anyMatch(r -> BasicSignals.DIVERGENCE_REQUEST.equals(r.kind()));
    }

    @Test
    void propose_whenRepetitionSingleCycle_doesNotTriggerReplan() {
        // strength=0.95 (single detection, partially decayed) → not a consistent pattern
        CycleContextData ctx = CycleContextData.builder()
                .parentCycleName("prev-cycle")
                .lastEvolutionFingerprint("fp-1")
                .lastEvaluatedEvolutionFingerprint("fp-1")
                .build();
        ctx = ctx.withSignal(Signal.of(BasicSignals.REPETITION_DETECTED, 0.95, 0.05, "test"));

        DeliberationResult result = proposer.propose(ctx);

        assertThat(result.mode()).isNotEqualTo(DeliberationMode.REPLAN);
    }

    @Test
    void propose_whenRepetitionHighButJustBelowThreshold_doesNotTriggerReplan() {
        // strength=1.9 — just below 2.0 threshold, one more re-detection needed
        CycleContextData ctx = CycleContextData.builder()
                .parentCycleName("prev-cycle")
                .lastEvolutionFingerprint("fp-1")
                .lastEvaluatedEvolutionFingerprint("fp-1")
                .build();
        ctx = ctx.withSignal(Signal.of(BasicSignals.REPETITION_DETECTED, 1.9, 0.05, "test"));

        DeliberationResult result = proposer.propose(ctx);

        assertThat(result.mode()).isNotEqualTo(DeliberationMode.REPLAN);
    }

    // ── T2-AP: score plateau soft pressure ───────────────────────────────────

    @Test
    void propose_whenScorePlateau_boostsMergeAndInjectsDivergenceRequest() {
        // Evolution is gated by accepted-not-merged; ROLE_MERGE boost unblocks it,
        // DIVERGENCE_REQUEST steers the next evolution attempt toward a different approach.
        CycleContextData ctx = CycleContextData.builder()
                .parentCycleName("prev-cycle")
                .lastEvolutionFingerprint("fp-1")
                .lastEvaluatedEvolutionFingerprint("fp-1")
                .build();
        ctx = ctx.withSignal(Signal.of(BasicSignals.SCORE_PLATEAU, 1.0, 0.05, "test"));

        DeliberationResult result = proposer.propose(ctx);

        assertThat(result.mode()).isEqualTo(DeliberationMode.UNDIRECTED);
        assertThat(result.proposedSignals()).anyMatch(s -> BasicSignals.ROLE_MERGE.equals(s.type()));
        assertThat(result.proposedRequests()).anyMatch(r -> BasicSignals.DIVERGENCE_REQUEST.equals(r.kind()));
    }

    @Test
    void propose_whenScorePlateauSustained_escalatesToReplan() {
        // strength >= 3.0 means the merge boost hasn't cleared the deadlock → REPLAN
        CycleContextData ctx = CycleContextData.builder()
                .parentCycleName("prev-cycle")
                .lastEvolutionFingerprint("fp-1")
                .lastEvaluatedEvolutionFingerprint("fp-1")
                .build();
        ctx = ctx.withSignal(Signal.of(BasicSignals.SCORE_PLATEAU, 3.0, 0.05, "test"));

        DeliberationResult result = proposer.propose(ctx);

        assertThat(result.mode()).isEqualTo(DeliberationMode.REPLAN);
        assertThat(result.proposedRequests()).anyMatch(r -> BasicSignals.REPLAN_REQUESTED.equals(r.kind()));
        assertThat(result.proposedSignals()).anyMatch(s -> BasicSignals.ROLE_PLANNER.equals(s.type()));
    }

    @Test
    void propose_whenScorePlateauButRepetitionSustained_repetitionWins() {
        // REPETITION_DETECTED >= 2.0 is checked before SCORE_PLATEAU → REPLAN wins
        CycleContextData ctx = CycleContextData.builder()
                .parentCycleName("prev-cycle")
                .build();
        ctx = ctx.withSignal(Signal.of(BasicSignals.REPETITION_DETECTED, 2.0, 0.05, "test"));
        ctx = ctx.withSignal(Signal.of(BasicSignals.SCORE_PLATEAU, 3.0, 0.05, "test"));

        DeliberationResult result = proposer.propose(ctx);

        assertThat(result.mode()).isEqualTo(DeliberationMode.REPLAN);
    }

    // ── T2-BF: stagnation circuit breaker + REPLAN = deadlock guard ──────────

    @Test
    void propose_whenCircuitBreakerActive_returnsNoopWithDivergenceInsteadOfReplan() {
        // stagnationCircuitBreakerActive=true → any REPLAN path must be bypassed;
        // UNDIRECTED + DIVERGENCE_REQUEST keeps all roles eligible while breaker enforces prune-only.
        CycleContextData ctx = CycleContextData.builder()
                .parentCycleName("prev-cycle")
                .stagnationCircuitBreakerActive(true)
                .lastEvolutionFingerprint("fp-1")
                .lastEvaluatedEvolutionFingerprint("fp-1")
                .build();

        DeliberationResult result = proposer.propose(ctx);

        assertThat(result.mode()).isEqualTo(DeliberationMode.UNDIRECTED);
        assertThat(result.proposedRequests()).anyMatch(r -> BasicSignals.DIVERGENCE_REQUEST.equals(r.kind()));
        assertThat(result.proposedRequests()).noneMatch(r -> BasicSignals.REPLAN_REQUESTED.equals(r.kind()));
    }

    @Test
    void propose_whenCircuitBreakerActiveAndRepetitionSustained_guardFiresFirst() {
        // REPETITION_DETECTED >= 2.0 would normally return REPLAN, but the circuit breaker
        // guard fires first and returns UNDIRECTED+DIVERGE instead.
        CycleContextData ctx = CycleContextData.builder()
                .parentCycleName("prev-cycle")
                .stagnationCircuitBreakerActive(true)
                .build();
        ctx = ctx.withSignal(Signal.of(BasicSignals.REPETITION_DETECTED, 2.0, 0.05, "test"));

        DeliberationResult result = proposer.propose(ctx);

        assertThat(result.mode()).isEqualTo(DeliberationMode.UNDIRECTED);
        assertThat(result.proposedRequests()).anyMatch(r -> BasicSignals.DIVERGENCE_REQUEST.equals(r.kind()));
    }

    @Test
    void propose_whenCircuitBreakerInactive_repetitionStillTriggersReplan() {
        // Confirm the guard only fires when the breaker is active.
        CycleContextData ctx = CycleContextData.builder()
                .parentCycleName("prev-cycle")
                .stagnationCircuitBreakerActive(false)
                .build();
        ctx = ctx.withSignal(Signal.of(BasicSignals.REPETITION_DETECTED, 2.0, 0.05, "test"));

        DeliberationResult result = proposer.propose(ctx);

        assertThat(result.mode()).isEqualTo(DeliberationMode.REPLAN);
    }

    @Test
    void propose_whenAlreadyEvaluated_doesNotReturnsEvaluateMode() {
        // fingerprints match → notYetEvaluated=false → EVALUATE branch skipped
        CycleContextData ctx = CycleContextData.builder()
                .parentCycleName("prev-cycle")
                .lastEvolutionFingerprint("fp-1")
                .lastEvaluatedEvolutionFingerprint("fp-1")
                .build();
        ctx = ctx.withSignal(Signal.of(BasicSignals.EVOLUTION_OCCURRED, 1.0, 0.10, "test"));

        DeliberationResult result = proposer.propose(ctx);

        assertThat(result.mode()).isNotEqualTo(DeliberationMode.EVALUATE);
    }
}
