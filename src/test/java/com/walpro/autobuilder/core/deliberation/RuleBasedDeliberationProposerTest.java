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
