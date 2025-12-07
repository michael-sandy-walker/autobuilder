package com.walpro.autobuilder.goal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.graph.goal.GoalGraphService;
import com.walpro.autobuilder.core.service.FileService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
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
    private final GoalPlannerConfigProperties goalPlannerConfig;

    private Map<String, GoalEntity> goalMap = new LinkedHashMap<>();

    private String rootGoalId;
    private String currentSubGoalId;
    private ObjectNode rootNode;
    
    private PlanningCallback planningCallback = parentId -> {
        // Default: log and ignore
        log.warn("⚠️ No planning callback defined. Ignoring planInto({})", parentId);
    };

    @PostConstruct
    public void init() throws IOException {
        Path goalPath = fileService.resolveSecurePath(GOAL_PATH);

        if (goalPlannerConfig.isSyncFromGraph()) {
            syncFromGraph();
        } else if (fileService.existsResolved(goalPath)) {
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
    
    public GoalEntity getGoalById(String id) {
        return goalMap.get(id);
    }

    public GoalEntity getRootGoal() {
        return goalMap.get(rootGoalId);
    }

    public GoalEntity getCurrentSubGoal() {
    	if (goalMap.size() == 1 && goalMap.get(rootGoalId).getStatus() == GoalStatus.ACTIVE) {
    	    return goalMap.get(rootGoalId);
    	}
        if (currentSubGoalId == null) {
            log.warn("⚠️ currentSubGoalId is null — no active goals remain?");
            return null;
        }
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
                .filter(g -> g.getStatus() == GoalStatus.ACTIVE)
                .toList();
    }

    public List<GoalEntity> getCompletedGoals() {
        return goalMap.values().stream()
                .filter(g -> g.getStatus() == GoalStatus.COMPLETED)
                .toList();
    }

    public boolean promoteCurrentSubGoal() {
        String completedId = currentSubGoalId;

        if (completedId == null || !goalMap.containsKey(completedId)) {
            log.warn("⚠️ Tried to promote a null or unknown currentSubGoalId.");
            currentSubGoalId = resolveNextActiveLeaf(rootGoalId); // attempt recovery
            persist();
            return false;
        }

        if (completedId.equals(rootGoalId) && getSubGoals(rootGoalId).isEmpty()) {
            log.info("⚠️ Root goal has no subgoals; skipping promotion.");
            return false;
        }

        markGoalCompleted(completedId);

        String next = findNextSiblingOrAncestor(completedId);

        // If no next and parent needs planning, try that
        if (next == null && shouldTriggerPlanner(completedId)) {
            String parentId = goalMap.get(completedId).getParentId();
            if (parentId != null) {
            	planningCallback.planInto(parentId);
                markPlannedOnce(parentId);
                next = findNextSiblingOrAncestor(parentId);
            }
        }

        // If still null, try global search
        if (next == null) {
            next = resolveNextActiveLeaf(rootGoalId);
        }

        // Set current or null if truly done
        currentSubGoalId = next;
        autoCompleteRootIfSatisfied();
        persist();
        
        return true;
    }

    private boolean shouldTriggerPlanner(String fromSubgoalId) {
        if (fromSubgoalId == null) return false;

        GoalEntity current = goalMap.get(fromSubgoalId);
        if (current == null) return false;

        String parentId = current.getParentId();
        while (parentId != null) {
            GoalEntity parent = goalMap.get(parentId);
            if (parent == null) break;

            List<GoalEntity> siblings = getSubGoals(parentId);
            boolean allInactive = siblings.stream().allMatch(g -> g.getStatus() != GoalStatus.ACTIVE);

            if (!allInactive) return false;

            if (Boolean.TRUE.equals(parent.getMetadata().get("plannedOnce"))) {
                log.debug("⏭️ Branch under '{}' already planned once — skipping replanning.", parentId);
                return false;
            }

            if (Objects.equals(parentId, rootGoalId)) {
                return true;
            }

            parentId = parent.getParentId();
        }

        return true;
    }
    
    public void markPlannedOnce(String goalId) {
        GoalEntity g = goalMap.get(goalId);
        if (g != null) {
            g.getMetadata().put("plannedOnce", true);
            persist();
        }
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
        currentSubGoalId = g.getId();
        persist();
    }
    
    public void addSubGoal(String id, String description, boolean setAsCurrent) {
        addSubGoal(id, description, setAsCurrent, rootGoalId);
    }

    public void addSubGoal(String id, String description, boolean setAsCurrent, String parentId) {
        GoalEntity parent = goalMap.get(parentId);
        if (parent == null) {
            throw new IllegalArgumentException("Parent goal not found: " + parentId);
        }

        GoalEntity subGoal = new GoalEntity();
        subGoal.setId(id);
        subGoal.setDescription(description);
        subGoal.setRole("planner");
        subGoal.setType(GoalType.SUBGOAL);
        subGoal.setParentId(parentId);
        subGoal.setStatus(GoalStatus.ACTIVE);
        subGoal.setCreated(Instant.now());
        subGoal.setMetadata(new HashMap<>());

        // Link into the graph
        parent.getSubGoals().add(subGoal);
        goalMap.put(id, subGoal);

        if (setAsCurrent) {
            currentSubGoalId = id;
        }

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

            fileService.writeFile(GOAL_PATH, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(rootNode));

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
        if (parentId == null || !goalMap.containsKey(parentId)) return null;

        List<GoalEntity> children = getSubGoals(parentId);
        for (GoalEntity child : children) {
            if (child.getStatus() != GoalStatus.ACTIVE) continue;

            String deeper = resolveNextActiveLeaf(child.getId());
            if (deeper != null) return deeper;

            return child.getId();
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
    
    public void pruneCompletedBranches() {
        log.info("🔍 Pruning completed branches...");

        Set<String> toPrune = new HashSet<>();

        for (GoalEntity g : goalMap.values()) {
            if (g.getStatus() == GoalStatus.COMPLETED || g.getStatus() == GoalStatus.GIVEN_UP) {
                List<GoalEntity> children = getSubGoals(g.getId());
                boolean noActiveChildren = children.stream().noneMatch(child -> child.getStatus() == GoalStatus.ACTIVE);
                if (noActiveChildren) {
                    toPrune.add(g.getId());
                }
            }
        }

        for (String id : toPrune) {
            GoalEntity g = goalMap.get(id);
            if (g != null && g.getParentId() != null) {
                GoalEntity parent = goalMap.get(g.getParentId());
                if (parent != null) {
                    parent.getSubGoals().removeIf(sg -> sg.getId().equals(id));
                }
            }
            goalMap.remove(id);
        }

        log.info("🪓 Pruned {} completed branches.", toPrune.size());
        persist();
    }

    private String findNextSiblingOrAncestor(String fromId) {
        String childId = fromId;

        while (childId != null) {
            String parentId = goalMap.containsKey(childId) ? goalMap.get(childId).getParentId() : null;
            if (parentId == null) break;

            GoalEntity parent = goalMap.get(parentId);
            if (parent != null && !parentId.equals(rootGoalId)) {
                List<GoalEntity> children = getSubGoals(parentId);
                boolean hasActiveChildren = children.stream().anyMatch(c -> c.getStatus() == GoalStatus.ACTIVE);

                if (!hasActiveChildren && parent.getStatus() == GoalStatus.ACTIVE) {
                    return parent.getId(); // ✅ promote parent as currentSubGoal
                }

                // Look for remaining active siblings after the current one
                boolean passed = false;
                for (GoalEntity sibling : children) {
                    if (sibling.getId().equals(childId)) {
                        passed = true;
                        continue;
                    }
                    if (passed && sibling.getStatus() == GoalStatus.ACTIVE) {
                        String leaf = resolveNextActiveLeaf(sibling.getId());
                        return (leaf != null) ? leaf : sibling.getId();
                    }
                }
            }

            childId = parentId;
        }

        return null;
    }

    public void setPlanningCallback(PlanningCallback callback) {
        this.planningCallback = callback;
    }

}