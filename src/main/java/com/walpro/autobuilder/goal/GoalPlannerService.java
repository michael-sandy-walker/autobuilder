package com.walpro.autobuilder.goal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.walpro.autobuilder.service.FileService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoalPlannerService {

    private static final String GOAL_PATH = "meta/GoalStack.json";
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();;

    private final FileService fileService;

    private Path goalPath;
    private Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
    private String currentGoalId;

    @PostConstruct
    public void init() throws IOException {
        goalPath = fileService.resolveSecurePath(GOAL_PATH);

        if (fileService.exists(goalPath)) {
            ObjectNode json = (ObjectNode) MAPPER.readTree(goalPath.toFile());

            if (json.has("goals")) {
                // New format
                List<GoalEntity> all = MAPPER.convertValue(json.get("goals"), new TypeReference<>() {});
                this.goalMap = all.stream().collect(Collectors.toMap(GoalEntity::getId, g -> g, (a, b) -> a, LinkedHashMap::new));
                this.currentGoalId = json.has("currentGoalId") ? json.get("currentGoalId").asText() : null;
            } else {
                // Legacy format -> migrate
                migrateFromFlatFormat(json);
            }
        }

        persist(); // Save normalized structure
    }

    private void migrateFromFlatFormat(ObjectNode json) {
        log.info("Migrating legacy flat goal format...");

        String currentGoal = json.has("currentGoal") ? json.get("currentGoal").asText() : null;
        List<String> remaining = json.has("remainingGoals")
                ? MAPPER.convertValue(json.get("remainingGoals"), new TypeReference<>() {})
                : new ArrayList<>();
        List<String> completed = json.has("completedGoals")
                ? MAPPER.convertValue(json.get("completedGoals"), new TypeReference<>() {})
                : new ArrayList<>();

        for (String goal : completed) {
            GoalEntity g = new GoalEntity(goal, "legacy", GoalType.GOAL, null);
            g.markCompleted();
            goalMap.put(g.getId(), g);
        }

        for (String goal : remaining) {
            GoalEntity g = new GoalEntity(goal, "legacy", GoalType.GOAL, null);
            goalMap.put(g.getId(), g);
        }

        if (currentGoal != null && !currentGoal.isBlank()) {
            GoalEntity g = new GoalEntity(currentGoal, "legacy", GoalType.GOAL, null);
            goalMap.put(g.getId(), g);
            currentGoalId = g.getId();
        }
    }

    public GoalEntity getCurrentGoal() {
        return goalMap.get(currentGoalId);
    }

    public List<GoalEntity> getRemainingGoals() {
        return goalMap.values().stream()
                .filter(g -> g.getStatus() == GoalStatus.ACTIVE && !g.getId().equals(currentGoalId))
                .toList();
    }

    public List<GoalEntity> getCompletedGoals() {
        return goalMap.values().stream()
                .filter(g -> g.getStatus() == GoalStatus.COMPLETED)
                .toList();
    }

    public void promoteCurrentGoal() {
        GoalEntity current = goalMap.get(currentGoalId);
        if (current != null) {
            current.markCompleted();
        }
        currentGoalId = goalMap.values().stream()
                .filter(g -> g.getStatus() == GoalStatus.ACTIVE)
                .map(GoalEntity::getId)
                .filter(id -> !id.equals(currentGoalId))
                .findFirst()
                .orElse(null);

        persist();
    }

    public void addGoal(String description, String role, GoalType type, String parentId) {
        GoalEntity g = new GoalEntity(description, role, type, parentId);
        goalMap.put(g.getId(), g);
        if (currentGoalId == null) {
            currentGoalId = g.getId();
        }
        persist();
    }

    public void addSubGoal(String description) {
        GoalEntity parent = getCurrentGoal();
        addGoal(description, "llm", GoalType.SUBGOAL, parent != null ? parent.getId() : null);
    }

    private void persist() {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("currentGoalId", currentGoalId);
            root.set("goals", MAPPER.valueToTree(goalMap.values()));
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(goalPath.toFile(), root);
        } catch (IOException e) {
            log.error("Failed to persist goal stack", e);
        }
    }

    public void markGoalCompleted(String id) {
        GoalEntity g = goalMap.get(id);
        if (g != null) {
            g.markCompleted();
            if (id.equals(currentGoalId)) promoteCurrentGoal();
            else persist();
        }
    }

    public List<GoalEntity> getAllGoals() {
        return List.copyOf(goalMap.values());
    }

}
