package com.walpro.autobuilder.goal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-DQ blocker 1: the fold has to reconstruct what {@code SET g.status = 'COMPLETED'} used to
 * destroy — not only the status a goal had, but whether it existed at all.
 */
class GoalTransitionLogTest {

    @TempDir
    Path tmp;

    private GoalTransitionLog log;

    @BeforeEach
    void setUp() {
        log = new GoalTransitionLog(new FileService(tmp.toString(), productionMapper()), productionMapper());
    }

    /**
     * The mapper the application actually injects — {@code JacksonConfig}, INDENT_OUTPUT and all.
     * Using a bare {@code new ObjectMapper()} here is what let the first cut of this log ship a
     * file of pretty-printed objects that no restart could read back: within one JVM the carried
     * sequence hid it completely.
     */
    private static ObjectMapper productionMapper() {
        return new com.walpro.autobuilder.config.JacksonConfig().objectMapper();
    }

    @Test
    @DisplayName("the fold returns the status a goal had at the cut point, not its latest")
    void foldReturnsTheStatusAtTheCutPoint() {
        long created = log.append("g1", null, "ACTIVE", GoalTransition.Kind.CREATED, "c1", null);
        long completed = log.append("g1", "ACTIVE", "COMPLETED", GoalTransition.Kind.CHANGED, "c2", "done");

        assertThat(log.statusesAsOf(created)).containsEntry("g1", GoalStatus.ACTIVE);
        assertThat(log.statusesAsOf(completed)).containsEntry("g1", GoalStatus.COMPLETED);
    }

    @Test
    @DisplayName("a goal planned after the cut point did not exist yet")
    void goalsPlannedLaterAreAbsentFromAnEarlierFold() {
        long cut = log.append("g1", null, "ACTIVE", GoalTransition.Kind.CREATED, "c1", null);
        log.append("g2", null, "ACTIVE", GoalTransition.Kind.CREATED, "c2", null);

        assertThat(log.goalsExistingAsOf(cut)).containsExactly("g1");
        assertThat(log.goalsExistingAsOf(log.currentSeq())).containsExactly("g1", "g2");
    }

    @Test
    @DisplayName("a goal rewound out disappears from every later fold but stays in the history")
    void removedGoalsDropOutOfLaterFolds() {
        log.append("g1", null, "ACTIVE", GoalTransition.Kind.CREATED, "c1", null);
        long beforeRemoval = log.append("g2", null, "ACTIVE", GoalTransition.Kind.CREATED, "c2", null);
        long afterRemoval = log.append("g2", "ACTIVE", "ACTIVE", GoalTransition.Kind.REMOVED, "c3",
                "rewound out");

        assertThat(log.goalsExistingAsOf(beforeRemoval)).contains("g2");
        assertThat(log.goalsExistingAsOf(afterRemoval)).doesNotContain("g2");
        assertThat(log.statusesAsOf(afterRemoval)).doesNotContainKey("g2");
        assertThat(log.readAll()).hasSize(3);
    }

    @Test
    @DisplayName("the fold is taken in one read, so its three views cannot disagree")
    void foldAtGivesAllThreeViewsFromOneRead() {
        log.append("g1", null, "ACTIVE", GoalTransition.Kind.CREATED, "c1", null);
        long cut = log.append("g2", null, "ACTIVE", GoalTransition.Kind.CREATED, "c1", null);
        log.append("g1", "ACTIVE", "COMPLETED", GoalTransition.Kind.CHANGED, "c2", "done");
        log.append("g2", "ACTIVE", "ACTIVE", GoalTransition.Kind.REMOVED, "c3", "rewound out");

        GoalTransitionLog.Fold fold = log.foldAt(cut);

        assertThat(fold.statuses()).containsEntry("g1", GoalStatus.ACTIVE).containsEntry("g2", GoalStatus.ACTIVE);
        assertThat(fold.existingGoalIds()).containsExactly("g1", "g2");
        assertThat(fold.transitionsUndone()).isEqualTo(2);
        assertThat(fold.statuses().keySet())
                .as("a goal cannot have a status the fold also says did not exist")
                .isSubsetOf(fold.existingGoalIds());
    }

    @Test
    @DisplayName("knowsGoal tells a first observation from a reload of a goal the log already has")
    void knowsGoalDistinguishesFirstSightingFromReload() {
        assertThat(log.knowsGoal("g1")).isFalse();
        log.append("g1", null, "ACTIVE", GoalTransition.Kind.CREATED, "c1", null);
        assertThat(log.knowsGoal("g1")).isTrue();
    }

    @Test
    @DisplayName("a log left behind by a pretty-printing writer is still read back")
    void aPrettyPrintedLogIsStillRecovered() throws IOException {
        // The shape the first field run wrote. Recovering it matters because the alternative is a
        // fold that silently returns nothing and a rewind that quietly restores an empty tree.
        java.nio.file.Files.createDirectories(tmp.resolve("meta"));
        java.nio.file.Files.writeString(tmp.resolve(GoalTransitionLog.LOG_PATH), """
                {
                  "seq" : 1,
                  "cycleId" : null,
                  "goalId" : "g1",
                  "from" : null,
                  "to" : "ACTIVE",
                  "kind" : "CREATED",
                  "resolution" : null,
                  "at" : "2026-09-02T20:50:36.245758849Z"
                }
                {
                  "seq" : 2,
                  "cycleId" : "c2",
                  "goalId" : "g1",
                  "from" : "ACTIVE",
                  "to" : "COMPLETED",
                  "kind" : "CHANGED",
                  "resolution" : "done",
                  "at" : "2026-09-02T20:51:36.245758849Z"
                }
                """);

        assertThat(log.readAll()).hasSize(2);
        assertThat(log.currentSeq()).isEqualTo(2L);
        assertThat(log.knowsGoal("g1")).isTrue();
        assertThat(log.statusesAsOf(1L)).containsEntry("g1", GoalStatus.ACTIVE);
    }

    @Test
    @DisplayName("the cut point for a cycle is the last sequence recorded at or before it")
    void seqAtCycleFindsTheCutPoint() {
        log.append("g1", null, "ACTIVE", GoalTransition.Kind.CREATED, "2026-01-01_00-00-01", null);
        long atSecond = log.append("g1", "ACTIVE", "COMPLETED", GoalTransition.Kind.CHANGED,
                "2026-01-01_00-00-02", null);
        log.append("g2", null, "ACTIVE", GoalTransition.Kind.CREATED, "2026-01-01_00-00-03", null);

        assertThat(log.seqAtCycle("2026-01-01_00-00-02")).isEqualTo(atSecond);
    }

    @Test
    @DisplayName("each event is one line, under the mapper the application actually injects")
    void eachEventIsOneLine() throws IOException {
        log.append("g1", null, "ACTIVE", GoalTransition.Kind.CREATED, "c1", null);
        log.append("g1", "ACTIVE", "COMPLETED", GoalTransition.Kind.CHANGED, "c2", "done");

        List<String> lines = java.nio.file.Files.readAllLines(tmp.resolve(GoalTransitionLog.LOG_PATH));
        assertThat(lines)
                .as("the injected mapper indents; a log written with it would be ten lines an event")
                .hasSize(2);
    }

    @Test
    @DisplayName("with the log file gone, the fold recovers the lineage's events from the graph")
    void aLostLogIsRecoveredFromTheGraph() {
        // The file is authoritative and normally present. When it is not, the alternative to this
        // is a fold that yields nothing and a rewind that restores no goal state while reporting
        // success — the silent partial restore the design exists to prevent.
        var graph = org.mockito.Mockito.mock(com.walpro.autobuilder.graph.goal.GoalGraphService.class);
        var identity = org.mockito.Mockito.mock(com.walpro.autobuilder.core.init.RunIdentityService.class);
        org.mockito.Mockito.when(identity.lineageId()).thenReturn("lineage-1");
        org.mockito.Mockito.when(graph.transitionsForLineage(org.mockito.ArgumentMatchers.eq("lineage-1"),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(
                        new GoalTransition(1L, "c1", "g1", null, "ACTIVE",
                                GoalTransition.Kind.CREATED, null, java.time.Instant.now()),
                        new GoalTransition(2L, "c2", "g1", "ACTIVE", "COMPLETED",
                                GoalTransition.Kind.CHANGED, "done", java.time.Instant.now())));
        log.setGoalGraphService(graph);
        log.setRunIdentityService(identity);

        // No file was ever written.
        assertThat(log.readAll()).hasSize(2);
        assertThat(log.statusesAsOf(1L)).containsEntry("g1", GoalStatus.ACTIVE);
        assertThat(log.foldAt(1L).transitionsUndone()).isEqualTo(1);
    }

    @Test
    @DisplayName("the file wins when it has events — the graph is a fallback, not a second source")
    void theFileIsAuthoritative() {
        var graph = org.mockito.Mockito.mock(com.walpro.autobuilder.graph.goal.GoalGraphService.class);
        log.setGoalGraphService(graph);
        log.append("g1", null, "ACTIVE", GoalTransition.Kind.CREATED, "c1", null);

        assertThat(log.readAll()).hasSize(1);
        org.mockito.Mockito.verify(graph, org.mockito.Mockito.never())
                .transitionsForLineage(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("the sequence survives a new reader over the same file")
    void sequenceIsRecoveredFromTheFile() throws IOException {
        log.append("g1", null, "ACTIVE", GoalTransition.Kind.CREATED, "c1", null);
        log.append("g1", "ACTIVE", "COMPLETED", GoalTransition.Kind.CHANGED, "c2", null);

        GoalTransitionLog reopened =
                new GoalTransitionLog(new FileService(tmp.toString(), productionMapper()), productionMapper());
        assertThat(reopened.currentSeq()).isEqualTo(2L);
        assertThat(reopened.knowsGoal("g1")).isTrue();
        assertThat(reopened.append("g2", null, "ACTIVE", GoalTransition.Kind.CREATED, "c3", null))
                .isEqualTo(3L);
    }
}
