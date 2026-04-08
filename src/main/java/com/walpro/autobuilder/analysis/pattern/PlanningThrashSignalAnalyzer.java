package com.walpro.autobuilder.analysis.pattern;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.planner.PlannerInflowStats;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PlanningThrashSignalAnalyzer {

    // Sliding-window settings
    private static final Duration WINDOW = Duration.ofMinutes(45);
    private static final int BASELINE_WINDOWS = 8; // uses older windows for baseline (adaptive)

    // Safety: minimum evidence before we throttle at all
    private static final int MIN_INFLOW_TO_CONSIDER = 3;

    private final AgentGraphService agentGraphService;
    private final GoalPlannerService goalPlannerService;

    public CycleContextData analyze(CycleContextData ctx) {
        if (ctx == null) return null;

        String scopeGoalId = /*(ctx.currentSubGoalId() != null && !ctx.currentSubGoalId().isBlank())
                ? ctx.currentSubGoalId()
                :*/ ctx.currentGoalId(); // TODO: maybe remove the id completely

        if (scopeGoalId == null || scopeGoalId.isBlank()) return ctx;

        boolean alreadyThrottled = ctx.hasSignal(BasicSignals.PLANNING_THROTTLED);

        Instant now = Instant.now();
        Instant currentSince = now.minus(WINDOW);
        PlannerInflowStats currentInflow = agentGraphService.getPlannerInflowSince(scopeGoalId, currentSince);
        int currentOutflow = countCompletedSince(currentSince);

        if (currentInflow.totalNewGoals() < MIN_INFLOW_TO_CONSIDER && !alreadyThrottled) {
            return ctx;
        }

        // Build baseline using older windows: [W..2W], [2W..3W], ...
        List<Integer> inflowSeries = new ArrayList<>();
        List<Integer> outflowSeries = new ArrayList<>();
        List<Double> ratioSeries = new ArrayList<>();

        for (int i = 1; i <= BASELINE_WINDOWS; i++) {
            Instant since = now.minus(WINDOW.multipliedBy(i + 0L));
            Instant until = now.minus(WINDOW.multipliedBy(i - 1L));

            // inflow between since..until (approx by subtracting cumulative since; done with 2 queries)
            PlannerInflowStats inflowSince = agentGraphService.getPlannerInflowSince(scopeGoalId, since);
            PlannerInflowStats inflowUntil = agentGraphService.getPlannerInflowSince(scopeGoalId, until);
            int inflow = Math.max(0, inflowSince.totalNewGoals() - inflowUntil.totalNewGoals());

            int outflow = countCompletedBetween(since, until);

            inflowSeries.add(inflow);
            outflowSeries.add(outflow);

            double ratio = inflow / Math.max(1.0, (double) outflow);
            ratioSeries.add(ratio);
        }

        Stats inflowStats = Stats.robust(inflowSeries);
        Stats ratioStats = Stats.robustD(ratioSeries);

        double currentRatio = currentInflow.totalNewGoals() / Math.max(1.0, (double) currentOutflow);

        // Adaptive trigger:
        // - inflow is an outlier (median + k*MAD)
        // - OR ratio is an outlier (median + k*MAD)
        // - OR we have inflow but baseline outflow is normally >0 and now is stuck at 0
        double inflowThreshold = inflowStats.median + inflowStats.mad * 3.0;
        double ratioThreshold = ratioStats.median + ratioStats.mad * 3.0;

        boolean baselineUsuallyCompletesSomething = median(outflowSeries) >= 1;

        // Only fire outlier checks when there is meaningful baseline history.
        // Without it, the Math.max floors act as absolute limits and misfire on the initial planning burst.
        boolean hasBaseline = inflowStats.median > 0;

        boolean inflowOutlier = hasBaseline && currentInflow.totalNewGoals() > Math.max(3, (int) Math.ceil(inflowThreshold));
        boolean ratioOutlier = hasBaseline && currentRatio > Math.max(2.0, ratioThreshold);
        boolean stuckOutflow = baselineUsuallyCompletesSomething
                && currentInflow.totalNewGoals() >= MIN_INFLOW_TO_CONSIDER
                && currentOutflow == 0;

        boolean throttle = inflowOutlier || ratioOutlier || stuckOutflow;

        // Adaptive release:
        // release when current falls back near baseline (within ~1.5 MAD)
        double inflowRelease = inflowStats.median + inflowStats.mad * 1.5;
        double ratioRelease = ratioStats.median + ratioStats.mad * 1.5;

        // Caught-up release: all injected goals have been worked through (ratio ≤ 1.0).
        // The baseline-relative inflow check cannot release the throttle in a fresh environment
        // because inflow is an absolute count that stays at N while the baseline windows are empty,
        // keeping inflowRelease clamped at 2 even after all N goals complete.
        boolean caughtUp = alreadyThrottled && currentOutflow > 0 && currentRatio <= 1.0;

        boolean release = caughtUp
                || (alreadyThrottled
                && currentInflow.totalNewGoals() <= Math.max(2, (int) Math.ceil(inflowRelease))
                && currentRatio <= Math.max(1.5, ratioRelease));

        if (release) {
            return ctx.withoutSignal(BasicSignals.PLANNING_THROTTLED);
        }

        if (!throttle) {
            return ctx;
        }

        String msg =
                "Planning throttled (adaptive): inflow=" + currentInflow.totalNewGoals()
                        + ", outflow(completed+given_up)=" + currentOutflow
                        + ", ratio=" + round2(currentRatio)
                        + "; baseline inflow median=" + round1(inflowStats.median)
                        + " mad=" + round1(inflowStats.mad)
                        + "; baseline ratio median=" + round2(ratioStats.median)
                        + " mad=" + round2(ratioStats.mad)
                        + ".";

        Signal s = Signal.of(
                BasicSignals.PLANNING_THROTTLED,
                1.0,
                0.10,
                msg
        );

        return alreadyThrottled ? ctx : ctx.withSignal(s);
    }

    private int countCompletedSince(Instant since) {
        int count = 0;
        for (GoalEntity g : goalPlannerService.getAllGoals()) {
            if (g == null) continue;
            Instant t = resolvedInstant(g);
            if (t != null && t.isAfter(since)) count++;
        }
        return count;
    }

    private int countCompletedBetween(Instant since, Instant until) {
        int count = 0;
        for (GoalEntity g : goalPlannerService.getAllGoals()) {
            if (g == null) continue;
            Instant t = resolvedInstant(g);
            if (t != null && !t.isBefore(since) && t.isBefore(until)) count++;
        }
        return count;
    }

    /**
     * Returns the "resolved" timestamp for a goal — the moment it was either completed or given up.
     * Both outcomes mean the goal was worked through, so both count equally toward outflow.
     * Uses persisted fields ({@link GoalEntity#getCompleted()}, {@link GoalEntity#getGivenUp()})
     * rather than metadata, which is wiped when {@code refreshFromGraph()} replaces the in-memory
     * goal map after promotion.
     */
    private Instant resolvedInstant(GoalEntity g) {
        if (g.getStatus() == GoalStatus.COMPLETED) return g.getCompleted();
        if (g.getStatus() == GoalStatus.GIVEN_UP) return g.getGivenUp();
        return null;
    }

    private static double median(List<Integer> values) {
        if (values == null || values.isEmpty()) return 0.0;
        List<Integer> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private record Stats(double median, double mad) {
        static Stats robust(List<Integer> values) {
            if (values == null || values.isEmpty()) return new Stats(0.0, 0.0);
            double med = PlanningThrashSignalAnalyzer.median(values);
            List<Double> dev = values.stream().map(v -> Math.abs(v - med)).sorted().toList();
            double mad = dev.isEmpty() ? 0.0 : (dev.size() % 2 == 1
                    ? dev.get(dev.size() / 2)
                    : (dev.get(dev.size() / 2 - 1) + dev.get(dev.size() / 2)) / 2.0);
            // avoid zero-width band
            return new Stats(med, Math.max(0.5, mad));
        }

        static Stats robustD(List<Double> values) {
            if (values == null || values.isEmpty()) return new Stats(0.0, 0.0);
            List<Double> sorted = values.stream().sorted().toList();
            int n = sorted.size();
            double med = (n % 2 == 1) ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;

            List<Double> dev = sorted.stream().map(v -> Math.abs(v - med)).sorted(Comparator.naturalOrder()).toList();
            double mad = dev.isEmpty() ? 0.0 : (dev.size() % 2 == 1
                    ? dev.get(dev.size() / 2)
                    : (dev.get(dev.size() / 2 - 1) + dev.get(dev.size() / 2)) / 2.0);
            return new Stats(med, Math.max(0.25, mad));
        }
    }

    private static String round2(double v) {
        return Double.toString(Math.round(v * 100.0) / 100.0);
    }

    private static String round1(double v) {
        return Double.toString(Math.round(v * 10.0) / 10.0);
    }
}