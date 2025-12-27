package com.walpro.autobuilder.agent.interpretation;

import com.walpro.autobuilder.agent.interpretation.model.InterpretationOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

public interface InterpretationAgent {
    InterpretationOutcome interpret(CycleContextData context);
}
