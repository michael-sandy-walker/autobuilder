package com.walpro.autobuilder.goal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Service
public class GoalPlannerService {

    private static final Path GOAL_FILE = Paths.get("firestarter/meta/GoalStack.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String getCurrentGoal() {
        try {
            if (!Files.exists(GOAL_FILE)) return "Bootstrap project";

            ObjectNode json = (ObjectNode) MAPPER.readTree(GOAL_FILE.toFile());
            List<String> goals = MAPPER.convertValue(json.get("activeGoals"), new TypeReference<List<String>>() {});
            return goals.isEmpty() ? "No active goal" : goals.get(0);
        } catch (IOException e) {
            return "Error loading goal stack";
        }
    }

    public void markGoalCompleted(String goal) {
        try {
            if (!Files.exists(GOAL_FILE)) return;

            ObjectNode json = (ObjectNode) MAPPER.readTree(GOAL_FILE.toFile());
            List<String> active = MAPPER.convertValue(json.get("activeGoals"), new TypeReference<List<String>>() {});
            List<String> done = MAPPER.convertValue(json.get("completedGoals"), new TypeReference<List<String>>() {});

            if (active.remove(goal)) {
                done.add(goal);
                json.putPOJO("activeGoals", active);
                json.putPOJO("completedGoals", done);
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(GOAL_FILE.toFile(), json);
            }
        } catch (IOException ignored) {}
    }
}
