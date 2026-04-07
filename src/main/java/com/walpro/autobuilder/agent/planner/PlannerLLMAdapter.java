package com.walpro.autobuilder.agent.planner;

import java.util.List;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.config.SystemCapabilityManifest;
import com.walpro.autobuilder.graph.planner.PlannerActionRegistrar;
import com.walpro.autobuilder.graph.planner.PlannerGraphFacade;
import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.protocol.planner.PlannerProtocolContract;
import com.walpro.autobuilder.protocol.planner.PlannerResponseParser;
import com.walpro.autobuilder.agent.shared.LLMAgentProtocolInvoker;
import com.walpro.autobuilder.agent.shared.LLMResponseResult;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerLLMAdapter {

    private final PlannerGraphFacade graphFacade;
    private final LLMClient llmClient;
    private final LLMAgentProtocolInvoker llmAgentProtocolInvoker;
    private final PlannerActionRegistrar plannerActionRegistrar;
    private final SystemCapabilityManifest capabilityManifest;

    private final PlannerResponseParser parser = new PlannerResponseParser(); // not injected

    public PlannerOutcome plan(CycleContextData input) {
        String systemPrompt = PlannerProtocolContract.getSystemPrompt()
                + "\n\n" + capabilityManifest.render();
        String userPrompt = PlannerProtocolContract.getUserPrompt(input);

        LLMResponseResult<PlannerOutcome> result = llmAgentProtocolInvoker.callWithValidation(
                llmClient,
                systemPrompt,
                userPrompt,
                PlannerProtocolContract::isValidResponse,
                parser::parse
        );

        if (result.success()) {
            return result.outcome();
        } else {
            log.warn("PlannerAgent received invalid LLM response. Reason: {}. Content:\n{}",
                    result.failureReason(),
                    result.rawContent() != null ? result.rawContent() : "[null]"
            );

            return new PlannerOutcome(false, List.of(), List.of(), "Invalid response: " + result.failureReason(), null);
        }
    }

    public void evolveGraph(CycleContextData input, PlannerOutcome outcome) {
        plannerActionRegistrar.registerPlannerAction("PlannerAlpha", input, outcome);

        graphFacade.recordPlanning(input, outcome);
    }

}
