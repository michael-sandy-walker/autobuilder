package com.walpro.autobuilder.agent.planner;

import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.core.engine.ExecutionAgent;
import com.walpro.autobuilder.core.grounding.SymbolGroundingService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.goal.AttemptIds;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalSimilarityService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.AbandonedGoalCluster;
import com.walpro.autobuilder.goal.AbandonedGoalQueryService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerAgent implements ExecutionAgent {

    // throttle the planner when the active backlog exceeds this count.
    // Keeps the remaining-goal count bounded and prevents REPLAN_REQUESTED
    // (emitted on every STAGNATING cycle) from flooding the backlog.
    private static final int MAX_ACTIVE_SUBGOALS_BEFORE_THROTTLE = 8;

    // T1-CI/T1-DE: shared via GoalSimilarityService to keep coverage-review dedup in sync.
    private static final int GIVEN_UP_RETRY_LIMIT = GoalSimilarityService.GIVEN_UP_RETRY_LIMIT;

    // T2-CB: maximum consecutive empty-backlog injections permitted while the stagnation circuit
    // breaker is active without an intervening merge. After this limit the loop halts rather than
    // churning through more injection→exhaust→empty-backlog→bypass cycles.
    private static final int EMPTY_BACKLOG_INJECTION_LIMIT = 3;

    // T2-CB: tracks how many consecutive times the backlog was empty AND the stagnation circuit
    // breaker was active when the planner injected new goals. Reset on any merged cycle or when
    // the circuit breaker is no longer active.
    private int consecutiveEmptyBacklogInjectionsWithoutMerge = 0;

    // T1-EI: number of distinct cycles in which a dedup-blocked proposal triggers a rehabilitation
    // vote before the matched GIVEN_UP goal is auto-reactivated. Mirrors the "2 within 3 cycles"
    // suggestion in the ticket — kept simple as a flat threshold on the cumulative vote count.
    private static final int REHABILITATION_VOTE_THRESHOLD = 2;

    // T1-EI: number of times a single GIVEN_UP goal may be auto-rehabilitated before it is
    // permanently re-given-up with IRREDUCIBLE_GAP. Counter is reset by a successful merge on
    // the rehabilitated goal (see MergeAgent rehabilitation accounting).
    private static final int REHABILITATION_FAIL_CAP = 2;

    private final PlannerLLMAdapter plannerAdapter;
    private final GoalPlannerService goalPlannerService;
    private final GoalSimilarityService similarityService;
    private final SymbolGroundingService groundingService;
    private final AbandonedGoalQueryService abandonedGoalQueryService;

    @PostConstruct
    public void registerPlanningCallback() {
        goalPlannerService.setPlanningCallback(this::planInto);
    }

    @Override
    public CycleContextData execute(CycleContextData context) {
        log.info("Executing agent (role={})", role());

        // T2-CB: a successful merge means the loop is healthy — reset the empty-backlog churn counter.
        if (context.merged()) {
            consecutiveEmptyBacklogInjectionsWithoutMerge = 0;
        }

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

        PlannerOutcome rawOutcome = plannerAdapter.plan(context);
        // T2-DC: rewrite hallucinated paths/symbols before they propagate into goal injection.
        Grounded grounded = applyGrounding(rawOutcome);
        PlannerOutcome outcome = grounded.outcome();
        for (String sym : grounded.undefinedSymbols()) {
            context = context.withSignal(Signal.of(BasicSignals.MISSING_SYMBOL, 1.0, 0.25, "planner-grounding:" + sym));
        }

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
        int prunedCount = processRemovals(outcome, context);
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
        // Structured replan: same response included #SubGoalRemove that pruned ≥1 goal AND
        // #SubGoalAppend with new goals. This is an explicit replacement, not REPLAN_REQUESTED-
        // driven pile-on — the LLM made room before proposing. Bypass the backlog-non-empty guard.
        boolean structuredReplan = !backlogEmpty
                && prunedCount > 0
                && outcome.newGoals() != null && !outcome.newGoals().isEmpty();

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
            // T1-CP: nest replacement subgoals under the GIVEN_UP subgoal rather than injecting
            // at root. Injecting at root (T1-CA) inflated the root's direct-child denominator,
            // making the completion ratio unreachable when multiple replan rounds occurred
            // (e.g. 6/14 = 43% < 50% threshold instead of 6/8 = 75%).
            String effectiveParentId = context.currentSubGoalId();
            if (effectiveParentId != null && afterRemoval != null
                    && (afterRemoval.getStatus() == GoalStatus.GIVEN_UP
                            || afterRemoval.getStatus() == GoalStatus.SUBSUMED)) {
                log.info("backlog empty with current subgoal [{}] terminal — injecting replacements under dead parent",
                        subGoalIdAfterRemovals);
                // T1-CH: clear stale evolution fingerprints so the new subgoals get a clean
                // evolution slate. Without this the scope guard compares their descriptions
                // against the dead subgoal's diff → always OUT_OF_SCOPE →
                // SUBGOAL_SCOPE_MISMATCH → mut=null cascade → immediate GIVEN_UP again.
                context = context
                        .withLastEvolutionFingerprint(null)
                        .withLastEvaluatedEvolutionFingerprint(null);
                log.info("cleared evolution fingerprints on replacement injection under GIVEN_UP parent");
            }

            // T2-CB: when the stagnation circuit breaker is active and the backlog is empty,
            // T2-BO's bypass allows injection to prevent deadlock — but if every injection
            // immediately exhausts without a merge, the loop churns indefinitely. Cap it.
            if (context.stagnationCircuitBreakerActive()) {
                consecutiveEmptyBacklogInjectionsWithoutMerge++;
                if (consecutiveEmptyBacklogInjectionsWithoutMerge >= EMPTY_BACKLOG_INJECTION_LIMIT) {
                    log.error("T2-CB: {} consecutive empty-backlog injections with stagnation circuit breaker active and no merge — halting to prevent infinite churn.",
                            consecutiveEmptyBacklogInjectionsWithoutMerge);
                    context = context
                            .withSignal(Signal.of(BasicSignals.GOAL_EXHAUSTED, 1.0, 0.0, "empty-backlog-injection-limit"))
                            .withSignal(Signal.of(BasicSignals.SYSTEM_PAUSED, 1.0, 0.0, "empty-backlog-injection-limit"));
                    return context.withPlannerOutcome(outcome).withLastPlannerOutcome(outcome);
                }
                log.warn("Planner: stagnation circuit breaker active but backlog empty — allowing injection ({}/{} before halt)",
                        consecutiveEmptyBacklogInjectionsWithoutMerge, EMPTY_BACKLOG_INJECTION_LIMIT);
            } else {
                consecutiveEmptyBacklogInjectionsWithoutMerge = 0;
            }

            // inject goals into planner service + similarity registry
            injection = injectOutcomeGoals(outcome, effectiveParentId, grounded.undefinedPerDescription(), context);
        } else if (structuredReplan) {
            log.info("Planner: structured replan (pruned {}, proposing {}) — bypassing backlog-non-empty guard",
                    prunedCount, outcome.newGoals().size());
            injection = injectOutcomeGoals(outcome, null, grounded.undefinedPerDescription(), context);
        } else {
            log.info("Planner: skipping injection — {} active subgoals already in backlog", remainingCount);
            injection = new InjectionResult(List.of(), 0);
        }

        plannerAdapter.evolveGraph(context, outcome);

        // T1-EI: apply any LLM-driven #SubGoalReactivate before refreshGoalState so the freshly
        // ACTIVE subgoal can be promoted to current.
        context = applyExplicitReactivations(outcome, context);

        context = refreshGoalState(context);
        context = context.withSkippedGoals(injection.skipped().isEmpty() ? null : injection.skipped());
        context = context.withSkippedProposalMatches(
                injection.proposalMatches().isEmpty() ? null : injection.proposalMatches());

        // T1-EI: emit IRREDUCIBLE_GAP for any goal that hit the rehabilitation fail-cap this cycle.
        for (String id : injection.irreducibleGoalIds()) {
            context = context.withSignal(Signal.of(BasicSignals.IRREDUCIBLE_GAP, 1.0, 0.10,
                    "rehabilitation-cap:" + id));
        }
        // T1-EI: surface auto-reactivated ids so downstream agents see them as fresh subgoals.
        if (!injection.reactivatedGoalIds().isEmpty()) {
            log.info("T1-EI: auto-reactivated {} goal(s) this cycle: {}",
                    injection.reactivatedGoalIds().size(), injection.reactivatedGoalIds());
        }

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

        PlannerOutcome rawOutcome = plannerAdapter.plan(planningContext);
        // T2-DC: ground citations once at the outcome level. Cannot emit MISSING_SYMBOL onto
        // the live cycle context from this callback (planInto returns int, not a context); log
        // undefined symbols so they're visible — execute() will re-emit on the next cycle if
        // the citation persists.
        Grounded grounded = applyGrounding(rawOutcome);
        PlannerOutcome outcome = grounded.outcome();
        if (!grounded.undefinedSymbols().isEmpty()) {
            log.warn("Planner planInto: undefined symbols cited in plan for {}: {}",
                    parentGoalId, grounded.undefinedSymbols());
        }

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

        // T1-DG: advance the planning run counter so this wave's subgoals are stamped with a
        // run-id distinct from the prior wave. Without this advance the first-wave ratio in
        // autoCompleteRootIfSatisfied is diluted by replacement waves that share the same id.
        goalPlannerService.beginPlanningRun();

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

            // T2-DC: persist undefined-symbol citations on the new subgoal's metadata.
            Set<String> undefHere = grounded.undefinedPerDescription().get(goal);
            if (undefHere != null && !undefHere.isEmpty()) {
                goalPlannerService.setUndefinedSymbols(id, undefHere);
            }

            injected++;
            isFirst = false;
        }

        // T3-BH: store plan rationale in the parent goal's metadata.
        if (injected >= 2 && outcome.planRationale() != null) {
            goalPlannerService.setPlanRationale(parentGoalId, outcome.planRationale());
            log.debug("Stored plan rationale for parent [{}]: {}", parentGoalId, outcome.planRationale());
        }

        plannerAdapter.evolveGraph(planningContext, outcome);
        return injected; // 0 if all proposals were deduped (T1-BK); >0 if some were injected
    }

    /**
     * T2-DC: result of grounding planner LLM citations against the on-disk output tree.
     *
     * @param undefinedSymbols       all undefined identifiers across the outcome (signal source)
     * @param undefinedPerDescription per proposed-subgoal-description undefined set, keyed by the
     *                                grounded description (what eventually reaches addSubGoal)
     */
    private record Grounded(
            PlannerOutcome outcome,
            Set<String> undefinedSymbols,
            Map<String, Set<String>> undefinedPerDescription) {}

    /**
     * T2-DC: rewrite a {@link PlannerOutcome} so undefined PascalCase identifiers in subgoal
     * descriptions and criteria are annotated {@code [UNDEFINED]}. For each proposed description
     * that cites an undefined symbol, prepend {@code "Create <Name>.java"} to its criteria so the
     * next subgoal is framed as a creation task. The criteria map is rekeyed by the grounded
     * description so downstream lookup-by-description still resolves correctly.
     */
    private Grounded applyGrounding(PlannerOutcome in) {
        if (in == null) return new Grounded(null, Set.of(), Map.of());
        Set<String> undefinedAll = new LinkedHashSet<>();
        Map<String, Set<String>> undefinedPerDesc = new LinkedHashMap<>();

        List<String> rewrittenGoals = new ArrayList<>(in.newGoals() != null ? in.newGoals().size() : 0);
        Map<String, List<String>> rewrittenCriteria = new LinkedHashMap<>();
        if (in.newGoals() != null) {
            for (String desc : in.newGoals()) {
                Set<String> perDescUndefined = new LinkedHashSet<>();
                String groundedDesc = groundingService.groundSymbolReferences(desc, perDescUndefined);
                rewrittenGoals.add(groundedDesc);

                List<String> origCriteria = in.goalCriteria() != null ? in.goalCriteria().get(desc) : null;
                List<String> newCriteria = new ArrayList<>();
                for (String sym : perDescUndefined) {
                    newCriteria.add("Create " + sym + ".java");
                }
                if (origCriteria != null) {
                    for (String c : origCriteria) {
                        // Symbols cited only in criteria text also count as undefined for this desc.
                        newCriteria.add(groundingService.groundSymbolReferences(c, perDescUndefined));
                    }
                }
                if (!newCriteria.isEmpty()) {
                    rewrittenCriteria.put(groundedDesc, newCriteria);
                }
                undefinedAll.addAll(perDescUndefined);
                if (!perDescUndefined.isEmpty()) {
                    undefinedPerDesc.put(groundedDesc, perDescUndefined);
                }
            }
        }

        // No grounding needed — pass the original outcome through unchanged. Preserves object
        // identity for healthy LLM output (no hallucinated symbols, no rewriting) so downstream
        // graph writes / behaviour events see the exact LLM-produced record.
        if (undefinedAll.isEmpty()) {
            return new Grounded(in, Set.of(), Map.of());
        }

        PlannerOutcome rewritten = new PlannerOutcome(
                in.updated(),
                rewrittenGoals,
                in.goalsToRemove(),
                in.message(),
                rewrittenCriteria.isEmpty() ? null : rewrittenCriteria,
                in.planRationale());
        return new Grounded(rewritten, undefinedAll, undefinedPerDesc);
    }

    private int processRemovals(PlannerOutcome outcome, CycleContextData context) {
        if (outcome == null || outcome.goalsToRemove() == null || outcome.goalsToRemove().isEmpty()) return 0;

        int[] pruned = {0};
        for (String entry : outcome.goalsToRemove()) {
            goalPlannerService.getRemainingGoals().stream()
                    .filter(g -> entry.equalsIgnoreCase(g.getId())
                            || (g.getDescription() != null && entry.equalsIgnoreCase(g.getDescription())))
                    .findFirst()
                    .ifPresentOrElse(
                            g -> {
                                // T1-DC: residual guard for unevaluated mutations only.
                                // T1-DA's accepted-but-unmerged guard is no longer needed: EvaluationAgent
                                // now writes mutationState in-cycle and emits ROLE_MERGE on ACCEPTED, so
                                // merge runs before planner can disturb the active subgoal pointer.
                                // The remaining hole is when evolution proposed a mutation this cycle
                                // that has not yet been evaluated — fingerprints diverge in that window.
                                if (g.getId().equals(context.currentSubGoalId())
                                        && context.lastEvolutionFingerprint() != null
                                        && !java.util.Objects.equals(
                                                context.lastEvolutionFingerprint(),
                                                context.lastEvaluatedEvolutionFingerprint())) {
                                    log.warn("T1-DC: refusing to prune subgoal [{}] — evolution this cycle has not been evaluated yet", g.getId());
                                    return;
                                }
                                // Planner removal IS supersession — replacement work carries
                                // the obligation, so the retired goal becomes SUBSUMED (not
                                // GIVEN_UP), which T2-DR's closure rule treats as a
                                // legitimate pass-through rather than a blocker.
                                goalPlannerService.transitionGoal(g.getId(),
                                        com.walpro.autobuilder.goal.GoalStatus.SUBSUMED,
                                        "superseded by replanning",
                                        com.walpro.autobuilder.goal.SubsumptionReason.REPLACED);
                                pruned[0]++;
                                log.info("Planner pruned subgoal [{}]: {}", g.getId(), entry);
                            },
                            () -> log.warn("Planner #SubGoalRemove: no active subgoal found for entry='{}'", entry)
                    );
        }

        // If currentSubGoalId is now terminal (T4-BC: SUBSUMED via SUPERSEDED, or pre-existing
        // GIVEN_UP path), advance to the next active subgoal.
        GoalEntity current = goalPlannerService.getCurrentSubGoal();
        if (current != null
                && (current.getStatus() == GoalStatus.GIVEN_UP
                        || current.getStatus() == GoalStatus.SUBSUMED)) {
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
        return pruned[0];
    }

    /**
     * T1-EI: process #SubGoalReactivate ids the LLM emitted. Reactivates each goal via the
     * existing {@link GoalPlannerService#reactivateGoal(String)} path; unknown ids are logged
     * and ignored. Clears any pending rehabilitation vote state on the reactivated goal.
     */
    private CycleContextData applyExplicitReactivations(PlannerOutcome outcome, CycleContextData context) {
        if (outcome == null) return context;
        List<String> ids = outcome.reactivateGoalIds();
        if (ids == null || ids.isEmpty()) return context;

        for (String id : ids) {
            List<String> flipped = goalPlannerService.reactivateGoal(id);
            if (flipped.isEmpty()) {
                log.warn("T1-EI: #SubGoalReactivate ignored — goalId={} unknown or already ACTIVE", id);
                continue;
            }
            log.info("T1-EI: #SubGoalReactivate flipped {} goal(s) back to ACTIVE (target={})",
                    flipped.size(), id);
            goalPlannerService.clearRehabilitationVotes(id);
            goalPlannerService.incrementGoalMetadataInt(id, "rehabilitationCount");
        }
        return context;
    }

    private record InjectionResult(List<String> skipped, int injected,
                                   List<String> reactivatedGoalIds, List<String> irreducibleGoalIds,
                                   Map<String, String> proposalMatches) {
        InjectionResult(List<String> skipped, int injected) {
            this(skipped, injected, List.of(), List.of(), Map.of());
        }
        InjectionResult(List<String> skipped, int injected,
                        List<String> reactivatedGoalIds, List<String> irreducibleGoalIds) {
            this(skipped, injected, reactivatedGoalIds, irreducibleGoalIds, Map.of());
        }
    }

    private record RehabResult(String matchedId, String reactivatedId, String irreducibleId) {
        static RehabResult none() { return new RehabResult(null, null, null); }
    }

    /**
     * T1-EI: when a dedup block fires against a candidate proposal, vote on the closest
     * matching GIVEN_UP goal. After {@link #REHABILITATION_VOTE_THRESHOLD} distinct cycles
     * of votes the goal is auto-reactivated; after {@link #REHABILITATION_FAIL_CAP}
     * reactivations without an intervening merge it is permanently re-given-up with the
     * IRREDUCIBLE_GAP signal so the planner cannot loop on it forever.
     */
    private RehabResult attemptRehabilitation(String candidate, String cycleName) {
        if (candidate == null || candidate.isBlank()) return RehabResult.none();
        List<GoalEntity> givenUp = goalPlannerService.getAllGoals().stream()
                .filter(g -> g.getStatus() == GoalStatus.GIVEN_UP)
                .toList();
        if (givenUp.isEmpty()) return RehabResult.none();

        GoalEntity match = similarityService.findGivenUpMatch(candidate, givenUp);
        if (match == null) return RehabResult.none();

        int reactivationCount = goalPlannerService.getGoalMetadataInt(match, "rehabilitationCount");
        if (reactivationCount >= REHABILITATION_FAIL_CAP) {
            log.warn("T1-EI: rehabilitation fail-cap reached for goalId={} ({} reactivations) — IRREDUCIBLE_GAP",
                    match.getId(), reactivationCount);
            goalPlannerService.clearRehabilitationVotes(match.getId());
            return new RehabResult(match.getId(), null, match.getId());
        }

        int votes = goalPlannerService.registerRehabilitationVote(match.getId(), cycleName);
        log.info("T1-EI: rehabilitation vote +1 for goalId={} (total={}, candidate='{}')",
                match.getId(), votes, candidate);
        if (votes < REHABILITATION_VOTE_THRESHOLD) {
            return new RehabResult(match.getId(), null, null);
        }

        log.info("T1-EI: vote threshold reached for goalId={} — reactivating", match.getId());
        List<String> flipped = goalPlannerService.reactivateGoal(match.getId());
        if (flipped.isEmpty()) {
            goalPlannerService.clearRehabilitationVotes(match.getId());
            return new RehabResult(match.getId(), null, null);
        }
        goalPlannerService.clearRehabilitationVotes(match.getId());
        goalPlannerService.incrementGoalMetadataInt(match.getId(), "rehabilitationCount");
        return new RehabResult(match.getId(), match.getId(), null);
    }

    private InjectionResult injectOutcomeGoals(PlannerOutcome outcome, String parentGoalId,
                                               Map<String, Set<String>> undefinedPerDescription) {
        return injectOutcomeGoals(outcome, parentGoalId, undefinedPerDescription, null);
    }

    private InjectionResult injectOutcomeGoals(PlannerOutcome outcome, String parentGoalId,
                                               Map<String, Set<String>> undefinedPerDescription,
                                               CycleContextData ctx) {
        if (outcome == null || !outcome.updated()) return new InjectionResult(List.of(), 0, List.of(), List.of());

        var sanitized = goalPlannerService.sanitizeProposedSubGoals(outcome.newGoals());
        if (sanitized.isEmpty()) return new InjectionResult(List.of(), 0, List.of(), List.of());

        // T1-DG: advance the planning run counter so this wave's subgoals are stamped with a
        // run-id distinct from the prior wave (initial bootstrap → 1, first re-plan → 2, etc.).
        // The first-wave ratio in autoCompleteRootIfSatisfied filters by minPlanningRunId, so
        // distinct run-ids are required for the filter to do anything useful.
        goalPlannerService.beginPlanningRun();

        List<String> knownDescriptions = buildKnownDescriptions();
        List<String> skipped = new ArrayList<>();
        List<String> reactivated = new ArrayList<>();
        List<String> irreducible = new ArrayList<>();
        Map<String, String> proposalMatches = new LinkedHashMap<>();
        int injected = 0;
        boolean isFirst = true;
        String cycleName = ctx != null ? ctx.cycleName() : null;
        for (String goal : sanitized) {
            if (similarityService.isDuplicate(goal, knownDescriptions)) {
                log.warn("Skipped similar subgoal: '{}'", goal);
                RehabResult rehab = attemptRehabilitation(goal, cycleName);
                if (rehab.reactivatedId() != null) reactivated.add(rehab.reactivatedId());
                if (rehab.irreducibleId() != null) irreducible.add(rehab.irreducibleId());
                if (rehab.matchedId() != null) proposalMatches.put(goal, rehab.matchedId());
                skipped.add(goal);
                continue;
            }

            long givenUpCount = similarityService.countGivenUpMatches(goal);
            if (givenUpCount >= GIVEN_UP_RETRY_LIMIT) {
                log.warn("Skipped unachievable subgoal ({}/{} prior given-up attempts): '{}'",
                        givenUpCount, GIVEN_UP_RETRY_LIMIT, goal);
                RehabResult rehab = attemptRehabilitation(goal, cycleName);
                if (rehab.reactivatedId() != null) reactivated.add(rehab.reactivatedId());
                if (rehab.irreducibleId() != null) irreducible.add(rehab.irreducibleId());
                if (rehab.matchedId() != null) proposalMatches.put(goal, rehab.matchedId());
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

            // T2-DC: persist undefined-symbol citations on the new subgoal's metadata.
            Set<String> undefHere = undefinedPerDescription != null ? undefinedPerDescription.get(goal) : null;
            if (undefHere != null && !undefHere.isEmpty()) {
                goalPlannerService.setUndefinedSymbols(id, undefHere);
            }

            injected++;
            isFirst = false;
        }

        // T3-BH: store plan rationale in the parent goal's metadata so evolution/evaluation
        // agents can show sibling coordination context when executing each subgoal.
        // When parentGoalId is null (initial bootstrap injection), subgoals are injected under
        // rootGoalId — use rootGoalId as the fallback so the rationale is still stored.
        String rationaleParentId = parentGoalId != null ? parentGoalId
                : goalPlannerService.getRootGoal() != null ? goalPlannerService.getRootGoal().getId() : null;
        if (injected >= 2 && outcome.planRationale() != null && rationaleParentId != null) {
            goalPlannerService.setPlanRationale(rationaleParentId, outcome.planRationale());
            log.debug("Stored plan rationale for parent [{}]: {}", rationaleParentId, outcome.planRationale());
        }

        log.info("Planner outcome message: {}", outcome.message());
        return new InjectionResult(skipped, injected, reactivated, irreducible, proposalMatches);
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

        // T3-BH: reload currentSubGoalParentId and planRationale so that agents running later
        // in the same cycle (e.g. evolution in bootstrap) see the sibling context injected by
        // this planner run. CycleContextCoordinator only populates these at cycle-start, before
        // the planner has injected new subgoals or stored the plan rationale.
        String parentId = current != null ? current.getParentId() : ctx.currentSubGoalParentId();
        String planRationale = null;
        if (parentId != null) {
            GoalEntity parentGoal = goalPlannerService.getGoalById(parentId);
            if (parentGoal != null) {
                planRationale = goalPlannerService.getPlanRationale(parentGoal);
            }
        }

        // T2-DL: re-query abandoned-goal clusters so the planner sees context against
        // whatever subgoal description is now current (analysis may have shifted it mid-cycle).
        String probe = subDesc != null ? subDesc : rootDesc;
        List<AbandonedGoalCluster> relatedAbandoned = probe != null
                ? abandonedGoalQueryService.findRelatedAbandonedGoals(probe)
                : List.of();

        return ctx
                .withCurrentGoalId(rootId)
                .withCurrentGoalDescription(rootDesc)
                .withCurrentSubGoalId(subId)
                .withCurrentSubGoalDescription(subDesc)
                .withRemainingGoals(goalPlannerService.getRemainingGoals())
                .withCompletedGoals(goalPlannerService.getCompletedGoals())
                .withCurrentSubGoalCriteria(subgoalCriteria.isEmpty() ? null : subgoalCriteria)
                .withCurrentSubGoalParentId(parentId)
                .withPlanRationale(planRationale)
                .withRelatedAbandonedGoals(relatedAbandoned);
    }

}
