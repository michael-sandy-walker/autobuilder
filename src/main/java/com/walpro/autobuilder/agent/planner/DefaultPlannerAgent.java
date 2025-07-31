package com.walpro.autobuilder.agent.planner;

import java.util.List;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.planner.graph.PlannerActionRegistrar;
import com.walpro.autobuilder.agent.planner.graph.PlannerGraphFacade;
import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.agent.planner.protocol.PlannerProtocolContract;
import com.walpro.autobuilder.agent.planner.protocol.PlannerResponseParser;
import com.walpro.autobuilder.agent.shared.LLMAgentProtocolInvoker;
import com.walpro.autobuilder.agent.shared.LLMResponseResult;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.lifecycle.CycleContextData;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultPlannerAgent implements PlannerAgent {

    private final PlannerGraphFacade graphFacade;
    private final LLMClient llmClient;
    private final LLMAgentProtocolInvoker llmAgentProtocolInvoker;
    private final PlannerActionRegistrar plannerActionRegistrar;
    
    private final PlannerResponseParser parser = new PlannerResponseParser(); // not injected

    @Override
    public PlannerOutcome plan(CycleContextData input) {
        String systemPrompt = PlannerProtocolContract.getSystemPrompt();
        String userPrompt = PlannerProtocolContract.getUserPrompt(input);

        LLMResponseResult<PlannerOutcome> result = llmAgentProtocolInvoker.callWithValidation(
                llmClient,
                systemPrompt,
                userPrompt,
                PlannerProtocolContract::isValidResponse,
                parser::parse
        );

        if (result.success()) {
        	PlannerOutcome outcome = result.outcome();
        	plannerActionRegistrar.registerPlannerAction("PlannerAlpha", input, outcome);
            return outcome;
        } else {
            log.warn("PlannerAgent received invalid LLM response. Reason: {}. Content:\n{}",
                    result.failureReason(),
                    result.rawContent() != null ? result.rawContent() : "[null]"
            );

            return new PlannerOutcome(false, List.of(), "Invalid response: " + result.failureReason());
        }
    }

    @Override
    public void evolveGraph(CycleContextData input, PlannerOutcome outcome) {
        graphFacade.recordPlanning(input, outcome);
    }

}
