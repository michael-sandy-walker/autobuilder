package com.walpro.autobuilder.core.interpretation;

import java.util.List;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.interpretation.InterpretationAgent;
import com.walpro.autobuilder.agent.interpretation.model.InterpretationOutcome;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.graph.interpretation.InterpretationActionRegistrar;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class InterpretationService {

    private static final String INTERPRETATION_AGENT_ID = "interpretation";

    private final InterpretationAgent interpretationAgent;
    private final InterpretationActionRegistrar registrar;

    public CycleContextData interpret(CycleContextData context) {
        InterpretationOutcome outcome = interpretationAgent.interpret(context);

        registrar.registerInterpretation(INTERPRETATION_AGENT_ID, context, outcome);

        // Carry summary and directives into context so next-cycle agents can read them.
        context = context
                .withInterpretationSummary(outcome.summary())
                .withInterpretationDirectives(outcome.directives());

        // Apply proposed signals into runtime context (optional, but closes the loop immediately).
        // T2-AH: when the stagnation circuit breaker is active, suppress REPLAN_REQUESTED from
        // interpretation to break the stagnate→interpret→replan→stagnate feedback loop.
        List<Signal> proposedSignals = outcome.proposedSignals();
        if (proposedSignals != null && !proposedSignals.isEmpty()) {
            boolean breakerActive = context.stagnationCircuitBreakerActive();
            for (Signal s : proposedSignals) {
                if (breakerActive && BasicSignals.REPLAN_REQUESTED.equals(s.type())) {
                    log.warn("Stagnation circuit breaker active — suppressing REPLAN_REQUESTED from interpretation.");
                    continue;
                }
                context = context.withSignal(s);
            }
        }

        return context;
    }
}
