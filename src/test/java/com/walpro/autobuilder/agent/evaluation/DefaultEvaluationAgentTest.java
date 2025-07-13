package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.agent.evaluation.DefaultEvaluationAgent;
import com.walpro.autobuilder.agent.evaluation.graph.EvaluationGraphFacade;
import com.walpro.autobuilder.agent.evaluation.heuristic.HeuristicNegotiator;
import com.walpro.autobuilder.agent.evaluation.heuristic.SuspiciousScoreHeuristic;
import com.walpro.autobuilder.agent.evaluation.heuristic.strategy.score.NoOpScoreAdjustmentStrategy;
import com.walpro.autobuilder.agent.evaluation.heuristic.strategy.score.ScoreAdjustmentStrategy;
import com.walpro.autobuilder.agent.evaluation.heuristic.strategy.score.WeightedAverageScoreAdjustmentStrategy;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationInput;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.protocol.EvaluationResponseParser;
import com.walpro.autobuilder.agent.shared.LLMAgentProtocolInvoker;
import com.walpro.autobuilder.agent.shared.LLMResponseResult;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import com.walpro.autobuilder.service.FileService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DefaultEvaluationAgentTest {

    @Mock private EvaluationGraphFacade graphFacade;
    @Mock private LLMClient llmClient;
    @Mock private FileService fileService;
    @Mock private HeuristicNegotiator heuristicNegotiator;
    @Mock private LLMAgentProtocolInvoker protocolInvoker;

    private DefaultEvaluationAgent agent;

    @BeforeEach
    void setup() {
        ScoreAdjustmentStrategy testStrategy = new NoOpScoreAdjustmentStrategy(); // or a real one
        heuristicNegotiator = new HeuristicNegotiator(new WeightedAverageScoreAdjustmentStrategy());

        agent = new DefaultEvaluationAgent(
                graphFacade,
                llmClient,
                protocolInvoker,
                heuristicNegotiator,
                List.of(new SuspiciousScoreHeuristic())
        );
    }


    @Test
    void testEvaluate_acceptsValidMutation() {
        String validResponse = """
            #Evaluation
            Score: 0.91
            MutationAcceptanceState: ACCEPTED
            GoalProgressState: PROGRESSING
            Message: Looks good.
            """;

        EvaluationOutcome parsed = new EvaluationResponseParser().parse(validResponse);

        when(protocolInvoker.callWithValidation(
                any(),
                anyString(),
                anyString(),
                any(),
                any()
        )).thenReturn(LLMResponseResult.success(parsed, validResponse));

        EvaluationInput input = new EvaluationInput(
                0.91,
                0.80,
                Set.of("src/Main.java"),
                Set.of(),
                "Improve logic", "dummySubgoal",
                "1", "2",
                Map.of(),
                List.of(),
                Map.of(),
                Map.of(),
                Collections.emptyList()
        );

        EvaluationOutcome actual = agent.evaluate(input);

        assertThat(actual.score()).isEqualTo(0.91);
        assertThat(actual.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(actual.goalProgress()).isEqualTo(GoalProgressState.PROGRESSING);
        assertThat(actual.feedbackMessage()).contains("Looks good.");
    }

    @Test
    void testEvaluate_withInvalidLLMResponse_returnsFallback() {
        EvaluationInput input = new EvaluationInput(
                0.9,
                null,
                Set.of("X.java"),
                Set.of(),
                "Test fallback", "dummySubgoal",
                "1", "2",
                Map.of(),
                List.of(),
                Map.of(),
                Map.of(),
                Collections.emptyList()
        );

        when(protocolInvoker.callWithValidation(
                any(),
                anyString(),
                anyString(),
                any(Predicate.class),
                any(Function.class)
        )).thenReturn(LLMResponseResult.failure("invalid", "raw"));

        EvaluationOutcome outcome = agent.evaluate(input);

        assertThat(outcome.score()).isEqualTo(-1.0);
        assertThat(outcome.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(outcome.goalProgress()).isEqualTo(GoalProgressState.NOT_EVALUATED);
        assertThat(outcome.feedbackMessage().toLowerCase())
                .contains("did not follow")
                .contains("protocol");    }

    @Test
    void testSuspiciousScoreTriggersDowngrade() {
        // Use real heuristic + real negotiator
        DefaultEvaluationAgent realAgent = new DefaultEvaluationAgent(
                graphFacade,
                llmClient,
                protocolInvoker,
                heuristicNegotiator,
                List.of(new SuspiciousScoreHeuristic())
        );

        EvaluationInput input = new EvaluationInput(
                1.0,
                0.99,
                Set.of(), // No file changes
                Set.of(),
                "Stagnation test", "dummySubgoal",
                "1", "2",
                Map.of(),
                List.of(),
                Map.of(),
                Map.of(),
                Collections.emptyList()
        );

        EvaluationOutcome suspicious = new EvaluationOutcome(
                1.0,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.FULFILLED,
                "Everything is perfect.",
                null
        );

        when(protocolInvoker.callWithValidation(
                any(),
                anyString(),
                anyString(),
                any(Predicate.class),
                any(Function.class)
        )).thenReturn(LLMResponseResult.success(suspicious, "#Evaluation"));

        EvaluationOutcome downgraded = realAgent.evaluate(input);

        assertThat(downgraded.score()).isLessThan(1.0);
        assertThat(downgraded.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(downgraded.goalProgress()).isEqualTo(GoalProgressState.STAGNATING);
        assertThat(downgraded.feedbackMessage()).containsIgnoringCase("overruled");
    }

    @Test
    void testGraphFacadeIsCalledOnEvaluation() {
        EvaluationInput input = new EvaluationInput(
                0.9,
                0.8,
                Set.of("A.java"),
                Set.of(),
                "Add feature", "dummySubgoal",
                "1", "2",
                Map.of(),
                List.of(),
                Map.of(),
                Map.of(),
                Collections.emptyList()
        );

        EvaluationOutcome outcome = new EvaluationOutcome(
                0.9,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "Added new functionality.",
                null
        );

        when(protocolInvoker.callWithValidation(
                any(),
                anyString(),
                anyString(),
                any(Predicate.class),
                any(Function.class)
        )).thenReturn(LLMResponseResult.success(outcome, "#Evaluation"));

        agent.evaluate(input);
        agent.evolveGraph(input, outcome);

        verify(graphFacade).recordEvaluation(eq(input), eq(outcome));
    }
}
