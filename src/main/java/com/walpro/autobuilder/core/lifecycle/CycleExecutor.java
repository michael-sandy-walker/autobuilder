package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.analysis.heuristic.SubGoalFulfillmentHeuristic;
import com.walpro.autobuilder.analysis.pattern.PatternSignalAnalyzer;
import com.walpro.autobuilder.analysis.pattern.PlanningThrashSignalAnalyzer;
import com.walpro.autobuilder.analysis.pattern.ReplanPatternDetector;
import com.walpro.autobuilder.analysis.pattern.ScopeThrashSignalAnalyzer;
import com.walpro.autobuilder.analysis.problem.GoalCompletionDecider;
import com.walpro.autobuilder.analysis.scope.SubgoalScopeGuard;
import com.walpro.autobuilder.controller.goal.GoalStateEventPublisher;
import com.walpro.autobuilder.core.engine.AdaptiveExecutionEngine;
import com.walpro.autobuilder.core.interpretation.InterpretationService;
import com.walpro.autobuilder.core.persistence.MutationSnapshotService;
import com.walpro.autobuilder.goal.AttemptIds;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalProgressApplicationResult;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import java.io.IOException;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CycleExecutor {

    private final AdaptiveExecutionEngine adaptiveExecutionEngine;
    private final GoalPlannerService goalPlannerService;
    private final SubGoalFulfillmentHeuristic subGoalFulfillmentHeuristic;
    private final InterpretationService interpretationService;
    private final CycleScoreRecorder cycleScoreRecorder;
    private final GoalCompletionDecider goalCompletionDecider;
    private final PatternSignalAnalyzer patternSignalAnalyzer;
    private final PlanningThrashSignalAnalyzer planningThrashSignalAnalyzer;
    private final ReplanPatternDetector replanPatternDetector;
    private final ScopeThrashSignalAnalyzer scopeThrashSignalAnalyzer;
    private final SubgoalScopeGuard subgoalScopeGuard;
    private final MutationSnapshotService mutationSnapshotService;
    private final com.walpro.autobuilder.core.build.BuildFixEscalator buildFixEscalator;

    /**
     * T4-AM: optional bean — when present, a {@code CYCLE_END} event is broadcast to all live
     * SSE subscribers at the end of {@link #runCycle(CycleContextData)} with a flat goal-state
     * snapshot and the active signal map. Optional so unit tests that build {@code CycleExecutor}
     * manually (see {@code CycleExecutorGoalLoopTest}) don't need a publisher instance.
     */
    @Setter
    @Autowired(required = false)
    private GoalStateEventPublisher goalStateEventPublisher;

    /** T5-H: optional behaviour trace logger (NDJSON + SSE). */
    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    /** T4-BE: optional deferred-mutation restage scheduler — fires at cycle_start before
     * the standard engine runs. Optional so unit tests that construct CycleExecutor without
     * the mutation services keep working. */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.mutation.DeferredRestageScheduler deferredRestageScheduler;

    /** T4-BO: optional signal-lifetime observability tracker (measure before tuning — see
     *  project_t4bo_design_note.md). Optional for the same reason as the other collaborators
     *  above: {@code CycleExecutorGoalLoopTest} constructs this class manually. */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.observability.SignalLifetimeTracker signalLifetimeTracker;

    /** T4-BZ-3 Q-b: optional — verified early expiry of MISSING_SYMBOL requests reads the
     *  declaration scan; without it (manual test constructions) horizons alone govern expiry. */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.grounding.SymbolGroundingService symbolGroundingService;

    /**
     * T1-ET Part B livelock detector (tertiary, defence-in-depth): tracks consecutive cycles
     * that end with {@code mut=null && promoted=true && merged=false} on the same
     * {@code currentSubGoalId}. Three in a row indicates the primary + secondary fixes failed
     * to close some new shape of pointer-clobber; emits a one-shot planner-priority signal
     * and resets so the WARN doesn't spam.
     */
    private String livelockTrackingSubGoalId;
    private int livelockConsecutiveCount;
    /** Threshold above which the livelock invariant fires. Mirrors the 3-cycle pattern from
     *  the {@code behaviour-2026-05-25_00-31-18.ndjson} 16-cycle zero-work loop. */
    private static final int LIVELOCK_THRESHOLD = 3;

    public CycleContextData runCycle(CycleContextData context) {

        goalPlannerService.setCurrentCycleContext(context);

        context = syncContextFromGoals(context);

        if (behaviourLogger != null) {
            behaviourLogger.cycleStart(context.cycleName(), context.currentGoalId(), context.currentSubGoalId(),
                    context.currentGoalDescription(), context.currentSubGoalDescription());
        }

        if (DerivedFacts.systemPaused(context)) {
            log.info("System is paused (cycle {}). Skipping run.", context.cycleName());
            CycleContextData paused = tickSignalsTracked(context);
            if (behaviourLogger != null) {
                behaviourLogger.cycleEnd(paused.cycleName(),
                        paused.cycleResultState() != null ? paused.cycleResultState().name() : null,
                        paused.merged(), "system_paused",
                        buildOutcomeName(paused));
            }
            return paused;
        }

        // T3-BL: criteria-banked early exit — if all acceptance criteria are already banked as
        // true AND at least one merge occurred (prevents a brand-new subgoal from self-fulfilling),
        // mark the subgoal FULFILLED immediately and skip agent execution for this cycle.
        // T1-ET Part B: also require {@code hasNoActiveDescendants} so this fast path does not
        // fire on parent goals whose banked criteria came from child merges accumulating
        // upward — the T1-DD guard would refuse the COMPLETED flip downstream and the cycle
        // would still end without running any agent. Defer to ordinary cycle execution so the
        // leaves get worked. Mirrors the T1-DD invariant at the early-exit producer site.
        String earlyExitSubGoalId = context.currentSubGoalId();
        if (earlyExitSubGoalId != null
                && goalPlannerService.allCriteriaDefinedAndBanked(earlyExitSubGoalId)
                && goalPlannerService.getGoalMetadataInt(earlyExitSubGoalId, "mergeCount") > 0
                && goalPlannerService.hasNoActiveDescendants(earlyExitSubGoalId)) {
            log.info("T3-BL: all criteria banked for subGoalId={}. Applying FULFILLED early exit.", earlyExitSubGoalId);
            GoalProgressApplicationResult earlyResult =
                    goalPlannerService.applyGoalProgress(earlyExitSubGoalId, GoalProgressState.FULFILLED, "all acceptance criteria banked");
            GoalEntity nextGoalEntity = goalPlannerService.getCurrentSubGoal();
            context = context
                    .withSubgoalPromoted(earlyResult != null && earlyResult.promoted())
                    .withCurrentSubGoalId(earlyResult != null ? earlyResult.newSubGoalId() : earlyExitSubGoalId)
                    .withRemainingGoals(goalPlannerService.getRemainingGoals())
                    .withCompletedGoals(goalPlannerService.getCompletedGoals())
                    .withLastGoalProgressApplication(earlyResult)
                    .withCurrentSubGoalDescription(nextGoalEntity != null ? nextGoalEntity.getDescription() : null);
            CycleResultState earlyState = deriveCycleResult(context);
            context = context.withCycleResultState(earlyState);
            context = tickSignalsTracked(context);
            if (context.currentSubGoalId() != null) {
                // T4-BZ-3 (I6): the scoped kinds persist on the channel carrier.
                goalPlannerService.saveSubgoalRequests(context.currentSubGoalId(),
                        context.requests().ofKinds(BasicSignals.SUBGOAL_SCOPED_SIGNALS));
            }
            if (behaviourLogger != null) {
                behaviourLogger.cycleEnd(context.cycleName(),
                        context.cycleResultState() != null ? context.cycleResultState().name() : null,
                        context.merged(), "criteria_banked_early_exit",
                        buildOutcomeName(context));
            }
            return context;
        }

        // T4-BE: cycle_start hook — check the deferred-mutation ledger for READY candidates
        // whose preconditions pass (in scope, OR out-of-scope but above completion-bias
        // threshold + outside cooldown + drift-clean). When a candidate is staged, the
        // returned context carries the mutation's files as evolutionOutcome + ROLE_EVALUATION
        // signal so the engine's signal-driven loop routes through the standard eval/merge path.
        if (deferredRestageScheduler != null) {
            try {
                context = deferredRestageScheduler.scheduleIfReady(context);
            } catch (Exception e) {
                log.warn("T4-BE: cycle_start restage scheduler failed: {}", e.getMessage());
            }
        }

        context = adaptiveExecutionEngine.runCycle(context);

        context = subgoalScopeGuard.guard(context);

        // T1-DC: mutationState/goalProgress are written directly by EvaluationAgent (line 225-231)
        // so the signal-driven loop's downstream agents (planner, merge, analysis) see fresh
        // state in the same cycle. The previous post-runCycle writer (applyEvaluationInterpretationIfPresent)
        // made T1-DA's guard structurally unreachable and starved the signal-driven merge agent.

        // T1-CT: After evaluation sets mutationState, persist it to meta.json so that
        // FileVariantCollector can find ACCEPTED variants for both same-cycle and
        // cross-cycle merge. MutationApplier wrote meta.json at evolution time with
        // mutationState=null (evaluation hadn't run yet). We update ONLY mutationState here,
        // preserving the correct pre-promotion currentSubGoalId and merged=false.
        persistEvaluationResultToMeta(context);

        context = patternSignalAnalyzer.analyze(context);
        if (!DerivedFacts.scopeOutOfScope(context)) {
            context = planningThrashSignalAnalyzer.analyze(context);
            context = replanPatternDetector.detect(context);
        }
        context = scopeThrashSignalAnalyzer.analyze(context);

        context = applyMergeOnAcceptedMutation(context);

        // T1-DY: persist evaluator's criteria-pass status and fail-streak only if the merge
        // gate confirmed the mutation (i.e. rollback did not fire). EvaluationAgent stopped
        // calling these directly so a rolled-back cycle's banking can't pollute GoalEntity
        // metadata and trigger T3-BL's criteria-banked early-exit on the next cycle.
        flushPendingCriteriaIfNotRolledBack(context);

        cycleScoreRecorder.recordGoalEventsIfPresent(context);
        // TODO: ensure cycle targets goal (e.g., "goalGraphService.ensureCycleTargetsGoal(ctx.cycleName(), ctx.currentSubGoalId() != null ? ctx.currentSubGoalId() : ctx.currentGoalId())")

        context = applyEvaluationProgressIfPresent(context);
        context = applyCompletion(context);

        CycleResultState result = deriveCycleResult(context);
        context = context.withCycleResultState(result);

        // T2-CV: signal/graph-driven escalation when the build has stayed broken without
        // progress. Runs before interpretation so the next-cycle planner / analysis sees the
        // emitted signals on the very next cycle.
        context = buildFixEscalator.escalateIfStuck(context);

        context = interpretationService.interpret(context);

        context = tickSignalsTracked(context);
        log.debug("Readout after completed cycle {}: {}", context.cycleName(), SignalReadout.of(context).strengthLine());

        // T2-BK: persist subgoal-scoped signals to the entity after decay so subgoal switches
        // and restarts restore the correct per-subgoal history rather than global carried state.
        if (context.currentSubGoalId() != null) {
            // T4-BZ-3 (I6): the scoped kinds persist on the channel carrier.
            goalPlannerService.saveSubgoalRequests(context.currentSubGoalId(),
                    context.requests().ofKinds(BasicSignals.SUBGOAL_SCOPED_SIGNALS));
        }

        // T1-DD: end-of-cycle invariant check — surfaces COMPLETED nodes with ACTIVE direct
        // children. Should be 0 once the markGoalCompleted guard is in place; non-zero means
        // a code path bypassed the guard and the tree is structurally inconsistent.
        goalPlannerService.assertGoalTreeIntegrity();

        // T1-ET Part B: livelock invariant. Should never fire once the primary + secondary
        // fixes (promoteCurrentSubGoal early-return + T3-BL hasNoActiveDescendants guard) are
        // in place; if it does, some new shape of zero-work loop has surfaced and the planner
        // needs the cycle.
        context = detectLivelock(context);

        // T4-AM: broadcast a cycle-end snapshot to any live SSE subscribers. Best-effort —
        // swallow errors here so streaming never blocks the control loop.
        if (goalStateEventPublisher != null) {
            try {
                goalStateEventPublisher.publishCycleEnd(context);
            } catch (Exception e) {
                log.debug("publishCycleEnd failed (ignoring): {}", e.getMessage());
            }
        }

        if (behaviourLogger != null) {
            behaviourLogger.cycleEnd(context.cycleName(),
                    context.cycleResultState() != null ? context.cycleResultState().name() : null,
                    context.merged(), null,
                    buildOutcomeName(context));
        }

        return context;
    }

    /**
     * T4-BO: the once-per-cycle carrier tick — request channel and participation windows so
     * {@link #signalLifetimeTracker} sees the signal map immediately before and after the tick
     * — the two snapshots it needs to attribute every ending to "decayed" or "cleared" without
     * hooking any of the ~70 {@code withoutSignal} call sites directly. All THREE tickSignals
     * sites in this class route through here (paused cycles and the T3-BL early exit still
     * decay signals, so they're legitimate observation points too, not just the main path).
     */
    private CycleContextData tickSignalsTracked(CycleContextData context) {
        CycleContextData ticked = context;
        // T4-BZ-3: the request channel ages once per cycle boundary; expiry (validity horizon
        // or verified early resolution) replaced silent decay.
        var preTickOpen = ticked.requests().open();
        RequestChannel.TickResult requestTick = ticked.requests().tick(requestResolvedEarly());
        ticked = ticked.withRequests(requestTick.channel());
        // T4-BZ-4: participation windows age at the same boundary; horizon expiry (or a shrink
        // below the survival threshold) replaced the role signals' silent decay ending.
        var preTickWindows = ticked.windows().active();
        ParticipationWindows.TickResult windowTick = ticked.windows().tick();
        ticked = ticked.withWindows(windowTick.windows());
        if (signalLifetimeTracker != null) {
            var endedLifecycles = new java.util.ArrayList<>(signalLifetimeTracker.observeRequests(preTickOpen, ticked.requests().open()));
            endedLifecycles.addAll(signalLifetimeTracker.observeWindows(preTickWindows, ticked.windows().active()));
            for (var ended : endedLifecycles) {
                if (behaviourLogger != null) {
                    behaviourLogger.signalLifetimeObserved(ticked.cycleName(), ended.signalType().name(),
                            ended.emitCycle(), ended.endCycle(), ended.endReason(), ended.emittedBySource());
                }
            }
        }
        return ticked;
    }

    /**
     * T4-BZ-3 Q-b: verified early expiry — a MISSING_SYMBOL request whose symbol now has a
     * declaration under output/ is resolved ("diagnostic goes stale as code changes",
     * implemented literally). The declaration scan is measured-cheap (~5ms on the live target)
     * and memoized for the tick via the supplier, so N open requests cost one walk. Optional
     * collaborator: without the grounding service (plain unit constructions) requests expire
     * on their horizon only.
     */
    private java.util.function.Predicate<ChannelRequest> requestResolvedEarly() {
        if (symbolGroundingService == null) return null;
        java.util.concurrent.atomic.AtomicReference<java.util.Set<String>> defined = new java.util.concurrent.atomic.AtomicReference<>();
        return r -> {
            if (!BasicSignals.MISSING_SYMBOL.equals(r.kind())) return false;
            String source = r.source() != null ? r.source() : "";
            int colon = source.lastIndexOf(':');
            if (colon < 0 || colon == source.length() - 1) return false;
            String symbol = source.substring(colon + 1).trim();
            if (symbol.isEmpty()) return false;
            java.util.Set<String> symbols = defined.get();
            if (symbols == null) {
                symbols = symbolGroundingService.definedSymbolsInOutput();
                defined.set(symbols);
            }
            return symbols.contains(symbol);
        };
    }

    private static String buildOutcomeName(CycleContextData ctx) {
        if (ctx == null) return null;
        var outcome = ctx.buildOutcome();
        if (outcome == null) return null;
        var result = outcome.result();
        return result == null ? null : result.name();
    }

    /**
     * T1-ET Part B livelock detector. Increments a per-{@code currentSubGoalId} counter
     * whenever a cycle ends in the zero-work shape (no mutation produced, promoted=true,
     * merged=false). On the {@value #LIVELOCK_THRESHOLD}th consecutive same-subgoal hit:
     * emit a structured WARN, fire a one-shot REPLAN_REQUESTED signal so the next cycle's
     * planner runs first, and reset the counter so the WARN doesn't spam. Returns the
     * (possibly signal-augmented) context.
     */
    private CycleContextData detectLivelock(CycleContextData ctx) {
        if (ctx == null) return ctx;
        String subId = ctx.currentSubGoalId();
        boolean mutEmpty = ctx.evolutionOutcome() == null
                || ctx.evolutionOutcome().addedOrChangedFiles() == null
                || ctx.evolutionOutcome().addedOrChangedFiles().isEmpty();
        boolean promoted = ctx.subgoalPromoted();
        boolean notMerged = !ctx.merged();
        boolean zeroWork = mutEmpty && promoted && notMerged && subId != null;

        if (!zeroWork) {
            livelockTrackingSubGoalId = null;
            livelockConsecutiveCount = 0;
            return ctx;
        }
        if (subId.equals(livelockTrackingSubGoalId)) {
            livelockConsecutiveCount++;
        } else {
            livelockTrackingSubGoalId = subId;
            livelockConsecutiveCount = 1;
        }
        if (livelockConsecutiveCount >= LIVELOCK_THRESHOLD) {
            log.warn("T1-ET: livelock-suspected (subGoalId={},n={})", subId, livelockConsecutiveCount);
            if (behaviourLogger != null) {
                behaviourLogger.guardViolated(ctx.cycleName(), "T1-ET",
                        "livelock-suspected: mut=null && promoted=true && merged=false on subGoalId="
                                + subId + " for " + livelockConsecutiveCount + " consecutive cycles");
            }
            ctx = ctx.issueRequest(ChannelRequest.of(BasicSignals.REPLAN_REQUESTED, 1.0, "T1-ET-livelock-suspected", ChannelRequest.horizonFromDecay(1.0, 0.5)));
            livelockTrackingSubGoalId = null;
            livelockConsecutiveCount = 0;
        }
        return ctx;
    }

    /**
     * T1-DY: defer criteria banking (pass-status and fail-streak) until the build gate
     * confirms the merge. EvaluationAgent stopped persisting these directly so that a
     * cycle whose merge is rolled back by T1-DW does not leave behind banked criteria
     * on the goal entity that would later trigger T3-BL's criteria-banked early exit
     * on phantom progress.
     */
    private void flushPendingCriteriaIfNotRolledBack(CycleContextData ctx) {
        if (ctx == null) return;
        if (ctx.rolledBackThisCycle()) return;
        String subGoalId = ctx.currentSubGoalId();
        if (subGoalId == null) return;
        if (ctx.cumulativeCriteriaStatus() != null) {
            goalPlannerService.saveCriteriaPassStatus(subGoalId, ctx.cumulativeCriteriaStatus());
        }
        if (ctx.criteriaConsecutiveFailCount() != null) {
            goalPlannerService.saveCriteriaFailStreak(subGoalId, ctx.criteriaConsecutiveFailCount());
        }
    }

    private CycleResultState deriveCycleResult(CycleContextData ctx) {

        // T1-DX: rolled-back cycle reports ROLLED_BACK so the cycle-end behaviour event and
        // operator-visible status reflect the truth (no progress this cycle), not a stale
        // CONTINUE/FULFILLED narrative inherited from pre-rollback state.
        if (ctx.rolledBackThisCycle()) {
            return CycleResultState.ROLLED_BACK;
        }

        if (ctx.hasOpenRequest(BasicSignals.SUBGOAL_EXHAUSTED)) {
            return CycleResultState.SUBGOAL_EXHAUSTED;
        }

        if (DerivedFacts.goalExhausted(ctx)) {
            // T1-DJ: a GOAL_EXHAUSTED signal present with a COMPLETED root means the goal was
            // fulfilled (e.g. by autoCompleteRootIfSatisfied) before the signal was consumed.
            // Return GOAL_FULFILLED so the correct terminal narrative is emitted.
            GoalEntity exhaustedRoot = goalPlannerService.getRootGoal();
            if (exhaustedRoot != null && exhaustedRoot.getStatus() == GoalStatus.COMPLETED) {
                log.info("T1-DJ: GOAL_EXHAUSTED signal present but root is COMPLETED — returning GOAL_FULFILLED.");
                return CycleResultState.GOAL_FULFILLED;
            }
            return CycleResultState.GOAL_EXHAUSTED;
        }

        GoalProgressApplicationResult goalProgressApplicationResult = ctx.lastGoalProgressApplication();

        if (goalProgressApplicationResult != null && goalProgressApplicationResult.rootAutoCompleted()) {
            return CycleResultState.GOAL_FULFILLED;
        }

        // T1-BH: leafCompletedNow detects the last subgoal being fulfilled and promoted with no
        // successor. Guard with an explicit root-goal COMPLETED check so that the condition cannot
        // fire when remainingGoals is empty for the wrong reason (e.g. planner gave up all subgoals
        // via GIVEN_UP, leaving no ACTIVE ones — same empty list, but goal is NOT done).
        // The former PROGRESSING branch was removed: PROGRESSING is not a completion signal and
        // caused false GOAL_FULFILLED when a mutation was ACCEPTED on a cycle where the planner
        // had exhausted all subgoals without completing any of them.
        boolean leafCompletedNow =
                goalProgressApplicationResult != null
                        && goalProgressApplicationResult.promoted()
                        && goalProgressApplicationResult.previousSubGoalId() != null
                        && ctx.currentSubGoalId() == null;
        if (leafCompletedNow && ctx.remainingGoals().isEmpty()) {
            GoalEntity root = goalPlannerService.getRootGoal();
            if (root != null && root.getStatus() == GoalStatus.COMPLETED) {
                return CycleResultState.GOAL_FULFILLED;
            }
            String rootStatus = root != null ? String.valueOf(root.getStatus()) : "null";
            log.warn("leafCompletedNow=true and remainingGoals empty, but root goal is not COMPLETED (status={}). " +
                    "Suppressing false GOAL_FULFILLED — likely all subgoals were given up, not completed.",
                    rootStatus);
            if (behaviourLogger != null) {
                behaviourLogger.guardViolated(ctx.cycleName(), "T1-BH",
                        "leafCompletedNow=true but root not COMPLETED (status=" + rootStatus + ")");
            }
        }

        if (ctx.subgoalPromoted()) {
            return CycleResultState.CONTINUE;
        }

        if (ctx.goalProgress() == GoalProgressState.STAGNATING) {
            return CycleResultState.STAGNATING;
        }

        return CycleResultState.CONTINUE;
    }

    private CycleContextData applyEvaluationProgressIfPresent(CycleContextData ctx) {
        if (ctx == null) return null;

        // T1-DX: a rolled-back cycle did NOT make progress regardless of mutationState/score.
        // Skip evaluation-driven progress promotion entirely.
        if (ctx.rolledBackThisCycle()) {
            log.debug("T1-DX: skipping evaluation progress on rolled-back cycle");
            return ctx;
        }

        if (DerivedFacts.subgoalScopeMismatch(ctx)) {
            log.debug("Skipping evaluation progress due to SUBGOAL_SCOPE_MISMATCH");
            return ctx;
        }

        if (DerivedFacts.scopeOutOfScope(ctx)) {
            log.debug("Skipping evaluation progress due to SCOPE_OUT_OF_SCOPE");
            return ctx;
        }

        GoalProgressState progress = ctx.goalProgress();
        if (progress == null) return ctx;

        // Keep FULFILLED exclusively in applyCompletion(...)
        if (progress == GoalProgressState.FULFILLED) return ctx;

        String targetGoalId = (ctx.currentSubGoalId() != null) ? ctx.currentSubGoalId() : ctx.currentGoalId();
        if (targetGoalId == null) return ctx;

        GoalProgressApplicationResult applied = goalPlannerService.applyGoalProgress(targetGoalId, progress);

        // For non-fulfilled progress, promotion is not expected, but we still keep context consistent.
        GoalEntity nextGoalEntity = goalPlannerService.getCurrentSubGoal();

        return ctx
                .withLastGoalProgressApplication(applied)
                .withRemainingGoals(goalPlannerService.getRemainingGoals())
                .withCompletedGoals(goalPlannerService.getCompletedGoals())
                .withCurrentSubGoalDescription(nextGoalEntity != null ? nextGoalEntity.getDescription() : null);
    }

    private CycleContextData applyCompletion(CycleContextData ctx) {
        if (ctx == null) return null;

        // T1-DX: a rolled-back cycle did NOT advance the subgoal even if mutationState=ACCEPTED
        // and score≥threshold remained on the context before rollback. Without this guard,
        // score-based closure promotes a subgoal whose code was just reverted from disk —
        // the operator-visible state would say GOAL_FULFILLED while output/ stays empty.
        if (ctx.rolledBackThisCycle()) {
            log.debug("T1-DX: skipping subgoal closure on rolled-back cycle");
            return ctx;
        }

        if (DerivedFacts.subgoalScopeMismatch(ctx)) {
            log.debug("Skipping completion due to SUBGOAL_SCOPE_MISMATCH");
            return ctx;
        }

        if (DerivedFacts.scopeOutOfScope(ctx)) {
            log.debug("Skipping completion due to SCOPE_OUT_OF_SCOPE");
            return ctx;
        }

        String subGoalId = ctx.currentSubGoalId();
        if (subGoalId == null) return ctx;

        var decision = subGoalFulfillmentHeuristic.decide(ctx);

        GoalCompletionDecider.CompletionDecision completionDecision = goalCompletionDecider.decide(ctx, decision.fulfilled());
        log.info("Completion decision: {}", completionDecision); // TODO: do something more useful with this (or eventually remove it)

        if (!decision.fulfilled()) {
            log.info("Subgoal not fulfilled (rationale={}). subGoalId={}", decision.rationale(), subGoalId);
            return ctx;
        }

        // T1-CU: guard score-based closure — do not fire FULFILLED when an accepted mutation has
        // not been merged yet. The evolve→evaluate→merge→complete sequence must be preserved.
        // Bypass when EVOLUTION_NOOP is present (the code was already in output from a prior merge
        // so no merge step is needed) or when the subgoal has a prior successful merge (mergeCount>0)
        // so we only block the very first merge-less FULFILLED on a freshly-started subgoal.
        if (ctx.mutationState() == MutationAcceptanceState.ACCEPTED
                && !ctx.merged()
                && !ctx.hasOpenRequest(BasicSignals.EVOLUTION_NOOP)) {
            int mergeCount = goalPlannerService.getGoalMetadataInt(subGoalId, "mergeCount");
            if (mergeCount == 0) {
                log.warn("T1-CU: suppressing FULFILLED — accepted mutation not merged and mergeCount=0. subGoalId={}", subGoalId);
                return ctx;
            }
        }

        // T3-BK: graduation — inject one micro-subgoal per structurally-stuck unmet criterion before
        // marking the parent FULFILLED so progress is banked and the system moves forward.
        if (decision.graduated()) {
            log.info("T3-BK: graduation triggered for subGoalId={}. Injecting {} micro-subgoal(s) for unmet criteria: {}",
                    subGoalId, decision.unmetCriteriaIndices().size(), decision.unmetCriteriaIndices());
            goalPlannerService.injectGraduationMicroSubgoals(subGoalId, decision.unmetCriteriaIndices(),
                    ctx.currentSubGoalCriteria());
        }

        log.info(
                "Subgoal fulfilled (confidence={}, rationale={}). Marking FULFILLED. subGoalId={}",
                decision.confidence(),
                decision.rationale(),
                subGoalId
        );
        // TODO: trigger merge here?
        String fulfillmentResolution = String.format(java.util.Locale.ROOT,
                "fulfilled (score=%.2f): %s", decision.confidence(), decision.rationale());
        GoalProgressApplicationResult applied = goalPlannerService.applyGoalProgress(subGoalId, GoalProgressState.FULFILLED, fulfillmentResolution);

        boolean promoted = applied != null && applied.promoted();

        GoalEntity nextGoalEntity = goalPlannerService.getCurrentSubGoal();

        return ctx
                .withSubgoalPromoted(promoted)
                .withCurrentSubGoalId(applied != null ? applied.newSubGoalId() : ctx.currentSubGoalId())
                .withRemainingGoals(goalPlannerService.getRemainingGoals())
                .withCompletedGoals(goalPlannerService.getCompletedGoals())
                .withLastGoalProgressApplication(applied)
                .withCurrentSubGoalDescription(nextGoalEntity != null ? nextGoalEntity.getDescription() : null);
    }

    /**
     * T1-CT: Persists the evaluation result (mutationState) to the mutation meta.json written
     * by MutationApplier at evolution time. FileVariantCollector reads meta.json from disk to
     * filter ACCEPTED variants, so the mutationState must be on disk before merge runs — both
     * for same-cycle merge and cross-cycle recovery. Delegates to
     * {@link MutationSnapshotService#persistMutationState} (T1-ED logic lives there).
     */
    private void persistEvaluationResultToMeta(CycleContextData ctx) {
        mutationSnapshotService.persistMutationState(ctx, behaviourLogger);
    }

    private CycleContextData applyMergeOnAcceptedMutation(CycleContextData ctx) {
        if (ctx == null) return null;

        if (ctx.merged()) {
            log.debug("Skipping merge — already merged in this cycle");
            return ctx;
        }

        if (DerivedFacts.subgoalScopeMismatch(ctx)) {
            log.debug("Skipping merge due to SUBGOAL_SCOPE_MISMATCH");
            return ctx;
        }

        if (DerivedFacts.scopeOutOfScope(ctx)) {
            log.debug("Skipping merge due to SCOPE_OUT_OF_SCOPE");
            return ctx;
        }

        boolean mergeRequestedAlready = ctx.hasWindow("merge");
        // T5-AZ: use the explicit per-cycle flag instead of the EVOLUTION_OCCURRED signal, which
        // decays slowly and bleeds into subsequent cycles causing false-positive merge invocations.
        boolean changedThisCycle = ctx.evolutionRanThisCycle();
        // Also fire when the accepted evolution fingerprint survived across a cycle boundary but
        // merge never ran (e.g. scope guard blocked it). EVOLUTION_OCCURRED decays away, but the
        // fingerprint equality tells us the accepted evaluation is still for the latest evolution.
        // T1-CD: FileVariantCollector.collect() now filters to ACCEPTED-only variants, so
        // hasAnyVariant() in the eligibility explainer acts as the safety net — we don't need
        // mutationState == ACCEPTED on the current cycle to allow ROLE_MERGE injection here.
        boolean acceptedEvolutionPending = ctx.lastEvolutionFingerprint() != null
                && Objects.equals(ctx.lastEvolutionFingerprint(), ctx.lastEvaluatedEvolutionFingerprint());

        // Only merge when: evaluation accepted this cycle, OR an accepted evolution from a prior
        // cycle is pending merge (cross-cycle recovery), OR merge was already explicitly requested.
        if (ctx.mutationState() != MutationAcceptanceState.ACCEPTED
                && !acceptedEvolutionPending
                && !mergeRequestedAlready) {
            return ctx;
        }

        if (!changedThisCycle && !acceptedEvolutionPending && !mergeRequestedAlready) {
            return ctx;
        }

        // T4-BZ-4: same-cycle window — decay-equivalent 1.0 gives validity 1, so the grant
        // dies at the window tick and never bleeds into the next cycle (the old full-decay 1:1).
        CycleContextData withMerge = ctx.hasWindow("merge")
                ? ctx
                : ctx.grantWindow(ParticipationWindow.of("merge", 1.0, 1.0, "accepted-mutation"));

        return adaptiveExecutionEngine.tryExecuteRole("merge", withMerge);
    }

    private CycleContextData syncContextFromGoals(CycleContextData ctx) {
        GoalEntity root = goalPlannerService.getRootGoal();
        GoalEntity current = goalPlannerService.getCurrentSubGoal();

        String rootId = root != null ? root.getId() : ctx.currentGoalId();
        String rootDesc = root != null ? root.getDescription() : ctx.currentGoalDescription();

        String subId = current != null ? current.getId() : null;
        String subDesc = current != null ? current.getDescription() : null;

        AttemptIds ids = goalPlannerService.ensureAttemptIdsForCurrentScope();

        // Bug T3-B2: EvolutionAgent.ensureAttemptId() sets lastAttemptId = cycleName (e.g. "cycle-<ts>"),
        // which is the ID under which the EvolutionAttempt node is stored in Neo4j. Always overwriting
        // with the goal-lineage UUID ("attempt-<UUID>") here meant every snapshot lookup queried a UUID
        // that never matched any EvolutionAttempt node, producing "Attempt: (none)" in the ledger.
        //
        // Strategy: preserve the incoming lastAttemptId (carries the cycle-name from the previous
        // evolution run) UNLESS the active subgoal just changed, in which case reset to the new
        // goal-lineage UUID so we don't leak data across subgoal boundaries.
        boolean subgoalChanged = !java.util.Objects.equals(ctx.currentSubGoalId(), subId);
        String existing = ctx.lastAttemptId();
        String resolvedAttemptId = (!subgoalChanged && existing != null && !existing.isBlank())
                ? existing
                : ids.attemptId();

        return ctx.withCurrentGoalId(rootId)
                .withCurrentGoalDescription(rootDesc)
                .withCurrentSubGoalId(subId)
                .withCurrentSubGoalDescription(subDesc)
                .withLastAttemptId(resolvedAttemptId)
                .withParentAttemptId(ids.parentAttemptId());
    }

}
