package com.walpro.autobuilder.agent.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.agent.analysis.model.AnalysisOutcome;
import com.walpro.autobuilder.core.grounding.SymbolGroundingService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalKindClassifier;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalSimilarityService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;

/**
 * T2-DO: analysis-side dedup must consider GIVEN_UP siblings, not just ACTIVE/COMPLETED. A
 * candidate that matches a GIVEN_UP sibling is blocked AND registers a T1-EI rehabilitation vote.
 */
@ExtendWith(MockitoExtension.class)
class AnalysisAgentDedupTest {

    @TempDir
    Path tempDir;

    @Mock private AnalysisLLMAdapter adapter;
    @Mock private GoalPlannerService goalPlannerService;
    @Mock private GoalKindClassifier kindClassifier;
    @Mock private GoalSimilarityService similarityService;
    @Mock private BehaviourLogger behaviourLogger;

    private AnalysisAgent agent;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(tempDir.resolve("output"));
        FileService fileService = new FileService(tempDir.toString(), new ObjectMapper());
        SymbolGroundingService groundingService = new SymbolGroundingService(fileService);
        agent = new AnalysisAgent(adapter, goalPlannerService, kindClassifier, similarityService, groundingService);
        agent.setBehaviourLogger(behaviourLogger);

        // Sensible defaults for shared stubs.
        when(goalPlannerService.getGoalMetadataInt(eq("parent-1"), anyString())).thenReturn(0);
        when(goalPlannerService.countUnmetCriteria("parent-1")).thenReturn(5);
        when(goalPlannerService.getGlobalAnalysisInjectionBudget()).thenReturn(10);
        when(kindClassifier.classify(anyString())).thenReturn(GoalKindClassifier.GoalKind.EXECUTABLE);
    }

    private CycleContextData ctx() {
        return CycleContextData.builder()
                .currentGoalId("root-1")
                .currentSubGoalId("parent-1")
                .cycleName("cycle-test")
                .build();
    }

    private AnalysisOutcome outcomeFor(String description) {
        return new AnalysisOutcome(
                new ArrayList<>(), new ArrayList<>(),
                List.of(description),
                Map.of(description, List.of("Criterion A")),
                "ok", "EXECUTABLE", false, false);
    }

    private GoalEntity sibling(String id, String desc, GoalStatus status) {
        GoalEntity g = new GoalEntity();
        g.setId(id);
        g.setDescription(desc);
        g.setStatus(status);
        g.setParentId("root-1");
        return g;
    }

    @Test
    void givenUpDuplicate_blocksInjection_andRegistersRehabilitationVote() {
        String desc = "POST /members endpoint missing validation for duplicate email (409 response)";
        AnalysisOutcome rawOutcome = outcomeFor(desc);
        when(adapter.analyze(any(CycleContextData.class))).thenReturn(rawOutcome);

        GoalEntity givenUp = sibling("goal-given-up", desc, GoalStatus.GIVEN_UP);
        when(goalPlannerService.getAllGoals()).thenReturn(List.of(givenUp));
        when(similarityService.findEquivalent(eq(desc), any())).thenReturn(givenUp);
        when(goalPlannerService.registerRehabilitationVote(eq("goal-given-up"), eq("cycle-test")))
                .thenReturn(1);

        agent.execute(ctx());

        verify(goalPlannerService).registerRehabilitationVote("goal-given-up", "cycle-test");
        verify(goalPlannerService, never()).addSubGoal(
                anyString(), anyString(), any(Boolean.class),
                anyString(), anyString(), any());
        verify(behaviourLogger, atLeastOnce()).subgoalDedupVote(
                eq("cycle-test"), eq("analysis"), eq("goal-given-up"), eq("GIVEN_UP"), eq(desc));
    }

    @Test
    void activeDuplicate_blocksSilently_withoutVote() {
        String desc = "POST /loans validation duplicate";
        AnalysisOutcome rawOutcome = outcomeFor(desc);
        when(adapter.analyze(any(CycleContextData.class))).thenReturn(rawOutcome);

        GoalEntity active = sibling("goal-active", desc, GoalStatus.ACTIVE);
        when(goalPlannerService.getAllGoals()).thenReturn(List.of(active));
        when(similarityService.findEquivalent(eq(desc), any())).thenReturn(active);

        agent.execute(ctx());

        verify(goalPlannerService, never()).registerRehabilitationVote(anyString(), anyString());
        verify(goalPlannerService, never()).addSubGoal(
                anyString(), anyString(), any(Boolean.class),
                anyString(), anyString(), any());
        verify(behaviourLogger, never()).subgoalDedupVote(
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void noMatch_proceedsWithInjection() {
        String desc = "Fresh new subgoal description";
        AnalysisOutcome rawOutcome = outcomeFor(desc);
        when(adapter.analyze(any(CycleContextData.class))).thenReturn(rawOutcome);

        when(goalPlannerService.getAllGoals()).thenReturn(List.of());
        lenient().when(similarityService.findEquivalent(anyString(), any())).thenReturn(null);

        agent.execute(ctx());

        verify(goalPlannerService).addSubGoal(
                anyString(), eq(desc), any(Boolean.class),
                eq("parent-1"), eq("analysis"), eq(GoalKindClassifier.GoalKind.EXECUTABLE));
    }
}
