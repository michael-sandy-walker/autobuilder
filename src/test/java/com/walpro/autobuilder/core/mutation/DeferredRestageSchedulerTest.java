package com.walpro.autobuilder.core.mutation;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.graph.mutation.DeferredMutationGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeferredRestageSchedulerTest {

    private FakeGraph graph;
    private DeferredMutationLedger ledger;
    private GoalPlannerService planner;
    private DeferredMutationRestager restager;
    private FileService fileService;
    private DeferredRestageScheduler scheduler;

    @BeforeEach
    void setUp() {
        graph = new FakeGraph();
        ledger = new DeferredMutationLedger(graph);
        planner = Mockito.mock(GoalPlannerService.class);
        restager = Mockito.mock(DeferredMutationRestager.class);
        fileService = Mockito.mock(FileService.class);
        scheduler = new DeferredRestageScheduler(ledger, restager, planner, fileService);
        // Default config matches @Value defaults
        ReflectionTestUtils.setField(scheduler, "refocusThreshold", 0.6);
        ReflectionTestUtils.setField(scheduler, "refocusCooldownCycles", 3);
        ReflectionTestUtils.setField(scheduler, "ttlCycles", 50);
        ReflectionTestUtils.setField(scheduler, "maxOpen", 20);
    }

    @Test
    void inScopeCandidate_isStagedDirectly() {
        DeferredMutation ready = deferReady("sub-1", "goal-root", 0.8, 0.8);
        when(planner.getCurrentSubGoalId()).thenReturn("sub-1");
        when(planner.isInActiveScope("sub-1")).thenReturn(true);
        when(restager.attemptRestage(any(), any())).thenReturn(
                DeferredMutationRestager.RestageResult.staged(baseContext("cycle-X", "goal-root", "sub-1")));

        CycleContextData result = scheduler.scheduleIfReady(baseContext("cycle-X", "goal-root", "sub-1"));

        assertThat(result).isNotNull();
        verify(restager, times(1)).attemptRestage(any(), any());
        verify(planner, never()).focusOnSubGoal(any());  // already in scope — no shift
    }

    @Test
    void outOfScopeCandidate_belowThreshold_notPromoted() {
        // candidate combined score 0.45 (< 0.6) → no re-focus
        deferReady("sub-A", "goal-root", 0.6, 0.7);  // combined = 0.42
        when(planner.getCurrentSubGoalId()).thenReturn("sub-B");
        when(planner.isInActiveScope("sub-A")).thenReturn(true);

        CycleContextData ctx = baseContext("cycle-X", "goal-root", "sub-B");
        CycleContextData result = scheduler.scheduleIfReady(ctx);

        assertThat(result).isSameAs(ctx);
        verify(planner, never()).focusOnSubGoal(any());
        verify(restager, never()).attemptRestage(any(), any());
    }

    @Test
    void outOfScopeCandidate_aboveThreshold_promotedAndStaged() {
        DeferredMutation candidate = deferReady("sub-A", "goal-root", 0.9, 0.9);  // combined = 0.81
        when(planner.getCurrentSubGoalId()).thenReturn("sub-B");
        when(planner.isInActiveScope("sub-A")).thenReturn(true);
        when(planner.focusOnSubGoal("sub-A")).thenReturn("sub-B");
        when(restager.attemptRestage(any(), any())).thenReturn(
                DeferredMutationRestager.RestageResult.staged(baseContext("cycle-X", "goal-root", "sub-A")));

        CycleContextData ctx = baseContext("cycle-X", "goal-root", "sub-B");
        CycleContextData result = scheduler.scheduleIfReady(ctx);

        assertThat(result).isNotNull();
        verify(planner, times(1)).focusOnSubGoal("sub-A");
        verify(restager, times(1)).attemptRestage(any(), any());
    }

    @Test
    void refocusCooldown_blocksSecondPromotionWithinWindow() throws Exception {
        // First promotion fires
        DeferredMutation first = deferReady("sub-A", "goal-root", 0.9, 0.9);
        when(planner.getCurrentSubGoalId()).thenReturn("sub-B");
        when(planner.isInActiveScope("sub-A")).thenReturn(true);
        when(planner.focusOnSubGoal("sub-A")).thenReturn("sub-B");
        when(restager.attemptRestage(any(), any())).thenReturn(
                DeferredMutationRestager.RestageResult.staged(baseContext("cycle-1", "goal-root", "sub-A")));
        scheduler.scheduleIfReady(baseContext("cycle-1", "goal-root", "sub-B"));
        verify(planner, times(1)).focusOnSubGoal("sub-A");

        // Defer a second candidate; scheduler runs cycle-2 (within cooldown=3)
        DeferredMutation second = deferReady("sub-C", "goal-root", 0.95, 0.95);
        when(planner.getCurrentSubGoalId()).thenReturn("sub-B");
        when(planner.isInActiveScope("sub-C")).thenReturn(true);
        when(planner.isInActiveScope("sub-A")).thenReturn(true);
        // restager mock still set from before — but we expect it NOT to fire again

        Mockito.clearInvocations(planner, restager);
        scheduler.scheduleIfReady(baseContext("cycle-2", "goal-root", "sub-B"));

        verify(planner, never()).focusOnSubGoal(any());
        verify(restager, never()).attemptRestage(any(), any());
    }

    @Test
    void suppressionSignal_blocksRestage() {
        DeferredMutation candidate = deferReady("sub-A", "goal-root", 0.9, 0.9);
        when(planner.getCurrentSubGoalId()).thenReturn("sub-A");
        when(planner.isInActiveScope("sub-A")).thenReturn(true);

        CycleContextData ctx = baseContext("cycle-X", "goal-root", "sub-A")
                .withSignal(Signal.of(BasicSignals.RESTAGE_SUPPRESS, 1.0, 0.1, "test"));

        scheduler.scheduleIfReady(ctx);

        verify(restager, never()).attemptRestage(any(), any());
    }

    @Test
    void intentClosed_candidateDropped() {
        DeferredMutation candidate = deferReady("sub-A", "goal-root", 0.9, 0.9);
        when(planner.getCurrentSubGoalId()).thenReturn("sub-B");
        when(planner.isInActiveScope("sub-A")).thenReturn(false);  // intent goal closed

        scheduler.scheduleIfReady(baseContext("cycle-X", "goal-root", "sub-B"));

        verify(restager, never()).attemptRestage(any(), any());
        assertThat(graph.findById(candidate.id()).state())
                .isEqualTo(DeferredMutationState.DROPPED_INTENT_CLOSED);
    }

    @Test
    void noReadyCandidates_returnsContextUnchanged() {
        // Defer but don't clear blockers, so nothing is READY
        deferOnly("sub-A", "goal-root", 0.9, 0.9);
        when(planner.getCurrentSubGoalId()).thenReturn("sub-A");

        CycleContextData ctx = baseContext("cycle-X", "goal-root", "sub-A");
        CycleContextData result = scheduler.scheduleIfReady(ctx);

        assertThat(result).isSameAs(ctx);
        verify(restager, never()).attemptRestage(any(), any());
    }

    @Test
    void ttlEviction_dropsLongPendingCandidates() {
        ledger.__resetCycleClockForTest(0);
        DeferredMutation old = deferOnly("sub-A", "goal-root", 0.5, 0.5);
        // Advance the cycle clock past TTL (default 50) and run scheduler
        for (int i = 0; i < 52; i++) ledger.tick();
        when(planner.getCurrentSubGoalId()).thenReturn("sub-A");
        when(planner.isInActiveScope(any())).thenReturn(true);

        scheduler.scheduleIfReady(baseContext("cycle-X", "goal-root", "sub-A"));

        assertThat(graph.findById(old.id()).state()).isEqualTo(DeferredMutationState.EVICTED);
    }

    @Test
    void capEviction_dropsOldestWhenOverLimit() {
        ReflectionTestUtils.setField(scheduler, "maxOpen", 2);
        when(planner.getCurrentSubGoalId()).thenReturn("sub-X");
        when(planner.isInActiveScope(any())).thenReturn(true);

        DeferredMutation oldest = deferOnly("sub-A", "goal-root", 0.5, 0.5);
        ledger.tick();
        DeferredMutation middle = deferOnly("sub-B", "goal-root", 0.5, 0.5);
        ledger.tick();
        DeferredMutation newest = deferOnly("sub-C", "goal-root", 0.5, 0.5);
        ledger.tick();
        DeferredMutation tooMany = deferOnly("sub-D", "goal-root", 0.5, 0.5);

        scheduler.scheduleIfReady(baseContext("cycle-X", "goal-root", "sub-X"));

        // Should keep 2 newest, evict 2 oldest.
        assertThat(graph.findById(oldest.id()).state()).isEqualTo(DeferredMutationState.EVICTED);
        assertThat(graph.findById(middle.id()).state()).isEqualTo(DeferredMutationState.EVICTED);
        // sub-C and sub-D remain — exact partition depends on enforce order; both survive.
        assertThat(graph.findById(newest.id()).state().isOpen()).isTrue();
        assertThat(graph.findById(tooMany.id()).state().isOpen()).isTrue();
    }

    // ---- helpers ----

    /** Defer + immediately clear so the record is READY_TO_RESTAGE. */
    private DeferredMutation deferReady(String intentSubgoalId, String intentGoalId,
                                         Double llmScore, Double heurScore) {
        DeferredMutation d = deferOnly(intentSubgoalId, intentGoalId, llmScore, heurScore);
        Set<String> fps = new HashSet<>();
        for (MutationBlocker b : d.blockers()) fps.add(b.fingerprint());
        ledger.recordBlockerClearance("cycle-clear", fps);
        return graph.findById(d.id());
    }

    private DeferredMutation deferOnly(String intentSubgoalId, String intentGoalId,
                                        Double llmScore, Double heurScore) {
        CycleContextData ctx = baseContext("cycle-defer-" + System.nanoTime(),
                intentGoalId, intentSubgoalId);
        return ledger.defer(ctx,
                List.of(new FileMutation("path-" + System.nanoTime() + ".java", "x")),
                List.of(MutationBlocker.missingType("T" + System.nanoTime())),
                llmScore, heurScore);
    }

    private CycleContextData baseContext(String cycleName, String goalId, String subId) {
        return CycleContextData.builder()
                .cycleName(cycleName)
                .currentGoalId(goalId)
                .currentSubGoalId(subId)
                .lastAttemptId("attempt-" + cycleName)
                .outputPath("output")
                .build();
    }

    private static class FakeGraph implements DeferredMutationGraphService {
        private final Map<String, DeferredMutation> store = new LinkedHashMap<>();
        @Override public void persist(DeferredMutation d) { store.put(d.id(), d); }
        @Override public void updateState(String id, DeferredMutationState s) {
            DeferredMutation cur = store.get(id);
            if (cur != null) store.put(id, cur.withState(s));
        }
        @Override public Set<String> dropBlockersAndFindCleared(Set<String> fps) {
            Set<String> cleared = new HashSet<>();
            for (DeferredMutation d : store.values()) {
                if (!d.state().isOpen()) continue;
                List<MutationBlocker> remaining = new ArrayList<>();
                for (MutationBlocker b : d.blockers()) if (!fps.contains(b.fingerprint())) remaining.add(b);
                if (remaining.size() != d.blockers().size()) {
                    DeferredMutation u = new DeferredMutation(
                            d.id(), d.attemptId(), d.cycleId(),
                            d.intentSubgoalId(), d.intentGoalId(),
                            d.llmScore(), d.heuristicScore(),
                            d.deferredAtCycleIndex(), d.deferredAt(),
                            d.mutationDir(), d.state(),
                            d.touchedFiles(), remaining);
                    store.put(d.id(), u);
                    if (remaining.isEmpty() && u.state() == DeferredMutationState.DEFERRED) {
                        cleared.add(d.id());
                    }
                }
            }
            return cleared;
        }
        @Override public List<DeferredMutation> findOpen() {
            List<DeferredMutation> out = new ArrayList<>();
            for (DeferredMutation d : store.values()) if (d.state().isOpen()) out.add(d);
            return out;
        }
        @Override public List<DeferredMutation> findReady() {
            List<DeferredMutation> out = new ArrayList<>();
            for (DeferredMutation d : store.values())
                if (d.state() == DeferredMutationState.READY_TO_RESTAGE) out.add(d);
            return out;
        }
        @Override public List<DeferredMutation> findByIntentGoal(String g) {
            List<DeferredMutation> out = new ArrayList<>();
            for (DeferredMutation d : store.values())
                if (d.state().isOpen() && g.equals(d.intentGoalId())) out.add(d);
            return out;
        }
        @Override public DeferredMutation findById(String id) { return store.get(id); }
    }
}
