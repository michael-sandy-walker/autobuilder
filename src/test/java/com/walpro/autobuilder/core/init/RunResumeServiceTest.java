package com.walpro.autobuilder.core.init;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.config.AutobuilderConfigProperties;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.JavacDirectProbe;
import com.walpro.autobuilder.core.persistence.PreservedArtifactService;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.GoalType;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4-CD slice 1: resume-from-preserved. The boot restores output/ from the preserved
 * artifact, keeps + re-grounds the loaded goal state (reactivate GIVEN_UP root, drop banked
 * criteria reads), clears run-scoped dirs, and falls through to the fresh-start path whenever
 * no resumable lineage exists.
 */
class RunResumeServiceTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private AutobuilderConfigProperties config;
    private FileService fileService;
    private GoalPlannerService goalPlannerService;
    private JavacDirectProbe buildProbe;
    private BehaviourLogger behaviourLogger;
    private RunResumeService service;

    @BeforeEach
    void setUp() {
        config = new AutobuilderConfigProperties();
        config.setResumeFromPreserved(true);
        fileService = new FileService(tempDir.toString(), objectMapper);
        goalPlannerService = mock(GoalPlannerService.class);
        buildProbe = mock(JavacDirectProbe.class);
        behaviourLogger = mock(BehaviourLogger.class);
        service = new RunResumeService(config, fileService, goalPlannerService, objectMapper);
        service.setBuildProbe(buildProbe);
        service.setBehaviourLogger(behaviourLogger);
    }

    private GoalEntity root(GoalStatus status) {
        GoalEntity g = new GoalEntity("root-1", "Evolve the app", null, GoalType.GOAL, null);
        g.setStatus(status);
        return g;
    }

    private void preservedArtifact() throws IOException {
        Path preserved = tempDir.resolve("meta/preserved-artifact");
        Files.createDirectories(preserved.resolve("src/main/java"));
        Files.writeString(preserved.resolve("pom.xml"), "<project/>");
        Files.writeString(preserved.resolve("src/main/java/App.java"), "class App {}");
        objectMapper.writeValue(tempDir.resolve("meta/preserved-artifact-provenance.json").toFile(),
                new PreservedArtifactService.Provenance("cycle-42", true, true,
                        "COMPILE_OK", "COMPILE_OK", 1, 7, "cycle-halt", Instant.now()));
    }

    private void staleRunResidue() throws IOException {
        Files.createDirectories(tempDir.resolve("output"));
        Files.writeString(tempDir.resolve("output/Stale.java"), "class Stale {}");
        Files.createDirectories(tempDir.resolve("snapshots/cycle-old"));
        Files.writeString(tempDir.resolve("snapshots/cycle-old/x.txt"), "old");
        Files.createDirectories(tempDir.resolve("mutations/cycle-old"));
        Files.writeString(tempDir.resolve("mutations/cycle-old/y.txt"), "old");
    }

    private void probeReturns(BuildResult result) {
        lenient().when(buildProbe.execute(anyString(), anyString(), anyLong()))
                .thenReturn(new BuildOutcome(result, "", 10L, "javac", "resume-verify"));
    }

    @Test
    void flagOff_neverResumes() throws IOException {
        config.setResumeFromPreserved(false);
        preservedArtifact();
        staleRunResidue();

        assertThat(service.attemptResume()).isFalse();
        assertThat(tempDir.resolve("output/Stale.java")).exists();
    }

    @Test
    void noPreservedArtifact_fallsThrough_untouched() throws IOException {
        staleRunResidue();
        when(goalPlannerService.getRootGoal()).thenReturn(root(GoalStatus.GIVEN_UP));

        assertThat(service.attemptResume()).isFalse();
        assertThat(tempDir.resolve("output/Stale.java")).exists();
        assertThat(tempDir.resolve("snapshots/cycle-old/x.txt")).exists();
    }

    @Test
    void noLoadedGoalState_fallsThrough() throws IOException {
        preservedArtifact();
        when(goalPlannerService.getRootGoal()).thenReturn(null);

        assertThat(service.attemptResume()).isFalse();
    }

    @Test
    void completedRoot_fallsThrough_freshRunIsRight() throws IOException {
        preservedArtifact();
        staleRunResidue();
        when(goalPlannerService.getRootGoal()).thenReturn(root(GoalStatus.COMPLETED));

        assertThat(service.attemptResume()).isFalse();
        assertThat(tempDir.resolve("output/Stale.java")).exists();
    }

    @Test
    void givenUpRoot_resumes_restoresOutput_clearsRunScopedDirs_regrounds() throws IOException {
        preservedArtifact();
        staleRunResidue();
        probeReturns(BuildResult.COMPILE_OK);
        when(goalPlannerService.getRootGoal()).thenReturn(root(GoalStatus.GIVEN_UP));
        when(goalPlannerService.reactivateGoal("root-1")).thenReturn(java.util.List.of("root-1"));
        when(goalPlannerService.regroundActiveSubgoalsForResume(anyString())).thenReturn(3);

        assertThat(service.attemptResume()).isTrue();

        // output/ replaced by the preserved tree
        assertThat(tempDir.resolve("output/Stale.java")).doesNotExist();
        assertThat(tempDir.resolve("output/pom.xml")).exists();
        assertThat(tempDir.resolve("output/src/main/java/App.java")).exists();
        // run-scoped dirs cleared but present
        assertThat(tempDir.resolve("snapshots/cycle-old")).doesNotExist();
        assertThat(tempDir.resolve("mutations/cycle-old")).doesNotExist();
        assertThat(tempDir.resolve("snapshots")).isDirectory();
        assertThat(tempDir.resolve("mutations")).isDirectory();
        // the preserved copy itself stays — it is the lineage record until the next preserve
        assertThat(tempDir.resolve("meta/preserved-artifact/pom.xml")).exists();

        verify(goalPlannerService).reactivateGoal("root-1");
        verify(goalPlannerService).regroundActiveSubgoalsForResume(anyString());
        verify(behaviourLogger).runResumed(eq("cycle-42"), eq("COMPILE_OK"), eq(true), eq(3), eq(false));
    }

    @Test
    void activeRoot_resumes_withoutReactivation() throws IOException {
        preservedArtifact();
        probeReturns(BuildResult.COMPILE_OK);
        when(goalPlannerService.getRootGoal()).thenReturn(root(GoalStatus.ACTIVE));
        when(goalPlannerService.regroundActiveSubgoalsForResume(anyString())).thenReturn(0);

        assertThat(service.attemptResume()).isTrue();
        verify(goalPlannerService, never()).reactivateGoal(anyString());
        verify(behaviourLogger).runResumed(eq("cycle-42"), eq("COMPILE_OK"), eq(false), eq(0), eq(false));
    }

    @Test
    void failedRestoreProbe_isRecorded_neverGates() throws IOException {
        // Loaded state is hypothesis: a compile-failed restore is recorded honestly and the
        // resume proceeds — the loop's build gates own repair.
        preservedArtifact();
        probeReturns(BuildResult.COMPILE_ERROR);
        when(goalPlannerService.getRootGoal()).thenReturn(root(GoalStatus.ACTIVE));
        when(goalPlannerService.regroundActiveSubgoalsForResume(anyString())).thenReturn(0);

        assertThat(service.attemptResume()).isTrue();
        verify(behaviourLogger).runResumed(eq("cycle-42"), eq("COMPILE_ERROR"), eq(false), eq(0), eq(false));
    }

    @Test
    void noProbeWired_recordsUnverified() throws IOException {
        preservedArtifact();
        service.setBuildProbe(null);
        when(goalPlannerService.getRootGoal()).thenReturn(root(GoalStatus.ACTIVE));
        when(goalPlannerService.regroundActiveSubgoalsForResume(anyString())).thenReturn(0);

        assertThat(service.attemptResume()).isTrue();
        verify(behaviourLogger).runResumed(eq("cycle-42"), eq("UNVERIFIED"), eq(false), eq(0), eq(false));
    }

    @Test
    void endReportOfSameLineage_isLoadedAndRendered() throws IOException {
        // T4-CD slice 2: the parent's end report is consulted when its root matches the loaded lineage.
        preservedArtifact();
        probeReturns(BuildResult.COMPILE_OK);
        when(goalPlannerService.getRootGoal()).thenReturn(root(GoalStatus.ACTIVE));
        when(goalPlannerService.regroundActiveSubgoalsForResume(anyString())).thenReturn(0);

        var endReportService = mock(com.walpro.autobuilder.core.persistence.RunEndReportService.class);
        var endReport = new com.walpro.autobuilder.core.persistence.RunEndReportService.RunEndReport(
                "1", "exhaustion-runaway", "cycle-halt", Instant.now(), "root-1", "Evolve the app",
                "GIVEN_UP", 3, 5, 2, java.util.List.of(), java.util.List.of(), null,
                java.util.List.of(), true, "cycle-42", "COMPILE_OK");
        when(endReportService.readForLineage("root-1")).thenReturn(java.util.Optional.of(endReport));
        when(endReportService.renderPromptBlock(endReport)).thenReturn("--- PRIOR RUN OF THIS LINEAGE (advisory) ---\n...");
        service.setRunEndReportService(endReportService);

        assertThat(service.attemptResume()).isTrue();
        assertThat(service.priorEndReportPromptBlock()).startsWith("--- PRIOR RUN OF THIS LINEAGE");
        verify(behaviourLogger).runResumed(eq("cycle-42"), eq("COMPILE_OK"), eq(false), eq(0), eq(true));
    }

    @Test
    void foreignEndReport_isNotConsulted() throws IOException {
        // Ownership: an end report written under a different root goal must not be claimed.
        preservedArtifact();
        probeReturns(BuildResult.COMPILE_OK);
        when(goalPlannerService.getRootGoal()).thenReturn(root(GoalStatus.ACTIVE));
        when(goalPlannerService.regroundActiveSubgoalsForResume(anyString())).thenReturn(0);

        var endReportService = mock(com.walpro.autobuilder.core.persistence.RunEndReportService.class);
        when(endReportService.readForLineage("root-1")).thenReturn(java.util.Optional.empty());
        service.setRunEndReportService(endReportService);

        assertThat(service.attemptResume()).isTrue();
        assertThat(service.priorEndReportPromptBlock()).isNull();
        verify(behaviourLogger).runResumed(eq("cycle-42"), eq("COMPILE_OK"), eq(false), eq(0), eq(false));
    }

    @Test
    void unreadableProvenance_resumesWithoutIt() throws IOException {
        preservedArtifact();
        Files.writeString(tempDir.resolve("meta/preserved-artifact-provenance.json"), "not-json");
        probeReturns(BuildResult.COMPILE_OK);
        when(goalPlannerService.getRootGoal()).thenReturn(root(GoalStatus.ACTIVE));
        when(goalPlannerService.regroundActiveSubgoalsForResume(anyString())).thenReturn(0);

        assertThat(service.attemptResume()).isTrue();
        verify(behaviourLogger).runResumed(any(), eq("COMPILE_OK"), eq(false), eq(0), eq(false));
    }
}
