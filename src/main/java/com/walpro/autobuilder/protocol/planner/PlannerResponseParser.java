package com.walpro.autobuilder.protocol.planner;

import java.util.ArrayList;
import java.util.List;

import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;

public class  PlannerResponseParser {

    public PlannerOutcome parse(String content) {
        boolean updated = false;
        List<String> newGoals = new ArrayList<>();
        StringBuilder messageBuilder = new StringBuilder();

        boolean inGoalsList = false;

        for (String line : content.lines().toList()) {
            String trimmed = line.trim();

            if (trimmed.startsWith("#SubGoalAppend") || trimmed.startsWith("#SubGoalUpdate")) {
                updated = true;
                inGoalsList = true;
            } else if (trimmed.startsWith("#SubGoalRemove")) {
                updated = true;
                inGoalsList = false; // We ignore remove for now
            } else if (inGoalsList && trimmed.startsWith("-")) {
                String goal = trimmed.substring(1).trim();
                if (!goal.isEmpty()) {
                    newGoals.add(goal);
                }
            } else if (trimmed.startsWith("Message:")) {
                String extracted = trimmed.substring("Message:".length()).trim();
                if (!messageBuilder.isEmpty()) {
                    messageBuilder.append('\n');
                }
                messageBuilder.append(extracted);
                inGoalsList = false;
            }
        }

        String msg = messageBuilder.toString();
        return new PlannerOutcome(updated, newGoals, msg.isEmpty() ? null : msg);
    }
}
