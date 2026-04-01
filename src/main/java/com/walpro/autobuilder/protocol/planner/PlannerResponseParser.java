package com.walpro.autobuilder.protocol.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PlannerResponseParser {

    // Matches "goal-<uuid>: <description>" — the format PlannerProtocolContract renders in the goal list.
    // When the LLM copies this format into #SubGoalRemove entries we extract just the ID for reliable matching.
    private static final Pattern GOAL_ID_PREFIX = Pattern.compile(
            "^(goal-[0-9a-f\\-]+):\\s*.*", Pattern.CASE_INSENSITIVE);

    public PlannerOutcome parse(String content) {
        boolean updated = false;
        List<String> newGoals = new ArrayList<>();
        List<String> goalsToRemove = new ArrayList<>();
        StringBuilder messageBuilder = new StringBuilder();

        boolean inGoalsList = false;
        boolean inRemoveList = false;

        for (String line : content.lines().toList()) {
            String trimmed = line.trim();

            if (trimmed.startsWith("#SubGoalUpdate")) {
                log.warn("LLM used deprecated #SubGoalUpdate — treating as #SubGoalAppend");
                updated = true;
                inGoalsList = true;
                inRemoveList = false;
            } else if (trimmed.startsWith("#SubGoalAppend")) {
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
                String entry = trimmed.substring(1).trim();
                if (!entry.isEmpty()) {
                    Matcher m = GOAL_ID_PREFIX.matcher(entry);
                    goalsToRemove.add(m.matches() ? m.group(1) : entry);
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
