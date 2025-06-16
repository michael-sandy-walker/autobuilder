package com.walpro.autobuilder.goal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.walpro.autobuilder.parser.ResponseParser;
import com.walpro.autobuilder.service.FileService;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoalPlannerService {

    private static final String GOAL_PATH = "meta/GoalStack.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    
    private final FileService fileService;
    
    private Path goalPath;
    
    @PostConstruct
    public void init() throws IOException {
    	goalPath = fileService.resolveSecurePath(GOAL_PATH);
    }

    public String getCurrentGoal() {
        try {
            if (!fileService.exists(goalPath)) return null;
            ObjectNode json = (ObjectNode) MAPPER.readTree(goalPath.toFile());
            return json.has("currentGoal") ? json.get("currentGoal").asText() : null;
        } catch (IOException e) {
            log.warn("Could not load current goal", e);
            return null;
        }
    }

    public List<String> getRemainingGoals() {
        try {
            if (!fileService.exists(goalPath)) return List.of();
            ObjectNode json = (ObjectNode) MAPPER.readTree(goalPath.toFile());
            return MAPPER.convertValue(json.get("remainingGoals"), new TypeReference<List<String>>() {});
        } catch (IOException e) {
            log.warn("Could not load remaining goals", e);
            return List.of();
        }
    }

    public List<String> getCompletedGoals() {
        try {
            if (!fileService.exists(goalPath)) return List.of();
            ObjectNode json = (ObjectNode) MAPPER.readTree(goalPath.toFile());
            return MAPPER.convertValue(json.get("completedGoals"), new TypeReference<List<String>>() {});
        } catch (IOException e) {
            log.warn("Could not load completed goals", e);
            return List.of();
        }
    }

    public void markGoalCompleted(String goal) {
        try {
            if (!fileService.exists(goalPath)) return;

            ObjectNode json = (ObjectNode) MAPPER.readTree(goalPath.toFile());
            String currentGoal = json.has("currentGoal") ? json.get("currentGoal").asText() : null;

            if (goal.equals(currentGoal)) {
                promoteCurrentGoal();
            }
        } catch (IOException e) {
            log.warn("Failed to mark goal completed", e);
        }
    }
    
    public void promoteCurrentGoal() {
        try {
            if (!fileService.exists(goalPath)) return;

            ObjectNode json = (ObjectNode) MAPPER.readTree(goalPath.toFile());

            String currentGoal = json.has("currentGoal") ? json.get("currentGoal").asText() : null;
            List<String> remaining = json.has("remainingGoals")
                ? MAPPER.convertValue(json.get("remainingGoals"), new TypeReference<>() {})
                : new ArrayList<>();
            List<String> completed = json.has("completedGoals")
                ? MAPPER.convertValue(json.get("completedGoals"), new TypeReference<>() {})
                : new ArrayList<>();

            if (currentGoal != null) {
                completed.add(0, currentGoal);
            }

            String newCurrent = remaining.isEmpty() ? null : remaining.remove(0);

            json.put("currentGoal", newCurrent != null ? newCurrent : "");
            json.putPOJO("remainingGoals", remaining);
            json.putPOJO("completedGoals", completed);

            MAPPER.writerWithDefaultPrettyPrinter().writeValue(goalPath.toFile(), json);

        } catch (IOException e) {
            log.warn("Failed to promote current goal", e);
        }
    }
    
    public void applyGoalStackDirectives(ResponseParser.GoalStackDirectives mods) {
        try {
            ObjectNode json = fileService.exists(goalPath)
                ? (ObjectNode) MAPPER.readTree(goalPath.toFile())
                : MAPPER.createObjectNode();

            String currentGoal = json.has("currentGoal") ? json.get("currentGoal").asText() : null;
            List<String> remaining = json.has("remainingGoals")
                ? MAPPER.convertValue(json.get("remainingGoals"), new TypeReference<>() {})
                : new ArrayList<>();

            if (!mods.update.isEmpty()) {
                currentGoal = mods.update.get(0);
                remaining = new ArrayList<>(mods.update.subList(1, mods.update.size()));
                log.info("Goal stack replaced via #GoalStackUpdate");
            }

            for (String g : mods.append) {
                if (!g.equals(currentGoal) && !remaining.contains(g)) {
                    remaining.add(g);
                }
            }

            for (String g : mods.remove) {
                if (g.equals(currentGoal)) currentGoal = null;
                remaining.remove(g);
            }

            json.put("currentGoal", currentGoal != null ? currentGoal : "");
            json.set("remainingGoals", MAPPER.valueToTree(remaining));
            if (!json.has("completedGoals")) {
                json.set("completedGoals", MAPPER.createArrayNode());
            }

            MAPPER.writerWithDefaultPrettyPrinter().writeValue(goalPath.toFile(), json);
            log.info("Applied goal stack modifications: {} updated, {} appended, {} removed",
                    mods.update.size(), mods.append.size(), mods.remove.size());

        } catch (IOException e) {
            log.error("Failed to apply goal stack directives", e);
        }
    }


}
