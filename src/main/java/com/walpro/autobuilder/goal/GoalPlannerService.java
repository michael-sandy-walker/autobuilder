package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.walpro.autobuilder.analysis.coverage.CoverageRetrospectiveService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
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
@StateHolder(value = StateBucket.ALREADY_PERSISTED,
        note = "the goal tree persists to meta/GoalStack.json and the graph, and its status history "
                + "to the T4-DQ transition log")
public class GoalPlannerService {

    private static final String GOAL_PATH = "meta/GoalStack.json";
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private static final String META_ATTEMPT_ID = "activeAttemptId";
    private static final String META_PARENT_ATTEMPT_ID = "parentAttemptId";

    /**
     * T2-FL: how many declined-leaf reasons one promotion sweep carries onto its behaviour event.
     * A sample, not a census -- the counts on the event are the measurement and they are exact;
     * these are there so a reader learns WHAT the sweep objected to without opening an archived
     * log, and a sweep facing thirty leaves would otherwise put thirty prose lines into every
     * cycle's trace.
     */
    private static final int PROMOTION_REASON_SAMPLE_CAP = 5;

    private static final int DEFAULT_MAX_NEW_SUBGOALS = 8;
    private static final int DEFAULT_MIN_CHARS = 12;

    private final FileService fileService;
    private final GoalGraphService goalGraphService;
    private final GoalPlannerConfigProperties goalPlannerConfig;

    /** Coverage retrospective — optional, wired by Spring if present. Null in tests that don't need it. */
    @Setter
    @Autowired(required = false)
    private CoverageRetrospectiveService coverageRetrospectiveService;

    /**
     * T4-DT: the same exercisability question the probe asks, used here only to give a refused
     * closure's work item a done-when the probe can re-check.
     */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.build.ExercisableCriterionQuery exercisableCriterionQuery;

    /** T1-DE: similarity dedup for coverage-review — optional, wired by Spring if present. */
    @Setter
    @Autowired(required = false)
    private GoalSimilarityService goalSimilarityService;

    /** T4-AJ: semantic equivalence reviewer for orphan adoption — optional, wired by Spring if present. */
    @Setter
    @Autowired(required = false)
    private GoalAdoptionService goalAdoptionService;

    /** T4-BP: code-aware feature verification for the T2-EA coverage gate — optional, wired by Spring if present. */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService criteriaVerificationService;

    /** Spec-grounding of authored criteria (run 19) — optional, wired by Spring if present. */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.agent.evaluation.CriteriaGroundingService criteriaGroundingService;

    /** T4-AN-8: runnable-artifact gate at the completion edge — optional, wired by Spring if present. */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.build.PackageGate packageGate;

    /** T4-AN-2: test-run gate at the completion edge — optional, wired by Spring if present. */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.build.TestRunGate testRunGate;

    /** T4-CQ: observes what an unasserted entry point returns; optional in narrow tests. */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.build.ValueObservationProbe valueObservationProbe;

    /**
     * T4-CQ: writes the seed's obligations file, markers included, when a root goal is set and the
     * target has none -- optional, present only where {@code autobuilder.obligations.generate} is on.
     */
    @Setter
    @Autowired(required = false)
    private SeedObligationsGenerator seedObligationsGenerator;

    /** T4-BV: execution-observation boot gate at the completion edge — optional, wired by Spring if present. */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.build.BootGate bootGate;

    /**
     * T4-BN-3 slice 2: deterministic actuator for the "removing unrequested broken tests is an
     * acceptable resolution" hint — optional, wired by Spring if present. Behind
     * {@code autobuilder.build.quarantine-broken-tests}.
     */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.build.BrokenTestQuarantine brokenTestQuarantine;

    /**
     * T2-EI (D2): deterministic endpoint-surface query at the completion edge — optional, wired
     * by Spring if present. Behind {@code autobuilder.goal.structure-query-enabled}.
     */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery endpointSurfaceQuery;

    /**
     * T2-EV: the specification's stated per-endpoint response behaviours — optional. Absent, a
     * coverage-injected endpoint keeps only its mapping-exists done-when, i.e. exactly today's
     * behaviour; present, it also carries what the spec says that endpoint must answer.
     */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.analysis.structure.SpecEndpointBehaviourQuery specBehaviourQuery;

    /**
     * T4-BT: criteria-traceability required/extra classification — optional, wired by Spring if
     * present. Behind {@code autobuilder.goal.spec-traceability-enabled} (default true).
     */
    @Setter
    @Autowired(required = false)
    private SpecTraceability specTraceability;

    /**
     * T4-BN-3: deterministic test-dependency fix at the package gate — optional, wired by Spring
     * if present. Its original consult sits behind the reverted T4-BN-2 test-compile probe
     * (main-only since `0c98742`), so missing-test-dep breaks first surface at the completion
     * edge now; see the package-gate block in {@link #autoCompleteRootIfSatisfied()}.
     */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.build.inject.TestDependencyEnsurer testDependencyEnsurer;

    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    /**
     * T4-DQ blocker 1: the append-only goal-status history. Optional so the many unit tests that
     * construct this service by hand need no stub; without it goal state is exactly as
     * un-rewindable as it was before, which is the honest degradation.
     */
    @Setter
    @Autowired(required = false)
    private GoalTransitionLog goalTransitionLog;

    /**
     * T4-DQ: true only while {@link #restoreGoalStateAsOf} is writing, so the chokepoint's own
     * diff does not double-append the same reversion the rewind already recorded as REWOUND.
     */
    private boolean rewindInProgress;

    private Map<String, GoalEntity> goalMap = new LinkedHashMap<>();

    // T5-I: shadow of each goal's last persisted status, diffed in persist() to emit
    // goal_status_changed from ONE home regardless of which mutation path flipped the status.
    private final Map<String, GoalStatus> lastObservedStatus = new HashMap<>();

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

    /**
     * T2-FB: drops a subgoal's banked criteria after its closure claim was refuted, so
     * {@link #allCriteriaDefinedAndBanked} reads false and T3-BL's early exit yields instead of
     * re-making the refuted claim. Returns true when a bank was actually present and removed —
     * the caller logs on that, so a run shows the livelock being broken rather than a line that
     * fires whether or not it did anything.
     */
    boolean clearCriteriaBankAfterRefutation(String goalId) {
        return clearCriteriaBankAfterRefutation(goalId, List.of());
    }

    /**
     * T4-CU: as above, dropping only the banked reads for {@code refutedCriteria} when the
     * verifier could say which criteria were refuted.
     *
     * <p>Dropping the WHOLE map was deliberate and is still the fallback, but the reason it was
     * deliberate has gone: the refutation used to arrive as prose that did not name a criterion,
     * so matching it back to one would have been code consuming prose for control flow. The port
     * now returns the criteria themselves, so the narrow drop is available without that coupling.
     * Re-verifying a criterion that did hold costs a re-check; trusting one inside a refuted claim
     * is how false completions happen — so an UNATTRIBUTED refutation still clears everything.
     */
    boolean clearCriteriaBankAfterRefutation(String goalId, List<String> refutedCriteria) {
        GoalEntity goal = goalId != null ? goalMap.get(goalId) : null;
        if (goal == null || goal.getMetadata() == null) return false;
        Object banks = goal.getMetadata().get("criteriaPassStatus");
        if (!(banks instanceof Map<?, ?> m) || m.isEmpty()) return false;
        Map<Integer, Boolean> narrowed = narrowBankDrop(goal, refutedCriteria);
        if (narrowed != null) {
            saveCriteriaPassStatus(goalId, narrowed);
            log.info("T2-FB: cleared {} of {} banked criterion read(s) for {} — the refutation named"
                            + " which criteria it contradicted, so the rest stand",
                    m.size() - narrowed.size(), m.size(), goalId);
            return true;
        }
        goal.getMetadata().remove("criteriaPassStatus");
        saveGoalMetadata(goal);
        log.info("T2-FB: cleared {} banked criterion read(s) for {} — the claim they supported was"
                + " refuted, so they must be re-earned before it can be made again", m.size(), goalId);
        return true;
    }

    /**
     * The bank that survives a refutation naming {@code refutedCriteria}, or null when the drop
     * cannot be narrowed and the whole map must go.
     *
     * <p>Returns null rather than an empty map for "do not narrow", because an empty map is a
     * legitimate narrowing result (every banked read was refuted) and must not be confused with
     * a refusal to attribute. The bank is keyed by the criterion's 1-based position in
     * {@code acceptanceCriteria}, so a criterion whose text is no longer in that list cannot be
     * located and forces the wide drop.
     */
    private Map<Integer, Boolean> narrowBankDrop(GoalEntity goal, List<String> refutedCriteria) {
        if (refutedCriteria == null || refutedCriteria.isEmpty()) return null;
        Object raw = goal.getMetadata().get("acceptanceCriteria");
        if (!(raw instanceof List<?> declared) || declared.isEmpty()) return null;
        Set<Integer> drop = new java.util.LinkedHashSet<>();
        for (String refuted : refutedCriteria) {
            if (refuted == null) continue;
            int at = -1;
            for (int i = 0; i < declared.size(); i++) {
                Object d = declared.get(i);
                if (d != null && refuted.equals(d.toString())) { at = i + 1; break; }
            }
            if (at < 0) return null;   // cannot place it — do not guess, drop everything
            drop.add(at);
        }
        // The bank's keys are Strings in metadata and Integers in the API; getCriteriaPassStatus
        // is the one home for that conversion and this must not become a second one.
        Map<Integer, Boolean> kept = new LinkedHashMap<>();
        getCriteriaPassStatus(goal).forEach((key, value) -> {
            if (!drop.contains(key)) kept.put(key, value);
        });
        return kept;
    }

    /** T4-CC: stores a string flag in goal metadata and persists the change. */
    public void setGoalMetadataString(String goalId, String key, String value) {
        GoalEntity goal = goalId != null ? goalMap.get(goalId) : null;
        if (goal == null) return;
        goal.getMetadata().put(key, value);
        saveGoalMetadata(goal);
    }

    /**
     * T4-DW: stores the criteria a boot-gate work item was raised from, so the retirement can
     * require those same criteria to be re-probed and pass rather than accepting the absence of a
     * contradiction (which a sweep that judged nothing also produces).
     */
    public void setGoalMetadataStrings(String goalId, String key, List<String> values) {
        GoalEntity goal = goalId != null ? goalMap.get(goalId) : null;
        if (goal == null || values == null || values.isEmpty()) return;
        goal.getMetadata().put(key, List.copyOf(values));
        saveGoalMetadata(goal);
    }

    /** T4-CC: reads a string flag from goal metadata (null when absent or goal not found). */
    public String getGoalMetadataString(String goalId, String key) {
        GoalEntity goal = goalId != null ? goalMap.get(goalId) : null;
        Object val = goal != null ? goal.getMetadata().get(key) : null;
        return val != null ? val.toString() : null;
    }

    /** T2-EI slice 1b: resets an integer counter in goal metadata to 0 (e.g. on a granted retry). */
    public void resetGoalMetadataInt(String goalId, String key) {
        GoalEntity goal = goalId != null ? goalMap.get(goalId) : null;
        if (goal == null) return;
        goal.getMetadata().put(key, 0);
        saveGoalMetadata(goal);
    }

    /** T2-EI: true when the subgoal is an EXTRA-origin augmentation (see {@link #SUBGOAL_ORIGIN_KEY}). */
    public boolean isExtraOriginSubgoal(String goalId) {
        return isExtraForClosure(goalId != null ? goalMap.get(goalId) : null);
    }

    /**
     * T4-BT: required/extra with criteria-traceability observations beating the static origin stamp.
     * Spec observations (test-flavored on a no-test spec → EXTRA; names a spec-declared endpoint →
     * REQUIRED) wins; no observation falls back to the creation-time origin stamp. One home for
     * every consumer of the required/extra decision (closure absorption, retry budget,
     * divide-on-exhaustion) so they cannot drift apart.
     */
    private boolean isExtraForClosure(GoalEntity g) {
        if (g == null) return false;
        if (goalPlannerConfig != null && goalPlannerConfig.isSpecTraceabilityEnabled()
                && specTraceability != null) {
            GoalEntity root = getRootGoal();
            SpecTraceability.Binding binding = specTraceability.classify(
                    root != null ? root.getDescription() : null, g);
            if (binding == SpecTraceability.Binding.EXTRA) {
                if (!isExtraOrigin(g)) {
                    log.info("T4-BT: subgoal [{}] re-binned EXTRA by spec traceability (origin stamp said required): {}",
                            g.getId(), g.getDescription());
                }
                return true;
            }
            if (binding == SpecTraceability.Binding.REQUIRED) {
                if (isExtraOrigin(g)) {
                    log.info("T4-BT: subgoal [{}] re-binned REQUIRED by spec traceability (origin stamp said extra): {}",
                            g.getId(), g.getDescription());
                }
                return false;
            }
        }
        return isExtraOrigin(g);
    }

    /** Increments the merge count for a goal (called by MergeAgent after a successful merge). */
    public void incrementGoalMergeCount(String goalId) {
        incrementGoalMetadataInt(goalId, "mergeCount");
    }

    /**
     * T2-DC: persist the set of Java identifiers cited in this goal's description or criteria
     * that could not be grounded against the on-disk output tree. Stored as a comma-joined
     * string under {@code metadata["undefinedSymbols"]} so future heuristics (e.g. T2-DD's
     * rollback streak escalation) can query repeated hallucinations cross-cycle without
     * re-deriving from the description.
     */
    public void setUndefinedSymbols(String goalId, java.util.Collection<String> symbols) {
        GoalEntity goal = goalId != null ? goalMap.get(goalId) : null;
        if (goal == null || symbols == null || symbols.isEmpty()) return;
        goal.getMetadata().put("undefinedSymbols", String.join(",", symbols));
        saveGoalMetadata(goal);
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

    /**
     * T1-EI: rehabilitation vote — increments {@code rehabilitationVotes} on a GIVEN_UP goal
     * at most once per cycle (de-duped on {@code rehabilitationVoteLastCycle}). Returns the
     * new vote count after applying the increment.
     */
    public int registerRehabilitationVote(String goalId, String cycleName) {
        if (goalId == null) return 0;
        GoalEntity g = goalMap.get(goalId);
        if (g == null) return 0;
        Object lastCycle = g.getMetadata().get("rehabilitationVoteLastCycle");
        if (cycleName != null && cycleName.equals(lastCycle)) {
            return getGoalMetadataInt(g, "rehabilitationVotes");
        }
        int current = getGoalMetadataInt(g, "rehabilitationVotes");
        int next = current + 1;
        g.getMetadata().put("rehabilitationVotes", next);
        if (cycleName != null) {
            g.getMetadata().put("rehabilitationVoteLastCycle", cycleName);
        }
        saveGoalMetadata(g);
        return next;
    }

    /** T1-EI: clear rehabilitation-vote state after a reactivation (or other reset path). */
    public void clearRehabilitationVotes(String goalId) {
        if (goalId == null) return;
        GoalEntity g = goalMap.get(goalId);
        if (g == null) return;
        g.getMetadata().remove("rehabilitationVotes");
        g.getMetadata().remove("rehabilitationVoteLastCycle");
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
     * T4-CD slice 1: loaded state is hypothesis, not fact — a resumed run re-grounds every
     * ACTIVE subgoal by dropping the prior run's per-subgoal state:
     * <ul>
     *   <li>{@code criteriaPassStatus} — banked criteria reads re-derive against the restored
     *       artifact via the loop's own verification. Terminal goals keep their banks: the
     *       completion edge (T4-BP-3) re-verifies every ledger criterion before any closure.</li>
     *   <li>{@code subgoalRequests} + {@code criteriaFailStreak} — run-scoped brake state that
     *       T2-BK/T4-BZ-3 persist on the entity for INTRA-run restarts. Across a run boundary
     *       they are the parent's brakes (reset decision table: default RESET) — the first field
     *       resume (2026-07-17 run 3) starved evolution for 21 cycles because the parent's
     *       restored REPETITION_DETECTED/DIVERGENCE_REQUEST requests suppressed the cycle-start
     *       window seeding ({@code CycleStartSignalSeeder} correctly abstains while any carrier
     *       is non-empty).</li>
     * </ul>
     *
     * @return the number of subgoals whose state was re-grounded
     */
    public int regroundActiveSubgoalsForResume(String reason) {
        int touched = 0;
        for (GoalEntity g : goalMap.values()) {
            if (g.getType() != GoalType.SUBGOAL || g.getStatus() != GoalStatus.ACTIVE) continue;
            if (g.getMetadata() == null) continue;
            boolean changed = false;
            Object banks = g.getMetadata().get("criteriaPassStatus");
            if (banks instanceof Map<?, ?> m && !m.isEmpty()) {
                g.getMetadata().remove("criteriaPassStatus");
                changed = true;
            }
            if (g.getMetadata().remove("subgoalRequests") != null) changed = true;
            if (g.getMetadata().remove("criteriaFailStreak") != null) changed = true;
            if (changed) {
                saveGoalMetadata(g);
                touched++;
            }
        }
        if (touched > 0) {
            log.info("T4-CD: re-grounded {} ACTIVE subgoal(s) (banks + run-scoped entity state dropped) — {}",
                    touched, reason);
            persist();
        }
        return touched;
    }

    /**
     * T3-BZ: re-points every banked criterion and goal description at a file the loop has just
     * relocated, so a repair cannot leave the goal tree asserting something it made false.
     *
     * <p>Walks every goal, not only the active one: a criterion banked on a COMPLETED goal is
     * still read at the closure edge, and a description on a queued goal still steers the
     * planner. Returns the number of goals changed, for the caller's log.
     */
    public int rewriteRelocatedFileReferences(String oldPath, String newPath) {
        if (oldPath == null || newPath == null || oldPath.equals(newPath)) return 0;
        int changed = 0;
        for (GoalEntity g : goalMap.values()) {
            if (g == null) continue;
            boolean touched = false;

            String description = g.getDescription();
            if (description != null) {
                String rewritten = RequirementPhrasingNormalizer
                        .rewriteRelocatedReferences(description, oldPath, newPath);
                if (!rewritten.equals(description)) {
                    g.setDescription(rewritten);
                    touched = true;
                }
            }

            Object raw = g.getMetadata() != null ? g.getMetadata().get("acceptanceCriteria") : null;
            if (raw instanceof List<?> list && !list.isEmpty()) {
                List<String> rewritten = new ArrayList<>(list.size());
                boolean criteriaChanged = false;
                for (Object o : list) {
                    String c = o == null ? null : o.toString();
                    String r = RequirementPhrasingNormalizer.rewriteRelocatedReferences(c, oldPath, newPath);
                    rewritten.add(r);
                    if (r != null && !r.equals(c)) criteriaChanged = true;
                }
                if (criteriaChanged) {
                    g.getMetadata().put("acceptanceCriteria", rewritten);
                    touched = true;
                }
            }

            if (touched) {
                changed++;
                try {
                    goalGraphService.save(g);
                } catch (Exception e) {
                    log.debug("T3-BZ: graph update failed for goal {} (ignoring): {}", g.getId(), e.toString());
                }
            }
        }
        if (changed > 0) {
            persist();
            log.info("T3-BZ: {} relocated to {} — re-pointed {} goal(s) whose criteria or description "
                    + "named the vacated path.", oldPath, newPath, changed);
        }
        return changed;
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
            // Spec-grounding (run 19): only spec-bound unmet criteria earn retry budget —
            // an all-authored goal gets the floor of 1 attempt instead of churning cycles
            // on requirements the loop invented (run 19's "strings" goal burned its whole
            // endgame this way before killing the root).
            // T4-CQ: an item a closure gate raised is the exception. Its criteria are stamped
            // AUTHORED so the closure ledger does not count what the gate re-checks itself, and
            // that stamp left it a budget of ZERO: the test gate's item on the first adopted
            // project was given up after one attempt ("1/0"), and the gate then refused closure
            // for the rest of the run with nothing left to work on. The gate's requirement is
            // not one the loop invented -- the root cannot close without it.
            if ((passing == null || !passing) && (raisedByClosureGate(goal) || !isAuthoredCriterion(goal, i))) unmet++;
        }
        return unmet;
    }

    private static boolean raisedByClosureGate(GoalEntity g) {
        return g.getMetadata() != null
                && g.getMetadata().get(com.walpro.autobuilder.core.build.BlockerTypes.ORIGINATING_GATE_KEY) != null;
    }

    /**
     * T3-BR: Returns true when the subgoal has a non-empty acceptance-criteria list defined
     * in metadata. Used to distinguish criteria-aware subgoals from criteria-less ones in
     * fingerprint-repeat handling (the latter still fall back to T2-CD).
     */
    public boolean hasAcceptanceCriteria(String goalId) {
        GoalEntity goal = goalId != null ? goalMap.get(goalId) : null;
        if (goal == null) return false;
        Object raw = goal.getMetadata().get("acceptanceCriteria");
        return raw instanceof List<?> l && !l.isEmpty();
    }

    /**
     * T4-DY: before the planner retires a subgoal as superseded, asks whether its work is DONE -- its
     * criteria banked, or verified against the code as it stands now (the same check T2-EH's
     * verify-before-pause makes). If so the subgoal is fulfilled, and true is returned: the planner must
     * not prune it. Rewriting a plan cannot un-observe a success any more than a failure.
     *
     * <p>receiver-questions run 2026-09-29_12-49-16: the main work item's changes merged at 12:52 and
     * 12:55, the run ended at 16/16, and at 12:58:46 the planner pruned that item as "superseded by
     * replanning" -- the root closed on "1 subsumed", and the interface showed the finished work as not done.
     */
    public boolean fulfilledInsteadOfPruned(String subGoalId, CycleContextData context) {
        GoalEntity sg = subGoalId == null ? null : goalMap.get(subGoalId);
        if (sg == null || sg.getStatus() != GoalStatus.ACTIVE || !hasNoActiveDescendants(subGoalId)) return false;
        if (allCriteriaDefinedAndBanked(subGoalId)) {
            applyGoalProgress(subGoalId, GoalProgressState.FULFILLED,
                    "criteria already banked -- fulfilled instead of superseded by replanning");
            return true;
        }
        List<String> criteria = acceptanceCriteriaOf(sg);
        if (criteriaVerificationService == null || context == null || criteria.isEmpty()) return false;
        Map<Integer, Boolean> passMap = criteriaVerificationService.verifyAndRecord(
                context, subGoalId, criteria, sg.getDescription());
        if (passMap.size() == criteria.size() && passMap.values().stream().allMatch(Boolean::booleanValue)) {
            applyGoalProgress(subGoalId, GoalProgressState.FULFILLED,
                    "acceptance criteria verified against current code -- fulfilled instead of superseded by replanning");
            return true;
        }
        return false;
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
     * T2-EL: criteria phrased as an absence requirement ("does not expose DELETE /x") are
     * satisfied by a missing endpoint — the structural reconciliation abstains on them.
     */
    private static final java.util.regex.Pattern NEGATED_CRITERION_TEXT =
            java.util.regex.Pattern.compile("(?i)\\b(no|not|never|without|cannot)\\b");

    /**
     * T2-EL: reconciles this goal's acceptance criteria against the structural view before a
     * FULFILLED application banks them. A criterion whose text names a verb-qualified endpoint
     * (e.g. the D2 work-item criterion "A request mapping for POST /loans exists in the
     * application sources") is decidable by {@code EndpointSurfaceQuery}: when the query
     * definitively reads that endpoint as NOT built, the text/LLM read loses — a banked
     * {@code true} is corrected to {@code false} and the criterion counts as contradicted so
     * the caller refuses the promotion. Run 20: T3-BL's early exit read the banked criterion
     * as MET every cycle while the D2 closure guard correctly read the same sources as ABSENT;
     * neither side corrected the other, and the leaf could neither close nor be worked —
     * 283 identical cycles. Correcting the bank here makes {@code allCriteriaDefinedAndBanked}
     * false on the next cycle, so the early exit yields to ordinary agent execution.
     *
     * <p>Abstain rules (a wrong un-bank is the false-give-up direction, so only a definitive
     * structural ABSENT contradicts): structure query disabled or absent → 0; no output on the
     * current cycle context → 0; criterion names no verb-qualified endpoint → skipped; negated
     * phrasing → skipped; built set ungroundable ({@code isBuilt} abstains) → skipped.
     *
     * @return the number of contradicted criteria (0 = nothing structurally refutable)
     */
    public int reconcileCriteriaAgainstStructure(String goalId) {
        if (!goalPlannerConfig.isStructureQueryEnabled() || endpointSurfaceQuery == null) return 0;
        GoalEntity goal = goalId != null ? goalMap.get(goalId) : null;
        if (goal == null || goal.getMetadata() == null) return 0;
        Object raw = goal.getMetadata().get("acceptanceCriteria");
        if (!(raw instanceof List<?> criteria) || criteria.isEmpty()) return 0;
        Map<String, String> output = currentCycleContext != null ? currentCycleContext.previousOutput() : null;
        if (output == null || output.isEmpty()) return 0; // cannot see the artifact — no opinion

        Map<Integer, Boolean> passStatus = getCriteriaPassStatus(goal);
        int contradicted = 0;
        boolean bankCorrected = false;
        for (int i = 1; i <= criteria.size(); i++) {
            Object rawCriterion = criteria.get(i - 1);
            if (!(rawCriterion instanceof String text) || text.isBlank()) continue;
            if (NEGATED_CRITERION_TEXT.matcher(text).find()) continue;
            for (var endpoint : endpointSurfaceQuery.declaredInSpec(text)) {
                Optional<Boolean> built = endpointSurfaceQuery.isBuilt(endpoint, output);
                if (built.isPresent() && !built.get()) {
                    contradicted++;
                    Boolean banked = passStatus.get(i);
                    if (banked != null && banked) {
                        passStatus.put(i, false);
                        bankCorrected = true;
                        log.warn("T2-EL: un-banked criterion {} of goalId={} — {} has no request mapping in the output (structure wins over the banked read)",
                                i, goalId, endpoint);
                    }
                    break;
                }
            }
        }
        if (bankCorrected) {
            saveCriteriaPassStatus(goalId, passStatus);
        }
        return contradicted;
    }

    /**
     * T2-EM: whether {@code goalId} is a structural repair leaf whose named gap is still open —
     * its description is one of {@code EndpointSurfaceQuery}'s work items AND the query
     * definitively reads the demanded endpoint as not yet built. The scope guard consults this
     * before emitting {@code SUBGOAL_SCOPE_MISMATCH}/{@code SCOPE_OUT_OF_SCOPE}: suppressing
     * evaluation/merge on such a leaf disarms the only agent able to close the gap the leaf
     * exists for — the gap IS the scope (run 20: the merge agent was selected 18× with nothing
     * landing while the D2 guard correctly refused closure on the same absent endpoint).
     * Abstains {@code false} on any uncertainty, keeping today's suppression behavior.
     */
    public boolean isOpenStructuralRepairLeaf(String goalId) {
        if (!goalPlannerConfig.isStructureQueryEnabled() || endpointSurfaceQuery == null) return false;
        GoalEntity goal = goalId != null ? goalMap.get(goalId) : null;
        if (goal == null) return false;
        var demanded = endpointSurfaceQuery.endpointFromWorkItem(goal.getDescription());
        if (demanded.isEmpty()) return false;
        Map<String, String> output = currentCycleContext != null ? currentCycleContext.previousOutput() : null;
        Optional<Boolean> built = endpointSurfaceQuery.isBuilt(demanded.get(), output);
        return built.isPresent() && !built.get();
    }

    /**
     * T2-FL: promote every ACTIVE leaf whose criteria the artifact has already been OBSERVED to
     * satisfy — the direction the loop has never had.
     *
     * <p><b>The asymmetry this closes.</b> {@link #isOpenStructuralRepairLeaf} answers "this leaf
     * is NOT built" and {@link #reconcileCriteriaAgainstStructure} un-banks a criterion the
     * structure refutes. Both run toward refusal. Nothing asks the complement, so the loop can
     * prove a work item unfinished in microseconds and has no path that proves one finished, and
     * a leaf nobody re-checks stays ACTIVE — which does not merely delay the closure edge,
     * it removes it, because {@link #autoCompleteRootIfSatisfied()} early-returns while any
     * subgoal is ACTIVE and every closure gate sits past that line.
     *
     * <p><b>Measured, run {@code 2026-09-22_15-59-33}:</b> {@code terminal=deadline} with a
     * 23/23 artifact, 36 of its 69 cycles spent after the artifact was already complete, and four
     * ACTIVE leaves naming endpoints that artifact served — {@code GET /members/{id}},
     * {@code POST /loans}, {@code POST /loans/{id}/return}, {@code GET /loans?memberId={id}}.
     * Over the last 54 minutes the boot gate probed nine criteria at a time and contradicted
     * none, and none of that reached the leaves.
     *
     * <p><b>Evidence, not inference, and never a new one.</b> The observation is what the
     * per-cycle boot gate already took against the tree on disk. This adds no probe, no build, no
     * model call and no cost — it reads what was spent and thrown away. A criterion that was
     * skipped, unparsed, unprobeable or refused is absent from the observation, so a leaf quoting
     * it is not promoted: an abstention is not a pass ([[project-false-completion-2]]).
     *
     * <p><b>Nothing here knows what kind of target it is holding.</b> The claim is derived by
     * {@code ExercisableCriterionQuery}, which reads a served surface and a terminating one
     * through the same call, so a command-line artifact is promoted on its exit statuses exactly
     * as a service is on its response statuses.
     *
     * <p><b>The structural gate applies only where it can speak.</b> Where the leaf's description
     * names an endpoint and the surface query has a definite opinion, that endpoint must be
     * built: criteria can be stated about one endpoint while the description demands another, and
     * a mapping existing is not the behaviour being right. Where the query has no opinion — a
     * target with no served surface, an ungroundable mapping — it withholds one rather than
     * blocking, which is the same abstain contract the rest of this class keeps.
     *
     * @param observation what the boot gate established on the tree on disk this cycle
     * @return the number of leaves promoted (0 = nothing was provable, the common case)
     */
    public int promoteSatisfiedLeaves(com.walpro.autobuilder.core.build.BootGate.BootObservation observation) {
        if (exercisableCriterionQuery == null) {
            // Said out loud for the same reason the refusals are: an unwired sweep and a sweep
            // with nothing to consider both promoted nothing, and the whole point of the event is
            // that those must not read alike.
            if (behaviourLogger != null) {
                behaviourLogger.observedLeafPromotion(
                        currentCycleContext != null ? currentCycleContext.cycleName() : null,
                        0, 0, 0, 0,
                        List.of("the criterion query is not wired, so no leaf could be judged"));
            }
            return 0;
        }
        // T2-FL: NOT gated on the observation carrying anything. An empty observation still leaves
        // every structural criterion decidable from the sources, and the first shipped version
        // returned here instead — which is precisely why run 2026-09-23_02-48-06 spent its whole
        // length with a built endpoint's coverage item ACTIVE. An abstained observation is a
        // reason to answer less, never a reason to stop reading what needs no observation.
        com.walpro.autobuilder.core.build.BootGate.BootObservation seen = observation != null
                ? observation
                : com.walpro.autobuilder.core.build.BootGate.BootObservation.abstained();

        GoalEntity root = getRootGoal();
        String specText = root != null ? root.getDescription() : null;
        Map<String, String> output = currentCycleContext != null ? currentCycleContext.previousOutput() : null;

        // Snapshotted before the loop: a promotion re-points currentSubGoalId and can mark
        // ancestors, so iterating the live map while promoting would be reading a tree that the
        // previous iteration changed underneath.
        List<GoalEntity> leaves = goalMap.values().stream()
                .filter(g -> g.getType() == GoalType.SUBGOAL && g.getStatus() == GoalStatus.ACTIVE)
                .filter(g -> !isStaleOrphan(g))
                .filter(g -> getSubGoals(g.getId()).stream()
                        .noneMatch(c -> c.getStatus() == GoalStatus.ACTIVE))
                .toList();

        int promoted = 0;
        int candidates = 0;
        int declined = 0;
        int guardRefused = 0;
        List<String> reasons = new ArrayList<>();
        for (GoalEntity leaf : leaves) {
            if (leaf.getStatus() != GoalStatus.ACTIVE) continue; // a sibling's promotion may have moved it
            List<String> criteria = criteriaTextOf(leaf);
            if (criteria.isEmpty()) continue; // nothing stated is nothing observed
            // Counted here rather than at the filter above: a leaf stating no criteria is not one
            // this sweep declined, it is one the sweep has no question to ask about.
            candidates++;
            Optional<String> refusal = whyNotSatisfied(criteria, specText, seen, output);
            if (refusal.isPresent()) {
                // T2-FL: said out loud, because otherwise "declined" and "never ran" are the same
                // silence. Run 2026-09-23_02-48-06 declined on every cycle and the only way to
                // establish it had run at all was noticing T4-DW logging beside it. The debug line
                // below did NOT achieve that: the runs log at INFO and emit zero DEBUG lines, so
                // run 2026-09-23_17-47-43 reproduced the same ambiguity with this fix in place.
                // The behaviour event below is what carries it; the debug line stays for a
                // developer running the loop at DEBUG.
                log.debug("T2-FL: goalId={} not promoted {} {}", leaf.getId(), "—", refusal.get());
                declined++;
                if (reasons.size() < PROMOTION_REASON_SAMPLE_CAP) {
                    reasons.add(leaf.getId() + " — " + refusal.get());
                }
                continue;
            }
            if (!descriptionSurfaceIsBuilt(leaf, output)) {
                declined++;
                if (reasons.size() < PROMOTION_REASON_SAMPLE_CAP) {
                    reasons.add(leaf.getId() + " — the surface its description names is not built yet");
                }
                continue;
            }

            log.info("T2-FL: goalId={} — the artifact was observed to satisfy all {} of its criteria; promoting [{}]",
                    leaf.getId(), criteria.size(), leaf.getDescription());
            var applied = applyGoalProgress(leaf.getId(), GoalProgressState.FULFILLED);
            if (applied != null && applied.promoted()) {
                promoted++;
            } else if (leaf.getStatus() != GoalStatus.ACTIVE) {
                promoted++; // marked completed off the current branch
            } else {
                log.info("T2-FL: goalId={} stayed ACTIVE — an existing guard refused the promotion, which stands",
                        leaf.getId());
                guardRefused++;
            }
        }
        if (behaviourLogger != null) {
            behaviourLogger.observedLeafPromotion(
                    currentCycleContext != null ? currentCycleContext.cycleName() : null,
                    candidates, promoted, declined, guardRefused, reasons);
        }
        return promoted;
    }

    /**
     * Empty when every criterion is satisfied by evidence this cycle already holds; otherwise the
     * reason the first unsatisfied one gives, in the words a reader of the run log needs.
     *
     * <p><b>Two kinds of criterion, two kinds of evidence, and the second was missing until a run
     * showed what it cost.</b> A criterion stating runtime behaviour can only be answered by
     * having ASKED the artifact. A criterion demanding that an endpoint merely EXIST is answered
     * by reading the sources, and no observation could add anything to that answer  {@code —}
     * which is why it must not be gated on one. Run {@code 2026-09-23_02-48-06} spent its whole
     * length with one ACTIVE leaf of the second kind, whose endpoint was built in
     * {@code LoanController} the entire time, and this method as first shipped refused it for want
     * of a behavioural claim it was never going to have.
     */
    private Optional<String> whyNotSatisfied(
            List<String> criteria, String specText,
            com.walpro.autobuilder.core.build.BootGate.BootObservation observation,
            Map<String, String> output) {
        boolean observedAny = false;
        boolean decidedByFilePresence = false;
        for (String criterion : criteria) {
            // The criterion's own words first: when the probe was handed this very string, that
            // is the most direct evidence there is and no parse can improve on it.
            if (observation.observedPassing(criterion)) {
                observedAny = true;
                continue;
            }

            // T2-FL: a demand that a source FILE exist is decided by the output's file map, as an
            // endpoint's existence is decided by its mappings. Run 2026-09-23_21-22-40 declined
            // every sweep on "Create MemberService.java"-shaped criteria while the artifact ran.
            Optional<String> demandedFile = sourceFileWhoseExistenceIsDemanded(criterion);
            if (demandedFile.isPresent()) {
                // Null is unreadable; an EMPTY map is a tree with nothing written yet, where the
                // file is simply absent (flightsim 2026-09-24 was told "could not be read" twice).
                if (output == null) {
                    return Optional.of("the output file map could not be read, so \"" + criterion + "\" is undecided");
                }
                if (!outputHoldsFile(output, demandedFile.get())) {
                    return Optional.of(demandedFile.get() + " is still absent from the output");
                }
                decidedByFilePresence = true;
                continue;
            }

            // A demand that an endpoint EXIST is decided by the sources, not by a probe.
            Optional<com.walpro.autobuilder.analysis.structure.Endpoint> demanded =
                    endpointWhoseExistenceIsDemanded(criterion);
            if (demanded.isPresent()) {
                if (!goalPlannerConfig.isStructureQueryEnabled()) {
                    return Optional.of("the structure query is disabled, so \"" + criterion + "\" cannot be decided");
                }
                Optional<Boolean> built = endpointSurfaceQuery.isBuilt(demanded.get(), output);
                if (built.isEmpty()) {
                    return Optional.of("the built surface could not be read, so \"" + criterion + "\" is undecided");
                }
                if (!built.get()) {
                    return Optional.of(demanded.get() + " is still absent from the output");
                }
                continue;
            }

            Optional<String> claim = exercisableCriterionQuery.claimOf(criterion, specText);
            if (claim.isEmpty()) {
                return Optional.of("no probe of any shape can read a claim out of \"" + criterion + "\"");
            }
            if (!observation.observedClaimPassing(claim.get())) {
                return Optional.of(claim.get() + " was not among the claims this observation judged");
            }
            observedAny = true;
        }
        // A file that exists is not work that is done: the leaf that demands it usually demands
        // what the file must CONTAIN, and a stub satisfies existence ([[project-false-completion-unmasked]]).
        // So file presence may complete a leaf's reading only beside a claim observed on the
        // running artifact — never as the whole evidence.
        if (decidedByFilePresence && !observedAny) {
            return Optional.of("its only evidence would be that files exist — no claim of it was observed on the running artifact");
        }
        return Optional.empty();
    }

    // A path or file name with an extension, optionally in backticks.
    private static final String FILE_TOKEN = "`?([\\w./-]+\\.[A-Za-z]\\w*)`?";
    private static final java.util.regex.Pattern CREATE_FILE = java.util.regex.Pattern.compile(
            "^(?:Create|Add)\\s+" + FILE_TOKEN + "\\s*\\.?$");
    private static final java.util.regex.Pattern FILE_EXISTS = java.util.regex.Pattern.compile(
            "^(?:The\\s+file\\s+)?" + FILE_TOKEN + "\\s+exists(?:\\s+(?:at|in|under)\\s+`?([\\w./-]+?)`?)?\\s*\\.?$");
    private static final String TYPE_KIND = "(?:Java\\s+)?(?:class|interface|type|record|enum|file)";
    private static final java.util.regex.Pattern TYPE_EXISTS_AT = java.util.regex.Pattern.compile(
            "^An?\\s+(?:" + TYPE_KIND + "\\s+`?(\\w+)`?|`?(\\w+)`?\\s+" + TYPE_KIND + ")"
                    + "\\s+exists\\s+(?:at|in|under)\\s+`?([\\w./-]+?)`?\\s*\\.?$");

    /**
     * T2-FL: the source file a criterion demands exist, when the criterion says NOTHING else —
     * the whole sentence must be one of three shapes, so a criterion that also states what the
     * file must do is never read as mere existence. Returns a path fragment: a file name, a
     * relative path, or a directory joined to a type name with a trailing dot (any extension).
     */
    public static Optional<String> sourceFileWhoseExistenceIsDemanded(String criterion) {
        if (criterion == null) return Optional.empty();
        String text = criterion.strip();
        java.util.regex.Matcher m = CREATE_FILE.matcher(text);
        if (m.matches()) return Optional.of(m.group(1));
        m = FILE_EXISTS.matcher(text);
        if (m.matches()) {
            String file = m.group(1);
            String dir = m.group(2);
            if (dir == null || file.contains("/")) return Optional.of(file);
            String where = trimSlashes(dir);
            // "X.java exists at a/b/X.java" names the file twice — the location already is it.
            if (where.equals(file) || where.endsWith("/" + file)) return Optional.of(where);
            return Optional.of(where + "/" + file);
        }
        m = TYPE_EXISTS_AT.matcher(text);
        if (m.matches()) {
            String type = m.group(1) != null ? m.group(1) : m.group(2);
            String where = trimSlashes(m.group(3));
            String last = where.substring(where.lastIndexOf('/') + 1);
            if (last.startsWith(type + ".")) return Optional.of(where);
            if (last.contains(".")) return Optional.empty(); // a file that is not the named type's
            if (!where.contains("/")) return Optional.empty(); // a package name, not a path
            return Optional.of(where + "/" + type + ".");
        }
        return Optional.empty();
    }

    private static String trimSlashes(String s) {
        String out = s;
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        while (out.startsWith("/")) out = out.substring(1);
        return out;
    }

    /** A fragment ending in '.' matches any extension; otherwise exact name or path-suffix match. */
    static boolean outputHoldsFile(Map<String, String> output, String fragment) {
        for (String path : output.keySet()) {
            String p = path.replace('\\', '/');
            if (fragment.endsWith(".")) {
                int slash = p.lastIndexOf('/');
                String withoutExt = p.contains(".") ? p.substring(0, p.lastIndexOf('.') + 1) : p;
                if (withoutExt.equals(fragment) || withoutExt.endsWith("/" + fragment)) return true;
                if (slash < 0 && withoutExt.equals(fragment)) return true;
            } else if (p.equals(fragment) || p.endsWith("/" + fragment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where the leaf's description names an endpoint the surface query has a definite opinion
     * about, that opinion must be "built". No opinion — including every target with no served
     * surface at all — withholds the gate rather than closing it.
     */
    private boolean descriptionSurfaceIsBuilt(GoalEntity leaf, Map<String, String> output) {
        if (!goalPlannerConfig.isStructureQueryEnabled() || endpointSurfaceQuery == null) return true;
        if (output == null || output.isEmpty()) return true;
        for (var endpoint : endpointSurfaceQuery.declaredInSpec(leaf.getDescription())) {
            Optional<Boolean> built = endpointSurfaceQuery.isBuilt(endpoint, output);
            if (built.isPresent() && !built.get()) {
                log.info("T2-FL: goalId={} not promoted — its criteria were observed passing but {} has no request mapping in the output",
                        leaf.getId(), endpoint);
                return false;
            }
        }
        return true;
    }

    /** A goal's acceptance criteria as stated, or empty when it declares none. */
    private List<String> criteriaTextOf(GoalEntity goal) {
        Object raw = goal.getMetadata() == null ? null : goal.getMetadata().get("acceptanceCriteria");
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(String.class::isInstance).map(String.class::cast)
                .filter(c -> !c.isBlank())
                .toList();
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
     * T1-DG: Loads the {@code planningRunId} from the root goal's metadata into
     * {@link #currentPlanningRunId} without advancing it. The counter is now advanced per planner
     * invocation by {@link #beginPlanningRun()} so each re-plan wave is stamped with a distinct
     * run-id; the root metadata holds the highest run-id used so far so that restarts pick up
     * after the last wave.
     */
    private void initPlanningRunId() {
        if (rootGoalId == null) return;
        GoalEntity root = goalMap.get(rootGoalId);
        if (root == null) return;
        currentPlanningRunId = getGoalMetadataInt(root, "planningRunId");
        log.info("planningRunId loaded from root metadata: {}", currentPlanningRunId);
    }

    /**
     * T1-DG: Advances {@link #currentPlanningRunId} by 1 and persists the new value on the root
     * goal so subsequent {@link #addSubGoal} calls stamp every newly-injected subgoal with the
     * fresh run-id. Called by the planner at the entry of each injection wave (initial bootstrap,
     * {@code planInto} re-plan after an exhausted branch, and re-plan via the empty-backlog path).
     * Without this advance, the {@code minPlanningRunId} filter in
     * {@link #autoCompleteRootIfSatisfied()} is a no-op because every direct child shares the same
     * run-id, dilutiug the first-wave completion ratio with later replacement waves.
     */
    public int beginPlanningRun() {
        currentPlanningRunId++;
        if (rootGoalId != null) {
            GoalEntity root = goalMap.get(rootGoalId);
            if (root != null) {
                root.getMetadata().put("planningRunId", currentPlanningRunId);
                try {
                    goalGraphService.save(root);
                } catch (Exception e) {
                    log.debug("Neo4j save of planningRunId on root failed (ignoring): {}", e.getMessage());
                }
            }
        }
        log.info("planningRunId advanced to {}", currentPlanningRunId);
        return currentPlanningRunId;
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
     * T4-BZ-3 (I6): persists the subgoal-scoped OPEN REQUESTS to goal metadata — the channel
     * carrier of the five formerly-stored scoped signal types (STAGNATION, REPETITION_DETECTED,
     * DIVERGENCE_REQUEST, LOW_CONFIDENCE, SCORE_PLATEAU). Same choreography as
     * {@link #saveSubgoalSignals}: caller pre-filters to scoped kinds; an empty list clears the
     * slot so a consumed request does not resurrect on restore.
     */
    public void saveSubgoalRequests(String goalId, java.util.List<com.walpro.autobuilder.core.lifecycle.ChannelRequest> requests) {
        GoalEntity goal = goalId != null ? goalMap.get(goalId) : null;
        if (goal == null) return;
        if (requests == null || requests.isEmpty()) {
            if (goal.getMetadata().remove("subgoalRequests") != null) {
                saveGoalMetadata(goal);
            }
            return;
        }
        Map<String, Map<String, Object>> serializable = new LinkedHashMap<>();
        for (com.walpro.autobuilder.core.lifecycle.ChannelRequest r : requests) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("urgency", r.urgency());
            entry.put("age", r.age());
            entry.put("source", r.source() != null ? r.source() : "");
            if (r.validForCycles() != null) entry.put("validForCycles", r.validForCycles());
            serializable.put(r.kind().name(), entry);
        }
        goal.getMetadata().put("subgoalRequests", serializable);
        saveGoalMetadata(goal);
    }

    /** T4-BZ-3 (I6): loads the persisted subgoal-scoped open requests; empty if none. */
    public java.util.List<com.walpro.autobuilder.core.lifecycle.ChannelRequest> getSubgoalRequests(GoalEntity goal) {
        if (goal == null || goal.getMetadata() == null) return java.util.List.of();
        Object raw = goal.getMetadata().get("subgoalRequests");
        if (!(raw instanceof Map<?, ?> rawMap)) return java.util.List.of();
        java.util.List<com.walpro.autobuilder.core.lifecycle.ChannelRequest> result = new java.util.ArrayList<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            try {
                String typeName = String.valueOf(entry.getKey());
                if (!(entry.getValue() instanceof Map<?, ?> m)) continue;
                double urgency = ((Number) m.get("urgency")).doubleValue();
                int age = ((Number) m.get("age")).intValue();
                Object rawSource = m.get("source");
                String source = rawSource != null ? String.valueOf(rawSource) : "restored";
                Integer validity = m.get("validForCycles") instanceof Number n ? n.intValue() : null;
                result.add(new com.walpro.autobuilder.core.lifecycle.ChannelRequest(
                        SignalType.of(typeName), urgency, source, goal.getId(), age, validity, Map.of()));
            } catch (Exception e) {
                log.warn("Failed to deserialize subgoal request entry '{}': {}", entry.getKey(), e.getMessage());
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
        } else {
            // Field-caught 2026-07-26: a goal whose metadata deserialised to an IMMUTABLE map
            // (absent/empty metadata property) made the put below throw
            // UnsupportedOperationException from @PostConstruct — i.e. the whole application
            // failed to boot over one odd goal. Copy into a mutable map rather than trusting the
            // shape a row happened to produce.
            try {
                meta.put(META_ATTEMPT_ID, meta.get(META_ATTEMPT_ID));
            } catch (UnsupportedOperationException immutable) {
                meta = new HashMap<>(meta);
                g.setMetadata(meta);
            }
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

    /**
     * T1-FD slice 1: read-only view of the current subgoal for observers (goal-browser REST,
     * SSE snapshots). {@link #getCurrentSubGoal()} is a LOOP-internal accessor with side
     * effects — null-pointer recovery, admission selection and, when the tree is all-terminal,
     * the T1-CN delegation into {@code autoCompleteRootIfSatisfied()} (which runs the
     * coverage-reviewer LLM call). On 2026-07-02 an open graph-browser tab drove that whole
     * chain on http-nio threads via {@code GET /autobuilder/goals/tree}, concurrently with the
     * scheduler and PAST the system pause, starving the local model into a run-killing
     * timeout livelock. Observers peek; only the loop recovers/closes.
     */
    public GoalEntity peekCurrentSubGoal() {
        return currentSubGoalId != null ? goalMap.get(currentSubGoalId) : null;
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
                    // T2-EI: prefer an admitted (non-held) subgoal so recovery respects the cap;
                    // fall back to the first remaining if all are held (shouldn't happen — cap≥1
                    // guarantees an admitted leaf exists).
                    Set<String> held = heldByAdmission();
                    String recovered = remaining.stream()
                            .map(GoalEntity::getId)
                            .filter(id -> !held.contains(id))
                            .findFirst()
                            .orElse(remaining.get(0).getId());
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
                    com.walpro.autobuilder.core.lifecycle.ChannelRequest replanRequest = currentCycleContext != null
                            ? currentCycleContext.peekRequest(BasicSignals.REPLAN_REQUESTED) : null;
                    boolean dedupBlockedThisCycle = replanRequest != null
                            && "planInto-deduped".equals(replanRequest.source());
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
                        && currentCycleContext.hasOpenRequest(BasicSignals.REPLAN_REQUESTED);
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

    /**
     * T1-EL: when {@code currentSubGoalId} references a non-ACTIVE goal (COMPLETED, GIVEN_UP,
     * or missing from the goal map), advance the pointer to the next ACTIVE leaf under the
     * root. Returns a {@link SubGoalRepair} record describing the repair when one occurred,
     * or {@code null} when the pointer was already healthy or no root exists.
     *
     * <p>Intended to be called at the start of {@code CycleContextCoordinator.prepareRuntimeContext}
     * before {@link #getCurrentSubGoal()}. Without this guard, a subgoal that was cascade-given-up
     * (or promoted while a stale pointer survived) keeps surfacing in cycle prompts, producing
     * zero-work cycles until other logic eventually rebinds the pointer.
     */
    public SubGoalRepair repairCurrentSubGoalIfStale() {
        if (currentSubGoalId == null) return null;
        GoalEntity current = goalMap.get(currentSubGoalId);
        if (current != null && current.getStatus() == GoalStatus.ACTIVE) {
            return null;
        }
        String oldId = currentSubGoalId;
        String reason = (current == null) ? "missing"
                : (current.getStatus() == GoalStatus.COMPLETED ? "completed"
                : current.getStatus() == GoalStatus.GIVEN_UP ? "given_up"
                : current.getStatus().name().toLowerCase());
        String newId = (rootGoalId != null) ? resolveNextActiveLeaf(rootGoalId) : null;
        currentSubGoalId = newId;
        if (newId != null) {
            ensureAttemptIdsFor(newId);
        }
        persist();
        log.warn("T1-EL: current_subgoal_repaired oldId={} reason={} -> newId={}", oldId, reason, newId);
        return new SubGoalRepair(oldId, newId, reason);
    }

    public record SubGoalRepair(String oldId, String newId, String reason) {}

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

    /** T4-BE: direct accessor for the active subgoal pointer (callers needing the id without
     * the full {@link GoalEntity}). Null when no subgoal is active. */
    public String getCurrentSubGoalId() {
        return currentSubGoalId;
    }

    /** T4-BE: true when {@code goalId} resolves to a goal that is ACTIVE in the tree. Used by
     * the deferred-mutation restage scheduler to decide whether a candidate's intent is still
     * pursuable (and thus restage-eligible). */
    public boolean isInActiveScope(String goalId) {
        if (goalId == null || goalId.isBlank()) return false;
        GoalEntity g = goalMap.get(goalId);
        return g != null && g.getStatus() == GoalStatus.ACTIVE;
    }

    /** T4-BE: same shape as {@link #setCurrentSubGoal} but lets the caller record the prior
     * pointer for a behaviour event (completion-bias re-focus emits
     * {@code deferred_restage_scope_promoted} with from/to). Returns the prior subgoal id
     * (may be null). Preconditions: goal must be ACTIVE and of type SUBGOAL — same as
     * {@link #setCurrentSubGoal}. */
    public String focusOnSubGoal(String goalId) {
        String prior = this.currentSubGoalId;
        setCurrentSubGoal(goalId);
        return prior;
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

        MarkCompletedResult completionResult = markGoalCompleted(completedId, pendingFulfillmentResolution);

        // T1-ET Part B: T1-DD refusal already re-pointed currentSubGoalId to a safe active leaf
        // and persisted to graph. Falling through to findNextSiblingOrAncestor / resolveNextActiveLeaf
        // / `currentSubGoalId = next` would clobber that pointer (often to null), then T1-DB's
        // null-recovery would re-pick the parent itself, and the next cycle re-enters T3-BL early-exit
        // on the same parent — the zero-work loop documented in
        // `behaviour-2026-05-25_00-31-18.ndjson`.
        switch (completionResult) {
            case MarkCompletedResult.RefusedActiveDescendants refused -> {
                // EVOLUTION_NOOP wipe still belongs to "we moved past the previous subgoal"; we did,
                // just via the T1-DD repoint rather than the standard promotion path.
                if (currentCycleContext != null) {
                    currentCycleContext = currentCycleContext.consumeRequest(BasicSignals.EVOLUTION_NOOP);
                }
                return true;
            }
            case MarkCompletedResult.RefusedClaimRefuted refuted -> {
                // T4-CU: the work is not done, so the loop stays on it. currentSubGoalId is left
                // where it was, deliberately — advancing would leave the defect with nothing
                // pointing at it, which is how the measured chain ended.
                //
                // Returns FALSE, unlike the T1-DD branch above: that one returns true because it
                // genuinely did move past the subgoal, just via a repoint rather than the standard
                // path. Nothing was adopted here. No production caller reads this value today
                // (the one in applyGoalProgress discards it), so this is about not misleading the
                // first one that does.
                log.warn("T4-CU: not promoting past {} — its own acceptance criteria are contradicted"
                        + " by the running artifact", refuted.goalId());
                // T2-FB: the banked pass-map belongs to the claim that was just refuted, so it is
                // hypothesis rather than fact and must be re-earned. Without this the criteria stay
                // banked, T3-BL's "all criteria banked" early exit re-declares FULFILLED next cycle,
                // T4-CU refuses again, and the loop spins with zero work — measured in run
                // 2026-08-01_19-29-42 as 78 early exits on ONE subgoal, 148 package-gate runs
                // against 26 in a comparable run, ~16 of its 50 minutes, ended by the T4-I brake.
                //
                // Same correction, same reason, as the closure-edge reopen path and the T4-CD
                // resume re-grounding: a contradicted bank is dropped so the loop's own
                // verification re-derives it. Dropping the WHOLE map rather than the one refuted
                // criterion is deliberate — the refutation arrives as prose from a port that does
                // not name which criterion failed, and matching it back to one would be code
                // consuming prose for control flow. Re-verifying a criterion that did hold costs a
                // re-check; trusting one inside a refuted claim is how false completions happen.
                clearCriteriaBankAfterRefutation(refuted.goalId(), refuted.refutation().refutedCriteria());
                injectClaimRefutationRepair(refuted.goalId(), refuted.refutation());
                return false;
            }
            case MarkCompletedResult.Completed completed -> { /* standard promotion continues below */ }
            case MarkCompletedResult.UnknownGoal unknown -> { /* nothing happened; fall through */ }
        }

        String next = findNextSiblingOrAncestor(completedId);

        // If no next and parent needs planning, try that
        boolean plannerWasCalled = false;
        int plannerInjectedCount = 0;
        if (next == null && shouldTriggerPlanner(completedId)) {
            String parentId = goalMap.get(completedId).getParentId();
            if (parentId != null) {
                // T1-DH: before invoking the planner, give the root a chance to auto-complete on
                // the just-finished first wave. If the ratio of COMPLETED first-wave children is
                // already above the threshold, marking the root terminal here avoids an entire
                // re-plan wave whose subgoals would only paraphrase already-completed work.
                autoCompleteRootIfSatisfied();
                GoalEntity rootGoal = rootGoalId != null ? goalMap.get(rootGoalId) : null;
                if (rootGoal != null && rootGoal.getStatus() != GoalStatus.ACTIVE) {
                    log.info("T1-DH: skipping re-plan under [{}] — root [{}] became {} via pre-plan auto-complete",
                            parentId, rootGoalId, rootGoal.getStatus());
                } else {
                    plannerInjectedCount = planningCallback.planInto(currentCycleContext, parentId);
                    plannerWasCalled = true;
                    markPlannedOnce(parentId);
                    next = findNextSiblingOrAncestor(parentId);
                }
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

        // T1-EE Fix #2: a stale EVOLUTION_NOOP must not outlive the subgoal it was emitted for.
        // Belt-and-braces with the subGoalId stamp check in AutobuilderService.
        if (currentCycleContext != null) {
            currentCycleContext = currentCycleContext.consumeRequest(BasicSignals.EVOLUTION_NOOP);
        }

        // T1-BK: if the planner was invoked but injected nothing (all proposals blocked by
        // deduplication), do NOT auto-complete the root — the system cannot distinguish
        // "genuinely done" from "planner tried to add work but was deduped".
        // Instead, surface REPLAN_REQUESTED so the next cycle re-attempts planning.
        if (plannerWasCalled && plannerInjectedCount == 0) {
            log.warn("planInto returned 0 injected subgoals — skipping autoCompleteRootIfSatisfied and raising REPLAN_REQUESTED");
            if (currentCycleContext != null) {
                currentCycleContext = currentCycleContext.issueRequest(
                        com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(
                                BasicSignals.REPLAN_REQUESTED, 1.0, "planInto-deduped",
                                com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.2)));
            }
        } else {
            autoCompleteRootIfSatisfied();
        }

        // T5-I: the pointer advanced past a completed leaf — the promotion event. The T1-DD
        // refusal path returned earlier (its repoint is a guard_violated, not a promotion).
        if (behaviourLogger != null) {
            behaviourLogger.subgoalPromoted(
                    currentCycleContext != null ? currentCycleContext.cycleName() : null,
                    completedId, currentSubGoalId);
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
        ensureSeedObligations(description);
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
        ensureSeedObligations(description);
    }

    /**
     * T4-CQ: every way a root goal is set (seed bootstrap, reset, an operator's {@code /goal}) comes
     * through the two methods above, so this is the one place the obligations file is generated.
     * Keyed to the text exactly as the root holds it, which is what the file's seed hash is read
     * against.
     */
    private void ensureSeedObligations(String rootDescription) {
        if (seedObligationsGenerator != null) seedObligationsGenerator.ensureFor(rootDescription);
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

        // T2-EB: descriptions double as the single verification requirement on criteria-less
        // goals — a defect-statement phrasing inverts that check. Creation is the one home
        // for the requirement-form invariant (same rationale as the BUILD_FIX stamp below).
        String normalizedDescription = RequirementPhrasingNormalizer.normalize(description);
        boolean rephrased = description != null && !description.equals(normalizedDescription);
        if (rephrased) {
            log.info("T2-EB: normalized defect-statement description for [{}]: '{}' -> '{}'",
                    id, description, normalizedDescription);
        }

        GoalEntity subGoal = new GoalEntity();
        subGoal.setId(id);
        subGoal.setDescription(normalizedDescription);
        subGoal.setRole(role != null ? role : "planner");
        subGoal.setType(GoalType.SUBGOAL);
        subGoal.setParentId(parentId);
        subGoal.setStatus(GoalStatus.ACTIVE);
        subGoal.setCreated(Instant.now());
        Map<String, Object> subGoalMeta = new HashMap<>();
        // T2-BW: stamp the current planning run so the global budget can exclude goals
        // created in prior sessions when they are later given up.
        subGoalMeta.put("planningRunId", currentPlanningRunId);
        // T2-EB: keep the original phrasing for provenance when a rewrite occurred.
        if (rephrased) {
            subGoalMeta.put("normalizedFrom", description);
        }
        // T1-CZ: tag analysis-spawned goals so exhaustParentIfStuck can exclude them
        // from exhaustion accounting (they are augmentations, not primary work items).
        if ("analysis".equals(role)) {
            subGoalMeta.put("spawnedBy", "analysis");
        }
        // The planner prompt instructs a "[BUILD_FIX]" description prefix but planner-created
        // subgoals never carried the metadata tag the rest of the system filters on
        // (BlockerSubgoalInjector stamps it only on its own injections) — observed 2026-06-11
        // when GoalConsolidationService folded a planner-created BUILD_FIX subgoal its
        // metadata-based exclusion couldn't see. Creation is the one home for the tag.
        if (normalizedDescription != null && normalizedDescription.trim().startsWith(BlockerTypes.BUILD_FIX_DESCRIPTION_PREFIX)) {
            subGoalMeta.put(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX);
        }
        // T2-EI (D1, origin-based required/extra classification): a subgoal is EXTRA when it is an
        // augmentation rather than a top-level requirement — nested under another subgoal (T1-CP
        // replacement nesting, decompose-on-stuck), or analysis/graduation/residue spawned. EXTRA
        // failures are absorbed at closure (do not doom the root) under depth-first; REQUIRED failures
        // still escalate. Build-fix and coverage-gap injections at root level stay REQUIRED (load-
        // bearing). Criteria-traceability-to-spec is the follow-up (T4-BT). See
        // [[project-t2ei-design-note]].
        boolean extraOrigin = !parentId.equals(rootGoalId)
                || "analysis".equals(role)
                || "graduation".equals(role)
                || VERIFICATION_RESIDUE_ROLE.equals(role);
        subGoalMeta.put(SUBGOAL_ORIGIN_KEY, extraOrigin ? SUBGOAL_ORIGIN_EXTRA : SUBGOAL_ORIGIN_REQUIRED);
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

    /** Role stamped on residue subgoals created by {@link #rescopeResidueSubGoal}. */
    public static final String VERIFICATION_RESIDUE_ROLE = "verification-residue";

    /**
     * T2-EI (D1): metadata key + values recording whether a subgoal is a top-level requirement
     * (REQUIRED) or an augmentation (EXTRA). Consumed by {@link #autoCompleteRootIfSatisfied()} to
     * absorb EXTRA-subgoal closure failures instead of dooming the root under depth-first.
     */
    public static final String SUBGOAL_ORIGIN_KEY = "subgoalOrigin";
    public static final String SUBGOAL_ORIGIN_REQUIRED = "required";
    public static final String SUBGOAL_ORIGIN_EXTRA = "extra";

    /** T2-EI: true when the subgoal is an EXTRA-origin augmentation (see {@link #SUBGOAL_ORIGIN_KEY}). */
    static boolean isExtraOrigin(GoalEntity g) {
        return g != null && g.getMetadata() != null
                && SUBGOAL_ORIGIN_EXTRA.equals(g.getMetadata().get(SUBGOAL_ORIGIN_KEY));
    }

    /**
     * T4-BP consumer #4 (2026-06-12): residue re-scope on partial verification. The exhaustion
     * rescue's verification pass produces an exact diagnosis — which criteria hold, which don't —
     * and previously the loop only banked it: the subgoal still went GIVEN_UP and its unmet
     * criteria blocked root closure via the T2-DR ledger (observed 14:45 run: root GIVEN_UP over
     * one subgoal whose unmet residue was "return 404 when book/member missing" — a diagnosis the
     * verifier had already made). Every other diagnosed failure class becomes a work item
     * (compile error → BUILD_FIX, coverage gap → injectCoverageGaps, packaging failure → T4-AN-8);
     * this gives the verification diagnosis the same path.
     *
     * <p>Creates one follow-up subgoal under the exhausted goal's parent carrying exactly the
     * unmet criteria, and transitions the exhausted goal to SUBSUMED/COVERED naming it (banked
     * criteria stay banked; SUBSUMED passes the closure ledger). Bounds: residue goals are never
     * re-scoped themselves (one generation), and a residue description matching a GIVEN_UP goal
     * is not re-proposed (T1-DE dedup). Empty result = caller proceeds with the normal give-up.
     */
    public Optional<String> rescopeResidueSubGoal(String exhaustedGoalId, List<String> unmetCriteria) {
        if (exhaustedGoalId == null || unmetCriteria == null || unmetCriteria.isEmpty()) return Optional.empty();
        GoalEntity g = goalMap.get(exhaustedGoalId);
        if (g == null) return Optional.empty();
        if (VERIFICATION_RESIDUE_ROLE.equals(g.getRole())) {
            log.info("rescopeResidueSubGoal: {} is already a residue goal — one generation only, proceeding with give-up", exhaustedGoalId);
            return Optional.empty();
        }
        String residueDescription = "Implement the remaining unmet behaviour of: " + g.getDescription();
        if (goalSimilarityService != null
                && goalSimilarityService.countGivenUpMatches(residueDescription) >= GoalSimilarityService.GIVEN_UP_RETRY_LIMIT) {
            log.info("rescopeResidueSubGoal: residue for {} already failed as GIVEN_UP — proceeding with give-up", exhaustedGoalId);
            return Optional.empty();
        }
        String id = "goal-" + UUID.randomUUID();
        String parentId = g.getParentId() != null ? g.getParentId() : rootGoalId;
        addSubGoal(id, residueDescription, true, parentId, VERIFICATION_RESIDUE_ROLE,
                GoalKindClassifier.GoalKind.EXECUTABLE);
        setAcceptanceCriteria(id, unmetCriteria);
        transitionGoal(exhaustedGoalId, GoalStatus.SUBSUMED,
                "unmet verified criteria re-scoped into " + id, SubsumptionReason.COVERED);
        log.info("T4-BP-4: re-scoped {} unmet criteria of {} into residue subgoal {} (original SUBSUMED)",
                unmetCriteria.size(), exhaustedGoalId, id);
        return Optional.of(id);
    }

    /** Role stamped on per-criterion subgoals created by {@link #divideExhaustedSubGoal}. */
    public static final String DIVIDED_CRITERION_ROLE = "divided-criterion";

    /**
     * T2-EJ: divide an exhausted subgoal's unmet criteria into per-criterion subgoals. The
     * 2026-06-27 reliability batch's one genuine failure was a REQUIRED subgoal ("Implement POST
     * /loans endpoint") bundling five behaviour rules as one block — the model satisfied none of
     * the five across four retries, so it went GIVEN_UP and doomed the root. Finer per-rule
     * subgoals are independently attemptable and their progress is bankable; this extends the
     * T4-BP-4 residue path (some-pass) to the 0-pass-but-divisible case.
     *
     * <p>Bounds: needs at least two DISTINCT unmet criteria (a single criterion has nothing to
     * split), one division level only (divided/residue goals are never divided again), and a
     * child description matching a GIVEN_UP goal is not re-proposed (T1-DE dedup). Children
     * inherit the exhausted goal's REQUIRED/EXTRA origin explicitly — {@code addSubGoal}'s
     * origin rule would stamp them EXTRA (injected role), and the slice-1a closure would then
     * absorb their failures, letting the root complete with the divided behaviour still missing.
     * Empty result = caller proceeds with the normal give-up.
     */
    public List<String> divideExhaustedSubGoal(String exhaustedGoalId, List<String> unmetCriteria) {
        if (exhaustedGoalId == null || unmetCriteria == null) return List.of();
        GoalEntity g = goalMap.get(exhaustedGoalId);
        if (g == null) return List.of();
        if (DIVIDED_CRITERION_ROLE.equals(g.getRole()) || VERIFICATION_RESIDUE_ROLE.equals(g.getRole())) {
            log.info("divideExhaustedSubGoal: {} has role {} — one division level only, proceeding with give-up",
                    exhaustedGoalId, g.getRole());
            return List.of();
        }
        List<String> distinct = unmetCriteria.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (distinct.size() < 2) return List.of();

        String parentId = g.getParentId() != null ? g.getParentId() : rootGoalId;
        Object origin = g.getMetadata() != null ? g.getMetadata().get(SUBGOAL_ORIGIN_KEY) : null;
        List<String> childIds = new ArrayList<>();
        for (String criterion : distinct) {
            String description = "Implement a single behaviour of [" + g.getDescription() + "]: " + criterion;
            if (goalSimilarityService != null
                    && goalSimilarityService.countGivenUpMatches(description) >= GoalSimilarityService.GIVEN_UP_RETRY_LIMIT) {
                log.info("divideExhaustedSubGoal: criterion already failed as GIVEN_UP, not re-proposing: {}", criterion);
                continue;
            }
            String id = "goal-" + UUID.randomUUID();
            addSubGoal(id, description, childIds.isEmpty(), parentId, DIVIDED_CRITERION_ROLE,
                    GoalKindClassifier.GoalKind.EXECUTABLE);
            GoalEntity child = goalMap.get(id);
            if (child != null && origin != null) {
                child.getMetadata().put(SUBGOAL_ORIGIN_KEY, origin);
            }
            setAcceptanceCriteria(id, List.of(criterion));
            childIds.add(id);
        }
        if (childIds.isEmpty()) {
            log.info("divideExhaustedSubGoal: no criterion of {} survived the dedup guard — proceeding with give-up",
                    exhaustedGoalId);
            return List.of();
        }
        transitionGoal(exhaustedGoalId, GoalStatus.SUBSUMED,
                "unmet criteria divided into per-criterion subgoals " + childIds, SubsumptionReason.COVERED);
        log.info("T2-EJ: divided {} unmet criteria of {} into {} per-criterion subgoals {} (original SUBSUMED)",
                distinct.size(), exhaustedGoalId, childIds.size(), childIds);
        return childIds;
    }

    /**
     * T3-AW: stores acceptance criteria in a goal's metadata and re-persists.
     * Called by PlannerAgent after injecting a subgoal when the LLM provided criteria.
     */
    public void setAcceptanceCriteria(String goalId, List<String> criteria) {
        if (criteria == null || criteria.isEmpty()) return;
        GoalEntity g = goalMap.get(goalId);
        if (g == null) return;
        // Two classes of text are sentenced to never become criteria, both dropped here at the
        // single write choke point (a goal left criteria-less falls back to
        // description-as-requirement):
        // 1. the structural placeholder — a protocol sentinel, not a requirement;
        // 2. diagnostic INSTRUCTIONS ("Diverge — …", "[BUILD_FIX] …") — work orders about a past
        //    build state, unconfirmable once the defect is fixed. They leak in as criteria via
        //    description-copying paths (consolidation merges, unconfirmed-criteria attachment);
        //    run 9 (2026-07-04) ended with two stale divergence instructions as the artifact's
        //    only closure wall.
        // 3. T4-DJ: an invented FILE PATH inside an otherwise-fine criterion. The seed states
        //    behaviour, not layout, so "X.java exists at <path>" asserts something nothing stated
        //    and lets the loop contradict itself — a correct file rejected at 0.42 for sitting
        //    where the loop's own package constraint put it. The file requirement survives; the
        //    invented location does not.
        List<String> declared = criteria.stream()
                .filter(c -> c != null && !c.isBlank())
                .filter(c -> !RequirementPhrasingNormalizer.isStructuralCriterionPlaceholder(c))
                .filter(c -> !com.walpro.autobuilder.core.build.BlockerTypes.isDiagnosticInstruction(c))
                .map(RequirementPhrasingNormalizer::stripInventedFilePath)
                .toList();
        if (declared.size() < criteria.size()) {
            log.info("setAcceptanceCriteria: dropped {} placeholder/instruction criterion(s) for goal {}",
                    criteria.size() - declared.size(), goalId);
        }
        if (declared.isEmpty()) return;
        g.getMetadata().put("acceptanceCriteria", declared);
        stampCriteriaBinding(g, declared);
        persist();
        try {
            goalGraphService.save(g);
        } catch (HttpServerErrorException e) {
            log.debug("Neo4j criteria update failed for goal {} (ignoring): {}", goalId, e.getMessage());
        }
    }

    /**
     * Spec-grounding stamp (run 19, 2026-07-07): classifies each declared criterion against the
     * root specification and records the per-criterion binding as goal metadata
     * ({@code criteriaBinding}: 1-based index → {@code "SPEC"} | {@code "AUTHORED"}). Runs at
     * the same choke point that already drops placeholders/instructions, so every write path
     * (planner, analysis, consolidation carry, division, closure-edge injection) is covered.
     *
     * <p>Two layers: (1) deterministic — criteria on BUILD_FIX-stamped goals are always
     * AUTHORED (build health is gate-owned; run 19's root-blocker "handled as strings where
     * required" was a compile-diagnostic snapshot fossilized as a requirement); (2) one cached LLM pass
     * for the rest. Abstain (no answer) stamps nothing — absent binding means SPEC, so the
     * gate never fails open into advisory when the model is down.
     */
    private void stampCriteriaBinding(GoalEntity g, List<String> declared) {
        if (goalPlannerConfig == null || !goalPlannerConfig.isCriteriaGroundingEnabled()) return;
        boolean gateOwned = g.getMetadata() != null
                && com.walpro.autobuilder.core.build.BlockerTypes.BUILD_FIX.equals(
                        g.getMetadata().get(com.walpro.autobuilder.core.build.BlockerTypes.METADATA_KEY))
                // The criteria gate's item carries seed requirements verbatim; stamping them
                // AUTHORED demoted the specification's own words to advisory.
                && !com.walpro.autobuilder.core.build.BlockerTypes.isFromCriteriaGate(g.getMetadata());
        Map<String, String> binding = new HashMap<>();
        if (gateOwned) {
            for (int i = 1; i <= declared.size(); i++) binding.put(String.valueOf(i), "AUTHORED");
        } else {
            if (criteriaGroundingService == null) return;
            GoalEntity root = getRootGoal();
            if (root == null || root.getDescription() == null || root.getDescription().isBlank()) return;
            Map<Integer, Boolean> grounded = criteriaGroundingService.classifyGrounded(
                    root.getDescription(), declared);
            if (grounded.isEmpty()) return; // no answer — keep the SPEC default
            for (Map.Entry<Integer, Boolean> e : grounded.entrySet()) {
                binding.put(String.valueOf(e.getKey()), e.getValue() ? "SPEC" : "AUTHORED");
            }
        }
        g.getMetadata().put("criteriaBinding", binding);
        long authored = binding.values().stream().filter("AUTHORED"::equals).count();
        if (authored > 0) {
            log.info("criteria-grounding: goal [{}] — {} of {} criterion(s) stamped AUTHORED (advisory; cannot block closure or burn budget)",
                    g.getId(), authored, declared.size());
        }
    }

    /**
     * Reads the per-criterion binding stamp. A criterion with no entry is SPEC — the
     * conservative default for pre-gate goals, abstained classifications, and disabled runs.
     */
    private boolean isAuthoredCriterion(GoalEntity g, int oneBasedIndex) {
        // T3-CC slice 2: the read moved to CriteriaBinding when the observability readout became
        // its fifth caller. This stays as the in-class name the four closure-side sites use.
        return CriteriaBinding.isAuthored(g, oneBasedIndex);
    }

    /**
     * T2-EV: the done-when for a coverage-injected endpoint work item.
     *
     * <p>Until now this was solely "a request mapping for X exists" — deliberate, because a
     * description embedding "no mapping exists" becomes unconfirmable once the work lands. But it
     * meant an endpoint that arrived through the coverage path acquired <b>no behavioural
     * requirement at all</b>, and the loop implements what a criterion names. Measured in run
     * {@code 2026-07-27_20-33-22}: the only two criteria mentioning {@code /members} in the whole
     * run were the two mapping-exists items, and {@code POST /members} with a missing name has
     * failed the behaviour smoke in three of three scored runs.
     *
     * <p>The mapping criterion stays first — it is the item's own done-when and the thing that
     * retires it. The specification's stated statuses for that endpoint follow, so the behaviour
     * is required from the moment the endpoint is, and is executable by the criterion probes.
     * Silent when the spec states nothing for it: no invented expectations.
     */
    // T2-FL: the coverage item's done-when, owned here as parts so the criterion that STATES it
    // and the read that RECOGNISES it are built from the same strings. Two spellings of one
    // template is how a matcher silently stops matching what a generator emits.
    private static final String MAPPING_EXISTS_PREFIX = "A request mapping for ";
    private static final String MAPPING_EXISTS_SUFFIX = " exists in the application sources";

    /**
     * T2-FL: the endpoint a criterion demands the mere EXISTENCE of, or empty when the criterion
     * is not that template.
     *
     * <p>Inverting a template this class owns, never parsing prose ([[feedback-string-consumption-path]]
     * governs control flow, and this is control flow). The same discipline as
     * {@code EndpointSurfaceQuery.endpointFromWorkItem}, and for the same reason: a criterion that
     * merely resembles this one must not be read as it.
     */
    private Optional<com.walpro.autobuilder.analysis.structure.Endpoint>
            endpointWhoseExistenceIsDemanded(String criterion) {
        if (criterion == null || endpointSurfaceQuery == null) return Optional.empty();
        return endpointWhoseExistenceIsDemanded(criterion, endpointSurfaceQuery);
    }

    /**
     * The template inversion itself, public so an offline readout (the criterion parse report)
     * asks THIS question rather than re-deriving it — one home for one predicate.
     */
    public static Optional<com.walpro.autobuilder.analysis.structure.Endpoint>
            endpointWhoseExistenceIsDemanded(String criterion,
                    com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery surfaceQuery) {
        if (criterion == null || surfaceQuery == null) return Optional.empty();
        String text = criterion.strip();
        if (!text.startsWith(MAPPING_EXISTS_PREFIX) || !text.endsWith(MAPPING_EXISTS_SUFFIX)) {
            return Optional.empty();
        }
        String middle = text.substring(MAPPING_EXISTS_PREFIX.length(),
                text.length() - MAPPING_EXISTS_SUFFIX.length()).strip();
        var declared = surfaceQuery.declaredInSpec(middle);
        // Exactly one endpoint, and the template's whole middle must be it — a criterion naming
        // two is not this template however much of it matches.
        return declared.size() == 1 ? Optional.of(declared.iterator().next()) : Optional.empty();
    }

    private List<String> coverageItemCriteria(
            com.walpro.autobuilder.analysis.structure.Endpoint endpoint, String specText) {
        // T2-EV CORRECTED 2026-07-28: the spec's stated statuses are NOT attached here. Attaching
        // them turned a cheap structural item into a behavioural contract that cannot close: run
        // 2026-07-28_01-50-15 carried TWELVE criteria on the POST /loans coverage item (this one,
        // four derived, seven the planner then added) while `@RequestMapping("/loans")` was
        // already in the output — the structural job done, the item ACTIVE anyway. Four such items
        // stayed open, the backlog never emptied, closure was attempted ONCE in 76 minutes, and
        // package gate / boot gate / criterion probes ran zero times.
        //
        // The item's job is to make the endpoint EXIST, so its done-when is the mapping and
        // nothing else. The spec-derived requirements reach the criterion probes through
        // specBoundBehaviouralCriteria() instead — T4-CU takes a list of requirements and never
        // needed them to be any goal's criteria.
        return List.of(MAPPING_EXISTS_PREFIX + endpoint + MAPPING_EXISTS_SUFFIX);
    }

    /**
     * T4-CU: every SPEC-bound acceptance criterion in the tree, for execution against the running
     * artifact at the completion edge.
     *
     * <p>AUTHORED criteria are excluded for the same reason they never join the completion-
     * verification set: the loop must not block its own closure on a requirement it invented and
     * the specification never made. Ordering follows the tree so the criteria of earlier subgoals
     * are exercised first, which is also the order in which their state was built.
     */
    /**
     * T4-CY: the SPECIFICATION's own stated requirements only — no goal-tree criteria.
     *
     * <p>The mid-run self-check must use this, not {@link #specBoundBehaviouralCriteria()}. That
     * one appends every criterion in the tree, which on run {@code 2026-07-28_23-16-15} meant
     * <b>73 and 77 requirements</b> per observation, each costing an LLM probe-synthesis call:
     * the two checks took <b>29.5 and 14.9 minutes</b> of a 90-minute run, the LLM circuit breaker
     * tripped, and the run managed 11 cycles. The tree's criteria are also the wrong input —
     * {@code skipped=71} of 77, because fine-grained plans write criteria about internal structure
     * rather than observable behaviour.
     *
     * <p>The spec-derived set is small (11 for this project's specification), stable across the
     * run, and independent of how the planner decomposed — so after the first observation the
     * synthesis cache covers all of it and later checks cost only the boot and the HTTP calls.
     */
    /**
     * T2-FL: what the per-cycle observation should ASK — the specification's own requirements plus
     * the questions the open work items are waiting on an answer to.
     *
     * <p><b>The gap this closes, measured on run {@code 2026-09-22_15-59-33}.</b> The self-check
     * was handed {@link #specDerivedRequirements} alone, so the probe spent that run asking
     * seventeen questions the specification stated and none of the eleven its four ACTIVE leaves
     * stated. Two of those leaf claims — {@code GET /members/{} → 200} and
     * {@code POST /loans/{}/return → 200} — are not derivable from the specification's prose at
     * all, because a success status stated once for every resource does not attach to a path the
     * way an error status does. The artifact served both, all run, and nothing ever asked.
     *
     * <p><b>Deduplicated by CLAIM, not by prose</b>, which is where the budget comes from: an
     * author and a specification state one requirement in two spellings, and probing both spends
     * two launches on one question. On that run's inputs the union of 17 specification
     * requirements and 11 leaf criteria is 16 distinct claims.
     *
     * <p><b>Claim-bearing requirements come first</b> so that the observation cap — which exists
     * to stop a pathological list consuming a run — trims what cannot be judged rather than what
     * can. A requirement no parser can read is still passed along, because the probe may yet
     * contradict it and that is evidence of a different kind; it simply does not get to displace
     * a question that has an answer.
     */
    public List<String> requirementsWorthObserving() {
        List<String> specDerived = specDerivedRequirements();
        if (exercisableCriterionQuery == null) return specDerived;

        GoalEntity root = getRootGoal();
        String specText = root != null ? root.getDescription() : null;

        List<String> candidates = new ArrayList<>(specDerived);
        for (GoalEntity leaf : getRemainingGoals()) {
            if (getSubGoals(leaf.getId()).stream().anyMatch(c -> c.getStatus() == GoalStatus.ACTIVE)) continue;
            candidates.addAll(criteriaTextOf(leaf));
        }

        List<String> answerable = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        Set<String> seenClaims = new LinkedHashSet<>();
        Set<String> seenText = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (!seenText.add(candidate)) continue;
            Optional<String> claim = exercisableCriterionQuery.claimOf(candidate, specText);
            if (claim.isEmpty()) {
                unreadable.add(candidate);
            } else if (seenClaims.add(claim.get())) {
                answerable.add(candidate);
            }
        }
        List<String> merged = new ArrayList<>(answerable);
        merged.addAll(unreadable);
        if (merged.size() != specDerived.size()) {
            // Says what this IS: the question set assembled for whatever observation follows. It
            // is NOT a record that one happened — the supplier runs before the self-check's own
            // abstention checks, so on run 2026-09-23_02-16-41 two of the first four emissions
            // were followed by "no behavioural observation this interval — the last compile probe
            // was COMPILE_ERROR". The earlier wording said "observing", which would have let a
            // later reader count preparations and report them as askings
            // ([[feedback-activity-not-progress]]).
            log.info("T2-FL: requirement set assembled — {} to ask, {} distinct claim(s) from the specification and the open work items, {} unreadable (the specification alone stated {}); whether they are asked is the self-check's next line",
                    merged.size(), answerable.size(), unreadable.size(), specDerived.size());
        }
        return merged;
    }

    public List<String> specDerivedRequirements() {
        List<String> out = new ArrayList<>();
        GoalEntity specRoot = getRootGoal();
        if (specBehaviourQuery == null || specRoot == null || specRoot.getDescription() == null) {
            return out;
        }
        var stated = specBehaviourQuery.statedBehaviours(specRoot.getDescription());
        for (var entry : stated.entrySet()) {
            out.addAll(specBehaviourQuery.criteriaFor(entry.getKey(), entry.getValue()));
        }
        return out;
    }

    public List<String> specBoundBehaviouralCriteria() {
        List<String> out = new ArrayList<>();
        // T2-EV: the specification's own stated per-endpoint statuses, supplied directly rather
        // than via any goal's criteria. This is where they belong — a requirement has to be
        // PROBEABLE, not OWNED by a work item, and attaching them to the coverage items instead
        // stopped those items ever closing (see coverageItemCriteria). Independent of how the
        // planner decomposed, so reach does not collapse on fine-grained plans: run 14-42-19
        // wrote 132 criteria of which only 7 were readable as assertions.
        GoalEntity specRoot = getRootGoal();
        if (specBehaviourQuery != null && specRoot != null && specRoot.getDescription() != null) {
            var stated = specBehaviourQuery.statedBehaviours(specRoot.getDescription());
            for (var entry : stated.entrySet()) {
                out.addAll(specBehaviourQuery.criteriaFor(entry.getKey(), entry.getValue()));
            }
        }
        for (GoalEntity g : getAllGoals()) {
            Object raw = g.getMetadata() != null ? g.getMetadata().get("acceptanceCriteria") : null;
            if (!(raw instanceof List<?> l)) continue;
            for (int i = 0; i < l.size(); i++) {
                Object c = l.get(i);
                if (c != null && !isAuthoredCriterion(g, i + 1)) out.add(c.toString());
            }
        }
        return out;
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
        emitStatusTransitions();
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


    /**
     * T5-I: goal_status_changed has ONE home — the persist() durability chokepoint. Every status
     * write (transitionGoal, cascade setStatus, entity markCompleted/markGivenUp, T2-DR
     * promotion, reactivation) becomes durable through persist(), so diffing a shadow map here
     * observes them all without instrumenting each mutation site. First sighting of an id seeds
     * silently: goal creation is planning_run's story, and startup load/sync must not replay a
     * prior run's history as fresh transitions. Seeding happens even while the logger is unwired
     * so a late-autowired logger cannot replay the backlog either.
     */
    private void emitStatusTransitions() {
        String cycleId = currentCycleContext != null ? currentCycleContext.cycleName() : null;
        for (GoalEntity g : goalMap.values()) {
            GoalStatus prev = lastObservedStatus.put(g.getId(), g.getStatus());
            if (behaviourLogger != null && prev != null && prev != g.getStatus()) {
                behaviourLogger.goalStatusChanged(cycleId, g.getId(),
                        prev.name(), g.getStatus().name(), g.getResolution());
            }
            appendGoalTransition(cycleId, g, prev);
        }
        lastObservedStatus.keySet().retainAll(goalMap.keySet());
    }

    /**
     * T4-DQ blocker 1: the same chokepoint now also APPENDS the change to the goal-transition log,
     * which is what makes goal state rewindable — it used to be overwritten in place, and an
     * overwritten value has nothing to return to.
     *
     * <p>The behaviour event above and this append answer different questions and so have
     * different seeding rules. The event answers "what changed during this run", and seeding a
     * first sighting silently is right there: a startup load is not news. The log answers "what
     * did the tree look like at sequence N", which needs to know a goal EXISTED — so a goal the
     * log has never seen gets a CREATED event, and a goal it already knows (a resumed lineage
     * reloading its own tree) gets nothing, because re-stamping it would misdate the history the
     * fold reads.
     */
    private void appendGoalTransition(String cycleId, GoalEntity g, GoalStatus prev) {
        if (goalTransitionLog == null || g.getStatus() == null || rewindInProgress) return;
        try {
            if (!goalTransitionLog.knowsGoal(g.getId())) {
                goalTransitionLog.append(g.getId(), null, g.getStatus().name(),
                        GoalTransition.Kind.CREATED, cycleId, g.getResolution());
            } else if (prev != null && prev != g.getStatus()) {
                goalTransitionLog.append(g.getId(), prev.name(), g.getStatus().name(),
                        GoalTransition.Kind.CHANGED, cycleId, g.getResolution());
            }
        } catch (Exception e) {
            log.warn("T4-DQ: goal-transition append failed for {}: {}", g.getId(), e.getMessage());
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

    /**
     * Single banking-aware terminal transition. Encodes both the target {@link GoalStatus} and
     * its paired ledger write (criteria banking) so call sites no longer pick the two
     * independently — replacing scattered direct use of {@link #markGoalCompleted} /
     * {@link #markGoalGivenUp} / raw {@link GoalEntity#setStatus(GoalStatus)} from non-cascade
     * sites.
     * <p>
     * Behaviour by target status:
     * <ul>
     *   <li>{@link GoalStatus#COMPLETED} — bank every defined criterion as {@code true} (no-op
     *       when none defined), then delegate to {@link #applyGoalProgress(String,
     *       GoalProgressState, String)} so cascade / promotion / sync logic is reused.</li>
     *   <li>{@link GoalStatus#GIVEN_UP} — delegate to {@code applyGoalProgress(GIVEN_UP)};
     *       leaves the ledger alone. The T2-DR auto-promotion may still flip GIVEN_UP →
     *       SUBSUMED when criteria were already banked by an earlier path.</li>
     *   <li>{@link GoalStatus#SUBSUMED} — set status directly, skipping the GIVEN_UP transition
     *       + cascade. Use when the goal is retired because replacement work covers it (the
     *       replacement carries the obligation; the original criteria are not claimed met).</li>
     * </ul>
     * Throws {@link IllegalArgumentException} for {@link GoalStatus#ACTIVE} — reactivation has
     * its own path ({@link #reactivateGoal(String)}).
     */
    public GoalProgressApplicationResult transitionGoal(String goalId, GoalStatus targetStatus, String resolution) {
        return transitionGoal(goalId, targetStatus, resolution, null);
    }

    /**
     * SUBSUMED-aware overload — when {@code targetStatus} is {@link GoalStatus#SUBSUMED}, the
     * supplied {@link SubsumptionReason} is stored as goal metadata under
     * {@link SubsumptionReason#METADATA_KEY} so consumers can distinguish "replaced by other
     * work" from "passively covered" without parsing the free-text resolution. Ignored for
     * non-SUBSUMED transitions.
     */
    public GoalProgressApplicationResult transitionGoal(String goalId, GoalStatus targetStatus, String resolution,
                                                        SubsumptionReason subsumptionReason) {
        if (goalId == null || targetStatus == null) {
            log.warn("transitionGoal: null goalId or targetStatus (goalId={}, targetStatus={})", goalId, targetStatus);
            return new GoalProgressApplicationResult(goalId, false, currentSubGoalId, currentSubGoalId, false);
        }
        if (targetStatus == GoalStatus.ACTIVE) {
            throw new IllegalArgumentException(
                    "transitionGoal: ACTIVE is not a terminal transition — use reactivateGoal(id)");
        }
        GoalEntity g = goalMap.get(goalId);
        if (g == null) {
            log.warn("transitionGoal: unknown goalId={}", goalId);
            return new GoalProgressApplicationResult(goalId, false, currentSubGoalId, currentSubGoalId, false);
        }

        return switch (targetStatus) {
            case COMPLETED -> {
                // T4-CU: the refutation check lives in markGoalCompleted, which BOTH completion
                // paths reach — this one and promoteCurrentSubGoal, which calls it directly.
                // Placing it here alone left the primary path uncovered.
                Optional<ClaimRefutation> refuted = refuteClosureClaim(goalId);
                if (refuted.isPresent()) {
                    yield refuseClosure(goalId, refuted.get());
                }
                bankAllCriteriaTrueIfDefined(goalId);
                yield applyGoalProgress(goalId, GoalProgressState.FULFILLED, resolution);
            }
            case GIVEN_UP -> applyGoalProgress(goalId, GoalProgressState.GIVEN_UP, resolution);
            case SUBSUMED -> transitionToSubsumed(g, resolution, subsumptionReason);
            // ACTIVE handled above; exhaustive switch over the remaining cases.
            case ACTIVE -> throw new IllegalStateException("unreachable");
        };
    }

    /**
     * Bank every defined acceptance-criterion index as {@code true} for {@code goalId}.
     * No-op when the goal has no criteria. Idempotent — replaces any existing partial-true map.
     */
    /**
     * T4-CU: optional closure-claim verifier. Absent in unit tests and wherever the target is one
     * no implementation understands, in which case closure behaves exactly as it did before.
     */
    @Setter
    @Autowired(required = false)
    private ClosureClaimVerifier closureClaimVerifier;

    /**
     * Whether the running artifact refutes what this goal is closing on.
     *
     * <p>Deliberately swallows failure: an exception from booting or probing is not evidence that
     * a criterion is false, and a verifier that can fail closed would block closure on its own
     * defects. The worst case is that the check does not run, which is where the loop already was.
     */
    /**
     * T4-CU: turns a refuted closure claim into work the loop can pick up.
     *
     * <p><b>This is the ticket's DoD and it was unreachable until the port named the criteria.</b>
     * The refusal used to log, clear the bank and return — so a correctly diagnosed defect left
     * nothing pointing at it, and the next cycle re-derived the same claim from scratch. The work
     * item's done-when is the refuted criteria THEMSELVES, which is only sayable now that they
     * arrive as data.
     *
     * <p><b>Nothing is injected for an unattributed refutation</b> — an artifact that will not boot
     * is the build gates' problem and they raise their own items; a work item saying only "this
     * claim is false" would re-ask the same question through a costlier channel, which is the trap
     * the nodiff escalation records.
     *
     * <p><b>MARKER FIRST, CRITERIA SECOND.</b> {@code setAcceptanceCriteria} stamps the
     * per-criterion binding and asks {@code isFromCriteriaGate}; written the other way round the
     * guard reads a map with no marker yet, concludes "gate-owned BUILD_FIX", and stamps the
     * specification's own words AUTHORED. Same ordering hazard as the T4-BP-3 site.
     */
    private void injectClaimRefutationRepair(String goalId, ClaimRefutation refutation) {
        if (goalId == null || refutation == null || !refutation.isAttributed()) return;
        GoalEntity root = getRootGoal();
        if (root == null) return;

        // T4-CU 2026-09-20: a repair item that is ITSELF refused must not clone itself. Its
        // done-when IS the refuted criteria, so a refusal means the repair has not landed yet —
        // the item should keep being worked, not spawn a twin. Measured in run
        // 2026-09-20_09-43-23: three identical items in 100 seconds, one per refusal, until the
        // run was stopped; half the goal tree was duplicates.
        // T2-ET widened this from the criteria gate to every gate: a boot-gate item refused on the
        // criteria it quotes is in the same position — its done-when IS those criteria.
        GoalEntity refused = goalMap.get(goalId);
        if (refused != null && BlockerTypes.isFromGate(refused.getMetadata())) {
            log.info("T4-CU: {} is itself a gate-raised repair item — not injecting a twin;"
                    + " the existing item stays the work", goalId);
            return;
        }

        // T4-CU 2026-09-20: the description is built from the CRITERIA, not from the refutation's
        // prose. The prose embeds the probe's temporary fixture path
        // (/tmp/boot-gate-fixture<random>), so every injection was textually unique and the
        // similarity dedup in injectCoverageGaps could never match one against the next. The
        // criteria are the planner's own words and stable across probe runs, which is what makes
        // dedup work — and they are already this item's done-when, so there is one source rather
        // than two. The volatile detail stays in the log, where it belongs.
        CoverageReviewResult injected = injectCoverageGaps(root, List.of(
                "[BUILD_FIX] The running artifact contradicts acceptance criteria this work item"
                        + " closed on — repair the code so they hold: "
                        + String.join("; ", refutation.refutedCriteria())), true);
        if (injected.injected().isEmpty()) {
            log.info("T4-CU: no repair item injected for {} — the gap was deduped or limited", goalId);
            return;
        }
        for (String id : injected.injectedIds()) {
            setGoalMetadataString(id, BlockerTypes.ORIGINATING_GATE_KEY, BlockerTypes.GATE_CRITERIA);
            setAcceptanceCriteria(id, refutation.refutedCriteria());
        }
        log.warn("T4-CU: {} closure refused and a repair item injected for {} refuted criterion(s)",
                goalId, refutation.refutedCriteria().size());
    }

    /** T4-CU: so the first closure of a run says out loud whether the check is even wired. */
    private boolean closureVerifierPresenceReported;

    private Optional<ClaimRefutation> refuteClosureClaim(String goalId) {
        // Without this, a run in which the verifier bean was absent looks exactly like one in
        // which every closure happened to carry no executable criterion — both are silence. That
        // ambiguity already cost a reading of run 2026-08-01_00-23-26, where four closures passed
        // with nothing logged and the log could not say which case it was.
        if (!closureVerifierPresenceReported) {
            closureVerifierPresenceReported = true;
            log.info("T4-CU: closure-claim verification is {}",
                    closureClaimVerifier == null ? "NOT wired — closures are unchecked" : "wired");
        }
        if (closureClaimVerifier == null) return Optional.empty();
        GoalEntity g = goalMap.get(goalId);
        if (g == null || g.getMetadata() == null) return Optional.empty();
        // T2-ET: a work item the boot gate raised is a claim about an OBSERVATION, and it
        // usually declares no criteria — so the check below abstained and a model score was the
        // only thing that closed it. Ask the observation first; the criteria, if any, still
        // follow.
        Optional<ClaimRefutation> observed = refuteOriginatingObservation(g);
        if (observed.isPresent()) return observed;
        Object raw = g.getMetadata().get("acceptanceCriteria");
        if (!(raw instanceof List<?> l) || l.isEmpty()) {
            // INFO, not DEBUG: this is the THIRD way this check can be silent, and until
            // 2026-09-17 it was the only one invisible. The bean-absent case got the presence
            // line above for exactly this reason ("both are silence"), and the
            // nothing-executable case already logs at INFO -- but a closure declaring NO
            // criteria exited here unseen, and it is the MAJORITY case: 4 of 6 closures in run
            // 2026-09-17_23-01-39. So a run could not distinguish "the verifier was never
            // reached" from "it was reached and had nothing to check", which is precisely the
            // ambiguity that made this ticket's own "5 runs, never executed" unreadable.
            log.info("T4-CU: {} declares no acceptance criteria — nothing to check", goalId);
            return Optional.empty();
        }
        // T3-CC: an AUTHORED criterion may be reported, never asserted against the artifact.
        //
        // The binding stamp was consulted at four closure-side sites and none on the verification
        // side, so a criterion the loop wrote beyond the specification could not block closure and
        // was still probed -- and a correct artifact that answers the SPECIFIED behaviour is then
        // recorded as contradicting an obligation nobody stated. Measured on the graph: of 97
        // distinct criteria naming a pattern input, 27 assert an error status the seed never
        // states for that endpoint.
        //
        // Filtered HERE rather than inside the verifier: this class already owns the stamp and its
        // one reader, so the invariant keeps its single home and the verifier stays ignorant of
        // goal metadata. Index is 1-based to match stampCriteriaBinding.
        List<String> all = l.stream().map(String::valueOf).toList();
        List<String> criteria = new ArrayList<>();
        List<String> elaboration = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            (isAuthoredCriterion(g, i + 1) ? elaboration : criteria).add(all.get(i));
        }
        if (!elaboration.isEmpty()) {
            // Reported, so the elaboration rate can be watched. Deliberately NOT scored: rewarding
            // it would pay the loop to invent obligations, which it already does often enough.
            log.info("T3-CC: {} — {} of {} criterion(s) are AUTHORED beyond the specification and "
                            + "are reported rather than exercised: {}",
                    goalId, elaboration.size(), all.size(), elaboration);
        }
        if (criteria.isEmpty()) {
            log.info("T3-CC: {} declares only AUTHORED criteria — nothing the specification "
                    + "requires is left to check, so no package build and no boot is bought", goalId);
            return Optional.empty();
        }
        try {
            // T4-DK: the specification travels with the question. A criterion naming a declared
            // operation rather than a literal invocation can only be turned into a probe against
            // the surface the specification itself states, and the verifier used to be handed an
            // empty string — which left every criterion on a target with no network surface
            // unexercisable and the check silently abstaining at every closure. The planner still
            // forms no opinion about these words; it passes the ones it was given.
            GoalEntity specRoot = getRootGoal();
            String specText = specRoot == null ? null : specRoot.getDescription();
            return closureClaimVerifier.refute(goalId, criteria, specText);
        } catch (RuntimeException e) {
            log.warn("T4-CU: closure-claim check for {} failed ({}) — treating as unchecked, not as refuted",
                    goalId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * T2-ET: the originating gate's vote on the closure of an item it raised.
     *
     * <p>T4-CC gave the gate precedence over the model for accepting a MUTATION on such an item;
     * nothing gave it a say in closing the item itself. Measured over the September archive: 45
     * boot-gate items closed by score, none by the gate, and of the 37 whose next observation
     * could be classified, 9 were followed by the very defect they named. The self-check does not
     * re-raise a finding identical to its last one, so each of those nine was lost for the rest of
     * the run rather than re-queued.
     *
     * <p>Read through the claim the injector stamped (T4-DW's {@code BOOT_CLAIM_KEY}), never
     * through the item's prose: a whole-artifact item is re-observed for the same defect; an item
     * quoting contradicted criteria has those criteria probed again; an archive-entry-point item
     * is checked against the build config and the jar (T4-DW). An entry-point item carries
     * its own criterion and is answered below by the ordinary path. An unstamped item is left to
     * that path too.
     */
    private Optional<ClaimRefutation> refuteOriginatingObservation(GoalEntity g) {
        Map<String, Object> md = g.getMetadata();
        if (!BlockerTypes.GATE_BOOT.equals(md.get(BlockerTypes.ORIGINATING_GATE_KEY))) {
            return Optional.empty();
        }
        Object claim = md.get(BlockerTypes.BOOT_CLAIM_KEY);
        GoalEntity specRoot = getRootGoal();
        String specText = specRoot == null ? null : specRoot.getDescription();
        try {
            if (BlockerTypes.CLAIM_SERVES.equals(claim)) {
                return closureClaimVerifier.refuteObservedDefect(g.getId(), g.getDescription(), specText);
            }
            if (BlockerTypes.CLAIM_CRITERION.equals(claim)
                    && md.get(BlockerTypes.BOOT_CLAIM_CRITERIA_KEY) instanceof List<?> quoted
                    && !quoted.isEmpty()) {
                return closureClaimVerifier.refute(g.getId(),
                        quoted.stream().map(String::valueOf).toList(), specText);
            }
            // T4-DW: the archive's missing entry point is answered by the build config and the jar,
            // not by a launch. Closing on a score while that evidence is absent is the hole T2-ET
            // closed for the other claims; an absent answer (no gate, nothing readable) refuses too,
            // because an abstention is not a pass.
            if (BlockerTypes.CLAIM_ARCHIVE_ENTRY_POINT.equals(claim)
                    && (packageGate == null || !packageGate.archiveEntryPointDeclared())) {
                return Optional.of(ClaimRefutation.archiveEntryPoint(
                        "the packaged archive still declares no entry point, and the build config does not"
                        + " declare the one the sources name"));
            }
        } catch (RuntimeException e) {
            log.warn("T2-ET: originating-observation check for {} failed ({}) — treating as unchecked",
                    g.getId(), e.toString());
        }
        return Optional.empty();
    }

    /**
     * Leaves the goal ACTIVE and records why, instead of closing it on a claim the artifact
     * contradicts.
     *
     * <p>The goal is left where it was rather than pushed to a failure state: the work is genuinely
     * unfinished, and ACTIVE is the truthful description of unfinished work. If the loop repairs
     * the defect the next closure attempt passes this check and proceeds normally; if it never
     * does, the ordinary exhaustion path retires the goal as GIVEN_UP — which is a true statement
     * about the run, where COMPLETED would have been a false one.
     */
    /** T4-DY slice B: repeated closure refusals are reported to the stuck layer; optional in narrow tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.core.stuck.StuckLayer stuckLayer;

    private GoalProgressApplicationResult refuseClosure(String goalId, ClaimRefutation refutation) {
        recordRefutedClosure(goalId, refutation);
        return new GoalProgressApplicationResult(goalId, false, currentSubGoalId, currentSubGoalId, false);
    }

    /** Logs the refusal and records the contradiction on the goal, for both completion paths. */
    private void recordRefutedClosure(String goalId, ClaimRefutation refutation) {
        String contradiction = refutation.description();
        log.warn("T4-CU: refusing to close {} — the running artifact contradicts its own acceptance"
                + " criteria: {}", goalId, contradiction);
        markGoalStatus(goalId, gx -> {
            gx.getMetadata().put("closureRefutedBy", contradiction);
            return null;
        });
        GoalEntity g = goalMap.get(goalId);
        if (g != null) goalGraphService.save(g);
        recordClosureRefusal("T4-CU", goalId, contradiction,
                refutation.refutedCriteria());
    }

    /**
     * T4-CU: one home for "a closure gate refused, and this is what it refused on".
     *
     * <p>Eleven gates block root or subgoal closure and every one of them reported it with a
     * {@code log.warn} alone — no event, no graph record. So the question "which gate refused this
     * closure, and on what" could only be answered by a regex over an archived log, which is why
     * the refusal rate went 25 runs without being measured at all. The counted event is the
     * actuator's prerequisite: it names the gate, so a run says which gate is doing the blocking
     * rather than leaving a reader to grep for eleven different sentences.
     *
     * <p>{@code criteria} is empty for a gate that does not judge criteria, and for a criteria
     * refutation nothing could attribute — see {@link ClaimRefutation}.
     */
    void recordClosureRefusal(String gate, String goalId, String reason, List<String> criteria) {
        // T4-DY slice B: the same refusal again is a stuck observation -- keyed by what was refused
        // and why, in words the evolution prompt can carry (no goal id, no gate name).
        if (stuckLayer != null && reason != null) {
            GoalEntity refused = goalMap.get(goalId);
            String what = refused != null && refused.getDescription() != null
                    ? refused.getDescription().strip().replaceAll("\\s+", " ") : "the goal";
            if (what.length() > 160) what = what.substring(0, 160) + "...";
            String why = reason.strip().replaceAll("\\s+", " ");
            if (why.length() > 400) why = why.substring(0, 400) + "...";
            stuckLayer.report(com.walpro.autobuilder.core.stuck.StuckObservation.Source.CLOSURE_REFUSAL,
                    "closing '" + what + "' was refused: " + why, criteria == null ? List.of() : criteria);
        }
        if (behaviourLogger == null) return;
        behaviourLogger.closureRefused(
                currentCycleContext != null ? currentCycleContext.cycleName() : null,
                gate, goalId, reason, criteria == null ? List.of() : criteria);
    }

    private void bankAllCriteriaTrueIfDefined(String goalId) {
        GoalEntity g = goalMap.get(goalId);
        if (g == null) return;
        Object raw = g.getMetadata().get("acceptanceCriteria");
        if (!(raw instanceof List<?> l) || l.isEmpty()) return;
        Map<Integer, Boolean> all = new LinkedHashMap<>();
        for (int i = 1; i <= l.size(); i++) all.put(i, Boolean.TRUE);
        saveCriteriaPassStatus(goalId, all);
    }

    /**
     * Sets status SUBSUMED directly (skipping the GIVEN_UP transition + cascade in
     * {@link #markGoalGivenUp}) so the T2-DR closure rule and abandoned-goal query treat the
     * goal as intentionally retired rather than failed work. Advances {@code currentSubGoalId}
     * when the retired goal was current. Persists to Neo4j and JSON.
     */
    private GoalProgressApplicationResult transitionToSubsumed(GoalEntity g, String resolution,
                                                                SubsumptionReason subsumptionReason) {
        String goalId = g.getId();
        String beforeCurrentSubGoalId = currentSubGoalId;
        GoalStatus rootBefore = (rootGoalId != null && goalMap.get(rootGoalId) != null)
                ? goalMap.get(rootGoalId).getStatus()
                : null;

        markGoalStatus(goalId, gx -> {
            gx.setResolutionIfAbsent(resolution);
            gx.setStatus(GoalStatus.SUBSUMED);
            gx.setGivenUp(java.time.Instant.now());
            if (subsumptionReason != null) {
                gx.getMetadata().put(SubsumptionReason.METADATA_KEY, subsumptionReason.name());
            }
            return null;
        });
        goalGraphService.save(g);
        log.info("transitionGoal: goalId={} -> SUBSUMED (reason={}, resolution='{}')",
                goalId, subsumptionReason, resolution);

        if (goalId.equals(currentSubGoalId)) {
            String next = findNextSiblingOrAncestor(goalId);
            if (next == null) next = resolveNextActiveLeaf(rootGoalId);
            currentSubGoalId = next;
            if (currentSubGoalId != null) ensureAttemptIdsFor(currentSubGoalId);
            persistPointersToGraph();
        }

        GoalStatus rootAfter = (rootGoalId != null && goalMap.get(rootGoalId) != null)
                ? goalMap.get(rootGoalId).getStatus()
                : null;
        boolean rootAutoCompleted = (rootBefore == GoalStatus.ACTIVE && rootAfter == GoalStatus.COMPLETED);
        return new GoalProgressApplicationResult(goalId, false,
                beforeCurrentSubGoalId, currentSubGoalId, rootAutoCompleted);
    }

    /**
     * @deprecated External callers must use
     * {@link #transitionGoal(String, GoalStatus, String)} with {@link GoalStatus#COMPLETED} so
     * criteria banking is paired with the status flip. Kept package-private as a cascade /
     * promotion adapter for internal use.
     */
    @Deprecated
    MarkCompletedResult markGoalCompleted(String id) {
        return markGoalCompleted(id, null);
    }

    /** @deprecated see {@link #markGoalCompleted(String)}. */
    @Deprecated
    MarkCompletedResult markGoalCompleted(String id, String resolution) {
        // T1-DD: refuse to mark a node COMPLETED while any direct descendant is ACTIVE.
        // Otherwise children become unreachable — getRemainingGoals filters them out via the
        // now-COMPLETED parent and resolveNextActiveLeaf cannot re-enter through a terminal
        // ancestor — so the dead branch silently drops out of the active backlog and the
        // first-wave completion ratio drifts off the actual tree state.
        // T1-ET Part B: returns a structured {@link MarkCompletedResult} so callers
        // (promoteCurrentSubGoal, applyGoalProgress) can distinguish a real flip from a
        // T1-DD refusal — previously the refusal was a silent side-effect on
        // {@code currentSubGoalId} that callers clobbered immediately after.
        GoalEntity g = goalMap.get(id);
        if (g == null) {
            return new MarkCompletedResult.UnknownGoal(id);
        }
        long activeChildren = getSubGoals(id).stream()
                .filter(c -> c.getStatus() == GoalStatus.ACTIVE)
                .count();
        if (activeChildren > 0) {
            String activeLeaf = resolveNextActiveLeaf(id);
            log.warn("T1-DD: refusing to mark [{}] COMPLETED — {} active descendants. Re-pointing currentSubGoalId to next active leaf {}.",
                    id, activeChildren, activeLeaf);
            if (behaviourLogger != null) {
                String cycleId = currentCycleContext != null ? currentCycleContext.cycleName() : "unknown";
                behaviourLogger.guardViolated(cycleId, "T1-DD",
                        "refused COMPLETED on goalId=" + id + " with " + activeChildren + " active descendants; repointing to " + activeLeaf);
            }
            if (activeLeaf != null && !activeLeaf.equals(currentSubGoalId)) {
                currentSubGoalId = activeLeaf;
                ensureAttemptIdsFor(currentSubGoalId);
                persistPointersToGraph();
            }
            return new MarkCompletedResult.RefusedActiveDescendants(id, activeLeaf, activeChildren);
        }
        // T4-CU: the last point before the flip, and the one both completion paths reach —
        // transitionGoal(COMPLETED) arrives via applyGoalProgress, promoteCurrentSubGoal calls
        // here directly. A claim the running artifact contradicts must not become a banked fact:
        // run 2026-07-30_21-25-43 closed a validation subgoal whose criteria said POST /books
        // answers 400 while the endpoint answered 201, and the banked tick then starved the
        // parent that had correctly diagnosed the same defect.
        //
        // The verifier only ever refutes — silence means "not checked" as often as "holds" — so
        // this can turn a false COMPLETED into an honest still-ACTIVE and can never block a
        // closure on absence of evidence.
        Optional<ClaimRefutation> refuted = refuteClosureClaim(id);
        if (refuted.isPresent()) {
            recordRefutedClosure(id, refuted.get());
            return new MarkCompletedResult.RefusedClaimRefuted(id, refuted.get());
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
        return new MarkCompletedResult.Completed(id);
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
            if (cursor.getStatus() != GoalStatus.ACTIVE) {
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

    /**
     * @deprecated External callers must use
     * {@link #transitionGoal(String, GoalStatus, String)} — pass
     * {@link GoalStatus#GIVEN_UP} when criteria are genuinely unmet, or
     * {@link GoalStatus#SUBSUMED} when retiring the goal because replacement work covers it.
     * Kept package-private as a cascade adapter.
     */
    @Deprecated
    void markGoalGivenUp(String id) {
        markGoalGivenUp(id, null);
    }

    /** @deprecated see {@link #markGoalGivenUp(String)}. */
    @Deprecated
    void markGoalGivenUp(String id, String resolution) {
        markGoalStatus(id, g -> {
            g.setResolutionIfAbsent(resolution);
            g.markGivenUp();
            return null;
        });

        GoalEntity g = goalMap.get(id);
        if (g != null) {
            // T2-DR: promote to SUBSUMED when the subgoal's own acceptance criteria are
            // already all banked-true. Distinguishes "given up but work already done" from
            // "given up because we failed" so the observation-ledger closure rule can pass
            // over it instead of blocking root completion.
            if (g.getType() == GoalType.SUBGOAL && allCriteriaDefinedAndBanked(id)) {
                g.setStatus(GoalStatus.SUBSUMED);
                log.info("T2-DR: subgoalId={} promoted GIVEN_UP -> SUBSUMED (all criteria banked at give-up)", id);
            }
            goalGraphService.save(g); // properties-only update of status + resolution
            // T1-CA + T1-EJ: cascade GIVEN_UP to direct ACTIVE children, but skip merge-bearing
            // descendants — children with mergeCount > 0 and no outstanding suppressed evictions
            // carry real merged work and must not be silently wiped. They may still exhaust
            // naturally via the per-parent exhaustion path. Mirrors the isMergeBearingProtected
            // criterion at line ~374.
            for (GoalEntity child : getSubGoals(id)) {
                if (child.getStatus() != GoalStatus.ACTIVE) continue;
                int childMergeCount = child.getMergeCount();
                int childSuppressedEvictions = getGoalMetadataInt(child, "consecutiveSuppressedEvictions");
                if (childMergeCount > 0 && childSuppressedEvictions == 0) {
                    // T1-EK: reparent merge-bearing protected children so the loop can still reach them.
                    String adopted = adoptStrandedChild(child, id, resolution);
                    log.info("markGoalGivenUp cascade: goalId={} PROTECTED (mergeCount={}, parent={}) — adopted to ancestorId={}",
                            child.getId(), childMergeCount, id, adopted);
                    continue;
                }
                log.info("markGoalGivenUp cascade: goalId={} marked GIVEN_UP (parent={})", child.getId(), id);
                markGoalGivenUp(child.getId(), "cascade from parent: " + (resolution != null ? resolution : "given up"));
            }
        }
    }

    /**
     * T1-EK: when the cascade in {@link #markGoalGivenUp} would strand a merge-bearing
     * child under a GIVEN_UP parent, reparent the child to its closest ACTIVE ancestor
     * (root if none). Records adoption lineage on the child's metadata so the transition
     * is auditable and the {@link #isStaleOrphan} guard recognises the child as belonging
     * to the current planning run.
     *
     * <p>Returns the id of the new parent the child was adopted to.
     */
    private String adoptStrandedChild(GoalEntity child, String givenUpParentId, String givenUpResolution) {
        // T4-AJ Part A: prefer a semantically compatible ACTIVE goal anywhere in the tree over
        // the purely structural nearest-ACTIVE-ancestor choice. Candidates exclude descendants
        // of the dying parent (would re-strand the child) and the root. Best-effort: reviewer
        // unavailable / no confident match → fall through to the T1-EK ancestor logic.
        String semanticParentId = findSemanticAdoptiveParent(child, givenUpParentId);

        String targetParentId = semanticParentId != null ? semanticParentId : rootGoalId;
        if (semanticParentId == null) {
            GoalEntity givenUpParent = goalMap.get(givenUpParentId);
            String grandparentId = givenUpParent != null ? givenUpParent.getParentId() : null;
            if (grandparentId != null && !grandparentId.equals(givenUpParentId)) {
                GoalEntity grandparent = goalMap.get(grandparentId);
                if (grandparent != null && grandparent.getStatus() == GoalStatus.ACTIVE) {
                    targetParentId = grandparentId;
                }
            }
        }
        if (targetParentId == null || targetParentId.equals(givenUpParentId)) {
            targetParentId = rootGoalId;
        }

        String originalParentId = child.getParentId();
        child.setParentId(targetParentId);

        Map<String, Object> meta = child.getMetadata();
        if (meta == null) {
            meta = new HashMap<>();
            child.setMetadata(meta);
        }
        meta.put("adoptedFromParentId", originalParentId);
        meta.put("adoptionReason", "parent given up: " + (givenUpResolution != null ? givenUpResolution : "given up"));
        // T4-AJ: record how the parent was chosen — semantic (reviewer match) vs structural
        // (nearest ACTIVE ancestor / root fallback).
        meta.put("adoptionSource", semanticParentId != null ? "semantic-reviewer" : "structural-ancestor");
        if (currentCycleContext != null && currentCycleContext.cycleName() != null) {
            meta.put("adoptionCycleId", currentCycleContext.cycleName());
        }
        meta.put("adoptionPlanningRunId", currentPlanningRunId);
        // Re-stamp planningRunId so isStaleOrphan no longer flags the adopted child.
        meta.put("planningRunId", currentPlanningRunId);

        try {
            goalGraphService.save(child);
            goalGraphService.setParent(child.getId(), targetParentId);
        } catch (HttpServerErrorException e) {
            log.debug("Neo4j adoption save failed for {} (ignoring): {}", child.getId(), e.getMessage());
        }
        persist();
        return targetParentId;
    }

    /**
     * T4-AJ Part A: consults the shared equivalence reviewer for an ACTIVE goal the orphan's
     * work logically belongs with. Candidates: ACTIVE subgoals that are not the root, not the
     * orphan itself, and not descendants of the dying parent (avoid cycles / re-stranding).
     * Returns the chosen parent id, or {@code null} when the reviewer is unwired, finds no
     * confident match, or the candidate set is empty.
     */
    private String findSemanticAdoptiveParent(GoalEntity child, String givenUpParentId) {
        if (goalAdoptionService == null) return null;
        List<GoalEntity> candidates = goalMap.values().stream()
                .filter(g -> g.getStatus() == GoalStatus.ACTIVE)
                .filter(g -> g.getType() == GoalType.SUBGOAL)
                .filter(g -> !g.getId().equals(child.getId()))
                .filter(g -> !g.getId().equals(givenUpParentId))
                .filter(g -> !isDescendantOf(g, givenUpParentId))
                .filter(g -> !isDescendantOf(g, child.getId()))
                .toList();
        if (candidates.isEmpty()) return null;
        return goalAdoptionService.findAdoptiveParent(child, candidates)
                .map(GoalEntity::getId)
                .orElse(null);
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

        // T1-CZ: analysis-spawned children are augmentations, not primary work items — exclude
        // them from exhaustion accounting so their failure cannot cascade to the parent.
        List<GoalEntity> regularSiblings = siblings.stream()
                .filter(s -> !"analysis".equals(s.getMetadata().get("spawnedBy")))
                .toList();

        long givenUpCount = regularSiblings.stream().filter(s -> s.getStatus() == GoalStatus.GIVEN_UP).count();
        long completedCount = regularSiblings.stream().filter(s -> s.getStatus() == GoalStatus.COMPLETED).count();
        int effectiveThreshold = Math.max(minFloor, (int) Math.ceil(regularSiblings.size() * 0.51));

        if (givenUpCount >= effectiveThreshold && completedCount == 0) {
            log.warn("Per-parent exhaustion: {}/{} regular children GIVEN_UP (threshold={}) with no completions under parentId={}. Marking parent GIVEN_UP.",
                    givenUpCount, regularSiblings.size(), effectiveThreshold, parentId);
            String exhaustionRationale = "per-parent exhaustion: " + givenUpCount + "/" + regularSiblings.size()
                    + " regular children given up (threshold=" + effectiveThreshold + ")";
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

        // T1-CZ: regular threshold not met because analysis-spawned children were excluded.
        // If all children are terminal and the parent has cumulatively satisfied criteria,
        // promote to FULFILLED so the branch does not strand as ACTIVE with no reachable leaf.
        boolean hasAnalysisChildren = regularSiblings.size() < siblings.size();
        boolean allChildrenTerminal = siblings.stream()
                .allMatch(s -> s.getStatus() != GoalStatus.ACTIVE);
        if (hasAnalysisChildren && allChildrenTerminal && areCriteriaCumulativelySatisfied(parentId)) {
            log.warn("T1-CZ: all children terminal; analysis-spawned children exhausted but parent [{}] has cumulatively satisfied criteria — promoting to FULFILLED.",
                    parentId);
            applyGoalProgress(parentId, GoalProgressState.FULFILLED,
                    "T1-CZ: parent criteria satisfied; analysis-spawned children exhausted");
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
        return resolveNextActiveLeaf(parentId, heldByAdmission());
    }

    private String resolveNextActiveLeaf(String parentId, Set<String> held) {
        if (parentId == null || !goalMap.containsKey(parentId)) return null;

        List<GoalEntity> children = getSubGoals(parentId);
        for (GoalEntity child : children) {
            if (child.getStatus() != GoalStatus.ACTIVE) continue;

            String deeper = resolveNextActiveLeaf(child.getId(), held);
            if (deeper != null) return deeper;

            // T2-EI: a held active leaf is not selectable as current — skip it so its files never
            // reach the shared disk. It keeps ACTIVE status and will be surfaced once admitted
            // leaves terminate and the working set drops below the cap.
            if (held.contains(child.getId())) continue;

            return child.getId();
        }

        return null;
    }

    /**
     * T2-EI count-based admission queue: returns the ids of ACTIVE leaf subgoals currently HELD
     * (beyond {@code admission-cap}). Held leaves keep their ACTIVE status — so they still block
     * premature root completion — but are excluded from current-subgoal selection so no agent works
     * them. Admission is deterministic: the current subgoal (if any) is always admitted, then the
     * oldest leaves by creation time fill the remaining cap slots; the newest surplus is held.
     * Empty when the cap is disabled (0) or the active-leaf count is within the cap — so default
     * behaviour is unchanged. See {@link GoalPlannerConfigProperties#getAdmissionCap()}.
     */
    private Set<String> heldByAdmission() {
        int cap = goalPlannerConfig != null ? goalPlannerConfig.getAdmissionCap() : 0;
        if (cap <= 0) return Set.of();

        List<GoalEntity> leaves = goalMap.values().stream()
                .filter(g -> g.getType() == GoalType.SUBGOAL && g.getStatus() == GoalStatus.ACTIVE)
                .filter(g -> !isStaleOrphan(g))
                .filter(g -> getSubGoals(g.getId()).stream()
                        .noneMatch(c -> c.getStatus() == GoalStatus.ACTIVE))
                .sorted(java.util.Comparator.comparing(GoalEntity::getCreated,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
        if (leaves.size() <= cap) return Set.of();

        java.util.LinkedHashSet<String> admitted = new java.util.LinkedHashSet<>();
        if (currentSubGoalId != null
                && leaves.stream().anyMatch(g -> g.getId().equals(currentSubGoalId))) {
            admitted.add(currentSubGoalId);
        }
        for (GoalEntity g : leaves) {
            if (admitted.size() >= cap) break;
            admitted.add(g.getId());
        }
        Set<String> held = new java.util.HashSet<>();
        for (GoalEntity g : leaves) {
            if (!admitted.contains(g.getId())) held.add(g.getId());
        }
        if (!held.isEmpty()) {
            log.info("T2-EI admission queue: {} active leaf(s), cap {} — holding {} (admitted {})",
                    leaves.size(), cap, held.size(), admitted.size());
        }
        return held;
    }

    /** T2-EI: true when {@code id} is an ACTIVE leaf currently held by the admission cap. */
    private boolean isHeldLeaf(String id) {
        return id != null && heldByAdmission().contains(id);
    }

    public List<GoalEntity> getAllGoals() {
        return List.copyOf(goalMap.values());
    }

    /**
     * T4-I (root-scoped slice): number of COMPLETED subgoals that carried at least one merge.
     * This is the convergence measure the planner cannot reset by renaming or re-decomposing
     * work — completed goals stay completed, and the merge requirement means each one
     * represents accepted work that stuck on disk.
     */
    public int countCompletedSubgoalsWithMerges() {
        // T4-I measure, extended 2026-06-12: a completion counts as real convergence progress
        // when it is backed by EITHER a merge of its own OR fully-verified banked criteria.
        // Rescue-promoted subgoals (work landed under siblings; criteria verified against the
        // output by T4-BP) carry no mergeCount — under the merge-only measure the brake fired
        // at 20:00:01 in the same second a 3/3-verified promotion landed (count frozen at 8
        // despite observation-backed completions accruing). Verified-banked criteria are exactly
        // the planner-unfakeable observation the measure exists to count.
        return (int) goalMap.values().stream()
                .filter(g -> g.getType() == GoalType.SUBGOAL)
                .filter(g -> g.getStatus() == GoalStatus.COMPLETED)
                .filter(g -> getGoalMetadataInt(g, "mergeCount") > 0
                        || areCriteriaCumulativelySatisfied(g.getId()))
                .count();
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

    /**
     * T4-DQ blocker 1: put the goal tree back the way it was at a fold cut point.
     *
     * <p>Two things move, and they are different. STATUS comes back from the fold — the log
     * remembers what each goal was before it was overwritten, which is precisely what mutating in
     * place destroyed. EXISTENCE is the other half and the easier one to miss: a goal the planner
     * thought of after the cut point had not been thought of yet in the restored state, so it is
     * rewound OUT — recorded as {@link GoalTransition.Kind#REMOVED} in the log and tombstoned in
     * the graph rather than deleted, because the history stays append-only and the graph browser
     * should still be able to see that the goal was once planned.
     *
     * <p>The rewind's own writes are themselves appended, tagged {@link GoalTransition.Kind#REWOUND}
     * so the log distinguishes "the loop decided this" from "a rewind put this back". Nothing here
     * rewrites an earlier line.
     *
     * @param statuses         the fold's status per goal at the cut point
     * @param existing         the goals that existed at the cut point
     * @param restoredSubGoalId the pointer as of the checkpoint, re-resolved if it is no longer valid
     * @return how many goals were restored, and how many were rewound out
     */
    public synchronized RewindResult restoreGoalStateAsOf(Map<String, GoalStatus> statuses,
                                                          java.util.Set<String> existing,
                                                          String restoredSubGoalId) {
        return restoreGoalStateAsOf(statuses, existing, restoredSubGoalId, null);
    }

    /**
     * T4-DQ follow-up: the same restore, also moving the ROOT to the one the checkpoint recorded.
     *
     * <p>Within a run the root never changes, so nothing needed to restore it. Across a lineage it
     * is a different goal, and the fold removes the live root because it is not among the goals
     * that existed at the cut — leaving the pointer aimed at a goal the map no longer holds. The
     * loop then threw {@code Parent goal not found: <live root>} on its first tick after a rewind
     * that had reported one divergence (2026-09-14). The checkpoint has always carried
     * {@code rootGoalId}; the restore simply never read it.
     */
    public synchronized RewindResult restoreGoalStateAsOf(Map<String, GoalStatus> statuses,
                                                          java.util.Set<String> existing,
                                                          String restoredSubGoalId,
                                                          String restoredRootGoalId) {
        int restored = 0;
        int removed = 0;
        rewindInProgress = true;
        try {
            java.util.List<String> toRemove = new java.util.ArrayList<>();
            for (String id : goalMap.keySet()) {
                if (existing != null && !existing.isEmpty() && !existing.contains(id)) toRemove.add(id);
            }
            for (String id : toRemove) {
                GoalEntity g = goalMap.remove(id);
                if (g != null && goalTransitionLog != null) {
                    goalTransitionLog.append(id, g.getStatus() != null ? g.getStatus().name() : null,
                            g.getStatus() != null ? g.getStatus().name() : GoalStatus.ACTIVE.name(),
                            GoalTransition.Kind.REMOVED, currentCycleName(), "rewound out — planned after the cut point");
                }
                removed++;
            }
            if (!toRemove.isEmpty() && goalGraphService != null) {
                try {
                    goalGraphService.markRewoundOut(toRemove, currentCycleName());
                } catch (Exception e) {
                    log.warn("T4-DQ: could not tombstone {} rewound-out goals in the graph: {}",
                            toRemove.size(), e.getMessage());
                }
            }

            // A goal that existed at the cut point and has since been pruned from the live tree has
            // to come BACK, or the fold only ever shrinks: it can remove what was planned after the
            // checkpoint and is powerless over what was deleted after it. The transition log knows
            // such a goal existed but not what it said, so the text comes from the graph, which
            // keeps goal nodes rather than deleting them. Without this, a rewind — and especially a
            // cross-run one, where more has had time to be pruned — silently restores a smaller
            // tree than the one it claims to be returning to.
            // The set to resurrect is EXISTENCE, not status. `foldOver` adds every goal it sees to
            // `existing` but only records a status when the transition carries one, so a goal that
            // existed at the cut point and never changed status appears in one set and not the
            // other. Resurrecting over `statuses` alone therefore restored a strict subset while
            // removal was already gated on `existing` — the fold could delete a goal it was unable
            // to bring back. Seen on the first cross-lineage rewind (2026-09-14): tree restored,
            // 17 goals rewound out, 1 status restored, and the loop left with no current subgoal
            // at all. Union, so the two halves of the same fold agree.
            java.util.Set<String> toResurrect = new java.util.LinkedHashSet<>();
            if (existing != null) toResurrect.addAll(existing);
            if (statuses != null) toResurrect.addAll(statuses.keySet());

            int resurrected = 0;
            if (goalGraphService != null) {
                for (String id : toResurrect) {
                    if (goalMap.containsKey(id)) continue;
                    try {
                        GoalEntity fromGraph = goalGraphService.findById(id).orElse(null);
                        if (fromGraph == null) continue;
                        goalMap.put(id, fromGraph);
                        resurrected++;
                    } catch (Exception e) {
                        log.debug("T4-DQ: could not restore pruned goal {} from the graph: {}",
                                id, e.getMessage());
                    }
                }
                if (resurrected > 0) {
                    log.info("T4-DQ: restored {} goal(s) that existed at the cut point and had since "
                            + "been pruned from the live tree.", resurrected);
                }
            }

            if (statuses != null) {
                for (Map.Entry<String, GoalStatus> entry : statuses.entrySet()) {
                    GoalEntity g = goalMap.get(entry.getKey());
                    if (g == null || entry.getValue() == null) continue;
                    GoalStatus before = g.getStatus();
                    if (before == entry.getValue()) continue;
                    g.setStatus(entry.getValue());
                    if (entry.getValue() != GoalStatus.COMPLETED) g.setCompleted(null);
                    if (entry.getValue() != GoalStatus.GIVEN_UP) g.setGivenUp(null);
                    if (goalTransitionLog != null) {
                        goalTransitionLog.append(g.getId(), before != null ? before.name() : null,
                                entry.getValue().name(), GoalTransition.Kind.REWOUND,
                                currentCycleName(), "rewound to the checkpoint's fold");
                    }
                    if (goalGraphService != null) {
                        try {
                            goalGraphService.save(g);
                        } catch (Exception e) {
                            log.debug("T4-DQ: graph save during rewind failed for {}: {}", g.getId(), e.getMessage());
                        }
                    }
                    restored++;
                }
            }

            // Move the root BEFORE resolving the leaf: resolveNextActiveLeaf walks down from
            // rootGoalId, so a stale root would search a tree that is no longer there.
            if (restoredRootGoalId != null && goalMap.containsKey(restoredRootGoalId)) {
                if (!restoredRootGoalId.equals(this.rootGoalId)) {
                    log.info("T4-DQ: root goal moved {} -> {} by the rewind (the checkpoint's own root).",
                            this.rootGoalId, restoredRootGoalId);
                }
                this.rootGoalId = restoredRootGoalId;
            } else if (restoredRootGoalId != null) {
                log.warn("T4-DQ: the checkpoint's root [{}] is not in the restored goal map — leaving "
                        + "the root at [{}] rather than pointing at a goal that is not there.",
                        restoredRootGoalId, this.rootGoalId);
            }

            this.currentSubGoalId = restoredSubGoalId != null
                    && goalMap.containsKey(restoredSubGoalId)
                    && goalMap.get(restoredSubGoalId).getStatus() == GoalStatus.ACTIVE
                    ? restoredSubGoalId
                    : resolveNextActiveLeaf(rootGoalId);
        } finally {
            rewindInProgress = false;
        }

        lastObservedStatus.clear();
        for (GoalEntity g : goalMap.values()) lastObservedStatus.put(g.getId(), g.getStatus());
        persistPointersToGraph();
        persist();
        log.info("T4-DQ: goal state rewound — {} statuses restored, {} goals rewound out, currentSubGoalId={}",
                restored, removed, currentSubGoalId);
        return new RewindResult(restored, removed, currentSubGoalId);
    }

    /** What {@link #restoreGoalStateAsOf} did. */
    public record RewindResult(int statusesRestored, int goalsRewoundOut, String currentSubGoalId) {}

    private String currentCycleName() {
        return currentCycleContext != null ? currentCycleContext.cycleName() : null;
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
        return directChildren.stream().allMatch(g -> g.getStatus() != GoalStatus.ACTIVE);
    }

    /**
     * T2-DR: observation-ledger closure. Root reaches COMPLETED iff every subgoal in its tree
     * is one of: (a) COMPLETED with all {@code criteriaPassStatus} entries true; (b) {@link
     * GoalStatus#SUBSUMED}. Any other terminal state (GIVEN_UP with at least one unmet
     * criterion, or a subgoal lacking declared {@code acceptanceCriteria}) blocks closure.
     * <p>
     * When every descendant is terminal but at least one blocks, root flips to GIVEN_UP
     * and a {@code GOAL_EXHAUSTED} signal is raised. Replaces the previous first-wave
     * completion-ratio rule (which let later-wave features go uncounted).
     */
    /**
     * Replan atomicity (2026-06-09 design lens): true while the planner agent is executing its
     * prune-then-inject sequence. {@link #autoCompleteRootIfSatisfied()} defers while set, so a
     * transiently-empty active set mid-replan can never complete the root.
     */
    private volatile boolean planningInProgress = false;

    /** Called by the planner agent before its first tree mutation. Pair with {@link #endPlanning()} in a finally block. */
    public void beginPlanning() {
        planningInProgress = true;
    }

    /** Called by the planner agent after its last tree mutation (always — also on failure paths). */
    public void endPlanning() {
        planningInProgress = false;
    }

    /** T2-EH: outcome of {@link #verifyAndPromoteBeforePause(CycleContextData)}. */
    public record VerifyBeforePauseResult(boolean rootCompleted, int promoted) {
        public boolean madeProgress() {
            return rootCompleted || promoted > 0;
        }
    }

    /**
     * T2-EH / T4-BP (promoting direction): verify-before-pause at the convergence-stall edge.
     *
     * <p>A T4-I convergence-stall or T1-EI planner-empty halt would otherwise abandon a
     * near-complete artifact WITHOUT ever asking "is it actually done?". The mechanism the brake
     * catches is real (2026-06-24 76-cycle run): merges keep landing but subgoals stop being
     * promoted — the evaluator banks an internal-structure criterion as ✗ even though the
     * behaviour is present, or the final code is never re-graded against the banked status — so
     * {@link #autoCompleteRootIfSatisfied()} never runs (it early-returns while any subgoal is
     * ACTIVE) and the brake just halts.
     *
     * <p>This method closes that gap: before such a halt, re-verify every ACTIVE leaf subgoal's
     * acceptance criteria against the <i>current</i> output (the same focused LLM pass the
     * exhaustion-rescue uses) and promote the ones that pass — including those whose criteria are
     * already banked but were never promoted — then attempt root completion through the existing
     * completion-edge gates. Returns {@link VerifyBeforePauseResult#rootCompleted()} when the root
     * reached COMPLETED (caller must not halt) and the count promoted (caller may reset the stall
     * counter rather than halt when any progress was made).
     *
     * <p>Conservative: promotes only on an all-criteria-pass result; abstains (no promotion) on
     * empty/failed verification, so a transient LLM hiccup never falsely completes a subgoal.
     */
    public VerifyBeforePauseResult verifyAndPromoteBeforePause(CycleContextData context) {
        GoalEntity root = getRootGoal();
        if (root == null) return new VerifyBeforePauseResult(false, 0);
        if (root.getStatus() == GoalStatus.COMPLETED) return new VerifyBeforePauseResult(true, 0);
        if (root.getStatus() != GoalStatus.ACTIVE) return new VerifyBeforePauseResult(false, 0);

        // Snapshot active leaves up front — promotion mutates goalMap statuses.
        List<GoalEntity> activeLeaves = goalMap.values().stream()
                .filter(g -> g.getType() == GoalType.SUBGOAL
                        && g.getStatus() == GoalStatus.ACTIVE
                        && isDescendantOf(g, root.getId())
                        && hasNoActiveDescendants(g.getId()))
                .toList();

        int promoted = 0;
        for (GoalEntity sg : activeLeaves) {
            String sgId = sg.getId();
            // Already banked but never promoted — the brake's "merges land, no promotion" case.
            if (allCriteriaDefinedAndBanked(sgId)) {
                applyGoalProgress(sgId, GoalProgressState.FULFILLED,
                        "T2-EH: criteria already banked — promoted before convergence-stall pause");
                promoted++;
                continue;
            }
            if (criteriaVerificationService == null || context == null) continue;
            Map<Integer, Boolean> passMap = criteriaVerificationService.verifyAndRecord(
                    context, sgId, acceptanceCriteriaOf(sg), sg.getDescription());
            if (!passMap.isEmpty() && passMap.values().stream().allMatch(Boolean::booleanValue)) {
                applyGoalProgress(sgId, GoalProgressState.FULFILLED,
                        "T2-EH: acceptance criteria verified against current code before pause");
                promoted++;
            }
        }
        if (promoted > 0) {
            log.info("T2-EH: verify-before-pause promoted {} of {} active leaf subgoal(s) against current code",
                    promoted, activeLeaves.size());
        }

        // Attempt root completion now that done-but-unbanked subgoals are promoted. autoComplete
        // early-returns while any subgoal is still ACTIVE, so this completes the root only when the
        // residual active set is empty (genuinely done) AND the completion-edge gates pass.
        autoCompleteRootIfSatisfied();
        GoalEntity after = getRootGoal();
        boolean completed = after != null && after.getStatus() == GoalStatus.COMPLETED;
        return new VerifyBeforePauseResult(completed, promoted);
    }

    /** T2-EH: reads a subgoal's declared acceptance criteria as a string list (empty when none). */
    private List<String> acceptanceCriteriaOf(GoalEntity g) {
        Object raw = g.getMetadata() != null ? g.getMetadata().get("acceptanceCriteria") : null;
        if (!(raw instanceof List<?> l)) return List.of();
        List<String> out = new ArrayList<>(l.size());
        for (Object o : l) {
            if (o != null) out.add(o.toString());
        }
        return out;
    }


    /**
     * T4-DK: a closure gate detected a real failure and could not inject its repair, because the
     * work item deduplicates against one already GIVEN_UP. **Exhausting the repair budget means
     * the requirement is unmet and currently unfixable — not that it is met.**
     *
     * <p>Seven gates (endpoints, coverage, packaging, boot, startup wiring, tests, criteria
     * re-verification) each drew the opposite conclusion and logged "proceeding with completion".
     * One invariant living in seven places is a missing capability, not seven policies
     * ([[feedback-one-invariant-one-home]]), and it produced the fourth false completion: on run
     * 2026-08-28_05-27-49 the criteria re-verification DID find unconfirmed criteria at the final
     * closure attempt, tried to block, hit this bypass, and the root completed on an artifact whose
     * renderer was a placeholder comment and whose HUD did not exist.
     *
     * <p>Refusing closure here cannot deadlock into a false success: the root simply does not
     * complete this cycle, and the existing exhaustion / convergence-stall machinery owns the
     * terminal. A stall is an honest halt; a fulfilled claim over an unmet requirement is not.
     *
     * <p><b>Measured safe before it was written:</b> across all seven archived {@code
     * GOAL_FULFILLED} runs, six used this bypass ZERO times — including both 23/23 runs and the
     * 12/12 CLI run. Only the false completion used it, twice. Tightening it costs no archived
     * success.
     */
    /**
     * T4-DT: run the specification's uncovered obligations against the artifact, and report a
     * contradiction if one is observed.
     *
     * <p>Abstains — empty — on everything the probe abstains on: no verifier wired, nothing stated,
     * an unpackageable tree, no obligation a probe can be built for. Absence of evidence is not a
     * refutation here any more than it is anywhere else in this chain.
     */
    private Optional<String> refuteUncoveredSeedByExecution(GoalEntity root, List<String> uncoveredSeed) {
        if (closureClaimVerifier == null || uncoveredSeed == null || uncoveredSeed.isEmpty()) {
            return Optional.empty();
        }
        try {
            // T4-CU: this caller wants only the prose — it reports an uncovered SEED obligation,
            // not one of a work item's own criteria, so there is no bank to narrow and no repair
            // item to name. Mapping to the description keeps it exactly as it was.
            return closureClaimVerifier.refute(root.getId(), uncoveredSeed, root.getDescription())
                    .map(ClaimRefutation::description);
        } catch (RuntimeException e) {
            log.warn("T4-DT: execution check of the seed's obligations failed ({}) — treating as "
                    + "unchecked, not as refuted", e.toString());
            return Optional.empty();
        }
    }

    /**
     * The work item for a closure the artifact refuted, with a done-when the probe can re-check.
     *
     * <p><b>Its criteria are the EXERCISABLE subset, not everything offered.</b> An obligation no
     * probe can judge would sit on the item unverifiable and keep it open for the run's life, which
     * is walling — the failure this whole chain is built to avoid. The satisfied exercisable ones
     * cost nothing: they verify met and the item closes when the refuted one is repaired.
     */
    private void injectExecutionRefutation(GoalEntity root, String refutation, List<String> uncoveredSeed) {
        List<String> doneWhen = exercisableCriterionQuery == null ? List.of()
                : exercisableCriterionQuery.exercisable(uncoveredSeed, root.getDescription());
        CoverageReviewResult injected = injectCoverageGaps(root, List.of(
                "[BUILD_FIX] The running artifact contradicts the specification — " + refutation), true);
        if (injected.injected().isEmpty() || doneWhen.isEmpty()) return;
        for (String id : injected.injectedIds()) {
            // ⚠ MARKER FIRST, CRITERIA SECOND, for the reason recorded at the sibling site: the
            // binding stamp consults isFromCriteriaGate(metadata), so written the other way round
            // it reads a map without the marker and stamps the SPECIFICATION's own words AUTHORED.
            setGoalMetadataString(id, com.walpro.autobuilder.core.build.BlockerTypes.ORIGINATING_GATE_KEY,
                    com.walpro.autobuilder.core.build.BlockerTypes.GATE_CRITERIA);
            setAcceptanceCriteria(id, doneWhen);
        }
    }


    /**
     * T6-S: the obligations file's text, or null when the target has none.
     *
     * <p>Read per call rather than cached: it is a small tracked file, and an operator correcting a
     * split mid-run should see the correction take effect rather than on the next boot. Absent is
     * the ordinary case for a target that has not had one generated, and it costs the caller
     * nothing — the derived split runs exactly as before.
     */
    private String writtenObligations() {
        try {
            return fileService.readFile(SeedObligationsFile.PATH);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The seed obligations closure still has to ask about. A line the operator marked
     * {@code gate: tests} is the test gate's -- which ran above and passed, or closure would not be
     * here -- so it is not put to a model reading the source. When that gate is off it answered
     * nothing, and the line goes back to the model like any other.
     */
    private List<String> uncoveredSeedAtClosure(GoalEntity root, List<String> banked) {
        String written = writtenObligations();
        List<String> uncovered = new ArrayList<>(
                SeedRequirements.notAlreadyCoveredVerifiable(root.getDescription(), banked, written));
        List<String> testGateLines = SeedRequirements.answeredByGate(
                root.getDescription(), written, SeedRequirements.GATE_TESTS);
        if (testGateLines.isEmpty()) return uncovered;
        if (testRunGate != null && testRunGate.isLive()) {
            log.info("T4-CQ: {} seed obligation(s) answered by the test gate, not by a reading: {}",
                    testGateLines.size(), testGateLines);
        } else {
            uncovered.addAll(testGateLines);
        }
        return uncovered;
    }

    private void refuseClosureUnrepairable(String gate, String detail) {
        log.warn("T4-DK: root closure REFUSED by {} — {}; the repair work item deduplicates against "
                + "an already GIVEN_UP item, so the requirement is unmet and unfixable this cycle. "
                + "Not completing; the exhaustion ladder owns the terminal.", gate, detail);
    }


    /**
     * T4-DO: makes the packaged artifact runnable when that is decidable without a model, and
     * says so. Returns true when the pom was changed — the caller then blocks closure for this
     * cycle, and the next package gate rebuilds against the repaired pom.
     *
     * <p>Writes through the same {@code output/} path the injector uses, so the change is an
     * ordinary artifact mutation that the existing snapshot and rollback machinery already sees.
     */
    private boolean applyRunnablePackagingRepair() {
        if (currentCycleContext == null || currentCycleContext.outputPath() == null) return false;
        // T4-DO (routing, 2026-09-21): delegated so the WRITE has one home. This method used to
        // read the pom out of the cycle context and write it itself; the boot gate now applies the
        // same repair per cycle, and two copies of "which pom does this project need, and where
        // does it get written" is the shape that produced the seven-gate closure bypass. The
        // project on disk is what the package gate just built from and what it will rebuild from.
        return com.walpro.autobuilder.core.build.RunnableJarGuarantee
                .repairOnDisk(Path.of(currentCycleContext.outputPath()));
    }

    /** T4-CQ: inputs of the last closure attempt that left the root ACTIVE; derived, not restored. */
    private String lastIncompleteClosureInputs;
    private boolean closureRepeatReported;

    /** The tree the closure gates judge, plus every goal's state and criteria. */
    private String closureInputsFingerprint() {
        StringBuilder sb = new StringBuilder();
        Map<String, String> output = currentCycleContext != null ? currentCycleContext.previousOutput() : null;
        if (output != null) {
            new java.util.TreeMap<>(output).forEach((path, content) ->
                    sb.append(path).append('=').append(content == null ? 0 : content.hashCode()).append(';'));
        }
        sb.append('|');
        new java.util.TreeMap<>(goalMap).forEach((id, g) -> {
            sb.append(id).append(':').append(g.getStatus());
            if (g.getMetadata() != null) {
                // All of it: origin, criteria, banks, reopen counts -- any of them can change a verdict.
                sb.append(':').append(new java.util.TreeMap<>(g.getMetadata()));
            }
            sb.append(';');
        });
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            return sb.toString(); // every JVM ships SHA-256; the raw text is a correct fallback
        }
    }

    public void autoCompleteRootIfSatisfied() {
        // Replan atomicity: while the planner is mid-execution (prune already applied, injection
        // not yet), the active set is transiently empty — completing the root here is the
        // observed 2026-06-08 23:53:26 false positive (root COMPLETED, then the same replan
        // injected four new subgoals). Defer; the post-planning call sites re-evaluate.
        if (planningInProgress) {
            log.info("autoCompleteRootIfSatisfied deferred — replan in flight (prune-then-inject is not atomic)");
            return;
        }

        GoalEntity root = getRootGoal();
        if (root == null) return;
        if (root.getStatus() != GoalStatus.ACTIVE) return;

        List<GoalEntity> allSubgoals = goalMap.values().stream()
                .filter(g -> g.getType() == GoalType.SUBGOAL && isDescendantOf(g, root.getId()))
                .toList();
        if (allSubgoals.isEmpty()) return;

        boolean anyActive = allSubgoals.stream().anyMatch(g -> g.getStatus() == GoalStatus.ACTIVE);
        if (anyActive) return;

        // T4-CQ: an attempt whose inputs have not changed cannot reach a different verdict. The
        // gates below package, boot and test the artifact and may ask a model; the first adopted
        // project's sixth run re-ran all of them about twelve times per cycle against an unchanged
        // tree (146 boot-gate runs), each time dropping the same blocked repair as a paraphrase.
        // Asking a model again on identical inputs is not new evidence either. The fingerprint is
        // the tree the gates judge plus every goal's state, so a merge, a retirement or an edited
        // criterion re-opens the question at once.
        if (closureInputsFingerprint().equals(lastIncompleteClosureInputs)) {
            if (!closureRepeatReported) {
                closureRepeatReported = true;
                log.info("T4-CQ: closure not re-attempted — nothing it judges has changed since the "
                        + "last attempt, which did not complete the root");
            }
            return;
        }
        closureRepeatReported = false;
        try {
            attemptRootClosure(root, allSubgoals);
        } finally {
            // Recorded AFTER the attempt: an attempt that itself changes state (reopening a
            // blocker, injecting a repair) must not leave the pre-attempt state marked as judged.
            lastIncompleteClosureInputs = getRootGoal() != null
                    && getRootGoal().getStatus() == GoalStatus.ACTIVE ? closureInputsFingerprint() : null;
        }
    }

    private void attemptRootClosure(GoalEntity root, List<GoalEntity> allSubgoals) {
        List<String> blockers = new ArrayList<>();
        int passing = 0;
        int subsumed = 0;
        int absorbedExtra = 0;
        // T2-EI hierarchical closure: under depth-first, a failed EXTRA-origin subgoal (a nested
        // augmentation — e.g. an unrequested unit-test leaf) must NOT doom the root. The 2026-06-24
        // run died here: a lone GIVEN_UP test leaf became the sole root blocker. Absorb EXTRA
        // failures so closure falls through to the clean branch (which the coverage/next-resource
        // path then drives); REQUIRED failures still block. See [[project-t2ei-design-note]] (P3).
        boolean depthFirst = goalPlannerConfig != null && goalPlannerConfig.isDepthFirstDecomposition();
        // T4-BP step 3: the acceptance criteria the ledger counts as done — re-verified against the
        // actual current code below, since banked pass-status can be set optimistically.
        java.util.LinkedHashSet<String> passingCriteria = new java.util.LinkedHashSet<>();
        for (GoalEntity sg : allSubgoals) {
            ClosureCheck check = checkSubgoalClosure(sg);
            if (check.passes()) {
                passing++;
                if (sg.getStatus() == GoalStatus.SUBSUMED) subsumed++;
                // BUILD_FIX goals' done-ness is owned by the deterministic gates: T1-DR closes
                // them when the build goes green, and the package/test gates re-prove build
                // health at this same edge every closure attempt. Their criteria are build-status
                // facts ("mvn compile exits 0") the LLM verifier cannot read from source — feeding
                // them to T4-BP-3 adds only false-block risk, never information.
                boolean gateOwned = sg.getMetadata() != null
                        && com.walpro.autobuilder.core.build.BlockerTypes.BUILD_FIX.equals(
                                sg.getMetadata().get(com.walpro.autobuilder.core.build.BlockerTypes.METADATA_KEY))
                        // ...except the completion edge's own criteria item, whose criteria are
                        // the requirements this verification exists to check. Skipping them let a
                        // subsumed criteria item take the unmet requirement out of the set.
                        && !com.walpro.autobuilder.core.build.BlockerTypes.isFromCriteriaGate(sg.getMetadata());
                if (gateOwned) continue;
                // Root completion is gated on the SPEC: when the spec offers a traceability
                // signal, only criteria of POSITIVELY spec-bound goals count toward root completion.
                // Run 11 (2026-07-04): a planner-authored structural stepping-stone ("Create …
                // package", origin-stamped required, T4-BT binding UNKNOWN) carried the
                // state-snapshot criterion "No classes or files are present in the package yet" —
                // unconfirmable once code existed — and walled T4-BP-3 for the rest of the run.
                // The same wording as a spec-traceable requirement (e.g. "no data is written to
                // disk" on an endpoint goal) stays in the set — traceability, not wording, is the
                // discriminator. Self-gated: a spec with no signal keeps today's collect-all
                // (completion re-verification must not go vacuous on non-REST specs).
                if (depthFirst && goalPlannerConfig != null && goalPlannerConfig.isSpecTraceabilityEnabled()
                        && specTraceability != null && root.getDescription() != null
                        && specTraceability.hasTraceabilitySignal(root.getDescription())
                        && specTraceability.classify(root.getDescription(), sg) != SpecTraceability.Binding.REQUIRED) {
                    continue;
                }
                Object raw = sg.getMetadata() != null ? sg.getMetadata().get("acceptanceCriteria") : null;
                if (raw instanceof List<?> l) {
                    for (int ci = 0; ci < l.size(); ci++) {
                        Object c = l.get(ci);
                        // Spec-grounding (run 19): AUTHORED criteria never join the completion-
                        // verification set — re-verifying an invented requirement at the root
                        // edge is exactly how a false ✗ on it walls the whole run.
                        if (c != null && !isAuthoredCriterion(sg, ci + 1)) passingCriteria.add(c.toString());
                    }
                }
            } else if (depthFirst && isExtraForClosure(sg)) {
                absorbedExtra++;
                log.info("T2-EI: absorbing EXTRA-origin closure failure for subgoal [{}] ({}) — not a root blocker",
                        sg.getId(), check.reason());
            } else {
                blockers.add(sg.getId() + ": " + check.reason());
            }
        }
        if (absorbedExtra > 0) {
            log.warn("T2-EI: {} EXTRA-origin subgoal failure(s) absorbed at closure (depth-first); {} required blocker(s) remain",
                    absorbedExtra, blockers.size());
        }

        if (blockers.isEmpty()) {
            // T2-EI (D2, P5): the deterministic half of the coverage invariant, ahead of every
            // LLM gate. Spec-named endpoints (intended, from the root description) are compared
            // against the output's request mappings (built, derived live from the sources) — a
            // spec-named endpoint with no mapping refuses closure and becomes a work item. This
            // is the check false completion #2 lacked: the ledger was clean because resources 2-3
            // were never planned, and the LLM coverage reviewer timed out. No LLM, no paraphrase
            // minting; the query abstains when the spec names no endpoints or the built set
            // cannot be grounded (see EndpointSurfaceQuery).
            if (goalPlannerConfig.isStructureQueryEnabled() && endpointSurfaceQuery != null) {
                Map<String, String> output = currentCycleContext != null ? currentCycleContext.previousOutput() : null;
                // T4-CV: an artifact that cannot START cannot serve any endpoint the spec names,
                // so the entry-point gap is raised in the SAME pass and listed first. Six recorded
                // runs ended `Unable to find main class` with a compiling, endpoint-bearing tree —
                // including 2026-07-27_19-37-42, which spent 65 cycles and 10 merges and never
                // wrote one. Nothing in the loop required it: PackageGate sits behind this very
                // check, the T2-EQ halt-edge rescue only covers halt terminals (that run hit the
                // deadline), and PreservedArtifactService decides "required" retrospectively.
                // Abstains when the output cannot be seen — absence of evidence is not a gap.
                List<String> missingEndpoints = new java.util.ArrayList<>();
                boolean entryPointMissing = output != null && !output.isEmpty()
                        && endpointSurfaceQuery.specRequiresRunnableArtifact(root.getDescription())
                        && !com.walpro.autobuilder.core.persistence.EntryPointScan.hasJavaEntryPointInSources(output);
                if (entryPointMissing) {
                    missingEndpoints.add(
                            com.walpro.autobuilder.core.persistence.EntryPointScan.MISSING_ENTRY_POINT_WORK_ITEM);
                }
                missingEndpoints.addAll(endpointSurfaceQuery.missingEndpointWorkItems(
                        root.getDescription(), output));
                if (!missingEndpoints.isEmpty()) {
                    CoverageReviewResult injected = injectCoverageGaps(root, missingEndpoints);
                    if (!injected.injected().isEmpty()) {
                        // The work item's done-when is the mapping's existence — declared as a
                        // criterion so promotion never falls back to description-as-criterion
                        // (the description embeds "no request mapping … exists", a defect
                        // statement that is unconfirmable once the endpoint is built; run 11
                        // carried it into the completion set and walled T4-BP-3).
                        for (String id : injected.injectedIds()) {
                            GoalEntity workItem = goalMap.get(id);
                            if (workItem == null) continue;
                            if (com.walpro.autobuilder.core.persistence.EntryPointScan
                                    .MISSING_ENTRY_POINT_WORK_ITEM.equals(workItem.getDescription())) {
                                setAcceptanceCriteria(id, List.of(
                                        com.walpro.autobuilder.core.persistence.EntryPointScan.ENTRY_POINT_CRITERION));
                                continue;
                            }
                            endpointSurfaceQuery.endpointFromWorkItem(workItem.getDescription())
                                    .ifPresent(e -> setAcceptanceCriteria(id,
                                            coverageItemCriteria(e, root.getDescription())));
                        }
                        if (entryPointMissing) {
                            log.warn("T4-CV: root closure blocked — the specification names endpoints but no"
                                    + " application source declares an entry point; injected as a work item");
                            recordClosureRefusal("T4-CV", root.getId(),
                                    "the specification names endpoints but no application source"
                                    + " declares an entry point", List.of());
                        }
                        log.warn("T2-EI D2: root closure blocked — {} coverage gap(s) injected as work items: {}",
                                injected.injected().size(), injected.injected());
                        recordClosureRefusal("T2-EI", root.getId(),
                                injected.injected().size() + " endpoint coverage gap(s)", List.of());
                        return;
                    }
                    refuseClosureUnrepairable("T2-EI D2", missingEndpoints.size() + " missing endpoint(s)");
                    return;
                }
            }
            // T4-AN-8: the compile-only probes never check that the artifact is a startable
            // application (no criterion names the entry point). One package-level build at the
            // completion edge; a failure is injected as a work item instead of completing. If
            // the injected subgoal later goes GIVEN_UP with unmet criteria, the ledger above
            // blocks closure honestly — no special handling needed.
            if (packageGate != null) {
                java.util.Optional<String> packagingFailure = packageGate.verifyPackageable(
                        currentCycleContext != null ? currentCycleContext.cycleName() : "completion-edge");
                // T4-BN-3 at the live edge: the packaging step compiles test sources (its
                // -DskipTests skips only execution), so a missing test dependency first fails
                // HERE — the in-cycle consult never fires (main-compile-only probe). Apply the
                // deterministic pom fix and re-verify once before handing the failure to the
                // LLM as a work item (2026-07-02 run 2: closure reached 0 required blockers,
                // then churned to a T2-CB halt on `cannot find symbol: class Test`).
                if (packagingFailure.isPresent() && testDependencyEnsurer != null
                        && packagingFailure.get().contains("src/test/")
                        && testDependencyEnsurer.ensure()) {
                    packagingFailure = packageGate.verifyPackageable(
                            currentCycleContext != null ? currentCycleContext.cycleName() : "completion-edge");
                    if (packagingFailure.isEmpty()) {
                        log.info("T4-BN-3: packaging failure cleared by adding the test dependency — closure proceeds");
                    }
                }
                // T4-BN-3 slice 2: on a goal whose spec never asked for tests, a packaging failure
                // confined to test sources gets the deterministic removal path after the injected
                // work item's repair round fails — runs 2/16/17 all died in this trap with the
                // removal option offered only as prose the model never took (run 17: false give-up
                // on a runtime-correct artifact). The quarantine component owns the repair-round
                // discipline; test-requiring specs never reach it.
                if (packagingFailure.isPresent() && brokenTestQuarantine != null
                        && packagingFailure.get().contains("src/test/")
                        && !packagingFailure.get().contains("src/main/")
                        && !com.walpro.autobuilder.core.build.TestRunGate.specRequiresTests(root.getDescription())) {
                    List<String> quarantined = brokenTestQuarantine.recordBlockedVisit(
                            root.getId(), packagingFailure.get(),
                            currentCycleContext != null ? currentCycleContext.cycleName() : "completion-edge");
                    if (!quarantined.isEmpty()) {
                        packagingFailure = packageGate.verifyPackageable(
                                currentCycleContext != null ? currentCycleContext.cycleName() : "completion-edge");
                        if (packagingFailure.isEmpty()) {
                            log.info("T4-BN-3: packaging failure cleared by quarantining {} unrequested "
                                    + "non-compiling test source(s) — closure proceeds", quarantined.size());
                        }
                    }
                } else if (packagingFailure.isEmpty() && brokenTestQuarantine != null) {
                    brokenTestQuarantine.reset(root.getId());
                }
                if (packagingFailure.isPresent()) {
                    CoverageReviewResult injected = injectCoverageGaps(root, List.of(
                            "[BUILD_FIX] Make the application packageable and startable — packaging failed: "
                                    + packagingFailure.get()), true);
                    if (!injected.injected().isEmpty()) {
                        // Same blob-becomes-criterion guard as the T4-BP-3 injection below: the
                        // done-when is the requirement, not the captured build output. The package
                        // gate re-checks it deterministically at every closure attempt.
                        for (String id : injected.injectedIds()) {
                            setAcceptanceCriteria(id, List.of(
                                    "The project packages into a startable application artifact without build errors"));
                            // T4-CC: acceptance of fixes on this work item is decided by re-running
                            // this gate, not by the LLM evaluator's opinion.
                            setGoalMetadataString(id, com.walpro.autobuilder.core.build.BlockerTypes.ORIGINATING_GATE_KEY,
                                    com.walpro.autobuilder.core.build.BlockerTypes.GATE_PACKAGE);
                        }
                        log.warn("T4-AN-8: root closure blocked — artifact is not packageable; work item injected: {}",
                                packagingFailure.get());
                        recordClosureRefusal("T4-AN-8", root.getId(), packagingFailure.get(), List.of());
                        return;
                    }
                    refuseClosureUnrepairable("T4-AN-8", packagingFailure.get());
                    return;
                }
            }
            // T4-BV: execution observation. Packaging + compiling prove nothing about runtime — the
            // 2026-07-02/03 fulfilled artifacts passed every static gate while their spec-named
            // endpoints were unreachable in the running application, and false completion #2's
            // jar died at launch. Boot the freshly packaged artifact once and probe the spec's
            // parameterless GET endpoints live; a startup crash or a dead endpoint becomes a
            // work item instead of a completion. Runs right after the package gate so the
            // artifact under observation is the one just packaged.
            if (bootGate != null) {
                // T4-CU: the gate also executes the tree's SPEC-bound acceptance criteria against
                // the artifact it just booted. Run 2026-07-27_16-41-48 closed with the criterion
                // "POST /books with invalid input returns HTTP 400" marked ✓ by the evaluator and
                // no validation anywhere in the artifact — a claim about runtime behaviour that
                // only a request can settle.
                // T4-DB: this is THE completion edge — the one call site of four that is not
                // inside a cycle — so the model fallback is permitted here and nowhere else.
                // T4-CY measured that path costing 29.5 and 14.9 minutes of a 90-minute run when
                // called per cycle, and recorded that it is "affordable at the completion edge".
                // Roughly 15 of 18 remaining abstentions need one thing the deterministic path
                // cannot supply: a request body the application will accept.
                java.util.Optional<String> bootFailure = bootGate.verifyBootsAndServes(
                        currentCycleContext != null ? currentCycleContext.cycleName() : "completion-edge",
                        root.getDescription(),
                        specBoundBehaviouralCriteria(),
                        true);
                if (bootFailure.isPresent()) {
                    // T4-DO (reachability, 2026-08-28): this branch is NOT where the repair earns
                    // its keep. Run 2026-08-28_20-37-30 produced `no main manifest attribute` — the
                    // exact defect — and T4-DO fired zero times, because the run stalled at 29
                    // cycles with 0 closure attempts and 0 boot-gate runs. **A run whose artifact
                    // cannot run is precisely the run that stalls before closure**, so gating the
                    // repair here makes it unreachable for the case it exists for.
                    //
                    // ⚠ CORRECTED 2026-09-21: this comment used to claim the check "now also runs
                    // after the package gate", pointing at the second call further down. Both call
                    // sites are inside THIS method, so the 2026-08-28 fix moved the call within
                    // the unreachable region rather than out of it, and the ticket recorded it as
                    // fixed. Run 2026-09-21_00-32-59 measured the consequence: thirty minutes with
                    // an unlaunchable jar, closure_attempts=0, this repair firing zero times. The
                    // real per-cycle route is now the boot gate's own T1-GD branch; both calls
                    // here remain as the closure-edge backstop for runs that DO get here, which is
                    // how clitarget exercised it.
                    // Before asking the MODEL to make the artifact runnable, check whether
                    // the answer is deterministic. A jar with no Main-Class cannot run and the
                    // sources say what it should run; that repair needs no model and no ecosystem
                    // table. Measured cause of the 2026-08-28 regression series, where packaging
                    // appeared in 0 / 1 / 1 of three runs on one seed and `boot-failed` landed in
                    // exactly the run that lacked it. Abstains whenever the project has already
                    // answered — a jar declaring an entry point, or sources naming no single one.
                    if (applyRunnablePackagingRepair()) {
                        log.warn("T4-DO: root closure blocked — the packaged artifact could not run; "
                                + "declared its entry point deterministically. Next cycle rebuilds.");
                        recordClosureRefusal("T4-DO", root.getId(),
                                "the packaged artifact could not run", List.of());
                        return;
                    }
                    CoverageReviewResult injected = injectCoverageGaps(root, List.of(
                            // T4-DD item 3: the imperative must not re-assert a completion model
                            // the artifact may not have — the gate's own summary already says what
                            // running it revealed, and on a target that terminates by design
                            // "start and serve the specified endpoints" was an instruction to
                            // build a network surface the specification never asked for.
                            "[BUILD_FIX] Make the packaged application behave as the specification states when run — "
                                    + bootFailure.get()), true);
                    if (!injected.injected().isEmpty()) {
                        for (String id : injected.injectedIds()) {
                            setGoalMetadataString(id, com.walpro.autobuilder.core.build.BlockerTypes.ORIGINATING_GATE_KEY,
                                    com.walpro.autobuilder.core.build.BlockerTypes.GATE_BOOT);
                            // T4-DW: the claim is that the packaged artifact does not run as the
                            // specification states, so only a launch that served cleanly retires it.
                            setGoalMetadataString(id, com.walpro.autobuilder.core.build.BlockerTypes.BOOT_CLAIM_KEY,
                                    com.walpro.autobuilder.core.build.BlockerTypes.CLAIM_SERVES);
                        }
                        log.warn("T4-BV: root closure blocked — {}; work item injected", bootFailure.get());
                        recordClosureRefusal("T4-BV", root.getId(), bootFailure.get(), List.of());
                        return;
                    }
                    refuseClosureUnrepairable("T4-BV", bootFailure.get());
                    return;
                }
            }
            // T4-DO: reached when the boot gate did not block — it abstained, is disabled, or
            // found no fresh artifact — so the packaging question still has to be asked before the
            // root may close. Repair here and block this cycle; the next package gate rebuilds
            // against the repaired pom.
            //
            // ⚠ This is NOT the early route it was written as. The original comment read "a jar
            // exists as soon as packaging succeeds, which is many cycles before any closure
            // attempt" — but reaching this line IS a closure attempt, so on a run that never gets
            // here the repair never fires. That route is the boot gate's T1-GD branch.
            if (applyRunnablePackagingRepair()) {
                log.warn("T4-DO: the packaged artifact could not run; declared its entry point "
                        + "deterministically at the packaging edge. Next cycle rebuilds.");
                return;
            }
            // T4-CX: the static injection-wiring FORECAST, now that the gate which measures the
            // same property by starting the artifact has had its turn. It used to be the last step
            // of the package gate, where it blocked closure before anything booted — in run
            // 2026-07-27_22-48-26 the package gate passed 4x, this forecast blocked each time, the
            // boot gate ran 0 times, and the criterion probes riding it never executed. Reached
            // here only when the boot gate did not block, which includes the paths where it
            // abstains (disabled, no artifact, artifact older than sources) — so an unstartable
            // artifact still cannot complete the root unobserved.
            if (packageGate != null) {
                java.util.Optional<String> wiringFailure = packageGate.predictedStartupWiringFailure();
                if (wiringFailure.isPresent()) {
                    CoverageReviewResult injected = injectCoverageGaps(root, List.of(
                            "[BUILD_FIX] Make the application startable — " + wiringFailure.get()), true);
                    if (!injected.injected().isEmpty()) {
                        for (String id : injected.injectedIds()) {
                            setGoalMetadataString(id, com.walpro.autobuilder.core.build.BlockerTypes.ORIGINATING_GATE_KEY,
                                    com.walpro.autobuilder.core.build.BlockerTypes.GATE_PACKAGE);
                        }
                        log.warn("T4-BW: root closure blocked — {}; work item injected", wiringFailure.get());
                        recordClosureRefusal("T4-BW", root.getId(), wiringFailure.get(), List.of());
                        return;
                    }
                    refuseClosureUnrepairable("T4-BW", wiringFailure.get());
                    return;
                }
            }
            // T4-AN-2: tests compile under the package gate but are never executed — the first
            // verified-packageable fulfilled artifact (2026-06-12 18:00) had 7+ failing tests.
            // Same once-per-completion-claim discipline as the package gate above.
            if (testRunGate != null) {
                // T4-AN-2b: when the goal spec itself calls for tests, their absence blocks closure —
                // otherwise `mvn test` passes vacuously and a test-required goal completes with zero
                // tests (observed 2026-06-22).
                boolean testsRequired = com.walpro.autobuilder.core.build.TestRunGate
                        .specRequiresTests(root.getDescription());
                java.util.Optional<String> testFailure = testRunGate.verifyTestsPass(
                        currentCycleContext != null ? currentCycleContext.cycleName() : "completion-edge",
                        testsRequired);
                if (testFailure.isPresent()) {
                    // Run 2 (2026-07-03) churned to a T2-CB halt on this work item: the model had
                    // authored a hollow test unprompted (test infrastructure never initialized),
                    // could not repair it across 5 gate blocks, and repair was the only offered
                    // resolution. When the goal itself never asked for tests, removing broken
                    // self-authored test sources is an honest resolution — the T4-AN-2b guard
                    // still blocks ABSENCE whenever the spec requires tests, so this wording
                    // cannot open a delete-the-tests path on test-requiring goals.
                    String resolutionHint = testsRequired
                            ? ""
                            : " (repair is preferred; since the goal specification does not call for"
                                    + " automated tests, removing test sources that cannot be made to"
                                    + " pass is also an acceptable resolution)";
                    CoverageReviewResult injected = injectCoverageGaps(root, List.of(
                            "[BUILD_FIX] Make the project's own test suite pass — " + testFailure.get()
                                    + resolutionHint), true);
                    if (!injected.injected().isEmpty()) {
                        // Same blob-becomes-criterion guard as the T4-BP-3 injection below; the
                        // test gate re-checks the requirement deterministically at closure.
                        for (String id : injected.injectedIds()) {
                            setAcceptanceCriteria(id, List.of(
                                    "The project's own automated test suite passes"));
                            setGoalMetadataString(id, com.walpro.autobuilder.core.build.BlockerTypes.ORIGINATING_GATE_KEY,
                                    com.walpro.autobuilder.core.build.BlockerTypes.GATE_TEST);
                        }
                        log.warn("T4-AN-2: root closure blocked — {}; work item injected", testFailure.get());
                        recordClosureRefusal("T4-AN-2", root.getId(), testFailure.get(), List.of());
                        return;
                    }
                    refuseClosureUnrepairable("T4-AN-2", testFailure.get());
                    return;
                }
            }
            // T4-BP step 3: positive corroboration. The gates above prove the artifact compiles
            // and its tests pass; closure otherwise trusts each subgoal's BANKED criteria pass-
            // status, which score-based / #NoDiff promotion can set without verifying the final
            // code — so a hollow artifact (stub bodies, uncomputed values) reached a false COMPLETED
            // (2026-06-16). Re-verify the passing criteria against the code that exists NOW, via the
            // LLM's pattern recognition (criterion-by-criterion, not the coverage gate's leading
            // "is this gap implemented?"). Digest-cached + call-counted (cost measured, not guessed);
            // conservative — abstains on no observation, blocks only on a confirmed-unconfirmed criterion.
            // T4-CQ: the SEED's obligations are checked even when no banked criterion survives.
            // This gate used to run only with passing criteria in hand, so a run whose subgoal
            // criteria were all stamped AUTHORED -- the first adopted project, whose objective is
            // written entirely as obligations -- closed GOAL_FULFILLED with one of seven routes
            // tested and the seed never consulted (run 2026-09-24_12-09-57). Having no evidence
            // must not be the one case that skips the check.
            boolean seedObligationsToCheck = criteriaVerificationService != null && passingCriteria.isEmpty()
                    && !uncoveredSeedAtClosure(root, List.of()).isEmpty();
            if (criteriaVerificationService != null && (!passingCriteria.isEmpty() || seedObligationsToCheck)) {
                Map<String, String> output = currentCycleContext != null ? currentCycleContext.previousOutput() : null;
                // T4-DK (open half): hand the SEED's own obligations to the same verifier. Closure
                // used to check only the banked criteria of surviving subgoals, so a requirement
                // whose subgoal was given up or subsumed left the ledger unchecked — which is how
                // run 2026-08-28_05-27-49 completed a flight simulator with a placeholder renderer
                // and no HUD. Requirements already covered by a banked criterion are skipped, so
                // the verifier is not asked the same question twice.
                List<String> toVerify = new ArrayList<>(passingCriteria);
                List<String> uncoveredSeed = uncoveredSeedAtClosure(root, toVerify);
                if (!uncoveredSeed.isEmpty()) {
                    log.info("T4-DK: {} seed requirement(s) are covered by no banked criterion — "
                            + "verifying them against the artifact too", uncoveredSeed.size());
                    toVerify.addAll(uncoveredSeed);
                }
                // T4-DT: offer the SPECIFICATION's own obligations to the EXECUTION probe before
                // asking a model to read the source for them.
                //
                // The seed's worked example is a seed requirement, and seed requirements reached
                // only `verifyCompletion` — a model reading the parser. Run 2026-09-10_21-46-44
                // closed GOAL_FULFILLED at 15/19 with that model calling "a file containing
                // a,"b,c","d""e" is parsed into one row with three fields" satisfied; running the
                // artifact reports two. The oracle for that claim was in the seed the whole time.
                //
                // Where the probe speaks it OUTRANKS the reading, because it ran the tool. Where it
                // abstains — no packageable artifact, nothing exercisable — nothing changes and the
                // model is asked exactly as before.
                Optional<String> refutedByExecution = refuteUncoveredSeedByExecution(root, uncoveredSeed);
                if (refutedByExecution.isPresent()) {
                    log.warn("T4-DT: root closure REFUSED by the running artifact — {}",
                            refutedByExecution.get());
                    injectExecutionRefutation(root, refutedByExecution.get(), uncoveredSeed);
                    return;
                }
                // T4-CQ: an obligation naming an entry point is answered by the test sources before a
                // model is asked: run 2026-09-25_04-35-01 closed GOAL_FULFILLED at 6/11 after the
                // verifier marked all 26 requirements met, "a test must assert what the entry point
                // returns" among them, over a test that asserted only that a string came back. Only
                // ever a refusal; a met check still goes to the verifier. The SEED's obligations only:
                // a planner criterion naming a controller method is met on a web target by a request,
                // which never calls the method, and judging it here would wall a correct run.
                java.util.Map<String, com.walpro.autobuilder.core.build.EntryPointAssertions.Finding> unasserted =
                        com.walpro.autobuilder.core.build.EntryPointAssertions.unmet(
                                SeedRequirements.verifiableObligationsOrSplit(root.getDescription(), writtenObligations()),
                                output);
                // T4-CQ: and where a test already calls the entry point, OBSERVE what it returns, so the
                // work item carries the value to assert instead of asking the model to guess it.
                String observedValues = "";
                if (!unasserted.isEmpty() && valueObservationProbe != null) {
                    var observations = valueObservationProbe.observe(unasserted, output,
                            currentCycleContext != null ? currentCycleContext.cycleName() : "closure");
                    if (!observations.isEmpty()) {
                        observedValues = " Observed by running the existing tests: " + observations.stream()
                                .map(com.walpro.autobuilder.core.build.ValueObservationProbe.Observation::describe)
                                .reduce((a, b) -> a + "; " + b).orElse("") + ".";
                    }
                }
                final String observedSuffix = observedValues;
                var completionVerification = unasserted.isEmpty()
                        ? criteriaVerificationService.verifyCompletion(root.getDescription(), toVerify, output)
                        : com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService.CompletionVerification.blocked(
                                unasserted.size() + " obligation(s) name an entry point no test calls and value-asserts: "
                                        + unasserted.values().stream().map(
                                                com.walpro.autobuilder.core.build.EntryPointAssertions.Finding::describe)
                                                .distinct().limit(5).reduce((a, b) -> a + "; " + b).orElse("")
                                        + ". To learn a value you do not know, assert your best guess with an equality"
                                        + " assertion: the test run reports what the code actually returned ('but was'),"
                                        + " and that is the value to assert." + observedSuffix,
                                new ArrayList<>(unasserted.keySet()));
                if (!unasserted.isEmpty()) {
                    log.warn("T4-CQ: root closure refused by the test sources -- {}", completionVerification.unconfirmed().orElse(""));
                }
                if (!completionVerification.answered()) {
                    log.warn("T4-BP-3: completion verification returned no answer (LLM failure) — deferring completion; an unanswered check must not complete the root");
                    return;
                }
                java.util.Optional<String> unconfirmed = completionVerification.unconfirmed();
                if (unconfirmed.isPresent()) {
                    CoverageReviewResult injected = injectCoverageGaps(root, List.of(
                            "[BUILD_FIX] Implement the unconfirmed acceptance criteria against the current code — " + unconfirmed.get()));
                    if (!injected.injected().isEmpty()) {
                        // The work item's done-when is the actual unmet criteria. Left criteria-less,
                        // promotion declares the log-text description above as the criterion and the
                        // next verification blocks on the blob itself — recursively re-injecting
                        // (run 4, 2026-07-03: verification #4 blocked on verification #1's work item).
                        for (String id : injected.injectedIds()) {
                            // The criteria on this item are the SPECIFICATION's unmet requirements,
                            // not build-status facts — stamped so the three sites that read the
                            // [BUILD_FIX] tag as "gate-owned build health" can tell them apart.
                            // Unstamped, a COMPILE_OK observation retired this item 48 seconds
                            // after it was raised (run 2026-08-30_00-06-00) and the root completed
                            // with the multi-line-row requirement still unmet.
                            //
                            // ⚠ MARKER FIRST, CRITERIA SECOND, AND THE ORDER IS THE WHOLE POINT.
                            // setAcceptanceCriteria stamps the per-criterion binding, and that
                            // stamp asks isFromCriteriaGate(metadata). Written the other way round
                            // the guard read a map that did not carry the marker yet, concluded
                            // "gate-owned BUILD_FIX", and stamped the SPECIFICATION's own words
                            // AUTHORED — observed in run 2026-09-09_23-39-58 on "GET /books/{id}
                            // returns HTTP 404 when the book is absent", which the seed states
                            // verbatim. A correct guard consulted before its input exists is an
                            // absent guard.
                            setGoalMetadataString(id, com.walpro.autobuilder.core.build.BlockerTypes.ORIGINATING_GATE_KEY,
                                    com.walpro.autobuilder.core.build.BlockerTypes.GATE_CRITERIA);
                            setAcceptanceCriteria(id, completionVerification.unconfirmedCriteria());
                        }
                        log.warn("T4-BP-3: root closure blocked — {}; work item injected", unconfirmed.get());
                        // The criteria this gate could not confirm ARE known here, so this gate
                        // attributes too -- it is the only other one that judges criteria.
                        recordClosureRefusal("T4-BP-3", root.getId(), unconfirmed.get(),
                                completionVerification.unconfirmedCriteria());
                        return;
                    }
                    refuseClosureUnrepairable("T4-BP-3", unconfirmed.get());
                    return;
                }
            }
            String rationale = "closure ledger clear: " + passing + " subgoal(s) accounted for"
                    + (subsumed > 0 ? " (" + subsumed + " subsumed)" : "");
            markGoalCompleted(root.getId(), "root goal satisfied: " + rationale);
            log.info("T2-DR: root '{}' marked COMPLETED — {}", root.getId(), rationale);
        } else {
            // Run 18 (2026-07-06): a COMPLETED blocker whose banked criteria disagree with its
            // own completion claim is a CONTRADICTION, not exhausted work — the #NoDiff
            // promotion had raced ahead of its criteria check, and the ✗ recorded seconds later
            // never became a work item. Giving the root up on it abandons work the loop never
            // retried (false give-up #4, on an artifact whose "unmet" behaviour ground-truthed
            // as working). Same rule as the T1-FG twin reopen: a deterministic contradiction of
            // a completion claim reopens the goal; the retry/division/give-up ladder then owns
            // it. One reopen per goal — a second contradiction means promote-falsely/reopen
            // cycling, and the give-up below is then the honest terminal.
            int reopened = reopenClosedButUnmetBlockers(allSubgoals);
            if (reopened > 0) {
                log.warn("T2-DR: {} closed-but-unmet blocker(s) reopened for rework instead of giving up the root", reopened);
                return;
            }
            String rationale = blockers.size() + " subgoal(s) blocking closure";
            markGoalGivenUp(root.getId(), "root goal exhausted: " + rationale);
            log.warn("T2-DR: root '{}' marked GIVEN_UP — {}; blockers={}",
                    root.getId(), rationale, blockers);
            if (currentCycleContext != null) {
                currentCycleContext = currentCycleContext.withGoalExhaustedReason("root-given-up");
            }
        }
    }

    /**
     * Run 18: reopens COMPLETED subgoals that block root closure because their banked criteria
     * contain an unmet entry — the completion claim and the bank contradict each other, so the
     * completion is unproven and the work goes back to the ladder. Unlike the T1-FG twin reopen
     * (which clears the whole pass-map because the re-detected gap contradicts the completion
     * wholesale), the bank here IS the diagnosis: verification marked specific criteria unmet
     * against current code. Keeping it blocks the "all criteria banked" early exit (the run-9
     * oscillation cannot start) and keeps retry budget and division focused on the unmet work.
     * Bounded to one reopen per goal via the shared {@code closureEdgeReopens} counter.
     */
    private int reopenClosedButUnmetBlockers(List<GoalEntity> allSubgoals) {
        int reopened = 0;
        for (GoalEntity sg : allSubgoals) {
            if (sg.getStatus() != GoalStatus.COMPLETED) continue;
            ClosureCheck check = checkSubgoalClosure(sg);
            if (check.passes()) continue;
            Object prior = sg.getMetadata().get("closureEdgeReopens");
            if (prior instanceof Number n && n.intValue() >= 1) continue;
            sg.setStatus(GoalStatus.ACTIVE);
            sg.setCompleted(null);
            sg.setGivenUp(null);
            sg.getMetadata().put("closureEdgeReopens", 1);
            reopened++;
            // First reopen of a goal is a convergence-brake observation, same class as the T1-FG reopen.
            closureEdgeInjections++;
            log.warn("T2-DR: COMPLETED blocker [{}] contradicts its banked criteria ({}) — reopened for rework", sg.getId(), check.reason());
            try {
                goalGraphService.save(sg);
            } catch (Exception e) {
                log.debug("Neo4j reopen update failed for goal {} (ignoring): {}", sg.getId(), e.getMessage());
            }
        }
        if (reopened > 0) {
            persist();
            if (currentCycleContext != null) {
                currentCycleContext = currentCycleContext.issueRequest(
                        com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(
                                BasicSignals.REPLAN_REQUESTED, 1.0, "closed-but-unmet-reopen",
                                com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.2)));
            }
        }
        return reopened;
    }

    /**
     * T2-DR: outcome of the closure check for one subgoal. {@code passes} means the
     * subgoal does not block root closure; {@code reason} is populated either way for
     * logging.
     */
    private record ClosureCheck(boolean passes, String reason) {
        static ClosureCheck pass(String reason) { return new ClosureCheck(true, reason); }
        static ClosureCheck block(String reason) { return new ClosureCheck(false, reason); }
    }

    private ClosureCheck checkSubgoalClosure(GoalEntity sg) {
        GoalStatus s = sg.getStatus();
        if (s == GoalStatus.SUBSUMED) return ClosureCheck.pass("subsumed");
        if (s == GoalStatus.ACTIVE) return ClosureCheck.block("still ACTIVE");

        Object raw = sg.getMetadata() != null ? sg.getMetadata().get("acceptanceCriteria") : null;
        if (!(raw instanceof List<?> l) || l.isEmpty()) {
            // A COMPLETED goal with no declared criteria was accepted by the evaluation that
            // promoted it — its description was the requirement and the completion is the
            // observation. Blocking it starves root closure on bookkeeping (observed 2026-06-11
            // 23:57: 2 of the 4 final blockers were COMPLETED goals whose only fault was
            // declaring no criteria). GIVEN_UP without criteria stays a blocker: abandoned
            // work with no observation of coverage.
            if (s == GoalStatus.COMPLETED) {
                return ClosureCheck.pass("completed; description was the requirement");
            }
            return ClosureCheck.block("no acceptanceCriteria declared");
        }

        Map<Integer, Boolean> passStatus = getCriteriaPassStatus(sg);
        List<Integer> unmet = new ArrayList<>();
        int authoredUnmet = 0;
        for (int i = 1; i <= l.size(); i++) {
            Boolean passing = passStatus.get(i);
            if (passing == null || !passing) {
                // Spec-grounding (run 19): an unmet AUTHORED criterion cannot block root
                // closure — it is a requirement the loop invented (compile-diagnostic
                // fossil, unrequested test, minted type constraint), not something the
                // specification asks for. Runs 17/18/19 each died at this exact edge on
                // authored requirements while the spec-level behaviour worked.
                if (isAuthoredCriterion(sg, i)) {
                    authoredUnmet++;
                } else {
                    unmet.add(i);
                }
            }
        }
        if (unmet.isEmpty()) {
            if (authoredUnmet > 0) {
                return ClosureCheck.pass("spec-bound criteria banked (" + authoredUnmet
                        + " unmet AUTHORED criterion(s) advisory, not blocking)");
            }
            return ClosureCheck.pass(s == GoalStatus.COMPLETED ? "all criteria banked" : "criteria banked despite " + s);
        }
        return ClosureCheck.block(s + " with unmet criteria " + unmet);
    }

    /**
     * Manual coverage review — called via REST, not automatically.
     * Compares the root goal description against all terminal subgoals and injects any
     * missing features as new first-wave subgoals (planningRunId=0).
     * If gaps are found and root is COMPLETED/GIVEN_UP, resets it to ACTIVE so the loop
     * can resume. Emits REPLAN_REQUESTED to kick the planner.
     * <p>
     * T1-DE: proposals that paraphrase already-GIVEN_UP goals are dropped via similarity
     * dedup, and calls are rate-limited by a per-session merge-gated budget.
     *
     * @return result with injected features, dropped count, and budgetExceeded flag
     */
    public CoverageReviewResult triggerManualCoverageReview() {
        GoalEntity root = getRootGoal();
        if (root == null) {
            log.warn("coverage-review: no root goal defined — nothing to review");
            return new CoverageReviewResult(List.of(), 0);
        }
        if (coverageRetrospectiveService == null) {
            log.warn("coverage-review: CoverageRetrospectiveService not wired — cannot run");
            return new CoverageReviewResult(List.of(), 0);
        }

        List<String> missing = coverageRetrospectiveService.findMissingFeatures(
                root.getDescription(), getAllGoals());

        if (missing.isEmpty()) {
            log.info("coverage-review: no gaps found — root goal appears fully covered");
            return new CoverageReviewResult(List.of(), 0);
        }

        return injectCoverageGaps(root, missing);
    }

    /**
     * T4-I: total work items ever injected through the closure edge this session. Monotonic;
     * read by {@code RootConvergenceTracker} as a progress class — a closure-gate injection is
     * measured distance-to-done materializing as fresh required work, which the planner cannot
     * fake and the brake must not read as stall (run 1 of 2026-07-03 was paused 34 seconds
     * after the package gate injected the entry-point work item, before a single attempt).
     */
    private int closureEdgeInjections = 0;

    /** See {@link #closureEdgeInjections}. */
    public int countClosureEdgeInjections() {
        return closureEdgeInjections;
    }

    /**
     * Shared injection path for coverage gaps — used by the manual REST review above and by the
     * {@link #autoCompleteRootIfSatisfied()} coverage gate. Filters GIVEN_UP paraphrases (T1-DE),
     * reactivates a terminal root, injects survivors as first-wave subgoals, and raises
     * {@code REPLAN_REQUESTED}.
     */
    /**
     * T4-CY: raises a behavioural defect the running artifact was observed to have, mid-run.
     *
     * <p>Distinct from every other injection path in that it does not come from a gate REFUSING
     * completion — it comes from the loop looking at its own output while it still has budget to
     * act. Returns true when the item landed; a paraphrase of abandoned work is dropped by the
     * usual guard, which is why this defers to {@link #injectCoverageGaps} rather than writing a
     * goal directly.
     */
    public boolean injectObservedBehaviouralDefect(String description) {
        return injectObservedBehaviouralDefect(description, List.of());
    }

    /**
     * T4-DW: as above, recording WHICH criteria the observation refuted.
     *
     * <p>A defect the probes attributed to named criteria is a claim about those criteria, and is
     * answered by probing them again — not by a later run happening not to contradict anything.
     * With nothing attributed the item is the whole-artifact claim the gate's summary describes,
     * and the serving observation answers it.
     */
    public boolean injectObservedBehaviouralDefect(String description, List<String> refutedCriteria) {
        return injectObservedBehaviouralDefect(description, refutedCriteria, null);
    }

    /**
     * T4-DW: as above, with the claim the gate named for this defect. {@code null} keeps the
     * default, where attribution alone chooses between {@code CLAIM_CRITERION} and
     * {@code CLAIM_SERVES}; a named claim (the archive's missing entry point) is stamped as given
     * so the evidence that retires the item is the evidence about that claim.
     */
    public boolean injectObservedBehaviouralDefect(String description, List<String> refutedCriteria,
                                                   String claim) {
        GoalEntity root = getRootGoal();
        if (root == null || description == null || description.isBlank()) return false;
        CoverageReviewResult injected = injectCoverageGaps(root, List.of(description), true);
        if (injected.injected().isEmpty()) return false;
        boolean attributed = refutedCriteria != null && !refutedCriteria.isEmpty();
        for (String id : injected.injectedIds()) {
            setGoalMetadataString(id, com.walpro.autobuilder.core.build.BlockerTypes.ORIGINATING_GATE_KEY,
                    com.walpro.autobuilder.core.build.BlockerTypes.GATE_BOOT);
            setGoalMetadataString(id, com.walpro.autobuilder.core.build.BlockerTypes.BOOT_CLAIM_KEY,
                    claim != null ? claim
                    : attributed ? com.walpro.autobuilder.core.build.BlockerTypes.CLAIM_CRITERION
                               : com.walpro.autobuilder.core.build.BlockerTypes.CLAIM_SERVES);
            if (attributed) {
                setGoalMetadataStrings(id,
                        com.walpro.autobuilder.core.build.BlockerTypes.BOOT_CLAIM_CRITERIA_KEY,
                        refutedCriteria);
            }
        }
        log.warn("T4-CY: observed behavioural defect injected as work: {}", description);
        return true;
    }

    /**
     * T4-CZ: requires an application entry point NOW, rather than when closure is attempted.
     *
     * <p>The requirement itself is T4-CV's and unchanged — same work-item text, same acceptance
     * criterion, same injection path (so the paraphrase guard and per-gap idempotency apply). Only
     * its TIMING changes, because the closure edge turned out to be unreachable for the runs that
     * need it most: seven of seven {@code artifact-build-failed} runs died on
     * {@code Unable to find main class}, five of them consecutively, each with no entry point
     * anywhere and each having never emptied its backlog, so T4-CV never ran.
     *
     * <p><b>Why this is not the packaging check that was reverted.</b> That one asked whether the
     * whole tree could be PACKAGED on every merge — a whole-project property demanded of a partial
     * skeleton, which stalled runs and was correctly moved to the halt edge. This asks only whether
     * an entry point EXISTS, which is scaffolding rather than an end-stage property, and it reads the
     * files already in memory: no build, no boot, nothing to be mid-repair about.
     *
     * <p>Returns true when the requirement landed. Silent and false when the specification does not
     * call for a runnable artifact — a library goal is entitled to have no entry point.
     */
    public boolean requireEntryPointNow(Map<String, String> outputFiles) {
        GoalEntity root = getRootGoal();
        if (root == null || root.getDescription() == null) return false;
        if (outputFiles == null || outputFiles.isEmpty()) return false;
        if (endpointSurfaceQuery == null
                || !endpointSurfaceQuery.specRequiresRunnableArtifact(root.getDescription())) {
            return false;
        }
        if (com.walpro.autobuilder.core.persistence.EntryPointScan
                .hasJavaEntryPointInSources(outputFiles)) {
            return false;
        }
        CoverageReviewResult injected = injectCoverageGaps(root, List.of(
                com.walpro.autobuilder.core.persistence.EntryPointScan.MISSING_ENTRY_POINT_WORK_ITEM), true);
        if (injected.injected().isEmpty()) return false;
        for (String id : injected.injectedIds()) {
            setAcceptanceCriteria(id, List.of(
                    com.walpro.autobuilder.core.persistence.EntryPointScan.ENTRY_POINT_CRITERION));
            setGoalMetadataString(id, com.walpro.autobuilder.core.build.BlockerTypes.ORIGINATING_GATE_KEY,
                    com.walpro.autobuilder.core.build.BlockerTypes.GATE_BOOT);
            // T4-DW: a claim about the SOURCES, so the scan that raised it is the evidence that
            // retires it — no launch required, which matters because this item lives in runs whose
            // tree does not compile.
            setGoalMetadataString(id, com.walpro.autobuilder.core.build.BlockerTypes.BOOT_CLAIM_KEY,
                    com.walpro.autobuilder.core.build.BlockerTypes.CLAIM_ENTRY_POINT);
        }
        log.warn("T4-CZ: the specification names endpoints and no source declares an entry point —"
                + " requirement injected mid-run rather than waiting for a closure attempt");
        return true;
    }

    CoverageReviewResult injectCoverageGaps(GoalEntity root, List<String> missing) {
        return injectCoverageGaps(root, missing, false);
    }

    /**
     * @param gateObservation true when {@code missing} is a deterministic closure gate's own
     *        observation of the artifact. T4-CQ: such an item, once GIVEN_UP, used to be dropped
     *        here as a "paraphrase" of itself every time the gate saw the defect again -- 145 and
     *        148 times in two adopted runs, which then sat to the deadline with the one piece of
     *        work that could satisfy the gate unreachable. A gate's re-observation is a fact about
     *        the artifact, not a re-proposal: the given-up item is REOPENED carrying the current
     *        observation. Bounded by the convergence brake, which counts only a goal's first reopen
     *        as progress, and by the closure memo, which does not re-judge an unchanged tree.
     */
    CoverageReviewResult injectCoverageGaps(GoalEntity root, List<String> missing, boolean gateObservation) {
        // T1-DE: dedup filter — drop proposals that paraphrase already-GIVEN_UP goals
        List<String> toInject;
        int dropped = 0;
        List<String> revivedIds = new ArrayList<>();
        int revivedFirstTime = 0;
        if (goalSimilarityService != null) {
            List<String> filtered = new ArrayList<>();
            for (String desc : missing) {
                GoalEntity givenUpTwin = gateObservation ? findGivenUpGateTwin(desc) : null;
                if (givenUpTwin != null) {
                    if (reopenForGateObservation(givenUpTwin, desc) == 1) revivedFirstTime++;
                    revivedIds.add(givenUpTwin.getId());
                    continue;
                }
                long givenUpMatches = goalSimilarityService.countGivenUpMatches(desc);
                if (givenUpMatches >= GoalSimilarityService.GIVEN_UP_RETRY_LIMIT) {
                    log.warn("T1-DE: dropping coverage-review proposal '{}' — already GIVEN_UP {} time(s) (limit={})",
                            desc, givenUpMatches, GoalSimilarityService.GIVEN_UP_RETRY_LIMIT);
                    dropped++;
                } else {
                    filtered.add(desc);
                }
            }
            toInject = filtered;
        } else {
            toInject = missing;
        }

        if (toInject.isEmpty() && !revivedIds.isEmpty()) {
            persist();
            closureEdgeInjections += revivedFirstTime;
            requestReplanForCoverage();
            return new CoverageReviewResult(List.copyOf(revivedIds), dropped, List.copyOf(revivedIds));
        }
        if (toInject.isEmpty()) {
            log.info("coverage-review: all {} LLM proposals dropped as GIVEN_UP paraphrases — no injection", missing.size());
            return new CoverageReviewResult(List.of(), dropped);
        }

        // T1-FG: closure-edge injection is idempotent per gap. Run 7 (2026-07-03) minted 15 twins
        // of the same D2 endpoint item — 4 promoted COMPLETED by score-based closure while the
        // endpoint provably did not exist, 10 SUBSUMED as near-duplicates — and halted on an
        // "empty" backlog. A gap re-detected at the closure edge is a deterministic observation that a
        // recorded completion of the same work is FALSE: reopen the twin instead of injecting a
        // copy; an ACTIVE twin means the work is already queued. Exact-normalized match only —
        // the items are template-generated, and fuzzy matching would merge genuinely distinct
        // endpoints one token apart (POST /loans vs POST /loans/{}/return).
        List<String> toCreate = new ArrayList<>();
        List<String> blocking = new ArrayList<>(revivedIds);
        List<String> liveIds = new ArrayList<>(revivedIds);
        int reopened = revivedIds.size();
        int alreadyQueued = 0;
        int reopenBrakeObservations = revivedFirstTime;
        for (String desc : toInject) {
            GoalEntity twin = findExactSubgoalTwin(desc);
            if (twin == null) {
                toCreate.add(desc);
                blocking.add(desc);
            } else if (twin.getStatus() == GoalStatus.ACTIVE) {
                alreadyQueued++;
                blocking.add(desc);
            } else if (twin.getStatus() == GoalStatus.GIVEN_UP) {
                dropped++;
            } else { // COMPLETED or SUBSUMED — contradicted by the re-detected gap
                twin.setStatus(GoalStatus.ACTIVE);
                twin.setCompleted(null);
                twin.setGivenUp(null);
                // The banked pass-map is part of the contradicted completion — without clearing
                // it, T3-BL's "all criteria banked" early exit re-promotes the reopened goal the
                // next cycle with zero work, and the edge oscillates (run 9, 2026-07-04: 262
                // reopens of one goal at ~4/min). Reopened work must earn its promotion again.
                twin.getMetadata().remove("criteriaPassStatus");
                Object prior = twin.getMetadata().get("closureEdgeReopens");
                int reopens = prior instanceof Number n ? n.intValue() + 1 : 1;
                twin.getMetadata().put("closureEdgeReopens", reopens);
                reopened++;
                // Only the FIRST reopen of a goal is convergence-brake observation: a repeat reopen
                // means promote-falsely/reopen cycling, and counting it would let the oscillation
                // reset the brake forever.
                if (reopens == 1) {
                    reopenBrakeObservations++;
                }
                liveIds.add(twin.getId());
                blocking.add(desc);
                log.warn("coverage-review: gap contradicts recorded completion — reopening [{}] (reopen #{}) instead of injecting a twin: {}",
                        twin.getId(), reopens, desc);
                try {
                    goalGraphService.save(twin);
                } catch (Exception e) {
                    log.debug("Neo4j reopen update failed for goal {} (ignoring): {}", twin.getId(), e.getMessage());
                }
            }
        }
        if (blocking.isEmpty()) {
            log.info("coverage-review: all {} gap(s) map to exhausted twins — no injection", toInject.size());
            return new CoverageReviewResult(List.of(), dropped);
        }

        log.warn("coverage-review: {} missing feature(s) — {} injected, {} reopened, {} already queued ({} dropped as paraphrases/exhausted)",
                blocking.size(), toCreate.size(), reopened, alreadyQueued, dropped);

        // Reactivate root if it reached a terminal state so the loop can resume
        if (root.getStatus() == GoalStatus.COMPLETED || root.getStatus() == GoalStatus.GIVEN_UP) {
            root.setStatus(GoalStatus.ACTIVE);
            root.setCompleted(null);
            root.setGivenUp(null);
            log.info("coverage-review: root goal reset to ACTIVE to allow new subgoals to be worked on");
        }

        if (!toCreate.isEmpty()) {
            // Inject with planningRunId=0 so they become the new first-wave minimum
            int savedRunId = this.currentPlanningRunId;
            this.currentPlanningRunId = 0;
            try {
                liveIds.addAll(addSubGoalsBatch(toCreate, rootGoalId, true, "coverage-retro", null));
            } finally {
                this.currentPlanningRunId = savedRunId;
            }
        } else if (reopened > 0) {
            persist();
        }

        requestReplanForCoverage();

        // Brake observations: creations and first reopens change the tree; an already-queued twin or
        // a repeat reopen does not (counting them would reset the convergence brake forever).
        closureEdgeInjections += toCreate.size() + reopenBrakeObservations;
        return new CoverageReviewResult(blocking, dropped, liveIds);
    }

    private void requestReplanForCoverage() {
        if (currentCycleContext != null) {
            currentCycleContext = currentCycleContext.issueRequest(
                    com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(
                            BasicSignals.REPLAN_REQUESTED, 1.0, "coverage-retro",
                            com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.2)));
        }
    }

    /** A GIVEN_UP item a closure gate raised whose description the new observation matches. */
    private GoalEntity findGivenUpGateTwin(String description) {
        GoalEntity best = null;
        float bestScore = 0f;
        for (GoalEntity g : goalMap.values()) {
            if (g.getType() != GoalType.SUBGOAL || g.getStatus() != GoalStatus.GIVEN_UP
                    || !raisedByClosureGate(g)) continue;
            float score = goalSimilarityService.similarity(description, g.getDescription());
            if (score >= GoalSimilarityService.GIVEN_UP_REVIVAL_SIMILARITY && score > bestScore) {
                best = g;
                bestScore = score;
            }
        }
        return best;
    }

    /** Reopens a given-up gate item under the gate's current observation; returns its reopen count. */
    private int reopenForGateObservation(GoalEntity g, String observation) {
        g.setStatus(GoalStatus.ACTIVE);
        g.setGivenUp(null);
        g.setCompleted(null);
        g.setDescription(observation);
        g.getMetadata().remove("criteriaPassStatus");
        g.getMetadata().remove("cumulativeStagnatingCycles");
        Object prior = g.getMetadata().get("closureEdgeReopens");
        int reopens = prior instanceof Number n ? n.intValue() + 1 : 1;
        g.getMetadata().put("closureEdgeReopens", reopens);
        log.warn("T4-CQ: the gate still observes the defect a given-up item was raised for — reopening [{}]"
                + " (reopen #{}) under the current observation: {}", g.getId(), reopens, observation);
        try {
            goalGraphService.save(g);
        } catch (Exception e) {
            log.debug("Neo4j reopen update failed for goal {} (ignoring): {}", g.getId(), e.getMessage());
        }
        return reopens;
    }

    /**
     * T1-FG: exact-normalized-description twin lookup among subgoals (whitespace-insensitive).
     * When several twins exist (run 7 left 15), an ACTIVE one wins — the work is already queued
     * and reopening a dead sibling would create a second live copy.
     */
    private GoalEntity findExactSubgoalTwin(String description) {
        if (description == null) return null;
        String needle = description.trim().replaceAll("\\s+", " ");
        if (needle.isEmpty()) return null;
        GoalEntity best = null;
        for (GoalEntity g : goalMap.values()) {
            if (g.getType() != GoalType.SUBGOAL || g.getDescription() == null) continue;
            if (!needle.equals(g.getDescription().trim().replaceAll("\\s+", " "))) continue;
            if (g.getStatus() == GoalStatus.ACTIVE) return g;
            if (best == null || best.getStatus() == GoalStatus.GIVEN_UP) best = g;
        }
        return best;
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
     * T1-ET Part B: true iff the subtree rooted at {@code goalId} contains no ACTIVE goal
     * other than {@code goalId} itself. Used by T3-BL's criteria-banked early-exit guard to
     * refuse the fast path on parents whose banked criteria came from child merges but whose
     * leaves are still ACTIVE — otherwise the cycle ends without running any agent (zero-work
     * loop documented in T1-ET Part B's filing). Unknown goal → true (vacuously, nothing to
     * keep us from the fast path).
     */
    public boolean hasNoActiveDescendants(String goalId) {
        if (goalId == null) return true;
        for (GoalEntity child : getSubGoals(goalId)) {
            if (child.getStatus() == GoalStatus.ACTIVE) return false;
            if (!hasNoActiveDescendants(child.getId())) return false;
        }
        return true;
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
                if (behaviourLogger != null) {
                    String cycleId = currentCycleContext != null ? currentCycleContext.cycleName() : "unknown";
                    behaviourLogger.guardViolated(cycleId, "T1-DD",
                            "tree integrity violation: goalId=" + g.getId() + " COMPLETED with " + activeChildren + " ACTIVE children");
                }
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
            if (g.getStatus() != GoalStatus.ACTIVE) {
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
                        if (leaf != null) return leaf;
                        // T2-EI: skip a held sibling leaf — keep scanning for an admitted one.
                        if (!isHeldLeaf(sibling.getId())) return sibling.getId();
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
                // T2-EI D2 promoting direction: an endpoint work item's done-ness is decidable
                // deterministically — the request mapping either exists in the output or it
                // does not. Score-based/banked promotion kept marking these FULFILLED while the
                // endpoint was absent (runs 7/9: the D2 gate then contradicted the completion at
                // the next closure visit — 262-reopen oscillation). Same abstain rules as the
                // closure-edge query: no output or ungroundable mappings = no opinion, proceed.
                if (goalPlannerConfig.isStructureQueryEnabled() && endpointSurfaceQuery != null) {
                    // T2-EL: the criteria are subject to the same structural arbiter as the
                    // description — a criterion naming an endpoint the output provably does not
                    // serve cannot be banked MET, whichever route (criteria checker, blanket
                    // bank below) claimed it. Reconciling corrects the persisted bank, so the
                    // T3-BL early exit stops re-firing on the stale read (run-20 livelock:
                    // the D2 refusal below blocked every promotion but left the bank standing,
                    // and the early exit re-fired on it for 283 cycles — the reconcile must
                    // therefore run BEFORE the description-based refusal returns).
                    int contradicted = reconcileCriteriaAgainstStructure(goalId);
                    if (contradicted > 0) {
                        log.warn("T2-EL: refusing FULFILLED for goalId={} — {} criteria name endpoint(s) with no request mapping in the output",
                                goalId, contradicted);
                        return new GoalProgressApplicationResult(goalId, false, currentSubGoalId, currentSubGoalId, false);
                    }
                    var demanded = endpointSurfaceQuery.endpointFromWorkItem(g.getDescription());
                    if (demanded.isPresent()) {
                        Map<String, String> output = currentCycleContext != null
                                ? currentCycleContext.previousOutput() : null;
                        var built = endpointSurfaceQuery.isBuilt(demanded.get(), output);
                        if (built.isPresent() && !built.get()) {
                            log.warn("T2-EI D2: refusing FULFILLED for endpoint work item [{}] — {} still has no request mapping in the output",
                                    goalId, demanded.get());
                            return new GoalProgressApplicationResult(goalId, false, currentSubGoalId, currentSubGoalId, false);
                        }
                    }
                }
                // Bank every defined criterion as true before flipping status — FULFILLED
                // means "criteria are met", so the ledger must reflect that. Centralising the
                // bank here covers every FULFILLED entry point (transitionGoal, the T2-CC
                // #NoDiff close, the T3-BL early-exit, etc.) so no caller can drift apart.
                bankAllCriteriaTrueIfDefined(goalId);
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
                    MarkCompletedResult nonCurrentResult = markGoalCompleted(goalId);
                    // T1-ET Part B: the result carries the T1-DD refusal directly — no need to
                    // re-read goalMap state to infer what markGoalCompleted just did. The
                    // post-hoc {@code getStatus() == ACTIVE} check was both fragile (other
                    // mutators between the call and re-read could mask the refusal) and
                    // ambiguous (an ACTIVE status here had three possible causes).
                    switch (nonCurrentResult) {
                        case MarkCompletedResult.RefusedActiveDescendants refused ->
                                log.info("T1-DD: skipped — applyGoalProgress non-current FULFILLED for goalId={} kept ACTIVE due to {} active descendants (repointed to {}).",
                                        goalId, refused.activeChildCount(), refused.repointedCurrentSubGoalId());
                        case MarkCompletedResult.RefusedClaimRefuted refuted ->
                                log.warn("T4-CU: skipped — applyGoalProgress non-current FULFILLED for goalId={} kept ACTIVE; the running artifact contradicts its criteria: {}",
                                        goalId, refuted.contradiction());
                        case MarkCompletedResult.Completed completed -> { /* standard flow */ }
                        case MarkCompletedResult.UnknownGoal unknown -> { /* nothing happened */ }
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
