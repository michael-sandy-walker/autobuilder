package com.walpro.autobuilder.agent.meta;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import com.walpro.autobuilder.agent.shared.LLMAgentProtocolInvoker;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.core.engine.ExecutionAgent;
import com.walpro.autobuilder.core.engine.ExecutionEligibilityExplainer;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.protocol.meta.MetaDeliberationPlan;
import com.walpro.autobuilder.protocol.meta.MetaDeliberationPromptBuilder;
import com.walpro.autobuilder.protocol.meta.MetaDeliberationProtocolContract;
import com.walpro.autobuilder.protocol.meta.MetaDeliberationResponseParser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetaDeliberationAgent {

    private final LLMClient llmClient;
    private final LLMAgentProtocolInvoker invoker;

    private final List<ExecutionAgent> executionAgents;
    private final ExecutionEligibilityExplainer eligibilityExplainer;

    private final MetaDeliberationResponseParser parser = new MetaDeliberationResponseParser();

    public MetaDeliberationPlan decide(CycleContextData ctx) {
        String eligibilityBlock = buildEligibilityBlock(ctx);

        String systemPrompt = MetaDeliberationProtocolContract.getSystemPrompt();
        String userPrompt = MetaDeliberationPromptBuilder.getUserPrompt(ctx, eligibilityBlock);

        var result = invoker.callWithValidation(
                llmClient,
                systemPrompt,
                userPrompt,
                MetaDeliberationProtocolContract::isValidResponse,
                parser::parse
        );

        if (!result.success()) {
            log.warn("MetaDeliberation failed: {}", result.failureReason());
            return new MetaDeliberationPlan(
                    false,
                    MetaDeliberationPlan.Mode.NOOP,
                    "Meta deliberation failed: " + result.failureReason(),
                    List.of(),
                    result.rawContent()
            );
        }

        return result.outcome();
    }

    private String buildEligibilityBlock(CycleContextData ctx) {
        if (executionAgents == null || executionAgents.isEmpty()) return "(no executionAgents wired)";

        return executionAgents.stream()
                .sorted(Comparator.comparing(ExecutionAgent::role))
                .map(a -> {
                    String role = a.role();
                    boolean can = a.canExecute(ctx);

                    if (can) {
                        return role.toUpperCase() + ": canExecute=true";
                    }

                    String blockers = eligibilityExplainer.explainBlockers(role, ctx).stream()
                            .collect(Collectors.joining("; "));

                    return role.toUpperCase() + ": canExecute=false; blockers=" + blockers;
                })
                .collect(Collectors.joining("\n"));
    }
}
