package com.walpro.autobuilder.agent.evolution;

import com.walpro.autobuilder.agent.evolution.model.EvolutionInput;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;

public interface EvolutionAgent {
    EvolutionOutcome evolve(EvolutionInput input);
    void evolveGraph(EvolutionInput input, EvolutionOutcome outcome);
}
