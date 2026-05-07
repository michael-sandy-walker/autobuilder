package com.walpro.autobuilder.core.blocker;

import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.build.BuildErrorClassifier;
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

    private BlockerSubgoalInjector injector;

    @BeforeEach
    void setUp() {
        injector = new BlockerSubgoalInjector(goalPlannerService);
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
    void clear_marksCurrentBuildFixGoalAsGivenUp() {
        GoalEntity current = activeBuildFixGoal();
        current.setId("goal-123");
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(current);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());

        injector.clearActiveBuildFixSubgoals();

        verify(goalPlannerService).markGoalGivenUp(eq("goal-123"), anyString());
    }

    @Test
    void clear_marksRemainingBuildFixGoalsAsGivenUp() {
        GoalEntity remaining = activeBuildFixGoal();
        remaining.setId("goal-remaining");
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(remaining));

        injector.clearActiveBuildFixSubgoals();

        verify(goalPlannerService).markGoalGivenUp(eq("goal-remaining"), anyString());
    }

    @Test
    void clear_noOpWhenNoBuildFixGoalsExist() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());

        injector.clearActiveBuildFixSubgoals();

        verify(goalPlannerService, never()).markGoalGivenUp(anyString(), anyString());
    }

    // ── summariseStderr ──

    @Test
    void summarise_returnsPlaceholderForNullInput() {
        assertThat(BlockerSubgoalInjector.summariseStderr(null)).isEqualTo("(no stderr captured)");
    }

    @Test
    void summarise_extractsFirstInformativeErrorLine() {
        String stderr = """
                [INFO] Scanning for projects...
                [ERROR]
                [ERROR] The goal requires a POM in this directory.
                [ERROR] -> [Help 1]
                """;
        assertThat(BlockerSubgoalInjector.summariseStderr(stderr))
                .isEqualTo("The goal requires a POM in this directory.");
    }

    @Test
    void summarise_skipsHelpContinuationLines() {
        String stderr = "[ERROR] -> [Help 1]\n[ERROR] real error here";
        assertThat(BlockerSubgoalInjector.summariseStderr(stderr)).isEqualTo("real error here");
    }

    // ── T2-CX: prefer diagnostic-shaped lines over Maven banner lines ──

    @Test
    void summarise_skipsCompilationErrorBanner_picksClassifierMatchableLine() {
        // Real Maven shape: [ERROR] COMPILATION ERROR :    (banner)
        //                   [ERROR] /path/Foo.java:[12,5] cannot find symbol  (header)
        //                   [ERROR]   symbol:   class Bar  (← classifier-matchable detail)
        // The summariser must skip both banner and header, picking the classifier-matchable
        // detail so BuildErrorClassifier produces CANNOT_FIND_SYMBOL not UNKNOWN.
        String stderr = """
                [INFO] BUILD FAILURE
                [INFO] ------------------------------------------------------------------------
                [ERROR] COMPILATION ERROR :
                [ERROR] /path/to/Foo.java:[12,5] cannot find symbol
                [ERROR]   symbol:   class Bar
                [ERROR]   location: class Foo
                [ERROR] -> [Help 1]
                """;
        String summary = BlockerSubgoalInjector.summariseStderr(stderr);
        assertThat(summary)
                .as("must skip COMPILATION ERROR banner and pick the classifier-matchable line")
                .doesNotContain("COMPILATION ERROR")
                .contains("symbol:")
                .contains("Bar");
        // Classifier must now correctly identify the kind (was UNKNOWN with the old summariser).
        BuildErrorClassifier.Classification c = BuildErrorClassifier.classify(summary);
        assertThat(c.kind()).isEqualTo(BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL);
    }

    @Test
    void summarise_skipsBuildFailureBanner() {
        // Without an [ERROR] COMPILATION ERROR : line but with BUILD FAILURE in [ERROR] form.
        String stderr = """
                [ERROR] BUILD FAILURE
                [ERROR] package com.example.missing does not exist
                """;
        String summary = BlockerSubgoalInjector.summariseStderr(stderr);
        assertThat(summary)
                .doesNotContain("BUILD FAILURE")
                .contains("does not exist");
        BuildErrorClassifier.Classification c = BuildErrorClassifier.classify(summary);
        assertThat(c.kind()).isEqualTo(BuildErrorClassifier.Kind.PACKAGE_DOES_NOT_EXIST);
    }

    @Test
    void summarise_picksMissingPomDiagnosticBeforeBanner() {
        // Existing T1-DM behaviour for missing-POM stderr — must still produce the
        // "no POM in this directory" line and stay non-regressive.
        String stderr = """
                [INFO] Scanning for projects...
                [ERROR] COMPILATION ERROR :
                [ERROR] The goal you specified requires a project to execute but there is no POM in this directory (/data/firestarter).
                [ERROR] -> [Help 1]
                """;
        String summary = BlockerSubgoalInjector.summariseStderr(stderr);
        assertThat(summary).contains("no POM in this directory");
        BuildErrorClassifier.Classification c = BuildErrorClassifier.classify(summary);
        assertThat(c.kind()).isEqualTo(BuildErrorClassifier.Kind.MISSING_POM);
    }

    @Test
    void summarise_fallsBackToFirstInformativeLineWhenNoDiagnosticMatches() {
        // No recognised diagnostic shape — must NOT skip the banner blindly, must return
        // SOMETHING informative. Falls back to the first non-banner [ERROR] line.
        String stderr = """
                [ERROR] BUILD FAILURE
                [ERROR] some unrecognised error type that no pattern matches
                [ERROR] -> [Help 1]
                """;
        String summary = BlockerSubgoalInjector.summariseStderr(stderr);
        assertThat(summary)
                .as("no diagnostic match — fall back to first informative [ERROR] line, skipping banner")
                .doesNotContain("BUILD FAILURE")
                .contains("some unrecognised error type");
    }

    @Test
    void summarise_stripsAnsiEscapesFromColourisedMavenOutput() {
        // Real Maven output is ANSI-colourised. ANSI sequences must be stripped both when
        // matching against banner/pattern sets and in the final emitted summary — otherwise
        // raw escape bytes leak into BUILD_FIX subgoal descriptions and behaviour events.
        String esc = "[31m"; String reset = "[m";
        String stderr = esc + "[INFO]" + reset + " BUILD FAILURE\n"
                + esc + "[ERROR]" + reset + " " + esc + "COMPILATION ERROR :" + reset + "\n"
                + esc + "[ERROR]" + reset + "   symbol:   class " + esc + "Bar" + reset + "\n";
        String summary = BlockerSubgoalInjector.summariseStderr(stderr);
        assertThat(summary)
                .as("ANSI escapes must not survive into the summary")
                .doesNotContain("")
                .contains("symbol:")
                .contains("Bar");
    }

    @Test
    void summarise_picksSemicolonExpectedDiagnostic() {
        String stderr = """
                [ERROR] COMPILATION ERROR :
                [ERROR] /path/to/Foo.java:[15,30] ';' expected
                """;
        String summary = BlockerSubgoalInjector.summariseStderr(stderr);
        assertThat(summary).contains("expected").doesNotContain("COMPILATION ERROR");
    }

    @Test
    void summarise_truncatesLongLines() {
        String longLine = "[ERROR] " + "x".repeat(300);
        String result = BlockerSubgoalInjector.summariseStderr(longLine);
        assertThat(result).hasSizeLessThanOrEqualTo(201); // 200 chars + ellipsis char
        assertThat(result).endsWith("…");
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
}
