package com.walpro.autobuilder.goal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.walpro.autobuilder.config.GoalPlannerConfig;
import com.walpro.autobuilder.goal.graph.GoalGraphService;
import com.walpro.autobuilder.service.FileService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoalPlannerService {

    private static final String GOAL_PATH = "meta/GoalStack.json";
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final FileService fileService;
    private final GoalGraphService goalGraphService;
    private final GoalPlannerConfig goalPlannerConfig;

    private Path goalPath;
    private Map<String, GoalEntity> goalMap = new LinkedHashMap<>();

    private String rootGoalId;
    private String currentSubGoalId;
    private ObjectNode rootNode;

    @PostConstruct
    public void init() throws IOException {
        goalPath = fileService.resolveSecurePath(GOAL_PATH);

        if (goalPlannerConfig.isSyncFromGraph()) {
            syncFromGraph();
        } else if (fileService.exists(goalPath)) {
            ObjectNode json = (ObjectNode) MAPPER.readTree(goalPath.toFile());
            if (json.has("goalTree")) {
                GoalEntity root = MAPPER.treeToValue(json.get("goalTree"), GoalEntity.class);
                this.goalMap = new LinkedHashMap<>();
                flattenRecursive(root, null); // fills goalMap and sets parentIds
                this.rootGoalId = root.getId();
                this.rootNode = MAPPER.createObjectNode();
                this.rootNode.put("currentGoalId", rootGoalId);
            }
        }

        if (currentSubGoalId == null) {
            currentSubGoalId = resolveNextActiveLeaf(rootGoalId);
        }

        persist();

        if (goalPlannerConfig.isMigrateToGraph()) {
            migrateToGraph();
        }

        logGoalTree();
    }

    private void flattenRecursive(GoalEntity node, String parentId) {
        node.setParentId(parentId);
        goalMap.put(node.getId(), node);
        for (GoalEntity child : node.getSubGoals()) {
            flattenRecursive(child, node.getId());
        }
    }

    public GoalEntity getRootGoal() {
        return goalMap.get(rootGoalId);
    }

    public GoalEntity getCurrentSubGoal() {
        return goalMap.get(currentSubGoalId);
    }

    public void setCurrentSubGoal(String goalId) {
        if (!goalMap.containsKey(goalId)) {
            throw new IllegalArgumentException("No such goal: " + goalId);
        }
        this.currentSubGoalId = goalId;
        persist();
    }

    public List<GoalEntity> getRemainingGoals() {
        return goalMap.values().stream()
                .filter(g -> g.getStatus() == GoalStatus.ACTIVE && !g.getId().equals(rootGoalId))
                .toList();
    }

    public List<GoalEntity> getCompletedGoals() {
        return goalMap.values().stream()
                .filter(g -> g.getStatus() == GoalStatus.COMPLETED)
                .toList();
    }

    public void promoteCurrentSubGoal() {
        markGoalCompleted(currentSubGoalId);

        // Recalculate next
        currentSubGoalId = resolveNextActiveLeaf(rootGoalId);

        // If there are no more active children and root is still active → trigger planner suggestion
        if (currentSubGoalId == null && getRootGoal() != null && getRootGoal().getStatus() == GoalStatus.ACTIVE) {
            log.info("⚠️ Root goal has no remaining active subgoals. Suggesting planner to inject more.");
            // currentSubGoalId stays null until planner adds something

            autoCompleteRootIfSatisfied();
        }

        persist();
    }

    public void addInitialGoal(String id, String description) {
        GoalEntity g = new GoalEntity(id, description, "system", GoalType.GOAL, null);
        goalMap.put(g.getId(), g);
        rootGoalId = g.getId();
        persist();
    }

    public void resetInitialGoal(String id, String description) {
        goalMap.clear();
        GoalEntity g = new GoalEntity(id, description, "system", GoalType.GOAL, null);
        goalMap.put(g.getId(), g);
        rootGoalId = g.getId();
        currentSubGoalId = resolveNextActiveLeaf(rootGoalId);
        persist();
    }

    public void addSubGoal(String id, String description) {
        GoalEntity parent = getCurrentSubGoal();
        String effectiveParentId = parent != null ? parent.getId() : rootGoalId;

        if (isDuplicateSubGoal(description, effectiveParentId)) {
            log.warn("🛑 Duplicate or semantically similar subgoal '{}' under parent '{}' was blocked.", description, effectiveParentId);
            return;
        }

        GoalEntity g = new GoalEntity(id, description, "llm", GoalType.SUBGOAL, effectiveParentId);
        goalMap.put(g.getId(), g);
        currentSubGoalId = g.getId();
        persist();
    }

    private void persist() {
        try {
            if (rootNode == null) rootNode = MAPPER.createObjectNode();
            rootNode.put("currentGoalId", rootGoalId);

            GoalEntity root = getRootGoal();
            if (root != null) {
                attachChildrenRecursive(root);
                rootNode.set("goalTree", MAPPER.valueToTree(root));
            }

            MAPPER.writerWithDefaultPrettyPrinter().writeValue(goalPath.toFile(), rootNode);
        } catch (IOException e) {
            log.error("Failed to persist goal stack", e);
        }
    }

    private void attachChildrenRecursive(GoalEntity parent) {
        List<GoalEntity> children = goalMap.values().stream()
                .filter(g -> Objects.equals(g.getParentId(), parent.getId()))
                .sorted(Comparator.comparing(
                        GoalEntity::getCreated,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ))
                .toList();
        parent.getSubGoals().clear();
        parent.getSubGoals().addAll(children);
        for (GoalEntity child : children) {
            attachChildrenRecursive(child);
        }
    }

    public void markGoalCompleted(String id) {
        markGoalStatus(id, g -> {
            g.markCompleted();
            return null;
        });
    }

    public void markGoalGivenUp(String id) {
        markGoalStatus(id, g -> {
            g.markGivenUp();
            return null;
        });
    }

    private void markGoalStatus(String id, Function<GoalEntity, Void> action) {
        GoalEntity g = goalMap.get(id);
        if (g != null) {
            action.apply(g);
            persist();
        }
    }

    private String resolveNextActiveLeaf(String parentId) {
        List<GoalEntity> children = goalMap.values().stream()
                .filter(g -> Objects.equals(g.getParentId(), parentId))
                .sorted(Comparator.comparing(
                        GoalEntity::getCreated,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ))
                .toList();

        for (GoalEntity child : children) {
            if (child.getStatus() == GoalStatus.ACTIVE) {
                // Try to go deeper
                String deeper = resolveNextActiveLeaf(child.getId());
                return deeper != null ? deeper : child.getId();
            }
        }
        return null;
    }

    public List<GoalEntity> getAllGoals() {
        return List.copyOf(goalMap.values());
    }

    public void migrateToGraph() {
        log.info("Migrating goals from JSON to Neo4j...");
        int migrated = 0;
        for (GoalEntity g : goalMap.values()) {
            try {
                goalGraphService.save(g);
                migrated++;
            } catch (Exception e) {
                log.error("Failed to migrate goal: {}", g.getId(), e);
            }
        }
        log.info("Migration completed. {} goals written to graph database.", migrated);
    }

    public void syncFromGraph() {
        try {
            List<GoalEntity> graphGoals = goalGraphService.findAll();
            this.goalMap = graphGoals.stream().collect(Collectors.toMap(GoalEntity::getId, g -> g, (a, b) -> a, LinkedHashMap::new));
            this.rootGoalId = graphGoals.stream()
                    .filter(g -> g.getType() == GoalType.GOAL)
                    .findFirst()
                    .map(GoalEntity::getId)
                    .orElse(null);
            this.currentSubGoalId = resolveNextActiveLeaf(rootGoalId);
            this.rootNode = MAPPER.createObjectNode();
            rootNode.put("currentGoalId", rootGoalId);
            persist();
            log.info("Goal stack synchronized from Neo4j into local JSON");
        } catch (Exception e) {
            log.error("Failed to sync goal stack from Neo4j", e);
        }
    }

    public void autoCompleteRootIfSatisfied() {
        GoalEntity root = getRootGoal();
        // TODO: maybe evaluate here if the root goal is still ACTIVE, COMPLETED or GIVEN_UP?
        if (root == null /*|| root.getStatus() != GoalStatus.ACTIVE*/) return;

        boolean allDescendantsCompleted = goalMap.values().stream()
                .filter(g -> isDescendantOf(g, root.getId()))
                .allMatch(g -> g.getStatus() == GoalStatus.COMPLETED /*|| g.getStatus() == GoalStatus.GIVEN_UP*/);

        if (allDescendantsCompleted) {
            root.markCompleted();
            persist();
            log.info("✅ Root goal '{}' marked as completed (all descendants done)", root.getId());
        }
    }

    private boolean isDescendantOf(GoalEntity g, String ancestorId) {
        String parent = g.getParentId();
        while (parent != null) {
            if (parent.equals(ancestorId)) return true;
            GoalEntity p = goalMap.get(parent);
            if (p == null) break;
            parent = p.getParentId();
        }
        return false;
    }

    public List<GoalEntity> getSubGoals(String parentId) {
        return goalMap.values().stream()
                .filter(g -> Objects.equals(g.getParentId(), parentId))
                .sorted(Comparator.comparing(
                        GoalEntity::getCreated,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ))
                .toList();
    }

    public void logGoalTree() {
        GoalEntity root = getRootGoal();
        if (root == null) {
            log.warn("No root goal defined.");
            return;
        }
        logGoalRecursive(root, 0);
    }

    private void logGoalRecursive(GoalEntity goal, int depth) {
        String indent = "  ".repeat(depth);
        log.info("{}- [{}] {}", indent, goal.getStatus(), goal.getDescription());
        for (GoalEntity sub : getSubGoals(goal.getId())) {
            logGoalRecursive(sub, depth + 1);
        }
    }

    private boolean isDuplicateSubGoal(String description, String parentId) {
        return goalMap.values().stream()
                .filter(g -> Objects.equals(g.getParentId(), parentId))
                .anyMatch(g -> normalize(g.getDescription()).equals(normalize(description)));
    }

    private String normalize(String input) {
        return input.toLowerCase()
                .replaceAll("[^a-z0-9]", " ") // Remove non-alphanumerics
                .replaceAll("\\s+", " ")      // Collapse whitespace
                .trim();
    }

}