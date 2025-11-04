package com.walpro.autobuilder.agent.meta;

import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.protocol.meta.MetaNegotiationRecord;
import com.walpro.autobuilder.protocol.meta.MetaPromptBuilder.MetaPromptMode;
import com.walpro.autobuilder.protocol.meta.MetaProtocolContract;
import com.walpro.autobuilder.protocol.meta.MetaResponseParser;
import com.walpro.autobuilder.protocol.meta.MetaResponseParser.AgentSpawnProposal;
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

    private final LLMClient llmClient;
    private final MetaAgentManager metaAgentManager;
    private final MetaResponseParser responseParser = new MetaResponseParser(); // Stateless
    private final LLMAgentProtocolInvoker llmAgentProtocolInvoker;
    private final AgentGraphService agentGraphService;

    public void analyzeAndEvolve(CycleContextData context) {
        String systemPrompt = MetaProtocolContract.getSystemPrompt();
        
        List<AgentEntity> staticAgents = List.of(
        		new AgentEntity("static-1", "DefaultEvaluationAgent", "DefaultEvaluationAgent", "evaluation", "bootstrap", "active"),
        		new AgentEntity("static-2", "DefaultPlannerAgent", "DefaultPlannerAgent", "planner", "bootstrap", "active"),
        		new AgentEntity("static-3", "DefaultMergeAgent", "DefaultMergeAgent", "merge", "bootstrap", "active"),
        		new AgentEntity("static-4", "DefaultEvolutionAgent", "DefaultEvolutionAgent", "evolution", "bootstrap", "active")
        		);

        List<AgentEntity> agents = new ArrayList<>(staticAgents);

        agents.addAll(agentGraphService.getAllAgents());

        MetaPromptMode mode = context.lastMetaProposal() != null
            ? MetaPromptMode.CONFLICT_RESOLUTION
            : MetaPromptMode.INITIAL;
        
        List<AgentEntity> idleAgents = agentGraphService.getActiveUnassignedAgents();
        for (AgentEntity idle : idleAgents) {
            log.info("💤 Deactivating idle agent: {}", idle.name());
            agentGraphService.updateAgentStatus(idle.id(), "inactive");
        }

        String userPrompt = MetaProtocolContract.getUserPrompt(context, mode, agents);


        var result = llmAgentProtocolInvoker.callWithValidation(
            llmClient,
            systemPrompt,
            userPrompt,
            MetaProtocolContract::isValidResponse,
            responseParser::parse
        );

//        if (result.success()) {
//            AgentSpawnProposal proposal = result.outcome();
//            log.info("🧬 MetaAgent decision: spawn '{}'", proposal);
//
//            var agent = metaAgentManager.spawnAgent(
//                "MetaAgent",
//                proposal.name(),
//                proposal.roleType(),
//                context.cycleName()
//            );
//
//            proposal.goalId().ifPresent(goalId ->
//                metaAgentManager.assignToGoal(agent.id(), goalId)
//            );
//
//            metaAgentManager.linkSpawn("MetaAgentRoot", agent.id());
//            
//            if (agentGraphService.getAllAgents().stream().anyMatch(a -> a.name().equals(proposal.name()))) {
//                context = context.withLastMetaProposal(new MetaNegotiationRecord(
//                    proposal.name(),
//                    proposal.roleType(),
//                    proposal.goalId(),
//                    "Agent with this name already exists"
//                ));
//                return;
//            }
//        } else {
//            log.warn("⚠️ MetaAgent skipped: {}", result.failureReason());
//        }
        
        if (result.success()) {
            AgentSpawnProposal proposal = result.outcome();

            // Check for name conflict
            boolean nameTaken = agentGraphService.getAllAgents()
                .stream()
                .anyMatch(a -> a.name().equalsIgnoreCase(proposal.name()));

            if (nameTaken /*&& !"replace".equalsIgnoreCase(proposal.action())*/) {
                context = context.withLastMetaProposal(new MetaNegotiationRecord(
                    proposal.name(),
                    proposal.roleType(),
                    proposal.goalId(),
                    //"Agent with this name already exists. Consider using 'rename' or 'replace'." // let's not rename/replace agents, because that agent would be destructive
                    "Agent with this name already exists. You may spawn an alternative agent with a distinct name or different role."
                ));
                return;
            }
            
            boolean alreadyExists = proposal.goalId()
            	    .map(goalId -> agentGraphService.getAgentsByRoleAndGoal("evolution", goalId).stream()
            	        .anyMatch(a -> a.status().equalsIgnoreCase("active")))
            	    .orElse(false);

            if (alreadyExists) {
            	log.warn("⚠️ Skipping spawn: active evolution agent already assigned to goal {}", proposal.goalId());
            	return;
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
    }
}
