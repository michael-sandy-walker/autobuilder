package com.walpro.autobuilder.core.checkpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.init.RunIdentityService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.persistence.MutationSnapshotService;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.GoalTransition;
import com.walpro.autobuilder.goal.GoalTransitionLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4-DQ: the four things a rewind moves — the tree, the goal state, the cache, and the
 * decision-bearing holders — plus the refusals, because a restore that is subtly wrong is worse
 * than no restore.
 */
class RewindServiceTest {

    @TempDir
    Path tmp;

    private FileService fileService;
    private ObjectMapper mapper;
    private CheckpointService checkpoints;
    private RewindService rewind;
    private GoalTransitionLog log;
    private GoalPlannerService planner;
    private CheckpointServiceTest.CountingHolder holder;

    @BeforeEach
    void setUp() throws IOException {
        mapper = new ObjectMapper().findAndRegisterModules();
        fileService = new FileService(tmp.toString(), mapper);
        holder = new CheckpointServiceTest.CountingHolder();
        StateManifest manifest = new StateManifest(factoryOf(holder));
        log = new GoalTransitionLog(fileService, mapper);
        RunIdentityService identity = mock(RunIdentityService.class);
        when(identity.runId()).thenReturn("run-1");
        when(identity.lineageId()).thenReturn("lineage-1");
        planner = mock(GoalPlannerService.class);
        when(planner.getAllGoals()).thenReturn(java.util.List.of());
        // The checkpoint records the PLANNER's pointer now, not the cycle context's — one fact,
        // one source (a mismatch between the two was the first field divergence).
        when(planner.getCurrentSubGoalId()).thenReturn("s1");
        StateFingerprintService fingerprints =
                new StateFingerprintService(fileService, mapper, planner, manifest, diffConfig());
        checkpoints = new CheckpointService(fileService, mapper, manifest, log, identity, fingerprints, planner, archiveFor(fileService));
        ReflectionTestUtils.setField(checkpoints, "enabled", true);
        ReflectionTestUtils.setField(checkpoints, "retainCheckpoints", 40);
        ReflectionTestUtils.setField(checkpoints, "captureCache", true);
        when(planner.restoreGoalStateAsOf(any(), any(), any(), any()))
                .thenReturn(new GoalPlannerService.RewindResult(2, 1, "s1"));
        rewind = new RewindService(fileService, mapper, checkpoints, manifest, log, planner, fingerprints, identity);
    }

    @Test
    @DisplayName("the tree comes back as it was, and later files are gone")
    void treeIsRestoredAndLaterWorkDisappears() throws IOException {
        writeSnapshot("cycle-1", Map.of("src/A.java", "class A {}"));
        checkpoints.write(context("cycle-1"));
        Files.createDirectories(tmp.resolve("output/src"));
        Files.writeString(tmp.resolve("output/src/B.java"), "class B {}");

        RewindService.Result result = rewind.rewindTo("cycle-1", CacheMode.FRESH, "test", "cycle-9");

        assertThat(result.performed()).isTrue();
        assertThat(tmp.resolve("output/src/A.java")).exists();
        assertThat(tmp.resolve("output/src/B.java")).doesNotExist();
    }

    @Test
    @DisplayName("after a rewind the loop's position is the target, not the newest point on disk")
    void positionFollowsTheRewind() throws IOException {
        writeSnapshot("cycle-1", Map.of("src/A.java", "class A {}"));
        writeSnapshot("cycle-2", Map.of("src/A.java", "class A {}", "src/B.java", "class B {}"));
        checkpoints.write(context("cycle-1"));
        checkpoints.write(context("cycle-2"));
        assertThat(checkpoints.position()).get()
                .as("writing a point advances the position to it")
                .extracting(CheckpointService.Position::cycleId, CheckpointService.Position::rewound)
                .containsExactly("cycle-2", false);

        rewind.rewindTo("cycle-1", CacheMode.FRESH, "test", "cycle-2");

        CheckpointService.Position position = checkpoints.position().orElseThrow();
        assertThat(position.cycleId()).isEqualTo("cycle-1");
        assertThat(position.rewound()).isTrue();
        assertThat(position.fromCycleId()).isEqualTo("cycle-2");
        assertThat(rewind.available().get(0).cycleId())
                .as("the listing still names cycle-2 newest — the position is what tells them apart")
                .isEqualTo("cycle-2");
    }

    @Test
    @DisplayName("the snapshot's own state stamp is not planted into the restored tree")
    void snapshotStampIsNotRestoredAsSource() throws IOException {
        writeSnapshot("cycle-1", Map.of("src/A.java", "class A {}"));
        Files.writeString(tmp.resolve("snapshots/cycle-1/" + MutationSnapshotService.SNAPSHOT_STATE_FILE), "{}");
        checkpoints.write(context("cycle-1"));

        rewind.rewindTo("cycle-1", CacheMode.FRESH, "test", "cycle-9");

        assertThat(tmp.resolve("output/" + MutationSnapshotService.SNAPSHOT_STATE_FILE)).doesNotExist();
    }

    @Test
    @DisplayName("decision-bearing holder state comes back with the tree")
    void holderStateIsRestored() throws IOException {
        writeSnapshot("cycle-1", Map.of("src/A.java", "class A {}"));
        holder.value = 5;
        checkpoints.write(context("cycle-1"));
        holder.value = 99;

        RewindService.Result result = rewind.rewindTo("cycle-1", CacheMode.FRESH, "test", "cycle-9");

        assertThat(holder.value).isEqualTo(5);
        assertThat(result.holdersRestored()).isEqualTo(1);
    }

    @Test
    @DisplayName("the goal fold is taken at the checkpoint's cut point, not at the latest sequence")
    void goalFoldUsesTheCheckpointCutPoint() throws IOException {
        log.append("g1", null, "ACTIVE", GoalTransition.Kind.CREATED, "cycle-1", null);
        writeSnapshot("cycle-1", Map.of("src/A.java", "class A {}"));
        checkpoints.write(context("cycle-1"));
        log.append("g1", "ACTIVE", "COMPLETED", GoalTransition.Kind.CHANGED, "cycle-2", "done");
        log.append("g2", null, "ACTIVE", GoalTransition.Kind.CREATED, "cycle-2", null);

        rewind.rewindTo("cycle-1", CacheMode.FRESH, "test", "cycle-9");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, GoalStatus>> statuses = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> existing = ArgumentCaptor.forClass(Set.class);
        verify(planner).restoreGoalStateAsOf(statuses.capture(), existing.capture(), anyString(),
                any());

        assertThat(statuses.getValue()).containsEntry("g1", GoalStatus.ACTIVE);
        assertThat(existing.getValue()).containsExactly("g1");
    }

    @Test
    @DisplayName("FAITHFUL brings the cache back; FRESH clears it")
    void cacheModesDiffer() throws IOException {
        Files.createDirectories(tmp.resolve("cache"));
        Files.writeString(tmp.resolve("cache/imports.json"), "{\"a\":1}");
        writeSnapshot("cycle-1", Map.of("src/A.java", "class A {}"));
        checkpoints.write(context("cycle-1"));
        Files.writeString(tmp.resolve("cache/imports.json"), "{\"a\":2}");

        rewind.rewindTo("cycle-1", CacheMode.FAITHFUL, "test", "cycle-9");
        assertThat(Files.readString(tmp.resolve("cache/imports.json"))).isEqualTo("{\"a\":1}");

        rewind.rewindTo("cycle-1", CacheMode.FRESH, "test", "cycle-9");
        assertThat(tmp.resolve("cache/imports.json")).doesNotExist();
    }

    @Test
    @DisplayName("when the run's own snapshot is gone, the archived copy still restores it")
    void theArchivedCopyOutlivesTheSnapshot() throws IOException {
        // This is what makes a point reachable after the run that took it has ended: snapshots/ is
        // cleared at the run boundary by design, and the source-only archive is not.
        writeSnapshot("cycle-1", Map.of("src/A.java", "class A {}"));
        checkpoints.write(context("cycle-1"));
        fileService.deleteRecursively("snapshots/cycle-1");

        RewindService.Result result = rewind.rewindTo("cycle-1", CacheMode.FRESH, "test", "cycle-9");

        assertThat(result.performed()).isTrue();
        assertThat(tmp.resolve("output/src/A.java")).exists();
    }

    @Test
    @DisplayName("with neither a snapshot nor an archived copy, the rewind is refused")
    void aPointWithNoTreeAtAllIsRefused() throws IOException {
        writeSnapshot("cycle-1", Map.of("src/A.java", "class A {}"));
        checkpoints.write(context("cycle-1"));
        fileService.deleteRecursively("snapshots/cycle-1");
        fileService.deleteRecursively(CheckpointArchive.ARCHIVE_DIR + "/cycle-1");

        RewindService.Result result = rewind.rewindTo("cycle-1", CacheMode.FRESH, "test", "cycle-9");

        assertThat(result.performed()).isFalse();
        assertThat(result.refusedBecause()).contains("archived copy");
        verify(planner, never()).restoreGoalStateAsOf(any(), any(), any(), any());
    }

    @Test
    @DisplayName("build output is left out of the archive — it regenerates and it is the bulk")
    void theArchiveKeepsSourceNotBuildOutput() throws IOException {
        writeSnapshot("cycle-1", Map.of(
                "src/A.java", "class A {}",
                "target/classes/A.class", "binary-ish",
                "target/maven-status/x.lst", "listing"));
        checkpoints.write(context("cycle-1"));

        assertThat(tmp.resolve(CheckpointArchive.ARCHIVE_DIR + "/cycle-1/src/A.java")).exists();
        assertThat(tmp.resolve(CheckpointArchive.ARCHIVE_DIR + "/cycle-1/target")).doesNotExist();
    }

    @Test
    @DisplayName("a checkpoint whose snapshot holds no files leaves the live tree standing")
    void anEmptySnapshotDoesNotDestroyTheLiveTree() throws IOException {
        writeSnapshot("cycle-1", Map.of("src/A.java", "class A {}"));
        checkpoints.write(context("cycle-1"));
        // The snapshot loses its contents after the checkpoint was taken — a half-cleared
        // snapshots/ directory, which is a real state (resume and reset both clear it).
        Files.delete(tmp.resolve("snapshots/cycle-1/src/A.java"));
        Files.createDirectories(tmp.resolve("output/src"));
        Files.writeString(tmp.resolve("output/src/Live.java"), "class Live {}");

        RewindService.Result result = rewind.rewindTo("cycle-1", CacheMode.FRESH, "test", "cycle-9");

        assertThat(result.performed()).isFalse();
        assertThat(tmp.resolve("output/src/Live.java"))
                .as("a rewind that cannot complete must leave the run its artifact")
                .exists();
        assertThat(tmp.resolve("output.rewind-staging")).doesNotExist();
    }

    @Test
    @DisplayName("an unknown cycle is refused")
    void unknownCheckpointIsRefused() {
        RewindService.Result result = rewind.rewindTo("nope", CacheMode.FRESH, "test", "cycle-9");
        assertThat(result.performed()).isFalse();
        assertThat(result.refusedBecause()).contains("no checkpoint");
    }

    @Test
    @DisplayName("the field case: a merged point whose root is finished is passed over for an older open one")
    void aMergedButFinishedPointIsNotChosenToContinueFrom() throws IOException {
        // The 2026-09-03 shape exactly: an older merged point with the root still open, then a
        // newer merged point taken once the root had completed. "Newest that landed work" picks
        // the second, restores it faultlessly, and the next cycle halts on a finished root.
        log.append("g1", null, "ACTIVE",
                com.walpro.autobuilder.goal.GoalTransition.Kind.CREATED, "cycle-1", null);
        writeSnapshot("cycle-1", Map.of("src/A.java", "class A {}"));
        checkpoints.write(mergedContext("cycle-1"));
        log.append("g1", "ACTIVE", "COMPLETED",
                com.walpro.autobuilder.goal.GoalTransition.Kind.CHANGED, "cycle-2", null);
        writeSnapshot("cycle-2", Map.of("src/A.java", "class A {}"));
        checkpoints.write(mergedContext("cycle-2"));

        assertThat(rewind.available()).extracting(CheckpointRecord::cycleId)
                .as("both are restorable, and the operator listing keeps offering both")
                .containsExactly("cycle-2", "cycle-1");
        assertThat(rewind.lastMergedBefore(null)).get()
                .extracting(CheckpointRecord::cycleId)
                .as("the newer one restores perfectly and then halts — the older one still has work in it")
                .isEqualTo("cycle-1");
        assertThat(rewind.lastGoodBefore(null)).get()
                .extracting(CheckpointRecord::cycleId)
                .isEqualTo("cycle-1");
    }

    @Test
    @DisplayName("with no open point left to return to, the policy accessors say so rather than offering a finished one")
    void nothingOpenLeftIsReportedAsNothing() throws IOException {
        log.append("g1", null, "ACTIVE",
                com.walpro.autobuilder.goal.GoalTransition.Kind.CREATED, "cycle-0", null);
        log.append("g1", "ACTIVE", "COMPLETED",
                com.walpro.autobuilder.goal.GoalTransition.Kind.CHANGED, "cycle-1", null);
        writeSnapshot("cycle-1", Map.of("src/A.java", "class A {}"));
        checkpoints.write(mergedContext("cycle-1"));

        assertThat(rewind.available()).hasSize(1);
        assertThat(rewind.lastMergedBefore(null))
                .as("empty means the halt happens as before, which is honest; a finished point would look like a rescue")
                .isEmpty();
    }

    @Test
    @DisplayName("lastGoodBefore finds the newest restorable point older than a cycle")
    void lastGoodBeforePicksTheNewestOlderPoint() throws IOException {
        writeSnapshot("cycle-1", Map.of("src/A.java", "class A {}"));
        writeSnapshot("cycle-2", Map.of("src/A.java", "class A2 {}"));
        checkpoints.write(context("cycle-1"));
        checkpoints.write(context("cycle-2"));

        assertThat(rewind.lastGoodBefore("cycle-3")).get()
                .extracting(CheckpointRecord::cycleId).isEqualTo("cycle-2");
        assertThat(rewind.lastGoodBefore("cycle-2")).get()
                .extracting(CheckpointRecord::cycleId).isEqualTo("cycle-1");
        assertThat(rewind.lastGoodBefore("cycle-1")).isEmpty();
    }

    private void writeSnapshot(String cycleId, Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path p = tmp.resolve("snapshots/" + cycleId + "/" + e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue());
        }
    }

    /** A cycle that landed a merge — what makes a point a candidate to return to. */
    private CycleContextData mergedContext(String cycleId) {
        return context(cycleId).toBuilder().merged(true).build();
    }

    private CycleContextData context(String cycleId) {
        return CycleContextData.builder()
                .cycleName(cycleId)
                .snapshotPath("snapshots/" + cycleId)
                .mutationPath("mutations/" + cycleId)
                .currentGoalId("g1")
                .currentSubGoalId("s1")
                .build();
    }

    /** A real bean factory holding exactly these holders — the manifest resolves them by name. */
    private static DefaultListableBeanFactory factoryOf(CheckpointableState... holders) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        for (CheckpointableState holder : holders) {
            factory.registerSingleton(holder.stateKey() + "Holder", holder);
        }
        return factory;
    }

    /**
     * A real archive over the temp target. The graph index is left unwired, which is the
     * no-graph path: checkpoints stay within their own run, exactly as before this existed.
     */
    private CheckpointArchive archiveFor(FileService files) {
        CheckpointArchive archive = new CheckpointArchive(files, diffConfig());
        ReflectionTestUtils.setField(archive, "enabled", true);
        ReflectionTestUtils.setField(archive, "retain", 500);
        return archive;
    }

    /** The same exclusions the archive uses — one definition of what counts as the project. */
    private com.walpro.autobuilder.config.DiffConfigProperties diffConfig() {
        var diff = new com.walpro.autobuilder.config.DiffConfigProperties();
        diff.setExcludeFolders(java.util.List.of("target", "logs"));
        return diff;
    }

    // ---- the carried cycle context (2026-09-14) ----
    //
    // A rewind restored holders, goals, tree and cache — and then the loop settled from the context
    // of the cycle just backed out of, because the carried context was never captured. Every
    // consecutive-cycle streak restarted at zero and the next prompt described a tree that was no
    // longer there. A rewind staged one cycle short of an escalation threshold could never reach it.

    @Test
    @DisplayName("the carried context comes back with the point, streaks and relayed reasons intact")
    void theCarriedContextComesBackWithThePoint() throws IOException {
        writeSnapshot("cycle-1", Map.of("src/A.java", "class A {}"));
        checkpoints.write(context("cycle-1")
                .withCriticalHeuristicStreaks(Map.of("StoreCoherenceHeuristic|Book", 2))
                .withRejectionFeedback(List.of("StoreCoherenceHeuristic: parallel store introduced")));

        RewindService.Result result = rewind.rewindTo("cycle-1", CacheMode.FRESH, "test", "cycle-9");

        assertThat(result.carriedContextSource()).isEqualTo("checkpoint");
        CycleContextData carried = result.carried().orElseThrow();
        assertThat(carried.cycleName()).isEqualTo("cycle-1");
        assertThat(carried.criticalHeuristicStreaks()).containsEntry("StoreCoherenceHeuristic|Book", 2);
        assertThat(carried.rejectionFeedback()).containsExactly("StoreCoherenceHeuristic: parallel store introduced");
        assertThat(result.failures()).noneMatch(f -> f.contains("carried cycle context"));
    }

    @Test
    @DisplayName("across the run boundary the archived context restores, and is not planted into the tree")
    void theArchivedContextOutlivesTheCheckpointDirectory() throws IOException {
        writeSnapshot("cycle-1", Map.of("src/A.java", "class A {}"));
        checkpoints.write(context("cycle-1")
                .withCriticalHeuristicStreaks(Map.of("StoreCoherenceHeuristic|Book", 2)));
        // What a run boundary does to this run's own copies: snapshots/ and the checkpoint's
        // context go; the record and the archive stay.
        fileService.deleteRecursively("snapshots/cycle-1");
        fileService.deleteFile("checkpoints/cycle-1/" + CheckpointService.CONTEXT_FILE);

        RewindService.Result result = rewind.rewindTo("cycle-1", CacheMode.FRESH, "test", "cycle-9");

        assertThat(result.performed()).isTrue();
        assertThat(result.carriedContextSource()).isEqualTo("archive");
        assertThat(result.carried().orElseThrow().criticalHeuristicStreaks())
                .containsEntry("StoreCoherenceHeuristic|Book", 2);
        assertThat(tmp.resolve("output/" + CheckpointArchive.CONTEXT_FILE))
                .as("state kept beside the tree must not become project source")
                .doesNotExist();
        assertThat(tmp.resolve("output/src/A.java")).exists();
    }

    @Test
    @DisplayName("a point that carries no context says so, and the rewind does not read as clean")
    void aPointWithoutACarriedContextSaysSo() throws IOException {
        writeSnapshot("cycle-1", Map.of("src/A.java", "class A {}"));
        checkpoints.write(context("cycle-1"));
        // A point taken before contexts were captured: neither copy exists.
        fileService.deleteFile("checkpoints/cycle-1/" + CheckpointService.CONTEXT_FILE);
        fileService.deleteFile(CheckpointArchive.ARCHIVE_DIR + "/cycle-1/" + CheckpointArchive.CONTEXT_FILE);

        RewindService.Result result = rewind.rewindTo("cycle-1", CacheMode.FRESH, "test", "cycle-9");

        assertThat(result.performed()).isTrue();
        assertThat(result.carried()).isEmpty();
        assertThat(result.carriedContextSource()).isEqualTo("none");
        assertThat(result.clean()).isFalse();
        assertThat(result.failures()).anyMatch(f -> f.contains("carried cycle context was not captured"));
    }

    @Test
    @DisplayName("a supplied context restores but is labelled with its provenance, never as a capture")
    void aSuppliedContextIsLabelledNotCaptured() throws IOException {
        writeSnapshot("cycle-1", Map.of("src/A.java", "class A {}"));
        checkpoints.write(context("cycle-1"));
        fileService.deleteFile("checkpoints/cycle-1/" + CheckpointService.CONTEXT_FILE);
        // The archived file was written by a person from the run's logs, and says so.
        Files.writeString(tmp.resolve(CheckpointArchive.ARCHIVE_DIR + "/cycle-1/" + CheckpointArchive.CONTEXT_PROVENANCE_FILE),
                "RECONSTRUCTED 2026-09-14 from autobuilder-2026-09-13_09-44-38.log\n");

        RewindService.Result result = rewind.rewindTo("cycle-1", CacheMode.FRESH, "test", "cycle-9");

        assertThat(result.carriedContextSource()).isEqualTo("archive");
        assertThat(result.carried()).isPresent();
        assertThat(result.clean()).isFalse();
        assertThat(result.failures()).anyMatch(f -> f.contains("not a capture")
                && f.contains("RECONSTRUCTED 2026-09-14 from autobuilder-2026-09-13_09-44-38.log"));
        assertThat(tmp.resolve("output/" + CheckpointArchive.CONTEXT_PROVENANCE_FILE)).doesNotExist();
    }


    @Test
    @DisplayName("nothing under the archive's reserved prefix is ever planted into the tree")
    void reservedArchiveEntriesAreNeverPlantedIntoTheTree() throws IOException {
        writeSnapshot("cycle-1", Map.of("src/A.java", "class A {}"));
        checkpoints.write(context("cycle-1"));
        fileService.deleteRecursively("snapshots/cycle-1");
        // A file a person or a later build put beside the tree, under the reserved prefix but not
        // on any list of names — the first field rewind planted exactly such a file into output/.
        Files.writeString(tmp.resolve(CheckpointArchive.ARCHIVE_DIR + "/cycle-1/__context.provenance.json"), "{}");

        rewind.rewindTo("cycle-1", CacheMode.FRESH, "test", "cycle-9");

        assertThat(tmp.resolve("output/__context.provenance.json")).doesNotExist();
        assertThat(tmp.resolve("output/" + CheckpointArchive.CONTEXT_FILE)).doesNotExist();
        assertThat(tmp.resolve("output/" + CheckpointArchive.CACHE_SUBDIR)).doesNotExist();
        assertThat(tmp.resolve("output/src/A.java")).exists();
    }

}
