package com.walpro.autobuilder.goal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.walpro.autobuilder.analysis.coverage.CoverageRetrospectiveService;
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
import org.springframework.beans.factory.annotation.Autowired;
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

    /** Coverage retrospective — optional, wired by Spring if present. Null in tests that don't need it. */
    @Setter
    @Autowired(required = false)
    private CoverageRetrospectiveService coverageRetrospectiveService;

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

    /** T3-BP: threaded from applyGoalProgress(3-arg) into promoteCurrentSubGoal → markGoalCompleted. */
    private transient String pendingFulfillmentResolution = null;

    // T2-BW: monotonically increasing run ID stored on the root goal; advanced once per startup.
    // Every new subgoal is stamped with this value so the budget formula can exclude GIVEN_UP
    // goals from previous sessions without relying on wall-clock timestamps.
    private int currentPlanningRunId = 0;

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

        // T2-BW: advance planning run ID so this session's GIVEN_UP goals are tracked separately
        // from those in previous runs loaded from the graph.
        initPlanningRunId();

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

    /** Returns an integer counter stored in goal metadata by goalId, defaulting to 0 if absent or goal not found. */
    public int getGoalMetadataInt(String goalId, String key) {
        GoalEntity goal = goalId != null ? goalMap.get(goalId) : null;
        return goal != null ? getGoalMetadataInt(goal, key) : 0;
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
     * Records the file paths that were merged for a goal so the planner can surface
     * them as "potentially usable files" in the abandoned-goals backtrace.
     * Duplicate paths are silently ignored; only the filename is stored for readability.
     */
    @SuppressWarnings("unchecked")
    public void recordMergedFiles(String goalId, java.util.Collection<String> paths) {
        if (goalId == null || paths == null || paths.isEmpty()) return;
        GoalEntity g = goalMap.get(goalId);
        if (g == null) return;
        List<String> stored = (List<String>) g.getMetadata().computeIfAbsent("mergedFiles", k -> new java.util.ArrayList<>());
        for (String p : paths) {
            if (p == null) continue;
            // Store only the filename portion for readability in prompts
            String name = p.contains("/") ? p.substring(p.lastIndexOf('/') + 1) : p;
            if (!stored.contains(name)) stored.add(name);
        }
        saveGoalMetadata(g);
    }

    /** Convenience wrapper that also records a resolution rationale for GIVEN_UP or FULFILLED transitions. */
    public GoalProgressApplicationResult applyGoalProgress(String goalId, GoalProgressState progress, String rationale) {
        if (rationale != null && goalId != null) {
            GoalEntity g = goalMap.get(goalId);
            if (g != null) {
                if (progress == GoalProgressState.GIVEN_UP) {
                    g.setResolutionIfAbsent(rationale);
                    g.getMetadata().put("givenUpReason", rationale);
                } else if (progress == GoalProgressState.FULFILLED) {
                    pendingFulfillmentResolution = rationale;
                }
            }
        }
        try {
            return applyGoalProgress(goalId, progress);
        } finally {
            pendingFulfillmentResolution = null;
        }
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
     * T2-BW: Returns the count of unmet acceptance criteria for the given subgoal.
     * A criterion is unmet when it is absent from the pass-status map or explicitly false.
     * Returns 1 (minimum budget floor) when the goal is unknown or has no criteria.
     */
    public int countUnmetCriteria(String subGoalId) {
        GoalEntity goal = subGoalId != null ? goalMap.get(subGoalId) : null;
        if (goal == null) return 1;
        Object raw = goal.getMetadata().get("acceptanceCriteria");
        if (!(raw instanceof List<?> l) || l.isEmpty()) return 1;
        Map<Integer, Boolean> passStatus = getCriteriaPassStatus(goal);
        int unmet = 0;
        for (int i = 1; i <= l.size(); i++) {
            Boolean passing = passStatus.get(i);
            if (passing == null || !passing) unmet++;
        }
        return unmet;
    }

    /**
     * T3-BL: Returns true when the subgoal has a non-empty acceptance-criteria list AND every
     * criterion index is recorded as {@code true} in the persisted pass-status map.
     * Criteria-less subgoals always return false (handled by T2-CD instead).
     */
    public boolean allCriteriaDefinedAndBanked(String goalId) {
        GoalEntity goal = goalId != null ? goalMap.get(goalId) : null;
        if (goal == null) return false;
        Object raw = goal.getMetadata().get("acceptanceCriteria");
        if (!(raw instanceof List<?> l) || l.isEmpty()) return false;
        Map<Integer, Boolean> passStatus = getCriteriaPassStatus(goal);
        for (int i = 1; i <= l.size(); i++) {
            Boolean passing = passStatus.get(i);
            if (passing == null || !passing) return false;
        }
        return true;
    }

    /**
     * T1-CF: Returns true when all acceptance criteria for the given subgoal have been
     * cumulatively satisfied (every criterion in the pass-status map is true).
     * Falls back to {@code mergeCount > 0 && consecutiveSuppressedEvictions == 0} for
     * subgoals with no defined criteria.
     */
    public boolean areCriteriaCumulativelySatisfied(String goalId) {
        GoalEntity goal = goalId != null ? goalMap.get(goalId) : null;
        if (goal == null) return false;
        Object raw = goal.getMetadata().get("acceptanceCriteria");
        if (raw instanceof List<?> l && !l.isEmpty()) {
            Map<Integer, Boolean> passStatus = getCriteriaPassStatus(goal);
            for (int i = 1; i <= l.size(); i++) {
                Boolean passing = passStatus.get(i);
                if (passing == null || !passing) return false;
            }
            return true;
        }
        // No defined criteria — proxy: at least one merge occurred with no outstanding suppressed evictions
        int mergeCount = getGoalMetadataInt(goal, "mergeCount");
        int suppressedEvictions = getGoalMetadataInt(goal, "consecutiveSuppressedEvictions");
        return mergeCount > 0 && suppressedEvictions == 0;
    }

    // T2-BW: session-level counter for total analysis injections; resets on service restart.
    private int totalAnalysisInjectionsThisSession = 0;

    /** T2-BW: Increments the session-level analysis injection counter. Called by AnalysisAgent after each inject. */
    public void incrementTotalAnalysisInjections() {
        totalAnalysisInjectionsThisSession++;
    }

    /** T2-BW: Returns the session-level total analysis injections so far. */
    public int getTotalAnalysisInjectionsThisSession() {
        return totalAnalysisInjectionsThisSession;
    }

    /**
     * T2-BW: Reads the {@code planningRunId} from the root goal's metadata, increments it by 1,
     * writes it back, and sets {@link #currentPlanningRunId}. Called once from {@link #init()}.
     * Storing the counter on the root goal makes it persistent across restarts without requiring
     * a separate Neo4j node or timestamp comparison.
     */
    private void initPlanningRunId() {
        if (rootGoalId == null) return;
        GoalEntity root = goalMap.get(rootGoalId);
        if (root == null) return;
        int prev = getGoalMetadataInt(root, "planningRunId");
        currentPlanningRunId = prev + 1;
        root.getMetadata().put("planningRunId", currentPlanningRunId);
        goalGraphService.save(root);
        log.info("planning run ID advanced to {} (was {})", currentPlanningRunId, prev);
    }

    /**
     * T2-BW: Tier-2 global session budget.
     * {@code global_remaining = sum(unmetCriteria per ACTIVE subgoal)
     *                         - totalAnalysisInjectionsThisSession
     *                         - sum(allCriteria per GIVEN_UP subgoal)}
     * Returns {@code max(0, computed)}.
     */
    public int getGlobalAnalysisInjectionBudget() {
        int activeUnmet = goalMap.values().stream()
                .filter(g -> g.getStatus() == GoalStatus.ACTIVE && g.getType() == GoalType.SUBGOAL)
                .mapToInt(g -> countUnmetCriteria(g.getId()))
                .sum();
        // Only count GIVEN_UP goals from the current planning run — prior-session goals loaded
        // from the graph have a lower planningRunId and must not drain the budget (T2-BW fix).
        int givenUpTotal = goalMap.values().stream()
                .filter(g -> g.getStatus() == GoalStatus.GIVEN_UP)
                .filter(g -> {
                    Object run = g.getMetadata().get("planningRunId");
                    return run instanceof Number n && n.intValue() == currentPlanningRunId;
                })
                .mapToInt(g -> {
                    Object raw = g.getMetadata().get("acceptanceCriteria");
                    return raw instanceof List<?> l ? l.size() : 0;
                })
                .sum();
        return Math.max(0, activeUnmet - totalAnalysisInjectionsThisSession - givenUpTotal);
    }

    /**
     * T3-BK: Reads per-criterion consecutive-fail streak counts from goal metadata.
     * Keys are stored as Strings in JSON but returned as Integer → Integer.
     */
    @SuppressWarnings("unchecked")
    public Map<Integer, Integer> getCriteriaFailStreak(GoalEntity goal) {
        if (goal == null || goal.getMetadata() == null) return new HashMap<>();
        Object raw = goal.getMetadata().get("criteriaFailStreak");
        if (!(raw instanceof Map<?, ?> rawMap)) return new HashMap<>();
        Map<Integer, Integer> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            try {
                int key = Integer.parseInt(String.valueOf(entry.getKey()));
                if (entry.getValue() instanceof Number n) result.put(key, n.intValue());
            } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    /**
     * T3-BK: Persists per-criterion consecutive-fail streak counts to goal metadata.
     * Integer keys are converted to String for JSON serialization compatibility.
     */
    public void saveCriteriaFailStreak(String goalId, Map<Integer, Integer> streak) {
        GoalEntity goal = goalId != null ? goalMap.get(goalId) : null;
        if (goal == null) return;
        Map<String, Integer> serializable = new LinkedHashMap<>();
        streak.forEach((k, v) -> serializable.put(String.valueOf(k), v));
        goal.getMetadata().put("criteriaFailStreak", serializable);
        saveGoalMetadata(goal);
    }

    /**
     * T3-BK: Injects one micro-subgoal per unmet criterion as siblings of the graduating subgoal.
     * Each micro-subgoal gets a single-criterion {@code acceptanceCriteria} block so evolution
     * can focus exclusively on that one remaining requirement.
     */
    public void injectGraduationMicroSubgoals(String graduatingSubGoalId, List<Integer> unmetIndices,
                                              List<String> allCriteria) {
        if (unmetIndices == null || unmetIndices.isEmpty() || allCriteria == null) return;
        GoalEntity graduating = goalMap.get(graduatingSubGoalId);
        if (graduating == null) return;
        String parentId = graduating.getParentId() != null ? graduating.getParentId() : rootGoalId;

        for (int idx : unmetIndices) {
            if (idx < 1 || idx > allCriteria.size()) continue;
            String criterionText = allCriteria.get(idx - 1);
            String microGoalId = "micro-" + java.util.UUID.randomUUID();
            String description = "Satisfy remaining criterion: " + criterionText;
            addSubGoal(microGoalId, description, false, parentId, "graduation", null);
            setAcceptanceCriteria(microGoalId, List.of(criterionText));
        }
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
            } else if (root != null && root.getStatus() == GoalStatus.GIVEN_UP) {
                // T1-CB: root was already given up by T1-BZ — do NOT clobber back to COMPLETED.
                // Clobbering overwrites the GIVEN_UP decision, causing T1-BH to see COMPLETED and
                // emit GOAL_FULFILLED instead of GOAL_EXHAUSTED.
                log.info("currentSubGoalId is null — root goal is GIVEN_UP (exhausted), loop should halt.");
            } else {
                // T1-DB: active replacement subgoals may exist under GIVEN_UP parents (T1-CP
                // nesting). resolveNextActiveLeaf skips GIVEN_UP nodes so it won't find them;
                // getRemainingGoals() correctly returns fresh replacements via the isStaleOrphan
                // filter. Advance the pointer before falling through to completion logic.
                List<GoalEntity> remaining = getRemainingGoals();
                if (!remaining.isEmpty()) {
                    String recovered = remaining.get(0).getId();
                    log.info("T1-DB: recovered null currentSubGoalId — advancing to active subgoal [{}]", recovered);
                    currentSubGoalId = recovered;
                    ensureAttemptIdsFor(currentSubGoalId);
                    return goalMap.get(currentSubGoalId);
                }
                log.warn("currentSubGoalId is null — no active subgoals remain; replanning may be needed.");
                // T1-CN: if all direct children are already terminal and REPLAN_REQUESTED was NOT
                // set by a dedup-blocked planner injection this cycle, the signal is stale — the
                // backlog is empty because all work finished, not because dedup blocked injection.
                // Exception: source "planInto-deduped" means T1-BK just fired; respect that guard.
                if (root != null && allDirectChildrenTerminal()) {
                    Signal replanSignal = currentCycleContext != null
                            ? currentCycleContext.getSignal(BasicSignals.REPLAN_REQUESTED) : null;
                    boolean dedupBlockedThisCycle = replanSignal != null
                            && "planInto-deduped".equals(replanSignal.source());
                    if (!dedupBlockedThisCycle) {
                        log.warn("T1-CN: all direct children terminal and REPLAN_REQUESTED is not dedup-sourced — bypassing replanPending guard, delegating to autoCompleteRootIfSatisfied.");
                        autoCompleteRootIfSatisfied();
                        return null;
                    }
                    log.debug("T1-CN: all direct children terminal but REPLAN_REQUESTED source is planInto-deduped — respecting T1-BK guard.");
                }
                // T1-BK: do not auto-complete root if REPLAN_REQUESTED is pending — the null pointer
                // is due to deduplication blocking injection, not genuine completion.
                boolean replanPending = currentCycleContext != null
                        && currentCycleContext.hasSignal(BasicSignals.REPLAN_REQUESTED);
                if (root != null && !replanPending) {
                    // T1-CJ: delegate to autoCompleteRootIfSatisfied() so that a majority-GIVEN_UP
                    // set of subgoals escalates to GIVEN_UP root + GOAL_EXHAUSTED rather than
                    // unconditionally marking COMPLETED (which triggered GOAL_FULFILLED on scope thrash).
                    log.warn("no active subgoals remain; delegating root completion decision to autoCompleteRootIfSatisfied.");
                    autoCompleteRootIfSatisfied();
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
                .filter(g -> !isStaleOrphan(g))
                .toList();
    }

    /**
     * T1-CP: a stale orphan is an ACTIVE subgoal whose parent is GIVEN_UP and whose
     * {@code planningRunId} is older than the current run. Fresh T1-CP replacement goals
     * injected under a GIVEN_UP parent share the current {@link #currentPlanningRunId}
     * and are therefore NOT orphans — they are the intended new work.
     * <p>
     * In practice the cascade in {@link #markGoalGivenUp} eliminates any pre-existing ACTIVE
     * children when a parent is given up, so this filter is a safety net for edge cases.
     */
    private boolean isStaleOrphan(GoalEntity g) {
        String parentId = g.getParentId();
        if (parentId == null || parentId.equals(rootGoalId)) return false;
        GoalEntity parent = goalMap.get(parentId);
        if (parent == null || parent.getStatus() != GoalStatus.GIVEN_UP) return false;
        Object run = g.getMetadata().get("planningRunId");
        return !(run instanceof Number n && n.intValue() == currentPlanningRunId);
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

        markGoalCompleted(completedId, pendingFulfillmentResolution);

        String next = findNextSiblingOrAncestor(completedId);

        // If no next and parent needs planning, try that
        boolean plannerWasCalled = false;
        int plannerInjectedCount = 0;
        if (next == null && shouldTriggerPlanner(completedId)) {
            String parentId = goalMap.get(completedId).getParentId();
            if (parentId != null) {
                // Strip any non-planner MetaIntention carried from a prior agent step (e.g. evolution).
                // A stale MetaIntention like "produce a mutation" overrides the planner NOTE that says
                // "respond with 'Goal complete' when all features are covered", causing the LLM to add
                // spurious subgoals and triggering an infinite planning loop.
                CycleContextData plannerCtx = currentCycleContext;
                if (plannerCtx != null && plannerCtx.metaIntention() != null
                        && !"planner".equals(plannerCtx.metaIntention().role())) {
                    plannerCtx = plannerCtx.withMetaIntention(null);
                }
                plannerInjectedCount = planningCallback.planInto(plannerCtx, parentId);
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
            log.warn("planInto returned 0 injected subgoals — skipping autoCompleteRootIfSatisfied and raising REPLAN_REQUESTED");
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
        // T2-BW: seed run ID so subgoals added after reset share run 1 and GIVEN_UP goals
        // created in the same test session are correctly counted by the budget formula.
        currentPlanningRunId = 1;
        g.getMetadata().put("planningRunId", currentPlanningRunId);
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
        // T1-CP: allow injection under a GIVEN_UP parent so that replacement subgoals
        // are nested under the stagnated subgoal rather than promoted to root level.
        // Promoting to root inflates the root's direct-child denominator and makes the
        // completion ratio unreachable when multiple replan rounds occur.
        if (parent.getStatus() == GoalStatus.GIVEN_UP) {
            log.warn("addSubGoal: parent [{}] is GIVEN_UP — allowing fresh injection under dead parent (T1-CP replacement nesting)", parentId);
        }

        GoalEntity subGoal = new GoalEntity();
        subGoal.setId(id);
        subGoal.setDescription(description);
        subGoal.setRole(role != null ? role : "planner");
        subGoal.setType(GoalType.SUBGOAL);
        subGoal.setParentId(parentId);
        subGoal.setStatus(GoalStatus.ACTIVE);
        subGoal.setCreated(Instant.now());
        Map<String, Object> subGoalMeta = new HashMap<>();
        // T2-BW: stamp the current planning run so the global budget can exclude goals
        // created in prior sessions when they are later given up.
        subGoalMeta.put("planningRunId", currentPlanningRunId);
        subGoal.setMetadata(subGoalMeta);
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

    /**
     * T3-BH: stores the plan rationale in the parent goal's metadata and re-persists.
     * Called by PlannerAgent after injecting a batch of subgoals when PlanRationale: was emitted.
     */
    public void setPlanRationale(String goalId, String rationale) {
        if (rationale == null || rationale.isBlank()) return;
        GoalEntity g = goalMap.get(goalId);
        if (g == null) return;
        g.getMetadata().put("planRationale", rationale);
        persist();
        try {
            goalGraphService.save(g);
        } catch (HttpServerErrorException e) {
            log.debug("Neo4j plan-rationale update failed for goal {} (ignoring): {}", goalId, e.getMessage());
        }
    }

    /**
     * T3-BH: reads the plan rationale from a goal's metadata.
     * Returns null when no rationale has been stored.
     */
    public String getPlanRationale(GoalEntity goal) {
        if (goal == null || goal.getMetadata() == null) return null;
        Object raw = goal.getMetadata().get("planRationale");
        return raw instanceof String s ? s : null;
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
        markGoalCompleted(id, null);
    }

    public void markGoalCompleted(String id, String resolution) {
        // T1-DD: refuse to mark a node COMPLETED while any direct descendant is ACTIVE.
        // Otherwise children become unreachable — getRemainingGoals filters them out via the
        // now-COMPLETED parent and resolveNextActiveLeaf cannot re-enter through a terminal
        // ancestor — so the dead branch silently drops out of the active backlog and the
        // first-wave completion ratio drifts off the actual tree state.
        GoalEntity g = goalMap.get(id);
        if (g != null) {
            long activeChildren = getSubGoals(id).stream()
                    .filter(c -> c.getStatus() == GoalStatus.ACTIVE)
                    .count();
            if (activeChildren > 0) {
                String activeLeaf = resolveNextActiveLeaf(id);
                log.warn("T1-DD: refusing to mark [{}] COMPLETED — {} active descendants. Re-pointing currentSubGoalId to next active leaf {}.",
                        id, activeChildren, activeLeaf);
                if (activeLeaf != null && !activeLeaf.equals(currentSubGoalId)) {
                    currentSubGoalId = activeLeaf;
                    ensureAttemptIdsFor(currentSubGoalId);
                    persistPointersToGraph();
                }
                return;
            }
        }
        markGoalStatus(id, gx -> {
            gx.setResolutionIfAbsent(resolution);
            gx.markCompleted();
            return null;
        });
        if (resolution != null) {
            goalGraphService.markCompleted(id, resolution);
        } else {
            goalGraphService.markCompleted(id);
        }
    }

    /**
     * Reverses a terminal ({@code COMPLETED} or {@code GIVEN_UP}) goal back to {@code ACTIVE}.
     * Clears the completed/givenUp timestamps and the resolution so downstream logic no longer
     * treats the goal as terminal. Cascades up to any terminal ancestors (including the root)
     * so the reactivated node is actually reachable by the loop — a subgoal under a GIVEN_UP
     * parent would otherwise still be effectively dead.
     *
     * <p>No-op when the goal is already ACTIVE. Returns the ordered list of goal ids that were
     * flipped (target first, then ancestors in parent-walk order) — useful for the UI and for
     * tests. Writes each affected goal to Neo4j and re-persists the JSON snapshot.
     */
    public List<String> reactivateGoal(String id) {
        GoalEntity g = goalMap.get(id);
        if (g == null) {
            log.warn("reactivateGoal: unknown goalId={}", id);
            return List.of();
        }

        List<String> flipped = new ArrayList<>();
        GoalEntity cursor = g;
        int guard = 0;
        while (cursor != null && guard++ < 64) {
            if (cursor.getStatus() == GoalStatus.COMPLETED || cursor.getStatus() == GoalStatus.GIVEN_UP) {
                cursor.setStatus(GoalStatus.ACTIVE);
                cursor.setCompleted(null);
                cursor.setGivenUp(null);
                cursor.setResolution(null);
                flipped.add(cursor.getId());
                try {
                    goalGraphService.save(cursor);
                } catch (HttpServerErrorException e) {
                    log.debug("Neo4j reactivate save failed for {} (ignoring): {}", cursor.getId(), e.getMessage());
                }
            }
            String parentId = cursor.getParentId();
            if (parentId == null || parentId.isBlank()) break;
            cursor = goalMap.get(parentId);
        }

        if (!flipped.isEmpty()) {
            persist();
            log.info("reactivateGoal: flipped {} goal(s) back to ACTIVE (target={}, cascade={})",
                    flipped.size(), id, flipped);
        } else {
            log.info("reactivateGoal: goalId={} already ACTIVE — nothing to flip", id);
        }
        return flipped;
    }

    public void markGoalGivenUp(String id) {
        markGoalGivenUp(id, null);
    }

    public void markGoalGivenUp(String id, String resolution) {
        markGoalStatus(id, g -> {
            g.setResolutionIfAbsent(resolution);
            g.markGivenUp();
            return null;
        });

        GoalEntity g = goalMap.get(id);
        if (g != null) {
            goalGraphService.save(g); // properties-only update of status + resolution
            // T1-CA: cascade GIVEN_UP to all direct ACTIVE children unconditionally so that
            // pruning a parent atomically removes its entire subtree from the active backlog.
            for (GoalEntity child : getSubGoals(id)) {
                if (child.getStatus() == GoalStatus.ACTIVE) {
                    log.info("markGoalGivenUp cascade: goalId={} marked GIVEN_UP (parent={})", child.getId(), id);
                    markGoalGivenUp(child.getId(), "cascade from parent: " + (resolution != null ? resolution : "given up"));
                }
            }
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
            log.warn("Per-parent exhaustion: {}/{} children GIVEN_UP (threshold={}) with no completions under parentId={}. Marking parent GIVEN_UP.",
                    givenUpCount, siblings.size(), effectiveThreshold, parentId);
            String exhaustionRationale = "per-parent exhaustion: " + givenUpCount + "/" + siblings.size()
                    + " children given up (threshold=" + effectiveThreshold + ")";
            markGoalGivenUp(parentId, exhaustionRationale); // cascades to remaining ACTIVE children

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

    private boolean allDirectChildrenTerminal() {
        if (rootGoalId == null) return false;
        List<GoalEntity> directChildren = getSubGoals(rootGoalId);
        if (directChildren.isEmpty()) return false;
        return directChildren.stream()
                .allMatch(g -> g.getStatus() == GoalStatus.COMPLETED || g.getStatus() == GoalStatus.GIVEN_UP);
    }

    public void autoCompleteRootIfSatisfied() {
        GoalEntity root = getRootGoal();
        if (root == null) return;

        // T1-BZ: check direct children only (not all descendants) to avoid inflated counts
        // from nested subgoal trees distorting the completion ratio.
        List<GoalEntity> directChildren = getSubGoals(root.getId());
        if (directChildren.isEmpty()) return;

        boolean allTerminal = directChildren.stream()
                .allMatch(g -> g.getStatus() == GoalStatus.COMPLETED || g.getStatus() == GoalStatus.GIVEN_UP);

        if (allTerminal) {
            // T2-CH: even when all first-wave direct children are terminal, some GIVEN_UP
            // children may have ACTIVE replacement descendants injected by the planner
            // (T1-CP replacement nesting). Firing auto-completion now would orphan that
            // queued work — the replacement subgoals are ACTIVE but invisible to the first-wave
            // ratio because they sit under a GIVEN_UP parent, not directly under root.
            // Defer until ALL descendants are terminal so replacements get a chance to run.
            boolean hasActiveReplacement = directChildren.stream()
                    .filter(g -> g.getStatus() == GoalStatus.GIVEN_UP)
                    .anyMatch(g -> hasActiveDescendantsOf(g.getId()));
            if (hasActiveReplacement) {
                log.warn("T2-CH: deferring auto-completion — GIVEN_UP first-wave children have active replacement subgoals. Waiting for replacements to complete.");
                return;
            }

            // T1-CQ: compute the ratio using only first-wave direct children (those with the
            // minimum planningRunId). Replacement subgoals injected under GIVEN_UP direct children
            // by later stagnation-replan rounds can also appear as direct children of root in edge
            // cases (e.g. if T1-CP routing is bypassed). Each such wave grows the denominator
            // without guaranteeing more completions, making the threshold unreachable.
            // Using minPlanningRunId isolates the original planning wave for the ratio.
            int minRunId = directChildren.stream()
                    .mapToInt(g -> {
                        Object run = g.getMetadata().get("planningRunId");
                        return run instanceof Number n ? n.intValue() : 0;
                    })
                    .min()
                    .orElse(0);
            List<GoalEntity> firstWaveChildren = directChildren.stream()
                    .filter(g -> {
                        Object run = g.getMetadata().get("planningRunId");
                        int runId = run instanceof Number n ? n.intValue() : 0;
                        return runId == minRunId;
                    })
                    .toList();

            long completedCount = firstWaveChildren.stream().filter(g -> g.getStatus() == GoalStatus.COMPLETED).count();
            long total = firstWaveChildren.size();
            double completionRatio = (double) completedCount / total;
            double threshold = goalPlannerConfig.getMinCompletionRatio();

            if (completionRatio > threshold) {
                String completedRationale = "root goal satisfied: " + completedCount + "/" + total
                        + " first-wave children completed (ratio=" + String.format("%.2f", completionRatio) + ")";
                markGoalCompleted(root.getId(), completedRationale);
                log.info("T1-BZ/T1-CQ: Root goal '{}' marked as COMPLETED ({}/{} first-wave children completed, ratio={} > threshold={}, totalDirectChildren={})",
                        root.getId(), completedCount, total, String.format("%.2f", completionRatio), String.format("%.2f", threshold), directChildren.size());
            } else {
                String givenUpRationale = "root goal exhausted: " + completedCount + "/" + total
                        + " first-wave children completed (ratio=" + String.format("%.2f", completionRatio) + ")";
                markGoalGivenUp(root.getId(), givenUpRationale);
                log.warn("T1-BZ/T1-CQ: Root goal '{}' marked as GIVEN_UP ({}/{} first-wave children completed, ratio={} <= threshold={}, totalDirectChildren={}) — escalating to GOAL_EXHAUSTED",
                        root.getId(), completedCount, total, String.format("%.2f", completionRatio), String.format("%.2f", threshold), directChildren.size());
                if (currentCycleContext != null) {
                    currentCycleContext = currentCycleContext.withSignal(
                            Signal.of(BasicSignals.GOAL_EXHAUSTED, 1.0, 0.0, "root-given-up"));
                }
            }
        }
    }

    /**
     * Manual coverage review — called via REST, not automatically.
     * Compares the root goal description against all terminal subgoals and injects any
     * missing features as new first-wave subgoals (planningRunId=0).
     * If gaps are found and root is COMPLETED/GIVEN_UP, resets it to ACTIVE so the loop
     * can resume. Emits REPLAN_REQUESTED to kick the planner.
     *
     * @return list of injected feature descriptions; empty if no gaps found or no root goal
     */
    public List<String> triggerManualCoverageReview() {
        GoalEntity root = getRootGoal();
        if (root == null) {
            log.warn("coverage-review: no root goal defined — nothing to review");
            return List.of();
        }
        if (coverageRetrospectiveService == null) {
            log.warn("coverage-review: CoverageRetrospectiveService not wired — cannot run");
            return List.of();
        }

        List<String> missing = coverageRetrospectiveService.findMissingFeatures(
                root.getDescription(), getAllGoals());

        if (missing.isEmpty()) {
            log.info("coverage-review: no gaps found — root goal appears fully covered");
            return List.of();
        }

        log.warn("coverage-review: found {} missing feature(s) — injecting as first-wave subgoals", missing.size());

        // Reactivate root if it reached a terminal state so the loop can resume
        if (root.getStatus() == GoalStatus.COMPLETED || root.getStatus() == GoalStatus.GIVEN_UP) {
            root.setStatus(GoalStatus.ACTIVE);
            root.setCompleted(null);
            root.setGivenUp(null);
            log.info("coverage-review: root goal reset to ACTIVE to allow new subgoals to be worked on");
        }

        // Inject with planningRunId=0 so they become the new first-wave minimum
        int savedRunId = this.currentPlanningRunId;
        this.currentPlanningRunId = 0;
        try {
            addSubGoalsBatch(missing, rootGoalId, true, "coverage-retro", null);
        } finally {
            this.currentPlanningRunId = savedRunId;
        }

        if (currentCycleContext != null) {
            currentCycleContext = currentCycleContext.withSignal(
                    Signal.of(BasicSignals.REPLAN_REQUESTED, 1.0, 0.2, "coverage-retro"));
        }

        return missing;
    }

    /**
     * T2-CH: returns true when any ACTIVE goal in the map is a descendant of parentId.
     * Used to detect T1-CP replacement subgoals injected under GIVEN_UP parents so that
     * autoCompleteRootIfSatisfied can defer firing until those replacements are worked on.
     */
    private boolean hasActiveDescendantsOf(String parentId) {
        return goalMap.values().stream()
                .anyMatch(g -> g.getStatus() == GoalStatus.ACTIVE && isDescendantOf(g, parentId));
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

    /**
     * T1-DD tertiary: walks every COMPLETED node and warns if any direct child is ACTIVE.
     * The primary guard inside {@link #markGoalCompleted(String, String)} prevents new
     * violations; this hook surfaces regressions if a future code path bypasses the guard.
     * Returns the count of violating nodes (0 in a healthy tree).
     */
    public int assertGoalTreeIntegrity() {
        int violations = 0;
        for (GoalEntity g : goalMap.values()) {
            if (g.getStatus() != GoalStatus.COMPLETED) continue;
            long activeChildren = getSubGoals(g.getId()).stream()
                    .filter(c -> c.getStatus() == GoalStatus.ACTIVE)
                    .count();
            if (activeChildren > 0) {
                log.warn("T1-DD: tree integrity violation goalId={} status=COMPLETED activeChildren={}",
                        g.getId(), activeChildren);
                violations++;
            }
        }
        return violations;
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
                    // T1-DD: the guard inside markGoalCompleted may have refused the flip when
                    // active descendants exist. Log the skip so the flow is traceable from this
                    // call-site (the warning fired inside markGoalCompleted is generic).
                    GoalEntity post = goalMap.get(goalId);
                    if (post != null && post.getStatus() == GoalStatus.ACTIVE) {
                        log.info("T1-DD: skipped — applyGoalProgress non-current FULFILLED for goalId={} kept ACTIVE due to active descendants.", goalId);
                    }
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
