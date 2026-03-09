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
