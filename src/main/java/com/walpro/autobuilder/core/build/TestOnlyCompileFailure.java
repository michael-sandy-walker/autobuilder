package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import java.util.regex.Pattern;

/**
 * T4-BN-2 follow-up (2026-06-23): distinguishes a compile failure that is confined to TEST sources
 * (main compiles clean) from one that breaks MAIN.
 *
 * <p>T4-BN-2 made the per-cycle probe compile {@code src/test/java} as well as {@code src/main/java}
 * so test breaks steer evolution in-cycle. But the probe returns one undifferentiated
 * {@link BuildResult#COMPILE_ERROR}, and both the pre-merge probe ({@code CompileProbeService}) and
 * the post-merge gate ({@code BuildResultHeuristic}) reject + roll back the whole mutation on it.
 * Consequence (field-confirmed across the 06-17→06-23 test-seed runs, which fulfilled 0/15 vs 3/3 on
 * plain seeds): a mutation whose MAIN code compiles perfectly is thrown away whenever a test source
 * fails to compile — so once tests exist on disk, main evolution freezes and tests can never iterate
 * (every imperfect intermediate is rejected). The intent of T4-BN-2 was to make test breaks
 * <i>steer</i>, but the implementation made them <i>gate</i>.
 *
 * <p>This helper lets the gates treat a test-only failure as non-blocking (accept the main work,
 * surface the test break as a diagnostic) while a main failure still rejects as before. The
 * completion-edge gates ({@code mvn package} / {@code mvn test}) remain the backstop that a finished
 * artifact's tests must actually compile and pass.
 *
 * <p>Classification is by error file path in the build output: {@code mvn test-compile} compiles main
 * then test, so a single failure's errors are cleanly all-main (main failed, phase stopped) or
 * all-test (main ok, test phase failed); javac-direct may mix, so the rule is conservative — a
 * failure counts as test-only ONLY when at least one {@code src/test/} error path is present and NO
 * {@code src/main/} error path is. Anything unclassifiable stays a (main) COMPILE_ERROR.
 */
public final class TestOnlyCompileFailure {

    private TestOnlyCompileFailure() {}

    private static final Pattern MAIN_PATH = Pattern.compile("[/\\\\]src[/\\\\]main[/\\\\]");
    private static final Pattern TEST_PATH = Pattern.compile("[/\\\\]src[/\\\\]test[/\\\\]");

    /**
     * True when {@code outcome} is a compile failure whose errors are confined to test sources
     * (main compiled). False for any non-{@code COMPILE_ERROR} outcome, a main failure, or an
     * unclassifiable one (no recognizable source paths in the output).
     */
    public static boolean isTestOnly(BuildOutcome outcome) {
        if (outcome == null || outcome.result() != BuildResult.COMPILE_ERROR) return false;
        String out = outcome.stderrTail();
        if (out == null || out.isBlank()) return false;
        boolean hasTest = TEST_PATH.matcher(out).find();
        boolean hasMain = MAIN_PATH.matcher(out).find();
        return hasTest && !hasMain;
    }

    /**
     * T4-BN-3: resolves the same "which outcome is ground truth" fallback
     * {@code BuildResultPromptBlock} renders — the post-merge {@code buildOutcome}, or, when
     * that is absent/{@code COMPILE_OK}/{@code SKIPPED}, the latest compile-probe outcome, but
     * only if the probe is itself a {@code COMPILE_ERROR}. Null when neither applies. One home
     * for this resolution so callers (the prompt block, the signal emitter) cannot disagree on
     * which outcome is authoritative.
     */
    public static BuildOutcome resolveOutcome(CycleContextData ctx) {
        if (ctx == null) return null;
        BuildOutcome outcome = ctx.buildOutcome();
        if (outcome == null || outcome.result() == BuildResult.COMPILE_OK || outcome.result() == BuildResult.SKIPPED) {
            BuildOutcome probe = ctx.lastProbeOutcome();
            if (probe == null || probe.result() != BuildResult.COMPILE_ERROR) return null;
            return probe;
        }
        return outcome;
    }

    /** True when {@link #resolveOutcome(CycleContextData)} is a test-only compile failure. */
    public static boolean isTestOnly(CycleContextData ctx) {
        return isTestOnly(resolveOutcome(ctx));
    }
}
