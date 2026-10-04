package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.CycleResultState;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoalConsolidationServiceTest {

    @Mock private GoalPlannerService goalPlannerService;
    @Mock private GoalAdoptionService goalAdoptionService;
    @Mock private GoalSimilarityService goalSimilarityService;

    private GoalConsolidationService service;

    @BeforeEach
    void setUp() {
        service = new GoalConsolidationService(goalPlannerService, goalAdoptionService, goalSimilarityService);
        service.setStagnationStreakTrigger(3);
        service.setActiveLeafThreshold(10);
        service.setMaxReviewerCalls(5);
        service.setSimilarityFloor(0.5);
    }

    private static GoalEntity subgoal(String id, String description) {
        return new GoalEntity(id, description, "role", GoalType.SUBGOAL, "root");
    }

    private static CycleContextData ctx(CycleResultState state) {
        return CycleContextData.builder().cycleName("cycle-1").cycleResultState(state).build();
    }

    private static CycleContextData mergedCtx(CycleResultState state) {
        return CycleContextData.builder().cycleName("cycle-1").cycleResultState(state).merged(true).build();
    }

    private void stubSimilarity(float value) {
        lenient().when(goalSimilarityService.similarity(anyString(), anyString())).thenReturn(value);
    }

    // ── triggers ──

    @Test
    void noTrigger_belowThresholds_noReviewerCall() {
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(
                subgoal("a", "Implement retrieval logic in the service layer")));

        service.maybeConsolidate(ctx(CycleResultState.STAGNATING)); // streak 1 of 3

        verify(goalAdoptionService, never()).findEquivalent(any(), anyList(), any());
    }

    @Test
    void stagnationStreak_triggersPass() {
        GoalEntity a = subgoal("a", "Implement retrieval logic in the service layer");
        GoalEntity b = subgoal("b", "Add retrieval method to the service layer");
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(a, b));
        lenient().when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());
        lenient().when(goalPlannerService.getAbandonedGoals()).thenReturn(List.of());
        stubSimilarity(0.9f);
        when(goalAdoptionService.findEquivalent(any(), anyList(), any())).thenReturn(Optional.empty());

        service.maybeConsolidate(ctx(CycleResultState.STAGNATING));
        service.maybeConsolidate(ctx(CycleResultState.STAGNATING));
        verify(goalAdoptionService, never()).findEquivalent(any(), anyList(), any());
        service.maybeConsolidate(ctx(CycleResultState.STAGNATING));

        verify(goalAdoptionService, times(2)).findEquivalent(any(), anyList(), any());
    }

    @Test
    void progressCycle_resetsStagnationStreak() {
        lenient().when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(
                subgoal("a", "Implement retrieval logic")));

        service.maybeConsolidate(ctx(CycleResultState.STAGNATING));
        service.maybeConsolidate(ctx(CycleResultState.STAGNATING));
        service.maybeConsolidate(ctx(CycleResultState.CONTINUE)); // reset
        service.maybeConsolidate(ctx(CycleResultState.STAGNATING));
        service.maybeConsolidate(ctx(CycleResultState.STAGNATING));

        verify(goalAdoptionService, never()).findEquivalent(any(), anyList(), any());
    }

    @Test
    void activeLeafBloat_triggersPass_evenWithoutStagnation() {
        List<GoalEntity> leaves = new java.util.ArrayList<>();
        for (int i = 0; i < 11; i++) leaves.add(subgoal("g" + i, "Distinct work item number " + i));
        when(goalPlannerService.getRemainingGoals()).thenReturn(leaves);
        lenient().when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());
        lenient().when(goalPlannerService.getAbandonedGoals()).thenReturn(List.of());
        stubSimilarity(0.3f); // below floor — pass runs but nothing reaches the LLM

        // T2-EC: a merge marks the tree as having history, so leaf count alone may trigger.
        service.maybeConsolidate(mergedCtx(CycleResultState.CONTINUE));

        verify(goalAdoptionService, never()).findEquivalent(any(), anyList(), any());
        verify(goalPlannerService, never()).transitionGoal(anyString(), any(), anyString(), any());
    }

    @Test
    void freshTree_leafCountTriggerSuppressed_untilOneCyclePerLeafObserved() {
        // T2-EC: the planner's fresh initial decomposition exceeds the leaf threshold at
        // cycle 2 with 0 merges (observed 2026-06-11 21:35) — that is not bloat. The grace
        // window is derived, not configured: one observed cycle per active leaf. 11 leaves →
        // suppressed through cycle 10, eligible at cycle 11.
        GoalEntity a = subgoal("a", "Implement retrieval logic in the service layer");
        GoalEntity b = subgoal("b", "Add retrieval method to the service layer");
        List<GoalEntity> leaves = new java.util.ArrayList<>(List.of(a, b));
        for (int i = 0; i < 9; i++) leaves.add(subgoal("g" + i, "Distinct work item number " + i));
        when(goalPlannerService.getRemainingGoals()).thenReturn(leaves);
        lenient().when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());
        lenient().when(goalPlannerService.getAbandonedGoals()).thenReturn(List.of());
        stubSimilarity(0.9f);
        lenient().when(goalAdoptionService.findEquivalent(any(), anyList(), any())).thenReturn(Optional.empty());

        for (int cycle = 1; cycle <= 10; cycle++) {
            service.maybeConsolidate(ctx(CycleResultState.CONTINUE)); // cycles 1-10 — suppressed
        }
        verify(goalAdoptionService, never()).findEquivalent(any(), anyList(), any());

        service.maybeConsolidate(ctx(CycleResultState.CONTINUE)); // cycle 11 = leaf count — eligible
        verify(goalAdoptionService, atLeastOnce()).findEquivalent(any(), anyList(), any());
    }

    @Test
    void freshTree_mergeLiftsSuppressionImmediately() {
        // 11 leaves would mean 11 cycles of derived grace — a merge on cycle 1 lifts it at once.
        GoalEntity a = subgoal("a", "Implement retrieval logic in the service layer");
        GoalEntity b = subgoal("b", "Add retrieval method to the service layer");
        List<GoalEntity> leaves = new java.util.ArrayList<>(List.of(a, b));
        for (int i = 0; i < 9; i++) leaves.add(subgoal("g" + i, "Distinct work item number " + i));
        when(goalPlannerService.getRemainingGoals()).thenReturn(leaves);
        lenient().when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());
        lenient().when(goalPlannerService.getAbandonedGoals()).thenReturn(List.of());
        stubSimilarity(0.9f);
        lenient().when(goalAdoptionService.findEquivalent(any(), anyList(), any())).thenReturn(Optional.empty());

        service.maybeConsolidate(mergedCtx(CycleResultState.CONTINUE)); // cycle 1, merged

        verify(goalAdoptionService, atLeastOnce()).findEquivalent(any(), anyList(), any());
    }

    @Test
    void haltSignal_skipsEntirely() {
        CycleContextData halting = ctx(CycleResultState.STAGNATING)
                .withPausedReason("test");

        service.maybeConsolidate(halting);
        service.maybeConsolidate(halting);
        service.maybeConsolidate(halting);

        verify(goalAdoptionService, never()).findEquivalent(any(), anyList(), any());
    }

    // ── folding two ACTIVE near-duplicates ──

    private void primeStreak() {
        service.maybeConsolidate(ctx(CycleResultState.STAGNATING));
        service.maybeConsolidate(ctx(CycleResultState.STAGNATING));
    }

    @Test
    void activeDuplicate_foldsOneOfThePairToSubsumedCovered_withCriteriaAndMergeCarry() {
        GoalEntity keptGoal = subgoal("keep", "Implement member retrieval in the service");
        GoalEntity duplicate = subgoal("lose", "Add member retrieval method to the service");
        keptGoal.getMetadata().put("mergeCount", 2);
        keptGoal.getMetadata().put("acceptanceCriteria", List.of("Service exposes a retrieval method"));
        duplicate.getMetadata().put("mergeCount", 1);
        duplicate.getMetadata().put("acceptanceCriteria", List.of(
                "Service exposes a retrieval method",       // duplicate after normalization
                "Missing entries produce a not-found response")); // genuinely new

        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(keptGoal, duplicate));
        lenient().when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());
        lenient().when(goalPlannerService.getAbandonedGoals()).thenReturn(List.of());
        when(goalPlannerService.getGoalMetadataInt(keptGoal, "mergeCount")).thenReturn(2);
        when(goalPlannerService.getGoalMetadataInt(duplicate, "mergeCount")).thenReturn(1);
        stubSimilarity(0.9f);
        when(goalAdoptionService.findEquivalent(eq(keptGoal), anyList(), any()))
                .thenReturn(Optional.of(new GoalAdoptionService.ReviewedMatch(duplicate, 0.92)));

        BehaviourLogger behaviourLogger = mock(BehaviourLogger.class);
        service.setBehaviourLogger(behaviourLogger);

        primeStreak();
        service.maybeConsolidate(ctx(CycleResultState.STAGNATING));

        // the kept goal has more merges → the other goal of the pair is subsumed
        verify(goalPlannerService).transitionGoal(eq("lose"), eq(GoalStatus.SUBSUMED),
                anyString(), eq(SubsumptionReason.COVERED));
        // criteria carry: only the non-duplicate criterion is appended
        verify(goalPlannerService).setAcceptanceCriteria(eq("keep"), eq(List.of(
                "Service exposes a retrieval method",
                "Missing entries produce a not-found response")));
        // mergeCount carry: 2 + 1
        assertThat(keptGoal.getMetadata().get("mergeCount")).isEqualTo(3);
        verify(goalPlannerService).saveGoalMetadata(keptGoal);
        verify(behaviourLogger).goalConsolidation(anyString(), eq("keep"), eq("lose"),
                eq(0.92), eq("active-duplicate"), eq(1));
    }

    @Test
    void currentSubgoal_isPreferredAsKeptGoal_overMergeCount() {
        GoalEntity current = subgoal("cur", "Implement member retrieval in the service");
        GoalEntity other = subgoal("oth", "Add member retrieval method to the service");
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(current, other));
        lenient().when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());
        lenient().when(goalPlannerService.getAbandonedGoals()).thenReturn(List.of());
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(current);
        lenient().when(goalPlannerService.getGoalMetadataInt(any(GoalEntity.class), eq("mergeCount")))
                .thenAnswer(inv -> ((GoalEntity) inv.getArgument(0)).getId().equals("oth") ? 5 : 0);
        stubSimilarity(0.9f);
        when(goalAdoptionService.findEquivalent(eq(current), anyList(), any()))
                .thenReturn(Optional.of(new GoalAdoptionService.ReviewedMatch(other, 0.9)));

        primeStreak();
        service.maybeConsolidate(ctx(CycleResultState.STAGNATING));

        verify(goalPlannerService).transitionGoal(eq("oth"), eq(GoalStatus.SUBSUMED),
                anyString(), eq(SubsumptionReason.COVERED));
    }

    @Test
    void ancestorDescendantPair_isNeverFolded() {
        GoalEntity parent = subgoal("p", "Implement member retrieval in the service");
        GoalEntity child = new GoalEntity("c", "Implement the retrieval method in the service", "role",
                GoalType.SUBGOAL, "p");
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(parent, child));
        lenient().when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());
        lenient().when(goalPlannerService.getAbandonedGoals()).thenReturn(List.of());
        lenient().when(goalPlannerService.getGoalById("p")).thenReturn(parent);
        stubSimilarity(0.95f);

        primeStreak();
        service.maybeConsolidate(ctx(CycleResultState.STAGNATING));

        verify(goalAdoptionService, never()).findEquivalent(any(), anyList(), any());
        verify(goalPlannerService, never()).transitionGoal(anyString(), any(), anyString(), any());
    }

    @Test
    void buildFixSubgoals_areExcluded() {
        GoalEntity buildFix = subgoal("bf", "Fix compile error in the service class");
        buildFix.getMetadata().put(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX);
        GoalEntity other = subgoal("oth", "Fix compile error in the service layer");
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(buildFix, other));
        lenient().when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());
        lenient().when(goalPlannerService.getAbandonedGoals()).thenReturn(List.of());
        stubSimilarity(0.95f);

        primeStreak();
        service.maybeConsolidate(ctx(CycleResultState.STAGNATING));

        verify(goalAdoptionService, never()).findEquivalent(any(), anyList(), any());
    }

    @Test
    void reviewerEmpty_leavesTreeAsIs() {
        GoalEntity a = subgoal("a", "Implement member retrieval in the service");
        GoalEntity b = subgoal("b", "Add member retrieval method to the service");
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(a, b));
        lenient().when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());
        lenient().when(goalPlannerService.getAbandonedGoals()).thenReturn(List.of());
        stubSimilarity(0.9f);
        when(goalAdoptionService.findEquivalent(any(), anyList(), any())).thenReturn(Optional.empty());

        primeStreak();
        service.maybeConsolidate(ctx(CycleResultState.STAGNATING));

        verify(goalPlannerService, never()).transitionGoal(anyString(), any(), anyString(), any());
    }

    @Test
    void reviewerCallBudget_capsThePass() {
        service.setMaxReviewerCalls(1);
        GoalEntity a = subgoal("a", "Implement member retrieval in the service");
        GoalEntity b = subgoal("b", "Add member retrieval method to the service");
        GoalEntity c = subgoal("c", "Implement loan return handling in the controller");
        GoalEntity d = subgoal("d", "Add loan return endpoint to the controller");
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(a, b, c, d));
        lenient().when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());
        lenient().when(goalPlannerService.getAbandonedGoals()).thenReturn(List.of());
        stubSimilarity(0.9f);
        when(goalAdoptionService.findEquivalent(any(), anyList(), any())).thenReturn(Optional.empty());

        primeStreak();
        service.maybeConsolidate(ctx(CycleResultState.STAGNATING));

        verify(goalAdoptionService, times(1)).findEquivalent(any(), anyList(), any());
    }

    @Test
    void foldParticipants_areFrozenForTheRun_noCascadesNoGrabBags() {
        // a absorbs b. Afterwards a is frozen for the entire run (both sides): c's later match
        // against a is never reviewed — absorbed criteria span several components and make the
        // absorber look equivalent to everything it overlaps (runaway observed 2026-06-11
        // 22:41-22:42: 3 folds into one goal, then 13 criteria re-carried out of it).
        GoalEntity a = subgoal("a", "Implement member retrieval in the service");
        GoalEntity b = subgoal("b", "Add member retrieval method to the service");
        GoalEntity c = subgoal("c", "Provide member lookup in the service layer");
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(a, b, c));
        lenient().when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());
        lenient().when(goalPlannerService.getAbandonedGoals()).thenReturn(List.of());
        lenient().when(goalPlannerService.getGoalMetadataInt(any(GoalEntity.class), eq("mergeCount")))
                .thenAnswer(inv -> ((GoalEntity) inv.getArgument(0)).getId().equals("a") ? 2 : 0);
        stubSimilarity(0.9f);
        when(goalAdoptionService.findEquivalent(eq(a), anyList(), any()))
                .thenReturn(Optional.of(new GoalAdoptionService.ReviewedMatch(b, 0.9)));
        lenient().when(goalAdoptionService.findEquivalent(eq(c), anyList(), any()))
                .thenReturn(Optional.of(new GoalAdoptionService.ReviewedMatch(a, 0.9)));

        primeStreak();
        service.maybeConsolidate(ctx(CycleResultState.STAGNATING));

        verify(goalPlannerService).transitionGoal(eq("b"), eq(GoalStatus.SUBSUMED), anyString(),
                eq(SubsumptionReason.COVERED));
        verify(goalPlannerService, never()).transitionGoal(eq("a"), any(), anyString(), any());
        verify(goalPlannerService, never()).transitionGoal(eq("c"), any(), anyString(), any());

        // Next pass (fresh trigger): a and b stay frozen; c may still match OTHER goals, but
        // its only above-floor candidate was a — so no review reaches the LLM for a's pair.
        primeStreak();
        service.maybeConsolidate(ctx(CycleResultState.STAGNATING));
        verify(goalPlannerService, never()).transitionGoal(eq("a"), any(), anyString(), any());
    }

    // ── folding GIVEN_UP blockers covered by equivalent work ──

    /**
     * Previously asserted the opposite — that a COMPLETED kept goal receives NO criteria, on the
     * premise that it had already banked its own. That premise holds when the completion is real
     * and fails exactly when the completion claim is what is wrong, and the assertion protected
     * the failure: on clitarget run 2026-08-28_16-35-37 the correct fix was folded into a
     * COMPLETED goal that could never satisfy it, the criteria were dropped, and
     * reopenClosedButUnmetBlockers could not fire because checkSubgoalClosure passes a COMPLETED
     * goal declaring no criteria. Carrying them is what lets that reopen see the contradiction.
     */
    @Test
    void givenUpBlocker_coveredByCompletedEquivalent_carriesCriteriaSoAFalseCompletionCanReopen() {
        GoalEntity givenUp = subgoal("gu", "Implement loan return handling in the service");
        givenUp.setStatus(GoalStatus.GIVEN_UP);
        givenUp.getMetadata().put("acceptanceCriteria", List.of("Loan return marks the loan returned"));
        GoalEntity completed = subgoal("done", "Add loan return processing to the service");
        completed.setStatus(GoalStatus.COMPLETED);

        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of(completed));
        when(goalPlannerService.getAbandonedGoals()).thenReturn(List.of(givenUp));
        when(goalPlannerService.countUnmetCriteria("gu")).thenReturn(1);
        stubSimilarity(0.9f);
        when(goalAdoptionService.findEquivalent(eq(givenUp), anyList(), any()))
                .thenReturn(Optional.of(new GoalAdoptionService.ReviewedMatch(completed, 0.88)));

        BehaviourLogger behaviourLogger = mock(BehaviourLogger.class);
        service.setBehaviourLogger(behaviourLogger);

        primeStreak();
        service.maybeConsolidate(ctx(CycleResultState.STAGNATING));

        verify(goalPlannerService).transitionGoal(eq("gu"), eq(GoalStatus.SUBSUMED),
                anyString(), eq(SubsumptionReason.COVERED));
        // The criterion must land on the COMPLETED goal: that is the evidence
        // reopenClosedButUnmetBlockers reads to reopen a completion its own bank contradicts.
        verify(goalPlannerService).setAcceptanceCriteria(eq("done"),
                argThat(cs -> cs.contains("Loan return marks the loan returned")));
        verify(behaviourLogger).goalConsolidation(anyString(), eq("done"), eq("gu"),
                eq(0.88), eq("given-up-covered"), eq(1));
    }

    @Test
    void givenUpWithoutUnmetCriteria_isNotACandidate() {
        GoalEntity givenUp = subgoal("gu", "Implement loan return handling in the service");
        givenUp.setStatus(GoalStatus.GIVEN_UP);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        lenient().when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());
        when(goalPlannerService.getAbandonedGoals()).thenReturn(List.of(givenUp));
        when(goalPlannerService.countUnmetCriteria("gu")).thenReturn(0);

        primeStreak();
        service.maybeConsolidate(ctx(CycleResultState.STAGNATING));

        verify(goalAdoptionService, never()).findEquivalent(any(), anyList(), any());
    }

    @Test
    void plannerThrowing_neverPropagates() {
        when(goalPlannerService.getRemainingGoals())
                .thenReturn(List.of(subgoal("a", "work"), subgoal("b", "work item")))
                .thenThrow(new IllegalStateException("boom"));
        stubSimilarity(0.9f);

        primeStreak();
        // third call trips the trigger; the pass itself then throws inside and is contained
        service.maybeConsolidate(ctx(CycleResultState.STAGNATING));
        // a fourth call must still work (no corrupted state)
        service.maybeConsolidate(ctx(CycleResultState.CONTINUE));
    }
}
