package com.walpro.autobuilder.agent.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import com.walpro.autobuilder.config.SystemCapabilityManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.agent.shared.LLMAgentProtocolInvoker;
import com.walpro.autobuilder.agent.shared.LLMResponseResult;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.planner.PlannerActionRegistrar;
import com.walpro.autobuilder.graph.planner.PlannerGraphFacade;

@ExtendWith(MockitoExtension.class)
class PlannerLLMAdapterTest {

    @Mock private PlannerGraphFacade graphFacade;
    @Mock private LLMClient llmClient;
    @Mock private LLMAgentProtocolInvoker invoker;
    @Mock private PlannerActionRegistrar registrar;
    @Mock private SystemCapabilityManifest systemCapabilityManifest;
    @Mock private com.walpro.autobuilder.core.grounding.SymbolGroundingService groundingService;

    private PlannerLLMAdapter adapter;

    @BeforeEach
    void setUp() {
        // Default: grounding service is a no-op pass-through so existing tests don't have to
        // care about goal-description refresh. Tests that exercise refresh can override.
        org.mockito.Mockito.lenient().when(groundingService.refreshGoalAnnotations(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));
        adapter = new PlannerLLMAdapter(graphFacade, llmClient, invoker, registrar,
                systemCapabilityManifest, groundingService);
    }

    @Test
    void plan_whenInvokerSuccess_returnsOutcome() {
        CycleContextData input = CycleContextData.builder()
                .currentGoalId("root-1")
                .currentSubGoalId("sub-1")
                .build();

        PlannerOutcome expected = new PlannerOutcome(true, List.of("A", "B"), List.of(), "ok", null, null);

        @SuppressWarnings("unchecked")
        LLMResponseResult<PlannerOutcome> result = mock(LLMResponseResult.class);
        when(result.success()).thenReturn(true);
        when(result.outcome()).thenReturn(expected);

        when(invoker.<PlannerOutcome>callWithValidation(
                eq(llmClient),
                anyString(),
                anyString(),
                any(),
                any()
        )).thenReturn(result);

        PlannerOutcome actual = adapter.plan(input);

        assertThat(actual).isSameAs(expected);
    }

    @Test
    void plan_whenInvokerFailure_returnsSafeFallbackOutcome() {
        CycleContextData input = CycleContextData.builder().build();

        @SuppressWarnings("unchecked")
        LLMResponseResult<PlannerOutcome> result = mock(LLMResponseResult.class);
        when(result.success()).thenReturn(false);
        when(result.failureReason()).thenReturn("bad format");
        when(result.rawContent()).thenReturn("raw");

        when(invoker.<PlannerOutcome>callWithValidation(
                eq(llmClient),
                anyString(),
                anyString(),
                any(),
                any()
        )).thenReturn(result);

        PlannerOutcome actual = adapter.plan(input);

        assertThat(actual.updated()).isFalse();
        assertThat(actual.newGoals()).isEmpty();
        assertThat(actual.message()).contains("Invalid response: bad format");
    }

    @Test
    void evolveGraph_registersPlannerActionAndRecordsPlanning() {
        CycleContextData input = CycleContextData.builder()
                .currentGoalId("root-1")
                .build();
        PlannerOutcome outcome = new PlannerOutcome(false, List.of(), List.of(), "x", null, null);

        adapter.evolveGraph(input, outcome);

        verify(registrar).registerPlannerAction(eq("PlannerAlpha"), eq(input), eq(outcome));
        verify(graphFacade).recordPlanning(eq(input), eq(outcome));
    }
}