package com.walpro.autobuilder.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.build.RecurringDefectTracker;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.GoalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T4-CD slice 2: the run testament — written once per pause episode, ownership-checked on read,
 * rendered as an advisory prompt block that carries only what the live goal tree cannot say.
 */
class RunTestamentServiceTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private FileService fileService;
    private GoalPlannerService goalPlannerService;
    private RecurringDefectTracker tracker;
    private RunTestamentService service;

    @BeforeEach
    void setUp() {
        fileService = new FileService(tempDir.toString(), objectMapper);
        goalPlannerService = mock(GoalPlannerService.class);
        tracker = mock(RecurringDefectTracker.class);
        service = new RunTestamentService(fileService, goalPlannerService, objectMapper);
        service.setRecurringDefectTracker(tracker);
        lenient().when(tracker.dominantDefect()).thenReturn(Optional.empty());
        lenient().when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        lenient().when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());
        lenient().when(goalPlannerService.getAbandonedGoals()).thenReturn(List.of());
        lenient().when(goalPlannerService.getCriteriaPassStatus(org.mockito.ArgumentMatchers.any(GoalEntity.class)))
                .thenReturn(Map.of());
    }

    private GoalEntity goal(String id, GoalStatus status, String description, List<String> criteria) {
        GoalEntity g = new GoalEntity(id, description, null, GoalType.SUBGOAL, "root-1");
        g.setStatus(status);
        if (criteria != null) {
            g.getMetadata().put("acceptanceCriteria", criteria);
        }
        return g;
    }

    private GoalEntity root(GoalStatus status) {
        GoalEntity g = new GoalEntity("root-1", "Evolve the app", null, GoalType.GOAL, null);
        g.setStatus(status);
        return g;
    }

    private CycleContextData context() {
        return CycleContextData.builder()
                .cycleName("cycle-77")
                .rejectionFeedback(List.of("the change does not persist the loan"))
                .build();
    }

    @Test
    void write_recordsTerminalDiagnosis() throws IOException {
        when(goalPlannerService.getRootGoal()).thenReturn(root(GoalStatus.GIVEN_UP));
        GoalEntity open = goal("g-1", GoalStatus.ACTIVE, "POST /loans returns 201", List.of("returns 201"));
        GoalEntity blocked = goal("g-2", GoalStatus.GIVEN_UP, "GET /loans lists loans", List.of("lists all loans"));
        GoalEntity cascade = goal("g-3", GoalStatus.GIVEN_UP, "bookkeeping child", null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(open));
        when(goalPlannerService.getAbandonedGoals()).thenReturn(List.of(blocked, cascade));
        when(tracker.dominantDefect()).thenReturn(Optional.of(
                new RecurringDefectTracker.DominantDefect("CANNOT_FIND_SYMBOL|LoanRepository", 0.8, 3, false)));

        service.write(context(), "exhaustion-runaway");

        var t = objectMapper.readValue(
                tempDir.resolve("meta/run-testament.json").toFile(),
                RunTestamentService.RunTestament.class);
        assertThat(t.terminalReason()).isEqualTo("exhaustion-runaway");
        assertThat(t.haltCycle()).isEqualTo("cycle-77");
        assertThat(t.rootGoalId()).isEqualTo("root-1");
        assertThat(t.rootStatus()).isEqualTo("GIVEN_UP");
        assertThat(t.openWork()).hasSize(1);
        assertThat(t.openWork().get(0).unmetCriteria()).containsExactly("returns 201");
        // only abandoned goals WITH recorded unmet criteria are blockers — cascade bookkeeping is not
        assertThat(t.abandonedBlockers()).hasSize(1);
        assertThat(t.abandonedBlockers().get(0).goalId()).isEqualTo("g-2");
        assertThat(t.dominantDefect().missingToken()).isEqualTo("LoanRepository");
        assertThat(t.lastRejectionReasons()).containsExactly("the change does not persist the loan");
    }

    @Test
    void write_bankedCriteriaAreNotUnmet() throws IOException {
        when(goalPlannerService.getRootGoal()).thenReturn(root(GoalStatus.ACTIVE));
        GoalEntity open = goal("g-1", GoalStatus.ACTIVE, "loans endpoints", List.of("returns 201", "returns 409"));
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(open));
        when(goalPlannerService.getCriteriaPassStatus(open)).thenReturn(Map.of(1, true, 2, false));

        service.write(context(), "deadline");

        var t = objectMapper.readValue(
                tempDir.resolve("meta/run-testament.json").toFile(),
                RunTestamentService.RunTestament.class);
        assertThat(t.openWork().get(0).unmetCriteria()).containsExactly("returns 409");
    }

    @Test
    void write_claimsProvenance_onlyFromItsOwnEpisode() throws IOException {
        when(goalPlannerService.getRootGoal()).thenReturn(root(GoalStatus.GIVEN_UP));
        // provenance from the SAME episode (haltCycle matches the context)
        Files.createDirectories(tempDir.resolve("meta"));
        objectMapper.writeValue(tempDir.resolve("meta/preserved-artifact-provenance.json").toFile(),
                new PreservedArtifactService.Provenance("cycle-50", true, true,
                        "COMPILE_OK", "COMPILE_OK", 1, 7, "cycle-77", Instant.now()));

        service.write(context(), "exhaustion-runaway");

        var t = objectMapper.readValue(
                tempDir.resolve("meta/run-testament.json").toFile(),
                RunTestamentService.RunTestament.class);
        assertThat(t.preservedFromCycle()).isEqualTo("cycle-50");
        assertThat(t.preservedVerifiedBuild()).isEqualTo("COMPILE_OK");
    }

    @Test
    void write_parentProvenance_isNotClaimed() throws IOException {
        // A stale provenance (parent run's — haltCycle differs) must not be attributed to this run.
        when(goalPlannerService.getRootGoal()).thenReturn(root(GoalStatus.GIVEN_UP));
        Files.createDirectories(tempDir.resolve("meta"));
        objectMapper.writeValue(tempDir.resolve("meta/preserved-artifact-provenance.json").toFile(),
                new PreservedArtifactService.Provenance("cycle-50", true, true,
                        "COMPILE_OK", "COMPILE_OK", 1, 7, "cycle-of-the-parent", Instant.now()));

        service.write(context(), "goal-fulfilled");

        var t = objectMapper.readValue(
                tempDir.resolve("meta/run-testament.json").toFile(),
                RunTestamentService.RunTestament.class);
        assertThat(t.preservedFromCycle()).isNull();
        assertThat(t.preservedVerifiedBuild()).isNull();
    }

    @Test
    void write_neverThrows() {
        when(goalPlannerService.getRootGoal()).thenThrow(new RuntimeException("graph down"));
        service.write(context(), "deadline"); // must not propagate
        assertThat(tempDir.resolve("meta/run-testament.json")).doesNotExist();
    }

    @Test
    void readForLineage_matchingRoot_returnsTestament() {
        when(goalPlannerService.getRootGoal()).thenReturn(root(GoalStatus.GIVEN_UP));
        service.write(context(), "exhaustion-runaway");

        assertThat(service.readForLineage("root-1")).isPresent();
    }

    @Test
    void readForLineage_foreignRoot_refuses() {
        when(goalPlannerService.getRootGoal()).thenReturn(root(GoalStatus.GIVEN_UP));
        service.write(context(), "exhaustion-runaway");

        assertThat(service.readForLineage("some-other-root")).isEmpty();
        assertThat(service.readForLineage(null)).isEmpty();
    }

    @Test
    void readForLineage_absentOrCorrupt_isEmpty() throws IOException {
        assertThat(service.readForLineage("root-1")).isEmpty();
        Files.createDirectories(tempDir.resolve("meta"));
        Files.writeString(tempDir.resolve("meta/run-testament.json"), "not-json");
        assertThat(service.readForLineage("root-1")).isEmpty();
    }

    @Test
    void renderPromptBlock_carriesTheDiagnosis_andStaysAdvisory() {
        var t = new RunTestamentService.RunTestament(
                "1", "exhaustion-runaway", "cycle-77", Instant.now(), "root-1", "Evolve the app",
                "GIVEN_UP", 4, 9, 2,
                List.of(),
                List.of(new RunTestamentService.OpenItem("g-2", "GIVEN_UP", "GET /loans lists loans",
                        List.of("lists all loans"), "exhausted")),
                new RunTestamentService.DefectShape("LoanRepository", 0.8, 3, true),
                List.of("the change does not persist the loan"),
                true, "cycle-50", "COMPILE_OK");

        String block = service.renderPromptBlock(t);

        assertThat(block).startsWith("--- PRIOR RUN OF THIS LINEAGE (advisory) ---");
        assertThat(block).contains("exhaustion-runaway");
        assertThat(block).contains("9 completed / 4 open / 2 abandoned");
        assertThat(block).contains("preserved artifact");
        assertThat(block).contains("LoanRepository");
        assertThat(block).contains("GET /loans lists loans");
        assertThat(block).contains("[unmet: lists all loans]");
        assertThat(block).contains("the change does not persist the loan");
        assertThat(block).contains("code on disk is the ground truth");
        assertThat(service.renderPromptBlock(null)).isNull();
    }
}
