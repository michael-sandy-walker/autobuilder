package com.walpro.autobuilder.protocol.build;

import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

/**
 * T4-AN-3: shared formatter for the {@code --- BUILD RESULT ---} prompt block. Consumed by
 * both the evaluation contract (was the original site, T4-AN-1) and the evolution contract.
 *
 * <p>The two audiences differ only in the closing directive: the evaluator is told to score
 * the mutation REJECTED; the evolver is told the build state is ground truth and that
 * {@code #NoDiff} is forbidden while the build is broken.</p>
 *
 * <p>Returns {@code null} when there is no signal worth showing
 * (outcome is {@code null}, {@link BuildResult#COMPILE_OK}, or {@link BuildResult#SKIPPED}).</p>
 */
public final class BuildResultPromptBlock {

    public enum Audience { EVALUATION, EVOLUTION }

    private static final int STDERR_LINE_LIMIT = 20;

    private BuildResultPromptBlock() {}

    public static String render(CycleContextData ctx, Audience audience) {
        BuildOutcome outcome = ctx.buildOutcome();
        if (outcome == null) return null;
        if (outcome.result() == BuildResult.COMPILE_OK
                || outcome.result() == BuildResult.SKIPPED) {
            return null;
        }

        StringBuilder sb = new StringBuilder("--- BUILD RESULT ---\n");
        sb.append("Result: ").append(outcome.result()).append("\n");
        sb.append("Command: ").append(outcome.command()).append("\n");
        sb.append("Duration: ").append(outcome.durationMs()).append("ms\n");

        if (outcome.stderrTail() != null && !outcome.stderrTail().isBlank()) {
            String[] allLines = outcome.stderrTail().split("\n");
            // Prefer [ERROR] lines — Maven writes compile errors mixed with [INFO] noise.
            String[] errorLines = java.util.Arrays.stream(allLines)
                    .filter(l -> l.contains("[ERROR]") || l.startsWith("ERROR"))
                    .toArray(String[]::new);
            String[] relevant = errorLines.length > 0 ? errorLines : allLines;
            int limit = Math.min(STDERR_LINE_LIMIT, relevant.length);
            sb.append("Compiler errors (").append(limit).append(" lines):\n");
            int start = relevant.length - limit;
            for (int i = start; i < relevant.length; i++) {
                sb.append("  ").append(relevant[i]).append("\n");
            }
        }

        sb.append(audience == Audience.EVALUATION
                ? "The merged code does not compile. Score this mutation REJECTED with a low score; "
                  + "the next cycle should fix the compile error before introducing new functionality."
                : "The current code does not compile. Your mutation MUST address the diagnostic above. "
                  + "If you cannot fix the underlying compile error, do NOT respond with #NoDiff — "
                  + "the build state is ground truth.");
        return sb.toString();
    }
}
