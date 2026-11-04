package com.walpro.autobuilder.core.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.init.RunIdentityService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.persistence.MutationSnapshotService;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalTransitionLog;
import com.walpro.autobuilder.goal.purpose.InputEntry;
import com.walpro.autobuilder.goal.purpose.PurposeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T4-EG slice 1: Continue is "continue with what we stopped" (user, 2026-11-04) -- the project returned to the point it
 * stands at, in total, whatever that point is. A restart is a fresh set of services over the same project folder: the
 * holders start empty, as a new JVM's do, and everything they had must come back from disk.
 */
class ContinueStateTest {

    @TempDir
    Path tmp;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private Jvm jvm;

    /** One JVM's worth of the services a Continue goes through. */
    private final class Jvm {
        final FileService files = new FileService(tmp.toString(), mapper);
        final CheckpointServiceTest.CountingHolder holder = new CheckpointServiceTest.CountingHolder();
        final PurposeService purpose = PurposeService.fileOnly(files);
        final CheckpointService checkpoints;
        final RewindService rewind;

        Jvm() {
            DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
            factory.registerSingleton("countingHolder", holder);
            factory.registerSingleton("purposeHolder", purpose);
            StateManifest manifest = new StateManifest(factory);
            GoalTransitionLog log = new GoalTransitionLog(files, mapper);
            RunIdentityService identity = mock(RunIdentityService.class);
            when(identity.runId()).thenReturn("run-" + System.nanoTime());
            when(identity.lineageId()).thenReturn("lineage-1");
            GoalPlannerService planner = mock(GoalPlannerService.class);
            when(planner.getAllGoals()).thenReturn(java.util.List.of());
            when(planner.getCurrentSubGoalId()).thenReturn("s1");
            when(planner.restoreGoalStateAsOf(any(), any(), any(), any()))
                    .thenReturn(new GoalPlannerService.RewindResult(0, 0, "s1"));
            var diff = new com.walpro.autobuilder.config.DiffConfigProperties();
            diff.setExcludeFolders(java.util.List.of("target", "logs"));
            StateFingerprintService fingerprints = new StateFingerprintService(files, mapper, planner, manifest, diff);
            CheckpointArchive archive = new CheckpointArchive(files, diff);
            ReflectionTestUtils.setField(archive, "retain", 500);
            checkpoints = new CheckpointService(files, mapper, manifest, log, identity, fingerprints, planner, archive);
            ReflectionTestUtils.setField(checkpoints, "enabled", true);
            ReflectionTestUtils.setField(checkpoints, "retainCheckpoints", 40);
            checkpoints.setSnapshotService(new MutationSnapshotService(files, mapper));
            rewind = new RewindService(files, mapper, checkpoints, manifest, log, planner, fingerprints, identity);
        }

        /** A cycle ends: the tree is snapshotted and the point written, as the cycle tail does. */
        void cycle(String id) throws IOException {
            files.copyFolder("output", "snapshots/" + id);
            checkpoints.write(CycleContextData.builder().cycleName(id).snapshotPath("snapshots/" + id)
                    .currentGoalId("g1").currentSubGoalId("s1").build());
        }

        /** What Continue does: the standing point, restored in total. */
        RewindService.Result continueProject() {
            String at = checkpoints.standing().orElseThrow().cycleId();
            return rewind.rewindTo(at, CacheMode.FAITHFUL, "continue", at);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        jvm = new Jvm();
        jvm.purpose.recordSeed("Build a quill registry.", "operator");
        write("output/src/A.java", "class A {}");
        write("cache/resolutions.json", "{}");
    }

    @Test
    @DisplayName("stopped after an import was kept: the import's code, its record and the loop's decisions all come back")
    void continuesAfterAnImport() throws IOException {
        jvm.holder.value = 3;
        jvm.cycle("cycle-1");
        InputEntry input = jvm.purpose.addProjectInput("use the reader", "operator", null, null, "digest");
        write("output/src/Reader.java", "class Reader {}");
        write("meta/inputs/" + input.id() + "/placed.txt", "src/Reader.java\n");
        jvm.holder.value = 4;
        jvm.checkpoints.writePoint(null, "an import was kept");

        jvm = new Jvm();
        assertThat(jvm.holder.value).as("a new JVM's holders start empty").isZero();
        RewindService.Result result = jvm.continueProject();

        assertThat(result.performed()).isTrue();
        assertThat(result.failures()).as("the state after Continue is the state at the stop").isEmpty();
        assertThat(jvm.holder.value).isEqualTo(4);
        assertThat(tmp.resolve("output/src/Reader.java")).hasContent("class Reader {}");
        assertThat(jvm.purpose.ledger().entries()).extracting(InputEntry::id).contains(input.id());
        assertThat(tmp.resolve("meta/inputs/" + input.id() + "/placed.txt")).exists();
    }

    @Test
    @DisplayName("stopped after a rewind: it continues from the rewound state, and the later one is still there to move forward to")
    void continuesFromARewoundState() throws IOException {
        jvm.holder.value = 1;
        jvm.cycle("cycle-1");
        write("output/src/B.java", "class B {}");
        jvm.holder.value = 2;
        jvm.cycle("cycle-2");
        jvm.rewind.rewindTo("cycle-1", CacheMode.FAITHFUL, "test", "cycle-2");

        jvm = new Jvm();
        RewindService.Result result = jvm.continueProject();

        assertThat(result.toCycleId()).as("the point the project stood at, not the newest on disk").isEqualTo("cycle-1");
        assertThat(jvm.holder.value).isEqualTo(1);
        assertThat(tmp.resolve("output/src/B.java")).doesNotExist();

        RewindService.Result forward = jvm.rewind.rewindTo("cycle-2", CacheMode.FAITHFUL, "operator", "cycle-1");
        assertThat(forward.performed()).isTrue();
        assertThat(jvm.holder.value).isEqualTo(2);
        assertThat(tmp.resolve("output/src/B.java")).exists();
    }

    @Test
    @DisplayName("a rewind to before an import removes it whole, and moving forward brings it back")
    void aRewindTakesTheImportWithIt() throws IOException {
        jvm.cycle("cycle-1");
        InputEntry input = jvm.purpose.addProjectInput("use the reader", "operator", null, null, "digest");
        write("output/src/Reader.java", "class Reader {}");
        write("meta/inputs/" + input.id() + "/placed.txt", "src/Reader.java\n");
        jvm.cycle("cycle-2");

        jvm.rewind.rewindTo("cycle-1", CacheMode.FAITHFUL, "operator", "cycle-2");
        assertThat(tmp.resolve("output/src/Reader.java")).doesNotExist();
        assertThat(jvm.purpose.ledger().entries()).extracting(InputEntry::id).doesNotContain(input.id());
        assertThat(tmp.resolve("meta/inputs")).doesNotExist();
        assertThat(jvm.purpose.specification()).doesNotContain("use the reader");

        jvm.rewind.rewindTo("cycle-2", CacheMode.FAITHFUL, "operator", "cycle-1");
        assertThat(tmp.resolve("output/src/Reader.java")).exists();
        assertThat(jvm.purpose.ledger().entries()).extracting(InputEntry::id).contains(input.id());
        assertThat(jvm.purpose.specification()).contains("use the reader");
    }

    @Test
    @DisplayName("a point outside a cycle carries the context the next tick reads, as it is")
    void aPointOutsideACycleKeepsTheCarriedContext() throws IOException {
        jvm.cycle("cycle-1");
        CycleContextData carried = CycleContextData.builder().cycleName("cycle-1").snapshotPath("snapshots/cycle-1")
                .currentGoalId("g1").currentSubGoalId("s1").pausedReason("user-pause").build();
        String point = jvm.checkpoints.writePoint(carried, "the loop halted").orElseThrow().cycleId();

        jvm = new Jvm();
        RewindService.Result result = jvm.continueProject();

        assertThat(point).isGreaterThan("cycle-1");
        assertThat(result.toCycleId()).isEqualTo(point);
        assertThat(result.carriedContext().cycleName()).isEqualTo("cycle-1");
        assertThat(result.carriedContext().pausedReason()).isEqualTo("user-pause");
    }

    private void write(String path, String text) throws IOException {
        Path p = tmp.resolve(path);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }
}
