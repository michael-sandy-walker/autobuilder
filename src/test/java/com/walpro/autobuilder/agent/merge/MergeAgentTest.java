package com.walpro.autobuilder.agent.merge;

import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.core.build.BlockerTypes;
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

    protected MergeAgent agent;

    @BeforeEach
    void setUp() {
        agent = new MergeAgent(
                fileService,
                fileVariantCollector,
                mergeProcessor,
                cycleMergeTracker,
                mergeActionRegistrar,
                goalPlannerService,
                buildExecutor
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

        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath(outputPath)
                .currentSubGoalId("sg-build")
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
        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId).outputPath("output").currentSubGoalId("sg-stuck").build();

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
        assertThat(descCaptor.getValue()).startsWith("Fix compile error:").contains("pom.xml not found");
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
                .cycleName(cycleId).outputPath("output").currentSubGoalId("sg-banner").build();

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
        assertThat(descCaptor.getValue())
                .startsWith("Fix compile error:")
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
                .cycleName(cycleId).outputPath("output").currentSubGoalId("sg-existing-fix").build();

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

        agent.mergeCompletedBranch(context);

        // BUILD_FIX is gated specifically on COMPILE_ERROR — no injection on COMPILE_OK.
        verify(goalPlannerService, never()).addSubGoal(anyString(), anyString(), anyBoolean());
        // Idempotency lookup is also unnecessary on success; assert it was skipped.
        verify(goalPlannerService, never()).getCurrentSubGoal();
    }

    @Test
    void mergeCompletedBranch_T2CT_unrecognisedError_keepsGenericCriteria() throws Exception {
        // T2-CT: when the stderr does not match any known pattern, criteria fall back to the
        // generic text so existing behaviour is preserved (zero regression for unobserved errors).
        String cycleId = "cycle-bf-unknown";
        String relativePath = "src/main/java/com/example/Foo.java";
        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId).outputPath("output").currentSubGoalId("sg-unknown").build();

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

    /**
     * Prime the cycle-merge-tracker / file / merge-processor mocks for one successful merge
     * of {@code relativePath}, so the build executor branch is reached. Used by T1-DM tests
     * that focus on what happens after the build runs, not on the merge mechanics.
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

        MergeAgent spyCoordinator = spy(new MergeAgent(
                fileService,
                fileVariantCollector,
                mergeProcessor,
                cycleMergeTracker,
                mergeActionRegistrar,
                goalPlannerService,
                buildExecutor
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
                buildExecutor
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
}
