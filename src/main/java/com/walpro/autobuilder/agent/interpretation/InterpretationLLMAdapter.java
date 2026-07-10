package com.walpro.autobuilder.agent.interpretation;

import java.util.List;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.interpretation.model.InterpretationOutcome;
import com.walpro.autobuilder.agent.shared.LLMAgentProtocolInvoker;
import com.walpro.autobuilder.agent.shared.LLMResponseResult;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.core.ArtifactEntity;
import com.walpro.autobuilder.protocol.interpretation.InterpretationProtocolContract;
import com.walpro.autobuilder.protocol.interpretation.InterpretationResponseParser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class InterpretationLLMAdapter {

    private final InterpretationResponseParser parser = new InterpretationResponseParser();

    private final LLMClient llmClient;
    private final LLMAgentProtocolInvoker protocolInvoker;

    public InterpretationOutcome interpret(CycleContextData context, List<ArtifactEntity> artifacts) {
        String systemPrompt = InterpretationProtocolContract.getSystemPrompt();
        String userPrompt = InterpretationProtocolContract.getUserPrompt(context, artifacts);

        LLMResponseResult<InterpretationOutcome> result = protocolInvoker.callWithValidation(
                llmClient,
                systemPrompt,
                userPrompt,
                InterpretationProtocolContract::isValidResponse,
                parser::parse
        );

        if (result.success()) {
            log.debug("InterpretationAgent LLM call succeeded. Status={}, requests={}",
                    result.outcome().status(), result.outcome().proposedRequests());
            return result.outcome();
        }

        log.warn("InterpretationAgent received invalid LLM response. Reason: {}. Content:\n{}",
                result.failureReason(),
                result.rawContent() != null ? result.rawContent() : "[null]"
        );

        return InterpretationOutcome.noop("LLM response did not follow interpretation protocol."
                + (result.rawContent() != null ? "\n" + result.rawContent() : ""));
    }
}
