package com.walpro.autobuilder.protocol.build;

import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.BuildStderrLines;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import java.util.List;

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

    public enum Audience { EVALUATION, EVOLUTION, PLANNER }

    private static final int STDERR_LINE_LIMIT = 20;

    private BuildResultPromptBlock() {}

    public static String render(CycleContextData ctx, Audience audience) {
        BuildOutcome outcome = ctx.buildOutcome();
        // T4-BD: when the post-merge buildOutcome is absent or clean, fall back to the latest
        // compile-probe outcome — represents the same "build is broken" ground truth that was
        // previously laundered through EvaluationOutcome.feedbackMessage as a magic-string
        // "Compile probe failed:" prefix and rendered by a separate prompt block.
        if (outcome == null
                || outcome.result() == BuildResult.COMPILE_OK
                || outcome.result() == BuildResult.SKIPPED) {
            BuildOutcome probe = ctx.lastProbeOutcome();
            if (probe == null || probe.result() != BuildResult.COMPILE_ERROR) return null;
            outcome = probe;
        }

        StringBuilder sb = new StringBuilder("--- BUILD RESULT ---\n");
        sb.append("Result: ").append(outcome.result()).append("\n");
        sb.append("Command: ").append(outcome.command()).append("\n");
        sb.append("Duration: ").append(outcome.durationMs()).append("ms\n");

        if (outcome.stderrTail() != null && !outcome.stderrTail().isBlank()) {
            List<String> relevant = BuildStderrLines.errorLines(
                    outcome.stderrTail(),
                    BuildStderrLines.Options.promptBlockTail(STDERR_LINE_LIMIT));
            if (!relevant.isEmpty()) {
                sb.append("Compiler errors (").append(relevant.size()).append(" lines):\n");
                for (String line : relevant) {
                    sb.append("  ").append(line).append("\n");
                }
            }
        }

        // T4-AN-5: surface the build tool's own diagnostic (captured on the failed probe) to the
        // audiences that act on it. The evaluator only scores REJECTED, so it doesn't need it.
        String diagnostic = ctx.buildDiagnostic();
        if (diagnostic != null && !diagnostic.isBlank() && audience != Audience.EVALUATION) {
            sb.append("\n--- BUILD DIAGNOSTIC (from the build tool itself) ---\n");
            sb.append(diagnostic).append("\n");
            sb.append("Use this to pick correct dependency coordinates/versions instead of guessing.\n\n");
        }

        sb.append(switch (audience) {
            case EVALUATION -> "The merged code does not compile. Score this mutation REJECTED with a low score; "
                    + "the next cycle should fix the compile error before introducing new functionality.";
            case EVOLUTION -> "The current code does not compile. Your mutation MUST address the diagnostic above. "
                    + "If you cannot fix the underlying compile error, do NOT respond with #NoDiff — "
                    + "the build state is ground truth.";
            case PLANNER -> "The codebase does not compile. While the build is COMPILE_ERROR, your only valid "
                    + "#SubGoalAppend is build-fix work. Do NOT remove a subgoal that carries [BUILD_FIX] metadata "
                    + "until the build passes — that subgoal protects the build invariant.";
        });
        return sb.toString();
    }
}
