package com.walpro.autobuilder.analysis.pattern;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.ChannelRequest;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.planner.PlannerActionSnapshot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * T2-R: Detects planner thrash early — before the count-based {@code PLANNING_THROTTLED}
 * mechanism engages — by watching fingerprint repetition in recent planner actions.
 *
 * <p>Uses a shorter lookback than {@code PlannerRepetitionHeuristic} (6 vs 12 actions)
 * and fires at a lower severity threshold (0.50) to act as a leading indicator. When
 * detected, emits {@code REPLAN_THRASH_DETECTED}, which steers
 * {@code RuleBasedDeliberationProposer} to request a REPLAN with a "broaden approach"
 * directive before the full throttle window fills up.
 *
 * <p>Does not fire when {@code PLANNING_THROTTLED} is already active (the heavier
 * mechanism has already engaged) or when there is insufficient data.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReplanPatternDetector {

    static final int LOOKBACK = 6;
    static final int MIN_SAMPLES = 4;
    static final double SEVERITY_THRESHOLD = 0.50;

    private final AgentGraphService agentGraphService;

    // T5-I: optional so unit tests constructing the detector directly need no logger stub.
    @lombok.Setter
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.observability.behaviour.BehaviourLogger behaviourLogger;

    /**
     * T5-BH liveness: this detector fired 0 times across 237 recorded runs, and its inputs are graph
     * planner actions, so no offline replay can say WHY. Rather than delete a wired detector on an
     * unexplained zero, each early-out is now named in the log — one live run then tells us which
     * precondition never holds, and the recalibrate-or-delete call rests on that instead of a guess.
     * Silence was the problem; an instrument that reports its own abstention is the fix.
     *
     * <p>Logged at INFO deliberately: this package is pinned to INFO, so a {@code debug} line here
     * reaches no field log and the instrument would be as silent as the detector it observes. One
     * line per cycle is the whole volume.
     */
    private CycleContextData abstain(CycleContextData ctx, String reason) {
        log.info("T5-BH liveness: ReplanPatternDetector abstained — {}", reason);
        return ctx;
    }

    public CycleContextData detect(CycleContextData ctx) {
        if (ctx == null) return null;

        String scopeGoalId = (ctx.currentSubGoalId() != null && !ctx.currentSubGoalId().isBlank())
                ? ctx.currentSubGoalId()
                : ctx.currentGoalId();
        if (scopeGoalId == null || scopeGoalId.isBlank()) return abstain(ctx, "no scope goal id");

        // The heavy throttle has already engaged — no need to pile on.
        if (ctx.hasOpenRequest(BasicSignals.PLANNING_THROTTLED)) {
            return abstain(ctx, "PLANNING_THROTTLED already open");
        }

        List<PlannerActionSnapshot> recent = agentGraphService.findRecentPlannerActions(scopeGoalId, LOOKBACK);
        if (recent == null || recent.size() < MIN_SAMPLES) {
            return abstain(ctx, "only " + (recent == null ? 0 : recent.size())
                    + " recent planner actions, need " + MIN_SAMPLES);
        }

        List<String> fps = recent.stream()
                .map(PlannerActionSnapshot::fingerprint)
                .filter(Objects::nonNull)
                .toList();

        if (fps.size() < MIN_SAMPLES) {
            return abstain(ctx, "only " + fps.size() + " of " + recent.size()
                    + " planner actions carry a fingerprint, need " + MIN_SAMPLES);
        }

        int total = fps.size();
        int unique = (int) fps.stream().distinct().count();
        double repetitionDensity = 1.0 - ((double) unique / total);
        int maxRun = maxConsecutiveRun(fps);
        double runFactor = (total <= 1) ? 0.0 : (double) Math.max(0, maxRun - 1) / (total - 1);
        double severity = Math.min(1.0, 0.65 * repetitionDensity + 0.35 * runFactor);

        if (severity < SEVERITY_THRESHOLD) {
            return abstain(ctx, String.format("severity %.2f below threshold %.2f", severity, SEVERITY_THRESHOLD));
        }

        log.warn("Replan thrash detected: severity={}, repetitionDensity={}, maxRun={} in scope '{}'",
                String.format("%.2f", severity),
                String.format("%.2f", repetitionDensity),
                maxRun,
                scopeGoalId);

        // T5-I: fired-only emission.
        if (behaviourLogger != null) {
            behaviourLogger.patternDetected(ctx.cycleName(), "ReplanPatternDetector",
                    String.format("severity=%.2f repetitionDensity=%.2f maxRun=%d scope=%s",
                            severity, repetitionDensity, maxRun, scopeGoalId),
                    "REPLAN_THRASH_DETECTED");
        }

        return ctx.issueRequest(ChannelRequest.of(BasicSignals.REPLAN_THRASH_DETECTED, 1.0, "ReplanPatternDetector", ChannelRequest.horizonFromDecay(1.0, 0.25)));
    }

    private static int maxConsecutiveRun(List<String> fps) {
        int best = 1;
        int run = 1;
        for (int i = 1; i < fps.size(); i++) {
            if (Objects.equals(fps.get(i), fps.get(i - 1))) {
                run++;
                if (run > best) best = run;
            } else {
                run = 1;
            }
        }
        return best;
    }
}
