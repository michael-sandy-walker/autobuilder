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
import com.walpro.autobuilder.core.build.PackageConsolidationService;
import com.walpro.autobuilder.core.grounding.SymbolGroundingService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class EvolutionLLMAdapter {

    private final LLMClient llmClient;
    private final EvolutionGraphFacade graphFacade;
    private final LLMAgentProtocolInvoker llmAgentProtocolInvoker;
    private final EvolutionActionRegistrar evolutionActionRegistrar;
    private final SymbolGroundingService groundingService;
    private final PackageConsolidationService packageConsolidationService;

    private final EvolutionResponseParser parser = new EvolutionResponseParser(); // not injected

    public CycleContextData evolve(CycleContextData context) throws IOException {
        return evolve(context, false);
    }

    public CycleContextData evolve(CycleContextData context, boolean recombined) throws IOException {
        // T1-EM: re-ground [UNDEFINED]/[NOT YET PRESENT] markers in goal descriptions against
        // current output state before rendering — same pattern as PlannerLLMAdapter /
        // AnalysisLLMAdapter. Without this, the evolution prompt can carry stale markers and
        // encourage re-creating files that already exist on disk.
        CycleContextData refreshed = groundingService.refreshGoalAnnotations(context);
        var result = refreshed.evaluationInterpretation();
        String systemPrompt = EvolutionProtocolContract.getSystemPrompt();
        String userPrompt = EvolutionProtocolContract.getUserPrompt(refreshed, result, recombined);

        LLMResponseResult<EvolutionOutcome> response = llmAgentProtocolInvoker.callWithValidation(
                llmClient,
                systemPrompt,
                userPrompt,
                EvolutionProtocolContract::isValidResponse,
                parser::parse
        );

        EvolutionOutcome outcome;

        if (response.success()) {
            outcome = response.outcome().withRecombined(recombined); // TODO: let the LLM return this flag?

            // T4-BL: consolidate fragmenting writes to their type's existing package before
            // anything downstream (graph records, probe, merge) sees the outcome.
            outcome = packageConsolidationService.consolidate(
                    outcome, refreshed.outputPath(), refreshed.cycleName());

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
                    response.rawContent(),
                    recombined
            );
        }

        return context
                .withEvolutionOutcome(outcome);
    }

    public CycleContextData evolveDivergent(CycleContextData ctx) {
        try {
            return evolve(ctx, true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void evolveGraph(CycleContextData context, EvolutionOutcome outcome) {
        graphFacade.recordEvolution(context, outcome);
    }

}
