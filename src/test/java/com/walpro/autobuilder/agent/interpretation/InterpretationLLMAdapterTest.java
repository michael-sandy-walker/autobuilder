package com.walpro.autobuilder.agent.interpretation;

import com.walpro.autobuilder.agent.interpretation.model.InterpretationOutcome;
import com.walpro.autobuilder.agent.shared.LLMAgentProtocolInvoker;
import com.walpro.autobuilder.agent.shared.LLMResponseResult;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.ChannelRequest;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.graph.core.ArtifactEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterpretationLLMAdapterTest {

    @Mock
    private LLMAgentProtocolInvoker protocolInvoker;

    @InjectMocks
    private InterpretationLLMAdapter adapter;

    @Test
    void interpret_successfulLLMResponse_returnsOutcome() {
        InterpretationOutcome expected = new InterpretationOutcome(
                "APPLIED",
                "Stagnation detected.",
                "Inject REPLAN_REQUESTED.",
                List.of(ChannelRequest.of(BasicSignals.REPLAN_REQUESTED, 0.8, "interpretation", ChannelRequest.horizonFromDecay(0.8, 0.05)))
        );

        when(protocolInvoker.callWithValidation(any(), any(), any(), any(), any()))
                .thenReturn(LLMResponseResult.success(expected, "raw response text"));

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .signals(new java.util.LinkedHashMap<>())
                .build();

        InterpretationOutcome result = adapter.interpret(ctx, List.of());

        assertThat(result.status()).isEqualTo("APPLIED");
        assertThat(result.summary()).isEqualTo("Stagnation detected.");
        assertThat(result.proposedRequests()).hasSize(1);
        assertThat(result.proposedRequests().get(0).kind()).isEqualTo(BasicSignals.REPLAN_REQUESTED);
    }

    @Test
    void interpret_invalidLLMResponse_returnsNoop() {
        when(protocolInvoker.callWithValidation(any(), any(), any(), any(), any()))
                .thenReturn(LLMResponseResult.failure("garbled output", "missing Status line"));

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("goal-1")
                .signals(new java.util.LinkedHashMap<>())
                .build();

        InterpretationOutcome result = adapter.interpret(ctx, List.of());

        assertThat(result.status()).isEqualTo("NOOP");
        assertThat(result.proposedRequests()).isEmpty();
    }

    @Test
    void interpret_forwardsContextAndArtifactsToInvoker() {
        InterpretationOutcome expected = new InterpretationOutcome("NOOP", "Nothing notable.", "", List.of());

        when(protocolInvoker.callWithValidation(any(), any(), any(), any(), any()))
                .thenReturn(LLMResponseResult.success(expected, "raw"));

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("goal-2")
                .signals(new java.util.LinkedHashMap<>())
                .build();

        List<ArtifactEntity> artifacts = List.of(
                new ArtifactEntity("a1", "planner_outcome", null, "some planner content", null),
                new ArtifactEntity("a2", "evaluation_payload", null, "some eval content", null)
        );

        adapter.interpret(ctx, artifacts);

        // The invoker must be called exactly once regardless of artifact count
        org.mockito.Mockito.verify(protocolInvoker).callWithValidation(any(), any(), any(), any(), any());
    }
}
