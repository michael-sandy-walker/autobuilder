package com.walpro.autobuilder.protocol.meta;

import com.walpro.autobuilder.graph.core.AgentEntity;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import java.util.List;

public class MetaSpawnAgentPromptBuilder {

    public enum MetaPromptMode {
        INITIAL, CONFLICT_RESOLUTION
    }

    public static String buildUserPrompt(
            CycleContextData context,
            MetaPromptMode mode,
            List<AgentEntity> existingAgents
    ) {
        StringBuilder sb = new StringBuilder();

        sb.append("The system is preparing to evaluate whether a new agent should be spawned.\n\n");

        sb.append("- Current cycle: ").append(context.cycleName()).append("\n");
        sb.append("- Active goal: ").append(context.currentGoalDescription()).append("\n");

        sb.append("\n## Existing agents:\n");
        if (existingAgents.isEmpty()) {
            sb.append("- (none)\n");
        } else {
            for (AgentEntity agent : existingAgents) {
                sb.append("- ").append(agent.name())
                  .append(" (").append(agent.roleType()).append(")\n");
            }
        }

        if (mode == MetaPromptMode.CONFLICT_RESOLUTION && context.lastMetaProposal() != null) {
            MetaSpawnAgentNegotiationRecord last = context.lastMetaProposal();
            sb.append("\n## Previous proposal:\n")
              .append("```yaml\n")
              .append("name: ").append(last.name()).append("\n")
              .append("roleType: ").append(last.roleType()).append("\n");
            last.goalId().ifPresent(id -> sb.append("goalId: ").append(id).append("\n"));
            sb.append("```\n");

            sb.append("System feedback: ").append(last.rejectionReason()).append("\n");
            sb.append("You may:\n")
            .append("- Propose an alternative agent with a distinct role\n")
            .append("- Refine your rationale or adjust the goal linkage\n");
        }

        sb.append("\n## Respond in YAML format:\n")
          .append("```yaml\n")
          .append("name: <AgentName>\n")
          .append("roleType: <evaluation|evolution|planner|merge|meta>\n")
          .append("goalId: <optional>\n");
        sb.append("```\n");

        return sb.toString();
    }
}
