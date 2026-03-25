package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.analysis.heuristic.SubGoalFulfillmentHeuristic;
import com.walpro.autobuilder.analysis.pattern.PatternSignalAnalyzer;
import com.walpro.autobuilder.analysis.pattern.PlanningThrashSignalAnalyzer;
import com.walpro.autobuilder.analysis.problem.GoalCompletionDecider;
import com.walpro.autobuilder.analysis.scope.SubgoalScopeGuard;
import com.walpro.autobuilder.core.engine.AdaptiveExecutionEngine;
import com.walpro.autobuilder.core.interpretation.InterpretationService;
import com.walpro.autobuilder.goal.AttemptIds;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalProgressApplicationResult;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import lombok.RequiredArgsConstructor;
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

//        if (context.hasSignal(BasicSignals.SCOPE_VERDICT_OUT_OF_SCOPE) && context.bestMatchSubGoalId() != null) {
//            context = adaptiveExecutionEngine.tryExecuteRole("planner", context);
//            context = syncContextFromGoals(context);
//            context = context.withoutSignal(BasicSignals.SCOPE_VERDICT_OUT_OF_SCOPE).withBestMatchSubGoalId(null);
//        }

        context = applyEvaluationInterpretationIfPresent(context);

        context = patternSignalAnalyzer.analyze(context);
        if (!context.hasSignal(BasicSignals.SCOPE_VERDICT_OUT_OF_SCOPE)) {
            context = planningThrashSignalAnalyzer.analyze(context);
        }

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

        return context;
    }

    private CycleResultState deriveCycleResult(CycleContextData ctx) {

        if (ctx.hasSignal(BasicSignals.GOAL_EXHAUSTED)) {
            return CycleResultState.GOAL_EXHAUSTED;
        }

        GoalProgressApplicationResult goalProgressApplicationResult = ctx.lastGoalProgressApplication();

        if (goalProgressApplicationResult != null && goalProgressApplicationResult.rootAutoCompleted()) {
            return CycleResultState.GOAL_FULFILLED;
        }

        boolean leafCompletedNow =
                goalProgressApplicationResult != null
                        && goalProgressApplicationResult.promoted()
                        && goalProgressApplicationResult.previousSubGoalId() != null
                        && ctx.currentSubGoalId() == null; // TODO: make sure that there is no more current subgoal id present after every subgoal is completed
        if ((leafCompletedNow || ctx.goalProgress() == GoalProgressState.PROGRESSING)
                && ctx.mutationState() == MutationAcceptanceState.ACCEPTED
                && ctx.remainingGoals().isEmpty()) {

            return CycleResultState.GOAL_FULFILLED;
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

        if (ctx.hasSignal(BasicSignals.SCOPE_VERDICT_OUT_OF_SCOPE)) {
            log.debug("Skipping evaluation progress due to SCOPE_VERDICT_OUT_OF_SCOPE");
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

        if (ctx.hasSignal(BasicSignals.SCOPE_VERDICT_OUT_OF_SCOPE)) {
            log.debug("Skipping completion due to SCOPE_VERDICT_OUT_OF_SCOPE");
            return ctx;
        }

        String subGoalId = ctx.currentSubGoalId();
        if (subGoalId == null) return ctx;

        var decision = subGoalFulfillmentHeuristic.decide(ctx);

        GoalCompletionDecider.CompletionDecision completionDecision = goalCompletionDecider.decide(ctx, decision.fulfilled());
        log.info("Completion decision: {}", completionDecision); // TODO: do something more useful with this (or eventually remove it)

        if (!decision.fulfilled()) {
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

        if (ctx.hasSignal(BasicSignals.SCOPE_VERDICT_OUT_OF_SCOPE)) {
            log.debug("Skipping evaluation interpretation due to SCOPE_VERDICT_OUT_OF_SCOPE");
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

        if (ctx.hasSignal(BasicSignals.SCOPE_VERDICT_OUT_OF_SCOPE)) {
            log.debug("Skipping merge due to SCOPE_VERDICT_OUT_OF_SCOPE");
            return ctx;
        }

        // Only merge when evaluation accepted
        if (ctx.mutationState() != MutationAcceptanceState.ACCEPTED) {
            return ctx;
        }

        boolean mergeRequestedAlready = ctx.hasSignal(BasicSignals.ROLE_MERGE);
        boolean changedThisCycle = ctx.hasSignal(BasicSignals.EVOLUTION_OCCURRED);

        // Only merge when something actually changed this cycle or if there already is a merge request
        if (!changedThisCycle && !mergeRequestedAlready) {
            return ctx;
        }

        Signal merge = Signal.of(
                BasicSignals.ROLE_MERGE,
                1.0,
                0.0, // should not decay before consumption
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

        return ctx.withCurrentGoalId(rootId)
                .withCurrentGoalDescription(rootDesc)
                .withCurrentSubGoalId(subId)
                .withCurrentSubGoalDescription(subDesc)
                .withLastAttemptId(ids.attemptId())
                .withParentAttemptId(ids.parentAttemptId());
    }

}
