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
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerAgent implements ExecutionAgent {

    // throttle the planner when the active backlog exceeds this count.
    // Keeps the remaining-goal count bounded and prevents REPLAN_REQUESTED
    // (emitted on every STAGNATING cycle) from flooding the backlog.
    private static final int MAX_ACTIVE_SUBGOALS_BEFORE_THROTTLE = 8;

    // Maximum number of times a textually similar goal may be given up before re-injection is blocked.
    // Allows a bounded number of retries on goals that were abandoned due to exhaustion rather than completion.
    private static final int GIVEN_UP_RETRY_LIMIT = 2;

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
        // T1-BV: capture subgoal ID before removals so we can detect a mid-cycle subgoal switch
        String subGoalIdBeforeRemovals = context.currentSubGoalId();
        processRemovals(outcome);
        GoalEntity afterRemoval = goalPlannerService.getCurrentSubGoal();
        String subGoalIdAfterRemovals = afterRemoval != null ? afterRemoval.getId() : null;
        if (!Objects.equals(subGoalIdBeforeRemovals, subGoalIdAfterRemovals)) {
            log.info("Planner: active subgoal changed [{}] → [{}] after removal — clearing stale SUBGOAL_EXHAUSTED + STAGNATION",
                    subGoalIdBeforeRemovals, subGoalIdAfterRemovals);
            context = context
                    .withoutSignal(BasicSignals.SUBGOAL_EXHAUSTED)
                    .withoutSignal(BasicSignals.STAGNATION); // T1-BX: remove STAGNATION
        }

        // only inject new goals when the active backlog is empty.
        // replanOverride is intentionally NOT an exception here: when stagnation or exhaustion
        // fires, AutobuilderService raises REPLAN_REQUESTED, which would bypass the guard and
        // inject new goals on top of already-stagnating ones (observed: remaining 5→8 before crash).
        // The correct replan flow is: bad goals get marked GIVEN_UP by the exhaustion handler,
        // which empties the backlog, and then the next planner cycle injects a fresh plan.
        int remainingCount = goalPlannerService.getRemainingGoals().size();
        boolean backlogEmpty = remainingCount == 0;

        InjectionResult injection;
        if (context.stagnationCircuitBreakerActive() && !backlogEmpty) {
            // T1-AH: stagnation circuit breaker — planner in prune-only mode.
            // Removals (processRemovals above) still run; new injection is suppressed.
            // Exception: if the backlog is already empty (currentSubGoalId==null), prune-only mode
            // is incoherent — there is nothing to prune, and all other agents are blocked on a
            // missing subgoal. Allow injection so the loop can recover.
            log.warn("Planner: stagnation circuit breaker active — skipping subgoal injection (prune-only mode)");
            injection = new InjectionResult(List.of(), 0);
        } else if (backlogEmpty) {
            // T1-CA: when the backlog is empty and the current subgoal pointer was not advanced
            // (no next sibling exists), it still points to the just-pruned GIVEN_UP goal.
            // Passing that ID to injectOutcomeGoals would nest new goals under a dead parent,
            // causing an orphaned-child cascade.  Compute a clean parent ID without touching
            // context so that evolveGraph and refreshGoalState downstream are unaffected.
            String effectiveParentId = context.currentSubGoalId();
            if (effectiveParentId != null && afterRemoval != null && afterRemoval.getStatus() == GoalStatus.GIVEN_UP) {
                log.info("T1-CA: backlog empty with current subgoal [{}] GIVEN_UP — injecting at root to prevent nesting under dead parent",
                        subGoalIdAfterRemovals);
                effectiveParentId = null;
            }
            // inject goals into planner service + similarity registry
            injection = injectOutcomeGoals(outcome, effectiveParentId);
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
     * Returns the number of subgoals successfully injected (0 if all were blocked by deduplication).
     */
    public int planInto(CycleContextData ctx, String parentGoalId) {
        GoalEntity parent = goalPlannerService.getGoalById(parentGoalId);
        if (parent == null) {
            log.warn("Cannot plan into null parent: {}", parentGoalId);
            return 0;
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

        // -1 = planner had nothing to propose (genuine completion signal)
        //  0 = planner proposed goals but all blocked by dedup (T1-BK dedup scenario)
        // >0 = goals injected successfully
        if (!outcome.updated()) {
            log.warn("Planner did not produce new subgoals for {}", parentGoalId);
            plannerAdapter.evolveGraph(planningContext, outcome);
            return -1;
        }

        var sanitized = goalPlannerService.sanitizeProposedSubGoals(outcome.newGoals());
        if (sanitized.isEmpty()) {
            log.warn("Planner produced no non-trivial subgoals for {} after sanitization", parentGoalId);
            plannerAdapter.evolveGraph(planningContext, outcome);
            return -1;
        }

        List<String> knownDescriptions = buildKnownDescriptions();
        boolean isFirst = true;
        int injected = 0;

        for (String goal : sanitized) {
            if (similarityService.isDuplicate(goal, knownDescriptions)) {
                log.warn("Skipped similar subgoal: '{}'", goal);
                continue;
            }

            long givenUpCount = similarityService.countGivenUpMatches(goal);
            if (givenUpCount >= GIVEN_UP_RETRY_LIMIT) {
                log.warn("Skipped unachievable subgoal ({}/{} prior given-up attempts): '{}'",
                        givenUpCount, GIVEN_UP_RETRY_LIMIT, goal);
                continue;
            }

            // T2-BU Fix B: validate criteria before adding to goal store
            List<String> criteria = outcome.goalCriteria() != null ? outcome.goalCriteria().get(goal) : null;
            if (criteria == null || criteria.isEmpty()) {
                log.warn("Planner: skipping nested subgoal — no acceptance criteria: '{}'", goal);
                continue;
            }

            String id = "goal-" + UUID.randomUUID();
            goalPlannerService.addSubGoal(id, goal, isFirst, parentGoalId);
            log.info("Planner injected nested subgoal [{}] under [{}]: {}", id, parentGoalId, goal);

            goalPlannerService.setAcceptanceCriteria(id, criteria);
            log.info("Planner stored {} acceptance criteria for subgoal [{}]", criteria.size(), id);

            injected++;
            isFirst = false;
        }

        plannerAdapter.evolveGraph(planningContext, outcome);
        return injected; // 0 if all proposals were deduped (T1-BK); >0 if some were injected
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

        List<String> knownDescriptions = buildKnownDescriptions();
        List<String> skipped = new ArrayList<>();
        int injected = 0;
        boolean isFirst = true;
        for (String goal : sanitized) {
            if (similarityService.isDuplicate(goal, knownDescriptions)) {
                log.warn("Skipped similar subgoal: '{}'", goal);
                skipped.add(goal);
                continue;
            }

            long givenUpCount = similarityService.countGivenUpMatches(goal);
            if (givenUpCount >= GIVEN_UP_RETRY_LIMIT) {
                log.warn("Skipped unachievable subgoal ({}/{} prior given-up attempts): '{}'",
                        givenUpCount, GIVEN_UP_RETRY_LIMIT, goal);
                skipped.add(goal);
                continue;
            }

            // T2-BU Fix B: validate criteria before adding to goal store — criteria-less
            // subgoals silence the T2-BL criteria-progress suppression, so skip them.
            List<String> criteria = outcome.goalCriteria() != null ? outcome.goalCriteria().get(goal) : null;
            if (criteria == null || criteria.isEmpty()) {
                log.warn("Planner: skipping subgoal — no acceptance criteria: '{}'", goal);
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

            goalPlannerService.setAcceptanceCriteria(id, criteria);
            log.debug("Planner stored {} acceptance criteria for subgoal [{}]", criteria.size(), id);

            injected++;
            isFirst = false;
        }

        log.info("Planner outcome message: {}", outcome.message());
        return new InjectionResult(skipped, injected);
    }

    /**
     * T1-BT: builds the dedup reference list for injection guards.
     * Extends the standard snapshot (ACTIVE + COMPLETED goals) with descriptions of GIVEN_UP
     * goals that had at least one merge — these were pruned after successful work, not failed,
     * so they must never be re-injected even though they only have one GIVEN_UP match (below
     * the GIVEN_UP_RETRY_LIMIT of 2).
     */
    private List<String> buildKnownDescriptions() {
        List<String> known = new ArrayList<>(similarityService.snapshotKnownDescriptions());
        goalPlannerService.getAllGoals().stream()
                .filter(g -> g.getStatus() == GoalStatus.GIVEN_UP && g.getMergeCount() > 0)
                .map(GoalEntity::getDescription)
                .filter(d -> d != null && !d.isBlank())
                .forEach(known::add);
        return known;
    }

    private CycleContextData refreshGoalState(CycleContextData ctx) {
        if (ctx == null) return null;

        GoalEntity root = goalPlannerService.getRootGoal();
        GoalEntity current = goalPlannerService.getCurrentSubGoal();

        String rootId = root != null ? root.getId() : ctx.currentGoalId();
        String rootDesc = root != null ? root.getDescription() : ctx.currentGoalDescription();

        String subId = current != null ? current.getId() : ctx.currentSubGoalId();
        String subDesc = current != null ? current.getDescription() : ctx.currentSubGoalDescription();

        // T3-BD: reload acceptance criteria from the (possibly newly set) current subgoal so that
        // the evaluator running later in the same cycle sees freshly injected criteria rather than
        // the stale empty list captured at cycle-start by CycleContextCoordinator.
        @SuppressWarnings("unchecked")
        List<String> subgoalCriteria = current != null && current.getMetadata() != null
                ? (List<String>) current.getMetadata().getOrDefault("acceptanceCriteria", List.of())
                : List.of();

        return ctx
                .withCurrentGoalId(rootId)
                .withCurrentGoalDescription(rootDesc)
                .withCurrentSubGoalId(subId)
                .withCurrentSubGoalDescription(subDesc)
                .withRemainingGoals(goalPlannerService.getRemainingGoals())
                .withCompletedGoals(goalPlannerService.getCompletedGoals())
                .withCurrentSubGoalCriteria(subgoalCriteria.isEmpty() ? null : subgoalCriteria);
    }

}
