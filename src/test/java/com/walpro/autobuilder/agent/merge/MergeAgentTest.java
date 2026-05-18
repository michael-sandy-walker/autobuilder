package com.walpro.autobuilder.agent.merge;

import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.core.blocker.BlockerSubgoalInjector;
import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.merge.MergeRollbackService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.core.service.merge.CycleMergeTracker;
import com.walpro.autobuilder.core.service.merge.FileVariantCollector;
import com.walpro.autobuilder.core.service.merge.MergeProcessor;
import com.walpro.autobuilder.core.service.merge.MergeResult;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.graph.merge.MergeActionRegistrar;
import com.walpro.autobuilder.model.MergeTrace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MergeAgentTest {

    @Mock
    protected FileService fileService;

    @Mock
    protected FileVariantCollector fileVariantCollector;

    @Mock
    protected MergeProcessor mergeProcessor;

    @Mock
    protected CycleMergeTracker cycleMergeTracker;

    @Mock
    protected MergeActionRegistrar mergeActionRegistrar;

    @Mock
    protected GoalPlannerService goalPlannerService;

    @Mock
    protected com.walpro.autobuilder.core.build.BuildExecutor buildExecutor;

    @Mock
    protected com.walpro.autobuilder.observability.behaviour.BehaviourLogger behaviourLogger;

    @Mock
    protected com.walpro.autobuilder.core.persistence.MutationSnapshotService mutationSnapshotService;

    @Mock
    protected com.walpro.autobuilder.core.build.CompileProbeService compileProbeService;

    protected MergeAgent agent;

    @BeforeEach
    void setUp() {
        BlockerSubgoalInjector blockerSubgoalInjector = new BlockerSubgoalInjector(goalPlannerService);
        agent = new MergeAgent(
                fileService,
                fileVariantCollector,
                mergeProcessor,
                cycleMergeTracker,
                mergeActionRegistrar,
                goalPlannerService,
                buildExecutor,
                blockerSubgoalInjector,
                new MergeRollbackService(),
                mutationSnapshotService,
                compileProbeService
        );
    }

    @Test
    void role_returnsMerge() {
        assertThat(agent.role()).isEqualTo("merge");
    }

    @Test
    void mergeCompletedBranch_noSubGoal_returnsEarlyWithoutMerging() throws Exception {
        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-no-sg")
                .outputPath("output")
                // currentSubGoalId intentionally absent (null)
                .build();

        CycleContextData result = agent.mergeCompletedBranch(context);

        assertThat(result).isSameAs(context);
        verifyNoInteractions(cycleMergeTracker, fileVariantCollector, mergeProcessor, fileService);
    }

    @Test
    void mergeCompletedBranch_blankSubGoal_returnsEarlyWithoutMerging() throws Exception {
        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-blank-sg")
                .outputPath("output")
                .currentSubGoalId("  ")
                .build();

        CycleContextData result = agent.mergeCompletedBranch(context);

        assertThat(result).isSameAs(context);
        verifyNoInteractions(cycleMergeTracker, fileVariantCollector, mergeProcessor, fileService);
    }

    @Test
    void mergeCompletedBranch_skipsWhenCycleAlreadyMerged() throws Exception {
        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-1")
                .outputPath("output")
                .currentSubGoalId("sg-test")
                .build();

        when(cycleMergeTracker.isAlreadyMerged("cycle-1")).thenReturn(true);

        CycleContextData result = agent.mergeCompletedBranch(context);

        // Returns original context and does nothing else
        assertThat(result).isSameAs(context);

        verify(cycleMergeTracker).isAlreadyMerged("cycle-1");
        verifyNoInteractions(fileVariantCollector, mergeProcessor, fileService);
        verify(cycleMergeTracker, never()).markCyclesAsMerged(any());
    }

    @Test
    void mergeCompletedBranch_mergesVariantsWritesOutputAndMarksMerged() throws Exception {
        String cycleId = "cycle-1";
        String outputPath = "output";
        String relativePath = "src/main/java/com/example/demo/DemoApplication.java";

        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath(outputPath)
                .currentSubGoalId("sg-test")
                .build();

        when(cycleMergeTracker.isAlreadyMerged(cycleId)).thenReturn(false);

        // One file to merge
        when(fileVariantCollector.getAllVariantPaths(context))
                .thenReturn(Set.of(relativePath));

        // Provide some variants (we don't care about their content in this test)
        List<FileVariant> variants = List.of(mock(FileVariant.class));
        when(fileVariantCollector.collect(eq(relativePath), anyList(), any()))
                .thenReturn(variants);

        // No existing file in output
        when(fileService.exists(anyString())).thenReturn(false);

        Path resolvedOutputPath = Path.of(outputPath, relativePath);
        when(fileService.resolveSecurePath(anyString()))
                .thenReturn(resolvedOutputPath);

        MergeResult mergeResult = mock(MergeResult.class);
        when(mergeResult.wasSuccessful()).thenReturn(true);
        when(mergeResult.currentContent()).thenReturn("merged-content");

        when(mergeProcessor.process(
                eq(relativePath),
                anyString(),
                eq(variants),
                eq(false),
                any(CycleContextData.class)
        )).thenReturn(mergeResult);

        when(cycleMergeTracker.track(context, relativePath, mergeResult))
                .thenReturn(context);


        when(cycleMergeTracker.markCyclesAsMerged(any(CycleContextData.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        CycleContextData result = agent.mergeCompletedBranch(context);

        // Assert pipeline
        verify(cycleMergeTracker).isAlreadyMerged(cycleId);
        verify(fileVariantCollector).getAllVariantPaths(context);
        verify(fileVariantCollector).collect(eq(relativePath), anyList(), any());

        verify(mergeProcessor).process(
                eq(relativePath),
                anyString(),
                eq(variants),
                eq(false),
                any(CycleContextData.class)
        );

        verify(fileService).writeFileResolved(eq(resolvedOutputPath), eq("merged-content"));
        verify(cycleMergeTracker).track(context, relativePath, mergeResult);
        verify(cycleMergeTracker).markCyclesAsMerged(context);

        // The final context is whatever markCyclesAsMerged returned (here: same as input)
        assertThat(result).isSameAs(context);
    }

    // ── T4-AN-1: post-merge compile gate ────────────────────────────────────

    @Test
    void mergeCompletedBranch_T4AN1_invokesBuildExecutorWhenEnabled_andStoresOutcome() throws Exception {
        String cycleId = "cycle-build";
        String outputPath = "output";
        String relativePath = "src/main/java/com/example/Foo.java";

        // T1-DW: set prior COMPILE_ERROR so rollback condition is not met; build outcome must persist.
        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath(outputPath)
                .currentSubGoalId("sg-build")
                .buildOutcome(prevCompileError())
                .build();

        when(cycleMergeTracker.isAlreadyMerged(cycleId)).thenReturn(false);
        when(fileVariantCollector.getAllVariantPaths(context)).thenReturn(Set.of(relativePath));
        List<FileVariant> variants = List.of(mock(FileVariant.class));
        when(fileVariantCollector.collect(eq(relativePath), anyList(), any())).thenReturn(variants);
        when(fileService.exists(anyString())).thenReturn(false);
        when(fileService.resolveSecurePath(anyString())).thenReturn(Path.of(outputPath, relativePath));

        MergeResult mergeResult = mock(MergeResult.class);
        when(mergeResult.wasSuccessful()).thenReturn(true);
        when(mergeResult.currentContent()).thenReturn("merged");
        when(mergeProcessor.process(eq(relativePath), anyString(), eq(variants), eq(false), any(CycleContextData.class)))
                .thenReturn(mergeResult);
        when(cycleMergeTracker.track(any(), anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(cycleMergeTracker.markCyclesAsMerged(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        com.walpro.autobuilder.goal.GoalEntity injectedGoal = new com.walpro.autobuilder.goal.GoalEntity();
        injectedGoal.setMetadata(new HashMap<>());
        when(goalPlannerService.getGoalById(anyString())).thenReturn(injectedGoal);

        com.walpro.autobuilder.core.build.BuildOutcome outcome = new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR,
                "[ERROR] cannot find symbol",
                4321L,
                "mvn compile",
                null
        );
        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(any(), any())).thenReturn(outcome);

        CycleContextData result = agent.mergeCompletedBranch(context);

        verify(buildExecutor).execute(any(), any());
        assertThat(result.buildOutcome()).isSameAs(outcome);
    }

    // ── T1-DM: deterministic BUILD_FIX subgoal injection on compile failure ──

    @Test
    void mergeCompletedBranch_T1DM_injectsBuildFixSubgoalOnCompileError_andSetsAsCurrent() throws Exception {
        // Setup mirrors the T4-AN-1 happy path but with COMPILE_ERROR + no existing build-fix goal.
        // Verifies: addSubGoal called with setAsCurrent=true, metadata tag written,
        // acceptance criteria recorded, and the description embeds the stderr excerpt.
        String cycleId = "cycle-bf-inject";
        String relativePath = "src/main/java/com/example/Foo.java";
        // T1-DW: set prevError so rollback condition is not met; BUILD_FIX injection must run.
        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId).outputPath("output").currentSubGoalId("sg-stuck")
                .buildOutcome(prevCompileError()).build();

        primeMergePipeline(cycleId, relativePath, context);

        com.walpro.autobuilder.core.build.BuildOutcome outcome = new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR,
                "[ERROR] /path/pom.xml not found\n[ERROR] aborting build",
                100L, "mvn compile", null);
        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(any(), any())).thenReturn(outcome);

        // No existing BUILD_FIX subgoal in the backlog.
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());

        // Capture the goal entity that addSubGoal would have created so the metadata
        // write-back path runs end-to-end.
        com.walpro.autobuilder.goal.GoalEntity injected = new com.walpro.autobuilder.goal.GoalEntity();
        injected.setMetadata(new HashMap<>());
        when(goalPlannerService.getGoalById(anyString())).thenReturn(injected);

        agent.mergeCompletedBranch(context);

        org.mockito.ArgumentCaptor<String> descCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> idCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(goalPlannerService).addSubGoal(idCaptor.capture(), descCaptor.capture(), eq(true));

        // Description carries the first informative [ERROR] line — without this the evolver
        // (which sees the subgoal description but NOT the BUILD RESULT block) has no signal.
        // T2-CY: when contextPaths name a non-build-config file, the description includes it
        // ("Fix compile error in <path>: ...") — the older shape ("Fix compile error: ...")
        // is preserved when no path can be resolved.
        assertThat(descCaptor.getValue()).startsWith("Fix compile error").contains("pom.xml not found");
        assertThat(idCaptor.getValue()).startsWith("goal-");

        // Metadata tag persists on the injected goal so the idempotency check finds it later.
        assertThat(injected.getMetadata()).containsEntry(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX);
        verify(goalPlannerService).saveGoalMetadata(injected);
        verify(goalPlannerService).setAcceptanceCriteria(eq(idCaptor.getValue()), anyList());
    }

    @Test
    void mergeCompletedBranch_T1DM_extractsErrorLineFromMavenBannerStderr() throws Exception {
        // Realistic Maven stderr for a missing pom.xml: the first non-blank line is the
        // INFO banner, not the actual error. summariseStderr must skip past it to the
        // first informative [ERROR] line — and skip the trailing "-> [Help 1]" framing.
        String cycleId = "cycle-bf-banner";
        String relativePath = "src/main/java/com/example/Foo.java";
        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId).outputPath("output").currentSubGoalId("sg-banner")
                .buildOutcome(prevCompileError()).build();

        primeMergePipeline(cycleId, relativePath, context);

        String mavenStderr = """
                [INFO] Scanning for projects...
                [INFO]
                [INFO] ------------------------------------------------------------------------
                [ERROR]
                [ERROR] The goal you specified requires a project to execute but there is no POM in this directory (/data/firestarter). Please verify you invoked Maven from the correct directory.
                [ERROR] -> [Help 1]
                [ERROR]
                """;
        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(any(), any())).thenReturn(new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR, mavenStderr, 100L, "mvn compile", null));

        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        com.walpro.autobuilder.goal.GoalEntity injected = new com.walpro.autobuilder.goal.GoalEntity();
        injected.setMetadata(new HashMap<>());
        when(goalPlannerService.getGoalById(anyString())).thenReturn(injected);

        agent.mergeCompletedBranch(context);

        org.mockito.ArgumentCaptor<String> descCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(goalPlannerService).addSubGoal(anyString(), descCaptor.capture(), eq(true));

        // Picks the meaningful "no POM in this directory" line, not the [INFO] banner and not
        // the bare [ERROR] / "-> [Help 1]" framing entries.
        // T2-CY: prefix may be "Fix compile error:" or "Fix compile error in <path>:".
        assertThat(descCaptor.getValue())
                .startsWith("Fix compile error")
                .contains("no POM in this directory")
                .doesNotContain("[INFO]")
                .doesNotContain("Scanning for projects")
                .doesNotContain("-> [Help");

        // T2-CT: criteria must name pom.xml and project root so the T1-CS substring guard
        // won't find them in unrelated Java files.
        org.mockito.ArgumentCaptor<String> idCaptor2 = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<java.util.List> criteriaCaptor = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(goalPlannerService).setAcceptanceCriteria(idCaptor2.capture(), criteriaCaptor.capture());
        String allCriteria = criteriaCaptor.getValue().toString();
        assertThat(allCriteria).contains("pom.xml").contains("project root");

        // T2-CT: metadata must record the classified error kind.
        assertThat(injected.getMetadata()).containsEntry("buildErrorKind", "MISSING_POM");
    }

    @Test
    void mergeCompletedBranch_T1DM_skipsInjectionWhenBuildFixAlreadyActive() throws Exception {
        // Idempotency: a prior cycle already injected a BUILD_FIX subgoal that is still ACTIVE.
        // The current cycle's COMPILE_ERROR must not double-inject.
        String cycleId = "cycle-bf-idem";
        String relativePath = "src/main/java/com/example/Foo.java";
        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId).outputPath("output").currentSubGoalId("sg-existing-fix")
                .buildOutcome(prevCompileError()).build();

        primeMergePipeline(cycleId, relativePath, context);

        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(any(), any())).thenReturn(new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR, "[ERROR] still broken", 100L, "mvn", null));

        com.walpro.autobuilder.goal.GoalEntity existing = new com.walpro.autobuilder.goal.GoalEntity();
        existing.setStatus(com.walpro.autobuilder.goal.GoalStatus.ACTIVE);
        java.util.Map<String, Object> meta = new HashMap<>();
        meta.put(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX);
        existing.setMetadata(meta);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(existing);

        agent.mergeCompletedBranch(context);

        verify(goalPlannerService, never()).addSubGoal(anyString(), anyString(), anyBoolean());
        verify(goalPlannerService, never()).setAcceptanceCriteria(anyString(), anyList());
    }

    @Test
    void mergeCompletedBranch_T1DM_doesNotInjectOnBuildSuccess() throws Exception {
        String cycleId = "cycle-bf-ok";
        String relativePath = "src/main/java/com/example/Foo.java";
        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId).outputPath("output").currentSubGoalId("sg-ok").build();

        primeMergePipeline(cycleId, relativePath, context);

        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(any(), any())).thenReturn(new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_OK, "", 100L, "mvn", null));

        // T1-DR: clearActiveBuildFixSubgoals() is called on COMPILE_OK to clean up stale BUILD_FIX
        // subgoals. Stub getCurrentSubGoal() to return a non-BUILD_FIX subgoal so the method is
        // a no-op (nothing to clean up in this scenario).
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(java.util.List.of());

        agent.mergeCompletedBranch(context);

        // BUILD_FIX is gated specifically on COMPILE_ERROR — no injection on COMPILE_OK.
        verify(goalPlannerService, never()).addSubGoal(anyString(), anyString(), anyBoolean());
        // T1-DR: cleanup scan runs but finds nothing to clean up.
        verify(goalPlannerService, never()).markGoalGivenUp(anyString(), anyString());
    }

    @Test
    void mergeCompletedBranch_T2CT_unrecognisedError_keepsGenericCriteria() throws Exception {
        // T2-CT: when the stderr does not match any known pattern, criteria fall back to the
        // generic text so existing behaviour is preserved (zero regression for unobserved errors).
        String cycleId = "cycle-bf-unknown";
        String relativePath = "src/main/java/com/example/Foo.java";
        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId).outputPath("output").currentSubGoalId("sg-unknown")
                .buildOutcome(prevCompileError()).build();

        primeMergePipeline(cycleId, relativePath, context);

        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(any(), any())).thenReturn(new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR,
                "[ERROR] some completely unrecognised Maven error with no pattern match",
                100L, "mvn compile", null));

        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        com.walpro.autobuilder.goal.GoalEntity injected = new com.walpro.autobuilder.goal.GoalEntity();
        injected.setMetadata(new HashMap<>());
        when(goalPlannerService.getGoalById(anyString())).thenReturn(injected);

        agent.mergeCompletedBranch(context);

        org.mockito.ArgumentCaptor<java.util.List> criteriaCaptor = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(goalPlannerService).setAcceptanceCriteria(anyString(), criteriaCaptor.capture());
        String allCriteria = criteriaCaptor.getValue().toString();
        assertThat(allCriteria)
                .contains("Project compiles without errors")
                .contains("Compile error reported in the previous build is resolved");
        assertThat(injected.getMetadata()).containsEntry("buildErrorKind", "UNKNOWN");
    }

    // ── T1-DW: filesystem rollback on post-merge COMPILE_ERROR ──

    @Test
    void mergeCompletedBranch_T1DW_rollsBackWhenPreviousBuildWasOkAndMutationIsNonBuildConfig() throws Exception {
        // Previous build = COMPILE_OK → Java file merged → post-merge build = COMPILE_ERROR.
        // Expected: filesystem restored, buildOutcome reset to prev COMPILE_OK, no BUILD_FIX injection.
        String cycleId = "cycle-rollback";
        String relativePath = "src/main/java/com/example/Broken.java";
        com.walpro.autobuilder.core.build.BuildOutcome prevOk = new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_OK, "", 50L, "mvn compile", "prev-cycle");

        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId).outputPath("output").currentSubGoalId("sg-rollback")
                .buildOutcome(prevOk).build();

        // fileExistsInOutput=true → rollback restores content, not deletes.
        primeRollbackPipeline(cycleId, relativePath, context, true, "original java content");

        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(any(), any())).thenReturn(new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR,
                "[ERROR] cannot find symbol: Broken.java", 100L, "mvn compile", cycleId));

        CycleContextData result = agent.mergeCompletedBranch(context);

        verify(fileService, atLeastOnce()).writeFileResolved(any(), eq("original java content"));
        verify(goalPlannerService, never()).addSubGoal(anyString(), anyString(), anyBoolean());
        assertThat(result.buildOutcome()).isSameAs(prevOk);
        // T1-DX: rolled-back cycle must clear cycle-level state and surface rollback evidence.
        assertThat(result.rolledBackThisCycle()).isTrue();
        assertThat(result.merged()).isFalse();
        assertThat(result.mutationState()).isNull();
        assertThat(result.cycleResultState()).isEqualTo(com.walpro.autobuilder.core.lifecycle.CycleResultState.ROLLED_BACK);
        assertThat(result.lastRollback()).isNotNull();
        assertThat(result.lastRollback().restoredPaths()).contains(relativePath);
        assertThat(result.lastRollback().errorKind()).isNotNull();
        verify(cycleMergeTracker, never()).markCyclesAsMerged(any());
        // T1-DY: rolled-back cycle must NOT mutate persistent goal-entity state — keeps
        // mergeCount=0 so T3-BL's criteria-banked early-exit can't fire on phantom progress.
        verify(goalPlannerService, never()).incrementGoalMergeCount(anyString());
        verify(goalPlannerService, never()).recordMergedFiles(anyString(), anyList());
        verify(behaviourLogger, never()).mutationMerged(anyString(), any(), anyList());
    }

    @Test
    void mergeCompletedBranch_T1DY_committedMergeIncrementsMergeCount_andRecordsMergedFiles() throws Exception {
        // Mirror of the T1-DW test but with the post-merge build returning COMPILE_OK so the
        // commit branch fires. mergeCount, recordMergedFiles, and mutationMerged must all run.
        String cycleId = "cycle-commit";
        String relativePath = "src/main/java/com/example/Foo.java";
        agent.setBehaviourLogger(behaviourLogger);

        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId).outputPath("output").currentSubGoalId("sg-commit")
                .evolutionOutcome(new com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome(
                        List.of(mock(com.walpro.autobuilder.agent.evolution.model.FileMutation.class)),
                        List.of(), null, null))
                .build();

        primeRollbackPipeline(cycleId, relativePath, context, true, "original");

        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(any(), any())).thenReturn(new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_OK, "", 100L, "mvn compile", cycleId));
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());

        agent.mergeCompletedBranch(context);

        // T1-DY: COMPILE_OK → commit fires.
        verify(goalPlannerService).incrementGoalMergeCount("sg-commit");
        verify(goalPlannerService).recordMergedFiles(eq("sg-commit"), eq(List.of(relativePath)));
        verify(behaviourLogger).mutationMerged(eq(cycleId), any(), eq(List.of(relativePath)));
    }

    @Test
    void mergeCompletedBranch_T1DY_buildDisabled_stillCommitsMergeState() throws Exception {
        // Build-disabled path is treated as "implicit OK" — the commit must still fire,
        // otherwise every dev/test flow without the build gate breaks.
        String cycleId = "cycle-no-build";
        String relativePath = "src/main/java/com/example/Foo.java";
        agent.setBehaviourLogger(behaviourLogger);

        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId).outputPath("output").currentSubGoalId("sg-no-build")
                .evolutionOutcome(new com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome(
                        List.of(mock(com.walpro.autobuilder.agent.evolution.model.FileMutation.class)),
                        List.of(), null, null))
                .build();

        primeRollbackPipeline(cycleId, relativePath, context, false, null);
        when(buildExecutor.isEnabled()).thenReturn(false);

        agent.mergeCompletedBranch(context);

        verify(goalPlannerService).incrementGoalMergeCount("sg-no-build");
        verify(goalPlannerService).recordMergedFiles(eq("sg-no-build"), eq(List.of(relativePath)));
        verify(behaviourLogger).mutationMerged(eq(cycleId), any(), eq(List.of(relativePath)));
    }

    @Test
    void mergeCompletedBranch_T1EG_marksContributingMutationsRejectedOnRollback() throws Exception {
        // T1-EG: when a post-merge rollback fires, every mutation directory that contributed a
        // variant to the failed merge must have its meta.json updated to mutationState=REJECTED,
        // so FileVariantCollector skips it on the next cycle. Without this, the deferred ACCEPTED
        // variant remains on disk and is re-applied → re-rolled-back every cycle.
        String cycleId = "cycle-eg-rollback";
        String relativePath = "src/main/java/com/example/Broken.java";
        com.walpro.autobuilder.core.build.BuildOutcome prevOk = new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_OK, "", 50L, "mvn compile", "prev-cycle");

        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId).outputPath("output").currentSubGoalId("sg-eg")
                .buildOutcome(prevOk).build();

        when(cycleMergeTracker.isAlreadyMerged(cycleId)).thenReturn(false);
        when(fileVariantCollector.getAllVariantPaths(any())).thenReturn(Set.of(relativePath));
        when(fileVariantCollector.collect(eq(relativePath), anyList(), any()))
                .thenReturn(List.of(mock(FileVariant.class)));
        when(fileService.exists(anyString())).thenReturn(true);
        when(fileService.readString(anyString())).thenReturn("original");
        when(fileService.resolveSecurePath(anyString())).thenReturn(Path.of("output", relativePath));

        // Two contributing cycles — both must be marked REJECTED.
        MergeResult mr = mock(MergeResult.class);
        when(mr.wasSuccessful()).thenReturn(true);
        when(mr.currentContent()).thenReturn("merged-broken");
        when(mr.contributingCycles()).thenReturn(Set.of("cycle-old-a", "cycle-old-b"));
        when(mergeProcessor.process(eq(relativePath), anyString(), anyList(), eq(true), any()))
                .thenReturn(mr);
        when(cycleMergeTracker.track(any(), anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(cycleMergeTracker.markCyclesAsMerged(any())).thenAnswer(inv -> inv.getArgument(0));

        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(any(), any())).thenReturn(new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR,
                "[ERROR] cannot find symbol", 100L, "mvn compile", cycleId));

        agent.mergeCompletedBranch(context);

        verify(mutationSnapshotService).markMutationRejected("cycle-old-a");
        verify(mutationSnapshotService).markMutationRejected("cycle-old-b");
    }

    @Test
    void mergeCompletedBranch_T1EH_reprobesDeferredMutation_marksRejectedAndSkipsMerge() throws Exception {
        // T1-EH: a deferred ACCEPTED mutation (originCycleId != current cycle) is re-probed
        // against current output. The probe returns COMPILE_ERROR (intervening cycles changed
        // a dependency), so the mutation must be marked REJECTED in meta.json AND skipped —
        // FileVariantCollector now returns no paths so no merge writes happen.
        String currentCycle = "cycle-eh-now";
        String relativePath = "src/main/java/com/example/Stale.java";
        String deferredCycle = "cycle-eh-old";

        CycleContextData context = CycleContextData.builder()
                .cycleName(currentCycle).outputPath("output").currentSubGoalId("sg-eh").build();

        when(cycleMergeTracker.isAlreadyMerged(currentCycle)).thenReturn(false);
        // First pass (pre-probe scan) returns the path; the deferred mutation supplies it.
        // After REJECTED marking the second pass returns empty (variant skipped).
        when(fileVariantCollector.getAllVariantPaths(any()))
                .thenReturn(Set.of(relativePath))     // pre-probe scan
                .thenReturn(Set.of());                 // post-reject refresh

        FileVariant deferredVariant = new FileVariant(
                "stale content", Set.of(), deferredCycle, 0.9);
        when(fileVariantCollector.collect(eq(relativePath), anyList(), eq("sg-eh")))
                .thenReturn(List.of(deferredVariant));

        // Provide one mutation file so buildProbeOutcome assembles a real EvolutionOutcome.
        com.walpro.autobuilder.model.FileInfo info = new com.walpro.autobuilder.model.FileInfo(
                relativePath, 10L, 0L);
        when(fileService.listFiles("mutations/" + deferredCycle)).thenReturn(List.of(info));
        when(fileService.readString("mutations/" + deferredCycle + "/" + relativePath))
                .thenReturn("stale content");

        when(buildExecutor.isEnabled()).thenReturn(true);
        // Probe returns COMPILE_ERROR — deferred mutation no longer compiles against current state.
        when(compileProbeService.probe(any())).thenReturn(
                new com.walpro.autobuilder.core.build.CompileProbeService.ProbeResult(
                        new com.walpro.autobuilder.core.build.BuildOutcome(
                                com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR,
                                "[ERROR] cannot find symbol", 50L, "mvn compile", deferredCycle + "-probe"),
                        null));

        // The merge loop will run on the empty path set after refresh — nothing to merge.
        lenient().when(cycleMergeTracker.markCyclesAsMerged(any())).thenAnswer(inv -> inv.getArgument(0));

        agent.mergeCompletedBranch(context);

        verify(compileProbeService).probe(any());
        verify(mutationSnapshotService).markMutationRejected(deferredCycle);
        // No merge write should occur — the only candidate variant was REJECTED before merge.
        verify(mergeProcessor, never()).process(anyString(), anyString(), anyList(), anyBoolean(), any());
    }

    @Test
    void mergeCompletedBranch_T1EH_doesNotReprobeCurrentCycleMutation() throws Exception {
        // T1-EH must not re-probe a variant that originates from the current cycle (no staleness
        // window). Only deferred mutations (originCycleId != current cycle) trigger re-probing.
        String currentCycle = "cycle-eh-fresh";
        String relativePath = "src/main/java/com/example/Fresh.java";

        CycleContextData context = CycleContextData.builder()
                .cycleName(currentCycle).outputPath("output").currentSubGoalId("sg-fresh").build();

        when(cycleMergeTracker.isAlreadyMerged(currentCycle)).thenReturn(false);
        when(fileVariantCollector.getAllVariantPaths(any())).thenReturn(Set.of(relativePath));

        FileVariant freshVariant = new FileVariant("fresh", Set.of(), currentCycle, 0.9);
        when(fileVariantCollector.collect(eq(relativePath), anyList(), eq("sg-fresh")))
                .thenReturn(List.of(freshVariant));

        when(fileService.exists(anyString())).thenReturn(false);
        when(fileService.resolveSecurePath(anyString())).thenReturn(Path.of("output", relativePath));
        MergeResult mr = mock(MergeResult.class);
        when(mr.wasSuccessful()).thenReturn(true);
        when(mr.currentContent()).thenReturn("fresh");
        when(mergeProcessor.process(eq(relativePath), anyString(), anyList(), eq(false), any())).thenReturn(mr);
        when(cycleMergeTracker.track(any(), anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(cycleMergeTracker.markCyclesAsMerged(any())).thenAnswer(inv -> inv.getArgument(0));
        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(any(), any())).thenReturn(new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_OK, "", 50L, "mvn compile", currentCycle));

        agent.mergeCompletedBranch(context);

        verify(compileProbeService, never()).probe(any());
        verify(mutationSnapshotService, never()).markMutationRejected(anyString());
    }

    @Test
    void mergeCompletedBranch_T1DW_noRollbackWhenPreviousBuildAlreadyFailed() throws Exception {
        // Previous = COMPILE_ERROR → no rollback (already in fix loop), BUILD_FIX path runs.
        String cycleId = "cycle-no-rb";
        String relativePath = "src/main/java/com/example/StillBroken.java";

        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId).outputPath("output").currentSubGoalId("sg-norollback")
                .buildOutcome(prevCompileError()).build();

        primeRollbackPipeline(cycleId, relativePath, context, false, null);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        com.walpro.autobuilder.goal.GoalEntity injected = new com.walpro.autobuilder.goal.GoalEntity();
        injected.setMetadata(new HashMap<>());
        when(goalPlannerService.getGoalById(anyString())).thenReturn(injected);

        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(any(), any())).thenReturn(new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR, "[ERROR] still broken", 100L, "mvn", cycleId));

        agent.mergeCompletedBranch(context);

        verify(goalPlannerService).addSubGoal(anyString(), anyString(), eq(true));
    }

    @Test
    void mergeCompletedBranch_T1DW_noRollbackForBuildConfigMutation() throws Exception {
        // pom.xml mutations are exempt from rollback — T1-DQ / force-accept path applies.
        String cycleId = "cycle-pom-no-rb";
        String relativePath = "pom.xml";
        com.walpro.autobuilder.core.build.BuildOutcome prevOk = new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_OK, "", 50L, "mvn", "prev");

        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId).outputPath("output").currentSubGoalId("sg-pom")
                .buildOutcome(prevOk).build();

        primeRollbackPipeline(cycleId, relativePath, context, false, null);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        com.walpro.autobuilder.goal.GoalEntity injected = new com.walpro.autobuilder.goal.GoalEntity();
        injected.setMetadata(new HashMap<>());
        when(goalPlannerService.getGoalById(anyString())).thenReturn(injected);

        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(any(), any())).thenReturn(new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR, "[ERROR] bad pom", 100L, "mvn", cycleId));

        CycleContextData result = agent.mergeCompletedBranch(context);

        verify(goalPlannerService).addSubGoal(anyString(), anyString(), eq(true));
        assertThat(result.consecutiveBuildFixAttempts()).isEqualTo(1);
    }

    @Test
    void mergeCompletedBranch_T1DW_newlyAddedFileDeletedOnRollback() throws Exception {
        // Newly-added file broke compilation — rollback must delete it (not restore empty content).
        String cycleId = "cycle-new-file-rb";
        String relativePath = "src/main/java/com/example/NewBroken.java";
        com.walpro.autobuilder.core.build.BuildOutcome prevOk = new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_OK, "", 50L, "mvn", "prev");

        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId).outputPath("output").currentSubGoalId("sg-new-broken")
                .buildOutcome(prevOk).build();

        // fileExistsInOutput=false → newly added → rollback deletes it.
        primeRollbackPipeline(cycleId, relativePath, context, false, null);

        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(any(), any())).thenReturn(new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR,
                "[ERROR] NewBroken.java: cannot compile", 100L, "mvn", cycleId));

        agent.mergeCompletedBranch(context);

        verify(fileService, atLeastOnce()).deleteFile(contains(relativePath));
        verify(goalPlannerService, never()).addSubGoal(anyString(), anyString(), anyBoolean());
    }

    /**
     * Prime the cycle-merge-tracker / file / merge-processor mocks for one successful merge
     * of {@code relativePath}, so the build executor branch is reached. Used by T1-DM tests
     * that focus on what happens after the build runs, not on the merge mechanics.
     * Always sets fileExistsInOutput=false (newly-added file).
     */
    private void primeMergePipeline(String cycleId, String relativePath, CycleContextData context) throws IOException {
        when(cycleMergeTracker.isAlreadyMerged(cycleId)).thenReturn(false);
        when(fileVariantCollector.getAllVariantPaths(context)).thenReturn(Set.of(relativePath));
        when(fileVariantCollector.collect(eq(relativePath), anyList(), any()))
                .thenReturn(List.of(mock(FileVariant.class)));
        when(fileService.exists(anyString())).thenReturn(false);
        when(fileService.resolveSecurePath(anyString())).thenReturn(Path.of("output", relativePath));
        MergeResult mr = mock(MergeResult.class);
        when(mr.wasSuccessful()).thenReturn(true);
        when(mr.currentContent()).thenReturn("merged");
        when(mergeProcessor.process(eq(relativePath), anyString(), anyList(), eq(false), any())).thenReturn(mr);
        when(cycleMergeTracker.track(any(), anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(cycleMergeTracker.markCyclesAsMerged(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** T1-DW: helpers for testing rollback — separate from primeMergePipeline to avoid stub conflicts. */
    private void primeRollbackPipeline(String cycleId, String relativePath, CycleContextData context,
                                       boolean fileExistsInOutput, String originalContent) throws IOException {
        when(cycleMergeTracker.isAlreadyMerged(cycleId)).thenReturn(false);
        when(fileVariantCollector.getAllVariantPaths(any())).thenReturn(Set.of(relativePath));
        when(fileVariantCollector.collect(eq(relativePath), anyList(), any()))
                .thenReturn(List.of(mock(FileVariant.class)));
        when(fileService.exists(anyString())).thenReturn(fileExistsInOutput);
        if (fileExistsInOutput) {
            when(fileService.readString(anyString())).thenReturn(originalContent);
        }
        when(fileService.resolveSecurePath(anyString())).thenReturn(Path.of("output", relativePath));
        MergeResult mr = mock(MergeResult.class);
        when(mr.wasSuccessful()).thenReturn(true);
        when(mr.currentContent()).thenReturn("merged-broken");
        when(mergeProcessor.process(eq(relativePath), anyString(), anyList(), eq(fileExistsInOutput), any()))
                .thenReturn(mr);
        when(cycleMergeTracker.track(any(), anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        // T1-DX: rollback path skips markCyclesAsMerged entirely — mark this stub lenient so
        // tests that exercise the rollback branch don't fail with UnnecessaryStubbing.
        lenient().when(cycleMergeTracker.markCyclesAsMerged(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static com.walpro.autobuilder.core.build.BuildOutcome prevCompileError() {
        return new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR, "[ERROR] was already broken",
                50L, "mvn compile", "prev-cycle");
    }

    @Test
    void mergeCompletedBranch_T4AN1_skipsBuildExecutorWhenDisabled() throws Exception {
        String cycleId = "cycle-build-off";
        String outputPath = "output";
        String relativePath = "src/main/java/com/example/Bar.java";

        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath(outputPath)
                .currentSubGoalId("sg-build-off")
                .build();

        when(cycleMergeTracker.isAlreadyMerged(cycleId)).thenReturn(false);
        when(fileVariantCollector.getAllVariantPaths(context)).thenReturn(Set.of(relativePath));
        List<FileVariant> variants = List.of(mock(FileVariant.class));
        when(fileVariantCollector.collect(eq(relativePath), anyList(), any())).thenReturn(variants);
        when(fileService.exists(anyString())).thenReturn(false);
        when(fileService.resolveSecurePath(anyString())).thenReturn(Path.of(outputPath, relativePath));

        MergeResult mergeResult = mock(MergeResult.class);
        when(mergeResult.wasSuccessful()).thenReturn(true);
        when(mergeResult.currentContent()).thenReturn("merged");
        when(mergeProcessor.process(eq(relativePath), anyString(), eq(variants), eq(false), any(CycleContextData.class)))
                .thenReturn(mergeResult);
        when(cycleMergeTracker.track(any(), anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(cycleMergeTracker.markCyclesAsMerged(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        when(buildExecutor.isEnabled()).thenReturn(false);

        CycleContextData result = agent.mergeCompletedBranch(context);

        verify(buildExecutor, never()).execute(any(), any());
        assertThat(result.buildOutcome()).isNull();
    }

    @Test
    void execute_delegatesToMergeCompletedBranch_andWrapsIOException() throws Exception {
        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-io")
                .outputPath("output")
                .build()
                .withSignal(Signal.of(BasicSignals.ROLE_MERGE, 0.5, 0.1, "test"));

        BlockerSubgoalInjector inj = new BlockerSubgoalInjector(goalPlannerService);
        MergeRollbackService rollbackSvc = new MergeRollbackService();
        MergeAgent spyCoordinator = spy(new MergeAgent(
                fileService,
                fileVariantCollector,
                mergeProcessor,
                cycleMergeTracker,
                mergeActionRegistrar,
                goalPlannerService,
                buildExecutor,
                inj,
                rollbackSvc,
                mutationSnapshotService,
                compileProbeService
        ));

        // Happy path: execute() returns what mergeCompletedBranch returns
        CycleContextData merged = CycleContextData.builder()
                .cycleName("cycle-io-merged")
                .outputPath("output")
                .build();

        when(spyCoordinator.mergeCompletedBranch(context)).thenReturn(merged);

        CycleContextData result = spyCoordinator.execute(context);
        assertThat(result).isSameAs(merged);

        // IOException wrapping
        MergeAgent throwingSpy = spy(new MergeAgent(
                fileService,
                fileVariantCollector,
                mergeProcessor,
                cycleMergeTracker,
                mergeActionRegistrar,
                goalPlannerService,
                buildExecutor,
                inj,
                rollbackSvc,
                mutationSnapshotService,
                compileProbeService
        ));

        when(throwingSpy.mergeCompletedBranch(context))
                .thenThrow(new IOException("boom"));

        Throwable thrown = catchThrowable(() -> throwingSpy.execute(context));
        assertThat(thrown)
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void mergeCompletedBranch_consumesRoleMergeSignal() throws Exception {
        String cycleId = "cycle-signal";
        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath("output")
                .currentSubGoalId("sg-test")
                .build()
                .withSignal(Signal.of(BasicSignals.ROLE_MERGE, 0.5, 0.1, "test"));

        when(cycleMergeTracker.isAlreadyMerged(cycleId)).thenReturn(false);
        when(fileVariantCollector.getAllVariantPaths(context)).thenReturn(Set.of()); // no work

        CycleContextData marked = context; // assume mark returns same instance in this test
        when(cycleMergeTracker.markCyclesAsMerged(any(CycleContextData.class))).thenReturn(marked);

        assertThat(context.hasSignal(BasicSignals.ROLE_MERGE)).isTrue();

        CycleContextData result = agent.mergeCompletedBranch(context);

        assertThat(result.hasSignal(BasicSignals.ROLE_MERGE)).isFalse();
        verify(cycleMergeTracker).markCyclesAsMerged(context);
    }

    @Test
    void mergeCompletedBranch_whenFileExists_readsBaseContentAndPassesItToProcessor() throws Exception {
        String cycleId = "cycle-base";
        String outputPath = "output";
        String relativePath = "pom.xml";

        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath(outputPath)
                .currentSubGoalId("sg-test")
                .build();

        when(cycleMergeTracker.isAlreadyMerged(cycleId)).thenReturn(false);
        when(fileVariantCollector.getAllVariantPaths(context)).thenReturn(Set.of(relativePath));

        List<FileVariant> variants = List.of(mock(FileVariant.class));
        when(fileVariantCollector.collect(eq(relativePath), anyList(), any())).thenReturn(variants);

        String outputFileRel = outputPath + "/" + relativePath;

        when(fileService.exists(outputFileRel)).thenReturn(true);
        when(fileService.readString(outputFileRel)).thenReturn("existing-content");

        MergeResult mergeResult = mock(MergeResult.class);
        when(mergeResult.wasSuccessful()).thenReturn(true);
        when(mergeResult.currentContent()).thenReturn("merged-content");

        when(mergeProcessor.process(
                eq(relativePath),
                eq("existing-content"),
                eq(variants),
                eq(true),
                eq(context)
        )).thenReturn(mergeResult);

        Path resolved = Path.of(outputPath, relativePath);
        when(fileService.resolveSecurePath(outputFileRel)).thenReturn(resolved);

        when(cycleMergeTracker.track(any(CycleContextData.class), anyString(), any(MergeResult.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(cycleMergeTracker.markCyclesAsMerged(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CycleContextData result = agent.mergeCompletedBranch(context);

        verify(fileService).readString(outputFileRel);
        verify(mergeProcessor).process(relativePath, "existing-content", variants, true, context);
        verify(fileService).writeFileResolved(resolved, "merged-content");
        verify(cycleMergeTracker).markCyclesAsMerged(context);

        assertThat(result).isSameAs(context);
    }

    @Test
    void mergeCompletedBranch_passesAlreadyMergedCyclesToCollector() throws Exception {
        String cycleId = "cycle-trace";
        String outputPath = "output";
        String relativePath = "pom.xml";

        // Build a context that has mergeTrace mergedCycles = ["cycle-old-1", "cycle-old-2"]
        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath(outputPath)
                .currentSubGoalId("sg-test")
                .build();

        context = context.withMergeTrace(
                new MergeTrace(List.of("cycle-old-1", "cycle-old-2"), new HashMap<>(), new HashMap<>())
        );

        when(cycleMergeTracker.isAlreadyMerged(cycleId)).thenReturn(false);
        when(cycleMergeTracker.getMergedCycles(context)).thenReturn(List.of("cycle-old-1", "cycle-old-2"));
        when(fileVariantCollector.getAllVariantPaths(context)).thenReturn(Set.of(relativePath));

        when(fileVariantCollector.collect(eq(relativePath), eq(List.of("cycle-old-1", "cycle-old-2")), any()))
                .thenReturn(List.of()); // empty so it stops early

        when(cycleMergeTracker.markCyclesAsMerged(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        agent.mergeCompletedBranch(context);

        verify(fileVariantCollector).collect(eq(relativePath), eq(List.of("cycle-old-1", "cycle-old-2")), any());
    }

    @Test
    void mergeCompletedBranch_whenCollectorThrows_continuesWithOtherPaths() throws Exception {
        String cycleId = "cycle-collect-io";
        String outputPath = "output";
        String badPath = "a.txt";
        String okPath = "b.txt";

        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath(outputPath)
                .currentSubGoalId("sg-test")
                .build();

        when(cycleMergeTracker.isAlreadyMerged(cycleId)).thenReturn(false);
        when(fileVariantCollector.getAllVariantPaths(context)).thenReturn(Set.of(badPath, okPath));

        when(fileVariantCollector.collect(eq(badPath), anyList(), any()))
                .thenThrow(new java.io.IOException("boom"));

        List<FileVariant> okVariants = List.of(mock(FileVariant.class));
        when(fileVariantCollector.collect(eq(okPath), anyList(), any())).thenReturn(okVariants);

        when(fileService.exists(anyString())).thenReturn(false);

        MergeResult ok = MergeResult.asAdopted("", "merged", Set.of(cycleId));
        when(mergeProcessor.process(eq(okPath), eq(""), eq(okVariants), eq(false), eq(context))).thenReturn(ok);

        Path resolvedOk = Path.of(outputPath, okPath);
        when(fileService.resolveSecurePath(eq(outputPath + "/" + okPath))).thenReturn(resolvedOk);

        when(cycleMergeTracker.track(any(CycleContextData.class), anyString(), any(MergeResult.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(cycleMergeTracker.markCyclesAsMerged(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        agent.mergeCompletedBranch(context);

        verify(mergeProcessor).process(eq(okPath), eq(""), eq(okVariants), eq(false), eq(context));
        verify(fileService).writeFileResolved(resolvedOk, "merged");
        verify(cycleMergeTracker).markCyclesAsMerged(context);
    }

    @Test
    void mergeCompletedBranch_whenWriteFails_doesNotTrackThatPath() throws Exception {
        String cycleId = "cycle-write-io";
        String outputPath = "output";
        String path = "pom.xml";

        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath(outputPath)
                .currentSubGoalId("sg-test")
                .build();

        when(cycleMergeTracker.isAlreadyMerged(cycleId)).thenReturn(false);
        when(fileVariantCollector.getAllVariantPaths(context)).thenReturn(Set.of(path));

        List<FileVariant> variants = List.of(mock(FileVariant.class));
        when(fileVariantCollector.collect(eq(path), anyList(), any())).thenReturn(variants);

        when(fileService.exists(anyString())).thenReturn(false);

        MergeResult ok = MergeResult.asAdopted("", "merged", Set.of(cycleId));
        when(mergeProcessor.process(eq(path), eq(""), eq(variants), eq(false), eq(context))).thenReturn(ok);

        Path resolved = Path.of(outputPath, path);
        when(fileService.resolveSecurePath(eq(outputPath + "/" + path))).thenReturn(resolved);

        doThrow(new java.io.IOException("disk full"))
                .when(fileService).writeFileResolved(resolved, "merged");

        when(cycleMergeTracker.markCyclesAsMerged(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        agent.mergeCompletedBranch(context);

        verify(cycleMergeTracker, never()).track(any(), anyString(), any());
        verify(cycleMergeTracker).markCyclesAsMerged(context);
    }

    @Test
    void mergeCompletedBranch_mergesTwoFiles_writesBoth_andMarksMerged() throws Exception {
        String cycleId = "cycle-2";
        String outputPath = "output";

        String pathA = "pom.xml";
        String pathB = "src/main/java/com/example/HelloController.java";

        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath(outputPath)
                .currentSubGoalId("sg-test")
                .build();

        when(cycleMergeTracker.isAlreadyMerged(cycleId)).thenReturn(false);

        // Two files to merge
        when(fileVariantCollector.getAllVariantPaths(context))
                .thenReturn(new java.util.LinkedHashSet<>(List.of(pathA, pathB)));

        List<FileVariant> variantsA = List.of(mock(FileVariant.class));
        List<FileVariant> variantsB = List.of(mock(FileVariant.class));

        when(fileVariantCollector.collect(eq(pathA), anyList(), any())).thenReturn(variantsA);
        when(fileVariantCollector.collect(eq(pathB), anyList(), any())).thenReturn(variantsB);

        // Assume both files do NOT exist in output yet
        when(fileService.exists(eq("output/" + pathA))).thenReturn(false);
        when(fileService.exists(eq("output/" + pathB))).thenReturn(false);

        // So baseContent should be empty (readString shouldn't be called, but safe to stub)
//        when(fileService.readString(anyString())).thenReturn("");

        // Merge results: both successful
        MergeResult resultA = MergeResult.asAdopted("", "merged-pom", Set.of(cycleId));
        MergeResult resultB = MergeResult.asAdopted("", "merged-controller", Set.of(cycleId));

        when(mergeProcessor.process(eq(pathA), eq(""), eq(variantsA), eq(false), any(CycleContextData.class)))
                .thenReturn(resultA);
        when(mergeProcessor.process(eq(pathB), eq(""), eq(variantsB), eq(false), any(CycleContextData.class)))
                .thenReturn(resultB);

        // write target paths must be resolved per call; easiest: return the actual intended path
        Path resolvedA = Path.of(outputPath, pathA);
        Path resolvedB = Path.of(outputPath, pathB);

        when(fileService.resolveSecurePath(eq(outputPath + "/" + pathA))).thenReturn(resolvedA);
        when(fileService.resolveSecurePath(eq(outputPath + "/" + pathB))).thenReturn(resolvedB);

        // tracking returns same context for simplicity
        when(cycleMergeTracker.track(any(CycleContextData.class), anyString(), any(MergeResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(cycleMergeTracker.markCyclesAsMerged(any(CycleContextData.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        CycleContextData result = agent.mergeCompletedBranch(context);

        // Assert: process called for both
        verify(mergeProcessor).process(eq(pathA), eq(""), eq(variantsA), eq(false), any(CycleContextData.class));
        verify(mergeProcessor).process(eq(pathB), eq(""), eq(variantsB), eq(false), any(CycleContextData.class));

        // Assert: writes happened for both files
        verify(fileService).writeFileResolved(resolvedA, "merged-pom");
        verify(fileService).writeFileResolved(resolvedB, "merged-controller");

        // Assert: tracker called for both
        verify(cycleMergeTracker).track(eq(context), eq(pathA), eq(resultA));
        verify(cycleMergeTracker).track(eq(context), eq(pathB), eq(resultB));

        // Final: marked merged
        verify(cycleMergeTracker).markCyclesAsMerged(context);

        assertThat(result).isSameAs(context);
    }

    @Test
    void mergeCompletedBranch_skipsFailedMerge_butStillWritesSuccessfulFiles_andMarksMerged() throws Exception {
        String cycleId = "cycle-3";
        String outputPath = "output";

        String okPath = "pom.xml";
        String badPath = "src/main/java/com/example/Bad.java";

        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath(outputPath)
                .currentSubGoalId("sg-test")
                .build();

        when(cycleMergeTracker.isAlreadyMerged(cycleId)).thenReturn(false);
        when(fileVariantCollector.getAllVariantPaths(context)).thenReturn(Set.of(okPath, badPath));

        List<FileVariant> okVariants = List.of(mock(FileVariant.class));
        List<FileVariant> badVariants = List.of(mock(FileVariant.class));

        when(fileVariantCollector.collect(eq(okPath), anyList(), any())).thenReturn(okVariants);
        when(fileVariantCollector.collect(eq(badPath), anyList(), any())).thenReturn(badVariants);

        when(fileService.exists(anyString())).thenReturn(false);

        MergeResult ok = MergeResult.asAdopted("", "merged-ok", Set.of(cycleId));
        MergeResult failed = MergeResult.asFailed("merge conflict");

        when(mergeProcessor.process(eq(okPath), eq(""), eq(okVariants), eq(false), any(CycleContextData.class)))
                .thenReturn(ok);
        when(mergeProcessor.process(eq(badPath), eq(""), eq(badVariants), eq(false), any(CycleContextData.class)))
                .thenReturn(failed);

        Path resolvedOk = Path.of(outputPath, okPath);
        when(fileService.resolveSecurePath(eq(outputPath + "/" + okPath))).thenReturn(resolvedOk);

        when(cycleMergeTracker.track(any(CycleContextData.class), anyString(), any(MergeResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(cycleMergeTracker.markCyclesAsMerged(any(CycleContextData.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CycleContextData result = agent.mergeCompletedBranch(context);

        // ok file written
        verify(fileService).writeFileResolved(resolvedOk, "merged-ok");

        // bad file NOT written
        verify(fileService, never()).writeFileResolved(eq(Path.of(outputPath, badPath)), anyString());

        // tracker invoked only for successful file
        verify(cycleMergeTracker).track(eq(context), eq(okPath), eq(ok));
        verify(cycleMergeTracker, never()).track(eq(context), eq(badPath), eq(failed));

        verify(cycleMergeTracker).markCyclesAsMerged(context);

        assertThat(result).isSameAs(context);
    }

    @Test
    void mergeCompletedBranch_accumulatesContextReturnedByTrack() throws Exception {
        String cycleId = "cycle-track-acc";
        String outputPath = "output";
        String pathA = "a.txt";
        String pathB = "b.txt";

        CycleContextData ctx0 = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath(outputPath)
                .currentSubGoalId("sg-test")
                .build();

        when(cycleMergeTracker.isAlreadyMerged(cycleId)).thenReturn(false);
        when(fileVariantCollector.getAllVariantPaths(ctx0))
                .thenReturn(new java.util.LinkedHashSet<>(List.of(pathA, pathB))); // ordered: pathA first

        List<FileVariant> variantsA = List.of(mock(FileVariant.class));
        List<FileVariant> variantsB = List.of(mock(FileVariant.class));

        when(fileVariantCollector.collect(eq(pathA), anyList(), any())).thenReturn(variantsA);
        when(fileVariantCollector.collect(eq(pathB), anyList(), any())).thenReturn(variantsB);

        when(fileService.exists(anyString())).thenReturn(false);

        MergeResult rA = MergeResult.asAdopted("", "A", Set.of(cycleId));
        MergeResult rB = MergeResult.asAdopted("", "B", Set.of(cycleId));

        when(mergeProcessor.process(eq(pathA), eq(""), eq(variantsA), eq(false), any(CycleContextData.class))).thenReturn(rA);
        when(mergeProcessor.process(eq(pathB), eq(""), eq(variantsB), eq(false), any(CycleContextData.class))).thenReturn(rB);

        when(fileService.resolveSecurePath(eq(outputPath + "/" + pathA))).thenReturn(Path.of(outputPath, pathA));
        when(fileService.resolveSecurePath(eq(outputPath + "/" + pathB))).thenReturn(Path.of(outputPath, pathB));

        CycleContextData ctx1 = CycleContextData.builder().cycleName(cycleId).outputPath(outputPath).build(); // pretend enriched
        CycleContextData ctx2 = CycleContextData.builder().cycleName(cycleId).outputPath(outputPath).build(); // enriched again

        when(cycleMergeTracker.track(eq(ctx0), eq(pathA), eq(rA))).thenReturn(ctx1);
        when(cycleMergeTracker.track(eq(ctx1), eq(pathB), eq(rB))).thenReturn(ctx2);

        when(cycleMergeTracker.markCyclesAsMerged(eq(ctx2))).thenReturn(ctx2);

        agent.mergeCompletedBranch(ctx0);

        // Key assertions: the second track must receive ctx1, not ctx0
        verify(cycleMergeTracker).track(ctx0, pathA, rA);
        verify(cycleMergeTracker).track(ctx1, pathB, rB);
        verify(cycleMergeTracker).markCyclesAsMerged(ctx2);
    }

    // Dynamic MergeAgent test
    @Test
    void mergeCompletedBranch_updatesMergedCyclesBetweenPaths() throws Exception {
        String cycleId = "cycle-dyn";
        String outputPath = "output";
        String pathA = "a.txt";
        String pathB = "b.txt";

        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath(outputPath)
                .currentSubGoalId("sg-test")
                .build()
                .withMergeTrace(new MergeTrace(
                        new java.util.ArrayList<>(List.of("cycle-old")),
                        new HashMap<>(),
                        new HashMap<>()
                ));

        when(cycleMergeTracker.isAlreadyMerged(cycleId)).thenReturn(false);
        when(fileVariantCollector.getAllVariantPaths(context)).thenReturn(new java.util.LinkedHashSet<>(List.of(pathA, pathB)));

        // Key: getMergedCycles returns different lists per iteration
        when(cycleMergeTracker.getMergedCycles(context))
                .thenReturn(List.of("cycle-old"))
                .thenReturn(List.of("cycle-old", "cycle-new"));

        when(fileVariantCollector.collect(eq(pathA), eq(List.of("cycle-old")), any()))
                .thenReturn(List.of(mock(FileVariant.class)));

        when(fileVariantCollector.collect(eq(pathB), eq(List.of("cycle-old")), any()))
                .thenReturn(List.of(mock(FileVariant.class)));

        when(fileService.exists(anyString())).thenReturn(false);

        MergeResult okA = MergeResult.asAdopted("", "A", Set.of("cycle-new"));
        MergeResult okB = MergeResult.asAdopted("", "B", Set.of("cycle-new"));

        when(mergeProcessor.process(eq(pathA), eq(""), anyList(), eq(false), eq(context))).thenReturn(okA);
        when(mergeProcessor.process(eq(pathB), eq(""), anyList(), eq(false), any(CycleContextData.class))).thenReturn(okB);

        when(fileService.resolveSecurePath(eq(outputPath + "/" + pathA))).thenReturn(Path.of(outputPath, pathA));
        when(fileService.resolveSecurePath(eq(outputPath + "/" + pathB))).thenReturn(Path.of(outputPath, pathB));

        // track mutates the trace in-place (real behavior)
        when(cycleMergeTracker.track(eq(context), eq(pathA), eq(okA)))
                .thenAnswer(inv -> {
                    CycleContextData ctx = inv.getArgument(0);
                    ctx.mergeTrace().mergedCycles().addAll(okA.contributingCycles());
                    return ctx;
                });

        when(cycleMergeTracker.track(any(CycleContextData.class), eq(pathB), eq(okB)))
                .thenAnswer(inv -> inv.getArgument(0));

        when(cycleMergeTracker.markCyclesAsMerged(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        agent.mergeCompletedBranch(context);

        verify(fileVariantCollector).collect(eq(pathA), eq(List.of("cycle-old")), any());
        verify(fileVariantCollector).collect(eq(pathB), eq(List.of("cycle-old")), any());

        verify(cycleMergeTracker, times(1)).getMergedCycles(context);
    }

    // ── T2-DB: skip per-file merges when prior cycle rolled back and mutation doesn't address it ──

    @Test
    void mergeCompletedBranch_T2DB_skipsAllMergesWhenPriorCycleRolledBackAndMutationDoesNotAddressTarget() throws Exception {
        // Prior cycle: MISSING_POM rollback. Restored paths included pom.xml. Current
        // mutation only contains Java files — does NOT include pom.xml. Guard fires:
        // no merges, no build run, no rollback noise.
        com.walpro.autobuilder.core.merge.RolledBackEvidence rb = new com.walpro.autobuilder.core.merge.RolledBackEvidence(
                "cycle-prior",
                List.of("pom.xml", "src/main/java/com/example/Foo.java"),
                "no POM in this directory",
                com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind.MISSING_POM);

        com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome eo = new com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome(
                List.of(new com.walpro.autobuilder.agent.evolution.model.FileMutation(
                        "src/main/java/com/example/BookController.java", "class BookController {}")),
                List.of(), null, null);

        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-skip")
                .outputPath("output")
                .currentSubGoalId("sg-bf")
                .lastRollback(rb)
                .evolutionOutcome(eo)
                .build();

        CycleContextData result = agent.mergeCompletedBranch(context);

        assertThat(result).isSameAs(context);
        verifyNoInteractions(cycleMergeTracker, fileVariantCollector, mergeProcessor, fileService, buildExecutor);
    }

    @Test
    void mergeCompletedBranch_T2DB_proceedsWhenMutationContainsPomXmlForMissingPomRollback() throws Exception {
        // Prior cycle: MISSING_POM rollback. Current mutation INCLUDES pom.xml. Guard does
        // NOT fire — merge proceeds. (We just need to verify the guard didn't short-circuit;
        // the merge work below is exercised via the variant collector.)
        com.walpro.autobuilder.core.merge.RolledBackEvidence rb = new com.walpro.autobuilder.core.merge.RolledBackEvidence(
                "cycle-prior",
                List.of("pom.xml"),
                "no POM in this directory",
                com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind.MISSING_POM);

        com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome eo = new com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome(
                List.of(new com.walpro.autobuilder.agent.evolution.model.FileMutation(
                        "pom.xml", "<project></project>")),
                List.of(), null, null);

        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-proceed")
                .outputPath("output")
                .currentSubGoalId("sg-bf")
                .lastRollback(rb)
                .evolutionOutcome(eo)
                .build();

        when(cycleMergeTracker.isAlreadyMerged(anyString())).thenReturn(false);
        when(fileVariantCollector.getAllVariantPaths(any())).thenReturn(Set.of());
        when(cycleMergeTracker.markCyclesAsMerged(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        agent.mergeCompletedBranch(context);

        // Guard didn't short-circuit — variant collection ran.
        verify(fileVariantCollector).getAllVariantPaths(any());
    }

    @Test
    void mergeCompletedBranch_T2DB_proceedsWhenMutationOverlapsRestoredPaths() throws Exception {
        // Prior cycle: UNKNOWN-kind rollback restoring Foo.java + Bar.java. Current mutation
        // includes Foo.java — guard does NOT fire even though the kind has no preferred target.
        com.walpro.autobuilder.core.merge.RolledBackEvidence rb = new com.walpro.autobuilder.core.merge.RolledBackEvidence(
                "cycle-prior",
                List.of("src/main/java/com/example/Foo.java", "src/main/java/com/example/Bar.java"),
                "cannot find symbol",
                com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind.UNKNOWN);

        com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome eo = new com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome(
                List.of(new com.walpro.autobuilder.agent.evolution.model.FileMutation(
                        "src/main/java/com/example/Foo.java", "class Foo {}")),
                List.of(), null, null);

        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-overlap")
                .outputPath("output")
                .currentSubGoalId("sg-bf")
                .lastRollback(rb)
                .evolutionOutcome(eo)
                .build();

        when(cycleMergeTracker.isAlreadyMerged(anyString())).thenReturn(false);
        when(fileVariantCollector.getAllVariantPaths(any())).thenReturn(Set.of());
        when(cycleMergeTracker.markCyclesAsMerged(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        agent.mergeCompletedBranch(context);

        verify(fileVariantCollector).getAllVariantPaths(any());
    }

    @Test
    void mergeCompletedBranch_T2DB_proceedsWhenLastRollbackIsNull() throws Exception {
        // No prior rollback evidence — guard inactive, merges proceed as usual. Verifies
        // the healthy-path behaviour is unchanged.
        com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome eo = new com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome(
                List.of(new com.walpro.autobuilder.agent.evolution.model.FileMutation(
                        "src/main/java/com/example/Anything.java", "class Anything {}")),
                List.of(), null, null);

        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-clean")
                .outputPath("output")
                .currentSubGoalId("sg-feat")
                .evolutionOutcome(eo)
                .build();

        when(cycleMergeTracker.isAlreadyMerged(anyString())).thenReturn(false);
        when(fileVariantCollector.getAllVariantPaths(any())).thenReturn(Set.of());
        when(cycleMergeTracker.markCyclesAsMerged(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        agent.mergeCompletedBranch(context);

        verify(fileVariantCollector).getAllVariantPaths(any());
    }

    @Test
    void mutationAddressesRollback_T2DB_returnsFalseWhenEvolutionOutcomeIsNull() {
        com.walpro.autobuilder.core.merge.RolledBackEvidence rb = new com.walpro.autobuilder.core.merge.RolledBackEvidence(
                "c", List.of("pom.xml"), "no POM", com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind.MISSING_POM);

        assertThat(MergeAgent.mutationAddressesRollback(null, rb)).isFalse();
    }

    @Test
    void mutationAddressesRollback_T2DB_returnsTrueForKindDerivedTarget() {
        com.walpro.autobuilder.core.merge.RolledBackEvidence rb = new com.walpro.autobuilder.core.merge.RolledBackEvidence(
                "c", List.of("Other.java"), "no POM", com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind.MISSING_POM);
        com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome eo = new com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome(
                List.of(new com.walpro.autobuilder.agent.evolution.model.FileMutation("pom.xml", "<x/>")),
                List.of(), null, null);

        // MISSING_POM's preferredRepairTarget is "pom.xml" — basename match wins even though
        // restoredPaths only has "Other.java".
        assertThat(MergeAgent.mutationAddressesRollback(eo, rb)).isTrue();
    }
}
