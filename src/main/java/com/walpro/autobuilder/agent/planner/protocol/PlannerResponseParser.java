package com.walpro.autobuilder.agent.planner.protocol;

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
                newGoals.add(trimmed.substring(1).trim());
            } else if (trimmed.startsWith("Message:")) {
                messageBuilder.append(trimmed.substring("Message:".length()).trim());
                inGoalsList = false;
            }
        }

        return new PlannerOutcome(updated, newGoals, messageBuilder.toString().trim());
    }
}
