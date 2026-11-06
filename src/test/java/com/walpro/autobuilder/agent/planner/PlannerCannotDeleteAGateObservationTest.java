package com.walpro.autobuilder.agent.planner;

import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.grounding.SymbolGroundingService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.goal.AbandonedGoalQueryService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalSimilarityService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.SubsumptionReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Replays the prune that cost run {@code 2026-09-03_19-05-02} its artifact.
 *
 * <p>The boot gate watched the application die at startup and injected the message verbatim as
 * work at 19:15:23, tagged {@code originatingGate=boot-gate}. Thirty-six seconds later the planner
 * listed that id under {@code #SubGoalRemove} and replaced it with "implement the complete
 * BookController". The loop then did exactly that, merging the controller five times across
 * twenty-five minutes, while the unregistered repository that actually stopped it was never
 * touched again. The run ended {@code smoke=boot-failed}.
 *
 * <p>The rule this fixes: a plan can be rewritten, but a failure that was OBSERVED cannot be
 * un-observed by rewriting one.
 */
@ExtendWith(MockitoExtension.class)
class PlannerCannotDeleteAGateObservationTest {

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
    @DisplayName("the planner cannot delete a work item the boot gate raised from a real startup failure")
    void aGateObservationSurvivesReplanning() {
        GoalEntity observed = goal("g-boot", BlockerTypes.GATE_BOOT,
                "the application exits at startup (exit 1) — Parameter 0 of constructor in "
                        + "BookController required a bean of type 'BookRepository'");

        planProposing(observed, "Implement the complete BookController with all three Book endpoints");

        verify(goalPlannerService, never()).transitionGoal(eq("g-boot"), eq(GoalStatus.SUBSUMED),
                anyString(), any(SubsumptionReason.class));
    }

    /**
     * T4-EI field 2026-11-06 (firestarter-cashbook, twice): two minutes after a capability import was kept, the planner
     * listed its goal under #SubGoalRemove and appended the receiver's own finished spec in its place.
     */
    @Test
    @DisplayName("the planner cannot delete the goal an import the operator kept landed")
    void anImportsGoalSurvivesReplanning() {
        GoalEntity imported = goal("g-input", null, "Capabilities taken from firestarter, as chosen: ...");
        imported.getMetadata().put("inputId", "input-1");

        planProposing(imported, "Implement Ledger class in package org.cashbook.ledger");

        verify(goalPlannerService, never()).transitionGoal(eq("g-input"), eq(GoalStatus.SUBSUMED),
                anyString(), any(SubsumptionReason.class));
    }

    @Test
    @DisplayName("an ordinary planned subgoal is still pruned — only observations are protected")
    void anOrdinaryGoalIsStillPruned() {
        GoalEntity planned = goal("g-planned", null, "Add pagination to the book listing");

        planProposing(planned, "Implement the complete BookController with all three Book endpoints");

        verify(goalPlannerService).transitionGoal(eq("g-planned"), eq(GoalStatus.SUBSUMED),
                anyString(), any(SubsumptionReason.class));
    }

    /** A remaining subgoal, optionally carrying the gate that raised it. */
    private static GoalEntity goal(String id, String originatingGate, String description) {
        GoalEntity g = mock(GoalEntity.class);
        Map<String, Object> metadata = new HashMap<>();
        if (originatingGate != null) metadata.put(BlockerTypes.ORIGINATING_GATE_KEY, originatingGate);
        lenient().when(g.getId()).thenReturn(id);
        lenient().when(g.getDescription()).thenReturn(description);
        lenient().when(g.getStatus()).thenReturn(GoalStatus.ACTIVE);
        lenient().when(g.getMetadata()).thenReturn(metadata);
        lenient().when(g.getMergeCount()).thenReturn(0);
        return g;
    }

    /**
     * One planning cycle that asks to remove {@code toRemove} and append {@code proposed}.
     *
     * <p>Stubs are lenient because the two tests drive different paths through the prune loop: a
     * protected goal returns early, so the strict checker would call the append-side stubs unused
     * in one test and the prune-side ones unused in the other.
     */
    private void planProposing(GoalEntity toRemove, String proposed) {
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1").currentGoalDescription("root desc").build();
        // Read off the mock BEFORE any stubbing: a mock call inside when(...) is read as an
        // unfinished stubbing of that second mock.
        String removeId = toRemove.getId();

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        lenient().when(outcome.updated()).thenReturn(true);
        lenient().when(outcome.newGoals()).thenReturn(List.of(proposed));
        lenient().when(outcome.goalsToRemove()).thenReturn(List.of(removeId));
        lenient().when(outcome.message()).thenReturn("m");
        lenient().when(outcome.goalCriteria()).thenReturn(Map.of());
        lenient().when(plannerAdapter.plan(any(CycleContextData.class))).thenReturn(outcome);

        lenient().when(goalPlannerService.sanitizeProposedSubGoals(anyList())).thenReturn(List.of(proposed));
        lenient().when(similarityService.snapshotKnownDescriptions()).thenReturn(List.of());
        lenient().when(goalPlannerService.getAllGoals()).thenReturn(List.of(toRemove));
        lenient().when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(toRemove));
        lenient().when(similarityService.isDuplicate(anyString(), anyList())).thenReturn(false);
        lenient().when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());
        lenient().when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);

        GoalEntity root = mock(GoalEntity.class);
        lenient().when(root.getId()).thenReturn("root-1");
        lenient().when(root.getDescription()).thenReturn("root desc");
        lenient().when(goalPlannerService.getRootGoal()).thenReturn(root);

        agent.execute(ctx);
    }
}
