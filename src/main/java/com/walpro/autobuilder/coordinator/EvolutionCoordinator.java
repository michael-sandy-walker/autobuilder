package com.walpro.autobuilder.coordinator;

import com.walpro.autobuilder.agent.evolution.EvolutionAgent;
import com.walpro.autobuilder.agent.evolution.model.EvolutionInput;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.lifecycle.CycleContextData;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EvolutionCoordinator {

    private final EvolutionAgent evolutionAgent;

    public CycleContextData evolve(CycleContextData context) {
        EvolutionInput input = EvolutionInput.from(context);

        EvolutionOutcome outcome = evolutionAgent.evolve(input, context.evaluationInterpretation());

        log.info("Evolution produced {} file changes and {} deletions.",
                outcome.addedOrChangedFiles().size(),
                outcome.deletedFiles().size());

        return context
                .withEvolutionOutcome(outcome)
                .withLastEvolutionOutcome(outcome);
    }

    public CycleContextData recombineOnStagnation(CycleContextData context) {
        log.info("♻️ Recombination triggered due to score stagnation — initiating fallback evolution...");

        EvolutionInput input = EvolutionInput.from(context).withRecombineFlag(true);

        EvolutionOutcome outcome = evolutionAgent.evolve(input, context.evaluationInterpretation());

        log.info("Recombination evolution produced {} file changes and {} deletions.",
                outcome.addedOrChangedFiles().size(),
                outcome.deletedFiles().size());

        return context
                .withEvolutionOutcome(outcome)
                .withRecombined(true);
    }
}
