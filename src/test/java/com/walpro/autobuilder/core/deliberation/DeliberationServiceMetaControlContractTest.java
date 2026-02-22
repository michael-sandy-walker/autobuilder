package com.walpro.autobuilder.core.deliberation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import com.walpro.autobuilder.agent.meta.MetaDeliberationAgent;
import com.walpro.autobuilder.analysis.exhaustion.ExhaustionReport;
import com.walpro.autobuilder.analysis.exhaustion.GoalExhaustionDetector;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.deliberation.DeliberationActionRegistrar;
import com.walpro.autobuilder.protocol.meta.MetaActionStep;
import com.walpro.autobuilder.protocol.meta.MetaDeliberationPlan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeliberationServiceMetaControlContractTest {

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

        when(goalExhaustionDetector.detect(any())).thenReturn(ExhaustionReport.none("no"));
        when(validator.validate(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void metaPlan_stepsAreSortedAndSerializedDeterministically() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c1")
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .lastAttemptId("attempt-1")
                .build();

        when(proposer.propose(any())).thenReturn(new DeliberationResult(
                DeliberationMode.NOOP, 0.1, "proposed",
                List.of(), List.of(),
                Map.of(), List.of()
        ));

        MetaDeliberationPlan plan = new MetaDeliberationPlan(
                false,
                DeliberationMode.EVOLVE,
                "meta rationale",
                List.of(
                        new MetaActionStep(5, MetaActionStep.Need.NEXT_STEP, "evolution", "do X", "", ""),
                        new MetaActionStep(2, MetaActionStep.Need.UNSTICK, "planner", "do Y", "HasPlannerOutcome", ""),
                        new MetaActionStep(3, MetaActionStep.Need.REDO_LAST, "merge", "do Z", "", "MutationAcceptanceState.ACCEPTED")
                ),
                "raw"
        );

        when(metaDeliberationAgent.decide(any(), any())).thenReturn(plan);

        DeliberationDecision d = service.deliberate(ctx);

        @SuppressWarnings("unchecked")
        List<String> steps = (List<String>) d.result().meta().get("metaControl.steps");

        assertThat(steps).containsExactly(
                // sorted by index: 2,3,5
                "2|UNSTICK|planner|do Y|stop_if HasPlannerOutcome",
                "3|REDO_LAST|merge|do Z|stop_cycle_if MutationAcceptanceState.ACCEPTED",
                "5|NEXT_STEP|evolution|do X|Never"
        );
    }

    @Test
    void metaPlan_stopCycleIfTakesPrecedenceOverStopIf() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c1")
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .lastAttemptId("attempt-1")
                .build();

        when(proposer.propose(any())).thenReturn(new DeliberationResult(
                DeliberationMode.NOOP, 0.1, "proposed",
                List.of(), List.of(),
                Map.of(), List.of()
        ));

        MetaDeliberationPlan plan = new MetaDeliberationPlan(
                false,
                DeliberationMode.NOOP,
                "r",
                List.of(
                        new MetaActionStep(
                                1,
                                MetaActionStep.Need.UNSTICK,
                                "planner",
                                "req",
                                "GoalProgressState.STAGNATING",
                                "MutationAcceptanceState.ACCEPTED"
                        )
                ),
                "raw"
        );

        when(metaDeliberationAgent.decide(any(), any())).thenReturn(plan);

        DeliberationDecision d = service.deliberate(ctx);

        @SuppressWarnings("unchecked")
        List<String> steps = (List<String>) d.result().meta().get("metaControl.steps");

        assertThat(steps).containsExactly(
                "1|UNSTICK|planner|req|stop_cycle_if MutationAcceptanceState.ACCEPTED"
        );
    }

    @Test
    void metaPlan_neverStringsDoNotEmitStopGuards_andStepsKeyStillExists() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c1")
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .lastAttemptId("attempt-1")
                .build();

        when(proposer.propose(any())).thenReturn(new DeliberationResult(
                DeliberationMode.NOOP, 0.1, "proposed",
                List.of(), List.of(),
                Map.of(), List.of()
        ));

        MetaDeliberationPlan plan = new MetaDeliberationPlan(
                true,
                DeliberationMode.PAUSE,
                "r",
                List.of(
                        new MetaActionStep(1, MetaActionStep.Need.NONE, "evolution", "req", "Never", "never"),
                        new MetaActionStep(2, MetaActionStep.Need.NONE, "planner", "req", "  never  ", "")
                ),
                "raw"
        );

        when(metaDeliberationAgent.decide(any(), any())).thenReturn(plan);

        DeliberationDecision d = service.deliberate(ctx);

        assertThat(d.result().meta()).containsEntry("metaControl.stop", true);
        assertThat(d.result().meta()).containsEntry("metaControl.mode", "PAUSE");
        assertThat(d.result().meta()).containsEntry("metaControl.rationale", "r");
        assertThat(d.result().meta()).containsKey("metaControl.steps");

        @SuppressWarnings("unchecked")
        List<String> steps = (List<String>) d.result().meta().get("metaControl.steps");

        assertThat(steps).containsExactly(
                "1|NONE|evolution|req|Never",
                "2|NONE|planner|req|Never"
        );
    }

    @Test
    void metaPlan_whenStepsNull_stepsMetaIsEmptyList() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c1")
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .lastAttemptId("attempt-1")
                .build();

        when(proposer.propose(any())).thenReturn(new DeliberationResult(
                DeliberationMode.NOOP, 0.1, "proposed",
                List.of(), List.of(),
                Map.of(), List.of()
        ));

        MetaDeliberationPlan plan = new MetaDeliberationPlan(
                false,
                DeliberationMode.NOOP,
                "r",
                null,
                "raw"
        );

        when(metaDeliberationAgent.decide(any(), any())).thenReturn(plan);

        DeliberationDecision d = service.deliberate(ctx);

        @SuppressWarnings("unchecked")
        List<String> steps = (List<String>) d.result().meta().get("metaControl.steps");

        assertThat(steps).isNotNull();
        assertThat(steps).isEmpty();
    }
}