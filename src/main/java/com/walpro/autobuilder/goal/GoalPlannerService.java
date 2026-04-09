package com.walpro.autobuilder.goal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.graph.goal.GoalGraphService;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.graph.goal.GoalGraphState;
import com.walpro.autobuilder.model.GoalProgressState;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;

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

    private static final String META_ATTEMPT_ID = "activeAttemptId";
    private static final String META_PARENT_ATTEMPT_ID = "parentAttemptId";

    private static final int DEFAULT_MAX_NEW_SUBGOALS = 8;
    private static final int DEFAULT_MIN_CHARS = 12;

    private final FileService fileService;
    private final GoalGraphService goalGraphService;
    private final GoalPlannerConfigProperties goalPlannerConfig;

    private Map<String, GoalEntity> goalMap = new LinkedHashMap<>();

    private String rootGoalId;
    private String currentSubGoalId;
    private ObjectNode rootNode;

    @Setter
    private PlanningCallback planningCallback = (ctx, parentId) -> {
        // Default: log and ignore. Return -1 to signal "no planner registered" so that
        // autoCompleteRootIfSatisfied still runs (not the T1-BK dedup-blocked case).
        log.warn("No planning callback defined. Ignoring planInto({})", parentId);
        return -1;
    };

    private transient CycleContextData currentCycleContext;

    public void setCurrentCycleContext(CycleContextData ctx) {
        this.currentCycleContext = ctx;
    }

    public CycleContextData getCurrentCycleContext() {
        return this.currentCycleContext;
    }

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

            if (json.hasNonNull("currentSubGoalId")) {
                String loaded = json.get("currentSubGoalId").asText(null);
                if (loaded != null && goalMap.containsKey(loaded) && goalMap.get(loaded).getStatus() == GoalStatus.ACTIVE) {
                    this.currentSubGoalId = loaded;
                }
            }
        }

        // Ensure attempt lineage exists for root + current scope (idempotent)
        if (rootGoalId != null) {
            ensureAttemptIdsFor(rootGoalId);
        }
        if (currentSubGoalId != null) {
            ensureAttemptIdsFor(currentSubGoalId);
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

    /** Persists metadata-only changes already applied to the entity object. */
    public void saveGoalMetadata(GoalEntity goal) {
        if (goal == null) return;
        goalGraphService.save(goal);
    }

    /** Returns an integer counter stored in goal metadata, defaulting to 0 if absent. */
    public int getGoalMetadataInt(GoalEntity goal, String key) {
        Object val = goal.getMetadata().get(key);
        return val instanceof Number n ? n.intValue() : 0;
    }

    /** Increments an integer counter in goal metadata and persists the change. */
    public void incrementGoalMetadataInt(String goalId, String key) {
        GoalEntity goal = goalId != null ? goalMap.get(goalId) : null;
        if (goal == null) return;
        int current = getGoalMetadataInt(goal, key);
        goal.getMetadata().put(key, current + 1);
        saveGoalMetadata(goal);
    }

    /** Increments the merge count for a goal (called by MergeAgent after a successful merge). */
    public void incrementGoalMergeCount(String goalId) {
        incrementGoalMetadataInt(goalId, "mergeCount");
    }

    /**
     * T1-BG/T3-BI: Reads the cumulative criteria pass status from goal metadata.
     * Keys are stored as Strings in JSON but returned as Integer → Boolean.
     */
    @SuppressWarnings("unchecked")
    public Map<Integer, Boolean> getCriteriaPassStatus(GoalEntity goal) {
        if (goal == null || goal.getMetadata() == null) return new HashMap<>();
        Object raw = goal.getMetadata().get("criteriaPassStatus");
        if (!(raw instanceof Map<?, ?> rawMap)) return new HashMap<>();
        Map<Integer, Boolean> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            try {
                int key = Integer.parseInt(String.valueOf(entry.getKey()));
                if (entry.getValue() instanceof Boolean b) result.put(key, b);
            } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    /**
     * T1-BG/T3-BI: Persists the cumulative criteria pass status to goal metadata.
     * Integer keys are converted to String for JSON serialization compatibility.
     */
    public void saveCriteriaPassStatus(String goalId, Map<Integer, Boolean> status) {
        GoalEntity goal = goalId != null ? goalMap.get(goalId) : null;
        if (goal == null) return;
        Map<String, Boolean> serializable = new LinkedHashMap<>();
        status.forEach((k, v) -> serializable.put(String.valueOf(k), v));
        goal.getMetadata().put("criteriaPassStatus", serializable);
        saveGoalMetadata(goal);
    }

    /**
     * T2-BK: Persists a set of subgoal-scoped signals to goal metadata.
     * The caller is responsible for pre-filtering to only subgoal-scoped types.
     * Each signal is stored as a plain map to survive JSON round-trips.
     */
    public void saveSubgoalSignals(String goalId, Map<SignalType, Signal> signals) {
        GoalEntity goal = goalId != null ? goalMap.get(goalId) : null;
        if (goal == null || signals == null || signals.isEmpty()) return;
        Map<String, Map<String, Object>> serializable = new LinkedHashMap<>();
        for (Signal s : signals.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("strength", s.strength());
            entry.put("decayPerCycle", s.decayPerCycle());
            entry.put("age", s.age());
            entry.put("source", s.source() != null ? s.source() : "");
            serializable.put(s.type().name(), entry);
        }
        goal.getMetadata().put("subgoalSignals", serializable);
        saveGoalMetadata(goal);
    }

    /**
     * T2-BK: Loads previously persisted subgoal-scoped signals from goal metadata.
     * Returns an empty map if no signals have been persisted yet.
     */
    @SuppressWarnings("unchecked")
    public Map<SignalType, Signal> getSubgoalSignals(GoalEntity goal) {
        if (goal == null || goal.getMetadata() == null) return Map.of();
        Object raw = goal.getMetadata().get("subgoalSignals");
        if (!(raw instanceof Map<?, ?> rawMap)) return Map.of();
        Map<SignalType, Signal> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            try {
                String typeName = String.valueOf(entry.getKey());
                if (!(entry.getValue() instanceof Map<?, ?> m)) continue;
                double strength = ((Number) m.get("strength")).doubleValue();
                double decay = ((Number) m.get("decayPerCycle")).doubleValue();
                int age = ((Number) m.get("age")).intValue();
                Object rawSource = m.get("source");
                String source = rawSource != null ? String.valueOf(rawSource) : "restored";
                SignalType type = SignalType.of(typeName);
                result.put(type, new Signal(type, strength, decay, age, source, Map.of()));
            } catch (Exception e) {
                log.warn("Failed to deserialize subgoal signal entry '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        return result;
    }

    public GoalEntity getRootGoal() {
        return goalMap.get(rootGoalId);
    }

    public AttemptIds ensureAttemptIdsFor(String goalId) {
        if (goalId == null || goalId.isBlank()) {
            return new AttemptIds(null, null);
        }

        GoalEntity g = goalMap.get(goalId);
        if (g == null) {
            return new AttemptIds(null, null);
        }

        Map<String, Object> meta = g.getMetadata();
        if (meta == null) {
            meta = new HashMap<>();
            g.setMetadata(meta);
        }

        boolean changed = false;

        String attemptId = asString(meta.get(META_ATTEMPT_ID));
        if (attemptId == null || attemptId.isBlank()) {
            attemptId = "attempt-" + UUID.randomUUID();
            meta.put(META_ATTEMPT_ID, attemptId);
            changed = true;
        }

        String parentAttemptId = asString(meta.get(META_PARENT_ATTEMPT_ID));
        String parentGoalId = g.getParentId();

        if (parentGoalId == null || parentGoalId.isBlank()) {
            // root scope
            parentAttemptId = null;
            if (meta.containsKey(META_PARENT_ATTEMPT_ID)) {
                meta.remove(META_PARENT_ATTEMPT_ID);
                changed = true;
            }
        } else {
            AttemptIds parent = ensureAttemptIdsFor(parentGoalId);
            String expectedParentAttemptId = parent.attemptId();

            if (expectedParentAttemptId != null && !expectedParentAttemptId.isBlank()) {
                if (parentAttemptId == null || parentAttemptId.isBlank()
                        || !expectedParentAttemptId.equals(parentAttemptId)) {
                    parentAttemptId = expectedParentAttemptId;
                    meta.put(META_PARENT_ATTEMPT_ID, parentAttemptId);
                    changed = true;
                }
            }
        }

        if (changed) {
            persist();
            try {
                goalGraphService.save(g);
            } catch (Exception ignored) {
            }
        }

        return new AttemptIds(attemptId, parentAttemptId);
    }

    public AttemptIds ensureAttemptIdsForCurrentScope() {
        String scope = (currentSubGoalId != null && !currentSubGoalId.isBlank()) ? currentSubGoalId : rootGoalId;
        return ensureAttemptIdsFor(scope);
    }

    private static String asString(Object o) {
        return (o == null) ? null : String.valueOf(o);
    }
    public GoalEntity getCurrentSubGoal() {
        if (goalMap.size() == 1 && rootGoalId != null && goalMap.get(rootGoalId).getStatus() == GoalStatus.ACTIVE) {
//            return goalMap.get(rootGoalId);
            return null;
        }
        if (currentSubGoalId == null) {
            GoalEntity root = rootGoalId != null ? goalMap.get(rootGoalId) : null;
            if (root != null && root.getStatus() == GoalStatus.COMPLETED) {
                log.info("currentSubGoalId is null — root goal is COMPLETED, loop should halt.");
            } else {
                log.warn("currentSubGoalId is null — no active subgoals remain; replanning may be needed.");
                // T1-BK: do not auto-complete root if REPLAN_REQUESTED is pending — the null pointer
                // is due to deduplication blocking injection, not genuine completion.
                boolean replanPending = currentCycleContext != null
                        && currentCycleContext.hasSignal(BasicSignals.REPLAN_REQUESTED);
                if (root != null && !replanPending) {
                    log.warn("no active subgoals remain; setting root to completed.");
                    root.setStatus(GoalStatus.COMPLETED);
                }
            }
            return null;
        }
        return goalMap.get(currentSubGoalId);
    }


    public void setCurrentSubGoal(String goalId) {
        GoalEntity goal = goalMap.get(goalId);
        if (goal == null) {
            throw new IllegalArgumentException("No such goal: " + goalId);
        }

        if (goal.getStatus() != GoalStatus.ACTIVE) {
            throw new IllegalStateException("Cannot activate non-active goal: " + goalId + " (status=" + goal.getStatus() + ")");
        }

        if (goal.getType() != GoalType.SUBGOAL) {
            throw new IllegalArgumentException("Current subgoal must be a SUBGOAL: " + goalId);
        }

        this.currentSubGoalId = goalId;

        // ensure attempt context for the newly active scope
        ensureAttemptIdsFor(goalId);
        persistPointersToGraph();

        persist();
    }

    public List<GoalEntity> getRemainingGoals() {
        return goalMap.values().stream()
                .filter(g -> g.getStatus() == GoalStatus.ACTIVE && g.getType() == GoalType.SUBGOAL)
                .toList();
    }

    public List<GoalEntity> getCompletedGoals() {
        return goalMap.values().stream()
                .filter(g -> g.getStatus() == GoalStatus.COMPLETED)
                .toList();
    }

    public List<GoalEntity> getAbandonedGoals() {
        return goalMap.values().stream()
                .filter(g -> g.getStatus() == GoalStatus.GIVEN_UP)
                .toList();
    }

    public boolean promoteCurrentSubGoal() {
        String completedId = currentSubGoalId;

        if (completedId == null || !goalMap.containsKey(completedId)) {
            log.warn("Tried to promote a null or unknown currentSubGoalId.");
            currentSubGoalId = resolveNextActiveLeaf(rootGoalId); // attempt recovery
            if (currentSubGoalId != null) {
                ensureAttemptIdsFor(currentSubGoalId);
            }
            persist();
            return false;
        }

        if (completedId.equals(rootGoalId) && getSubGoals(rootGoalId).isEmpty()) {
            log.info("Root goal has no subgoals; skipping promotion.");
            return false;
        }

        markGoalCompleted(completedId);

        String next = findNextSiblingOrAncestor(completedId);

        // If no next and parent needs planning, try that
        boolean plannerWasCalled = false;
        int plannerInjectedCount = 0;
        if (next == null && shouldTriggerPlanner(completedId)) {
            String parentId = goalMap.get(completedId).getParentId();
            if (parentId != null) {
                plannerInjectedCount = planningCallback.planInto(currentCycleContext, parentId);
                plannerWasCalled = true;
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

        if (currentSubGoalId != null) {
            ensureAttemptIdsFor(currentSubGoalId);
        }

        // T1-BK: if the planner was invoked but injected nothing (all proposals blocked by
        // deduplication), do NOT auto-complete the root — the system cannot distinguish
        // "genuinely done" from "planner tried to add work but was deduped".
        // Instead, surface REPLAN_REQUESTED so the next cycle re-attempts planning.
        if (plannerWasCalled && plannerInjectedCount == 0) {
            log.warn("T1-BK: planInto returned 0 injected subgoals — skipping autoCompleteRootIfSatisfied and raising REPLAN_REQUESTED");
            if (currentCycleContext != null) {
                currentCycleContext = currentCycleContext.withSignal(
                        Signal.of(BasicSignals.REPLAN_REQUESTED, 1.0, 0.2, "planInto-deduped"));
            }
        } else {
            autoCompleteRootIfSatisfied();
        }

        persistPointersToGraph();
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
                log.debug("Branch under '{}' already planned once — skipping replanning.", parentId);
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
            try {
                goalGraphService.save(g);   // properties-only metadata update
            } catch (HttpServerErrorException e) { // ignore server error exceptions for now TODO: think about a potential delay or something else
                log.debug("Neo4j save failed temporarily for goal {} (ignoring for now): {}", goalId, e.getMessage());
            }
            persist();
        }
    }

    public void addInitialGoal(String id, String description) {
        GoalEntity g = new GoalEntity(id, description, "system", GoalType.GOAL, null);
        goalMap.put(g.getId(), g);
        rootGoalId = g.getId();

        // write node + state to graph as authoritative
        goalGraphService.save(g);
        persistPointersToGraph();

        persist();
    }

    public void resetInitialGoal(String id, String description) {
        goalMap.clear();
        GoalEntity g = new GoalEntity(id, description, "system", GoalType.GOAL, null);
        goalMap.put(g.getId(), g);
        rootGoalId = g.getId();
//        currentSubGoalId = g.getId();

        // write node + state to graph as authoritative
        goalGraphService.save(g);
        persistPointersToGraph();

        persist();
    }

    public void addSubGoal(String id, String description, boolean setAsCurrent) {
        addSubGoal(id, description, setAsCurrent, rootGoalId);
    }

    public void addSubGoal(String id, String description, boolean setAsCurrent, String parentId) {
        addSubGoal(id, description, setAsCurrent, parentId, "planner", null);
    }

    public void addSubGoal(
            String id,
            String description,
            boolean setAsCurrent,
            String parentId,
            String role,
            GoalKindClassifier.GoalKind kind
    ) {
        GoalEntity parent = goalMap.get(parentId);
        if (parent == null) {
            throw new IllegalArgumentException("Parent goal not found: " + parentId);
        }

        GoalEntity subGoal = new GoalEntity();
        subGoal.setId(id);
        subGoal.setDescription(description);
        subGoal.setRole(role != null ? role : "planner");
        subGoal.setType(GoalType.SUBGOAL);
        subGoal.setParentId(parentId);
        subGoal.setStatus(GoalStatus.ACTIVE);
        subGoal.setCreated(Instant.now());
        subGoal.setMetadata(new HashMap<>());
        subGoal.setKind(kind);

        // In-memory index only
        goalMap.put(id, subGoal);

        // ensure parent attempt exists first (so child can link to it)
        ensureAttemptIdsFor(parentId);
        ensureAttemptIdsFor(id);

        if (setAsCurrent) {
            currentSubGoalId = id;
            persistPointersToGraph();
        }

        persist(); // TODO: persist is also called by the ensure methods, maybe deduplicate this

        try {
            // Graph writes:
            // 1) properties-only upsert
//            goalGraphService.save(parent);
            goalGraphService.save(subGoal);

            // 2) explicit topology operation (no longer hidden inside save())
            goalGraphService.setParent(subGoal.getId(), parentId);
        } catch (HttpServerErrorException e) { // ignore server error exceptions for now TODO: think about a potential delay or something else
            log.debug("Neo4j save failed temporarily for goal {} (ignoring for now): {}", subGoal.getId(), e.getMessage());
        }
    }

    /**
     * T3-AW: stores acceptance criteria in a goal's metadata and re-persists.
     * Called by PlannerAgent after injecting a subgoal when the LLM provided criteria.
     */
    public void setAcceptanceCriteria(String goalId, List<String> criteria) {
        if (criteria == null || criteria.isEmpty()) return;
        GoalEntity g = goalMap.get(goalId);
        if (g == null) return;
        g.getMetadata().put("acceptanceCriteria", criteria);
        persist();
        try {
            goalGraphService.save(g);
        } catch (HttpServerErrorException e) {
            log.debug("Neo4j criteria update failed for goal {} (ignoring): {}", goalId, e.getMessage());
        }
    }

    public List<String> sanitizeProposedSubGoals(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();

        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();

        for (String s : raw) {
            String cleaned = normalizeGoalText(s);
            if (cleaned == null) continue;

            // local (within-batch) exact de-dup
            if (!seen.add(cleaned)) continue;

            out.add(cleaned);
            if (out.size() >= DEFAULT_MAX_NEW_SUBGOALS) break;
        }

        return out;
    }

    private String normalizeGoalText(String s) {
        if (s == null) return null;

        String cleaned = s
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();

        if (cleaned.isBlank()) return null;
        if (cleaned.length() < DEFAULT_MIN_CHARS) return null;

        String lc = cleaned.toLowerCase(Locale.ROOT);

        // Drop obvious junk/meta
        if (lc.startsWith("as an ai")) return null;
        if (lc.equals("fix") || lc.equals("improve") || lc.equals("refactor")) return null;

        return cleaned;
    }

    /**
     * Batch helper: adds all sanitized subgoals under parentId.
     * The first accepted entry can become current if setFirstAsCurrent is true.
     */
    public List<String> addSubGoalsBatch(
            List<String> rawDescriptions,
            String parentId,
            boolean setFirstAsCurrent,
            String role,
            GoalKindClassifier.GoalKind kind
    ) {
        List<String> sanitized = sanitizeProposedSubGoals(rawDescriptions);
        if (sanitized.isEmpty()) return List.of();

        List<String> createdIds = new ArrayList<>();
        boolean first = true;

        for (String desc : sanitized) {
            String id = "goal-" + java.util.UUID.randomUUID();
            boolean setAsCurrent = setFirstAsCurrent && first;

            addSubGoal(id, desc, setAsCurrent, parentId, role, kind);

            createdIds.add(id);
            first = false;
        }

        return createdIds;
    }

    /**
     * Guard-friendly scope switch:
     * - no exceptions
     * - only switches to ACTIVE SUBGOAL
     * - persists pointers to graph + JSON snapshot
     */
    public boolean activateSubGoal(String subGoalId, String reason) {
        if (subGoalId == null || subGoalId.isBlank()) return false;

        GoalEntity g = goalMap.get(subGoalId);
        if (g == null) {
            log.warn("activateSubGoal: unknown goalId={} (reason={})", subGoalId, reason);
            return false;
        }

        if (g.getType() != GoalType.SUBGOAL) {
            log.warn("activateSubGoal: goalId={} is not SUBGOAL (type={}) (reason={})",
                    subGoalId, g.getType(), reason);
            return false;
        }

        if (g.getStatus() != GoalStatus.ACTIVE) {
            log.warn("activateSubGoal: goalId={} is not ACTIVE (status={}) (reason={})",
                    subGoalId, g.getStatus(), reason);
            return false;
        }

        if (Objects.equals(currentSubGoalId, subGoalId)) {
            log.debug("activateSubGoal: already active goalId={} (reason={})", subGoalId, reason);
            return true;
        }

        this.currentSubGoalId = subGoalId;

        // ensure lineage context exists for this scope
        ensureAttemptIdsFor(subGoalId);

        // graph-primary pointer update
        persistPointersToGraph();

        // JSON snapshot (derived)
        persist();

        log.info("Activated subgoal: {} (reason={})", subGoalId, reason);
        return true;
    }

    private void persist() {
        try {
            if (rootNode == null) rootNode = MAPPER.createObjectNode();

            rootNode.put("currentGoalId", rootGoalId);
            rootNode.put("currentSubGoalId", currentSubGoalId);


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

        goalGraphService.markCompleted(id);
    }

    public void markGoalGivenUp(String id) {
        markGoalStatus(id, g -> {
            g.markGivenUp();
            return null;
        });

        GoalEntity g = goalMap.get(id);
        if (g != null) {
            goalGraphService.save(g); // properties-only update of status
        }

        List<GoalEntity> activeDescendants = collectActiveDescendants(id);
        if (!activeDescendants.isEmpty()) {
            for (GoalEntity desc : activeDescendants) {
                desc.markGivenUp();
                goalGraphService.save(desc);
                log.info("markGoalGivenUp cascade: goalId={} marked GIVEN_UP (ancestor={})", desc.getId(), id);
            }
            persist();
        }
    }

    /**
     * T2-AL: per-parent exhaustion pattern detection.
     * Fires when a strict majority (> 50%) of the parent's direct children are GIVEN_UP
     * and none are COMPLETED, subject to a minimum floor count.
     * Effective threshold = max(minFloor, ceil(totalChildren × 0.51)).
     * <p>
     * The parent (and its remaining ACTIVE children) are marked GIVEN_UP, and the
     * currentSubGoalId pointer is advanced past the dead branch.
     *
     * @param childId  the child that was just exhausted
     * @param minFloor minimum number of GIVEN_UP children required regardless of family size
     * @return true if the parent was exhausted, false otherwise
     */
    public boolean exhaustParentIfStuck(String childId, int minFloor) {
        GoalEntity child = goalMap.get(childId);
        if (child == null) return false;
        String parentId = child.getParentId();
        if (parentId == null || parentId.equals(rootGoalId)) return false;

        GoalEntity parent = goalMap.get(parentId);
        if (parent == null || parent.getStatus() != GoalStatus.ACTIVE) return false;

        List<GoalEntity> siblings = getSubGoals(parentId);
        long givenUpCount = siblings.stream().filter(s -> s.getStatus() == GoalStatus.GIVEN_UP).count();
        long completedCount = siblings.stream().filter(s -> s.getStatus() == GoalStatus.COMPLETED).count();

        int effectiveThreshold = Math.max(minFloor, (int) Math.ceil(siblings.size() * 0.51));

        if (givenUpCount >= effectiveThreshold && completedCount == 0) {
            log.warn("Per-parent exhaustion (T2-AL): {}/{} children GIVEN_UP (threshold={}) with no completions under parentId={}. Marking parent GIVEN_UP.",
                    givenUpCount, siblings.size(), effectiveThreshold, parentId);
            markGoalGivenUp(parentId); // cascades to remaining ACTIVE children

            // advance currentSubGoalId past the dead branch (mirrors applyGoalProgress GIVEN_UP path)
            String next = findNextSiblingOrAncestor(parentId);
            if (next == null) {
                next = resolveNextActiveLeaf(rootGoalId);
            }
            currentSubGoalId = next;
            if (currentSubGoalId != null) {
                ensureAttemptIdsFor(currentSubGoalId);
            }
            log.info("Per-parent exhaustion: advanced currentSubGoalId from {} to {}", parentId, currentSubGoalId);
            persistPointersToGraph();
            return true;
        }
        return false;
    }

    private List<GoalEntity> collectActiveDescendants(String parentId) {
        List<GoalEntity> result = new ArrayList<>();
        for (GoalEntity child : getSubGoals(parentId)) {
            if (child.getStatus() == GoalStatus.ACTIVE) {
                if (child.getMergeCount() > 0) {
                    log.info("collectActiveDescendants: skipping goalId={} from cascade — has {} merge(s), letting it exhaust naturally",
                            child.getId(), child.getMergeCount());
                    continue; // protect this goal and its subtree — it produced real output
                }
                result.add(child);
            }
            result.addAll(collectActiveDescendants(child.getId()));
        }
        return result;
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
                if (g.getParentId() != null && !g.getParentId().isBlank()) {
                    goalGraphService.setParent(g.getId(), g.getParentId());
                }
                migrated++;
            } catch (Exception e) {
                log.error("Failed to migrate goal: {}", g.getId(), e);
            }
        }
        persistPointersToGraph();
        log.info("Migration completed. {} goals written to graph database.", migrated);
    }

    public void syncFromGraph() {
        try {
            List<GoalEntity> graphGoals = goalGraphService.findAll();
            this.goalMap = graphGoals.stream().collect(Collectors.toMap(
                    GoalEntity::getId, g -> g, (a, b) -> a, LinkedHashMap::new
            ));

            Optional<GoalGraphState> stateOpt = goalGraphService.getState();

            // --- ROOT GOAL ---
            String inferredRoot = graphGoals.stream()
                    .filter(g -> g.getType() == GoalType.GOAL)
                    .filter(g -> g.getParentId() == null || g.getParentId().isBlank())
                    .findFirst()
                    .map(GoalEntity::getId)
                    .orElse(null);

            this.rootGoalId = stateOpt
                    .map(GoalGraphState::rootGoalId)
                    .filter(goalMap::containsKey)
                    .orElse(inferredRoot);

            // --- CURRENT SUBGOAL ---
            if (stateOpt.isPresent()) {
                // IMPORTANT: null means exhausted — preserve it
                String storedCurrent = stateOpt.get().currentSubGoalId();

                if (storedCurrent == null) {
                    this.currentSubGoalId = null;
                } else if (goalMap.containsKey(storedCurrent)
                        && goalMap.get(storedCurrent).getStatus() == GoalStatus.ACTIVE) {
                    this.currentSubGoalId = storedCurrent;
                } else {
                    // Pointer invalid → fallback
                    this.currentSubGoalId = resolveNextActiveLeaf(rootGoalId);
                }
            } else {
                // No SystemState at all → legacy fallback
                this.currentSubGoalId = resolveNextActiveLeaf(rootGoalId);
            }

            // Persist authoritative pointers back (normalizes state)
            persistPointersToGraph();

            // Snapshot to JSON (derived)
            this.rootNode = MAPPER.createObjectNode();
            rootNode.put("currentGoalId", rootGoalId);
            persist();

            log.info("Goal stack synchronized from Neo4j into JSON (graph-primary pointers applied)");

        } catch (Exception e) {
            log.error("Failed to sync goal stack from Neo4j", e);
        }
    }

    public void autoCompleteRootIfSatisfied() {
        GoalEntity root = getRootGoal();
        if (root == null) return;

        List<GoalEntity> descendants = goalMap.values().stream()
                .filter(g -> isDescendantOf(g, root.getId()))
                .toList();

        if (descendants.isEmpty()) return;

        boolean allTerminal = descendants.stream()
                .allMatch(g -> g.getStatus() == GoalStatus.COMPLETED || g.getStatus() == GoalStatus.GIVEN_UP);

        if (allTerminal) {
            long completedCount = descendants.stream().filter(g -> g.getStatus() == GoalStatus.COMPLETED).count();
            long givenUpCount = descendants.stream().filter(g -> g.getStatus() == GoalStatus.GIVEN_UP).count();

            if (givenUpCount > completedCount) {
                markGoalGivenUp(root.getId());
                log.warn("Root goal '{}' marked as GIVEN_UP ({} abandoned > {} completed descendants)",
                        root.getId(), givenUpCount, completedCount);
            } else {
                markGoalCompleted(root.getId());
                log.info("Root goal '{}' marked as COMPLETED ({} completed, {} abandoned descendants)",
                        root.getId(), completedCount, givenUpCount);
            }
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
                .filter(g -> Objects.equals(g.getParentId(), parentId) && g.getType().equals(GoalType.SUBGOAL) /* TODO: make sure the the SUBGOAL filter holds */)
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
        log.info("Pruning completed branches...");

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

        log.info("Pruned {} completed branches.", toPrune.size());
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

                // TODO (branch-closure semantics):
                // When a parent becomes current because all of its descendants are inactive,
                // we currently require an explicit evaluation step to decide whether the parent
                // should be marked COMPLETED/GIVEN_UP or whether replanning is needed.
                // Re-evaluate if additional automatic actions are needed here, e.g.:
                //  - trigger planningCallback for the parent (once) if it has no actionable children
                //  - auto-promote the parent only when it is structurally a pure container (no work itself)
                // Keep explicit promotion as default to preserve evaluation-driven completion.
                if (!hasActiveChildren && parent.getStatus() == GoalStatus.ACTIVE) {
                    return parent.getId(); // parent becomes currentSubGoal (requires explicit completion decision)
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

    public GoalProgressApplicationResult applyGoalProgress(String goalId, GoalProgressState progress) {
        if (goalId == null || progress == null) {
            log.warn("applyGoalProgress called with null goalId or progress. goalId={}, progress={}", goalId, progress);
            return new GoalProgressApplicationResult(goalId, false, currentSubGoalId, currentSubGoalId, false);
        }

        GoalEntity g = goalMap.get(goalId);
        if (g == null) {
            log.warn("applyGoalProgress: unknown goalId={}, skipping.", goalId);
            return new GoalProgressApplicationResult(goalId, false, currentSubGoalId, currentSubGoalId, false);
        }

        final String beforeCurrentSubGoalId = currentSubGoalId;
        final GoalStatus rootBefore = (rootGoalId != null && goalMap.get(rootGoalId) != null)
                ? goalMap.get(rootGoalId).getStatus()
                : null;

        // Ensure we have a metadata map
        Map<String, Object> metadata = g.getMetadata();
        if (metadata == null) {
            metadata = new HashMap<>();
            g.setMetadata(metadata);
        }

        metadata.put("lastProgressState", progress.name());
        metadata.put("lastUpdated", Instant.now().toString());

        boolean promoted = false;

        switch (progress) {
            case FULFILLED -> {
                if (goalId.equals(currentSubGoalId)) {
                    log.info("applyGoalProgress: FULFILLED for currentSubGoalId={} -> promoting branch.", goalId);

                    // promoteCurrentSubGoal() already marks current as completed and advances currentSubGoalId
                    promoteCurrentSubGoal();
                    promoted = true;

                    if (goalPlannerConfig.isSyncFromGraph()) {
                        refreshFromGraph(true);
                    }
                } else {
                    log.info("applyGoalProgress: FULFILLED for non-current goalId={} -> marking completed.", goalId);
                    markGoalCompleted(goalId);
                }
            }
            case GIVEN_UP -> {
                log.info("applyGoalProgress: GIVEN_UP for goalId={} -> marking given up.", goalId);
                markGoalGivenUp(goalId);
                if (goalId.equals(currentSubGoalId)) {
                    String next = findNextSiblingOrAncestor(goalId);
                    if (next == null) {
                        next = resolveNextActiveLeaf(rootGoalId);
                    }
                    currentSubGoalId = next;
                    if (currentSubGoalId != null) {
                        ensureAttemptIdsFor(currentSubGoalId);
                    }
                    log.info("applyGoalProgress: GIVEN_UP advanced currentSubGoalId from {} to {}", goalId, currentSubGoalId);
                    persistPointersToGraph();
                }
            }
            default -> {
                // PROGRESSING / STAGNATING / REGRESSING / NOT_EVALUATED:
                // metadata only
                log.debug("applyGoalProgress: progress={} for goalId={} recorded as metadata only.", progress, goalId);
            }
        }

        final String afterCurrentSubGoalId = currentSubGoalId;
        final GoalStatus rootAfter = (rootGoalId != null && goalMap.get(rootGoalId) != null)
                ? goalMap.get(rootGoalId).getStatus()
                : null;

        boolean rootAutoCompleted = (rootBefore == GoalStatus.ACTIVE && rootAfter == GoalStatus.COMPLETED);

        // Persist: don’t just save 'g' — promotion changes other nodes too.
        // Save the ones most likely affected.
        Set<String> idsToSave = new HashSet<>();
        idsToSave.add(goalId);
        if (beforeCurrentSubGoalId != null) idsToSave.add(beforeCurrentSubGoalId);
        if (afterCurrentSubGoalId != null) idsToSave.add(afterCurrentSubGoalId);
        if (rootGoalId != null) idsToSave.add(rootGoalId);

        for (String id : idsToSave) {
            GoalEntity e = goalMap.get(id);
            if (e != null) {
                goalGraphService.save(e);
            }
        }

        persist();

        // Important: “adopted” should mean “subgoal changed”
        boolean actuallyPromoted = promoted && (beforeCurrentSubGoalId == null || !beforeCurrentSubGoalId.equals(afterCurrentSubGoalId));

        return new GoalProgressApplicationResult(goalId, actuallyPromoted, beforeCurrentSubGoalId, afterCurrentSubGoalId, rootAutoCompleted);

    }

    private void refreshFromGraph(boolean refreshPointers) {
        List<GoalEntity> graphGoals = goalGraphService.findAll();
        this.goalMap = graphGoals.stream().collect(Collectors.toMap(
                GoalEntity::getId, g -> g, (a, b) -> a, LinkedHashMap::new
        ));

        if (!refreshPointers) {
            return;
        }

        goalGraphService.getState().ifPresent(state -> {
            if (state.rootGoalId() != null && goalMap.containsKey(state.rootGoalId())) {
                this.rootGoalId = state.rootGoalId();
            }

            // IMPORTANT: null currentSubGoalId means exhausted — preserve it
            String storedCurrent = state.currentSubGoalId();
            if (storedCurrent == null) {
                this.currentSubGoalId = null;
            } else if (goalMap.containsKey(storedCurrent)
                    && goalMap.get(storedCurrent).getStatus() == GoalStatus.ACTIVE) {
                this.currentSubGoalId = storedCurrent;
            } else {
                this.currentSubGoalId = resolveNextActiveLeaf(rootGoalId);
            }
        });
    }

    private void persistPointersToGraph() {
        if (rootGoalId == null || rootGoalId.isBlank()) return;
        // currentSubGoalId may be null when exhausted; that's still valuable state.
        goalGraphService.setState(rootGoalId, currentSubGoalId);
    }
}
