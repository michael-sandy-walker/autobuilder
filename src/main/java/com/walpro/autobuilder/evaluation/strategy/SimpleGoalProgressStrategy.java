package com.walpro.autobuilder.evaluation.strategy;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.config.EvaluationConfig;
import com.walpro.autobuilder.evaluation.model.GoalProgressState;

@Component
public class SimpleGoalProgressStrategy implements GoalProgressStrategy {
	
    private final EvaluationConfig config;
    
    public SimpleGoalProgressStrategy(EvaluationConfig config) {
        this.config = config;
    }

	
    @Override
    public GoalProgressState evaluate(Set<String> filesChanged, double score, String goal, Map<String, String> projectState) {
        if (filesChanged.size() < config.getHeuristicThreshold()) {
        	return GoalProgressState.STAGNATING;
        }
        
        // Future: Add deeper analysis of whether changes relate to the current goal
        
        return GoalProgressState.PROGRESSING;
    }
}
