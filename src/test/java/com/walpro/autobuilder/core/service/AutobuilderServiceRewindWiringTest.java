package com.walpro.autobuilder.core.service;

import com.walpro.autobuilder.core.checkpoint.CacheMode;
import com.walpro.autobuilder.core.checkpoint.RewindPolicy;
import com.walpro.autobuilder.core.checkpoint.RewindService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.RootConvergenceTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
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

    private static RewindService.Result performed() {
        return new RewindService.Result(true, "cycle-10", null, CacheMode.FAITHFUL,
                12, 0, 3, 1, 20, true, List.of());
    }
}
