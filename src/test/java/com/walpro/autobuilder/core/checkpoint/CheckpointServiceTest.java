package com.walpro.autobuilder.core.checkpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.init.RunIdentityService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T4-DQ slice 1: a checkpoint is mostly references — what it must actually carry is the
 * decision-bearing holder state and the fold cut point, and it must refuse to look restorable
 * when the tree it references is gone.
 */
class CheckpointServiceTest {

    @TempDir
    Path tmp;

    private FileService fileService;
    private ObjectMapper mapper;
    private CheckpointService checkpoints;
    private CountingHolder holder;

    @BeforeEach
    void setUp() throws IOException {
        mapper = new ObjectMapper().findAndRegisterModules();
        fileService = new FileService(tmp.toString(), mapper);
        holder = new CountingHolder();
        StateManifest manifest = new StateManifest(factoryOf(holder));
        GoalTransitionLog log = new GoalTransitionLog(fileService, mapper);
        log.append("g1", null, "ACTIVE", com.walpro.autobuilder.goal.GoalTransition.Kind.CREATED, "c1", null);
        RunIdentityService identity = mock(RunIdentityService.class);
        when(identity.runId()).thenReturn("run-1");
        when(identity.lineageId()).thenReturn("lineage-1");
        GoalPlannerService planner = mock(GoalPlannerService.class);
        when(planner.getAllGoals()).thenReturn(java.util.List.of());
        StateFingerprintService fingerprints =
                new StateFingerprintService(fileService, mapper, planner, manifest, diffConfig());
        checkpoints = new CheckpointService(fileService, mapper, manifest, log, identity, fingerprints, planner, archiveFor(fileService));
        ReflectionTestUtils.setField(checkpoints, "enabled", true);
        ReflectionTestUtils.setField(checkpoints, "retainCheckpoints", 40);
        ReflectionTestUtils.setField(checkpoints, "captureCache", true);
    }

    @Test
    @DisplayName("a checkpoint carries holder state and the goal fold cut point")
    void checkpointCarriesHolderStateAndCutPoint() {
        holder.value = 7;
        CheckpointRecord record = checkpoints.write(context("cycle-1", "snapshots/cycle-1")).orElseThrow();

        assertThat(record.goalTransitionSeq()).isEqualTo(1L);
        assertThat(record.holders()).containsKey("counting");
        assertThat(record.complete()).isTrue();

        CheckpointRecord readBack = checkpoints.read("cycle-1").orElseThrow();
        JsonNode node = checkpoints.holdersNode(readBack).get("counting");
        assertThat(node.get("value").asInt()).isEqualTo(7);
    }

    @Test
    @DisplayName("the resolution cache is copied in, so a FAITHFUL rewind has something to restore")
    void cacheIsCopiedIntoTheCheckpoint() throws IOException {
        Files.createDirectories(tmp.resolve("cache"));
        Files.writeString(tmp.resolve("cache/imports.json"), "{}");

        CheckpointRecord record = checkpoints.write(context("cycle-1", "snapshots/cycle-1")).orElseThrow();

        assertThat(record.cacheCaptured()).isTrue();
        assertThat(tmp.resolve("checkpoints/cycle-1/cache/imports.json")).exists();
    }

    @Test
    @DisplayName("a checkpoint whose tree snapshot has been cleared is not offered as restorable")
    void checkpointWithoutItsTreeIsNotOffered() throws IOException {
        checkpoints.write(context("cycle-1", "snapshots/cycle-1"));
        assertThat(checkpoints.restorableCheckpoints()).extracting(CheckpointRecord::cycleId)
                .containsExactly("cycle-1");

        fileService.deleteRecursively("snapshots/cycle-1");

        assertThat(checkpoints.restorableCheckpoints()).isEmpty();
    }

    @Test
    @DisplayName("a cycle that wrote no tree snapshot yields a point that cannot be returned to")
    void cycleWithoutASnapshotIsNotRestorable() {
        CheckpointRecord record = checkpoints.write(context("cycle-1", null)).orElseThrow();
        assertThat(record.restorable()).isFalse();
        assertThat(checkpoints.restorableCheckpoints()).isEmpty();
    }

    @Test
    @DisplayName("retention prunes oldest-first so per-cycle checkpoints stay bounded")
    void retentionPrunesOldestFirst() {
        ReflectionTestUtils.setField(checkpoints, "retainCheckpoints", 2);
        checkpoints.write(context("cycle-1", "snapshots/cycle-1"));
        checkpoints.write(context("cycle-2", "snapshots/cycle-2"));
        checkpoints.write(context("cycle-3", "snapshots/cycle-3"));

        assertThat(checkpoints.listCheckpointCycles()).containsExactly("cycle-2", "cycle-3");
    }

    @Test
    @DisplayName("one unresolvable holder costs one holder, not the whole checkpoint")
    void anUnresolvableHolderDoesNotLoseTheOthers() {
        // The first field run's shape: the bootstrap cycle runs from inside AutobuilderService's
        // own construction, so resolving it throws "currently in creation". Before this, that one
        // bean took the entire capture down and cycle 1 recorded zero holders.
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("countingHolder", holder);
        factory.registerBeanDefinition("brokenHolder",
                new org.springframework.beans.factory.support.RootBeanDefinition(ExplodingHolder.class));
        StateManifest manifest = new StateManifest(factory);

        StateManifest.Capture capture = manifest.capture();

        assertThat(capture.state()).containsKey("counting");
        assertThat(capture.failures()).anyMatch(f -> f.startsWith("brokenHolder: unresolvable"));
        assertThat(capture.complete()).isFalse();
    }

    @Test
    @DisplayName("a checkpoint whose fingerprint this build cannot read is kept, restorable but unverified")
    void anUnreadableFingerprintDoesNotCostThePoint() throws IOException {
        // Exactly what happened: the fingerprint's shape changed and every checkpoint on disk
        // became unreadable, so nine perfectly restorable points — full trees, all holders, no
        // capture failures — went unreachable because the VERIFICATION had changed. The check is
        // not the payload.
        checkpoints.write(context("cycle-1", "snapshots/cycle-1"));
        String path = "checkpoints/cycle-1/" + CheckpointService.CHECKPOINT_FILE;
        var node = (com.fasterxml.jackson.databind.node.ObjectNode)
                mapper.readTree(fileService.readString(path));
        node.set("fingerprint", mapper.readTree("{\"tree\":\"abc\",\"goals\":\"a-string-not-a-map\"}"));
        fileService.writeString(path, mapper.writeValueAsString(node));

        CheckpointRecord record = checkpoints.read("cycle-1").orElseThrow();

        assertThat(record.restorable()).isTrue();
        assertThat(record.holders()).isNotEmpty();
        assertThat(record.fingerprint())
                .as("null means the round trip goes unchecked, which the rewind reports rather than hides")
                .isNull();
        assertThat(checkpoints.restorableCheckpoints()).extracting(CheckpointRecord::cycleId)
                .contains("cycle-1");
    }

    @Test
    @DisplayName("an incomplete checkpoint is not offered as a rewind target")
    void incompleteCheckpointsAreNotOffered() {
        holder.explode = true;
        CheckpointRecord record = checkpoints.write(context("cycle-1", "snapshots/cycle-1")).orElseThrow();

        assertThat(record.complete()).isFalse();
        assertThat(checkpoints.restorableCheckpoints())
                .as("restoring a holder to its start-of-run value instead of the value it had is a "
                        + "rewind that silently changes a decision")
                .isEmpty();
    }

    @Test
    @DisplayName("a holder that refuses capture makes the checkpoint knowingly incomplete")
    void captureFailuresAreRecordedNotSwallowed() {
        holder.explode = true;
        CheckpointRecord record = checkpoints.write(context("cycle-1", "snapshots/cycle-1")).orElseThrow();

        assertThat(record.complete()).isFalse();
        assertThat(record.captureFailures()).anyMatch(f -> f.startsWith("counting:"));
    }

    private CycleContextData context(String cycleId, String snapshotPath) {
        if (snapshotPath != null) {
            try {
                fileService.ensureFolderExists(snapshotPath);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
        return CycleContextData.builder()
                .cycleName(cycleId)
                .snapshotPath(snapshotPath)
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

    /** A holder the container cannot construct — the "currently in creation" case, in miniature. */
    static final class ExplodingHolder implements CheckpointableState {
        ExplodingHolder() {
            throw new IllegalStateException("currently in creation");
        }

        @Override
        public String stateKey() {
            return "exploding";
        }

        @Override
        public Object captureState() {
            return Map.of();
        }

        @Override
        public void restoreState(JsonNode state, ObjectMapper mapper) {
            // unreachable
        }
    }

    /** A holder whose state is a single number, so a round trip is unambiguous. */
    static final class CountingHolder implements CheckpointableState {
        int value;
        boolean explode;

        public record State(int value) {}

        @Override
        public String stateKey() {
            return "counting";
        }

        @Override
        public Object captureState() {
            if (explode) throw new IllegalStateException("refusing");
            return new State(value);
        }

        @Override
        public void restoreState(JsonNode state, ObjectMapper mapper) {
            value = state == null || state.isNull() ? 0 : mapper.convertValue(state, State.class).value();
        }
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
}
