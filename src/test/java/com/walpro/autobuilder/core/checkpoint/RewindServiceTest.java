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
        StateFingerprintService fingerprints =
                new StateFingerprintService(fileService, mapper, planner, manifest);
        checkpoints = new CheckpointService(fileService, mapper, manifest, log, identity, fingerprints);
        ReflectionTestUtils.setField(checkpoints, "enabled", true);
        ReflectionTestUtils.setField(checkpoints, "retainCheckpoints", 40);
        ReflectionTestUtils.setField(checkpoints, "captureCache", true);
        when(planner.restoreGoalStateAsOf(any(), any(), any()))
                .thenReturn(new GoalPlannerService.RewindResult(2, 1, "s1"));
        rewind = new RewindService(fileService, mapper, checkpoints, manifest, log, planner, fingerprints);
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
        verify(planner).restoreGoalStateAsOf(statuses.capture(), existing.capture(), anyString());

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
    @DisplayName("a checkpoint whose tree is gone is refused rather than half-restored")
    void missingTreeIsRefused() throws IOException {
        writeSnapshot("cycle-1", Map.of("src/A.java", "class A {}"));
        checkpoints.write(context("cycle-1"));
        fileService.deleteRecursively("snapshots/cycle-1");

        RewindService.Result result = rewind.rewindTo("cycle-1", CacheMode.FRESH, "test", "cycle-9");

        assertThat(result.performed()).isFalse();
        assertThat(result.refusedBecause()).contains("cleared");
        verify(planner, never()).restoreGoalStateAsOf(any(), any(), any());
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
}
