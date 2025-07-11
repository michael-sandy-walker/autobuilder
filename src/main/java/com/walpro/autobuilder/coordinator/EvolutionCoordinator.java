package com.walpro.autobuilder.coordinator;

import com.walpro.autobuilder.agent.evolution.EvolutionAgent;
import com.walpro.autobuilder.agent.evolution.model.EvolutionInput;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.model.CycleContextData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EvolutionCoordinator {

    private final EvolutionAgent evolutionAgent;

    public CycleContextData evolve(CycleContextData context) {
        EvolutionInput input = new EvolutionInput(
                context.currentGoalDescription(),
                context.currentSubGoalDescription(),
                context.currentGoalId(),
                context.currentSubGoalId(),
                context.remainingGoals(),
                context.completedGoals(),
                context.projectSummary(),
                context.diffReport(),
                context.rejectionFeedback(),
                context.recommendation(),
                false
        );

        EvolutionOutcome outcome = evolutionAgent.evolve(input, context.evaluationInterpretation());

        log.info("Evolution produced {} file changes and {} deletions.",
                outcome.addedOrChangedFiles().size(),
                outcome.deletedFiles().size());

        return context.withEvolutionOutcome(outcome);
    }
    
    public CycleContextData recombineOnStagnation(CycleContextData context) {
        log.info("♻️ Recombination triggered due to score stagnation — initiating fallback evolution...");

        EvolutionInput input = new EvolutionInput(
                context.currentGoalDescription(),
                context.currentSubGoalDescription(),
                context.currentGoalId(),
                context.currentSubGoalId(),
                context.remainingGoals(),
                context.completedGoals(),
                context.projectSummary(),
                context.diffReport(),
                context.rejectionFeedback(),
                context.recommendation(),
                true
        );

        EvolutionOutcome outcome = evolutionAgent.evolve(input, context.evaluationInterpretation());

        log.info("Recombination evolution produced {} file changes and {} deletions.",
                outcome.addedOrChangedFiles().size(),
                outcome.deletedFiles().size());

        return context
                .withEvolutionOutcome(outcome)
                .withRecombined(true); // ⬅️ mark that recombination was triggered
    }

    
}