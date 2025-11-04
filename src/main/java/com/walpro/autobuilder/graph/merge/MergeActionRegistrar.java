package com.walpro.autobuilder.graph.merge;

import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.agent.merge.model.MergeOutcome;
import com.walpro.autobuilder.graph.core.ActionEntity;
import com.walpro.autobuilder.graph.core.ArtifactEntity;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class MergeActionRegistrar {

    private final AgentGraphService agentGraphService;

    public void registerMerge(String agentId, CycleContextData context, MergeOutcome outcome) {
        String actionId = UUID.randomUUID().toString();

        ActionEntity action = new ActionEntity(
            actionId,
            "mergeCompletedBranch",
            "MERGE",
            agentId,
            context.currentGoalId(),
            context.cycleName(),
            Instant.now()
        );
        agentGraphService.registerAction(action);
        
        if (outcome == null) {
    		return;
    	}

        String message = outcome.mergedContent(); 
        		
        message += outcome.reasoning() != null && !outcome.reasoning().isBlank()
            ? "\n\nReasoning:\n" + outcome.reasoning()
            : "";
        
        ArtifactEntity artifact = new ArtifactEntity(
            UUID.randomUUID().toString(),
            "merge_summary",
            null,
            message,
            null
        );

        agentGraphService.linkActionOutput(actionId, artifact);
    }
}
