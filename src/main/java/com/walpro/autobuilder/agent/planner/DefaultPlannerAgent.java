package com.walpro.autobuilder.agent.planner;

import com.walpro.autobuilder.agent.planner.graph.PlannerGraphFacade;
import com.walpro.autobuilder.agent.planner.model.PlannerInput;
import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.agent.planner.protocol.PlannerProtocolContract;
import com.walpro.autobuilder.agent.planner.protocol.PlannerResponseParser;
import com.walpro.autobuilder.agent.shared.LLMAgentProtocolInvoker;
import com.walpro.autobuilder.agent.shared.LLMResponseResult;
import com.walpro.autobuilder.client.LLMClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class DefaultPlannerAgent implements PlannerAgent {

    private final PlannerGraphFacade graphFacade;
    private final LLMClient llmClient;
    private final PlannerResponseParser parser = new PlannerResponseParser();
    private final LLMAgentProtocolInvoker llmAgentProtocolInvoker;

    public DefaultPlannerAgent(PlannerGraphFacade graphFacade, LLMClient llmClient, LLMAgentProtocolInvoker llmAgentProtocolInvoker) {
        this.graphFacade = graphFacade;
        this.llmClient = llmClient;
        this.llmAgentProtocolInvoker = llmAgentProtocolInvoker;
    }

    @Override
    public PlannerOutcome plan(PlannerInput input) {
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
            return result.outcome();
        } else {
            log.warn("PlannerAgent received invalid LLM response. Reason: {}. Content:\n{}",
                    result.failureReason(),
                    result.rawContent() != null ? result.rawContent() : "[null]"
            );

            return new PlannerOutcome(false, List.of(), "Invalid response: " + result.failureReason());
        }
    }

    @Override
    public void evolveGraph(PlannerInput input, PlannerOutcome outcome) {
        graphFacade.recordPlanning(input, outcome);
    }

}
