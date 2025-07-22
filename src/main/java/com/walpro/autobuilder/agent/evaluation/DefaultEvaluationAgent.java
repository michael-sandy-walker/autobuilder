package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.agent.evaluation.graph.EvaluationGraphFacade;
import com.walpro.autobuilder.agent.evaluation.heuristic.EvaluationHeuristic;
import com.walpro.autobuilder.agent.evaluation.heuristic.HeuristicNegotiator;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationInput;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.protocol.EvaluationProtocolContract;
import com.walpro.autobuilder.agent.evaluation.protocol.EvaluationResponseParser;
import com.walpro.autobuilder.agent.shared.LLMAgentProtocolInvoker;
import com.walpro.autobuilder.agent.shared.LLMResponseResult;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultEvaluationAgent implements EvaluationAgent {

    private final EvaluationResponseParser parser = new EvaluationResponseParser();

    private final EvaluationGraphFacade graphFacade;
    private final LLMClient llmClient;
    private final LLMAgentProtocolInvoker protocolInvoker;
    private final HeuristicNegotiator heuristicNegotiator;
    private final List<EvaluationHeuristic> heuristics;

//    private static final int MAX_ATTEMPTS = 2;
    private static final String INVALID_RESPONSE_MESSAGE = "LLM response did not follow evaluation protocol.";
    
    @PostConstruct
    public void test() {
    	
    }
    
    @Override
    public EvaluationOutcome evaluate(EvaluationInput input) {
        String systemPrompt = EvaluationProtocolContract.getSystemPrompt();
        String userPrompt = EvaluationProtocolContract.getUserPrompt(input);

        LLMResponseResult<EvaluationOutcome> result = protocolInvoker.callWithValidation(
                llmClient,
                systemPrompt,
                userPrompt,
                EvaluationProtocolContract::isValidResponse,
                parser::parse
        );

        if (result.success()) {

            EvaluationOutcome outcome = result.outcome();

            final EvaluationOutcome finalOutcome = heuristicNegotiator.negotiate(input, outcome, heuristics);
            
            heuristics.forEach(h -> {
                var opinion = h.evaluate(input, finalOutcome);
                if (opinion != null) {
                    log.info("🧠 Heuristic [{}] mutationState: {}, goalProgress: {}, rationale: {}, score={}", 
                        opinion.name(), 
                        opinion.mutationState(),
                        opinion.goalProgress(),
                        opinion.rationale()
,                        opinion.suggestedScore());
                }
            });
            
            outcome = finalOutcome;

            double score = outcome.score();
            double last = input.lastScore() != null ? input.lastScore() : -1.0;
            double delta = score - last;

            log.info("""
            🔍 Evaluation Summary:
              Goal        		: %s
              Score       		: %.2f
              Last Score  		: %s
              Δ Score     		: %.2f
              State       		: %s
              Progress    		: %s
              Feedback    		: %s
              Recommendation	: %s
            """.formatted(
                    input.currentGoal(),
                    outcome.score(),
                    input.lastScore() != null ? "%.2f".formatted(input.lastScore()) : "(n/a)",
                    delta,
                    outcome.mutationState(),
                    outcome.goalProgress(),
                    outcome.feedbackMessage(),
                    outcome.recommendation()
            ));

            return outcome;
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
