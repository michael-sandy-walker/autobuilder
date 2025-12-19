package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.agent.meta.MetaAgent;
import com.walpro.autobuilder.analysis.heuristic.SubGoalFulfillmentHeuristic;
import com.walpro.autobuilder.core.deliberation.DeliberationService;
import com.walpro.autobuilder.core.engine.AdaptiveExecutionEngine;
import com.walpro.autobuilder.goal.GoalPlannerService;
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

    private final MetaAgent metaAgent;
    private final AdaptiveExecutionEngine adaptiveExecutionEngine;
    private final DeliberationService deliberationService;

    private final GoalPlannerService goalPlannerService;

    private final SubGoalFulfillmentHeuristic subGoalFulfillmentHeuristic;


    public CycleContextData runCycle(CycleContextData context) {
        var decision = deliberationService.deliberate(context);
        context = decision.context().withDeliberationResult(decision.result());

        if (decision.result() != null && decision.result().proposedSignals() != null) {
            for (Signal s : decision.result().proposedSignals()) {
                context = context.withSignal(s);
            }
        }

        if (context.hasStrongSignal(BasicSignals.SYSTEM_PAUSED, 0.50)) {
            log.info("System is paused (cycle {}). Skipping run.", context.cycleName());
            return context.tickSignals(SIGNAL_SURVIVAL_THRESHOLD);
        }

//        if (context.hasStrongSignal(BasicSignals.GOAL_EXHAUSTED, 0.50)) {
//            log.warn("Goal already exhausted before engine run (cycle {}). Running Meta deliberation only.", context.cycleName());
//
//            context = context.tickSignals(SIGNAL_SURVIVAL_THRESHOLD);
//            context = metaAgent.analyzeAndEvolve(context);
//
//            // If MetaAgent did not transition to a new actionable goal, pause.
//            if (context.hasStrongSignal(BasicSignals.GOAL_EXHAUSTED, 0.50)) {
//                context = context.withSignal(Signal.of(BasicSignals.SYSTEM_PAUSED, 1.0, 0.01, "CycleExecutor"));
//                log.warn("No goal transition occurred. System paused.");
//            }
//
//            return context;
//        }

        context = adaptiveExecutionEngine.runCycle(context);

        CycleResultState result = deriveCycleResult(context);
        context = context.withCycleResultState(result);

        context = applyCompletionAndMaybeTriggerMerge(context);

        context = context.tickSignals(SIGNAL_SURVIVAL_THRESHOLD);
        log.debug("Signals after completed cycle {}: {}", context.cycleName(), context.signals());

//        context = metaAgent.analyzeAndEvolve(context);

        return context;
    }

    private CycleResultState deriveCycleResult(CycleContextData ctx) {

        // 1. Goal explicitly exhausted
        if (ctx.hasSignal(BasicSignals.GOAL_EXHAUSTED)) {
            return CycleResultState.GOAL_EXHAUSTED;
        }

        // 2. Goal fulfilled
        if (ctx.goalProgress() == GoalProgressState.PROGRESSING
                && ctx.mutationState() == MutationAcceptanceState.ACCEPTED
                && ctx.remainingGoals().isEmpty()) {

            return CycleResultState.GOAL_FULFILLED;
        }

        // 3. System stagnation
        if (ctx.goalProgress() == GoalProgressState.STAGNATING) {
            return CycleResultState.STAGNATING;
        }

        return CycleResultState.CONTINUE;
    }

    private CycleContextData applyCompletionAndMaybeTriggerMerge(CycleContextData ctx) {
        if (ctx == null) return null;

        String subGoalId = ctx.currentSubGoalId();
        if (subGoalId == null) return ctx;

        var decision = subGoalFulfillmentHeuristic.decide(ctx);
        if (!decision.fulfilled()) {
            return ctx;
        }

        log.info(
                "✅ Subgoal fulfilled (confidence={}, rationale={}). Marking FULFILLED and triggering merge. subGoalId={}",
                decision.confidence(),
                decision.rationale(),
                subGoalId
        );

        goalPlannerService.applyGoalProgress(subGoalId, GoalProgressState.FULFILLED);

        CycleContextData updated = ctx
                .withSubgoalPromoted(true)
                .withRemainingGoals(goalPlannerService.getRemainingGoals())
                .withCompletedGoals(goalPlannerService.getCompletedGoals());

        Signal merge = Signal.of(
                BasicSignals.ROLE_MERGE,
                1.0,
                0.20,
                "subgoal-fulfilled"
        );

        return updated.withSignal(merge);
    }

}
