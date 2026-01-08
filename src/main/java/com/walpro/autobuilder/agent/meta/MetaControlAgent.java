package com.walpro.autobuilder.agent.meta;

import com.walpro.autobuilder.agent.shared.LLMAgentProtocolInvoker;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.protocol.meta.MetaControlDecision;
import com.walpro.autobuilder.protocol.meta.MetaControlPromptBuilder;
import com.walpro.autobuilder.protocol.meta.MetaControlProtocolContract;
import com.walpro.autobuilder.protocol.meta.MetaControlResponseParser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetaControlAgent {

    private final LLMClient llmClient;
    private final LLMAgentProtocolInvoker llmAgentProtocolInvoker;

    private final MetaControlResponseParser responseParser = new MetaControlResponseParser(); // stateless

    public MetaControlDecision decide(CycleContextData context) {
        String systemPrompt = MetaControlProtocolContract.getSystemPrompt();
        String userPrompt = MetaControlPromptBuilder.getUserPrompt(context);

        var result = llmAgentProtocolInvoker.callWithValidation(
                llmClient,
                systemPrompt,
                userPrompt,
                MetaControlProtocolContract::isValidResponse,
                responseParser::parse
        );

        if (result.success()) {
            MetaControlDecision decision = result.outcome();
            log.info("MetaControlDecision: nextRole={}, stop={}, msg={}", decision.nextRole(), decision.stop(), decision.message());
            return decision;
        }

        log.warn("MetaControlAgent skipped: {}", result.failureReason());
        return new MetaControlDecision(MetaControlDecision.NextRole.PLANNER, false, "Meta failed; fallback to PLANNER.", "(meta failed)");
    }
}
