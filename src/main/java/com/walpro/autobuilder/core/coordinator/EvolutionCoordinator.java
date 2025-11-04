package com.walpro.autobuilder.core.coordinator;

import com.walpro.autobuilder.agent.evolution.EvolutionAgent;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class EvolutionCoordinator implements AgentCoordinator {

    private final EvolutionAgent evolutionAgent;

    public CycleContextData evolve(CycleContextData context) throws IOException {
        CycleContextData updated = evolutionAgent.evolve(context);
        logEvolution("⚙️ Evolution", updated);
        return updated.withLastEvolutionOutcome(updated.evolutionOutcome());
    }

    public CycleContextData recombineOnStagnation(CycleContextData context) throws IOException {
        log.info("♻️ Recombination triggered due to score stagnation — initiating fallback evolution...");
        CycleContextData updated = evolutionAgent.evolve(context.withRecombined(true));
        logEvolution("♻️ Recombination", updated);
        return updated.withRecombined(true);
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
        try {
            return context.recombined()
                    ? recombineOnStagnation(context)
                    : evolve(context);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void logEvolution(String prefix, CycleContextData context) {
        var outcome = context.evolutionOutcome();
        log.info("{} produced {} file changes and {} deletions.",
                prefix,
                outcome.addedOrChangedFiles().size(),
                outcome.deletedFiles().size()
        );
    }
}
