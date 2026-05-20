package com.walpro.autobuilder.agent.meta;

import java.util.ArrayList;
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
import com.walpro.autobuilder.graph.core.OrchestrationSnapshot;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshotService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import com.walpro.autobuilder.protocol.meta.MetaActionStep;
import com.walpro.autobuilder.protocol.meta.MetaDeliberationPlan;
import com.walpro.autobuilder.protocol.meta.MetaDeliberationPromptBuilder;
import com.walpro.autobuilder.protocol.meta.MetaDeliberationProtocolContract;
import com.walpro.autobuilder.protocol.meta.MetaDeliberationResponseParser;

import com.walpro.autobuilder.protocol.meta.OrchestrationLedgerRenderer;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    /** T2-DF: optional behaviour trace logger. Setter so the @Bean ctor does not need a new arg. */
    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    public MetaDeliberationPlan decide(CycleContextData ctx, DeliberationMode currentMode) {
        log.info("Executing agent (role={})", "meta-orchestration");

        // T2-DF: compute eligible roles once before the LLM call — injected into prompt and used
        // for post-LLM filtering so the two stay in sync with a single eligibility computation.
        Set<String> eligibleRoles = computeEligibleRoles(ctx, currentMode);
        String eligibilityBlock = buildEligibilityBlock(ctx, currentMode, eligibleRoles);
        String learningsBlock = buildMetaLearningsBlock();

        String modeLine = currentMode == null ? "(unknown)" : currentMode.name();
        Set<String> allowed = currentMode == null ? Set.of() : deliberationGate.allowedRoles(currentMode);
        String allowedLine = allowed.isEmpty()
                ? "(none)"
                : allowed.stream().sorted().collect(Collectors.joining(", "));

        // If the in-memory lastAttemptId is absent (fresh start / restart before any evolution),
        // fall back to the most recent attempt recorded in the graph so the ledger is non-empty.
        OrchestrationSnapshot snapshot;
        if (ctx.lastAttemptId() != null && !ctx.lastAttemptId().isBlank()) {
            snapshot = snapshotService.fetchSnapshot(
                    ctx.currentGoalId(), ctx.currentSubGoalId(), ctx.lastAttemptId(), 5);
        } else {
            snapshot = snapshotService.fetchLatestSnapshot(
                    ctx.currentGoalId(), ctx.currentSubGoalId(), 5);
        }

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
                    DeliberationMode.UNDIRECTED,
                    "Meta deliberation failed: " + result.failureReason(),
                    List.of(),
                    result.rawContent()
            );
        }

        // T2-DF: post-LLM validation — drop any steps that target roles ineligible this cycle.
        return filterIneligibleSteps(result.outcome(), eligibleRoles, ctx);
    }

    private Set<String> computeEligibleRoles(CycleContextData ctx, DeliberationMode mode) {
        if (executionAgents == null || executionAgents.isEmpty()) return Set.of();
        Set<String> modeAllowed = mode == null ? Set.of() : deliberationGate.allowedRoles(mode);
        return executionAgents.stream()
                .filter(a -> modeAllowed.isEmpty() || modeAllowed.contains(a.role().toLowerCase()))
                .filter(a -> eligibilityExplainer.canExecute(a.role(), ctx))
                .map(a -> a.role().toLowerCase())
                .collect(Collectors.toSet());
    }

    private String buildEligibilityBlock(CycleContextData ctx, DeliberationMode mode, Set<String> eligibleRoles) {
        if (executionAgents == null || executionAgents.isEmpty()) return "(no executionAgents wired)";

        Set<String> modeAllowed = mode == null ? Set.of() : deliberationGate.allowedRoles(mode);

        List<String> eligibleDisplay = new ArrayList<>();
        List<String> blockedDisplay = new ArrayList<>();
        List<String> details = new ArrayList<>();

        executionAgents.stream()
                .sorted(Comparator.comparing(ExecutionAgent::role))
                .filter(a -> modeAllowed.isEmpty() || modeAllowed.contains(a.role().toLowerCase()))
                .forEach(a -> {
                    String role = a.role();
                    if (eligibleRoles.contains(role.toLowerCase())) {
                        eligibleDisplay.add(role.toUpperCase());
                        details.add(role.toUpperCase() + ": canExecute=true");
                    } else {
                        // Only call explainBlockers for blocked roles — eligible set already computed.
                        List<String> blockersList = eligibilityExplainer.explainBlockers(role, ctx);
                        String blockers = blockersList.isEmpty() ? "unknown" : String.join("; ", blockersList);
                        String firstBlocker = blockersList.isEmpty() ? "unknown" : blockersList.get(0);
                        blockedDisplay.add(role.toUpperCase() + " (" + firstBlocker + ")");
                        details.add(role.toUpperCase() + ": canExecute=false; blockers=" + blockers);
                    }
                });

        String eligibleLine = eligibleDisplay.isEmpty() ? "(none)" : String.join(", ", eligibleDisplay);
        String blockedLine = blockedDisplay.isEmpty() ? "(none)" : String.join(", ", blockedDisplay);

        return "Only these roles can execute this cycle: " + eligibleLine + "\n"
                + "Blocked (do NOT propose steps for these): " + blockedLine + "\n"
                + "You MUST NOT target blocked roles in your plan.\n\n"
                + String.join("\n", details);
    }

    private MetaDeliberationPlan filterIneligibleSteps(MetaDeliberationPlan plan,
                                                        Set<String> eligibleRoles,
                                                        CycleContextData ctx) {
        if (plan == null || !plan.hasSteps()) return plan;

        List<MetaActionStep> filtered = new ArrayList<>();
        for (MetaActionStep step : plan.steps()) {
            String role = step.targetRole() == null ? "" : step.targetRole().trim().toLowerCase();
            if (eligibleRoles.contains(role)) {
                filtered.add(step);
            } else {
                log.warn("Meta plan step {} filtered: role={} is not eligible this cycle (eligible={})",
                        step.index(), step.targetRole(), eligibleRoles);
                if (behaviourLogger != null) {
                    behaviourLogger.metaStepFiltered(ctx.cycleName(), step.index(), step.targetRole(),
                            "role_not_eligible");
                }
            }
        }
        if (filtered.size() == plan.steps().size()) return plan;
        return new MetaDeliberationPlan(plan.stop(), plan.mode(), plan.rationale(), filtered, plan.rawResponse());
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
