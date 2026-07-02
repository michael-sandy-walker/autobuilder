package com.walpro.autobuilder.core.interpretation;

import com.walpro.autobuilder.agent.interpretation.InterpretationAgent;
import com.walpro.autobuilder.agent.interpretation.model.InterpretationOutcome;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.graph.interpretation.InterpretationActionRegistrar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterpretationServiceTest {

    @Mock
    private InterpretationAgent interpretationAgent;

    @Mock
    private InterpretationActionRegistrar registrar;

    @InjectMocks
    private InterpretationService service;

    @Test
    void interpret_noopOutcome_returnsContextUnchanged() {
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("goal-1")
                .signals(new java.util.LinkedHashMap<>())
                .build();

        when(interpretationAgent.interpret(ctx))
                .thenReturn(InterpretationOutcome.noop("nothing notable"));

        CycleContextData result = service.interpret(ctx);

        assertThat(result.hasSignal(BasicSignals.REPLAN_REQUESTED)).isFalse();
        assertThat(result.hasSignal(BasicSignals.LOW_CONFIDENCE)).isFalse();
        assertThat(result.hasSignal(BasicSignals.DIVERGENCE_REQUEST)).isFalse();
    }

    @Test
    void interpret_appliedWithSignals_injectsSignalsIntoContext() {
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("goal-1")
                .signals(new java.util.LinkedHashMap<>())
                .build();

        List<Signal> proposed = List.of(
                Signal.of(BasicSignals.REPLAN_REQUESTED, 0.8, 0.05, "interpretation"),
                Signal.of(BasicSignals.LOW_CONFIDENCE, 0.7, 0.10, "interpretation")
        );
        InterpretationOutcome outcome = new InterpretationOutcome("APPLIED", "Stagnation.", "", proposed);
        when(interpretationAgent.interpret(ctx)).thenReturn(outcome);

        CycleContextData result = service.interpret(ctx);

        assertThat(result.hasStrongSignal(BasicSignals.REPLAN_REQUESTED, 0.5)).isTrue();
        assertThat(result.hasStrongSignal(BasicSignals.LOW_CONFIDENCE, 0.5)).isTrue();
    }

    @Test
    void interpret_alwaysCallsRegistrar() {
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("goal-1")
                .signals(new java.util.LinkedHashMap<>())
                .build();

        InterpretationOutcome outcome = InterpretationOutcome.noop("noop");
        when(interpretationAgent.interpret(ctx)).thenReturn(outcome);

        service.interpret(ctx);

        verify(registrar).registerInterpretation(eq("interpretation"), eq(ctx), eq(outcome));
    }

    @Test
    void interpret_divergenceRequestSignal_injectedIntoContext() {
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("goal-2")
                .signals(new java.util.LinkedHashMap<>())
                .build();

        List<Signal> proposed = List.of(
                Signal.of(BasicSignals.DIVERGENCE_REQUEST, 0.7, 0.10, "interpretation")
        );
        InterpretationOutcome outcome = new InterpretationOutcome("APPLIED", "No progress.", "", proposed);
        when(interpretationAgent.interpret(ctx)).thenReturn(outcome);

        CycleContextData result = service.interpret(ctx);

        assertThat(result.hasStrongSignal(BasicSignals.DIVERGENCE_REQUEST, 0.5)).isTrue();
    }

    @Test
    void interpret_T2CZ_rewritesSummary_whenLastRollbackPresent_andLlmClaimsHealthy() {
        // T2-CZ: when the previous cycle rolled back AND the LLM still produces healthy-progress
        // language, InterpretationService must rewrite the summary to surface the rollback.
        com.walpro.autobuilder.core.merge.RolledBackReport rb =
                new com.walpro.autobuilder.core.merge.RolledBackReport(
                        "cycle-pre",
                        List.of("Foo.java"),
                        "cannot find symbol",
                        com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL);

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("goal-cz")
                .signals(new java.util.LinkedHashMap<>())
                .lastRollback(rb)
                .build();

        InterpretationOutcome misleading = new InterpretationOutcome(
                "APPLIED",
                "The system is making steady progress with accepted mutations and a clear evolution direction.",
                "Continue current strategy.", List.of());
        when(interpretationAgent.interpret(ctx)).thenReturn(misleading);

        CycleContextData result = service.interpret(ctx);

        String rewritten = result.interpretationSummary();
        assertThat(rewritten.toLowerCase()).contains("rolled back");
        assertThat(rewritten.toLowerCase()).doesNotContain("the system is making steady progress");
    }

    @Test
    void interpret_T2CZ_noRewrite_whenSummaryAlreadyMentionsRollback() {
        com.walpro.autobuilder.core.merge.RolledBackReport rb =
                new com.walpro.autobuilder.core.merge.RolledBackReport(
                        "cycle-pre", List.of("Foo.java"), "x",
                        com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind.UNKNOWN);

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("goal-cz")
                .signals(new java.util.LinkedHashMap<>())
                .lastRollback(rb)
                .build();

        InterpretationOutcome aware = new InterpretationOutcome(
                "APPLIED",
                "Previous cycle rolled back; planner should target the failing file.",
                "...", List.of());
        when(interpretationAgent.interpret(ctx)).thenReturn(aware);

        CycleContextData result = service.interpret(ctx);

        // Idempotent: should not mutate when the summary already covers the rollback.
        assertThat(result.interpretationSummary()).isEqualTo(aware.summary());
    }

    @Test
    void interpret_T2CZ_noRewrite_whenLastRollbackIsNull() {
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("goal-cz")
                .signals(new java.util.LinkedHashMap<>())
                .build();

        InterpretationOutcome healthy = new InterpretationOutcome(
                "NOOP",
                "The system is making steady progress.",
                "NONE", List.of());
        when(interpretationAgent.interpret(ctx)).thenReturn(healthy);

        CycleContextData result = service.interpret(ctx);

        // No rollback in context → no rewrite — healthy progress language is legitimate.
        assertThat(result.interpretationSummary()).isEqualTo(healthy.summary());
    }

    @Test
    void interpret_nullProposedSignals_doesNotThrow() {
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("goal-3")
                .signals(new java.util.LinkedHashMap<>())
                .build();

        // null signals list (edge case guard)
        InterpretationOutcome outcome = new InterpretationOutcome("NOOP", "ok", "", null);
        when(interpretationAgent.interpret(ctx)).thenReturn(outcome);

        CycleContextData result = service.interpret(ctx);

        assertThat(result).isNotNull();
        verify(registrar).registerInterpretation(any(), any(), any());
    }
}
