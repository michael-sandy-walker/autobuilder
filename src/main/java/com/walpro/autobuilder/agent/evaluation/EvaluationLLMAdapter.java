package com.walpro.autobuilder.agent.evaluation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.walpro.autobuilder.protocol.evaluation.EvaluationProtocolContract;
import com.walpro.autobuilder.protocol.evaluation.EvaluationResponseParser;
import com.walpro.autobuilder.agent.shared.LLMAgentProtocolInvoker;
import com.walpro.autobuilder.agent.shared.LLMResponseResult;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class EvaluationLLMAdapter {

    private final EvaluationResponseParser parser = new EvaluationResponseParser();

    private final LLMClient llmClient;
    private final LLMAgentProtocolInvoker protocolInvoker;

    @Value("${autobuilder.evaluation.fileContentMaxLines:150}")
    private int fileContentMaxLines;

    public ParsedEvaluation evaluate(CycleContextData context) {
        String systemPrompt = EvaluationProtocolContract.getSystemPrompt();
        String userPrompt = EvaluationProtocolContract.getUserPrompt(context, fileContentMaxLines);

        LLMResponseResult<EvaluationOutcome> result = protocolInvoker.callWithValidation(
                llmClient,
                systemPrompt,
                userPrompt,
                EvaluationProtocolContract::isValidResponse,
                parser::parse
        );

        if (result.success()) {
            var assessments = parser.parseCriteriaAssessments(result.rawContent());
            return new ParsedEvaluation(result.outcome(), assessments);
        }

        log.warn("EvaluationAgent received invalid LLM response. Reason: {}. Content:\n{}",
                result.failureReason(),
                result.rawContent() != null ? result.rawContent() : "[null]"
        );

        return new ParsedEvaluation(
                new EvaluationOutcome(
                        null,
                        MutationAcceptanceState.REJECTED,
                        GoalProgressState.NOT_EVALUATED,
                        "LLM response did not follow evaluation protocol."
                                + (result.rawContent() != null ? "\n" + result.rawContent() : ""),
                        null
                ),
                java.util.List.of()
        );
    }
}