package com.walpro.autobuilder.agent.planner;

import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.core.engine.ExecutionAgent;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.goal.AttemptIds;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalSimilarityService;
import com.walpro.autobuilder.goal.GoalStatus;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerAgent implements ExecutionAgent {

    // throttle the planner when the active backlog exceeds this count.
    // Keeps the remaining-goal count bounded and prevents REPLAN_REQUESTED
    // (emitted on every STAGNATING cycle) from flooding the backlog.
    private static final int MAX_ACTIVE_SUBGOALS_BEFORE_THROTTLE = 8;

    private final PlannerLLMAdapter plannerAdapter;
    private final GoalPlannerService goalPlannerService;
    private final GoalSimilarityService similarityService;

    @PostConstruct
    public void registerPlanningCallback() {
        goalPlannerService.setPlanningCallback(this::planInto);
    }

    @Override
    public CycleContextData execute(CycleContextData context) {
        log.info("Executing agent (role={})", role());

        context = ensureFallbackGoalState(context);

        if (context.hasSignal(BasicSignals.SCOPE_OUT_OF_SCOPE)) {
            String best = context.bestMatchSubGoalId();
            if (best != null && !best.isBlank()) {
                boolean switched = false;
                try {
                    switched = goalPlannerService.activateSubGoal(best, "planner: scope repair");
                } catch (Exception e) {
                    log.warn("Planner scope repair activation failed (bestMatch={}): {}", best, e.getMessage());
                }

                if (switched) {
                    GoalEntity g = goalPlannerService.getCurrentSubGoal();
                    context = context
                            .withCurrentSubGoalId(g != null ? g.getId() : best)
                            .withCurrentSubGoalDescription(g != null ? g.getDescription() : context.currentSubGoalDescription());
                } else {
                    log.warn("Planner scope repair could not activate bestMatchSubGoalId={}", best);
                }
            }

            // Consume scope assessment regardless (avoid stale carryover); guard will re-emit next cycle if still needed.
            context = context
                    .withoutSignal(BasicSignals.SCOPE_OUT_OF_SCOPE)
                    .withBestMatchSubGoalId(null);
        }

        PlannerOutcome outcome = plannerAdapter.plan(context);

        boolean replanOverride = context.hasStrongSignal(BasicSignals.REPLAN_REQUESTED, 1.0);
        if (context.hasSignal(BasicSignals.PLANNING_THROTTLED) && !replanOverride) {
            CycleContextData refreshed = refreshGoalState(context)
                    .withPlannerOutcome(outcome)
                    .withLastPlannerOutcome(outcome);

            // record planner attempt/outcome, but do not inject goals TODO: still to test if this holds
            plannerAdapter.evolveGraph(refreshed, outcome);
            return refreshed;
        }
//        if (replanOverride && context.hasSignal(BasicSignals.PLANNING_THROTTLED)) { // TODO: this is commented out, because PLANNING_THROTTLED should still be a signal in non-REPLAN_REQUESTED runs
//            log.info("Planner: PLANNING_THROTTLED cleared — REPLAN_REQUESTED override");
//            context = context.withoutSignal(BasicSignals.PLANNING_THROTTLED);
//        }
        if (replanOverride) {
            context = context.withoutSignal(BasicSignals.REPLAN_REQUESTED);
        }

        // remove goals the planner wants to prune before injecting new ones
        processRemovals(outcome);

        // only inject new goals when the active backlog is empty.
        // replanOverride is intentionally NOT an exception here: when stagnation or exhaustion
        // fires, AutobuilderService raises REPLAN_REQUESTED, which would bypass the guard and
        // inject new goals on top of already-stagnating ones (observed: remaining 5→8 before crash).
        // The correct replan flow is: bad goals get marked GIVEN_UP by the exhaustion handler,
        // which empties the backlog, and then the next planner cycle injects a fresh plan.
        int remainingCount = goalPlannerService.getRemainingGoals().size();
        boolean backlogEmpty = remainingCount == 0;
        InjectionResult injection;
        if (context.stagnationCircuitBreakerActive()) {
            // T2-AH: stagnation circuit breaker — planner in prune-only mode.
            // Removals (processRemovals above) still run; new injection is suppressed.
            log.warn("Planner: stagnation circuit breaker active — skipping subgoal injection (prune-only mode)");
            injection = new InjectionResult(List.of(), 0);
        } else if (backlogEmpty) {
            // inject goals into planner service + similarity registry
            injection = injectOutcomeGoals(outcome, context.currentSubGoalId());
        } else {
            log.info("Planner: skipping injection — {} active subgoals already in backlog", remainingCount);
            injection = new InjectionResult(List.of(), 0);
        }

        plannerAdapter.evolveGraph(context, outcome);

        context = refreshGoalState(context);
        context = context.withSkippedGoals(injection.skipped().isEmpty() ? null : injection.skipped());

        // If more proposals were rejected (duplicate) than accepted, the planner is spinning —
        // raise PLANNING_THROTTLED so the next cycle deprioritises the planner. This is relative:
        // majority-rejection is the threshold, not a fixed count.
        if (injection.skipped().size() > injection.injected()) {
            log.warn("Planner skip ratio exceeded majority ({} skipped / {} injected) — raising PLANNING_THROTTLED",
                    injection.skipped().size(), injection.injected());
            context = context.withSignal(Signal.of(BasicSignals.PLANNING_THROTTLED, 0.6, 0.15, "planner-skip-ratio"));
        }

        // if the backlog already exceeds the per-call cap, throttle the planner to
        // prevent REPLAN_REQUESTED (raised on every STAGNATING cycle) from piling on more work.
        if (goalPlannerService.getRemainingGoals().size() > MAX_ACTIVE_SUBGOALS_BEFORE_THROTTLE) {
            log.warn("Planner: backlog cap reached ({} active subgoals) — raising PLANNING_THROTTLED",
                    goalPlannerService.getRemainingGoals().size());
            context = context.withSignal(Signal.of(BasicSignals.PLANNING_THROTTLED, 0.9, 0.10, "goal-backlog-cap"));
        }

        return context.withPlannerOutcome(outcome).withLastPlannerOutcome(outcome);
    }

    @Override
    public String name() {
        return "planner";
    }

    @Override
    public String role() {
        return "planner";
    }

    @Override
    public java.util.Set<SignalType> getSupportedSignals() {
        return BasicSignals.PLANNER_SIGNALS;
    }

    private CycleContextData ensureFallbackGoalState(CycleContextData context) {
        boolean noCurrent = context.currentGoalId() == null;
        boolean noRemaining = context.remainingGoals() == null || context.remainingGoals().isEmpty();

        if (noCurrent && noRemaining) {
            log.warn("No current or remaining goals found — injecting fallback root goal");

            return context.withCurrentGoalId("bootstrap-project-structure")
                    .withCurrentSubGoalId("create-basic-structure")
                    .withPlannerOutcome(null)
                    .withEvaluationFeedback(context.evaluationFeedback())
                    .withRecommendation(context.recommendation());
        }

        return context;
    }

    /**
     * Callback used by GoalPlannerService to plan under a given parent goal id.
     */
    public void planInto(CycleContextData ctx, String parentGoalId) {
        GoalEntity parent = goalPlannerService.getGoalById(parentGoalId);
        if (parent == null) {
            log.warn("Cannot plan into null parent: {}", parentGoalId);
            return;
        }

        AttemptIds ids = goalPlannerService.ensureAttemptIdsFor(parent.getId());

        GoalEntity root = goalPlannerService.getRootGoal();
        String goalId = root != null ? root.getId() : null;
        String goalDescription = root != null ? root.getDescription() : null;

        String parentDescription = parent.getDescription();

        CycleContextData base = (ctx != null) ? ctx : CycleContextData.builder().build();

        // Always start from ctx, but overwrite the planning scope fields deterministically
        CycleContextData planningContext = base
                .withCurrentGoalId(goalId)
                .withCurrentGoalDescription(goalDescription)
                .withCurrentSubGoalId(parent.getId())
                .withCurrentSubGoalDescription(parentDescription)
                .withLastAttemptId(ids.attemptId())
                .withParentAttemptId(ids.parentAttemptId())
                .withRemainingGoals(goalPlannerService.getRemainingGoals())
                .withCompletedGoals(goalPlannerService.getCompletedGoals());

        PlannerOutcome outcome = plannerAdapter.plan(planningContext);

        // No throttle check here: planInto() is a lifecycle callback, not demand-driven planning.
        // It is only invoked by promoteCurrentSubGoal() when all siblings are exhausted and
        // shouldTriggerPlanner() + plannedOnce already gate it. The PLANNING_THROTTLED signal
        // is stale at this point (set at cycle start, before completions are applied).

        if (outcome.updated()) {
            var sanitized = goalPlannerService.sanitizeProposedSubGoals(outcome.newGoals());
            List<String> knownDescriptions = similarityService.snapshotKnownDescriptions();
            boolean isFirst = true;

            for (String goal : sanitized) {
                if (similarityService.isDuplicate(goal, knownDescriptions)) {
                    log.warn("Skipped similar subgoal: '{}'", goal);
                    continue;
                }
                String id = "goal-" + UUID.randomUUID();
                goalPlannerService.addSubGoal(id, goal, isFirst, parentGoalId);
                log.info("Planner injected nested subgoal [{}] under [{}]: {}", id, parentGoalId, goal);

                isFirst = false;
            }
        } else {
            log.warn("Planner did not produce new subgoals for {}", parentGoalId);
        }

        plannerAdapter.evolveGraph(planningContext, outcome);
    }

    private void processRemovals(PlannerOutcome outcome) {
        if (outcome == null || outcome.goalsToRemove() == null || outcome.goalsToRemove().isEmpty()) return;

        for (String entry : outcome.goalsToRemove()) {
            goalPlannerService.getRemainingGoals().stream()
                    .filter(g -> entry.equalsIgnoreCase(g.getId())
                            || (g.getDescription() != null && entry.equalsIgnoreCase(g.getDescription())))
                    .findFirst()
                    .ifPresentOrElse(
                            g -> {
                                goalPlannerService.markGoalGivenUp(g.getId());
                                log.info("Planner pruned subgoal [{}]: {}", g.getId(), entry);
                            },
                            () -> log.warn("Planner #SubGoalRemove: no active subgoal found for entry='{}'", entry)
                    );
        }

        // If currentSubGoalId is now GIVEN_UP, advance to the next active subgoal.
        GoalEntity current = goalPlannerService.getCurrentSubGoal();
        if (current != null && current.getStatus() == GoalStatus.GIVEN_UP) {
            goalPlannerService.getRemainingGoals().stream().findFirst().ifPresentOrElse(
                    next -> {
                        try {
                            goalPlannerService.setCurrentSubGoal(next.getId());
                            log.info("Advanced current subgoal to [{}] after removal.", next.getId());
                        } catch (Exception e) {
                            log.warn("Could not advance current subgoal after removal: {}", e.getMessage());
                        }
                    },
                    () -> log.warn("All subgoals given up after removal — no active subgoal remains.")
            );
        }
    }

    private record InjectionResult(List<String> skipped, int injected) {}

    private InjectionResult injectOutcomeGoals(PlannerOutcome outcome, String parentGoalId) {
        if (outcome == null || !outcome.updated()) return new InjectionResult(List.of(), 0);

        var sanitized = goalPlannerService.sanitizeProposedSubGoals(outcome.newGoals());
        if (sanitized.isEmpty()) return new InjectionResult(List.of(), 0);

        List<String> knownDescriptions = similarityService.snapshotKnownDescriptions();
        List<String> skipped = new ArrayList<>();
        int injected = 0;
        boolean isFirst = true;
        for (String goal : sanitized) {
            if (similarityService.isDuplicate(goal, knownDescriptions)) {
                log.warn("Skipped similar subgoal: '{}'", goal);
                skipped.add(goal);
                continue;
            }

            String id = "goal-" + UUID.randomUUID();
            if (parentGoalId != null) {
                goalPlannerService.addSubGoal(id, goal, isFirst, parentGoalId);
                log.info("Planner injected nested subgoal [{}] under [{}]: {}", id, parentGoalId, goal);
            } else {
                goalPlannerService.addSubGoal(id, goal, isFirst);
                log.info("Planner injected new subgoal [{}]: {}", id, goal);
            }

            injected++;
            isFirst = false;
        }

        log.info("Planner outcome message: {}", outcome.message());
        return new InjectionResult(skipped, injected);
    }

    private CycleContextData refreshGoalState(CycleContextData ctx) {
        if (ctx == null) return null;

        GoalEntity root = goalPlannerService.getRootGoal();
        GoalEntity current = goalPlannerService.getCurrentSubGoal();

        String rootId = root != null ? root.getId() : ctx.currentGoalId();
        String rootDesc = root != null ? root.getDescription() : ctx.currentGoalDescription();

        String subId = current != null ? current.getId() : ctx.currentSubGoalId();
        String subDesc = current != null ? current.getDescription() : ctx.currentSubGoalDescription();

        return ctx
                .withCurrentGoalId(rootId)
                .withCurrentGoalDescription(rootDesc)
                .withCurrentSubGoalId(subId)
                .withCurrentSubGoalDescription(subDesc)
                .withRemainingGoals(goalPlannerService.getRemainingGoals())
                .withCompletedGoals(goalPlannerService.getCompletedGoals());
    }

}
