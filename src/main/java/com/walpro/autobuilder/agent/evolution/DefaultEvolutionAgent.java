package com.walpro.autobuilder.agent.evolution;

import com.walpro.autobuilder.agent.evolution.graph.EvolutionGraphFacade;
import com.walpro.autobuilder.agent.evolution.model.EvolutionInput;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.protocol.EvolutionProtocolContract;
import com.walpro.autobuilder.agent.evolution.protocol.EvolutionResponseParser;
import com.walpro.autobuilder.agent.shared.LLMAgentProtocolInvoker;
import com.walpro.autobuilder.agent.shared.LLMResponseResult;
import com.walpro.autobuilder.client.LLMClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class DefaultEvolutionAgent implements EvolutionAgent {

    private final LLMClient llmClient;
    private final EvolutionGraphFacade graphFacade;
    private final EvolutionResponseParser parser = new EvolutionResponseParser();
    private final LLMAgentProtocolInvoker llmAgentProtocolInvoker;

    public DefaultEvolutionAgent(LLMClient llmClient, EvolutionGraphFacade graphFacade, LLMAgentProtocolInvoker llmAgentProtocolInvoker) {
        this.llmClient = llmClient;
        this.graphFacade = graphFacade;
        this.llmAgentProtocolInvoker = llmAgentProtocolInvoker;
    }

    @Override
    public EvolutionOutcome evolve(EvolutionInput input) {
        String systemPrompt = EvolutionProtocolContract.getSystemPrompt();
        String userPrompt = EvolutionProtocolContract.getUserPrompt(input);

        LLMResponseResult<EvolutionOutcome> result = llmAgentProtocolInvoker.callWithValidation(
                llmClient,
                systemPrompt,
                userPrompt,
                EvolutionProtocolContract::isValidResponse,
                parser::parse
        );

        if (result.success()) {
            return result.outcome();
        } else {
            log.warn("EvolutionAgent received invalid LLM response. Reason: {}. Content:\n{}",
                    result.failureReason(),
                    result.rawContent() != null ? result.rawContent() : "[null]"
            );

            return new EvolutionOutcome(List.of(), List.of(), "Invalid response: " + result.failureReason());
        }
    }


    @Override
    public void evolveGraph(EvolutionInput input, EvolutionOutcome outcome) {
        graphFacade.recordEvolution(input, outcome);
    }
}
