package com.walpro.autobuilder.analysis.pattern;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.ChannelRequest;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
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

    public CycleContextData detect(CycleContextData ctx) {
        if (ctx == null) return null;

        String scopeGoalId = (ctx.currentSubGoalId() != null && !ctx.currentSubGoalId().isBlank())
                ? ctx.currentSubGoalId()
                : ctx.currentGoalId();
        if (scopeGoalId == null || scopeGoalId.isBlank()) return ctx;

        // The heavy throttle has already engaged — no need to pile on.
        if (ctx.hasOpenRequest(BasicSignals.PLANNING_THROTTLED)) return ctx;

        List<PlannerActionSnapshot> recent = agentGraphService.findRecentPlannerActions(scopeGoalId, LOOKBACK);
        if (recent == null || recent.size() < MIN_SAMPLES) return ctx;

        List<String> fps = recent.stream()
                .map(PlannerActionSnapshot::fingerprint)
                .filter(Objects::nonNull)
                .toList();

        if (fps.size() < MIN_SAMPLES) return ctx;

        int total = fps.size();
        int unique = (int) fps.stream().distinct().count();
        double repetitionDensity = 1.0 - ((double) unique / total);
        int maxRun = maxConsecutiveRun(fps);
        double runFactor = (total <= 1) ? 0.0 : (double) Math.max(0, maxRun - 1) / (total - 1);
        double severity = Math.min(1.0, 0.65 * repetitionDensity + 0.35 * runFactor);

        if (severity < SEVERITY_THRESHOLD) return ctx;

        log.warn("Replan thrash detected: severity={}, repetitionDensity={}, maxRun={} in scope '{}'",
                String.format("%.2f", severity),
                String.format("%.2f", repetitionDensity),
                maxRun,
                scopeGoalId);

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
