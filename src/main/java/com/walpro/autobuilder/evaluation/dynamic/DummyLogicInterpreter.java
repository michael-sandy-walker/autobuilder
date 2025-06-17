package com.walpro.autobuilder.evaluation.dynamic;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.evaluation.model.GoalProgressState;
import com.walpro.autobuilder.evaluation.model.MutationAcceptanceState;
import com.walpro.autobuilder.model.EvaluationInput;

@Component
public class DummyLogicInterpreter implements DynamicLogicInterpreter {

    @Override
    public EvaluationOutcome evaluate(DynamicEvaluatorDefinition def, EvaluationInput input) {
        String behavior = def.behavior();
        
        // Placeholder logic: always accept unless "reject" is in behavior
        boolean reject = behavior.toLowerCase().contains("reject");

        return new EvaluationOutcome(
            reject ? MutationAcceptanceState.REJECTED : MutationAcceptanceState.ACCEPTED,
            GoalProgressState.NOT_EVALUATED,
            "Evaluated by dummy logic: " + (reject ? "rejected" : "accepted"),
            null
        );
    }
}
