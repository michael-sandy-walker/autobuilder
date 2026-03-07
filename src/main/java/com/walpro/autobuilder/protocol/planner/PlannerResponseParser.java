package com.walpro.autobuilder.protocol.planner;

import java.util.ArrayList;
import java.util.List;

import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;

public class  PlannerResponseParser {

    public PlannerOutcome parse(String content) {
        boolean updated = false;
        List<String> newGoals = new ArrayList<>();
        List<String> goalsToRemove = new ArrayList<>();
        StringBuilder messageBuilder = new StringBuilder();

        boolean inGoalsList = false;
        boolean inRemoveList = false;

        for (String line : content.lines().toList()) {
            String trimmed = line.trim();

            if (trimmed.startsWith("#SubGoalAppend") || trimmed.startsWith("#SubGoalUpdate")) {
                updated = true;
                inGoalsList = true;
                inRemoveList = false;
            } else if (trimmed.startsWith("#SubGoalRemove")) {
                updated = true;
                inRemoveList = true;
                inGoalsList = false;
            } else if (inGoalsList && trimmed.startsWith("-")) {
                String goal = trimmed.substring(1).trim();
                if (!goal.isEmpty()) {
                    newGoals.add(goal);
                }
            } else if (inRemoveList && trimmed.startsWith("-")) {
                String goal = trimmed.substring(1).trim();
                if (!goal.isEmpty()) {
                    goalsToRemove.add(goal);
                }
            } else if (trimmed.startsWith("Message:")) {
                String extracted = trimmed.substring("Message:".length()).trim();
                if (!messageBuilder.isEmpty()) {
                    messageBuilder.append('\n');
                }
                messageBuilder.append(extracted);
                inGoalsList = false;
                inRemoveList = false;
            }
        }

        String msg = messageBuilder.toString();
        return new PlannerOutcome(updated, newGoals, goalsToRemove, msg.isEmpty() ? null : msg);
    }
}
