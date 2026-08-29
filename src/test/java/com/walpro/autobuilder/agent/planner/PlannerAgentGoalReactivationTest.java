package com.walpro.autobuilder.agent.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.core.grounding.SymbolGroundingService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalSimilarityService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.GoalType;
import com.walpro.autobuilder.goal.AbandonedGoalQueryService;

/**
 * T1-EI: planner-output truncation goal-reactivation regression tests.
 *
 * <p>These tests focus on the reactivation-by-repeated-proposal mechanism:
 * when the planner LLM keeps re-proposing work that's been GIVEN_UP, the matching
 * GIVEN_UP goal accumulates reactivation votes and is auto-reactivated once the
 * vote threshold is crossed.</p>
 */
@ExtendWith(MockitoExtension.class)
class PlannerAgentGoalReactivationTest {

    @Mock private PlannerLLMAdapter plannerAdapter;
    @Mock private GoalPlannerService goalPlannerService;
    @Mock private GoalSimilarityService similarityService;
    @Mock private SymbolGroundingService groundingService;
    @Mock private AbandonedGoalQueryService abandonedGoalQueryService;

    private PlannerAgent agent;

    @BeforeEach
    void setUp() {
        lenient().when(groundingService.groundSymbolReferences(anyString(), anySet()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(abandonedGoalQueryService.findRelatedAbandonedGoals(anyString()))
                .thenReturn(List.of());
        agent = new PlannerAgent(plannerAdapter, goalPlannerService, similarityService,
                groundingService, abandonedGoalQueryService);
    }

    @Test
    void firstDedupBlock_registersVote_butDoesNotReactivateYet() {
        CycleContextData ctx = ctxNoSubgoal("cycle-1");
        GoalEntity givenUpMatch = givenUp("g-loans", "Implement loans endpoints");

        PlannerOutcome outcome = outcomeProposing("Implement loans endpoints");
        when(plannerAdapter.plan(any(CycleContextData.class))).thenReturn(outcome);
        stubBlockingDedup("Implement loans endpoints", givenUpMatch);
        when(goalPlannerService.registerRehabilitationVote(eq("g-loans"), eq("cycle-1"))).thenReturn(1);

        CycleContextData updated = agent.execute(ctx);

        verify(goalPlannerService).registerRehabilitationVote("g-loans", "cycle-1");
        verify(goalPlannerService, never()).reactivateGoal(anyString());
        assertThat(updated.skippedProposalMatches()).containsEntry(
                "Implement loans endpoints", "g-loans");
    }

    @Test
    void voteThresholdCrossed_reactivatesMatchedGoal_andIncrementsCounter() {
        CycleContextData ctx = ctxNoSubgoal("cycle-3");
        GoalEntity givenUpMatch = givenUp("g-loans", "Implement loans endpoints");

        PlannerOutcome outcome = outcomeProposing("Implement loans endpoints");
        when(plannerAdapter.plan(any(CycleContextData.class))).thenReturn(outcome);
        stubBlockingDedup("Implement loans endpoints", givenUpMatch);
        when(goalPlannerService.registerRehabilitationVote(eq("g-loans"), eq("cycle-3"))).thenReturn(2);
        when(goalPlannerService.reactivateGoal("g-loans")).thenReturn(List.of("g-loans"));

        agent.execute(ctx);

        verify(goalPlannerService).reactivateGoal("g-loans");
        verify(goalPlannerService).clearRehabilitationVotes("g-loans");
        verify(goalPlannerService).incrementGoalMetadataInt("g-loans", "rehabilitationCount");
    }

    @Test
    void failCapReached_emitsIrreducibleGap_doesNotReactivate() {
        CycleContextData ctx = ctxNoSubgoal("cycle-9");
        GoalEntity givenUpMatch = givenUp("g-loans", "Implement loans endpoints");
        givenUpMatch.getMetadata().put("rehabilitationCount", 2);

        PlannerOutcome outcome = outcomeProposing("Implement loans endpoints");
        when(plannerAdapter.plan(any(CycleContextData.class))).thenReturn(outcome);
        stubBlockingDedup("Implement loans endpoints", givenUpMatch);

        CycleContextData updated = agent.execute(ctx);

        verify(goalPlannerService, never()).reactivateGoal(anyString());
        verify(goalPlannerService, never()).registerRehabilitationVote(anyString(), anyString());
        verify(goalPlannerService).saveGoalMetadata(givenUpMatch);
        assertThat(givenUpMatch.getMetadata().get("irreducibleGap")).isEqualTo(true);
    }

    @Test
    void explicitSubGoalReactivate_invokesReactivateAndIncrementsCounter() {
        CycleContextData ctx = ctxNoSubgoal("cycle-7");

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(true);
        when(outcome.newGoals()).thenReturn(List.of());
        when(outcome.reactivateGoalIds()).thenReturn(List.of("g-explicit"));

        when(plannerAdapter.plan(any(CycleContextData.class))).thenReturn(outcome);
        when(goalPlannerService.sanitizeProposedSubGoals(anyList())).thenReturn(List.of());
        when(goalPlannerService.reactivateGoal("g-explicit")).thenReturn(List.of("g-explicit"));
        stubRefreshGoalState(null);

        agent.execute(ctx);

        verify(goalPlannerService).reactivateGoal("g-explicit");
        verify(goalPlannerService).clearRehabilitationVotes("g-explicit");
        verify(goalPlannerService).incrementGoalMetadataInt("g-explicit", "rehabilitationCount");
    }

    // --- helpers -------------------------------------------------------------

    private CycleContextData ctxNoSubgoal(String cycleName) {
        return CycleContextData.builder()
                .cycleName(cycleName)
                .currentGoalId("root-1")
                .currentGoalDescription("Library REST API")
                .build();
    }

    private PlannerOutcome outcomeProposing(String description) {
        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(true);
        when(outcome.newGoals()).thenReturn(List.of(description));
        Map<String, List<String>> criteria = new HashMap<>();
        criteria.put(description, List.of("c1"));
        lenient().when(outcome.goalCriteria()).thenReturn(criteria);
        lenient().when(outcome.reactivateGoalIds()).thenReturn(List.of());
        return outcome;
    }

    private void stubBlockingDedup(String description, GoalEntity givenUpMatch) {
        when(goalPlannerService.sanitizeProposedSubGoals(anyList()))
                .thenReturn(List.of(description));
        when(similarityService.snapshotKnownDescriptions())
                .thenReturn(List.of(givenUpMatch.getDescription()));
        when(similarityService.isDuplicate(eq(description), anyList())).thenReturn(true);
        when(goalPlannerService.getAllGoals()).thenReturn(List.of(givenUpMatch));
        when(similarityService.findGivenUpMatch(eq(description), anyList())).thenReturn(givenUpMatch);
        when(goalPlannerService.getGoalMetadataInt(eq(givenUpMatch), eq("rehabilitationCount")))
                .thenReturn(toInt(givenUpMatch.getMetadata().get("rehabilitationCount")));

        stubRefreshGoalState(null);
    }

    private void stubRefreshGoalState(GoalEntity current) {
        GoalEntity root = mock(GoalEntity.class);
        lenient().when(root.getId()).thenReturn("root-1");
        lenient().when(root.getDescription()).thenReturn("root");
        lenient().when(goalPlannerService.getRootGoal()).thenReturn(root);
        lenient().when(goalPlannerService.getCurrentSubGoal()).thenReturn(current);
        lenient().when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        lenient().when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());
    }

    private static int toInt(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static GoalEntity givenUp(String id, String description) {
        GoalEntity g = new GoalEntity(id, description, "role", GoalType.SUBGOAL, null);
        g.setStatus(GoalStatus.GIVEN_UP);
        g.setMetadata(new HashMap<>());
        return g;
    }
}
