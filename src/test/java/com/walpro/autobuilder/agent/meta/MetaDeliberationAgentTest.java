package com.walpro.autobuilder.agent.meta;

import com.walpro.autobuilder.agent.shared.LLMAgentProtocolInvoker;
import com.walpro.autobuilder.agent.shared.LLMResponseResult;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.core.deliberation.DeliberationMode;
import com.walpro.autobuilder.core.engine.DeliberationExecutionGate;
import com.walpro.autobuilder.core.engine.ExecutionAgent;
import com.walpro.autobuilder.core.engine.ExecutionEligibilityExplainer;
import com.walpro.autobuilder.core.engine.meta.MetaOverrideLearningStore;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshotService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import com.walpro.autobuilder.protocol.meta.MetaActionStep;
import com.walpro.autobuilder.protocol.meta.MetaActionStep.Need;
import com.walpro.autobuilder.protocol.meta.MetaDeliberationPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetaDeliberationAgentTest {

    @Mock LLMClient llmClient;
    @Mock LLMAgentProtocolInvoker invoker;
    @Mock ExecutionEligibilityExplainer eligibilityExplainer;
    @Mock MetaOverrideLearningStore learningStore;
    @Mock OrchestrationSnapshotService snapshotService;
    @Mock BehaviourLogger behaviourLogger;

    @Mock ExecutionAgent analysisAgent;
    @Mock ExecutionAgent evolutionAgent;
    @Mock ExecutionAgent evaluationAgent;
    @Mock ExecutionAgent plannerAgent;

    DeliberationExecutionGate gate = new DeliberationExecutionGate();
    MetaDeliberationAgent agent;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(analysisAgent.role()).thenReturn("analysis");
        when(evolutionAgent.role()).thenReturn("evolution");
        when(evaluationAgent.role()).thenReturn("evaluation");
        when(plannerAgent.role()).thenReturn("planner");

        agent = new MetaDeliberationAgent(
                llmClient, invoker, gate,
                List.of(analysisAgent, evolutionAgent, evaluationAgent, plannerAgent),
                eligibilityExplainer, learningStore, snapshotService
        );
        agent.setBehaviourLogger(behaviourLogger);

        when(learningStore.top(anyInt())).thenReturn(List.of());
        when(snapshotService.fetchLatestSnapshot(any(), any(), anyInt())).thenReturn(null);
    }

    private CycleContextData ctx() {
        return CycleContextData.builder()
                .cycleName("cycle-test")
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .build();
    }

    @SuppressWarnings("unchecked")
    private void givenLLMReturns(MetaDeliberationPlan plan) {
        when(invoker.callWithValidation(any(), any(), any(), any(), any()))
                .thenReturn((LLMResponseResult) LLMResponseResult.success(plan, "raw"));
    }

    private MetaActionStep step(int index, String role) {
        return new MetaActionStep(index, Need.NEXT_STEP, role, "do something", "Never", "Never");
    }

    // ── T2-DF: post-LLM filtering ──────────────────────────────────────────────────────

    @Test
    void decide_dropsStepsTargetingIneligibleRoles() {
        // only analysis is eligible this cycle
        when(eligibilityExplainer.canExecute(eq("analysis"), any())).thenReturn(true);
        when(eligibilityExplainer.canExecute(eq("evolution"), any())).thenReturn(false);
        when(eligibilityExplainer.canExecute(eq("evaluation"), any())).thenReturn(false);
        when(eligibilityExplainer.canExecute(eq("planner"), any())).thenReturn(false);
        // explainBlockers only called for blocked roles (eligible set used directly for analysis)
        when(eligibilityExplainer.explainBlockers(eq("evolution"), any())).thenReturn(List.of("build_blocked"));
        when(eligibilityExplainer.explainBlockers(eq("evaluation"), any())).thenReturn(List.of("build_blocked"));
        when(eligibilityExplainer.explainBlockers(eq("planner"), any())).thenReturn(List.of("build_blocked"));

        MetaDeliberationPlan llmPlan = new MetaDeliberationPlan(
                false, DeliberationMode.UNDIRECTED, "rationale",
                List.of(step(1, "evolution"), step(2, "analysis"), step(3, "evaluation")),
                "raw"
        );
        givenLLMReturns(llmPlan);

        MetaDeliberationPlan result = agent.decide(ctx(), DeliberationMode.UNDIRECTED);

        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().get(0).targetRole()).isEqualTo("analysis");
    }

    @Test
    void decide_allStepsIneligible_returnsEmptyPlan() {
        when(eligibilityExplainer.canExecute(anyString(), any())).thenReturn(false);
        when(eligibilityExplainer.explainBlockers(anyString(), any())).thenReturn(List.of("build_blocked"));

        MetaDeliberationPlan llmPlan = new MetaDeliberationPlan(
                false, DeliberationMode.UNDIRECTED, "rationale",
                List.of(step(1, "evolution"), step(2, "evaluation"), step(3, "planner")),
                "raw"
        );
        givenLLMReturns(llmPlan);

        MetaDeliberationPlan result = agent.decide(ctx(), DeliberationMode.UNDIRECTED);

        assertThat(result.steps()).isEmpty();
    }

    @Test
    void decide_allStepsEligible_planUnchanged() {
        when(eligibilityExplainer.canExecute(anyString(), any())).thenReturn(true);
        // no explainBlockers stub needed — all roles eligible, so it is never called

        List<MetaActionStep> steps = List.of(step(1, "evolution"), step(2, "evaluation"));
        MetaDeliberationPlan llmPlan = new MetaDeliberationPlan(
                false, DeliberationMode.UNDIRECTED, "rationale", steps, "raw"
        );
        givenLLMReturns(llmPlan);

        MetaDeliberationPlan result = agent.decide(ctx(), DeliberationMode.UNDIRECTED);

        // Same object — nothing was rebuilt
        assertThat(result).isSameAs(llmPlan);
    }

    @Test
    void decide_emitsBehaviourEventForEachFilteredStep() {
        when(eligibilityExplainer.canExecute(eq("analysis"), any())).thenReturn(true);
        when(eligibilityExplainer.canExecute(eq("evolution"), any())).thenReturn(false);
        when(eligibilityExplainer.canExecute(eq("evaluation"), any())).thenReturn(false);
        when(eligibilityExplainer.canExecute(eq("planner"), any())).thenReturn(false);
        when(eligibilityExplainer.explainBlockers(eq("evolution"), any())).thenReturn(List.of("build_blocked"));
        when(eligibilityExplainer.explainBlockers(eq("evaluation"), any())).thenReturn(List.of("build_blocked"));
        when(eligibilityExplainer.explainBlockers(eq("planner"), any())).thenReturn(List.of("build_blocked"));

        MetaDeliberationPlan llmPlan = new MetaDeliberationPlan(
                false, DeliberationMode.UNDIRECTED, "rationale",
                List.of(step(1, "evolution"), step(2, "evaluation")),
                "raw"
        );
        givenLLMReturns(llmPlan);

        agent.decide(ctx(), DeliberationMode.UNDIRECTED);

        verify(behaviourLogger).metaStepFiltered(eq("cycle-test"), eq(1), eq("evolution"), anyString());
        verify(behaviourLogger).metaStepFiltered(eq("cycle-test"), eq(2), eq("evaluation"), anyString());
        verifyNoMoreInteractions(behaviourLogger);
    }

    @Test
    void decide_noFilterEvents_whenNoStepsDropped() {
        when(eligibilityExplainer.canExecute(anyString(), any())).thenReturn(true);

        MetaDeliberationPlan llmPlan = new MetaDeliberationPlan(
                false, DeliberationMode.UNDIRECTED, "rationale",
                List.of(step(1, "evolution")),
                "raw"
        );
        givenLLMReturns(llmPlan);

        agent.decide(ctx(), DeliberationMode.UNDIRECTED);

        verify(behaviourLogger, never()).metaStepFiltered(any(), anyInt(), any(), any());
    }

    // ── T2-DF: pre-LLM eligible roles block ───────────────────────────────────────────

    @Test
    void decide_eligibilityBlockContainsDirective_whenOnlyAnalysisEligible() {
        when(eligibilityExplainer.canExecute(eq("analysis"), any())).thenReturn(true);
        when(eligibilityExplainer.canExecute(eq("evolution"), any())).thenReturn(false);
        when(eligibilityExplainer.canExecute(eq("evaluation"), any())).thenReturn(false);
        when(eligibilityExplainer.canExecute(eq("planner"), any())).thenReturn(false);
        when(eligibilityExplainer.explainBlockers(eq("evolution"), any())).thenReturn(List.of("build_blocked"));
        when(eligibilityExplainer.explainBlockers(eq("evaluation"), any())).thenReturn(List.of("build_blocked"));
        when(eligibilityExplainer.explainBlockers(eq("planner"), any())).thenReturn(List.of("build_blocked"));

        givenLLMReturns(new MetaDeliberationPlan(false, DeliberationMode.UNDIRECTED, "r", List.of(), "raw"));

        agent.decide(ctx(), DeliberationMode.UNDIRECTED);

        // Capture the user prompt to verify it contains the eligibility directive
        verify(invoker).callWithValidation(any(), any(),
                argThat(prompt -> prompt.contains("Only these roles can execute this cycle: ANALYSIS")
                        && prompt.contains("Blocked (do NOT propose steps for these)")
                        && prompt.contains("You MUST NOT target blocked roles")),
                any(), any());
    }
}
