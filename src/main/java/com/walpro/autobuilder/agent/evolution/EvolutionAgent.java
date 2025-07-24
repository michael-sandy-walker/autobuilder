package com.walpro.autobuilder.agent.evolution;

import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.lifecycle.CycleContextData;

public interface EvolutionAgent {
    EvolutionOutcome evolve(CycleContextData input, EvaluationInterpreter.InterpretationResult result);
    void evolveGraph(CycleContextData input, EvolutionOutcome outcome);
}
