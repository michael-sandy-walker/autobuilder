package com.walpro.autobuilder.core.service;

import com.walpro.autobuilder.core.checkpoint.CacheMode;
import com.walpro.autobuilder.core.checkpoint.RewindPolicy;
import com.walpro.autobuilder.core.checkpoint.RewindService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.CycleResultState;
import com.walpro.autobuilder.core.lifecycle.RootConvergenceTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4-DQ: the WIRING, which nothing covered until the field run made the gap obvious.
 *
 * <p>{@code RewindPolicy} and {@code RootConvergenceTracker.grinding()} each had their own tests
 * and both passed while the cycle tail consulted a condition 88% of runs never reach. Two correct
 * components joined by a wrong condition is exactly the shape unit tests miss, so the seam gets
 * its own.
 */
class AutobuilderServiceRewindWiringTest {

    private AutobuilderService service;
    private RootConvergenceTracker tracker;
    private RewindPolicy policy;
    private RewindService rewinds;

    @BeforeEach
    void setUp() {
        tracker = mock(RootConvergenceTracker.class);
        policy = mock(RewindPolicy.class);
        service = new AutobuilderService(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        // Set by name rather than by constructor position: the constructor takes sixteen
        // collaborators and counting positions is how a test ends up asserting about the wrong one.
        ReflectionTestUtils.setField(service, "rootConvergenceTracker", tracker);
        ReflectionTestUtils.setField(service, "rewindPolicy", policy);
        rewinds = mock(RewindService.class);
        service.setRewindService(rewinds);
    }

    // ---- the operator's rewind: the field defect this test class was widened for ----
    //
    // Four operator rewinds on 2026-09-03 each restored cleanly and were each undone within ten
    // milliseconds. The checkpoint's holders put systemPaused back to false; the cycle context —
    // not a holder — still said GOAL_FULFILLED; the next tick re-ran the fulfilled terminal. No
    // cycle ever ran from the restored state, and the run stayed at one checkpoint.

    @Test
    @DisplayName("an operator rewind settles the stale result, so the next tick runs a cycle rather than re-pausing")
    void anOperatorRewindDoesNotLeaveTheFulfilledResultBehind() {
        when(rewinds.rewindTo(eq("cycle-12"), any(), eq("operator"), anyString()))
                .thenReturn(performed("cycle-12"));
        ReflectionTestUtils.setField(service, "context",
                cycle("cycle-30", null).withCycleResultState(CycleResultState.GOAL_FULFILLED));
        ReflectionTestUtils.setField(service, "systemPaused", true);

        var result = service.rewindAsOperator("cycle-12", CacheMode.FAITHFUL);

        assertThat(result).isPresent();
        CycleContextData after = service.getContext().orElseThrow();
        assertThat(after.cycleResultState())
                .as("the result of the cycle that was backed out of must not be re-processed")
                .isEqualTo(CycleResultState.CONTINUE);
        assertThat(after.pausedReason()).isNull();
        assertThat(after.goalExhaustedReason()).isNull();
        assertThat(service.isPaused()).isFalse();
        assertThat(service.userPaused()).isFalse();
        verify(tracker).resyncAfterRewind();
        // the trace names the cycle the loop was AT, not the target twice
        verify(rewinds).rewindTo("cycle-12", CacheMode.FAITHFUL, "operator", "cycle-30");
    }

    @Test
    @DisplayName("a pause the person asked for survives their rewind — rewinding is not resuming")
    void anOperatorRewindKeepsAUserPause() {
        when(rewinds.rewindTo(anyString(), any(), anyString(), anyString()))
                .thenReturn(performed("cycle-12"));
        ReflectionTestUtils.setField(service, "context",
                cycle("cycle-30", "user-pause").withCycleResultState(CycleResultState.CONTINUE));
        ReflectionTestUtils.setField(service, "systemPaused", true);

        service.rewindAsOperator("cycle-12", CacheMode.FAITHFUL);

        assertThat(service.getContext().orElseThrow().pausedReason()).isEqualTo("user-pause");
        assertThat(service.isPaused()).isTrue();
        assertThat(service.userPaused()).isTrue();
    }

    @Test
    @DisplayName("a refused operator rewind changes nothing about the carried context")
    void aRefusedOperatorRewindLeavesTheContextAlone() {
        when(rewinds.rewindTo(anyString(), any(), anyString(), anyString()))
                .thenReturn(new RewindService.Result(false, "cycle-12", "no checkpoint for that cycle",
                        null, 0, 0, 0, 0, 0, false, List.of()));
        ReflectionTestUtils.setField(service, "context",
                cycle("cycle-30", null).withCycleResultState(CycleResultState.GOAL_FULFILLED));

        var result = service.rewindAsOperator("cycle-12", CacheMode.FAITHFUL);

        assertThat(result).isPresent();
        assertThat(result.get().performed()).isFalse();
        assertThat(service.getContext().orElseThrow().cycleResultState()).isEqualTo(CycleResultState.GOAL_FULFILLED);
        verify(tracker, never()).resyncAfterRewind();
    }

    @Test
    @DisplayName("the policy's rewind settles the result state too — the same stale-context gap, one home")
    void thePolicysRewindSettlesTheResultStateToo() {
        when(policy.considerRewindInsteadOfHalting(anyString(), anyInt()))
                .thenReturn(Optional.of(performed()));

        service.rewindBeforeHalting(
                cycle("cycle-30", "root-exhaustion").withCycleResultState(CycleResultState.GOAL_EXHAUSTED));

        assertThat(service.getContext().orElseThrow().cycleResultState())
                .as("left as GOAL_EXHAUSTED, the next tick raises the exhaustion halt again")
                .isEqualTo(CycleResultState.CONTINUE);
    }

    @Test
    @DisplayName("a grinding run is rewound mid-run, with no halt anywhere in sight")
    void aGrindingRunIsRewoundWithoutWaitingForTheHalt() {
        when(tracker.grinding()).thenReturn(true);
        when(policy.considerRewindInsteadOfHalting(anyString(), anyInt()))
                .thenReturn(Optional.of(performed()));

        assertThat(service.rewindIfGrinding(cycle("cycle-30", null)))
                .as("this is the case the field run exposed: no pausedReason, no brake halt, and "
                        + "the run still needs to back out")
                .isTrue();
        verify(tracker).resyncAfterRewind();
    }

    @Test
    @DisplayName("EVERY honest halt gets the question asked, not just the convergence brake's")
    void everyHonestHaltConsultsTheRewind() {
        when(policy.considerRewindInsteadOfHalting(anyString(), anyInt()))
                .thenReturn(Optional.of(performed()));

        // The four the ledger actually shows runs dying on, plus the brake's own. A forced run
        // died at cycle 4 on exhaustion-runaway with nobody asking — that is what widened this.
        for (String reason : List.of(RootConvergenceTracker.HALT_SOURCE, "exhaustion-runaway",
                "empty-backlog-injection-limit", "root-exhaustion", "planner-output-truncated")) {
            assertThat(service.rewindBeforeHalting(cycle("cycle-30", reason)))
                    .as("halt reason %s", reason)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("an operator pause and a breaker suspension are never overridden")
    void someHaltsAreNotOursToOverride() {
        when(policy.considerRewindInsteadOfHalting(anyString(), anyInt()))
                .thenReturn(Optional.of(performed()));

        assertThat(service.rewindBeforeHalting(cycle("cycle-30", "user-pause")))
                .as("a person asked the loop to stop; backing up to keep working overrides them")
                .isFalse();
        assertThat(service.rewindBeforeHalting(cycle("cycle-30", "llm-circuit-breaker")))
                .as("a suspension waiting on the endpoint says nothing about the path being bad")
                .isFalse();
        verify(policy, never()).considerRewindInsteadOfHalting(anyString(), anyInt());
    }

    @Test
    @DisplayName("a cycle with no halt reason at all is not treated as a halt")
    void noPauseReasonIsNotAHalt() {
        assertThat(service.rewindBeforeHalting(cycle("cycle-30", null))).isFalse();
        verify(policy, never()).considerRewindInsteadOfHalting(anyString(), anyInt());
    }

    @Test
    @DisplayName("a healthy run is never rewound, and the policy is not even consulted")
    void aHealthyRunIsLeftAlone() {
        when(tracker.grinding()).thenReturn(false);

        assertThat(service.rewindIfGrinding(cycle("cycle-30", null))).isFalse();
        verify(policy, never()).considerRewindInsteadOfHalting(anyString(), anyInt());
    }

    @Test
    @DisplayName("a rewind clears the halt this cycle raised, so the run continues")
    void aRewindClearsTheHaltAndTheRunContinues() {
        when(policy.considerRewindInsteadOfHalting(anyString(), anyInt()))
                .thenReturn(Optional.of(performed()));

        service.rewindBeforeHalting(cycle("cycle-30", "exhaustion-runaway"));

        CycleContextData after = service.getContext().orElseThrow();
        assertThat(after.pausedReason()).isNull();
        assertThat(after.goalExhaustedReason()).isNull();
    }

    @Test
    @DisplayName("a refused rewind lets the cycle finish normally rather than swallowing it")
    void arefusedRewindDoesNotEndTheCycle() {
        when(tracker.grinding()).thenReturn(true);
        when(policy.considerRewindInsteadOfHalting(anyString(), anyInt())).thenReturn(Optional.empty());

        assertThat(service.rewindIfGrinding(cycle("cycle-30", null)))
                .as("nothing to return to — the run carries on and halts as it always did")
                .isFalse();
        verify(tracker, never()).resyncAfterRewind();
    }

    @Test
    @DisplayName("without the policy wired, the tail behaves exactly as it did before T4-DQ")
    void withoutThePolicyNothingHappens() {
        ReflectionTestUtils.setField(service, "rewindPolicy", null);
        when(tracker.grinding()).thenReturn(true);

        assertThat(service.rewindIfGrinding(cycle("cycle-30", null))).isFalse();
    }

    private static CycleContextData cycle(String cycleId, String pausedReason) {
        return CycleContextData.builder()
                .cycleName(cycleId)
                .pausedReason(pausedReason)
                .build();
    }

    @Test
    @DisplayName("an adopted project with no goal waits, and a goal releases it")
    void anAdoptedProjectWaitsForAGoal() {
        // The first adopted run died at boot on "Parent goal not found: create-basic-structure".
        // With no current goal and no remaining goals the planner injects a fallback naming ids
        // that do not exist, and the first thing to treat one as a real parent throws. An adopted
        // project has no goal by design, so it must WAIT rather than cycle into that.
        ReflectionTestUtils.setField(service, "awaitingGoal", true);

        assertThat(service.awaitingGoal()).isTrue();

        service.goalProvided();

        assertThat(service.awaitingGoal())
                .as("the goal endpoint is what releases it; nothing else should")
                .isFalse();
    }

    @Test
    @DisplayName("waiting for a goal is not the same as being refused")
    void waitingIsNotRefusal() {
        ReflectionTestUtils.setField(service, "awaitingGoal", true);

        assertThat(service.held())
                .as("the start succeeded and the tree is adopted; only the purpose is missing")
                .isFalse();
    }

    private static RewindService.Result performed() {
        return performed("cycle-10");
    }

    private static RewindService.Result performed(String toCycleId) {
        return new RewindService.Result(true, toCycleId, null, CacheMode.FAITHFUL,
                12, 0, 3, 1, 20, true, List.of());
    }
}
