package com.walpro.autobuilder.core.deliberation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Map;

import com.walpro.autobuilder.agent.meta.MetaDeliberationAgent;
import com.walpro.autobuilder.analysis.exhaustion.ExhaustionReport;
import com.walpro.autobuilder.analysis.exhaustion.GoalExhaustionDetector;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.graph.deliberation.DeliberationActionRegistrar;
import com.walpro.autobuilder.protocol.meta.MetaDeliberationPlan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeliberationServiceTest {

    private GoalExhaustionDetector goalExhaustionDetector;
    private DeliberationProposer proposer;
    private DeliberationValidator validator;
    private DeliberationActionRegistrar registrar;
    private MetaDeliberationAgent metaDeliberationAgent;

    private DeliberationService service;

    @BeforeEach
    void setUp() {
        goalExhaustionDetector = mock(GoalExhaustionDetector.class);
        proposer = mock(DeliberationProposer.class);
        validator = mock(DeliberationValidator.class);
        registrar = mock(DeliberationActionRegistrar.class);
        metaDeliberationAgent = mock(MetaDeliberationAgent.class);

        service = new DeliberationService(
                goalExhaustionDetector,
                proposer,
                validator,
                registrar,
                metaDeliberationAgent
        );
    }

    @Test
    void deliberate_whenContextNull_returnsNoopAndDoesNotRegister() {
        DeliberationDecision d = service.deliberate(null);

        assertThat(d).isNotNull();
        assertThat(d.context()).isNull();
        assertThat(d.result()).isNotNull();
        assertThat(d.result().mode()).isEqualTo(DeliberationMode.UNDIRECTED);

        verifyNoInteractions(goalExhaustionDetector, proposer, validator, registrar, metaDeliberationAgent);
    }

    @Test
    void deliberate_whenNotExhausted_keepsContextAndRegistersValidatedResult() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c1")
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .lastAttemptId("attempt-1")
                .build();

        when(goalExhaustionDetector.detect(any())).thenReturn(ExhaustionReport.none("no"));
        DeliberationResult proposed = new DeliberationResult(
                DeliberationMode.UNDIRECTED, 0.1, "r",
                java.util.List.of(), java.util.List.of(),
                Map.of("k", "v"),
                java.util.List.of()
        );
        when(proposer.propose(any())).thenReturn(proposed);

        when(metaDeliberationAgent.decide(any(), any())).thenReturn(null); // no meta enrichment

        DeliberationResult validated = new DeliberationResult(
                proposed.mode(), proposed.confidence(), "validated",
                proposed.considerations(), proposed.evidence(),
                proposed.meta(),
                proposed.proposedSignals()
        );
        when(validator.validate(any())).thenReturn(validated);

        DeliberationDecision d = service.deliberate(ctx);

        assertThat(d.context()).isSameAs(ctx); // no exhaustion signal injected => should be exact same instance
        assertThat(d.result()).isEqualTo(validated);

        verify(proposer).propose(ctx);
        verify(validator).validate(any(DeliberationResult.class));
        verify(registrar).registerDeliberation(eq("deliberation"), eq(ctx), eq(validated));
    }

    @Test
    void deliberate_metaLaneOff_skipsTheLlmMetaAgentEntirely_ruleLayerStillDrives() {
        // T4-AI-2+5: the meta-DECISION lane switch. Off → the LLM meta-agent is never consulted
        // (no plan, no per-agent request text, no imperative prefix); the rule layer's mode +
        // signals still ride the result, so the signal-driven fitness pass is the sole
        // orchestrator downstream. Also saves one LLM call per cycle.
        service.setMetaLaneEnabled(false);

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c1")
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .lastAttemptId("attempt-1")
                .build();

        when(goalExhaustionDetector.detect(any())).thenReturn(ExhaustionReport.none("no"));
        DeliberationResult proposed = new DeliberationResult(
                DeliberationMode.UNDIRECTED, 0.4, "rule-proposed",
                java.util.List.of(), java.util.List.of(),
                Map.of("k", "v"),
                java.util.List.of(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.1, "rule")));
        when(proposer.propose(any())).thenReturn(proposed);
        when(validator.validate(any())).thenAnswer(inv -> inv.getArgument(0));

        DeliberationDecision d = service.deliberate(ctx);

        verifyNoInteractions(metaDeliberationAgent);
        assertThat(d.result().metaSteps()).as("no plan produced when the lane is off").isEmpty();
        assertThat(d.result().meta())
                .as("no metaControl.* entries written when the lane is off")
                .doesNotContainKey("metaControl.steps")
                .doesNotContainKey("metaControl.mode")
                .doesNotContainKey("metaControl.stop");
        assertThat(d.result().proposedSignals())
                .as("the rule layer's steering survives — the fitness pass reads it")
                .isEqualTo(proposed.proposedSignals());
        assertThat(d.result().mode()).isEqualTo(DeliberationMode.UNDIRECTED);
    }

    @Test
    void deliberate_metaLaneOn_isTheDefault_andConsultsTheMetaAgent() {
        // Guards the default: without touching the flag, the meta-agent IS consulted.
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c1").currentGoalId("goal-1").currentSubGoalId("sub-1").lastAttemptId("a1")
                .build();
        when(goalExhaustionDetector.detect(any())).thenReturn(ExhaustionReport.none("no"));
        when(proposer.propose(any())).thenReturn(new DeliberationResult(
                DeliberationMode.UNDIRECTED, 0.4, "r",
                java.util.List.of(), java.util.List.of(), Map.of(), java.util.List.of()));
        when(metaDeliberationAgent.decide(any(), any())).thenReturn(null);
        when(validator.validate(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deliberate(ctx);

        verify(metaDeliberationAgent).decide(any(), any());
    }

    @Test
    void deliberate_whenExhausted_injectsGoalExhaustedSignal_andAddsEvidenceMeta() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c1")
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .lastAttemptId("attempt-1")
                .build();

        when(goalExhaustionDetector.detect(any()))
                .thenReturn(ExhaustionReport.of(0.90, "stuck", Map.of("x", 1)));

        DeliberationResult proposed = new DeliberationResult(
                DeliberationMode.REPLAN, 0.7, "proposed",
                java.util.List.of(), java.util.List.of(),
                Map.of(),
                java.util.List.of()
        );
        when(proposer.propose(any())).thenReturn(proposed);

        when(metaDeliberationAgent.decide(any(), any())).thenReturn(null);

        ArgumentCaptor<DeliberationResult> validateCaptor = ArgumentCaptor.forClass(DeliberationResult.class);

        when(validator.validate(any())).thenAnswer(inv -> inv.getArgument(0));

        DeliberationDecision d = service.deliberate(ctx);

        CycleContextData updated = d.context();
        assertThat(updated).isNotNull();
        assertThat(updated).isNotSameAs(ctx); // signal injection creates a new instance
        assertThat(updated.hasSignal(BasicSignals.SUBGOAL_EXHAUSTED)).isTrue();

        Signal s = updated.getSignal(BasicSignals.SUBGOAL_EXHAUSTED);
        assertThat(s).isNotNull();
        assertThat(s.strength()).isGreaterThanOrEqualTo(0.55); // clamp lower bound
        assertThat(s.strength()).isLessThanOrEqualTo(1.0);

        verify(validator).validate(validateCaptor.capture());
        DeliberationResult passedToValidate = validateCaptor.getValue();

        assertThat(passedToValidate.meta()).containsEntry("goalExhaustedSignal", true);
        assertThat(passedToValidate.meta()).containsEntry("goalExhaustion", "detected");

        verify(registrar).registerDeliberation(eq("deliberation"), eq(updated), eq(d.result()));
    }

    @Test
    void deliberate_whenExhaustedButAlreadyStrong_doesNotReinforceOrReplaceContext() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c1")
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .lastAttemptId("attempt-1")
                .signals(Map.of(
                        BasicSignals.SUBGOAL_EXHAUSTED,
                        Signal.of(BasicSignals.SUBGOAL_EXHAUSTED, 0.80, 0.05, "preexisting")
                ))
                .build();

        when(goalExhaustionDetector.detect(any()))
                .thenReturn(ExhaustionReport.of(0.95, "stuck", Map.of()));

        DeliberationResult proposed = new DeliberationResult(
                DeliberationMode.REPLAN, 0.7, "proposed",
                java.util.List.of(), java.util.List.of(),
                Map.of(),
                java.util.List.of()
        );
        when(proposer.propose(any())).thenReturn(proposed);

        when(metaDeliberationAgent.decide(any(), any())).thenReturn(null);
        when(validator.validate(any())).thenAnswer(inv -> inv.getArgument(0));

        DeliberationDecision d = service.deliberate(ctx);

        // contract: already-strong exhaustion should not "pump" -> same ctx instance
        assertThat(d.context()).isSameAs(ctx);
        verify(proposer).propose(ctx);
        verify(registrar).registerDeliberation(eq("deliberation"), eq(ctx), any(DeliberationResult.class));
    }

    @Test
    void deliberate_whenMetaPlanPresent_enrichesMetaFields() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c1")
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .lastAttemptId("attempt-1")
                .build();

        when(goalExhaustionDetector.detect(any())).thenReturn(ExhaustionReport.none("no"));

        DeliberationResult proposed = new DeliberationResult(
                DeliberationMode.UNDIRECTED, 0.2, "proposed",
                java.util.List.of(), java.util.List.of(),
                Map.of(),
                java.util.List.of()
        );
        when(proposer.propose(any())).thenReturn(proposed);

        MetaDeliberationPlan plan = new MetaDeliberationPlan(
                true,
                DeliberationMode.PAUSE,
                "because",
                java.util.List.of(),
                "raw"
        );
        when(metaDeliberationAgent.decide(any(), any())).thenReturn(plan);

        when(validator.validate(any())).thenAnswer(inv -> inv.getArgument(0));

        DeliberationDecision d = service.deliberate(ctx);

        assertThat(d.result().meta()).containsEntry("metaControl.stop", true);
        assertThat(d.result().meta()).containsEntry("metaControl.mode", "PAUSE");
        assertThat(d.result().meta()).containsEntry("metaControl.rationale", "because");
        assertThat(d.result().meta()).containsKey("metaControl.steps"); // should exist, even if empty
    }

    @Test
    void deliberate_whenProposerReturnsNull_validatorAndRegistrarStillCalledWithNullSafeResult() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c1")
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .lastAttemptId("attempt-1")
                .build();

        // no exhaustion: keeps context same instance
        when(goalExhaustionDetector.detect(any())).thenReturn(ExhaustionReport.none("no"));

        // proposer returns null => service passes null through to validator
        when(proposer.propose(any())).thenReturn(null);

        // meta deliberation should not be called with a null proposed result,
        // but even if it is, we keep it safe by returning null.
        when(metaDeliberationAgent.decide(any(), any())).thenReturn(null);

        // enforce "null-safe result" at the validator boundary
        DeliberationResult fallback = DeliberationResult.noop("validator fallback");
        when(validator.validate(isNull())).thenReturn(fallback);

        DeliberationDecision d = service.deliberate(ctx);

        // context should remain the same instance (no exhaustion injection)
        assertThat(d.context()).isSameAs(ctx);

        // result should be the fallback, not null
        assertThat(d.result()).isSameAs(fallback);
        assertThat(d.result().mode()).isEqualTo(DeliberationMode.UNDIRECTED);

        // proposer called with ctx (same instance)
        verify(proposer).propose(ctx);

        // validator must be called even though proposer returned null
        verify(validator).validate(isNull());

        // registrar must still register the deliberation
        verify(registrar).registerDeliberation(eq("deliberation"), eq(ctx), eq(fallback));

        // meta deliberation should NOT run when proposed is null (because enrichWithMetaDeliberation short-circuits)
        verify(metaDeliberationAgent, never()).decide(any(), any());
    }
}