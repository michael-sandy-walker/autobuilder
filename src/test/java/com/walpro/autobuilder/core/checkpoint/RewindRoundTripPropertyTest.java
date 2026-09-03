package com.walpro.autobuilder.core.checkpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.init.RunIdentityService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.GoalTransitionLog;
import com.walpro.autobuilder.goal.GoalType;
import com.walpro.autobuilder.graph.goal.GoalGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T4-DQ slice 2 — the round-trip property, stated as the design states it: checkpoint at N, move
 * forward, rewind to N, and assert the state is IDENTICAL. Backtracking is unsafe without this,
 * because a restore that is subtly wrong is worse than no restore.
 *
 * <p>The last test is the one that makes the rest mean anything. An assertion that the round trip
 * succeeds proves nothing unless the same check FAILS on a state that was genuinely not captured —
 * so one is deliberately left uncaptured, and the check has to find it. That is the difference
 * between a property and a hope.
 */
class RewindRoundTripPropertyTest {

    @TempDir
    Path tmp;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private FileService fileService;
    private GoalPlannerService planner;
    private GoalTransitionLog log;
    private CheckpointService checkpoints;
    private RewindService rewind;
    private StateFingerprintService fingerprints;
    private CheckpointServiceTest.CountingHolder captured;
    private LeakyHolder leaky;

    @BeforeEach
    void setUp() {
        fileService = new FileService(tmp.toString(), mapper);
        GoalGraphService graph = mock(GoalGraphService.class);
        planner = new GoalPlannerService(fileService, graph, mock(GoalPlannerConfigProperties.class));
        log = new GoalTransitionLog(fileService, mapper);
        planner.setGoalTransitionLog(log);
        planner.setCurrentCycleContext(CycleContextData.builder().cycleName("cycle-1").build());
        captured = new CheckpointServiceTest.CountingHolder();
        leaky = new LeakyHolder();
        wire(captured);
    }

    /** Rebuilds the manifest-dependent services over a chosen set of holders. */
    private void wire(CheckpointableState... holders) {
        StateManifest manifest = new StateManifest(factoryOf(holders));
        fingerprints = new StateFingerprintService(fileService, mapper, planner, manifest);
        RunIdentityService identity = mock(RunIdentityService.class);
        when(identity.runId()).thenReturn("run-1");
        when(identity.lineageId()).thenReturn("lineage-1");
        checkpoints = new CheckpointService(fileService, mapper, manifest, log, identity, fingerprints, planner);
        ReflectionTestUtils.setField(checkpoints, "enabled", true);
        ReflectionTestUtils.setField(checkpoints, "retainCheckpoints", 40);
        ReflectionTestUtils.setField(checkpoints, "captureCache", true);
        rewind = new RewindService(fileService, mapper, checkpoints, manifest, log, planner, fingerprints);
    }

    @Test
    @DisplayName("checkpoint at N, run to M, rewind to N — every component of the state matches")
    void theRoundTripRestoresEveryComponent() throws IOException {
        seedTree("cycle-1", Map.of("src/A.java", "class A {}"));
        seedGoals();
        captured.value = 3;
        CheckpointRecord atN = checkpoints.write(context("cycle-1")).orElseThrow();
        StateFingerprint before = fingerprints.current();

        runForwardTo("cycle-2");

        assertThat(fingerprints.current().divergencesFrom(before))
                .as("the forward run genuinely moved the state, or this test proves nothing")
                .contains("tree", "holder:counting")
                // Goals are reported per goal now, naming the one that moved rather than saying
                // only that something in the tree did.
                .anyMatch(d -> d.startsWith("goal["));

        RewindService.Result result = rewind.rewindTo("cycle-1", CacheMode.FRESH, "test", "cycle-2");

        assertThat(result.performed()).isTrue();
        assertThat(fingerprints.current().divergencesFrom(atN.fingerprint())).isEmpty();
        assertThat(result.failures()).isEmpty();
        assertThat(result.clean()).isTrue();
    }

    @Test
    @DisplayName("state the checkpoint did not capture is caught AT the restore, not later")
    void uncapturedStateIsCaughtAtTheRestore() throws IOException {
        // The leaky holder captures nothing at all — the shape of the defect this whole design
        // exists to prevent: state that gates behaviour, quietly not carried.
        wire(captured, leaky);
        seedTree("cycle-1", Map.of("src/A.java", "class A {}"));
        seedGoals();
        leaky.streak = 1;
        checkpoints.write(context("cycle-1"));

        runForwardTo("cycle-2");
        leaky.streak = 9;

        RewindService.Result result = rewind.rewindTo("cycle-1", CacheMode.FRESH, "test", "cycle-2");

        assertThat(result.performed()).isTrue();
        assertThat(result.failures())
                .as("the uncaptured holder announces itself at the restore")
                .anyMatch(f -> f.contains("state diverged after restore: holder:leaky"));
        assertThat(result.clean()).isFalse();
    }

    @Test
    @DisplayName("a goal completed after the checkpoint is active again, and its later sibling is gone")
    void goalStateGoesBackTogetherWithTheTree() throws IOException {
        seedTree("cycle-1", Map.of("src/A.java", "class A {}"));
        seedGoals();
        CheckpointRecord atN = checkpoints.write(context("cycle-1")).orElseThrow();

        planner.setCurrentCycleContext(CycleContextData.builder().cycleName("cycle-2").build());
        goal("s1").setStatus(GoalStatus.COMPLETED);
        addGoal("s2", "root");
        persist();
        seedTree("cycle-2", Map.of("src/A.java", "class A {}", "src/B.java", "class B {}"));

        rewind.rewindTo("cycle-1", CacheMode.FRESH, "test", "cycle-2");

        assertThat(goal("s1").getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(planner.getGoalById("s2")).isNull();
        assertThat(fingerprints.current().divergencesFrom(atN.fingerprint())).isEmpty();
    }

    // ---------------------------------------------------------------------------------

    /** Moves the tree, the goal tree and a captured holder, the way a real cycle would. */
    private void runForwardTo(String cycleId) throws IOException {
        planner.setCurrentCycleContext(CycleContextData.builder().cycleName(cycleId).build());
        Files.writeString(tmp.resolve("output/src/B.java"), "class B {}");
        goal("s1").setStatus(GoalStatus.COMPLETED);
        persist();
        captured.value = 11;
        seedTree(cycleId, Map.of("src/A.java", "class A {}", "src/B.java", "class B {}"));
    }

    private void seedGoals() {
        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        GoalEntity root = new GoalEntity("root", "build the thing", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        GoalEntity s1 = new GoalEntity("s1", "add the first part", "planner", GoalType.SUBGOAL, "root");
        s1.setStatus(GoalStatus.ACTIVE);
        goalMap.put("root", root);
        goalMap.put("s1", s1);
        ReflectionTestUtils.setField(planner, "goalMap", goalMap);
        ReflectionTestUtils.setField(planner, "rootGoalId", "root");
        ReflectionTestUtils.setField(planner, "currentSubGoalId", "s1");
        persist();
    }

    private void addGoal(String id, String parentId) {
        GoalEntity g = new GoalEntity(id, "later work " + id, "planner", GoalType.SUBGOAL, parentId);
        g.setStatus(GoalStatus.ACTIVE);
        goalMap().put(id, g);
    }

    private GoalEntity goal(String id) {
        return goalMap().get(id);
    }

    @SuppressWarnings("unchecked")
    private Map<String, GoalEntity> goalMap() {
        return (Map<String, GoalEntity>) ReflectionTestUtils.getField(planner, "goalMap");
    }

    private void persist() {
        ReflectionTestUtils.invokeMethod(planner, "persist");
    }

    /** Writes {@code output/} and its per-cycle snapshot, the way the loop does every cycle. */
    private void seedTree(String cycleId, Map<String, String> files) throws IOException {
        Path output = tmp.resolve("output");
        if (Files.exists(output)) {
            try (var walk = Files.walk(output)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
        for (Map.Entry<String, String> e : files.entrySet()) {
            write(output.resolve(e.getKey()), e.getValue());
            write(tmp.resolve("snapshots/" + cycleId + "/" + e.getKey()), e.getValue());
        }
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private CycleContextData context(String cycleId) {
        return CycleContextData.builder()
                .cycleName(cycleId)
                .snapshotPath("snapshots/" + cycleId)
                .mutationPath("mutations/" + cycleId)
                .currentGoalId("root")
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
     * A holder that gates behaviour on a streak and captures nothing — the exact shape of blocker 2.
     * Its state is visible to the fingerprint (it is registered) but does not survive a restore,
     * so the round-trip check has something real to catch.
     */
    static final class LeakyHolder implements CheckpointableState {
        int streak;

        @Override
        public String stateKey() {
            return "leaky";
        }

        @Override
        public Object captureState() {
            return Map.of("streak", streak);
        }

        @Override
        public void restoreState(JsonNode state, ObjectMapper mapper) {
            // deliberately does nothing: the defect under test
        }
    }
}
