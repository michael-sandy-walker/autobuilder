package com.walpro.autobuilder.graph.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.heuristic.NegotiationResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

/**
 * Verifies that T3-BB saves CriterionResult nodes for BOTH passing and failing criteria,
 * and that unassessed criteria (LLM omits CriteriaCheck) are filled in as passed=false.
 */
@ExtendWith(MockitoExtension.class)
class EvaluationActionRegistrarCriterionPersistenceTest {

    @Mock(answer = org.mockito.Answers.RETURNS_DEEP_STUBS)
    private AgentGraphService agentGraphService;

    private EvaluationActionRegistrar registrar() {
        return new EvaluationActionRegistrar(agentGraphService, new ObjectMapper());
    }

    private NegotiationResult negotiationResult() {
        EvaluationOutcome outcome = new EvaluationOutcome(
                0.6, MutationAcceptanceState.REJECTED, GoalProgressState.PROGRESSING, "msg", null);
        return new NegotiationResult(outcome, List.of(), List.of());
    }

    @Test
    void registerEvaluation_savesPassingCriterionResult() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-1")
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .criteriaAssessments(List.of("[1] \u2713 Endpoint returns 201"))
                .build();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CriterionResultEntity>> captor = ArgumentCaptor.forClass(List.class);

        registrar().registerEvaluation("agent-1", ctx, negotiationResult());

        verify(agentGraphService).linkCriterionResults(captor.capture());
        List<CriterionResultEntity> saved = captor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).criterionIndex()).isEqualTo(1);
        assertThat(saved.get(0).passed()).isTrue();
        assertThat(saved.get(0).criterionText()).isEqualTo("Endpoint returns 201");
    }

    @Test
    void registerEvaluation_savesFailingCriterionResult() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-1")
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .criteriaAssessments(List.of("[2] \u2717 GET /books \u2014 not implemented"))
                .build();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CriterionResultEntity>> captor = ArgumentCaptor.forClass(List.class);

        registrar().registerEvaluation("agent-1", ctx, negotiationResult());

        verify(agentGraphService).linkCriterionResults(captor.capture());
        List<CriterionResultEntity> saved = captor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).criterionIndex()).isEqualTo(2);
        assertThat(saved.get(0).passed()).isFalse();
        assertThat(saved.get(0).criterionText()).isEqualTo("GET /books");
    }

    @Test
    void registerEvaluation_savesBothPassingAndFailingCriterionResults() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-1")
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .criteriaAssessments(List.of(
                        "[1] \u2713 Endpoint returns 201",
                        "[2] \u2717 GET /books \u2014 not implemented"
                ))
                .build();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CriterionResultEntity>> captor = ArgumentCaptor.forClass(List.class);

        registrar().registerEvaluation("agent-1", ctx, negotiationResult());

        verify(agentGraphService).linkCriterionResults(captor.capture());
        List<CriterionResultEntity> saved = captor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).passed()).isTrue();
        assertThat(saved.get(1).passed()).isFalse();
    }

    @Test
    void registerEvaluation_emptyAssessments_allDefinedCriteriaSavedAsFailed() {
        // LLM omits CriteriaCheck entirely (common for rejected mutations)
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-2")
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .criteriaAssessments(List.of())   // LLM returned nothing
                .currentSubGoalCriteria(List.of("POST /books returns 201", "GET /books returns 200"))
                .build();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CriterionResultEntity>> captor = ArgumentCaptor.forClass(List.class);

        registrar().registerEvaluation("agent-1", ctx, negotiationResult());

        verify(agentGraphService).linkCriterionResults(captor.capture());
        List<CriterionResultEntity> saved = captor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved).allMatch(cr -> !cr.passed());
        assertThat(saved.get(0).criterionIndex()).isEqualTo(1);
        assertThat(saved.get(0).criterionText()).isEqualTo("POST /books returns 201");
        assertThat(saved.get(1).criterionIndex()).isEqualTo(2);
        assertThat(saved.get(1).criterionText()).isEqualTo("GET /books returns 200");
    }

    @Test
    void registerEvaluation_partialAssessments_unassessedCriteriaSavedAsFailed() {
        // LLM assessed criterion 1 (✓) but omitted criterion 2 — treat omitted as failed
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-3")
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .criteriaAssessments(List.of("[1] \u2713 POST /books returns 201"))
                .currentSubGoalCriteria(List.of("POST /books returns 201", "GET /books returns 200"))
                .build();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CriterionResultEntity>> captor = ArgumentCaptor.forClass(List.class);

        registrar().registerEvaluation("agent-1", ctx, negotiationResult());

        verify(agentGraphService).linkCriterionResults(captor.capture());
        List<CriterionResultEntity> saved = captor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved.stream().filter(cr -> cr.criterionIndex() == 1).findFirst())
                .hasValueSatisfying(cr -> assertThat(cr.passed()).isTrue());
        assertThat(saved.stream().filter(cr -> cr.criterionIndex() == 2).findFirst())
                .hasValueSatisfying(cr -> {
                    assertThat(cr.passed()).isFalse();
                    assertThat(cr.criterionText()).isEqualTo("GET /books returns 200");
                });
    }
}
