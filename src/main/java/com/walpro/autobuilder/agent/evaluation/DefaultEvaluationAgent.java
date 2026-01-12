package com.walpro.autobuilder.agent.evaluation;

import java.util.List;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.graph.evaluation.EvaluationActionRegistrar;
import com.walpro.autobuilder.graph.evaluation.EvaluationGraphFacade;
import com.walpro.autobuilder.analysis.heuristic.EvaluationHeuristic;
import com.walpro.autobuilder.analysis.heuristic.HeuristicNegotiator;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.protocol.evaluation.EvaluationProtocolContract;
import com.walpro.autobuilder.protocol.evaluation.EvaluationResponseParser;
import com.walpro.autobuilder.agent.shared.LLMAgentProtocolInvoker;
import com.walpro.autobuilder.agent.shared.LLMResponseResult;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
    private final EvaluationActionRegistrar evaluationActionRegistrar;
    
    private static final String AGENT_ID = "StaticEvaluationAgent";

    @PostConstruct
    private void test() {
        // no-op
    }

    @Override
    public EvaluationOutcome evaluate(CycleContextData context) {
        String systemPrompt = EvaluationProtocolContract.getSystemPrompt();
        String userPrompt = EvaluationProtocolContract.getUserPrompt(context);

        LLMResponseResult<EvaluationOutcome> result = protocolInvoker.callWithValidation(
                llmClient,
                systemPrompt,
                userPrompt,
                EvaluationProtocolContract::isValidResponse,
                parser::parse
        );

        if (result.success()) {
            EvaluationOutcome outcome = result.outcome();
            final EvaluationOutcome finalOutcome = heuristicNegotiator.negotiate(context, outcome, heuristics);

            heuristics.forEach(h -> {
                var opinion = h.evaluate(context, finalOutcome);
                if (opinion != null) {
                    log.info("Heuristic [{}] mutationState: {}, goalProgress: {}, rationale: {}, score={}",
                        opinion.name(),
                        opinion.mutationState(),
                        opinion.goalProgress(),
                        opinion.rationale(),
                        opinion.suggestedScore());
                }
            });

            double score = outcome.score();            
            Double last = context.evaluationInterpretation() != null
                    ? context.evaluationInterpretation().previous()
                    : null;
            Double delta = last == null ? null : score - last;
            // TODO: do something with these values

            log.info("""
                Evaluation Summary:
                  Goal              : %s
                  Score             : %.2f
                  Last Score        : %s
                  Δ Score           : %s
                  State             : %s
                  Progress          : %s
                  Feedback          : %s
                  Recommendation    : %s
                """.formatted(
                context.currentGoalDescription(),
                score,
                last == null ? "(n/a)" : last >= 0 ? "%.2f".formatted(last) : "(n/a)",
                delta,
                outcome.mutationState(),
                outcome.goalProgress(),
                outcome.feedbackMessage(),
                outcome.recommendation()
            ));
            
            evaluationActionRegistrar.registerEvaluation(AGENT_ID, context, finalOutcome);

            return finalOutcome;
        } else {
            log.warn("EvaluationAgent received invalid LLM response. Reason: {}. Content:\n{}",
                    result.failureReason(),
                    result.rawContent() != null ? result.rawContent() : "[null]"
            );

            return new EvaluationOutcome(
                    null,
                    MutationAcceptanceState.REJECTED,
                    GoalProgressState.NOT_EVALUATED,
                    "LLM response did not follow evaluation protocol." +
                            (result.rawContent() != null ? "\n" + result.rawContent() : ""),
                    null
            );
        }
    }

    @Override
    public void evolveGraph(CycleContextData context, EvaluationOutcome outcome) {
        graphFacade.recordEvaluation(context, outcome);
    }
}
