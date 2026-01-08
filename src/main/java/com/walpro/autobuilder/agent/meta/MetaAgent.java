package com.walpro.autobuilder.agent.meta;

import com.walpro.autobuilder.analysis.exhaustion.AgentExhaustionDetector;
import com.walpro.autobuilder.analysis.exhaustion.ExhaustionReport;
import com.walpro.autobuilder.analysis.exhaustion.GoalExhaustionDetector;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.protocol.meta.MetaSpawnAgentNegotiationRecord;
import com.walpro.autobuilder.protocol.meta.MetaSpawnAgentPromptBuilder.MetaPromptMode;
import com.walpro.autobuilder.protocol.meta.MetaSpawnAgentProtocolContract;
import com.walpro.autobuilder.protocol.meta.MetaSpawnAgentResponseParser;
import com.walpro.autobuilder.protocol.meta.MetaSpawnAgentResponseParser.AgentSpawnProposal;
import com.walpro.autobuilder.agent.shared.LLMAgentProtocolInvoker;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.graph.core.AgentEntity;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetaAgent {

    private static final int PRUNE_LIMIT_PER_CYCLE = 3; // conservative start

    private final LLMClient llmClient;
    private final MetaAgentManager metaAgentManager;
    private final MetaSpawnAgentResponseParser responseParser = new MetaSpawnAgentResponseParser(); // Stateless
    private final LLMAgentProtocolInvoker llmAgentProtocolInvoker;
    private final AgentGraphService agentGraphService;
    private final GoalExhaustionDetector goalExhaustionDetector;
    private final AgentExhaustionDetector agentExhaustionDetector;

    public CycleContextData analyzeAndEvolve(CycleContextData context) {
        String systemPrompt = MetaSpawnAgentProtocolContract.getSystemPrompt();
        
        List<AgentEntity> staticAgents = List.of(
        		new AgentEntity("static-1", "DefaultEvaluationAgent", "DefaultEvaluationAgent", "evaluation", "bootstrap", "active"),
        		new AgentEntity("static-2", "DefaultPlannerAgent", "DefaultPlannerAgent", "planner", "bootstrap", "active"),
        		new AgentEntity("static-3", "DefaultMergeAgent", "DefaultMergeAgent", "merge", "bootstrap", "active"),
        		new AgentEntity("static-4", "DefaultEvolutionAgent", "DefaultEvolutionAgent", "evolution", "bootstrap", "active")
        		);

        List<AgentEntity> agents = new ArrayList<>(staticAgents);

        agents.addAll(agentGraphService.getAllAgents());

        ExhaustionReport goalReport = goalExhaustionDetector.detect(context);
        if (goalReport.exhausted()) {
            log.warn("Goal exhausted (confidence={}): {}", goalReport.confidence(), goalReport.rationale());

            context = context.withSignal(
                    Signal.of(BasicSignals.GOAL_EXHAUSTED, goalReport.confidence(), 0.05, "MetaAgent")
            );

            // Optional but very useful: record as a finding on the context if we have such a mechanism
            // context = context.withFinding(...)

            // Optional: persist to goal graph metadata if you have GoalGraphService
            // goalGraphService.annotateGoalExhaustion(context.currentGoalId(), goalReport);

            // IMPORTANT: do not spawn new agents when the goal is exhausted.
            // Either return immediately OR switch to a special prompt mode (see section D).
            return context;
        }

        MetaPromptMode mode = context.lastMetaProposal() != null
            ? MetaPromptMode.CONFLICT_RESOLUTION
            : MetaPromptMode.INITIAL;
        
        List<AgentEntity> idleAgents = agentGraphService.getActiveUnassignedAgents();
        for (AgentEntity idle : idleAgents) {
            log.info("💤 Deactivating idle agent: {}", idle.name());
            agentGraphService.updateAgentStatus(idle.id(), "inactive");
        }

        long pruned = agentGraphService.pruneInactiveUnassignedAgents(PRUNE_LIMIT_PER_CYCLE);
        if (pruned > 0) {
            log.info("🧹 Pruned {} inactive unassigned agents (limit={}).", pruned, PRUNE_LIMIT_PER_CYCLE);
        }

        // NEW: detect saturation/churn and publish a signal
        ExhaustionReport agentReport = agentExhaustionDetector.detect(context);
        if (agentReport.exhausted()) {
            log.warn("Agent exhaustion detected (confidence={}): {}", agentReport.confidence(), agentReport.rationale());

            context = context.withSignal(
                    Signal.of(BasicSignals.AGENT_EXHAUSTED, agentReport.confidence(), 0.05, "MetaAgent-" + context.cycleName())
            );

            // Hard policy (recommended): stop spawning when exhausted.
            // If you prefer “soft policy”, remove this return and keep going.
            return context;
        }


        String userPrompt = MetaSpawnAgentProtocolContract.getUserPrompt(context, mode, agents);


        var result = llmAgentProtocolInvoker.callWithValidation(
            llmClient,
            systemPrompt,
            userPrompt,
            MetaSpawnAgentProtocolContract::isValidResponse,
            responseParser::parse
        );
        
        if (result.success()) {
            AgentSpawnProposal proposal = result.outcome();

            // Check for name conflict
            boolean nameTaken = agentGraphService.getAllAgents()
                .stream()
                .anyMatch(a -> a.name().equalsIgnoreCase(proposal.name()));

            if (nameTaken) {
                context = context.withLastMetaProposal(new MetaSpawnAgentNegotiationRecord(
                    proposal.name(),
                    proposal.roleType(),
                    proposal.goalId(),
                    //"Agent with this name already exists. Consider using 'rename' or 'replace'." // let's not rename/replace agents, because that agent would be destructive
                    "Agent with this name already exists. You may spawn an alternative agent with a distinct name or different role."
                ));
                return context;
            }
            
            boolean alreadyExists = proposal.goalId()
            	    .map(goalId -> agentGraphService.getAgentsByRoleAndGoal("evolution", goalId).stream()
            	        .anyMatch(a -> a.status().equalsIgnoreCase("active")))
            	    .orElse(false);

            if (alreadyExists) {
            	log.warn("⚠️ Skipping spawn: active evolution agent already assigned to goal {}", proposal.goalId());
            	return context;
            }

            // Proceed with spawn
            var agent = metaAgentManager.spawnAgent(
                "MetaAgent",
                proposal.name(),
                proposal.roleType(),
                context.cycleName()
            );

            proposal.goalId().ifPresent(goalId ->
                metaAgentManager.assignToGoal(agent.id(), goalId)
            );

            metaAgentManager.linkSpawn("MetaAgentRoot", agent.id());
        } else {
        	log.warn("⚠️ MetaAgent skipped: {}", result.failureReason());
        }

        return context;
    }
}
