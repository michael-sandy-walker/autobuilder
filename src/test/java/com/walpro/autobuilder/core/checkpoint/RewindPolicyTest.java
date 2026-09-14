package com.walpro.autobuilder.core.checkpoint;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4-DQ slice 3: the bounds. A capability that lets a run go backwards has to be unable to spend
 * the run going backwards, and has to end exactly where it used to when there is nothing better
 * to return to.
 */
class RewindPolicyTest {

    private RewindService rewindService;
    private RewindPolicy policy;

    @BeforeEach
    void setUp() {
        rewindService = mock(RewindService.class);
        policy = new RewindPolicy(rewindService);
        ReflectionTestUtils.setField(policy, "enabled", true);
        ReflectionTestUtils.setField(policy, "maxRewinds", 2);
        ReflectionTestUtils.setField(policy, "cacheMode", CacheMode.FAITHFUL);
    }

    @Test
    @DisplayName("a grinding run returns to the last point that landed work")
    void grindingRunReturnsToTheLastMergedPoint() {
        when(rewindService.lastMergedBefore("cycle-50")).thenReturn(Optional.of(checkpoint("cycle-5", true)));
        when(rewindService.rewindTo(eq("cycle-5"), any(), anyString(), anyString()))
                .thenReturn(performed("cycle-5"));

        assertThat(policy.considerRewindInsteadOfHalting("cycle-50", 42)).isPresent();
        verify(rewindService).rewindTo("cycle-5", CacheMode.FAITHFUL, RewindPolicy.TRIGGER, "cycle-50");
        assertThat(policy.rewindsUsed()).isEqualTo(1);
    }

    @Test
    @DisplayName("with no earlier point that landed work, the run halts exactly as it used to")
    void noEligiblePointMeansHaltAsBefore() {
        when(rewindService.lastMergedBefore(anyString())).thenReturn(Optional.empty());

        assertThat(policy.considerRewindInsteadOfHalting("cycle-50", 42)).isEmpty();
        verify(rewindService, never()).rewindTo(anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("the budget is spent, not refunded — a run cannot rewind forever")
    void theBudgetIsFinite() {
        when(rewindService.lastMergedBefore(anyString())).thenReturn(Optional.of(checkpoint("cycle-5", true)));
        when(rewindService.rewindTo(anyString(), any(), anyString(), anyString()))
                .thenReturn(performed("cycle-5"));

        assertThat(policy.considerRewindInsteadOfHalting("cycle-50", 42)).isPresent();
        assertThat(policy.considerRewindInsteadOfHalting("cycle-70", 42)).isPresent();
        assertThat(policy.considerRewindInsteadOfHalting("cycle-90", 42))
                .as("the third grind explores no further; with no branch explorer wired there is "
                        + "nothing to settle onto either, so the run halts as it always did")
                .isEmpty();
    }

    @Test
    @DisplayName("the second rewind must target a point strictly older than the first")
    void eachRewindGoesFurtherBack() {
        when(rewindService.lastMergedBefore("cycle-50")).thenReturn(Optional.of(checkpoint("cycle-20", true)));
        when(rewindService.lastMergedBefore("cycle-20")).thenReturn(Optional.of(checkpoint("cycle-5", true)));
        when(rewindService.rewindTo(anyString(), any(), anyString(), anyString()))
                .thenReturn(performed("x"));

        policy.considerRewindInsteadOfHalting("cycle-50", 42);
        policy.considerRewindInsteadOfHalting("cycle-70", 42);

        // The second search starts from the first target, not from the current cycle, so the same
        // stretch is never re-walked from the same place.
        verify(rewindService).lastMergedBefore("cycle-20");
        verify(rewindService).rewindTo(eq("cycle-5"), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("a refused rewind spends no budget and falls through to the halt")
    void arefusedRewindFallsThrough() {
        when(rewindService.lastMergedBefore(anyString())).thenReturn(Optional.of(checkpoint("cycle-5", true)));
        when(rewindService.rewindTo(anyString(), any(), anyString(), anyString()))
                .thenReturn(refused());

        assertThat(policy.considerRewindInsteadOfHalting("cycle-50", 42)).isEmpty();
        assertThat(policy.rewindsUsed()).isZero();
    }

    @Test
    @DisplayName("with branching, the fork point is the newest UNTRIED one, not merely an older one")
    void branchingChoosesByWhatHasBeenTriedNotByPosition() {
        BranchExplorer explorer = mock(BranchExplorer.class);
        when(explorer.enabled()).thenReturn(true);
        policy.setBranchExplorer(explorer);
        // A point NEWER than the previous target is still untried — the positional rule would skip
        // it for no reason beyond its own arithmetic.
        when(explorer.nextUntriedForkPoint()).thenReturn(Optional.of(checkpoint("cycle-40", true)));
        when(rewindService.rewindTo(anyString(), any(), anyString(), anyString()))
                .thenReturn(performed("cycle-40"));

        assertThat(policy.considerRewindInsteadOfHalting("cycle-50", 42)).isPresent();

        verify(rewindService).rewindTo("cycle-40", CacheMode.FAITHFUL, RewindPolicy.TRIGGER, "cycle-50");
        verify(rewindService, never()).lastMergedBefore(anyString());
        verify(explorer).forkAt(any(), eq("cycle-50"), anyString());
    }

    @Test
    @DisplayName("when every point that landed work has been explored, the run halts")
    void exhaustedExplorationHalts() {
        BranchExplorer explorer = mock(BranchExplorer.class);
        when(explorer.enabled()).thenReturn(true);
        policy.setBranchExplorer(explorer);
        when(explorer.nextUntriedForkPoint()).thenReturn(Optional.empty());

        assertThat(policy.considerRewindInsteadOfHalting("cycle-50", 42)).isEmpty();
        verify(rewindService, never()).rewindTo(anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("when exploration runs out, the run is put on the best path it found — once")
    void exhaustedExplorationSettlesOnTheBestPath() {
        BranchExplorer explorer = mock(BranchExplorer.class);
        when(explorer.enabled()).thenReturn(true);
        when(explorer.nextUntriedForkPoint()).thenReturn(Optional.empty());
        when(explorer.adoptMostAdvanced(anyString())).thenReturn(Optional.of(performed("cycle-2")));
        policy.setBranchExplorer(explorer);

        assertThat(policy.considerRewindInsteadOfHalting("cycle-50", 42))
                .as("a deadline-killed run never reaches the halt, so this is its only chance to "
                        + "end on the best path rather than the last one tried")
                .isPresent();
        verify(explorer).sealCurrent("cycle-50");
        verify(explorer).adoptMostAdvanced("cycle-50");

        // One-shot: the "exploration is over" branch is reached on every later cycle too.
        assertThat(policy.considerRewindInsteadOfHalting("cycle-51", 43)).isEmpty();
        verify(explorer, org.mockito.Mockito.times(1)).adoptMostAdvanced(anyString());
    }

    @Test
    @DisplayName("switching branching off falls back to the positional rule, it does not stop rewinding")
    void disablingBranchingKeepsTheRewind() {
        BranchExplorer explorer = mock(BranchExplorer.class);
        when(explorer.enabled()).thenReturn(false);
        policy.setBranchExplorer(explorer);
        when(rewindService.lastMergedBefore(anyString())).thenReturn(Optional.of(checkpoint("cycle-5", true)));
        when(rewindService.rewindTo(anyString(), any(), anyString(), anyString()))
                .thenReturn(performed("cycle-5"));

        assertThat(policy.considerRewindInsteadOfHalting("cycle-50", 42))
                .as("the branch switch removes slice 4's bookkeeping, not slice 3's rewind")
                .isPresent();
        verify(explorer, never()).nextUntriedForkPoint();
        verify(explorer, never()).forkAt(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("the switch turns it off and the halt edge behaves as before")
    void theSwitchTurnsItOff() {
        ReflectionTestUtils.setField(policy, "enabled", false);

        assertThat(policy.considerRewindInsteadOfHalting("cycle-50", 42)).isEmpty();
        verify(rewindService, never()).lastMergedBefore(anyString());
    }

    private static CheckpointRecord checkpoint(String cycleId, boolean merged) {
        return new CheckpointRecord(cycleId, Instant.now(), "run-1", "lineage-1", "root", "s1",
                1L, "snapshots/" + cycleId, "mutations/" + cycleId + "/meta.json", merged, true,
                Map.of(), List.of(), null);
    }

    private static RewindService.Result performed(String toCycleId) {
        return new RewindService.Result(true, toCycleId, null, CacheMode.FAITHFUL,
                3, 0, 2, 1, 40, true, List.of(), null, "none");
    }

    private static RewindService.Result refused() {
        return new RewindService.Result(false, "cycle-5", "the checkpoint's tree snapshot has been cleared",
                null, 0, 0, 0, 0, 0, false, List.of(), null, "none");
    }
}
