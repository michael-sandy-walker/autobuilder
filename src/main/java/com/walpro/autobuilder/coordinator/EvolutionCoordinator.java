package com.walpro.autobuilder.coordinator;

import com.walpro.autobuilder.agent.evolution.EvolutionAgent;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.lifecycle.CycleContextData;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EvolutionCoordinator implements AgentCoordinator {

    private final EvolutionAgent evolutionAgent;

    public CycleContextData evolve(CycleContextData context) {
        EvolutionOutcome outcome = evolutionAgent.evolve(context, context.evaluationInterpretation());

        log.info("⚙️ Evolution produced {} file changes and {} deletions.",
                outcome.addedOrChangedFiles().size(),
                outcome.deletedFiles().size());

        return context
                .withEvolutionOutcome(outcome)
                .withLastEvolutionOutcome(outcome);
    }

    public CycleContextData recombineOnStagnation(CycleContextData context) {
        log.info("♻️ Recombination triggered due to score stagnation — initiating fallback evolution...");

        EvolutionOutcome outcome = evolutionAgent.evolve(context.withRecombined(true), context.evaluationInterpretation());

        log.info("♻️ Recombination evolution produced {} file changes and {} deletions.",
                outcome.addedOrChangedFiles().size(),
                outcome.deletedFiles().size());

        return context
                .withEvolutionOutcome(outcome)
                .withRecombined(true);
    }

    @Override
    public String role() {
        return "evolution";
    }

    @Override
    public boolean canExecute(CycleContextData context) {
        // mimic logic currently in the corresponding AgentWrapper
        return true; // minimal version; refine next step
    }

    @Override
    public CycleContextData execute(CycleContextData context) {
        return evolve(context);
    }
}
