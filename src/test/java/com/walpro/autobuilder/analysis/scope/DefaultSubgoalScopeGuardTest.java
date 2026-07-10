package com.walpro.autobuilder.analysis.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.DerivedFacts;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.graph.scope.ScopeActionRegistrar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultSubgoalScopeGuardTest {

    @Mock private SubgoalScopeReviewer reviewer;
    @Mock private GoalPlannerService goalPlannerService;
    @Mock private ScopeActionRegistrar scopeActionRegistrar;

    private DefaultSubgoalScopeGuard guard;

    @BeforeEach
    void setUp() {
        guard = new DefaultSubgoalScopeGuard(reviewer, goalPlannerService, scopeActionRegistrar);
    }

    private static CycleContextData baseCtx() {
        return CycleContextData.builder()
                .cycleName("cycle-1")
                .signals(new LinkedHashMap<>())
                .remainingGoals(List.of())
                .completedGoals(List.of())
                .build();
    }

    private static ProjectDiffResult diffWithChanges() {
        return new ProjectDiffResult(
                null,
                Map.of("src/main/java/X.java", "class X {}"),
                Set.of()
        );
    }

    private static ProjectDiffResult diffNoChanges() {
        return new ProjectDiffResult(
                null,
                Map.of(),
                Set.of()
        );
    }

    @Test
    void guard_returnsNull_whenCtxNull() {
        assertThat(guard.guard(null)).isNull();
        verifyNoInteractions(reviewer, goalPlannerService);
    }

    @Test
    void guard_alwaysClearsScopeSignalsAndBestMatch_perCycle() {
        CycleContextData in = baseCtx()
                .withCurrentSubGoalId("") // triggers early return after clearing
                .withBestMatchSubGoalId("old-best")
                .withSignal(Signal.of(BasicSignals.SUBGOAL_SCOPE_MISMATCH, 1.0, 0.0, "old"))
                .withSignal(Signal.of(BasicSignals.SCOPE_OUT_OF_SCOPE, 1.0, 0.0, "old"));

        CycleContextData out = guard.guard(in);

        assertThat(DerivedFacts.subgoalScopeMismatch(out)).isFalse();
        assertThat(DerivedFacts.scopeOutOfScope(out)).isFalse();
        assertThat(out.bestMatchSubGoalId()).isNull();

        verifyNoInteractions(reviewer, goalPlannerService);
    }

    @Test
    void guard_noops_whenNoSubGoalId() {
        CycleContextData in = baseCtx()
                .withCurrentSubGoalId(null)
                .withProjectDiff(diffWithChanges())
                .withSignal(Signal.of(BasicSignals.EVOLUTION_OCCURRED, 1.0, 0.0, "test"));

        CycleContextData out = guard.guard(in);

        // It should clear (nothing to clear here) and return; no scope assessment.
        verifyNoInteractions(reviewer, goalPlannerService);
        assertThat(DerivedFacts.scopeOutOfScope(out)).isFalse();
        assertThat(DerivedFacts.subgoalScopeMismatch(out)).isFalse();
        assertThat(out.bestMatchSubGoalId()).isNull();
    }

    @Test
    void guard_noops_whenNoDiff() {
        CycleContextData in = baseCtx()
                .withCurrentSubGoalId("sub-1")
                .withProjectDiff(null)
                .withSignal(Signal.of(BasicSignals.EVOLUTION_OCCURRED, 1.0, 0.0, "test"));

        CycleContextData out = guard.guard(in);

        verifyNoInteractions(reviewer, goalPlannerService);
        assertThat(DerivedFacts.scopeOutOfScope(out)).isFalse();
        assertThat(DerivedFacts.subgoalScopeMismatch(out)).isFalse();
    }

    @Test
    void guard_noops_whenEvolutionDidNotOccur() {
        CycleContextData in = baseCtx()
                .withCurrentSubGoalId("sub-1")
                .withProjectDiff(diffWithChanges());

        CycleContextData out = guard.guard(in);

        verifyNoInteractions(reviewer, goalPlannerService);
        assertThat(DerivedFacts.scopeOutOfScope(out)).isFalse();
        assertThat(DerivedFacts.subgoalScopeMismatch(out)).isFalse();
    }

    @Test
    void guard_noops_whenDiffHasNoChanges() {
        CycleContextData in = baseCtx()
                .withCurrentSubGoalId("sub-1")
                .withProjectDiff(diffNoChanges())
                .withSignal(Signal.of(BasicSignals.EVOLUTION_OCCURRED, 1.0, 0.0, "test"));

        CycleContextData out = guard.guard(in);

        verifyNoInteractions(reviewer, goalPlannerService);
        assertThat(DerivedFacts.scopeOutOfScope(out)).isFalse();
        assertThat(DerivedFacts.subgoalScopeMismatch(out)).isFalse();
    }

    @Test
    void guard_failOpen_whenJudgeThrows() {
        CycleContextData in = baseCtx()
                .withCurrentSubGoalId("sub-1")
                .withProjectDiff(diffWithChanges())
                .withSignal(Signal.of(BasicSignals.EVOLUTION_OCCURRED, 1.0, 0.0, "test"));

        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(reviewer.assess(any(), any(), any())).thenThrow(new RuntimeException("boom"));

        CycleContextData out = guard.guard(in);

        assertThat(DerivedFacts.scopeOutOfScope(out)).isFalse();
        assertThat(DerivedFacts.subgoalScopeMismatch(out)).isFalse();
        assertThat(out.bestMatchSubGoalId()).isNull();

        verify(goalPlannerService).getRemainingGoals();
        verify(reviewer).assess(any(CycleContextData.class), any(), any());
    }

    @Test
    void guard_setsOutOfScope_andBestMatch_whenHighConfidenceAndUsableBestMatch() {
        CycleContextData in = baseCtx()
                .withCurrentSubGoalId("sub-1")
                .withProjectDiff(diffWithChanges())
                .withSignal(Signal.of(BasicSignals.EVOLUTION_OCCURRED, 1.0, 0.0, "test"));

        List<GoalEntity> remaining = List.of(mock(GoalEntity.class));
        List<GoalEntity> completed = List.of(mock(GoalEntity.class));
        when(goalPlannerService.getRemainingGoals()).thenReturn(remaining);
        when(goalPlannerService.getCompletedGoals()).thenReturn(completed);

        when(reviewer.assess(any(), any(), any())).thenReturn(
                new ScopeAssessment(ScopeFit.OUT_OF_SCOPE, 0.90, "wrong subgoal", "sub-2")
        );

        CycleContextData out = guard.guard(in);

        assertThat(DerivedFacts.scopeOutOfScope(out)).isTrue();
        assertThat(DerivedFacts.subgoalScopeMismatch(out)).isFalse();
        assertThat(out.getSignal(BasicSignals.REPLAN_REQUESTED)).isNull();
        assertThat(out.bestMatchSubGoalId()).isEqualTo("sub-2");

        ArgumentCaptor<List> remainingCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List> completedCaptor = ArgumentCaptor.forClass(List.class);
        verify(reviewer).assess(any(CycleContextData.class), remainingCaptor.capture(), completedCaptor.capture());
        assertThat(remainingCaptor.getValue()).isSameAs(remaining);
        assertThat(completedCaptor.getValue()).isSameAs(completed);

        verify(goalPlannerService).getRemainingGoals();
        verify(goalPlannerService).isOpenStructuralRepairLeaf("sub-1"); // T2-EM consult
        verifyNoMoreInteractions(goalPlannerService, reviewer);
    }

    @Test
    void guard_skipsSuppression_whenLeafIsOpenStructuralRepairTarget() {
        // T2-EM (run 20): the repair leaf spawned to provide POST /loans had its actuator
        // disarmed by the scope-mismatch suppression while the structural guard kept refusing
        // closure on the same absent endpoint — can't close, can't fix. While the leaf's named
        // gap is still open, an out-of-scope opinion must not emit the suppression signals.
        CycleContextData in = baseCtx()
                .withCurrentSubGoalId("repair-leaf")
                .withProjectDiff(diffWithChanges())
                .withSignal(Signal.of(BasicSignals.EVOLUTION_OCCURRED, 1.0, 0.0, "test"));

        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.isOpenStructuralRepairLeaf("repair-leaf")).thenReturn(true);
        when(reviewer.assess(any(), any(), any())).thenReturn(
                new ScopeAssessment(ScopeFit.OUT_OF_SCOPE, 0.95, "looks off-scope", null)
        );

        CycleContextData out = guard.guard(in);

        assertThat(DerivedFacts.subgoalScopeMismatch(out)).isFalse();
        assertThat(DerivedFacts.scopeOutOfScope(out)).isFalse();
        assertThat(out.getSignal(BasicSignals.REPLAN_REQUESTED)).isNull();
        assertThat(out.bestMatchSubGoalId()).isNull();
    }

    @Test
    void guard_stillSuppresses_whenRepairLeafGapAlreadyClosed() {
        // Exemption ends with the gap: once the endpoint is built (or the leaf is not a
        // structural work item at all), the ordinary suppression applies unchanged.
        CycleContextData in = baseCtx()
                .withCurrentSubGoalId("repair-leaf")
                .withProjectDiff(diffWithChanges())
                .withSignal(Signal.of(BasicSignals.EVOLUTION_OCCURRED, 1.0, 0.0, "test"));

        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.isOpenStructuralRepairLeaf("repair-leaf")).thenReturn(false);
        when(reviewer.assess(any(), any(), any())).thenReturn(
                new ScopeAssessment(ScopeFit.OUT_OF_SCOPE, 0.95, "wrong subgoal", null)
        );

        CycleContextData out = guard.guard(in);

        assertThat(DerivedFacts.subgoalScopeMismatch(out)).isTrue();
        assertThat(out.getSignal(BasicSignals.REPLAN_REQUESTED)).isNotNull();
    }

    @Test
    void guard_setsMismatchAndReplan_whenOutOfScopeButNoUsableBestMatch() {
        CycleContextData in = baseCtx()
                .withCurrentSubGoalId("sub-1")
                .withProjectDiff(diffWithChanges())
                .withSignal(Signal.of(BasicSignals.EVOLUTION_OCCURRED, 1.0, 0.0, "test"));

        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(reviewer.assess(any(), any(), any())).thenReturn(
                new ScopeAssessment(ScopeFit.OUT_OF_SCOPE, 0.90, "wrong subgoal", null)
        );

        CycleContextData out = guard.guard(in);

        assertThat(DerivedFacts.subgoalScopeMismatch(out)).isTrue();
        assertThat(out.getSignal(BasicSignals.REPLAN_REQUESTED)).isNotNull();
        assertThat(DerivedFacts.scopeOutOfScope(out)).isFalse();
        assertThat(out.bestMatchSubGoalId()).isNull();

        verify(goalPlannerService).getRemainingGoals();
        verify(reviewer).assess(any(CycleContextData.class), any(), any());
    }

    @Test
    void guard_noops_whenNotOutOfScope_orConfidenceBelowThreshold() {
        CycleContextData in = baseCtx()
                .withCurrentSubGoalId("sub-1")
                .withProjectDiff(diffWithChanges())
                .withSignal(Signal.of(BasicSignals.EVOLUTION_OCCURRED, 1.0, 0.0, "test"));

        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());

        // Not out of scope
        when(reviewer.assess(any(), any(), any())).thenReturn(
                new ScopeAssessment(ScopeFit.PARTIAL, 0.95, "uncertain", "sub-2")
        );
        CycleContextData out1 = guard.guard(in);
        assertThat(DerivedFacts.scopeOutOfScope(out1)).isFalse();
        assertThat(DerivedFacts.subgoalScopeMismatch(out1)).isFalse();

        // Out of scope but below threshold (0.80)
        when(reviewer.assess(any(), any(), any())).thenReturn(
                new ScopeAssessment(ScopeFit.OUT_OF_SCOPE, 0.79, "barely", "sub-2")
        );
        CycleContextData out2 = guard.guard(in);
        assertThat(DerivedFacts.scopeOutOfScope(out2)).isFalse();
        assertThat(DerivedFacts.subgoalScopeMismatch(out2)).isFalse();

        verify(goalPlannerService, times(2)).getRemainingGoals();
        verify(reviewer, times(2)).assess(any(CycleContextData.class), any(), any());
    }
}