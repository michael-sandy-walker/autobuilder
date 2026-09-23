package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-BD: migrated from {@code BlockerSubgoalInjectorTest#summarise_*} +
 * {@code EvolutionProtocolContractTest#lastCompileErrorsBlock_*} +
 * {@code BuildResultPromptBlockTest}. Covers the shared utility's single-line picker and
 * its list extractor across the option combinations the three retired callers used.
 */
class BuildStderrLinesTest {

    // ── pickBestSingleLine ─────────────────────────────────────────────────

    @Test
    void pickBestSingleLine_returnsPlaceholderForNullInput() {
        assertThat(BuildStderrLines.pickBestSingleLine(null)).isEqualTo("(no stderr captured)");
    }

    @Test
    void pickBestSingleLine_extractsFirstInformativeErrorLine() {
        String stderr = """
                [INFO] Scanning for projects...
                [ERROR]
                [ERROR] The goal requires a POM in this directory.
                [ERROR] -> [Help 1]
                """;
        assertThat(BuildStderrLines.pickBestSingleLine(stderr))
                .isEqualTo("The goal requires a POM in this directory.");
    }

    @Test
    void pickBestSingleLine_skipsHelpContinuationLines() {
        String stderr = "[ERROR] -> [Help 1]\n[ERROR] real error here";
        assertThat(BuildStderrLines.pickBestSingleLine(stderr)).isEqualTo("real error here");
    }

    @Test
    void pickBestSingleLine_skipsCompilationErrorBanner_picksClassifierMatchableLine() {
        String stderr = """
                [INFO] BUILD FAILURE
                [INFO] ------------------------------------------------------------------------
                [ERROR] COMPILATION ERROR :
                [ERROR] /path/to/Foo.java:[12,5] cannot find symbol
                [ERROR]   symbol:   class Bar
                [ERROR]   location: class Foo
                [ERROR] -> [Help 1]
                """;
        String summary = BuildStderrLines.pickBestSingleLine(stderr);
        assertThat(summary)
                .doesNotContain("COMPILATION ERROR")
                .contains("symbol:")
                .contains("Bar");
        BuildErrorClassifier.Classification c = BuildErrorClassifier.classify(summary);
        assertThat(c.kind()).isEqualTo(BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL);
    }

    @Test
    void pickBestSingleLine_skipsBuildFailureBanner() {
        String stderr = """
                [ERROR] BUILD FAILURE
                [ERROR] package com.example.missing does not exist
                """;
        String summary = BuildStderrLines.pickBestSingleLine(stderr);
        assertThat(summary)
                .doesNotContain("BUILD FAILURE")
                .contains("does not exist");
        BuildErrorClassifier.Classification c = BuildErrorClassifier.classify(summary);
        assertThat(c.kind()).isEqualTo(BuildErrorClassifier.Kind.PACKAGE_DOES_NOT_EXIST);
    }

    @Test
    void pickBestSingleLine_picksMissingPomDiagnosticBeforeBanner() {
        String stderr = """
                [INFO] Scanning for projects...
                [ERROR] COMPILATION ERROR :
                [ERROR] The goal you specified requires a project to execute but there is no POM in this directory (/data/firestarter).
                [ERROR] -> [Help 1]
                """;
        String summary = BuildStderrLines.pickBestSingleLine(stderr);
        assertThat(summary).contains("no POM in this directory");
        BuildErrorClassifier.Classification c = BuildErrorClassifier.classify(summary);
        assertThat(c.kind()).isEqualTo(BuildErrorClassifier.Kind.MISSING_POM);
    }

    @Test
    void pickBestSingleLine_fallsBackToFirstInformativeLineWhenNoDiagnosticMatches() {
        String stderr = """
                [ERROR] BUILD FAILURE
                [ERROR] some unrecognised error type that no pattern matches
                [ERROR] -> [Help 1]
                """;
        String summary = BuildStderrLines.pickBestSingleLine(stderr);
        assertThat(summary)
                .doesNotContain("BUILD FAILURE")
                .contains("some unrecognised error type");
    }

    @Test
    void pickBestSingleLine_stripsAnsiEscapesFromColourisedMavenOutput() {
        String esc = "[31m"; String reset = "[m";
        String stderr = esc + "[INFO]" + reset + " BUILD FAILURE\n"
                + esc + "[ERROR]" + reset + " " + esc + "COMPILATION ERROR :" + reset + "\n"
                + esc + "[ERROR]" + reset + "   symbol:   class " + esc + "Bar" + reset + "\n";
        String summary = BuildStderrLines.pickBestSingleLine(stderr);
        assertThat(summary)
                .doesNotContain("")
                .contains("symbol:")
                .contains("Bar");
    }

    @Test
    void pickBestSingleLine_picksSemicolonExpectedDiagnostic() {
        String stderr = """
                [ERROR] COMPILATION ERROR :
                [ERROR] /path/to/Foo.java:[15,30] ';' expected
                """;
        String summary = BuildStderrLines.pickBestSingleLine(stderr);
        assertThat(summary).contains("expected").doesNotContain("COMPILATION ERROR");
    }

    @Test
    void pickBestSingleLine_truncatesLongLines() {
        String longLine = "[ERROR] " + "x".repeat(300);
        String result = BuildStderrLines.pickBestSingleLine(longLine);
        assertThat(result).hasSizeLessThanOrEqualTo(201);
        assertThat(result).endsWith("…");
    }

    // ── errorLines: promptBlockTail preset ──────────────────────────────────

    @Test
    void promptBlockTail_returnsTailOfErrorLinesOnly_whenErrorLinesPresent() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 25; i++) sb.append("[INFO] info ").append(i).append("\n");
        for (int i = 0; i < 25; i++) sb.append("[ERROR] err ").append(i).append("\n");

        List<String> lines = BuildStderrLines.errorLines(
                sb.toString(),
                BuildStderrLines.Options.promptBlockTail(20));

        assertThat(lines).hasSize(20);
        assertThat(lines.get(0)).isEqualTo("[ERROR] err 5");
        assertThat(lines.get(19)).isEqualTo("[ERROR] err 24");
    }

    @Test
    void promptBlockTail_fallsBackToAllLines_whenNoErrorLines() {
        String stderr = "line0\nline1\nline2\nline3";

        List<String> lines = BuildStderrLines.errorLines(
                stderr,
                BuildStderrLines.Options.promptBlockTail(2));

        assertThat(lines).containsExactly("line2", "line3");
    }

    // ── errorLines: compileErrorsBlock preset ───────────────────────────────

    @Test
    void compileErrorsBlock_keepsErrorLinesWithTrailingContext() {
        String stderr = """
                [INFO] Scanning for projects...
                [ERROR] /a/Foo.java:[12,5] cannot find symbol
                  symbol:   class Bar
                [ERROR] /a/Bar.java:[3,1] ';' expected
                [INFO] BUILD FAILURE
                """;

        List<String> lines = BuildStderrLines.errorLines(
                stderr,
                BuildStderrLines.Options.compileErrorsBlock(30));

        assertThat(lines).contains("[ERROR] /a/Foo.java:[12,5] cannot find symbol");
        assertThat(lines).contains("  symbol:   class Bar");
        assertThat(lines).contains("[ERROR] /a/Bar.java:[3,1] ';' expected");
        // No [INFO] survives
        assertThat(lines).noneMatch(l -> l.contains("Scanning"));
        assertThat(lines).noneMatch(l -> l.contains("BUILD FAILURE"));
    }

    @Test
    void compileErrorsBlock_dropsEmptyErrorBodies() {
        String stderr = """
                [ERROR]
                [ERROR] :
                [ERROR] real diagnostic
                """;

        List<String> lines = BuildStderrLines.errorLines(
                stderr,
                BuildStderrLines.Options.compileErrorsBlock(30));

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).isEqualTo("[ERROR] real diagnostic");
    }

    @Test
    void compileErrorsBlock_capsAtMaxLines() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append("[ERROR] diag ").append(i).append("\n");

        List<String> lines = BuildStderrLines.errorLines(
                sb.toString(),
                BuildStderrLines.Options.compileErrorsBlock(10));

        assertThat(lines).hasSize(10);
        assertThat(lines.get(0)).isEqualTo("[ERROR] diag 0");
    }

    @Test
    void errorLines_emptyInputReturnsEmptyList() {
        assertThat(BuildStderrLines.errorLines(null, BuildStderrLines.Options.promptBlockTail(10)))
                .isEmpty();
        assertThat(BuildStderrLines.errorLines("", BuildStderrLines.Options.compileErrorsBlock(10)))
                .isEmpty();
    }

    // ----- T1-GE: pathsCarryingDiagnostics ------------------------------------------------------

    private static final String REPO = "src/main/java/x/repository/MemberRepository.java";

    @Test
    void pathsCarryingDiagnostics_buildToolCoordinate_matchesTheRelativePath() {
        String stderr = "[ERROR] /home/u/output/" + REPO + ":[3,47] package a.b does not exist";

        assertThat(BuildStderrLines.pathsCarryingDiagnostics(List.of(REPO, "src/main/java/x/Other.java"), stderr))
                .containsExactly(REPO);
    }

    @Test
    void pathsCarryingDiagnostics_compilerCoordinate_andAnsiColouring_match() {
        String javac = "/o/" + REPO + ":3: error: package a.b does not exist";
        String coloured = "[\u001B[1;31mERROR\u001B[m] /o/" + REPO + ":[3,47] package a.b does not exist";

        assertThat(BuildStderrLines.pathsCarryingDiagnostics(List.of(REPO), javac)).containsExactly(REPO);
        assertThat(BuildStderrLines.pathsCarryingDiagnostics(List.of(REPO), coloured)).containsExactly(REPO);
    }

    /** A file NAMED in a message is not a file an error is LOCATED in, nor is a same-named file elsewhere. */
    @Test
    void pathsCarryingDiagnostics_mentionOrSameNameElsewhere_doesNotMatch() {
        String stderr = "[ERROR] /o/src/main/java/x/service/MemberService.java:[20,29] cannot find symbol\n"
                + "  location: variable repo of type MemberRepository\n"
                + "[ERROR] /o/src/main/java/y/repository/MemberRepository.java:[1,1] class, interface expected";

        assertThat(BuildStderrLines.pathsCarryingDiagnostics(List.of(REPO), stderr)).isEmpty();
    }
}
