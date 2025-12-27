package com.walpro.autobuilder.core.interpretation;

import java.util.List;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.interpretation.InterpretationAgent;
import com.walpro.autobuilder.agent.interpretation.model.InterpretationOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.graph.interpretation.InterpretationActionRegistrar;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class InterpretationService {

    private static final String INTERPRETATION_AGENT_ID = "interpretation";

    private final InterpretationAgent interpretationAgent;
    private final InterpretationActionRegistrar registrar;

    public CycleContextData interpret(CycleContextData context) {
        InterpretationOutcome outcome = interpretationAgent.interpret(context);

        registrar.registerInterpretation(INTERPRETATION_AGENT_ID, context, outcome);

        // Apply proposed signals into runtime context (optional, but closes the loop immediately).
        List<Signal> proposedSignals = outcome.proposedSignals();
        if (proposedSignals != null && !proposedSignals.isEmpty()) {
            for (Signal s : proposedSignals) {
                context = context.withSignal(s);
            }
        }

        return context;
    }
}
