package com.walpro.autobuilder.agent.evolution;

import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Component;

import com.walpro.autobuilder.graph.evolution.EvolutionActionRegistrar;
import com.walpro.autobuilder.graph.evolution.EvolutionGraphFacade;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.protocol.evolution.EvolutionProtocolContract;
import com.walpro.autobuilder.protocol.evolution.EvolutionResponseParser;
import com.walpro.autobuilder.agent.shared.LLMAgentProtocolInvoker;
import com.walpro.autobuilder.agent.shared.LLMResponseResult;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultEvolutionAgent implements EvolutionAgent {

    private final LLMClient llmClient;
    private final EvolutionGraphFacade graphFacade;    
    private final LLMAgentProtocolInvoker llmAgentProtocolInvoker;
    private final EvolutionActionRegistrar evolutionActionRegistrar;
    
    private final EvolutionResponseParser parser = new EvolutionResponseParser(); // not injected

    @Override
    public CycleContextData evolve(CycleContextData context) throws IOException {
        var result = context.evaluationInterpretation();
        String systemPrompt = EvolutionProtocolContract.getSystemPrompt();
        String userPrompt = EvolutionProtocolContract.getUserPrompt(context, result);

        LLMResponseResult<EvolutionOutcome> response = llmAgentProtocolInvoker.callWithValidation(
                llmClient,
                systemPrompt,
                userPrompt,
                EvolutionProtocolContract::isValidResponse,
                parser::parse
        );

        EvolutionOutcome outcome;

        if (response.success()) {
            outcome = response.outcome();

            evolutionActionRegistrar.registerEvolution("StaticEvolutionAgent", context, outcome);
            evolveGraph(context, outcome);
        } else {
            log.warn("EvolutionAgent received invalid LLM response. Reason: {}. Content:\n{}",
                    response.failureReason(),
                    response.rawContent() != null ? response.rawContent() : "[null]"
            );

            outcome = new EvolutionOutcome(
                    List.of(),
                    List.of(),
                    "Invalid response: " + response.failureReason(),
                    response.rawContent()
            );
        }

        return context
                .withEvolutionOutcome(outcome);
    }

    @Override
    public void evolveGraph(CycleContextData context, EvolutionOutcome outcome) {
        graphFacade.recordEvolution(context, outcome);
    }

}
