package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * T4-AN-1: turns the post-merge compile gate's {@link BuildOutcome} into an evaluation
 * heuristic opinion. {@link BuildResult#COMPILE_ERROR} produces a CRITICAL REJECTED opinion
 * — ground-truth evidence overrides every other heuristic. {@link BuildResult#TIMEOUT} is
 * treated as REJECTED at HIGH confidence (the build did not complete; we cannot prove the
 * code is sound, but it may not actually be broken). {@link BuildResult#COMPILE_OK} and
 * {@link BuildResult#SKIPPED} produce no opinion.
 *
 * <p>Registered only when {@code autobuilder.build.enabled=true} so disabled environments
 * don't pay the negotiation cost.
 */
@Component
@ConditionalOnProperty(prefix = "autobuilder.build", name = "enabled", havingValue = "true")
public class BuildResultHeuristic implements EvaluationHeuristic {

    private static final String NAME = "BuildResultHeuristic";

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome outcome) {
        BuildOutcome build = input.buildOutcome();
        if (build == null) return HeuristicOpinion.none(NAME);
        // Only act on build results from the current cycle's merge. A stale result from a
        // previous cycle means the build was broken before this mutation — not after it. Applying
        // a CRITICAL REJECTED opinion in that case creates a deadlock: the mutation that would
        // fix the compile error is itself rejected because the compile error exists.
        if (!build.isFromCycle(input.cycleName())) return HeuristicOpinion.none(NAME);

        return switch (build.result()) {
            case COMPILE_ERROR -> HeuristicOpinion.ofWithScore(
                    NAME,
                    1.0,
                    MutationAcceptanceState.REJECTED,
                    GoalProgressState.REGRESSING,
                    "Post-merge build FAILED: code does not compile. Build output:\n" + build.stderrTail(),
                    0.0
            );
            case TIMEOUT -> HeuristicOpinion.ofWithScore(
                    NAME,
                    0.75,
                    MutationAcceptanceState.REJECTED,
                    GoalProgressState.STAGNATING,
                    "Post-merge build TIMED OUT after " + build.durationMs() + "ms — compilation status unknown.",
                    0.3
            );
            // T4-BH: FRAGMENTATION_REJECTED is a probe-time rejection (no merge ever ran);
            // it should never appear here as a post-merge build outcome. Treat as no-evidence.
            case COMPILE_OK, SKIPPED, FRAGMENTATION_REJECTED -> HeuristicOpinion.none(NAME);
        };
    }
}
