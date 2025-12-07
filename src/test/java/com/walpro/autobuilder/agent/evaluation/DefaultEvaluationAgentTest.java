package com.walpro.autobuilder.agent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.walpro.autobuilder.graph.evaluation.EvaluationActionRegistrar;
import com.walpro.autobuilder.graph.evaluation.EvaluationGraphFacade;
import com.walpro.autobuilder.analysis.heuristic.HeuristicNegotiator;
import com.walpro.autobuilder.analysis.heuristic.SuspiciousScoreHeuristic;
import com.walpro.autobuilder.analysis.heuristic.strategy.score.WeightedAverageScoreAdjustmentStrategy;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.protocol.evaluation.EvaluationResponseParser;
import com.walpro.autobuilder.agent.shared.LLMAgentProtocolInvoker;
import com.walpro.autobuilder.agent.shared.LLMResponseResult;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import com.walpro.autobuilder.core.service.FileService;

@ExtendWith(MockitoExtension.class)
class DefaultEvaluationAgentTest {

    @Mock private EvaluationGraphFacade graphFacade;
    @Mock private LLMClient llmClient;
    @Mock private FileService fileService;
    @Mock private LLMAgentProtocolInvoker protocolInvoker;
    @Mock private EvaluationActionRegistrar evaluationActionRegistrar;

    private DefaultEvaluationAgent agent;

    @BeforeEach
    void setup() {
        HeuristicNegotiator heuristicNegotiator = new HeuristicNegotiator(new WeightedAverageScoreAdjustmentStrategy());

        agent = new DefaultEvaluationAgent(
                graphFacade,
                llmClient,
                protocolInvoker,
                heuristicNegotiator,
                java.util.List.of(new SuspiciousScoreHeuristic()),
                evaluationActionRegistrar
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

        CycleContextData context = CycleContextData.builder().build();
        context = context.withScore(0.80);
        context = context.withProjectDiff(new ProjectDiffResult(null, Map.of("src/Main.java", "changed"), Set.of()));
        context = context.withCurrentGoalDescription("Improve logic");

        EvaluationOutcome actual = agent.evaluate(context);

        assertThat(actual.score()).isEqualTo(0.91);
        assertThat(actual.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(actual.goalProgress()).isEqualTo(GoalProgressState.PROGRESSING);
        assertThat(actual.feedbackMessage()).contains("Looks good.");
    }

    @SuppressWarnings("unchecked")
    @Test
    void testEvaluate_withInvalidLLMResponse_returnsFallback() {
    	CycleContextData context = CycleContextData.builder().build();
    	context = context.withScore(0.9);
    	context = context.withProjectDiff(new ProjectDiffResult(null, Map.of("X.java", "changed"), Set.of()));
    	context = context.withCurrentGoalDescription("Test fallback");

        when(protocolInvoker.callWithValidation(
                any(),
                anyString(),
                anyString(),
                any(Predicate.class),
                any(Function.class)
        )).thenReturn(LLMResponseResult.failure("invalid", "raw"));

        EvaluationOutcome outcome = agent.evaluate(context);

        assertThat(outcome.score()).isNull();
        assertThat(outcome.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(outcome.goalProgress()).isEqualTo(GoalProgressState.NOT_EVALUATED);
        assertThat(outcome.feedbackMessage().toLowerCase())
                .contains("did not follow")
                .contains("protocol");
    }

    @SuppressWarnings("unchecked")
    @Test
    void testSuspiciousScoreTriggersDowngrade() {
        DefaultEvaluationAgent realAgent = new DefaultEvaluationAgent(
                graphFacade,
                llmClient,
                protocolInvoker,
                new HeuristicNegotiator(new WeightedAverageScoreAdjustmentStrategy()),
                java.util.List.of(new SuspiciousScoreHeuristic()),
                evaluationActionRegistrar
        );

        CycleContextData context = CycleContextData.builder().build();
        context = context.withScore(0.99);
        context = context.withProjectDiff(new ProjectDiffResult(null, Map.of(), Set.of()));
        context = context.withCurrentGoalDescription("Stagnation test");

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

        EvaluationOutcome downgraded = realAgent.evaluate(context);

        assertThat(downgraded.score()).isLessThan(1.0);
        assertThat(downgraded.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(downgraded.goalProgress()).isEqualTo(GoalProgressState.STAGNATING);
        assertThat(downgraded.feedbackMessage()).containsIgnoringCase("overruled");
    }

    @SuppressWarnings("unchecked")
    @Test
    void testGraphFacadeIsCalledOnEvaluation() {
        CycleContextData context = CycleContextData.builder().build();
        context = context.withScore(0.8);
        context = context.withProjectDiff(new ProjectDiffResult(null, Map.of("A.java", "changed"), Set.of()));
        context = context.withCurrentGoalDescription("Add feature");

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

        agent.evaluate(context);
        agent.evolveGraph(context, outcome);

        verify(graphFacade).recordEvaluation(eq(context), eq(outcome));
    }
}
