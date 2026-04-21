package com.walpro.autobuilder.protocol.planner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        Map<String, List<String>> criteriaMap = new LinkedHashMap<>();
        StringBuilder messageBuilder = new StringBuilder();
        String planRationale = null;

        boolean inGoalsList = false;
        boolean inRemoveList = false;
        boolean inCriteriaBlock = false;
        String lastGoalDescription = null;

        for (String line : content.lines().toList()) {
            String trimmed = line.trim();

            if (trimmed.startsWith("#SubGoalUpdate")) {
                log.warn("LLM used deprecated #SubGoalUpdate — treating as #SubGoalAppend");
                updated = true;
                inGoalsList = true;
                inRemoveList = false;
                inCriteriaBlock = false;
            } else if (trimmed.startsWith("#SubGoalAppend")) {
                updated = true;
                inGoalsList = true;
                inRemoveList = false;
                inCriteriaBlock = false;
            } else if (trimmed.startsWith("#SubGoalRemove")) {
                updated = true;
                inRemoveList = true;
                inGoalsList = false;
                inCriteriaBlock = false;
            } else if (trimmed.startsWith("PlanRationale:")) {
                // T3-BH: parse the batch rationale emitted by the planner
                String extracted = trimmed.substring("PlanRationale:".length()).trim();
                if (!extracted.isEmpty()) {
                    planRationale = extracted;
                }
                inGoalsList = false;
                inRemoveList = false;
                inCriteriaBlock = false;
            } else if (inGoalsList && trimmed.startsWith("-")) {
                // A new goal line always exits criteria block
                inCriteriaBlock = false;
                String goal = trimmed.substring(1).trim();
                if (!goal.isEmpty()) {
                    newGoals.add(goal);
                    lastGoalDescription = goal;
                }
            } else if (inGoalsList && trimmed.startsWith("Criteria:")) {
                inCriteriaBlock = (lastGoalDescription != null);
            } else if (inCriteriaBlock && trimmed.startsWith("*")) {
                String criterion = trimmed.substring(1).trim();
                if (!criterion.isEmpty()) {
                    criteriaMap.computeIfAbsent(lastGoalDescription, k -> new ArrayList<>()).add(criterion);
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
                inCriteriaBlock = false;
            }
        }

        String msg = messageBuilder.toString();
        return new PlannerOutcome(updated, newGoals, goalsToRemove, msg.isEmpty() ? null : msg,
                criteriaMap.isEmpty() ? null : criteriaMap, planRationale);
    }
}
