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

    /**
     * T2-FI: a per-file CHARACTER budget for the evaluation prompt, replacing a 150-line cap that
     * was in practice a 4000-character one. Sized against measured fact rather than taste: the four
     * targets' entire Java sources are 8.3k, 15.8k, 8.1k and 7.3k characters, and this applies per
     * CHANGED file, so 40000 leaves every realistic file whole while still bounding a pathological
     * one. The old value cropped `Main.java` -- 7kB -- to roughly its first hundred lines before
     * asking the model to score the mutation.
     */
    @Value("${autobuilder.evaluation.fileContentBudgetChars:40000}")
    private int fileContentMaxLines;

    /** Optional: absent, the evaluator reads source only, as before. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @lombok.Setter
    private EvaluationTestEvidence testEvidence;

    public ParsedEvaluation evaluate(CycleContextData context) {
        String systemPrompt = EvaluationProtocolContract.getSystemPrompt();
        String userPrompt = EvaluationProtocolContract.getUserPrompt(context, fileContentMaxLines);
        if (testEvidence != null) {
            String evidence = testEvidence.block(context).orElse(null);
            if (evidence != null) userPrompt = userPrompt + "\n\n" + evidence;
        }

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