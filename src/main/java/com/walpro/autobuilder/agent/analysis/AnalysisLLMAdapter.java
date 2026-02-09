// NEW FILE: src/main/java/com/walpro/autobuilder/agent/analysis/AnalysisLLMAdapter.java
package com.walpro.autobuilder.agent.analysis;

import com.walpro.autobuilder.agent.analysis.model.AnalysisOutcome;
import com.walpro.autobuilder.agent.shared.LLMAgentProtocolInvoker;
import com.walpro.autobuilder.agent.shared.LLMResponseResult;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.protocol.analysis.AnalysisProtocolContract;
import com.walpro.autobuilder.protocol.analysis.AnalysisResponseParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisLLMAdapter {

    private final AnalysisResponseParser parser = new AnalysisResponseParser();

    private final LLMClient llmClient;
    private final LLMAgentProtocolInvoker protocolInvoker;

    public AnalysisOutcome analyze(CycleContextData context) {
        String systemPrompt = AnalysisProtocolContract.getSystemPrompt();
        String userPrompt = AnalysisProtocolContract.getUserPrompt(context);

        LLMResponseResult<AnalysisOutcome> result = protocolInvoker.callWithValidation(
                llmClient,
                systemPrompt,
                userPrompt,
                AnalysisProtocolContract::isValidResponse,
                parser::parse
        );

        if (result.success()) {
            return result.outcome();
        }

        log.warn("AnalysisAgent received invalid LLM response. Reason: {}. Content:\n{}",
                result.failureReason(),
                result.rawContent() != null ? result.rawContent() : "[null]"
        );

        return AnalysisOutcome.empty("LLM response did not follow analysis protocol.");
    }
}