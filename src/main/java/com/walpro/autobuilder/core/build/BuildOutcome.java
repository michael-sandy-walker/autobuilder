package com.walpro.autobuilder.core.build;

/**
 * T4-AN-1: result of one {@link BuildExecutor} invocation. Captures the outcome plus a
 * truncated tail of stderr suitable for direct inclusion in evaluation/evolution prompts.
 *
 * @param result      categorical outcome
 * @param stderrTail  last N lines of stderr (truncated; never null — empty on success/skip)
 * @param durationMs  wall-clock duration of the build invocation
 * @param command     command line that was executed (for logging/prompt context)
 * @param cycleId     cycle in which this build ran — used by BuildResultHeuristic to detect stale results
 */
public record BuildOutcome(
        BuildResult result,
        String stderrTail,
        long durationMs,
        String command,
        String cycleId
) {
    public static BuildOutcome skipped(String reason) {
        return new BuildOutcome(BuildResult.SKIPPED, reason == null ? "" : reason, 0L, "", null);
    }

    public boolean failed() {
        return result == BuildResult.COMPILE_ERROR || result == BuildResult.TIMEOUT;
    }

    public boolean isFromCycle(String candidateCycleId) {
        return cycleId != null && cycleId.equals(candidateCycleId);
    }
}
