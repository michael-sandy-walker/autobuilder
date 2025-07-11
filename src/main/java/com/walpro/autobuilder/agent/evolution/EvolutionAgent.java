package com.walpro.autobuilder.agent.evolution;

import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter;
import com.walpro.autobuilder.agent.evolution.model.EvolutionInput;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;

public interface EvolutionAgent {
    EvolutionOutcome evolve(EvolutionInput input, EvaluationInterpreter.InterpretationResult result);
    void evolveGraph(EvolutionInput input, EvolutionOutcome outcome);
}
