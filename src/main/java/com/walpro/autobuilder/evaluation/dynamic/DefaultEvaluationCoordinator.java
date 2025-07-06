//package com.walpro.autobuilder.evaluation.dynamic;
//
//import com.walpro.autobuilder.evaluation.model.GoalProgressState;
//import com.walpro.autobuilder.evaluation.model.MutationAcceptanceState;
//import com.walpro.autobuilder.model.EvaluationInput;
//import org.springframework.stereotype.Component;
//
//import java.util.List;
//
//@Component
//public class DefaultEvaluationCoordinator implements DynamicEvaluationCoordinator {
//
//    @Override
//    public EvaluationOutcome coordinate(List<EvaluationOutcome> outcomes, EvaluationInput input) {
//        if (outcomes == null || outcomes.isEmpty()) {
//            return new EvaluationOutcome(
//                    MutationAcceptanceState.ACCEPTED,
//                    GoalProgressState.NOT_EVALUATED,
//                    "No evaluators active. Default accept.",
//                    null
//            );
//        }
//
//        boolean anyRejected = false;
//        GoalProgressState bestProgress = GoalProgressState.NOT_EVALUATED;
//        StringBuilder combinedFeedback = new StringBuilder();
//
//        for (EvaluationOutcome o : outcomes) {
//            combinedFeedback.append("- ").append(o.feedbackMessage()).append("\n");
//
//            if (o.mutationState() == MutationAcceptanceState.REJECTED) {
//                anyRejected = true;
//            }
//
//            if (o.goalProgress() == GoalProgressState.FULFILLED) {
//                bestProgress = GoalProgressState.FULFILLED;
//            } else if (o.goalProgress() == GoalProgressState.PROGRESSING && bestProgress != GoalProgressState.FULFILLED) {
//                bestProgress = GoalProgressState.PROGRESSING;
//            } else if (o.goalProgress() == GoalProgressState.STAGNATING
//                    && bestProgress == GoalProgressState.NOT_EVALUATED) {
//                bestProgress = GoalProgressState.STAGNATING;
//            }
//        }
//
//        return new EvaluationOutcome(
//                anyRejected ? MutationAcceptanceState.REJECTED : MutationAcceptanceState.ACCEPTED,
//                bestProgress,
//                combinedFeedback.toString().trim(),
//                null
//        );
//    }
//}
