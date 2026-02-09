package com.walpro.autobuilder.agent.meta;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.walpro.autobuilder.agent.shared.LLMAgentProtocolInvoker;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.core.deliberation.DeliberationMode;
import com.walpro.autobuilder.core.engine.DeliberationExecutionGate;
import com.walpro.autobuilder.core.engine.ExecutionAgent;
import com.walpro.autobuilder.core.engine.ExecutionEligibilityExplainer;
import com.walpro.autobuilder.core.engine.meta.MetaOverrideLearningStore;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshotService;
import com.walpro.autobuilder.protocol.meta.MetaDeliberationPlan;
import com.walpro.autobuilder.protocol.meta.MetaDeliberationPromptBuilder;
import com.walpro.autobuilder.protocol.meta.MetaDeliberationProtocolContract;
import com.walpro.autobuilder.protocol.meta.MetaDeliberationResponseParser;

import com.walpro.autobuilder.protocol.meta.OrchestrationLedgerRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetaDeliberationAgent {

    private final LLMClient llmClient;
    private final LLMAgentProtocolInvoker invoker;
    private final DeliberationExecutionGate deliberationGate;

    private final List<ExecutionAgent> executionAgents;
    private final ExecutionEligibilityExplainer eligibilityExplainer;

    private final MetaDeliberationResponseParser parser = new MetaDeliberationResponseParser();

    private final MetaOverrideLearningStore learningStore;

    private final OrchestrationSnapshotService snapshotService;

    public MetaDeliberationPlan decide(CycleContextData ctx, DeliberationMode currentMode) {
        log.info("Executing agent (role={})", "meta-orchestration");

        String eligibilityBlock = buildEligibilityBlock(ctx);
        String learningsBlock = buildMetaLearningsBlock();

        String modeLine = currentMode == null ? "(unknown)" : currentMode.name();
        Set<String> allowed = currentMode == null ? Set.of() : deliberationGate.allowedRoles(currentMode);
        String allowedLine = allowed.isEmpty()
                ? "(none)"
                : allowed.stream().sorted().collect(Collectors.joining(", "));

        var snapshot = snapshotService.fetchSnapshot(
                ctx.currentGoalId(),
                ctx.currentSubGoalId(),
                ctx.lastAttemptId(),
                5
        );

        String ledgerBlock = OrchestrationLedgerRenderer.render(snapshot);

        String systemPrompt = MetaDeliberationProtocolContract.getSystemPrompt();
        String userPrompt = MetaDeliberationPromptBuilder.getUserPrompt(
                ctx,
                "DeliberationMode: " + modeLine + "\nAllowedRolesForMode: " + allowedLine + "\n\n" + eligibilityBlock,
                learningsBlock,
                ledgerBlock
        );

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
                    DeliberationMode.NOOP,
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
                    boolean can = eligibilityExplainer.canExecute(role, ctx);

                    if (can) {
                        return role.toUpperCase() + ": canExecute=true";
                    }

                    String blockers = eligibilityExplainer.explainBlockers(role, ctx).stream()
                            .collect(Collectors.joining("; "));

                    return role.toUpperCase() + ": canExecute=false; blockers=" + blockers;
                })
                .collect(Collectors.joining("\n"));
    }

    private String buildMetaLearningsBlock() {
        String learningsBlock = learningStore.top(10).stream()
                .map(e -> {
                    var k = e.getKey();
                    var v = e.getValue();
                    return "- mode=%s role=%s reason=%s count=%d lastSeen=%s"
                            .formatted(k.mode(), k.role(), k.reasonCode(), v.count(), v.lastSeen());
                })
                .collect(java.util.stream.Collectors.joining("\n"));
        return learningsBlock.isBlank() ? "(none)" : learningsBlock;
    }
}
