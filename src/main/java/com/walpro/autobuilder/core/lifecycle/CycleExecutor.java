package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.analysis.heuristic.SubGoalFulfillmentHeuristic;
import com.walpro.autobuilder.analysis.pattern.PatternSignalAnalyzer;
import com.walpro.autobuilder.analysis.pattern.PlanningThrashSignalAnalyzer;
import com.walpro.autobuilder.analysis.pattern.ReplanPatternDetector;
import com.walpro.autobuilder.analysis.pattern.ScopeThrashSignalAnalyzer;
import com.walpro.autobuilder.analysis.problem.GoalCompletionDecider;
import com.walpro.autobuilder.analysis.scope.SubgoalScopeGuard;
import com.walpro.autobuilder.core.engine.AdaptiveExecutionEngine;
import com.walpro.autobuilder.core.interpretation.InterpretationService;
import com.walpro.autobuilder.goal.AttemptIds;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalProgressApplicationResult;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import lombok.RequiredArgsConstructor;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CycleExecutor {

    /** Minimum signal strength below which a signal disappears. */
    public static final double SIGNAL_SURVIVAL_THRESHOLD = 0.05;

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

    public CycleContextData runCycle(CycleContextData context) {

        goalPlannerService.setCurrentCycleContext(context);

        context = syncContextFromGoals(context);

        if (context.hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.50)) {
            log.info("System is paused (cycle {}). Skipping run.", context.cycleName());
            return context.tickSignals(SIGNAL_SURVIVAL_THRESHOLD);
        }

        context = adaptiveExecutionEngine.runCycle(context);

        context = subgoalScopeGuard.guard(context);

//        if (context.hasSignal(BasicSignals.SCOPE_OUT_OF_SCOPE) && context.bestMatchSubGoalId() != null) {
//            context = adaptiveExecutionEngine.tryExecuteRole("planner", context);
//            context = syncContextFromGoals(context);
//            context = context.withoutSignal(BasicSignals.SCOPE_OUT_OF_SCOPE).withBestMatchSubGoalId(null);
//        }

        context = applyEvaluationInterpretationIfPresent(context);

        context = patternSignalAnalyzer.analyze(context);
        if (!context.hasSignal(BasicSignals.SCOPE_OUT_OF_SCOPE)) {
            context = planningThrashSignalAnalyzer.analyze(context);
            context = replanPatternDetector.detect(context);
        }
        context = scopeThrashSignalAnalyzer.analyze(context);

        context = applyMergeOnAcceptedMutation(context);

        cycleScoreRecorder.recordGoalEventsIfPresent(context);
        // TODO: ensure cycle targets goal (e.g., "goalGraphService.ensureCycleTargetsGoal(ctx.cycleName(), ctx.currentSubGoalId() != null ? ctx.currentSubGoalId() : ctx.currentGoalId())")

        context = applyEvaluationProgressIfPresent(context);
        context = applyCompletion(context);

        CycleResultState result = deriveCycleResult(context);
        context = context.withCycleResultState(result);

        context = interpretationService.interpret(context);

        context = context.tickSignals(SIGNAL_SURVIVAL_THRESHOLD);
        log.debug("Signals after completed cycle {}: {}", context.cycleName(), context.signals());

        // T2-BK: persist subgoal-scoped signals to the entity after decay so subgoal switches
        // and restarts restore the correct per-subgoal history rather than global carried state.
        if (context.currentSubGoalId() != null && context.signals() != null) {
            java.util.Map<SignalType, Signal> scoped = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<SignalType, Signal> e : context.signals().entrySet()) {
                if (BasicSignals.SUBGOAL_SCOPED_SIGNALS.contains(e.getKey())) {
                    scoped.put(e.getKey(), e.getValue());
                }
            }
            goalPlannerService.saveSubgoalSignals(context.currentSubGoalId(), scoped);
        }

        return context;
    }

    private CycleResultState deriveCycleResult(CycleContextData ctx) {

        if (ctx.hasSignal(BasicSignals.SUBGOAL_EXHAUSTED)) {
            return CycleResultState.SUBGOAL_EXHAUSTED;
        }

        if (ctx.hasSignal(BasicSignals.GOAL_EXHAUSTED)) {
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
            log.warn("[T1-BH] leafCompletedNow=true and remainingGoals empty, but root goal is not COMPLETED (status={}). " +
                    "Suppressing false GOAL_FULFILLED — likely all subgoals were given up, not completed.",
                    root != null ? root.getStatus() : "null");
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

        if (ctx.hasSignal(BasicSignals.SUBGOAL_SCOPE_MISMATCH)) {
            log.debug("Skipping evaluation progress due to SUBGOAL_SCOPE_MISMATCH");
            return ctx;
        }

        if (ctx.hasSignal(BasicSignals.SCOPE_OUT_OF_SCOPE)) {
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

        if (ctx.hasSignal(BasicSignals.SUBGOAL_SCOPE_MISMATCH)) {
            log.debug("Skipping completion due to SUBGOAL_SCOPE_MISMATCH");
            return ctx;
        }

        if (ctx.hasSignal(BasicSignals.SCOPE_OUT_OF_SCOPE)) {
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

        log.info(
                "Subgoal fulfilled (confidence={}, rationale={}). Marking FULFILLED. subGoalId={}",
                decision.confidence(),
                decision.rationale(),
                subGoalId
        );
        // TODO: trigger merge here?
        GoalProgressApplicationResult applied = goalPlannerService.applyGoalProgress(subGoalId, GoalProgressState.FULFILLED);

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

    private CycleContextData applyEvaluationInterpretationIfPresent(CycleContextData ctx) {
        if (ctx == null) return null;

        if (ctx.hasSignal(BasicSignals.SUBGOAL_SCOPE_MISMATCH)) {
            log.debug("Skipping evaluation interpretation due to SUBGOAL_SCOPE_MISMATCH");
            return ctx;
        }

        if (ctx.hasSignal(BasicSignals.SCOPE_OUT_OF_SCOPE)) {
            log.debug("Skipping evaluation interpretation due to SCOPE_OUT_OF_SCOPE");
            return ctx;
        }

        var ir = ctx.evaluationInterpretation();
        if (ir == null) return ctx;

        // This is the only place that writes mutationState/goalProgress.
        return ctx
                .withMutationState(ir.mutationState())
                .withGoalProgress(ir.goalProgress());
    }

    private CycleContextData applyMergeOnAcceptedMutation(CycleContextData ctx) {
        if (ctx == null) return null;

        if (ctx.merged()) {
            log.debug("Skipping merge — already merged in this cycle");
            return ctx;
        }

        if (ctx.hasSignal(BasicSignals.SUBGOAL_SCOPE_MISMATCH)) {
            log.debug("Skipping merge due to SUBGOAL_SCOPE_MISMATCH");
            return ctx;
        }

        if (ctx.hasSignal(BasicSignals.SCOPE_OUT_OF_SCOPE)) {
            log.debug("Skipping merge due to SCOPE_OUT_OF_SCOPE");
            return ctx;
        }

        // Only merge when evaluation accepted
        if (ctx.mutationState() != MutationAcceptanceState.ACCEPTED) {
            return ctx;
        }

        boolean mergeRequestedAlready = ctx.hasSignal(BasicSignals.ROLE_MERGE);
        // T5-AZ: use the explicit per-cycle flag instead of the EVOLUTION_OCCURRED signal, which
        // decays slowly and bleeds into subsequent cycles causing false-positive merge invocations.
        boolean changedThisCycle = ctx.evolutionRanThisCycle();
        // Also fire when the accepted evolution fingerprint survived across a cycle boundary but
        // merge never ran (e.g. scope guard blocked it). EVOLUTION_OCCURRED decays away, but the
        // fingerprint equality tells us the accepted evaluation is still for the latest evolution.
        boolean acceptedEvolutionPending = ctx.lastEvolutionFingerprint() != null
                && Objects.equals(ctx.lastEvolutionFingerprint(), ctx.lastEvaluatedEvolutionFingerprint());

        if (!changedThisCycle && !acceptedEvolutionPending && !mergeRequestedAlready) {
            return ctx;
        }

        Signal merge = Signal.of(
                BasicSignals.ROLE_MERGE,
                1.0,
                1.0, // full decay: dies at tickSignals so it never bleeds into the next cycle
                "accepted-mutation"
        );

        CycleContextData withMerge = ctx.hasSignal(BasicSignals.ROLE_MERGE)
                ? ctx
                : ctx.withSignal(merge);

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
