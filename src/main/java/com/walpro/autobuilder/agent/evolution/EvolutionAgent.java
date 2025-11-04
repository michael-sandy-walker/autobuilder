package com.walpro.autobuilder.agent.evolution;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import java.io.IOException;

public interface EvolutionAgent {
    CycleContextData evolve(CycleContextData context) throws IOException;
    void evolveGraph(CycleContextData input, EvolutionOutcome outcome);
}
