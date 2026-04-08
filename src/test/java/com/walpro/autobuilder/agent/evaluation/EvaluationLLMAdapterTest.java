package com.walpro.autobuilder.agent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.shared.LLMResponseResult;
import com.walpro.autobuilder.agent.shared.LLMAgentProtocolInvoker;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import com.walpro.autobuilder.protocol.evaluation.EvaluationResponseParser;

@ExtendWith(MockitoExtension.class)
class EvaluationLLMAdapterTest {

    @Mock private LLMClient llmClient;
    @Mock private LLMAgentProtocolInvoker protocolInvoker;

    private EvaluationLLMAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new EvaluationLLMAdapter(llmClient, protocolInvoker);
    }

    @Test
    void evaluateRaw_acceptsValidMutation() {
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

        CycleContextData context = CycleContextData.builder()
                .score(0.80)
                .projectDiff(new ProjectDiffResult(null, Map.of("src/Main.java", "changed"), Set.of()))
                .currentGoalDescription("Improve logic")
                .build();

        ParsedEvaluation actual = adapter.evaluate(context);

        assertThat(actual.outcome().score()).isEqualTo(0.91);
        assertThat(actual.outcome().mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(actual.outcome().goalProgress()).isEqualTo(GoalProgressState.PROGRESSING);
        assertThat(actual.outcome().feedbackMessage()).contains("Looks good.");
    }

    @Test
    void evaluate_withCriteriaCheckBlock_parsesAssessments() {
        String responseWithCriteria = """
                #Evaluation
                Score: 0.54
                MutationAcceptanceState: ACCEPTED
                GoalProgressState: STAGNATING
                CriteriaCheck:
                [1] \u2713 POST /books returns HTTP 201 with book body
                [2] \u2717 GET /books returns all previously created books \u2014 endpoint not implemented yet
                Message: POST /books implemented; GET /books missing.
                """;

        EvaluationOutcome parsed = new EvaluationResponseParser().parse(responseWithCriteria);

        when(protocolInvoker.callWithValidation(
                any(), anyString(), anyString(), any(), any()
        )).thenReturn(LLMResponseResult.success(parsed, responseWithCriteria));

        CycleContextData context = CycleContextData.builder()
                .score(0.60)
                .projectDiff(new ProjectDiffResult(null, Map.of("src/BooksController.java", "changed"), Set.of()))
                .currentGoalDescription("Implement books API")
                .build();

        ParsedEvaluation result = adapter.evaluate(context);

        assertThat(result.criteriaAssessments()).hasSize(2);
        assertThat(result.criteriaAssessments().get(0)).contains("\u2713").contains("POST /books");
        assertThat(result.criteriaAssessments().get(1)).contains("\u2717").contains("GET /books");
    }

    @SuppressWarnings("unchecked")
    @Test
    void evaluateRaw_withInvalidLLMResponse_returnsFallback() {
        CycleContextData context = CycleContextData.builder()
                .score(0.9)
                .projectDiff(new ProjectDiffResult(null, Map.of("X.java", "changed"), Set.of()))
                .currentGoalDescription("Test fallback")
                .build();

        when(protocolInvoker.callWithValidation(
                any(),
                anyString(),
                anyString(),
                any(Predicate.class),
                any(Function.class)
        )).thenReturn(LLMResponseResult.failure("invalid", "raw"));

        ParsedEvaluation parsed = adapter.evaluate(context);

        assertThat(parsed.outcome().score()).isNull();
        assertThat(parsed.outcome().mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(parsed.outcome().goalProgress()).isEqualTo(GoalProgressState.NOT_EVALUATED);
        assertThat(parsed.outcome().feedbackMessage().toLowerCase())
                .contains("did not follow")
                .contains("protocol");
    }
}