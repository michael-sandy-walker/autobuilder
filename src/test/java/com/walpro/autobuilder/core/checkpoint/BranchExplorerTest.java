package com.walpro.autobuilder.core.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.init.RunIdentityService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.persistence.MutationSnapshotService;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalTransitionLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4-DQ slice 4: exploring more than one path from the same point, and choosing between them on
 * what they achieved.
 *
 * <p>The interesting assertions here are the two that stop this being a toy: a fork point is never
 * re-used, so exploration terminates rather than looping; and the run ends on the path that got
 * FURTHEST rather than the one it was last on, which is the whole reason branching is worth having.
 */
class BranchExplorerTest {

    @TempDir
    Path tmp;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private FileService fileService;
    private CheckpointService checkpoints;
    private BranchLedger ledger;
    private BranchExplorer explorer;
    private RewindService rewindService;
    private GoalPlannerService planner;

    @BeforeEach
    void setUp() {
        fileService = new FileService(tmp.toString(), mapper);
        planner = mock(GoalPlannerService.class);
        when(planner.getAllGoals()).thenReturn(java.util.List.of());
        StateManifest manifest = new StateManifest(new DefaultListableBeanFactory());
        StateFingerprintService fingerprints =
                new StateFingerprintService(fileService, mapper, planner, manifest);
        RunIdentityService identity = mock(RunIdentityService.class);
        when(identity.runId()).thenReturn("run-1");
        when(identity.lineageId()).thenReturn("lineage-1");
        checkpoints = new CheckpointService(fileService, mapper, manifest,
                new GoalTransitionLog(fileService, mapper), identity, fingerprints);
        ReflectionTestUtils.setField(checkpoints, "enabled", true);
        ReflectionTestUtils.setField(checkpoints, "retainCheckpoints", 40);
        ReflectionTestUtils.setField(checkpoints, "captureCache", false);
        ledger = new BranchLedger(fileService, mapper);
        rewindService = mock(RewindService.class);
        explorer = new BranchExplorer(ledger, checkpoints, rewindService, planner);
    }

    @Test
    @DisplayName("a fork seals the path being left and opens one from the checkpoint")
    void forkingSealsTheOldPathAndOpensANewOne() throws IOException {
        checkpoint("cycle-1", true);
        checkpoint("cycle-2", false);
        when(planner.countCompletedSubgoalsWithMerges()).thenReturn(2);

        explorer.forkAt(checkpoints.read("cycle-1").orElseThrow(), "cycle-2", "grinding");

        assertThat(ledger.branches()).hasSize(2);
        assertThat(ledger.sealedBranches()).extracting(ExplorationBranch::id)
                .containsExactly(ExplorationBranch.MAIN);
        assertThat(ledger.openBranch()).get().extracting(ExplorationBranch::forkedFromCycleId)
                .isEqualTo("cycle-1");
        // The original path is measured too — a comparison that could not see it would only ever
        // be able to choose between detours.
        assertThat(ledger.sealedBranches().get(0).outcome().subgoalsCompletedWithMerge()).isEqualTo(2);
    }

    @Test
    @DisplayName("a point that has been forked from is never offered again, so exploration ends")
    void forkPointsAreNeverReused() throws IOException {
        checkpoint("cycle-1", true);
        checkpoint("cycle-2", true);
        checkpoint("cycle-3", false);

        Optional<CheckpointRecord> first = explorer.nextUntriedForkPoint();
        assertThat(first).get().extracting(CheckpointRecord::cycleId).isEqualTo("cycle-2");
        explorer.forkAt(first.orElseThrow(), "cycle-3", "first attempt");

        Optional<CheckpointRecord> second = explorer.nextUntriedForkPoint();
        assertThat(second).get().extracting(CheckpointRecord::cycleId).isEqualTo("cycle-1");
        explorer.forkAt(second.orElseThrow(), "cycle-3", "second attempt");

        assertThat(explorer.nextUntriedForkPoint())
                .as("every point that landed work has been tried — the honest end of exploration")
                .isEmpty();
    }

    @Test
    @DisplayName("only checkpoints that landed work are offered as fork points")
    void unmergedCheckpointsAreNotForkPoints() throws IOException {
        checkpoint("cycle-1", false);
        checkpoint("cycle-2", false);

        assertThat(explorer.nextUntriedForkPoint()).isEmpty();
    }

    @Test
    @DisplayName("the run ends on the path that got furthest, not the one it was last on")
    void theMostAdvancedBranchIsAdopted() throws IOException {
        checkpoint("cycle-1", true);
        checkpoint("cycle-2", true);
        // The original path reaches three completed-with-merge subgoals.
        when(planner.countCompletedSubgoalsWithMerges()).thenReturn(3);
        explorer.forkAt(checkpoints.read("cycle-1").orElseThrow(), "cycle-2", "grinding");
        // The detour reaches one, and is where the run happens to end.
        when(planner.countCompletedSubgoalsWithMerges()).thenReturn(1);
        checkpoint("cycle-3", true);
        explorer.sealCurrent("cycle-3");

        when(rewindService.rewindTo(anyString(), any(), anyString(), anyString()))
                .thenReturn(performed());

        Optional<ExplorationBranch> adopted = explorer.adoptMostAdvanced("cycle-3");

        assertThat(adopted).get().extracting(ExplorationBranch::id).isEqualTo(ExplorationBranch.MAIN);
        verify(rewindService).rewindTo("cycle-2", CacheMode.FAITHFUL, "branch-adoption", "cycle-3");
    }

    @Test
    @DisplayName("nothing is adopted when the run is already on the path that got furthest")
    void nothingIsAdoptedWhenAlreadyOnTheBest() throws IOException {
        checkpoint("cycle-1", true);
        when(planner.countCompletedSubgoalsWithMerges()).thenReturn(1);
        explorer.forkAt(checkpoints.read("cycle-1").orElseThrow(), "cycle-1", "grinding");
        checkpoint("cycle-2", true);
        when(planner.countCompletedSubgoalsWithMerges()).thenReturn(5);
        explorer.sealCurrent("cycle-2");

        assertThat(explorer.adoptMostAdvanced("cycle-2")).isEmpty();
        verify(rewindService, org.mockito.Mockito.never())
                .rewindTo(anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("root progress outranks merge count, and merge rate is not ranked on at all")
    void rankingPrefersRootProgressOverActivity() throws IOException {
        // A short path that closed real work, against a long one that merged more and closed less.
        checkpoint("cycle-1", true);
        when(planner.countCompletedSubgoalsWithMerges()).thenReturn(4);
        explorer.forkAt(checkpoints.read("cycle-1").orElseThrow(), "cycle-1", "detour");
        for (String c : java.util.List.of("cycle-2", "cycle-3", "cycle-4", "cycle-5")) {
            checkpoint(c, true);
        }
        when(planner.countCompletedSubgoalsWithMerges()).thenReturn(1);
        explorer.sealCurrent("cycle-5");

        ExplorationBranch best = explorer.mostAdvancedSealedBranch().orElseThrow();

        assertThat(best.id()).isEqualTo(ExplorationBranch.MAIN);
        assertThat(best.outcome().subgoalsCompletedWithMerge()).isEqualTo(4);
        assertThat(best.outcome().merges())
                .as("the branch that got further landed FEWER merges — activity is not progress")
                .isLessThan(4);
    }

    @Test
    @DisplayName("the ledger survives a fresh reader over the same file")
    void theLedgerIsDurable() throws IOException {
        checkpoint("cycle-1", true);
        when(planner.countCompletedSubgoalsWithMerges()).thenReturn(2);
        explorer.forkAt(checkpoints.read("cycle-1").orElseThrow(), "cycle-1", "grinding");

        BranchLedger reopened = new BranchLedger(new FileService(tmp.toString(), mapper), mapper);
        assertThat(reopened.branches()).hasSize(2);
        assertThat(reopened.alreadyForkedFrom("cycle-1")).isTrue();
    }

    private void checkpoint(String cycleId, boolean merged) throws IOException {
        Path snapshot = tmp.resolve("snapshots/" + cycleId);
        Files.createDirectories(snapshot.resolve("src"));
        Files.writeString(snapshot.resolve("src/A.java"), "class A {}");
        fileService.writeJson("snapshots/" + cycleId + "/" + MutationSnapshotService.SNAPSHOT_STATE_FILE,
                new MutationSnapshotService.SnapshotState(cycleId, true, "COMPILE_OK", merged, Instant.now()));
        checkpoints.write(CycleContextData.builder()
                .cycleName(cycleId)
                .snapshotPath("snapshots/" + cycleId)
                .merged(merged)
                .currentGoalId("root")
                .currentSubGoalId("s1")
                .build());
    }

    private static RewindService.Result performed() {
        return new RewindService.Result(true, "cycle-2", null, CacheMode.FAITHFUL,
                3, 0, 2, 1, 4, true, java.util.List.of());
    }
}
