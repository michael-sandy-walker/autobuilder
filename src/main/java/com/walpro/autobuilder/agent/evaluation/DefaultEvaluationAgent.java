package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.agent.evaluation.graph.EvaluationGraphFacade;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationInput;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.protocol.EvaluationProtocolContract;
import com.walpro.autobuilder.agent.evaluation.protocol.EvaluationResponseParser;
import com.walpro.autobuilder.agent.shared.LLMAgentProtocolInvoker;
import com.walpro.autobuilder.agent.shared.LLMResponseResult;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.evaluation.model.GoalProgressState;
import com.walpro.autobuilder.evaluation.model.MutationAcceptanceState;
import com.walpro.autobuilder.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultEvaluationAgent implements EvaluationAgent {

    private final EvaluationResponseParser parser = new EvaluationResponseParser();

    private final EvaluationGraphFacade graphFacade;
    private final LLMClient llmClient;
    private final FileService fileService;

    private static final int MAX_ATTEMPTS = 2;
    private static final String INVALID_RESPONSE_MESSAGE = "LLM response did not follow evaluation protocol.";

//    public DefaultEvaluationAgent(EvaluationGraphFacade graphFacade, LLMClient llmClient) {
//        this.graphFacade = graphFacade;
//        this.llmClient = llmClient;
//    }

    @Override
    public EvaluationOutcome evaluate(EvaluationInput input) {
        String systemPrompt = EvaluationProtocolContract.getSystemPrompt();
        String userPrompt = EvaluationProtocolContract.getUserPrompt(input);

        LLMResponseResult<EvaluationOutcome> result = LLMAgentProtocolInvoker.callWithValidation(
                llmClient,
                systemPrompt,
                userPrompt,
                EvaluationProtocolContract::isValidResponse,
                parser::parse
        );

        if (result.success()) {

            EvaluationOutcome outcome = result.outcome();

//            List<String> missingFiles = input.deletedFiles().stream()
//                    .map(path -> "output/" + path)
//                    .filter(path -> {
//                        try {
//                            return !fileService.exists(Path.of(path));
//                        } catch (IOException e) {
//                            throw new RuntimeException(e);
//                        }
//                    })
//                    .toList();

            double score = outcome.score();
            double last = input.lastScore() != null ? input.lastScore() : -1.0;
            double delta = score - last;

            boolean suspiciousScore = score == 1.0 || delta < 0.01;

            if (suspiciousScore && input.updatedFiles().isEmpty() && input.deletedFiles().isEmpty()) {
                log.warn("⚠️ Suspicious evaluation: score %.2f with no meaningful change — downgrading.".formatted(score));
                outcome = new EvaluationOutcome(
                        0.4,
                        MutationAcceptanceState.REJECTED,
                        GoalProgressState.STAGNATING,
                        "Score adjusted due to no significant mutation impact.",
                        outcome.recommendation()
                );
            }

            log.info("""
            🔍 Evaluation Summary:
              Goal        : %s
              Score       : %.2f
              Last Score  : %s
              Δ Score     : %.2f
              State       : %s
              Progress    : %s
              Feedback    : %s
            """.formatted(
                    input.currentGoal(),
                    outcome.score(),
                    input.lastScore() != null ? "%.2f".formatted(input.lastScore()) : "(n/a)",
                    delta,
                    outcome.mutationState(),
                    outcome.goalProgress(),
                    outcome.feedbackMessage()
            ));

            return result.outcome();
        } else {
            log.warn("EvaluationAgent received invalid LLM response. Reason: {}. Content:\n{}",
                    result.failureReason(),
                    result.rawContent() != null ? result.rawContent() : "[null]"
            );

            return new EvaluationOutcome(
                    -1.0,
                    MutationAcceptanceState.REJECTED,
                    GoalProgressState.NOT_EVALUATED,
                    INVALID_RESPONSE_MESSAGE + (result.rawContent() != null ? "\n" + result.rawContent() : ""),
                    null
            );
        }
    }


    @Override
    public void evolveGraph(EvaluationInput input, EvaluationOutcome outcome) {
        // TODO: Implement graph mutation logic (e.g., add outcome to node)
        graphFacade.recordEvaluation(input, outcome);
    }
}
