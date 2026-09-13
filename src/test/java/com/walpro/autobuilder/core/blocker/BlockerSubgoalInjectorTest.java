package com.walpro.autobuilder.core.blocker;

import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.build.BuildErrorClassifier;
import com.walpro.autobuilder.core.build.BuildErrorClassifierAgent;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlockerSubgoalInjectorTest {

    @Mock
    private GoalPlannerService goalPlannerService;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private BuildErrorClassifierAgent buildErrorClassifierAgent;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private com.walpro.autobuilder.core.service.FileService fileService;

    private BlockerSubgoalInjector injector;

    @BeforeEach
    void setUp() throws java.io.IOException {
        // T4-BD: pattern-fallback delegation — keep test expectations identical to the prior
        // self-contained behaviour (the injector used to call BuildErrorClassifier.classify
        // directly). The adaptive LLM path is exercised via the agent's own unit tests.
        when(buildErrorClassifierAgent.classify(anyString()))
                .thenAnswer(inv -> BuildErrorClassifier.classify(inv.getArgument(0)));
        // T1-EX: default the phantom-diagnostic guard to "file not on disk yet" so existing tests
        // (which describe creation scenarios) keep firing the injection path. Tests exercising the
        // phantom-diagnostic skip branch override exists/readString explicitly.
        when(fileService.exists(anyString())).thenReturn(false);
        injector = new BlockerSubgoalInjector(goalPlannerService, buildErrorClassifierAgent, fileService, com.walpro.autobuilder.core.build.TestBuildToolRegistry.classifier());
    }

    // ── injectBuildFixSubgoalIfAbsent ──

    @Test
    void inject_createsSubgoalWithSetAsCurrent_andWritesMetadata() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());

        GoalEntity injected = goalEntityWithMeta(new HashMap<>());
        when(goalPlannerService.getGoalById(anyString())).thenReturn(injected);

        BuildOutcome outcome = buildOutcome("[ERROR] /data/firestarter/pom.xml not found");
        injector.injectBuildFixSubgoalIfAbsent(outcome, java.util.List.of());

        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> descCaptor = ArgumentCaptor.forClass(String.class);
        verify(goalPlannerService).addSubGoal(idCaptor.capture(), descCaptor.capture(), eq(true));

        assertThat(idCaptor.getValue()).startsWith("goal-");
        assertThat(descCaptor.getValue()).startsWith("Fix compile error:").contains("pom.xml not found");

        assertThat(injected.getMetadata())
                .containsEntry(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX);
        verify(goalPlannerService).saveGoalMetadata(injected);
        verify(goalPlannerService).setAcceptanceCriteria(eq(idCaptor.getValue()), anyList());
    }

    @Test
    void inject_idempotent_skipsWhenActiveBuildFixAlreadyOnCurrentGoal() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(activeBuildFixGoal());

        injector.injectBuildFixSubgoalIfAbsent(buildOutcome("[ERROR] still broken"), java.util.List.of());

        verify(goalPlannerService, never()).addSubGoal(anyString(), anyString(), anyBoolean());
        verify(goalPlannerService, never()).setAcceptanceCriteria(anyString(), anyList());
    }

    @Test
    void inject_idempotent_skipsWhenActiveBuildFixInRemainingGoals() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(activeBuildFixGoal()));

        injector.injectBuildFixSubgoalIfAbsent(buildOutcome("[ERROR] still broken"), java.util.List.of());

        verify(goalPlannerService, never()).addSubGoal(anyString(), anyString(), anyBoolean());
    }

    // ── T2-EF: symbol-centric recurring-defect redirection ──

    @Test
    void injectRecurringDefectRedirection_createsSymbolCentricSubgoal() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        GoalEntity injected = goalEntityWithMeta(new HashMap<>());
        when(goalPlannerService.getGoalById(anyString())).thenReturn(injected);

        injector.injectRecurringDefectRedirection("CANNOT_FIND_SYMBOL", "Member", 7, true);

        ArgumentCaptor<String> descCaptor = ArgumentCaptor.forClass(String.class);
        verify(goalPlannerService).addSubGoal(anyString(), descCaptor.capture(), eq(true));
        // Symbol-centric ("create the type"), names the symbol + the cross-subgoal span, and
        // surfaces the standing-fix knowledge (hadGreenFix=true) in the hint.
        assertThat(descCaptor.getValue())
                .contains("Member")
                .contains("7 subgoals")
                .contains("import for Member");
        assertThat(injected.getMetadata()).containsEntry(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX);
        verify(goalPlannerService).saveGoalMetadata(injected);
        verify(goalPlannerService).setAcceptanceCriteria(anyString(), anyList());
    }

    @Test
    void injectRecurringDefectRedirection_skipsWhenBuildFixAlreadyActive() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(activeBuildFixGoal());
        injector.injectRecurringDefectRedirection("CANNOT_FIND_SYMBOL", "Member", 7, false);
        verify(goalPlannerService, never()).addSubGoal(anyString(), anyString(), anyBoolean());
    }

    // ── T2-CY: BUILD_FIX subgoal description names the actually-failed file ──

    @Test
    void inject_extractsFilePathFromStderr_andEmbedsInDescription() {
        // Real Maven stderr carries a file-coordinate prefix on the diagnostic line.
        // Even though T2-CX picks a different (classifier-matchable) line as the summary,
        // the path-extraction scans the FULL stderr — so the description still gets the path.
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        GoalEntity injected = goalEntityWithMeta(new HashMap<>());
        when(goalPlannerService.getGoalById(anyString())).thenReturn(injected);

        String stderr = """
                [ERROR] COMPILATION ERROR :
                [ERROR] /home/me/firestarter/output/src/main/java/com/example/library/Foo.java:[12,5] cannot find symbol
                [ERROR]   symbol:   class Bar
                """;

        injector.injectBuildFixSubgoalIfAbsent(buildOutcome(stderr), List.of());

        ArgumentCaptor<String> descCaptor = ArgumentCaptor.forClass(String.class);
        verify(goalPlannerService).addSubGoal(anyString(), descCaptor.capture(), eq(true));
        assertThat(descCaptor.getValue())
                .startsWith("Fix compile error in src/main/java/com/example/library/Foo.java:")
                .contains("Bar");

        // Per-file criterion prepended.
        ArgumentCaptor<List> criteriaCaptor = ArgumentCaptor.forClass(List.class);
        verify(goalPlannerService).setAcceptanceCriteria(anyString(), criteriaCaptor.capture());
        assertThat(criteriaCaptor.getValue().get(0))
                .isEqualTo("src/main/java/com/example/library/Foo.java compiles without errors");
    }

    @Test
    void inject_fallsBackToContextPaths_whenStderrLacksFilePrefix() {
        // MISSING_POM stderr has no file:line — must fall back to contextPaths. The injector
        // takes the first non-blank entry; ecosystem-aware filtering is intentionally absent
        // (see T4-AU). For MISSING_POM, naming `pom.xml` is the right repair target.
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        GoalEntity injected = goalEntityWithMeta(new HashMap<>());
        when(goalPlannerService.getGoalById(anyString())).thenReturn(injected);

        BuildOutcome outcome = buildOutcome(
                "[ERROR] The goal you specified requires a project to execute but there is no POM in this directory.");
        List<String> contextPaths = List.of("pom.xml", "src/main/java/com/example/Bar.java");

        injector.injectBuildFixSubgoalIfAbsent(outcome, contextPaths);

        ArgumentCaptor<String> descCaptor = ArgumentCaptor.forClass(String.class);
        verify(goalPlannerService).addSubGoal(anyString(), descCaptor.capture(), eq(true));
        assertThat(descCaptor.getValue())
                .startsWith("Fix compile error in pom.xml:")
                .contains("no POM in this directory");
    }

    @Test
    void inject_omitsPathSuffix_whenNoFileInStderrOrContextPaths() {
        // No path information anywhere — fall back to the existing description shape.
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        GoalEntity injected = goalEntityWithMeta(new HashMap<>());
        when(goalPlannerService.getGoalById(anyString())).thenReturn(injected);

        injector.injectBuildFixSubgoalIfAbsent(
                buildOutcome("[ERROR] some unrecognised error"),
                List.of());

        ArgumentCaptor<String> descCaptor = ArgumentCaptor.forClass(String.class);
        verify(goalPlannerService).addSubGoal(anyString(), descCaptor.capture(), eq(true));
        assertThat(descCaptor.getValue())
                .startsWith("Fix compile error: ")
                .doesNotContain("Fix compile error in ");
    }

    @Test
    void inject_missingPom_picksPomXml_evenWhenContextPathsAreOnlyJavaFiles() {
        // Reproduces the 2026-05-07 03:53 defect: LLM proposed only Java files, MISSING_POM
        // rollback led to a BUILD_FIX subgoal saying "Fix compile error in BookController.java"
        // for a missing pom.xml — contradictory and unactionable. T4-AU's class-aware override
        // forces "pom.xml" regardless of contextPaths.
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        GoalEntity injected = goalEntityWithMeta(new HashMap<>());
        when(goalPlannerService.getGoalById(anyString())).thenReturn(injected);

        BuildOutcome outcome = buildOutcome(
                "[ERROR] The goal you specified requires a project to execute but there is no POM in this directory.");
        List<String> javaOnlyContextPaths = List.of(
                "src/main/java/com/example/library/BookController.java",
                "src/main/java/com/example/library/Book.java");

        injector.injectBuildFixSubgoalIfAbsent(outcome, javaOnlyContextPaths);

        ArgumentCaptor<String> descCaptor = ArgumentCaptor.forClass(String.class);
        verify(goalPlannerService).addSubGoal(anyString(), descCaptor.capture(), eq(true));
        assertThat(descCaptor.getValue())
                .as("MISSING_POM must always name pom.xml as the repair target")
                .startsWith("Fix compile error in pom.xml:")
                .doesNotContain("BookController.java")
                .doesNotContain("Book.java");

        // Per-file criterion names pom.xml, not the Java files.
        ArgumentCaptor<List> criteriaCaptor = ArgumentCaptor.forClass(List.class);
        verify(goalPlannerService).setAcceptanceCriteria(anyString(), criteriaCaptor.capture());
        assertThat(criteriaCaptor.getValue().get(0)).isEqualTo("pom.xml compiles without errors");
    }

    @Test
    void inject_recordsBuildErrorKindInMetadata() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());

        GoalEntity injected = goalEntityWithMeta(new HashMap<>());
        when(goalPlannerService.getGoalById(anyString())).thenReturn(injected);

        String missingPomStderr = "[ERROR] The goal you specified requires a project to execute but there is no POM in this directory (/data/firestarter).";
        injector.injectBuildFixSubgoalIfAbsent(buildOutcome(missingPomStderr), java.util.List.of());

        assertThat(injected.getMetadata())
                .containsKey(BlockerSubgoalInjector.METADATA_KEY_BUILD_ERROR_KIND);
    }

    // ── hasActiveBuildFixSubgoal ──

    @Test
    void hasActive_returnsTrueWhenCurrentGoalIsBuildFix() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(activeBuildFixGoal());

        assertThat(injector.hasActiveBuildFixSubgoal()).isTrue();
    }

    @Test
    void hasActive_returnsTrueWhenBuildFixInRemainingGoals() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(activeBuildFixGoal()));

        assertThat(injector.hasActiveBuildFixSubgoal()).isTrue();
    }

    @Test
    void hasActive_returnsFalseWhenNoBuildFixGoalsExist() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());

        assertThat(injector.hasActiveBuildFixSubgoal()).isFalse();
    }

    @Test
    void hasActive_ignoresGoalsWithWrongMetadataValue() {
        GoalEntity g = goalEntityWithMeta(Map.of(BlockerTypes.METADATA_KEY, "OTHER_TYPE"));
        g.setStatus(GoalStatus.ACTIVE);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(g);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());

        assertThat(injector.hasActiveBuildFixSubgoal()).isFalse();
    }

    // ── clearActiveBuildFixSubgoals ──

    @Test
    void clear_marksCurrentBuildFixGoalAsSubsumed() {
        GoalEntity current = activeBuildFixGoal();
        current.setId("goal-123");
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(current);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());

        injector.clearActiveBuildFixSubgoals();

        // Build cleared → BUILD_FIX purpose is met without this goal shipping its own work,
        // so the status flips to SUBSUMED via transitionGoal (with the COVERED reason) rather
        // than GIVEN_UP, which T2-DR's closure rule treats as a legitimate pass-through.
        verify(goalPlannerService).transitionGoal(eq("goal-123"),
                eq(com.walpro.autobuilder.goal.GoalStatus.SUBSUMED), anyString(),
                eq(com.walpro.autobuilder.goal.SubsumptionReason.COVERED));
    }

    @Test
    void clear_marksRemainingBuildFixGoalsAsSubsumed() {
        GoalEntity remaining = activeBuildFixGoal();
        remaining.setId("goal-remaining");
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(remaining));

        injector.clearActiveBuildFixSubgoals();

        verify(goalPlannerService).transitionGoal(eq("goal-remaining"),
                eq(com.walpro.autobuilder.goal.GoalStatus.SUBSUMED), anyString(),
                eq(com.walpro.autobuilder.goal.SubsumptionReason.COVERED));
    }

    /**
     * Run 2026-08-30_00-06-00: the completion edge raised a work item naming four unmet
     * requirements at 00:15:12, a merge compiled at 00:16:00 and this cleanup retired it, and the
     * root completed 17 seconds later with the multi-line-row requirement still unmet. Compiling
     * says nothing about whether an acceptance criterion is met, so it cannot retire the item
     * that carries one.
     */
    @Test
    void clear_leavesCriteriaGateWorkItemAlone() {
        GoalEntity current = activeBuildFixGoal();
        current.setId("goal-criteria");
        current.getMetadata().put(BlockerTypes.ORIGINATING_GATE_KEY, BlockerTypes.GATE_CRITERIA);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(current);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());

        injector.clearActiveBuildFixSubgoals();

        verify(goalPlannerService, never()).transitionGoal(anyString(),
                eq(com.walpro.autobuilder.goal.GoalStatus.SUBSUMED), anyString(), any());
    }

    /** Same reasoning, and the same reading error the package-gate carve-out already corrected:
     *  a boot failure is not answered by the sources compiling. */
    @Test
    void clear_leavesBootGateWorkItemAlone() {
        GoalEntity remaining = activeBuildFixGoal();
        remaining.setId("goal-boot");
        remaining.getMetadata().put(BlockerTypes.ORIGINATING_GATE_KEY, BlockerTypes.GATE_BOOT);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(remaining));

        injector.clearActiveBuildFixSubgoals();

        verify(goalPlannerService, never()).transitionGoal(anyString(),
                eq(com.walpro.autobuilder.goal.GoalStatus.SUBSUMED), anyString(), any());
    }

    @Test
    void clear_noOpWhenNoBuildFixGoalsExist() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());

        injector.clearActiveBuildFixSubgoals();

        verify(goalPlannerService, never()).transitionGoal(anyString(),
                eq(com.walpro.autobuilder.goal.GoalStatus.SUBSUMED), anyString(), any());
    }

    // ── T2-DD: divergence subgoal on locus-stuck streak ──

    @Test
    void inject_T2DD_divergenceSubgoal_whenStreakAndCannotFindSymbol() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());

        GoalEntity injected = goalEntityWithMeta(new HashMap<>());
        when(goalPlannerService.getGoalById(anyString())).thenReturn(injected);

        // stderr with file-coordinate prefix + missing-symbol diagnostic.
        String stderr = "[ERROR] /output/src/main/java/com/example/library/exception/GlobalExceptionHandler.java:[12,5] cannot find symbol\n"
                + "[ERROR]   symbol:   class BookAlreadyLoanedException\n"
                + "[ERROR]   location: class GlobalExceptionHandler";
        BuildOutcome outcome = buildOutcome(stderr);

        injector.injectBuildFixSubgoalIfAbsent(outcome,
                java.util.List.of("src/main/java/com/example/library/exception/GlobalExceptionHandler.java"),
                3, "CANNOT_FIND_SYMBOL|src/main/java/com/example/library/exception/GlobalExceptionHandler.java");

        ArgumentCaptor<String> descCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<String>> criteriaCaptor = ArgumentCaptor.forClass(List.class);
        verify(goalPlannerService).addSubGoal(anyString(), descCaptor.capture(), eq(true));
        verify(goalPlannerService).setAcceptanceCriteria(anyString(), criteriaCaptor.capture());

        // Divergence-shaped description: names the symbol and offers two paths.
        assertThat(descCaptor.getValue())
                .startsWith("Diverge")
                .contains("BookAlreadyLoanedException")
                .contains("Either create").contains("or remove");

        // Criteria offer EITHER resolution.
        List<String> criteria = criteriaCaptor.getValue();
        assertThat(criteria).anyMatch(c -> c.contains("BookAlreadyLoanedException") && c.contains("(creation path)"));
        assertThat(criteria).anyMatch(c -> c.contains("BookAlreadyLoanedException") && c.contains("(removal path)"));
    }

    @Test
    void inject_T2DD_noDivergence_whenStreakBelowThreshold() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());

        GoalEntity injected = goalEntityWithMeta(new HashMap<>());
        when(goalPlannerService.getGoalById(anyString())).thenReturn(injected);

        String stderr = "[ERROR] /output/src/main/java/Foo.java:[1,1] cannot find symbol\n"
                + "[ERROR]   symbol:   class Bar";
        injector.injectBuildFixSubgoalIfAbsent(buildOutcome(stderr),
                java.util.List.of("src/main/java/Foo.java"), 1, "CANNOT_FIND_SYMBOL|Foo.java");

        ArgumentCaptor<String> descCaptor = ArgumentCaptor.forClass(String.class);
        verify(goalPlannerService).addSubGoal(anyString(), descCaptor.capture(), eq(true));
        assertThat(descCaptor.getValue()).doesNotStartWith("Diverge");
        assertThat(descCaptor.getValue()).startsWith("Fix compile error in");
    }

    // ── T1-EX: phantom-diagnostic pre-injection guard ──

    @Test
    void inject_T1EX_skipsWhenFileDoesNotReferenceSymbol_phantomDiagnostic() throws java.io.IOException {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());

        // The diagnostic claims `symbol: class Bar` in Foo.java, but Foo.java's current on-disk
        // content does NOT reference `Bar` anywhere — phantom (stale) diagnostic. Guard must skip.
        when(fileService.exists(eq("output/src/main/java/com/example/Foo.java"))).thenReturn(true);
        when(fileService.readString(eq("output/src/main/java/com/example/Foo.java")))
                .thenReturn("class Foo { Object onlyHasObject; }");

        String stderr = """
                [ERROR] /output/src/main/java/com/example/Foo.java:[1,1] cannot find symbol
                [ERROR]   symbol:   class Bar
                """;
        injector.injectBuildFixSubgoalIfAbsent(buildOutcome(stderr), List.of());

        verify(goalPlannerService, never()).addSubGoal(anyString(), anyString(), anyBoolean());
        verify(goalPlannerService, never()).setAcceptanceCriteria(anyString(), anyList());
    }

    @Test
    void inject_T1EX_doesNotSkip_whenSymbolPresentInFile() throws java.io.IOException {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        GoalEntity injected = goalEntityWithMeta(new HashMap<>());
        when(goalPlannerService.getGoalById(anyString())).thenReturn(injected);

        when(fileService.exists(eq("output/src/main/java/com/example/Foo.java"))).thenReturn(true);
        when(fileService.readString(eq("output/src/main/java/com/example/Foo.java")))
                .thenReturn("class Foo { Bar member; }");

        String stderr = """
                [ERROR] /output/src/main/java/com/example/Foo.java:[1,1] cannot find symbol
                [ERROR]   symbol:   class Bar
                """;
        injector.injectBuildFixSubgoalIfAbsent(buildOutcome(stderr), List.of());

        verify(goalPlannerService).addSubGoal(anyString(), anyString(), eq(true));
    }

    @Test
    void inject_T1EX_doesNotSkip_whenFileDoesNotExist_subjectIsCreation() throws java.io.IOException {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        GoalEntity injected = goalEntityWithMeta(new HashMap<>());
        when(goalPlannerService.getGoalById(anyString())).thenReturn(injected);

        // File-not-found is the canonical "to be created" case — the BUILD_FIX subgoal exists
        // precisely to drive that creation. Guard must NOT skip.
        when(fileService.exists(eq("output/src/main/java/com/example/Foo.java"))).thenReturn(false);

        String stderr = """
                [ERROR] /output/src/main/java/com/example/Foo.java:[1,1] cannot find symbol
                [ERROR]   symbol:   class Bar
                """;
        injector.injectBuildFixSubgoalIfAbsent(buildOutcome(stderr), List.of());

        verify(goalPlannerService).addSubGoal(anyString(), anyString(), eq(true));
    }

    // ── T2-ES: structural-defect redirection ─────────────────────────────────

    /**
     * The work item names the alternatives and forbids the repeat — the T2-DD divergence shape,
     * for a defect the compiler cannot see. Each resolution path becomes its own criterion,
     * because satisfying any ONE of them clears the defect.
     */
    @Test
    void structuralDefect_injectsDivergenceNamingEveryResolutionPath() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        GoalEntity injected = goalEntityWithMeta(new HashMap<>());
        when(goalPlannerService.getGoalById(anyString())).thenReturn(injected);

        List<String> paths = List.of(
                "BookRepository is the only type that declares a collection of 'Book', and BookStore "
                        + "reads and writes that collection through BookRepository instead of declaring its own",
                "BookStore is the only type that declares a collection of 'Book', and BookRepository "
                        + "reads and writes that collection through BookStore instead of declaring its own");

        injector.injectStructuralDefectRedirection("StoreCoherenceHeuristic", "Book", paths, 3);

        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> descCaptor = ArgumentCaptor.forClass(String.class);
        verify(goalPlannerService).addSubGoal(idCaptor.capture(), descCaptor.capture(), eq(true));

        String description = descCaptor.getValue();
        assertThat(description).startsWith(BlockerTypes.DIVERGENCE_DESCRIPTION_PREFIX);
        assertThat(description).contains("3 consecutive proposals").contains("'Book'");
        assertThat(description).contains(paths.get(0)).contains(paths.get(1));
        assertThat(description).contains("Do NOT propose another edit");

        // The description is a framework-generated instruction, so it must never be bankable as a
        // criterion (the T1-FF/T1-FG hazard).
        assertThat(BlockerTypes.isDiagnosticInstruction(description)).isTrue();

        verify(goalPlannerService).setAcceptanceCriteria(idCaptor.getValue(), paths);
    }

    /**
     * The defect compiles perfectly — a parallel store is invisible to javac — so without a gate
     * stamp the COMPILE_OK cleanup would retire this item on the very next merge. That is T2-EQ's
     * Cause B arriving through a new door, and the stamp is what closes it.
     */
    @Test
    void structuralDefect_isStampedSoACompileObservationCannotRetireIt() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        GoalEntity injected = goalEntityWithMeta(new HashMap<>());
        when(goalPlannerService.getGoalById(anyString())).thenReturn(injected);

        injector.injectStructuralDefectRedirection(
                "StoreCoherenceHeuristic", "Book", List.of("one type declares it"), 3);

        assertThat(injected.getMetadata())
                .containsEntry(BlockerTypes.ORIGINATING_GATE_KEY, BlockerTypes.GATE_STRUCTURAL_DEFECT);
        assertThat(BlockerTypes.isFromGate(injected.getMetadata())).isTrue();

        // And the compile-OK sweep leaves it alone.
        injected.setStatus(GoalStatus.ACTIVE);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(injected);
        injector.clearActiveBuildFixSubgoals();
        verify(goalPlannerService, never()).transitionGoal(any(), eq(GoalStatus.SUBSUMED), any(), any());
    }

    /**
     * A finder that cannot say what would clear its defect has nothing to add to the warning
     * already in the prompt. Injecting anyway would re-ask through a more expensive channel,
     * which is the thing this ticket exists to stop.
     */
    @Test
    void structuralDefect_withNoResolutionPaths_doesNotInject() {
        injector.injectStructuralDefectRedirection("SomeHeuristic", "Book", List.of(), 5);
        verify(goalPlannerService, never()).addSubGoal(anyString(), anyString(), anyBoolean());
    }

    /** A broken build is the more urgent blocker; do not stack a second work item behind it. */
    @Test
    void structuralDefect_whenABuildFixIsAlreadyActive_doesNotInject() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(activeBuildFixGoal());

        injector.injectStructuralDefectRedirection(
                "StoreCoherenceHeuristic", "Book", List.of("one type declares it"), 4);

        verify(goalPlannerService, never()).addSubGoal(anyString(), anyString(), anyBoolean());
    }

    // ── helpers ──

    private static BuildOutcome buildOutcome(String stderr) {
        return new BuildOutcome(BuildResult.COMPILE_ERROR, stderr, 100L, "mvn compile", null);
    }

    private static GoalEntity activeBuildFixGoal() {
        GoalEntity g = goalEntityWithMeta(new HashMap<>(Map.of(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX)));
        g.setStatus(GoalStatus.ACTIVE);
        return g;
    }

    private static GoalEntity goalEntityWithMeta(Map<String, Object> meta) {
        GoalEntity g = new GoalEntity();
        g.setMetadata(meta);
        return g;
    }

    // ── T4-DL: an API no published artifact provides ─────────────────────────

    /**
     * Run 2026-08-28_05-27-49 wrote {@code import com.sun.opengl.util.GLCapabilities} in nine
     * responses — JOGL 1.x, unpublished under that name for over a decade. Demanding that the
     * package become "available on the compile classpath" is unachievable, and sends the loop
     * hunting a dependency that does not exist; T4-DH's 26 invented coordinates are the same
     * failure seen from the other side. The achievable path is removal.
     */
    @Test
    void aPackageNoArtifactPublishes_steersToTheRemovalPath() {
        var repo = org.mockito.Mockito.mock(
                com.walpro.autobuilder.core.build.inject.CoordinateSearchClient.class);
        when(repo.publishesClass(anyString())).thenReturn(
                com.walpro.autobuilder.core.build.inject.CoordinateSearchClient.Availability.ABSENT);
        injector.setCoordinateSearchClient(repo);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        injector.injectBuildFixSubgoalIfAbsent(buildOutcome(
                "[ERROR] /src/main/java/A.java:[3,1] package com.sun.opengl.util does not exist\n"
                        + "[ERROR]   symbol:   class GLCapabilities"), java.util.List.of());

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(goalPlannerService).setAcceptanceCriteria(anyString(), captor.capture());
        List<String> criteria = captor.getValue();
        assertThat(criteria).anySatisfy(c ->
                assertThat(c).contains("no longer imported").contains("no published artifact provides it"));
        assertThat(criteria).noneSatisfy(c ->
                assertThat(c).contains("available on the compile classpath"));
    }

    @Test
    void aRepositoryThatCannotBeConsulted_leavesTodaysCriteriaUntouched() {
        // The safety property: UNKNOWN must never read as "this API does not exist", or an
        // offline box would steer the loop to delete imports it needs.
        var repo = org.mockito.Mockito.mock(
                com.walpro.autobuilder.core.build.inject.CoordinateSearchClient.class);
        when(repo.publishesClass(anyString())).thenReturn(
                com.walpro.autobuilder.core.build.inject.CoordinateSearchClient.Availability.UNKNOWN);
        injector.setCoordinateSearchClient(repo);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        injector.injectBuildFixSubgoalIfAbsent(buildOutcome(
                "[ERROR] /src/main/java/A.java:[3,1] package org.real.lib does not exist\n"
                        + "[ERROR]   symbol:   class Thing"), java.util.List.of());

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(goalPlannerService).setAcceptanceCriteria(anyString(), captor.capture());
        assertThat((List<String>) captor.getValue()).anySatisfy(c ->
                assertThat(c).contains("available on the compile classpath"));
    }
}
