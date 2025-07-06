//package com.walpro.autobuilder.evaluation;
//
//import java.util.List;
//import java.util.Map;
//import java.util.Set;
//
//import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
//import org.springframework.stereotype.Service;
//
//import com.walpro.autobuilder.evaluation.model.GoalProgressState;
//import com.walpro.autobuilder.evaluation.model.MutationAcceptanceState;
//import com.walpro.autobuilder.evaluation.strategy.GoalProgressStrategy;
//import com.walpro.autobuilder.evaluation.strategy.MutationAcceptanceStrategy;
//import com.walpro.autobuilder.heuristic.HeuristicFinding;
//
//@Service
//public class MutationEvaluator {
//    private final MutationAcceptanceStrategy acceptanceStrategy;
//    private final GoalProgressStrategy goalStrategy;
//
//    public MutationEvaluator(MutationAcceptanceStrategy a, GoalProgressStrategy g) {
//        this.acceptanceStrategy = a;
//        this.goalStrategy = g;
//    }
//
//    public EvaluationOutcome evaluate(
//    	    double score,
//    	    Double lastScore,
//    	    Set<String> updatedFiles,
//    	    Set<String> deletedFiles,
//    	    String goal,
//    	    Map<String, String> projectState,
//    	    List<HeuristicFinding> findings
//    	) {
//    	    MutationAcceptanceState mState = acceptanceStrategy.evaluate(
//    	        score, lastScore, updatedFiles, deletedFiles, findings
//    	    );
//
//    	    GoalProgressState gState = GoalProgressState.NOT_EVALUATED;
//
//    	    if (mState != MutationAcceptanceState.REJECTED) {
//    	        gState = goalStrategy.evaluate(updatedFiles, score, goal, projectState);
//    	    }
//
//    	    String msg = "Mutation: " + mState + ", Goal: " + gState;
//    	    return new EvaluationOutcome(mState, gState, msg, null);
//    	}
//
//}
