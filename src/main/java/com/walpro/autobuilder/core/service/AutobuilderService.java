package com.walpro.autobuilder.core.service;

import com.walpro.autobuilder.core.checkpoint.CheckpointableState;
import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import com.walpro.autobuilder.core.bootstrap.BuildSystemBootstrap;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.lifecycle.*;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshot;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshotService;
import com.walpro.autobuilder.memory.sms.SmsCycleIngestor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.walpro.autobuilder.client.LlmCircuitBreaker;
import com.walpro.autobuilder.config.AutobuilderConfigProperties;
import com.walpro.autobuilder.core.init.StartMode;
import com.walpro.autobuilder.core.init.StartModeService;
import com.walpro.autobuilder.core.init.SystemResetConfig;
import com.walpro.autobuilder.core.persistence.CyclePersistenceService;
import com.walpro.autobuilder.goal.CoverageReviewResult;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
@Service
@RequiredArgsConstructor
@StateHolder(value = StateBucket.CAPTURE,
        note = "stagnation, zero-work, exhaustion and fingerprint-repeat counters plus the pause "
                + "flag decide promotion, division and halting")
public class AutobuilderService implements CheckpointableState {

    private final SeedCoordinator seedCoordinator;
    private final BuildSystemBootstrap buildSystemBootstrap;
    private final FileService fileService;
    private final AutobuilderConfigProperties autobuilderConfig;
    private final SystemResetConfig systemResetConfig;
    private final CycleContextCoordinator contextCoordinator;
    private final CycleExecutor cycleExecutor;

    /** T3-BY: preserve every N merged cycles during the run; 0 disables (terminal-only, pre-T3-BY). */
    @org.springframework.beans.factory.annotation.Value("${autobuilder.preserve.interval-cycles:10}")
    private int preserveIntervalCycles;

    /** Merged cycles since the last mid-run preserve. */
    private int cyclesSincePreserve;
    private final CyclePersistenceService cyclePersistenceService;
    private final CycleLoggingService cycleLoggingService;
    private final SmsCycleIngestor smsCycleIngestor;
    private final GoalPlannerService goalPlannerService;

    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    /**
     * T4-CA: optional — preserves the best verified snapshot at the pause transition. Null in
     * pure-unit tests, where the halt path behaves exactly as before.
     */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.persistence.PreservedArtifactService preservedArtifactService;

    /**
     * T2-EQ: optional — asks whether the artifact can be packaged before the run halts. Null in
     * pure-unit tests, where the halt path behaves exactly as before.
     */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.build.PreHaltPackagingCheck preHaltPackagingCheck;

    /**
     * T4-CD slice 1: optional — resumes a preserved prior lineage at boot instead of resetting.
     * Null in pure-unit tests, where init() behaves exactly as before.
     */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.init.RunResumeService runResumeService;

    /**
     * T4-CD slice 2: optional — writes the run end report (terminal self-description) once per
     * pause episode, at the same home as T4-CA preserve. Null in pure-unit tests.
     */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.persistence.RunEndReportService runEndReportService;

    /** T4-DP: names how this run begins and refuses a mode the tree cannot support. */
    @Autowired(required = false)
    @lombok.Setter
    private com.walpro.autobuilder.core.init.StartModeService startModeService;

    /**
     * T4-DR: the tree-property repairs, shared with the merge path. Applied once to the tree a
     * CONTINUE_LINEAGE run is handed, because a merge is an event that run may never have.
     */
    @Autowired(required = false)
    @lombok.Setter
    private com.walpro.autobuilder.core.build.StructuralTreeRepair structuralTreeRepair;
    /** T2-ES: the one home for injecting work items. Optional, as in every other caller. */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.blocker.BlockerSubgoalInjector blockerSubgoalInjector;

    /** T2-ES: derives the peer differences the escalation names. Optional, as the other analyzers are. */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.analysis.structure.WiringFactsAnalyzer wiringFactsAnalyzer;

    /** T2-ES: lets the disagreement threshold be tuned like the structural-defect one. */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.tuning.TuningParameterStore tuningParameterStore;

    private final LlmCircuitBreaker llmCircuitBreaker;
    private final com.walpro.autobuilder.core.lifecycle.RootConvergenceTracker rootConvergenceTracker;
    private final com.walpro.autobuilder.goal.GoalConsolidationService goalConsolidationService;
    private final OrchestrationSnapshotService orchestrationSnapshotService;
    private final com.walpro.autobuilder.core.init.RunIdentityService runIdentityService;

    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Executed context (bootstrap or normal cycle).
     * May be {@code null} before the first successful run.
     */
    private volatile CycleContextData context;

    /**
     * Backstop counter (T1-AK): incremented each time a subgoal is marked GIVEN_UP without a
     * preceding merge. Reset to zero on every successful merge. When it reaches
     * maxConsecutiveSubgoalExhaustionsWithoutMerge the loop is escalated to GOAL_EXHAUSTED +
     * SYSTEM_PAUSED.
     */
    private int consecutiveSubgoalExhaustionsWithoutMerge = 0;

    /**
     * T1-AH: stagnation circuit breaker counter. Incremented each time a cycle ends STAGNATING
     * without a merge. Reset on every successful merge. Activates the circuit breaker flag at
     * STAGNATION_BREAKER_THRESHOLD; escalates to SYSTEM_PAUSED at STAGNATION_PAUSE_THRESHOLD.
     */
    private int consecutiveStagnatingCyclesWithoutMerge = 0;
    private static final int STAGNATION_BREAKER_THRESHOLD = 5;
    private static final int STAGNATION_PAUSE_THRESHOLD = 10;

    /**
     * T1-FL: the pause reason reserved for LlmCircuitBreaker-caused pauses. Only pauses carrying
     * exactly this reason auto-resume when the breaker's timed cooldown elapses.
     */
    private static final String LLM_BREAKER_PAUSE_REASON = "llm-circuit-breaker";

    /**
     * T2-AP: score plateau detection. Tracks the score at the last evaluation so we can detect
     * a flat band across consecutive cycles. NaN until the first evaluation runs.
     */
    private double lastEvaluatedScore = Double.NaN;

    /**
     * T2-CD: consecutive cycles where evolution produced the same fingerprint (REPETITION_DETECTED).
     * When this reaches SAME_FINGERPRINT_FULFILLED_THRESHOLD and the current subgoal has no
     * acceptance criteria, the snapshot eval score is checked; if stable and above 0.75, the
     * subgoal is promoted to FULFILLED rather than continuing to churn.
     */
    private int consecutiveSameFingerprintCycles = 0;

    /**
     * T1-EB: consecutive cycles that produced zero useful work (no merge, no mutation proposed,
     * no agent executed) while BUILD_BROKEN is active. Reset on any merge or agent execution.
     * When this reaches zeroWorkDeadlockLimit, the loop emits GOAL_EXHAUSTED + SYSTEM_PAUSED.
     */
    private int consecutiveZeroWorkCycles = 0;

    /**
     * T1-EI: consecutive cycles where the planner emitted proposals that were all dedup-blocked
     * (skipped &gt; 0, injected == 0) without an intervening merge. When this reaches
     * {@link #plannerEmptyOutputLimit}, the loop emits GOAL_EXHAUSTED + SYSTEM_PAUSED to break
     * the planner-output-truncated spiral. Reset on any merge or any successful injection.
     */
    private int consecutivePlannerEmptyOutputCycles = 0;

    private static final int SAME_FINGERPRINT_FULFILLED_THRESHOLD = 3;

    /**
     * T4-CJ slice 2: may this snapshot be used as proof of progress in the CURRENT run?
     *
     * <p>The orchestration graph accumulates across every run of a lineage and the snapshot's
     * anchor is time-ordered, so on a resumed run the newest attempt for a goal can belong to a
     * PREVIOUS run. That is legitimate continuation context to show an agent, but it is not this
     * run's own evidence and must not drive a completion decision.
     *
     * <p>Fails CLOSED on a missing stamp: a row written before the stamp existed is unattributable,
     * and an unattributable row is exactly the case this check exists to reject.
     */
    static boolean isThisRunsEvidence(OrchestrationSnapshot snapshot, String currentRunId) {
        return snapshot != null
                && snapshot.attemptRunId() != null
                && currentRunId != null
                && snapshot.attemptRunId().equals(currentRunId);
    }
    private static final int PLATEAU_THRESHOLD = 5;
    private static final double PLATEAU_BAND = 0.03;

    /**
     * T1-BY: cap on how many consecutive times the T1-BP merge-suppression can fire for the
     * same subgoal without allowing eviction. Prevents immortal-subgoal deadlocks when a
     * subgoal keeps producing accepted-but-plateau merges indefinitely.
     */
    private static final int MAX_SUPPRESSED_EVICTIONS = 3;

    @Value("${autobuilder.goal.max-consecutive-subgoal-exhaustions:5}")
    private int maxConsecutiveSubgoalExhaustionsWithoutMerge = 5;

    /**
     * T2-EI slice 1b: earned per-leaf retry budget. A REQUIRED subgoal is otherwise given up the
     * first time it is declared exhausted (no per-leaf retries) — the 2026-06-27 reliability batch
     * died 3/5 when a single required subgoal exhausted and cascaded to root GIVEN_UP. When enabled,
     * a required subgoal gets fresh attempts (stagnation state reset) before give-up; EXTRA-origin
     * subgoals keep the eager give-up (absorbed at closure anyway).
     *
     * <p>The budget is RELATIVE, not a constant ([[feedback-relative-thresholds]]): one fresh
     * attempt per currently-unmet acceptance criterion ({@code countUnmetCriteria}, floor 1) —
     * attempts proportional to the work item's remaining declared scope, shrinking as criteria
     * bank. Replaces the experiment-era fixed {@code subgoal-retry-budget} count (the old key is
     * gone; this boolean is the switch). Counter still resets on any merged cycle (merge =
     * progress = refreshed budget). See [[project-t2ei-design-note]] slice 1b.
     */
    @Value("${autobuilder.goal.subgoal-retry-enabled:false}")
    private boolean subgoalRetryEnabled = false;

    /** T2-EI slice 1b: per-subgoal exhaustion-retry counter, cleared on any merged cycle. */
    private final java.util.Map<String, Integer> subgoalRetryCount = new java.util.HashMap<>();

    /**
     * T2-EJ: divide-and-conquer fallback at the give-up edge. When a REQUIRED subgoal has spent
     * its retries (pair with {@code subgoal-retry-enabled} — the division is meant as the
     * step AFTER retries, not instead of them) and output verification graded two or more of its
     * criteria unmet, divide it into independently-attemptable per-criterion subgoals instead of
     * giving up. Targets the 2026-06-27 run-2 failure: one subgoal bundling 5 behaviour rules,
     * 0/5 passed, root GIVEN_UP. false = disabled (default, preserves prior behaviour).
     * See [[project-t2ei-design-note]].
     */
    @Value("${autobuilder.goal.divide-on-exhaustion:false}")
    private boolean divideOnExhaustion = false;

    /** Minimum GIVEN_UP children required before per-parent exhaustion fires (floor for the majority rule). */
    @Value("${autobuilder.goal.min-exhausted-children-floor:5}")
    private int minExhaustedChildrenFloor = 5;

    /** T1-EB: consecutive zero-work cycles with BUILD_BROKEN that trigger the deadlock halt. */
    @Value("${autobuilder.deadlock.zero-work-limit:5}")
    private int zeroWorkDeadlockLimit = 5;

    /** T1-EI: consecutive planner empty-output cycles that trigger the planner-amputation halt. */
    @Value("${autobuilder.deadlock.planner-empty-output-limit:5}")
    private int plannerEmptyOutputLimit = 5;

    /**
     * Controls whether the Autobuilder bootstrap cycle is executed on startup.
     * Default: true (enabled in normal runtime).
     * In tests, we set this to false via application-test.properties.
     */
    @Value("${autobuilder.bootstrap.enabled:true}")
    private boolean bootstrapEnabled = true;

    /**
     * Controls whether the scheduled runAutobuilderCycle() should actually run.
     * Default: true in normal runtime. In tests: false.
     */
    @Value("${autobuilder.scheduler.enabled:true}")
    private boolean schedulerEnabled = true;

    private boolean systemPaused = false;

    /**
     * T4-DP: the start was refused, so this run never initialised. The scheduler must not cycle on
     * a tree nobody confirmed — the refusal exists precisely to leave the disk untouched, and a
     * loop that then starts working defeats it.
     */
    private volatile boolean startRefused = false;

    /**
     * T4-CQ: adopted, but with nothing to work towards yet.
     *
     * <p>{@code ADOPT_PROJECT} deliberately does not invent a goal for a codebase the loop did not
     * write. It then has to actually WAIT for one, which is not the same as being refused: the
     * start succeeded, the tree is adopted, and only the purpose is missing. Cycling anyway walks
     * into the planner's no-goals fallback, which invents the ids
     * {@code bootstrap-project-structure} / {@code create-basic-structure} for a context that has
     * no such goals — and the first thing to treat one as a real parent throws
     * {@code Parent goal not found}, which is how the first adopted run died at boot.
     */
    private volatile boolean awaitingGoal = false;

    /** T5-BF follow-up: guards {@link #emitSystemPausedOnce} — one behaviour event per pause
     *  episode. Reset alongside {@code systemPaused} on the first provably-unpaused tick. */
    private boolean pauseEventEmitted = false;

    /** T4-CD slice 2: guards the end report write — one end report per pause episode. */
    private boolean endReportWrittenThisEpisode = false;

    /**
     * T4-DQ: takes the per-cycle checkpoint. Optional so the unit tests that construct this
     * service by hand need no stub; without it the loop simply cannot rewind.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @lombok.Setter
    private com.walpro.autobuilder.core.checkpoint.CheckpointService checkpointService;

    /**
     * T4-DQ slice 3: consulted when the convergence brake decides the run is grinding, so it backs
     * out to its last point that landed work instead of dying there. Optional for the same reason;
     * absent means the halt behaves exactly as it always has.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @lombok.Setter
    private com.walpro.autobuilder.core.checkpoint.RewindPolicy rewindPolicy;

    /**
     * T4-DQ: the operator's rewind goes through this service rather than straight to the
     * {@link com.walpro.autobuilder.core.checkpoint.RewindService}, because the restore alone is
     * not the whole rewind — see {@link #settleAfterRewind}. Optional for the same reason as the policy.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @lombok.Setter
    private com.walpro.autobuilder.core.checkpoint.RewindService rewindService;

    /**
     * T4-DQ slice 4: optional — at the run terminal, ends the run on the path that got furthest
     * rather than the one it was last on. Absent leaves the terminal exactly as it was.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @lombok.Setter
    private com.walpro.autobuilder.core.checkpoint.BranchExplorer branchExplorer;

    /** T4-BP step 1: criteria verification on the #NoDiff route — optional, wired by Spring if present. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @lombok.Setter
    private com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService criteriaVerificationService;

    // ---------------------------------------------------------------------------------
    // T4-DQ: checkpoint / rewind
    // ---------------------------------------------------------------------------------

    /**
     * The run-level brakes: every counter that decides promotion, division, escalation or halting,
     * plus the pause flags. This is the largest single block of decision-bearing state in the
     * loop and none of it was ever persisted — a run that was three zero-work cycles from a
     * deadlock escalation and one from a stagnation promotion would, without this, come back from
     * a rewind at zero on both.
     *
     * <p>{@code context} is absent: the cycle context has its own durable home
     * ({@code mutations/<cycleId>/meta.json}), which the checkpoint references rather than copies.
     * {@code preserveIntervalCycles} and the limit fields are configuration.
     */
    public record Snapshot(int cyclesSincePreserve,
                           int consecutiveSubgoalExhaustionsWithoutMerge,
                           int consecutiveStagnatingCyclesWithoutMerge,
                           double lastEvaluatedScore,
                           int consecutiveSameFingerprintCycles,
                           int consecutiveZeroWorkCycles,
                           int consecutivePlannerEmptyOutputCycles,
                           java.util.Map<String, Integer> subgoalRetryCount,
                           boolean systemPaused,
                           boolean pauseEventEmitted,
                           boolean endReportWrittenThisEpisode) {}

    @Override
    public String stateKey() {
        return "runBrakes";
    }

    @Override
    public Object captureState() {
        return new Snapshot(cyclesSincePreserve, consecutiveSubgoalExhaustionsWithoutMerge,
                consecutiveStagnatingCyclesWithoutMerge, lastEvaluatedScore,
                consecutiveSameFingerprintCycles, consecutiveZeroWorkCycles,
                consecutivePlannerEmptyOutputCycles, new java.util.TreeMap<>(subgoalRetryCount),
                systemPaused, pauseEventEmitted, endReportWrittenThisEpisode);
    }

    @Override
    public void restoreState(com.fasterxml.jackson.databind.JsonNode state,
                             com.fasterxml.jackson.databind.ObjectMapper mapper) {
        cyclesSincePreserve = 0;
        consecutiveSubgoalExhaustionsWithoutMerge = 0;
        consecutiveStagnatingCyclesWithoutMerge = 0;
        lastEvaluatedScore = Double.NaN;
        consecutiveSameFingerprintCycles = 0;
        consecutiveZeroWorkCycles = 0;
        consecutivePlannerEmptyOutputCycles = 0;
        subgoalRetryCount.clear();
        systemPaused = false;
        pauseEventEmitted = false;
        endReportWrittenThisEpisode = false;
        if (state == null || state.isNull()) return;
        Snapshot snapshot = mapper.convertValue(state, Snapshot.class);
        cyclesSincePreserve = snapshot.cyclesSincePreserve();
        consecutiveSubgoalExhaustionsWithoutMerge = snapshot.consecutiveSubgoalExhaustionsWithoutMerge();
        consecutiveStagnatingCyclesWithoutMerge = snapshot.consecutiveStagnatingCyclesWithoutMerge();
        lastEvaluatedScore = snapshot.lastEvaluatedScore();
        consecutiveSameFingerprintCycles = snapshot.consecutiveSameFingerprintCycles();
        consecutiveZeroWorkCycles = snapshot.consecutiveZeroWorkCycles();
        consecutivePlannerEmptyOutputCycles = snapshot.consecutivePlannerEmptyOutputCycles();
        if (snapshot.subgoalRetryCount() != null) subgoalRetryCount.putAll(snapshot.subgoalRetryCount());
        systemPaused = snapshot.systemPaused();
        pauseEventEmitted = snapshot.pauseEventEmitted();
        endReportWrittenThisEpisode = snapshot.endReportWrittenThisEpisode();
    }

    /**
     * T4-DQ: whether the loop is halted. Read by the operator rewind endpoint, which refuses to
     * replace {@code output/} and rewrite goal state underneath a cycle that is still running.
     */
    public boolean isPaused() {
        return systemPaused;
    }

    public Optional<CycleContextData> getContext() {
        return Optional.ofNullable(context);
    }

    /**
     * Banks an output-verification pass map onto the subgoal. When the subgoal declared no
     * acceptance criteria, the verified statement was its description (see
     * {@code CriteriaVerificationService.verifyAndRecord}) — declare it as the goal's criterion
     * before banking, otherwise the T2-DR closure ledger still blocks the goal as
     * "no acceptanceCriteria declared" despite the verified observation.
     */
    private void bankVerifiedCriteria(String subGoalId, CycleContextData ctx, Map<Integer, Boolean> verified) {
        java.util.List<String> declared = ctx.currentSubGoalCriteria();
        if ((declared == null || declared.isEmpty()) && ctx.currentSubGoalDescription() != null) {
            goalPlannerService.setAcceptanceCriteria(subGoalId, java.util.List.of(ctx.currentSubGoalDescription()));
        }
        goalPlannerService.saveCriteriaPassStatus(subGoalId, verified);
    }

    /** T4-BP-4: texts of the criteria the verification graded ✗ (1-based pass-map indices). */
    private static java.util.List<String> unmetCriteriaTexts(CycleContextData ctx, Map<Integer, Boolean> verified) {
        java.util.List<String> declared = ctx.currentSubGoalCriteria();
        if (declared == null || declared.isEmpty()) return java.util.List.of();
        java.util.List<String> unmet = new java.util.ArrayList<>();
        for (Map.Entry<Integer, Boolean> e : verified.entrySet()) {
            int idx = e.getKey() - 1;
            if (!e.getValue() && idx >= 0 && idx < declared.size()) {
                unmet.add(declared.get(idx));
            }
        }
        return unmet;
    }

    /**
     * T4-BZ-3 (Q-d): operator/REST injection for request-family kinds — enqueues on the
     * request channel instead of writing the stored signal map. Same locking/no-context
     * contract as {@link #injectSignal}.
     */
    public void injectRequest(ChannelRequest request) {
        if (request == null) return;
        lock.lock();
        try {
            if (context == null) {
                log.debug("injectRequest {} ignored — no context yet", request.kind());
                return;
            }
            context = context.issueRequest(request);
            log.info("injectRequest: {} urgency={} source={}", request.kind(), request.urgency(), request.source());
        } finally {
            lock.unlock();
        }
    }

    /**
     * T4-BZ-4 (Q-d): operator/REST grant of a role's participation window — WINDOW-family
     * names grant/refresh instead of writing the stored signal map. Same locking/no-context
     * contract as {@link #injectSignal}.
     */
    public void injectWindow(com.walpro.autobuilder.core.lifecycle.ParticipationWindow window) {
        if (window == null) return;
        lock.lock();
        try {
            if (context == null) {
                log.debug("injectWindow {} ignored — no context yet", window.role());
                return;
            }
            context = context.grantWindow(window);
            log.info("injectWindow: {} boost={} source={}", window.role(), window.boost(), window.source());
        } finally {
            lock.unlock();
        }
    }

    /**
     * T4-BZ-5: the pause toggle — REST pause and TOGGLE-family injection set a recorded
     * reason; {@link #clearHaltSignals} clears it.
     */
    public void pause(String reason) {
        lock.lock();
        try {
            if (context == null) {
                log.debug("pause({}) ignored — no context yet", reason);
                return;
            }
            context = context.withPausedReason(reason != null ? reason : "user-pause");
            log.info("pause: reason={}", reason);
        } finally {
            lock.unlock();
        }
    }

    /**
     * T4-BZ-5: operator switches (formerly REST-injected STEERING_SUPPRESS/RESTAGE_SUPPRESS).
     */
    public void setOperatorSwitch(SignalType type, boolean on) {
        lock.lock();
        try {
            if (context == null) {
                log.debug("setOperatorSwitch({}, {}) ignored — no context yet", type, on);
                return;
            }
            if (BasicSignals.STEERING_SUPPRESS.equals(type)) {
                context = context.withSteeringSuppressed(on);
            } else if (BasicSignals.RESTAGE_SUPPRESS.equals(type)) {
                context = context.withRestageSuppressed(on);
            }
            log.info("setOperatorSwitch: {} = {}", type.name(), on);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes a single signal by type from the carried-over cycle context.
     * No-op when no context exists. Safe to call from REST handlers.
     */
    public void removeSignal(SignalType type) {
        if (type == null) return;
        lock.lock();
        try {
            if (context == null) {
                log.debug("removeSignal {} ignored — no context yet", type);
                return;
            }
            // T4-BZ-3: consumeRequest clears both carriers (open request + stored twin), so
            // DELETE keeps working for every family during the migration. T4-BZ-4: a ROLE_*
            // name additionally revokes the participation window (all carriers clear).
            context = context.consumeRequest(type);
            String role = com.walpro.autobuilder.core.lifecycle.BasicSignals.roleNameFor(type);
            if (role != null) {
                context = context.revokeWindow(role);
            }
            // T4-BZ-5: toggles clear their carriers too.
            if (BasicSignals.SYSTEM_PAUSED.equals(type)) {
                context = context.withPausedReason(null);
            } else if (BasicSignals.STEERING_SUPPRESS.equals(type) || BasicSignals.RESTAGE_SUPPRESS.equals(type)) {
                context = context.withSteeringSuppressed(BasicSignals.STEERING_SUPPRESS.equals(type) ? false : context.steeringSuppressed())
                                 .withRestageSuppressed(BasicSignals.RESTAGE_SUPPRESS.equals(type) ? false : context.restageSuppressed());
            }
            log.info("removeSignal: {} removed", type);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Clears the halt signals ({@code SYSTEM_PAUSED} and {@code GOAL_EXHAUSTED}) from the
     * carried-over cycle context and flips the result state back to {@code CONTINUE} so the
     * scheduler resumes on the next tick. No-op when no context exists (service not warmed).
     * Intended for the "resume loop" action in the UI and for coverage-review follow-ups.
     */
    public void clearHaltSignals() {
        lock.lock();
        try {
            if (context == null) {
                log.debug("clearHaltSignals ignored — no context yet");
                return;
            }
            context = context
                    .withPausedReason(null)
                    .withGoalExhaustedReason(null)
                    .withCycleResultState(CycleResultState.CONTINUE);
            log.info("clearHaltSignals: pause + goal-exhausted reasons cleared — loop will resume on next cycle");
        } finally {
            lock.unlock();
        }
    }

    @PostConstruct
    public void init() throws IOException {
        if (!bootstrapEnabled) {
            log.warn("Autobuilder bootstrap disabled via configuration (autobuilder.bootstrap.enabled=false)");
            return;
        }

        // T4-DP: how this run begins, said once and checked against what is actually in the tree.
        // The check is not ceremony: NEW_PROJECT clears output/, the standard entry point defaulted
        // to it, and on 2026-09-03 the target was a codebase the loop had not written.
        StartModeService.Decision start = startModeService != null
                ? startModeService.decide()
                : null;
        if (start != null && !start.allowed()) {
            log.warn("T4-DP: refusing to start in {} — {}", start.mode(), start.explanation());
            log.warn("T4-DP: nothing on disk has been touched. Set autobuilder.start.mode to a mode "
                    + "this tree supports, then restart.");
            startRefused = true;
            return;
        }
        performStart(start);
        StartMode startedAs = start != null ? start.mode() : StartMode.NEW_PROJECT;
        if (startedAs == StartMode.ADOPT_PROJECT && goalPlannerService.getRootGoal() == null) {
            awaitingGoal = true;
            log.warn("T4-CQ: adopted {} and it has no goal yet, so the loop is waiting rather than "
                    + "cycling. Read it with GET /autobuilder/cold-read and set one with "
                    + "POST /autobuilder/goal.", startModeService != null
                            ? startModeService.situation() : "this tree");
            return;
        }
        repairInheritedTree(startedAs);
        runAutobuilderCycle();
    }

    /**
     * T4-DR: applies the structural repairs the loop already owns to the tree it was HANDED,
     * before the run spends a cycle on anything else.
     *
     * <p><b>Why this is not the merge hook's job.</b> Those repairs assert a property of the tree
     * — "a type that is asked for must be obtainable", "a type sits with the peers that name it" —
     * and a property of the tree is true or false whether or not this cycle wrote to it. A run
     * that inherits its tree starts with the property already false and may never merge at all:
     * {@code 2026-09-03_20-25-22} named its own unstartable tree in its goal text, rendered the
     * diagnosis eight times, and ran 44 cycles with zero merges, so the repair for that exact tree
     * was never entered. Measured over the whole corpus, that run and one pre-{@code T1-FX} run
     * are the only two whose finding the merge hook could never reach.
     *
     * <p><b>Only {@link StartMode#CONTINUE_LINEAGE}.</b> {@code NEW_PROJECT} clears the tree, so
     * there is nothing inherited to repair. {@code ADOPT_PROJECT} is a codebase the loop did not
     * write and has not yet been given a goal — rewriting someone else's source before anyone has
     * asked for work is not a repair, it is an uninvited edit, and {@code T4-CQ}'s cold read is
     * where an adopted tree's findings belong. {@code ASK} touches nothing by definition.
     */
    void repairInheritedTree(StartMode startedAs) {
        if (structuralTreeRepair == null || startedAs != StartMode.CONTINUE_LINEAGE) return;
        try {
            // The same derivation the cycle context uses, so the pass and the cycles that follow
            // it cannot disagree about which tree is the tree.
            String outputPath = fileService.resolveSecurePath("output").toString();
            log.info("T4-DR: continuing a lineage — checking the tree as found for structural "
                    + "findings the loop can already repair, before the first cycle.");
            structuralTreeRepair.repairTree(outputPath);
        } catch (Exception e) {
            // Best-effort, exactly as on the merge path: a repair that cannot run must never stop
            // a run from starting.
            log.warn("T4-DR: start-of-run tree repair could not run — {}", e.toString());
        }
    }

    /** T4-CQ: a goal arrived for an adopted project, so it may start working. */
    public void goalProvided() {
        if (awaitingGoal) {
            awaitingGoal = false;
            log.warn("T4-CQ: a goal was set — the adopted project starts on the next cycle.");
        }
    }

    /** Whether this run is adopted and still has nothing to work towards. */
    public boolean awaitingGoal() {
        return awaitingGoal;
    }

    /**
     * T4-DP slice 4: release a held run and start it in {@code requested}.
     *
     * <p>The counterpart to {@link StartMode#ASK}, which holds a run before anything on disk is
     * touched. Without a release the hold is a dead end and the only way out is a restart, which
     * defeats the point of holding rather than defaulting.
     *
     * <p>Deliberately does NOT run the first cycle itself: the scheduler picks the run up within a
     * tick, and a cycle can take minutes, which is not something to do on the caller's thread while
     * it waits for an answer.
     *
     * @return the decision that was acted on, or the refusal that stopped it
     */
    public synchronized StartModeService.Decision startNow(StartMode requested) throws IOException {
        if (startModeService == null) {
            return new StartModeService.Decision(false, requested, StartModeService.Situation.EMPTY,
                    "start modes are not enabled in this context");
        }
        if (!startRefused) {
            return new StartModeService.Decision(false, startModeService.mode(),
                    startModeService.situation(),
                    "this run has already started; a start mode is chosen once, so restart to change it");
        }
        startModeService.setMode(requested);
        StartModeService.Decision decision = startModeService.decide();
        if (!decision.allowed()) {
            log.warn("T4-DP: refusing to start in {} — {}", decision.mode(), decision.explanation());
            return decision;
        }
        performStart(decision);
        // Last, and only once the start actually succeeded: until this flips the scheduler stays
        // idle, which is what makes a refused or held run leave the disk alone.
        startRefused = false;
        log.warn("T4-DP: released by request — starting in {}", decision.mode());
        return decision;
    }

    /** Whether this run is holding, waiting to be told how to start. */
    public boolean held() {
        return startRefused;
    }

    /** The start actions themselves, shared by the boot path and the release. */
    private void performStart(StartModeService.Decision start) throws IOException {
        StartMode mode = start != null ? start.mode() : StartMode.NEW_PROJECT;
        if (start != null) {
            log.info("T4-DP: starting in {} ({}) — {}", mode, start.situation(), start.explanation());
        }

        // T4-CD slice 1: resume wins over reset when a resumable lineage exists — the reset
        // would delete the very artifact the resume restores. attemptResume() is false unless
        // the flag is on AND preserved state + a non-COMPLETED root are present, so passing
        // both flags means "resume if possible, else fresh".
        //
        // T4-DP: the modes below are what those flags always meant, said out loud. NEW_PROJECT is
        // the only one that may clear the tree, and ADOPT_PROJECT neither clears it nor seeds over
        // what someone else wrote.
        boolean treeCleared = false;
        if (mode == StartMode.CONTINUE_LINEAGE
                && runResumeService != null && runResumeService.attemptResume()) {
            log.info("T4-CD: resumed prior lineage — skipping reset-on-start.");
        } else if (mode.discardsTheTree() && autobuilderConfig.isResetOnStart()) {
            log.warn("Autobuilder system reset triggered via config (AutobuilderService)");
            systemResetConfig.resetSystem();
            treeCleared = true;
        }

        // T4-DP slice 3: after the start has actually happened, never before — NEW_PROJECT clears
        // meta/, so a record written first would be deleted by the step it describes.
        if (start != null && startModeService != null) {
            startModeService.recordStart(start, treeCleared);
        }

        if (mode != StartMode.ADOPT_PROJECT) {
            seedCoordinator.seedFromFileIfNecessary();
        } else {
            log.info("T4-DP: ADOPT_PROJECT — not seeding a goal over a codebase the loop did not "
                    + "write. Read it with GET /autobuilder/cold-read and set one deliberately.");
        }

        fileService.ensureFolderExists("output");

        // T3-BT: seed build system skeleton into output/ before cycle 1 so that mvn compile
        // succeeds on the first invocation and BUILD_FIX is not injected at session start.
        buildSystemBootstrap.seedBuildSystemIfNecessary();
    }

    @Scheduled(fixedDelayString = "${autobuilder.scheduler.inter-cycle-delay-ms:1000}")
    public void runAutobuilderCycle() {
        lock.lock();
        try {
            runAutobuilderCycleLocked();
        } finally {
            lock.unlock();
        }
    }

    /**
     * T5-Q: true while a cycle is actively executing. RetentionPolicy consults this before
     * deleting filesystem entries to avoid racing a mid-cycle snapshot/mutation write.
     */
    public boolean isCycleInProgress() {
        return lock.isLocked();
    }

    /**
     * T1-FD: runs a REST-triggered goal-state mutation under the cycle lock so it cannot
     * interleave with a cycle's own goal transitions. Waits only briefly — a cycle holds the
     * lock for minutes and an http thread must not wait that out — then gives up: empty means
     * "not applied, a cycle is executing; retry". Reentrant from within a cycle. Being paused
     * is deliberately NOT a refusal condition: editing goals while the loop is paused is the
     * normal recovery workflow.
     */
    public <T> Optional<T> runExclusively(java.util.function.Supplier<T> action) {
        boolean acquired;
        try {
            acquired = lock.tryLock(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        if (!acquired) return Optional.empty();
        try {
            return Optional.ofNullable(action.get());
        } finally {
            lock.unlock();
        }
    }

    /**
     * T5-BF follow-up: the single home for the {@code system_paused} behaviour event. Ten
     * producers can pause the loop but only three ever emitted the event (the 2026-07-16
     * afternoon halt via {@code empty-backlog-injection-limit} was invisible to the stream);
     * every pause path now funnels through here, and the guard makes it exactly one event
     * per pause episode regardless of how many producers and catch-alls see the same pause.
     */
    private void emitSystemPausedOnce(String cycleId, String reason, int consecutiveZeroWorkCycles) {
        if (pauseEventEmitted || behaviourLogger == null) {
            return;
        }
        pauseEventEmitted = true;
        behaviourLogger.systemPaused(cycleId, reason != null ? reason : "paused", consecutiveZeroWorkCycles);
    }

    /** T4-CD slice 2: one end report per pause episode, at the terminal the episode settled on. */
    private void writeEndReportOnce(CycleContextData ctx, String terminalReason) {
        if (endReportWrittenThisEpisode || runEndReportService == null) {
            return;
        }
        endReportWrittenThisEpisode = true;
        runEndReportService.write(ctx, terminalReason != null ? terminalReason : "paused");
    }

    private void runAutobuilderCycleLocked() {
        // T4-DP: the start was refused, so nothing on disk was confirmed. Cycling here would undo
        // the point of the refusal, which is that the tree is left exactly as it was found.
        // T4-CQ: adopted with no goal is the same restraint for a different reason — there is
        // nothing to work towards, and the planner's fallback would invent goals that do not exist.
        if (startRefused || awaitingGoal) return;

        // T1-FL: a breaker-caused pause is a suspension, not a terminal. The breaker's timed
        // open (T1-FD) already encodes when the next attempt is allowed; once its cooldown
        // elapses the pause episode is over — otherwise the self-recovering breaker is frozen
        // into a permanent halt and a transient model outage kills the whole run. Only the
        // breaker's own pause reason resumes (permanent opens keep isOpen() true; user-pause
        // and exhaustion halts are untouched). The resume tick must NOT re-process the stale
        // cycleResultState: it was fully processed on the pause tick, before the breaker check.
        boolean resumedFromLlmBreakerPause = false;
        if (context != null && LLM_BREAKER_PAUSE_REASON.equals(context.pausedReason())
                && !llmCircuitBreaker.isOpen()) {
            log.info("LLM circuit breaker cooldown elapsed — resuming from the breaker pause (cycle {}).",
                    context.cycleName());
            context = context.withPausedReason(null);
            if (behaviourLogger != null) {
                behaviourLogger.systemResumed(context.cycleName(), "llm-circuit-breaker-cooldown-elapsed");
            }
            resumedFromLlmBreakerPause = true;
        }

        if (!resumedFromLlmBreakerPause && context != null && context.cycleResultState() != null) {
            if (com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(context)) {
                if (!systemPaused) {
                    // T4-DQ: every honest halt funnels through here — convergence stall, exhaustion
                    // runaway, empty-backlog limit, root exhaustion. Ask ONCE, at the one place
                    // they all reach, whether there is a better path to go back to instead.
                    if (rewindBeforeHalting(context)) {
                        return;
                    }
                    systemPaused = true;
                    log.info("System is paused (cycle {}). Skipping run.", context.cycleName());
                    // Catch-all for pause reasons raised outside this method (REST user-pause,
                    // recorded-state twins) — the guard suppresses it when a producer already
                    // emitted at raise time.
                    emitSystemPausedOnce(context.cycleName(), context.pausedReason(), 0);
                    // T4-DQ slice 4: the run is ending. If it explored more than one path, end it
                    // on the one that got FURTHEST rather than the one it happened to be on when
                    // the brake fired. This runs before preserve deliberately, so the state left
                    // on disk — which a T4-CD resume continues from — is the best explored one.
                    // T4-CA already picks the best TREE to ship; nothing picked the best state to
                    // carry on from, so a run that backed out of a good path and ground on a worse
                    // one handed its successor the worse one.
                    // T4-DQ slice 4: the run is ending honestly, so settle it on the path that
                    // got furthest. The rewind policy does the same when exploration runs out
                    // mid-run, which is the only chance a deadline-killed run gets; this covers
                    // the halts, and both are one-shot so they cannot fight each other.
                    if (branchExplorer != null) {
                        branchExplorer.sealCurrent(context.cycleName());
                        log.info("T4-DQ: paths explored this run — {}", branchExplorer.readout());
                        branchExplorer.adoptMostAdvanced(context.cycleName());
                    }
                    // T4-CA: the run just reached an in-process terminal — preserve the best
                    // verified snapshot BEFORE the JVM idles (an external watcher may kill it).
                    if (preservedArtifactService != null) {
                        preservedArtifactService.preserve(context);
                    }
                    // T4-CD slice 2: end report AFTER preserve so the provenance on disk is this
                    // episode's own (the writer's haltCycle guard rejects a parent run's file).
                    writeEndReportOnce(context, context.pausedReason());
                }
                return;
            }

            // T2-D (redesigned 2026-06-11): the analysis agent's completion belief is a fact
            // about the CURRENT SUBGOAL, not the root — pausing the whole system on it halted a
            // healthy run at 60% spec coverage over a leaf that was indeed done ("Create Member
            // entity", Member.java on disk, 12 goals still ACTIVE). Verify the belief against
            // the output instead: confirmed → promote that subgoal; unconfirmed → drop the
            // belief and keep cycling. Root-scoped completion stays with T2-DR/GOAL_FULFILLED.
            if (context.hasStrongRequest(BasicSignals.POTENTIALLY_COMPLETE, 0.50)) {
                String pcSubGoalId = context.currentSubGoalId();
                context = context.consumeRequest(BasicSignals.POTENTIALLY_COMPLETE);
                Map<Integer, Boolean> pcVerified = criteriaVerificationService != null && pcSubGoalId != null
                        ? criteriaVerificationService.verifyAndRecord(context, pcSubGoalId)
                        : Map.<Integer, Boolean>of();
                if (!pcVerified.isEmpty() && pcVerified.values().stream().allMatch(Boolean::booleanValue)) {
                    log.info("POTENTIALLY_COMPLETE confirmed by output verification ({} criteria) — promoting subgoal {} to FULFILLED.",
                            pcVerified.size(), pcSubGoalId);
                    bankVerifiedCriteria(pcSubGoalId, context, pcVerified);
                    goalPlannerService.applyGoalProgress(pcSubGoalId, GoalProgressState.FULFILLED,
                            "analysis completion belief confirmed by output verification");
                } else {
                    log.info("POTENTIALLY_COMPLETE not confirmed by output verification (subGoalId={}, verified={}) — continuing.",
                            pcSubGoalId, pcVerified);
                    if (!pcVerified.isEmpty() && pcSubGoalId != null) {
                        bankVerifiedCriteria(pcSubGoalId, context, pcVerified);
                    }
                }
            }

            switch (context.cycleResultState()) {
                case GOAL_FULFILLED -> {
                    if (!systemPaused) {
                        systemPaused = true;
                        log.info("Goal fulfilled. Pausing Autobuilder.");
                        // T5-BF: the happy-path halt leaves the same terminal marker in the
                        // behaviour stream as every other pause (fulfilled runs carry no
                        // pausedReason, so the post-cycle catch-all cannot see this one).
                        emitSystemPausedOnce(context.cycleName(), "goal-fulfilled", 0);
                        // T4-CA (2026-07-24): the fulfilled terminal is an in-process halt too, so
                        // preserve the best verified snapshot here as well — the generic pause
                        // branch above (DerivedFacts.systemPaused) does this, but a fulfilled run
                        // carries no pausedReason and never enters it. Without this a successful
                        // lineage leaves no restorable artifact and no artifact_preserved record,
                        // the one in-process terminal missing from T4-CA's coverage.
                        if (preservedArtifactService != null) {
                            preservedArtifactService.preserve(context);
                        }
                        // T4-CD slice 2: a fulfilled terminal writes its end report too — the
                        // lineage's self-description says it finished, not just how it failed.
                        // AFTER preserve so the provenance on disk is this episode's own.
                        writeEndReportOnce(context, "goal-fulfilled");
                    }
                    return;
                }
                case SUBGOAL_EXHAUSTED -> {
                    String subGoalId = context.currentSubGoalId();

                    // T1-AR Fix 2: a subgoal that produced a successful merge in the just-completed
                    // cycle must not be given up. Clear the exhaustion signal and continue.
                    // T1-BY: cap suppression at MAX_SUPPRESSED_EVICTIONS to prevent a plateau-merging
                    // subgoal from becoming immortal. After the cap is hit, allow eviction to proceed.
                    if (context.merged()) {
                        int suppressedCount = goalPlannerService.getGoalMetadataInt(subGoalId, "consecutiveSuppressedEvictions");
                        if (suppressedCount < MAX_SUPPRESSED_EVICTIONS) {
                            log.info("SUBGOAL_EXHAUSTED but merge occurred — suppressing eviction (count={}/{}) for subGoalId={}.",
                                    suppressedCount + 1, MAX_SUPPRESSED_EVICTIONS, subGoalId);
                            goalPlannerService.incrementGoalMetadataInt(subGoalId, "consecutiveSuppressedEvictions");
                            context = context.consumeRequest(BasicSignals.SUBGOAL_EXHAUSTED);
                            break;
                        }
                        log.warn("SUBGOAL_EXHAUSTED + merge occurred but suppression cap reached ({}/{}) — allowing eviction for subGoalId={}.",
                                suppressedCount, MAX_SUPPRESSED_EVICTIONS, subGoalId);
                    }

                    // T1-AR Fix 1: when all subgoals are already given up (currentSubGoalId=null),
                    // applyGoalProgress(null, GIVEN_UP) is a no-op. Halt immediately instead of
                    // looping through recovery cycles that produce no useful work.
                    if (subGoalId == null) {
                        log.error("SUBGOAL_EXHAUSTED with null currentSubGoalId — no subgoal to recover. Halting system.");
                        context = context
                                .withGoalExhaustedReason("null-subgoal-halt")
                                .withPausedReason("null-subgoal-halt");
                        return;
                    }

                    // T1-CF: if all acceptance criteria are already cumulatively satisfied, the
                    // subgoal is done — evolution simply ran out of new diffs to generate. Promote
                    // to FULFILLED rather than abandoning work that is provably complete.
                    if (goalPlannerService.areCriteriaCumulativelySatisfied(subGoalId)) {
                        log.info("SUBGOAL_EXHAUSTED but all criteria met — promoting to FULFILLED instead of GIVEN_UP (subGoalId={}).", subGoalId);
                        consecutiveStagnatingCyclesWithoutMerge = 0;
                        lastEvaluatedScore = Double.NaN;
                        context = context.withPlateauCycles(0).consumeRequest(BasicSignals.SCORE_PLATEAU);
                        goalPlannerService.applyGoalProgress(subGoalId, GoalProgressState.FULFILLED,
                                "exhausted but all criteria cumulatively satisfied");
                        context = context
                                .consumeRequest(BasicSignals.SUBGOAL_EXHAUSTED)
                                .consumeRequest(BasicSignals.STAGNATION)
                                .consumeRequest(BasicSignals.REPETITION_DETECTED)
                                .consumeRequest(BasicSignals.LOW_CONFIDENCE)
                                .withStagnationCircuitBreakerActive(false)
                                .withGoalProgress(GoalProgressState.NOT_EVALUATED);
                        break;
                    }

                    // T4-BP step 3 (promoting direction): before giving up, verify the criteria
                    // against the actual output files. Work frequently lands under SIBLING
                    // subgoals (a controller merge implements several endpoints at once), so this
                    // subgoal's own attempts all look like no-progress and its criteria are never
                    // banked — observed 2026-06-11 22:11: POST /loans/{id}/return given up with
                    // "unmet criteria [1,2,3]" while the endpoint sat implemented on disk, taking
                    // the root to GIVEN_UP over finished work. A banked observation (above) is cheap
                    // and checked first; this is one focused LLM pass, only on the give-up edge.
                    Map<Integer, Boolean> verified = criteriaVerificationService != null
                            ? criteriaVerificationService.verifyAndRecord(context, subGoalId)
                            : Map.<Integer, Boolean>of();
                    if (!verified.isEmpty() && verified.values().stream().allMatch(Boolean::booleanValue)) {
                        log.info("SUBGOAL_EXHAUSTED but output verification passed all {} criteria — promoting to FULFILLED instead of GIVEN_UP (subGoalId={}).",
                                verified.size(), subGoalId);
                        bankVerifiedCriteria(subGoalId, context, verified);
                        consecutiveStagnatingCyclesWithoutMerge = 0;
                        lastEvaluatedScore = Double.NaN;
                        context = context.withPlateauCycles(0).consumeRequest(BasicSignals.SCORE_PLATEAU);
                        goalPlannerService.applyGoalProgress(subGoalId, GoalProgressState.FULFILLED,
                                "exhausted but output verification confirmed all acceptance criteria");
                        context = context
                                .consumeRequest(BasicSignals.SUBGOAL_EXHAUSTED)
                                .consumeRequest(BasicSignals.STAGNATION)
                                .consumeRequest(BasicSignals.REPETITION_DETECTED)
                                .consumeRequest(BasicSignals.LOW_CONFIDENCE)
                                .withStagnationCircuitBreakerActive(false)
                                .withGoalProgress(GoalProgressState.NOT_EVALUATED);
                        break;
                    }
                    if (!verified.isEmpty()) {
                        // Partial pass: bank what the verification proved so T2-DR and any later
                        // check see the real coverage instead of an empty ledger.
                        bankVerifiedCriteria(subGoalId, context, verified);

                        // T4-BP consumer #4: a true partial pass (some ✓, some ✗) is an exact
                        // diagnosis of what is still missing — convert the unmet criteria into one
                        // focused follow-up subgoal instead of carrying a known diagnosis to the
                        // root ledger as a GIVEN_UP blocker (observed 2026-06-12 14:45 run: root
                        // GIVEN_UP over one subgoal whose unmet residue the verifier had already
                        // named). The exhaustion counter still increments — the runaway brake
                        // must see re-scopes the same as give-ups; at the cap the give-up wins.
                        boolean truePartial = verified.containsValue(true) && verified.containsValue(false);
                        if (truePartial
                                && consecutiveSubgoalExhaustionsWithoutMerge + 1 < maxConsecutiveSubgoalExhaustionsWithoutMerge) {
                            java.util.List<String> unmet = unmetCriteriaTexts(context, verified);
                            var residueId = goalPlannerService.rescopeResidueSubGoal(subGoalId, unmet);
                            if (residueId.isPresent()) {
                                consecutiveSubgoalExhaustionsWithoutMerge++;
                                consecutiveStagnatingCyclesWithoutMerge = 0;
                                lastEvaluatedScore = Double.NaN;
                                context = context.withPlateauCycles(0).consumeRequest(BasicSignals.SCORE_PLATEAU)
                                        .consumeRequest(BasicSignals.SUBGOAL_EXHAUSTED)
                                        .consumeRequest(BasicSignals.STAGNATION)
                                        .consumeRequest(BasicSignals.REPETITION_DETECTED)
                                        .consumeRequest(BasicSignals.LOW_CONFIDENCE)
                                        .withStagnationCircuitBreakerActive(false)
                                        .withGoalProgress(GoalProgressState.NOT_EVALUATED);
                                break;
                            }
                        }
                    }

                    // T2-EI slice 1b: earned per-leaf retry budget. Before giving up a REQUIRED
                    // subgoal, grant it fresh attempts — reset its stagnation state (incl. the
                    // cumulativeStagnatingCycles history that would otherwise re-fire exhaustion
                    // Rule D next cycle) and keep it current. The model is stochastic; a fresh
                    // attempt has a real chance (the reliability batch showed identical configs
                    // fulfilling or dying on variance). The budget is one attempt per currently-
                    // unmet criterion (floor 1) — relative to the item's remaining declared scope,
                    // not a constant. EXTRA-origin subgoals skip this (absorbed at closure anyway).
                    if (subgoalRetryEnabled && subGoalId != null
                            && !goalPlannerService.isExtraOriginSubgoal(subGoalId)) {
                        int budget = goalPlannerService.countUnmetCriteria(subGoalId);
                        int used = subgoalRetryCount.merge(subGoalId, 1, Integer::sum);
                        if (used <= budget) {
                            goalPlannerService.resetGoalMetadataInt(subGoalId, "cumulativeStagnatingCycles");
                            consecutiveStagnatingCyclesWithoutMerge = 0;
                            lastEvaluatedScore = Double.NaN;
                            context = context.withPlateauCycles(0)
                                    .consumeRequest(BasicSignals.SCORE_PLATEAU)
                                    .consumeRequest(BasicSignals.SUBGOAL_EXHAUSTED)
                                    .consumeRequest(BasicSignals.STAGNATION)
                                    .consumeRequest(BasicSignals.REPETITION_DETECTED)
                                    .consumeRequest(BasicSignals.LOW_CONFIDENCE)
                                    .withStagnationCircuitBreakerActive(false)
                                    .withGoalProgress(GoalProgressState.NOT_EVALUATED);
                            log.warn("T2-EI slice 1b: required subgoal {} exhausted — retry {}/{} (budget = unmet criteria; reset stagnation, not giving up)",
                                    subGoalId, used, budget);
                            break;
                        }
                        log.warn("T2-EI slice 1b: required subgoal {} exhausted its retry budget ({}/{}, budget = unmet criteria) — giving up",
                                subGoalId, used, budget);
                    }

                    // T2-EJ: retries are spent (the block above fell through) and the subgoal is
                    // about to be given up. If verification graded >= 2 distinct criteria unmet,
                    // divide it into per-criterion subgoals — each gets its own retry budget and
                    // banks progress independently. Observation-based: an empty verification map
                    // divides nothing. Counts toward the exhaustion runaway brake exactly like
                    // the T4-BP-4 residue path — at the cap the give-up wins. EXTRA-origin
                    // subgoals are not divided (absorbed at closure anyway).
                    if (divideOnExhaustion
                            && consecutiveSubgoalExhaustionsWithoutMerge + 1 < maxConsecutiveSubgoalExhaustionsWithoutMerge
                            && !goalPlannerService.isExtraOriginSubgoal(subGoalId)) {
                        java.util.List<String> unmet = unmetCriteriaTexts(context, verified);
                        java.util.List<String> childIds = goalPlannerService.divideExhaustedSubGoal(subGoalId, unmet);
                        if (!childIds.isEmpty()) {
                            consecutiveSubgoalExhaustionsWithoutMerge++;
                            consecutiveStagnatingCyclesWithoutMerge = 0;
                            lastEvaluatedScore = Double.NaN;
                            log.warn("T2-EJ: divided exhausted subgoal {} into {} per-criterion subgoals instead of giving up",
                                    subGoalId, childIds.size());
                            context = context.withPlateauCycles(0).consumeRequest(BasicSignals.SCORE_PLATEAU)
                                    .consumeRequest(BasicSignals.SUBGOAL_EXHAUSTED)
                                    .consumeRequest(BasicSignals.STAGNATION)
                                    .consumeRequest(BasicSignals.REPETITION_DETECTED)
                                    .consumeRequest(BasicSignals.LOW_CONFIDENCE)
                                    .withStagnationCircuitBreakerActive(false)
                                    .withGoalProgress(GoalProgressState.NOT_EVALUATED);
                            break;
                        }
                    }

                    consecutiveSubgoalExhaustionsWithoutMerge++;
                    consecutiveStagnatingCyclesWithoutMerge = 0;
                    lastEvaluatedScore = Double.NaN;
                    context = context.withPlateauCycles(0).consumeRequest(BasicSignals.SCORE_PLATEAU);
                    log.warn("Subgoal exhausted (subGoalId={}, consecutiveWithoutMerge={}/{}). Marking GIVEN_UP and recovering.",
                            subGoalId, consecutiveSubgoalExhaustionsWithoutMerge, maxConsecutiveSubgoalExhaustionsWithoutMerge);

                    goalPlannerService.applyGoalProgress(subGoalId, GoalProgressState.GIVEN_UP,
                            "exhausted after " + consecutiveSubgoalExhaustionsWithoutMerge + " cycles without merge");

                    // T2-AL: if a strict majority of siblings are GIVEN_UP with no completions,
                    // give up on the parent branch entirely rather than replanning more siblings.
                    goalPlannerService.exhaustParentIfStuck(subGoalId, minExhaustedChildrenFloor);

                    if (consecutiveSubgoalExhaustionsWithoutMerge >= maxConsecutiveSubgoalExhaustionsWithoutMerge) {
                        log.error("Exhaustion runaway detected: {} consecutive subgoal exhaustions without a merge. Escalating to root exhaustion — manual input required.",
                                consecutiveSubgoalExhaustionsWithoutMerge);
                        // Emit GOAL_EXHAUSTED + SYSTEM_PAUSED signals; the cycle continues normally
                        // so deriveCycleResult picks up GOAL_EXHAUSTED → next scheduled run halts.
                        context = context
                                .withGoalExhaustedReason("exhaustion-runaway")
                                .withPausedReason("exhaustion-runaway");
                    } else {
                        context = context
                                .consumeRequest(BasicSignals.SUBGOAL_EXHAUSTED)
                                .consumeRequest(BasicSignals.STAGNATION)
                                .consumeRequest(BasicSignals.REPETITION_DETECTED)
                                .consumeRequest(BasicSignals.LOW_CONFIDENCE)
                                .withStagnationCircuitBreakerActive(false)
                                .withGoalProgress(GoalProgressState.NOT_EVALUATED);
                        if (!context.hasStrongRequest(BasicSignals.REPLAN_REQUESTED, 0.50)) {
                            context = context.issueRequest(ChannelRequest.of(BasicSignals.REPLAN_REQUESTED, 1.0, "exhaustion-recovery", ChannelRequest.horizonFromDecay(1.0, 0.20)));
                        }
                    }
                }
                case GOAL_EXHAUSTED -> {
                    String rootGoalId = context.currentGoalId();
                    // T1-DJ: defensive regression check — deriveCycleResult should have returned
                    // GOAL_FULFILLED for a COMPLETED root; reaching here with COMPLETED status
                    // means the guard was bypassed.
                    GoalEntity rootGoal = goalPlannerService.getRootGoal();
                    if (rootGoal != null && rootGoal.getStatus() == GoalStatus.COMPLETED) {
                        log.warn("T1-DJ: GOAL_EXHAUSTED handler reached with COMPLETED root (goalId={}) — " +
                                "deriveCycleResult should have returned GOAL_FULFILLED. Possible regression.", rootGoalId);
                        if (behaviourLogger != null) {
                            behaviourLogger.guardViolated(context.cycleName(), "T1-DJ",
                                    "GOAL_EXHAUSTED handler reached with COMPLETED root goalId=" + rootGoalId);
                        }
                    }
                    log.warn("Root goal exhausted (goalId={}). Pausing — manual input required.", rootGoalId);
                    context = context.withPausedReason("root-exhaustion");
                    return;
                }
                case STAGNATING -> {
                    // T2-AA: track cumulative stagnation per subgoal for pre-emptive exhaustion.
                    if (context.currentSubGoalId() != null) {
                        goalPlannerService.incrementGoalMetadataInt(context.currentSubGoalId(), "cumulativeStagnatingCycles");
                    }
                    // T1-AH: stagnation circuit breaker — count stagnating cycles without a merge.
                    // T1-BU: do not count null-subgoal stagnation cycles — with no active subgoal
                    // no agent can produce a merge, so this is a structural wait state (T1-AD
                    // recovery gap), not a stuck subgoal. Counting it would re-activate the breaker
                    // and permanently block the T1-AD null-subgoal re-plan.
                    if (context.currentSubGoalId() == null) {
                        log.debug("Cycle result STAGNATING with no active subgoal — skipping circuit-breaker count.");
                        break;
                    }
                    consecutiveStagnatingCyclesWithoutMerge++;
                    log.warn("Cycle result STAGNATING (consecutiveWithoutMerge={}/{}).",
                            consecutiveStagnatingCyclesWithoutMerge, STAGNATION_PAUSE_THRESHOLD);

                    if (consecutiveStagnatingCyclesWithoutMerge >= STAGNATION_PAUSE_THRESHOLD) {
                        // T2-EO: verify-before-pause at the stagnation-runaway edge — the third
                        // pause edge, and the only one that halted WITHOUT asking "is the stuck
                        // work actually done?" (T4-I and T1-EI both already run this rescue).
                        // The recorded shape: structure-described subgoals whose structure
                        // already exists on disk —
                        // evolution emits cosmetic diffs, evaluation rejects them as
                        // no-meaningful-change, nothing merges, and the run halts over work that
                        // verification would confirm as complete. Conservative by construction:
                        // the rescue promotes only on an all-criteria-pass result and abstains on
                        // empty/failed verification, so an honest stall still pauses.
                        var verify = goalPlannerService.verifyAndPromoteBeforePause(context);
                        if (verify.madeProgress()) {
                            log.info("T2-EO: verify-before-pause broke the stagnation runaway (rootCompleted={}, promoted={}) — not halting.",
                                    verify.rootCompleted(), verify.promoted());
                            consecutiveStagnatingCyclesWithoutMerge = 0;
                            context = context.withStagnationCircuitBreakerActive(false);
                            break;
                        }
                        // T2-EQ: same last question as the convergence-stall edge — can the build
                        // tool package this artifact? One home, both halt edges.
                        if (preHaltPackagingCheck != null
                                && preHaltPackagingCheck.rescueIfNotPackageable(context.cycleName())) {
                            log.info("T2-EQ: the artifact does not package — repair work item injected at the stagnation-runaway edge; not halting.");
                            consecutiveStagnatingCyclesWithoutMerge = 0;
                            context = context.withStagnationCircuitBreakerActive(false);
                            break;
                        }
                        log.error("Stagnation runaway: {} consecutive stagnating cycles without a merge; verify-before-pause found nothing to promote. Pausing — manual input required.",
                                consecutiveStagnatingCyclesWithoutMerge);
                        context = context
                                .withStagnationCircuitBreakerActive(true)
                                .withPausedReason("stagnation-runaway");
                        return;
                    }

                    if (consecutiveStagnatingCyclesWithoutMerge >= STAGNATION_BREAKER_THRESHOLD) {
                        log.warn("Stagnation circuit breaker activated (consecutiveWithoutMerge={}). Suppressing REPLAN_REQUESTED from interpretation; planner in prune-only mode.",
                                consecutiveStagnatingCyclesWithoutMerge);
                        context = context.withStagnationCircuitBreakerActive(true);
                    }

                    if (!context.hasStrongRequest(BasicSignals.REPLAN_REQUESTED, 0.50)) {
                        context = context.issueRequest(ChannelRequest.of(BasicSignals.REPLAN_REQUESTED, 1.0, "stagnation-recovery", ChannelRequest.horizonFromDecay(1.0, 0.20)));
                    }
                    context = context.issueRequest(ChannelRequest.of(BasicSignals.DIVERGENCE_REQUEST, 1.0, "stagnation-recovery", ChannelRequest.horizonFromDecay(1.0, 0.10)));
                }
                case CONTINUE -> {
                    // next scheduled run
                }
            }
        }

        systemPaused = false;
        pauseEventEmitted = false;
        endReportWrittenThisEpisode = false;

        if (llmCircuitBreaker.isOpen()) {
            String reason = llmCircuitBreaker.pauseReason();
            log.error("LLM circuit breaker is open ({}). Pausing autobuilder.", reason);
            context = (context != null ? context : CycleContextData.builder().build())
                    .withPausedReason(LLM_BREAKER_PAUSE_REASON);
            return;
        }

        if (!schedulerEnabled) {
            log.warn("Autobuilder scheduler disabled via configuration (autobuilder.scheduler.enabled=false)");
            return;
        }

        Optional<CycleContextData> initialContext = contextCoordinator.prepareRuntimeContext(context);
        if (initialContext.isEmpty()) {
            log.warn("Aborting cycle: unable to prepare context.");
            return;
        }

        context = cycleExecutor.runCycle(initialContext.get());

        // T2-CC: evolution declared #NoDiff — the LLM's #NoDiff response is an explicit assertion
        // that the current output already satisfies all criteria for the subgoal. Promote directly
        // to FULFILLED without requiring criteriaPassStatus (which is only written by evaluation and
        // would be absent when the implementation arrived via a prior subgoal's deferred merge).
        context = promoteOnNoDiffOrRefuse(context);

        // T1-EB: build-blocked deadlock breaker. When no agent runs and no mutation is proposed
        // while BUILD_BROKEN is active, increment a counter. Halt once the limit is reached.
        // "Zero work" = nothing merged, no mutation proposed, and no agent produced an outcome.
        if (DerivedFacts.buildBroken(context)) {
            boolean anyAgentRan = context.evolutionOutcome() != null
                    || context.evaluationOutcome() != null
                    || context.plannerOutcome() != null
                    || context.analysisOutcome() != null;
            boolean zeroWork = !context.merged()
                    && context.mutationState() == null
                    && !context.rolledBackThisCycle()
                    && !anyAgentRan;
            if (zeroWork) {
                consecutiveZeroWorkCycles++;
                log.warn("T1-EB: zero-work cycle with BUILD_BROKEN active (consecutiveZeroWorkCycles={}/{})",
                        consecutiveZeroWorkCycles, zeroWorkDeadlockLimit);
                if (consecutiveZeroWorkCycles >= zeroWorkDeadlockLimit) {
                    log.error("T1-EB: build-blocked deadlock — {} consecutive zero-work cycles. Pausing — manual intervention required.",
                            consecutiveZeroWorkCycles);
                    emitSystemPausedOnce(context.cycleName(), "build-blocked-deadlock", consecutiveZeroWorkCycles);
                    context = context
                            .withGoalExhaustedReason("build-blocked-deadlock")
                            .withPausedReason("build-blocked-deadlock");
                }
            } else {
                consecutiveZeroWorkCycles = 0;
            }
        } else {
            consecutiveZeroWorkCycles = 0;
        }

        // T1-EI: planner-output-truncated backstop. When the planner emitted proposals that
        // were all dedup-blocked (skipped > 0, injected == 0) and no merge occurred, the
        // session is in the rehabilitation spiral. After N such cycles, pause for review.
        boolean plannerEmptyOutput = context.plannerOutcome() != null
                && context.plannerOutcome().updated()
                && context.skippedGoals() != null
                && !context.skippedGoals().isEmpty()
                && !context.merged();
        if (plannerEmptyOutput) {
            consecutivePlannerEmptyOutputCycles++;
            log.warn("T1-EI: planner-empty-output cycle (consecutive={}/{})",
                    consecutivePlannerEmptyOutputCycles, plannerEmptyOutputLimit);
            if (consecutivePlannerEmptyOutputCycles >= plannerEmptyOutputLimit) {
                // T2-EH: verify-before-pause — same discipline as the T4-I brake. The planner-empty
                // spiral also abandons a possibly-near-complete artifact unverified; re-verify the
                // active subgoals against the current code and try root completion before halting.
                var verify = goalPlannerService.verifyAndPromoteBeforePause(context);
                if (verify.madeProgress()) {
                    log.info("T2-EH: verify-before-pause broke the planner-empty stall (rootCompleted={}, promoted={}) — not halting.",
                            verify.rootCompleted(), verify.promoted());
                    consecutivePlannerEmptyOutputCycles = 0;
                } else {
                    log.error("T1-EI: planner-output truncation — {} consecutive empty-output cycles; verify-before-pause found nothing to promote. Pausing for review.",
                            consecutivePlannerEmptyOutputCycles);
                    emitSystemPausedOnce(context.cycleName(),
                            "planner-output-truncated", consecutivePlannerEmptyOutputCycles);
                    context = context
                            .withGoalExhaustedReason("planner-output-truncated")
                            .withPausedReason("planner-output-truncated");
                }
            }
        } else if (context.merged() || (context.plannerOutcome() != null && context.skippedGoals() == null)) {
            consecutivePlannerEmptyOutputCycles = 0;
        }

        // T4-I (root-scoped slice): convergence brake — activity without convergence. Unlike the
        // counters above, a merge alone does NOT reset this; only a new completed-with-merge
        // subgoal (root progress the planner can't fake by re-decomposing) does.
        context = rootConvergenceTracker.track(context);

        // T4-DQ slice 3: the brake has judged that this run is grinding and its only answer, until
        // now, was to end the run. Ending it is the LAST answer, not the only one — a point where
        // work was still landing is on disk, because the loop snapshots the tree every cycle. Back
        // out to it and take a different path instead.
        //
        // Sited HERE rather than inside the brake on purpose: the brake's job is to judge whether
        // the run is still landing work, and what to do about a halt belongs to the thing that
        // owns the cycle. The rest of the post-cycle tail describes the path just abandoned —
        // stagnation counters, plateau tracking, consolidation over a tree that has moved — so the
        // cycle ends here and the next tick rebuilds its context from the restored state.
        //
        // Measured on the runs this exists for: 2026-09-01_23-11-25 landed nothing for 42 of its
        // 53 cycles and 2026-08-31_05-47-22 for 60 of 65; both shipped 5/23.
        // T4-DQ slice 3, mid-run: a full window with nothing landing. This is the only moment a
        // deadline-killed run ever gets — a SIGTERM runs no terminal code — and 55 of 115 recorded
        // runs die that way. The halt cases are handled where they all converge, at the pause
        // transition in this same class.
        if (rewindIfGrinding(context)) {
            return;
        }

        // T4-AJ Part B: catch-up consolidation of equivalent goals. Triggers well before the
        // brake (stagnation streak / ACTIVE-leaf bloat), so folding duplicates gets a chance to
        // restore convergence; no-ops when the cycle already carries a halt signal.
        goalConsolidationService.maybeConsolidate(context);

        context = trackScorePlateau(context);

        // T2-CD: update consecutive same-fingerprint counter
        if (context.merged()) {
            consecutiveSameFingerprintCycles = 0;
        } else if (context.hasOpenRequest(BasicSignals.REPETITION_DETECTED)) {
            consecutiveSameFingerprintCycles++;
        } else if (DerivedFacts.evolutionFingerprintChanged(context)) {
            consecutiveSameFingerprintCycles = 0;
        }

        // T2-CD: fingerprint-repeat FULFILLED inference for criteria-less subgoals
        if (!context.merged() && consecutiveSameFingerprintCycles >= SAME_FINGERPRINT_FULFILLED_THRESHOLD) {
            String cdSubGoalId = context.currentSubGoalId();
            java.util.List<String> cdCriteria = context.currentSubGoalCriteria();
            boolean criteriaLess = (cdCriteria == null || cdCriteria.isEmpty());
            if (cdSubGoalId != null && criteriaLess) {
                var snap = orchestrationSnapshotService.fetchLatestSnapshot(context.currentGoalId(), cdSubGoalId, 5);
                // T4-CJ slice 2: the graph accumulates across a lineage and the snapshot's anchor
                // is time-ordered, so on a resumed run this score can belong to a PREVIOUS run's
                // attempt. Promoting a subgoal to FULFILLED is a completion decision and must rest
                // on THIS run's own evidence — a prior run's score is context, never proof.
                boolean anchoredInThisRun = isThisRunsEvidence(snap, runIdentityService.runId());
                if (snap != null && !anchoredInThisRun) {
                    log.info("T4-CJ: skipping T2-CD fingerprint-repeat promotion for {} — newest attempt "
                                    + "belongs to run {} , not this run ({}).",
                            cdSubGoalId, snap.attemptRunId(), runIdentityService.runId());
                }
                Double scoreNow = anchoredInThisRun ? snap.evalScoreNow() : null;
                Double scoreBefore = anchoredInThisRun ? snap.evalScoreBefore() : null;
                if (scoreNow != null && scoreNow >= 0.75
                        && (scoreBefore == null || scoreNow >= scoreBefore - 0.05)) {
                    log.info("{} consecutive same-fingerprint cycles + stable score ({:.3f}) — promoting criteria-less subgoal to FULFILLED (subGoalId={}).",
                            consecutiveSameFingerprintCycles, scoreNow, cdSubGoalId);
                    consecutiveSameFingerprintCycles = 0;
                    consecutiveStagnatingCyclesWithoutMerge = 0;
                    lastEvaluatedScore = Double.NaN;
                    context = context.withPlateauCycles(0).consumeRequest(BasicSignals.SCORE_PLATEAU);
                    String cdResolution = String.format(java.util.Locale.ROOT,
                            "fingerprint-repeat FULFILLED: criteria-less subgoal with stable score=%.2f over %d cycles (T2-CD)",
                            scoreNow, consecutiveSameFingerprintCycles);
                    goalPlannerService.applyGoalProgress(cdSubGoalId, GoalProgressState.FULFILLED, cdResolution);
                    context = context
                            .consumeRequest(BasicSignals.REPETITION_DETECTED)
                            .consumeRequest(BasicSignals.STAGNATION)
                            .consumeRequest(BasicSignals.LOW_CONFIDENCE)
                            .withStagnationCircuitBreakerActive(false)
                            .withGoalProgress(GoalProgressState.NOT_EVALUATED);
                } else {
                    log.info("{} consecutive same-fingerprint cycles but score insufficient (scoreNow={}) — continuing (subGoalId={}).",
                            consecutiveSameFingerprintCycles, scoreNow, cdSubGoalId);
                }
            }
        }

        if (context.merged()) {
            consecutiveSubgoalExhaustionsWithoutMerge = 0;
            subgoalRetryCount.clear(); // T2-EI slice 1b: a merge means progress — refresh retry budgets
            consecutiveStagnatingCyclesWithoutMerge = 0;
            consecutiveSameFingerprintCycles = 0;
            consecutiveZeroWorkCycles = 0;
            consecutivePlannerEmptyOutputCycles = 0;
            if (context.stagnationCircuitBreakerActive()) {
                log.info("Merge detected — resetting stagnation circuit breaker.");
                context = context.withStagnationCircuitBreakerActive(false);
            }
        }

        context = cyclePersistenceService.persistSnapshot(context);

        // T4-DQ: take the checkpoint HERE, immediately after the tree snapshot the checkpoint
        // references — a checkpoint whose snapshot does not yet exist is a point that cannot be
        // returned to. Every cycle, matching the snapshot cadence, and bounded by retention.
        if (checkpointService != null) {
            checkpointService.write(context);
        }

        cycleLoggingService.logCycleSummary(context);

        smsCycleIngestor.ingestCycle(context);

        log.info("Cycle {} complete. Result: {}, Merged: {}", context.cycleName(), context.cycleResultState(), context.merged());

        // T3-BY: preserve DURING the run, not only at an in-process terminal.
        //
        // T4-CA's preserve fires in the systemPaused branch, and its own comment says it runs
        // there because "an external watcher may kill it". But the watcher does not wait for a
        // pause: measured over 64 recorded runs, 4 ended AFTER their best cycle and every one of
        // them scored 0 — tails of 1, 3, 12 and 64 cycles, terminals deadline x3 and jvm-died.
        // One held a 23/23 artifact at cycle 71 of 74 and shipped a tree that will not compile.
        // A SIGTERM/SIGKILL five seconds apart is not a window a tree copy plus a verification
        // build can be trusted to finish in, so this preserves as it goes instead of trying to
        // react at the end.
        //
        // Interval-gated because preserve() runs a real build probe per candidate: firing every
        // cycle would add a build to every cycle. Gated on merged() too — an unmerged cycle
        // produced no new state worth the probe.
        if (preservedArtifactService != null && dueForMidRunPreserve(context.merged())) {
            preservedArtifactService.preserve(context);
        }

        // T5-BF follow-up: any pause reason raised INSIDE this tick (planner empty-backlog
        // injection limit, convergence brake, runaway guards, circuit breaker…) becomes a
        // behaviour event now — not silently a tick later, and never never.
        if (DerivedFacts.systemPaused(context)) {
            emitSystemPausedOnce(context.cycleName(), context.pausedReason(), 0);
        }
    }

    /**
     * T4-DQ slice 3, MID-RUN: the run is grinding — a full window with nothing landing — so return
     * to a point where work was still landing rather than carrying on down a path that has stopped
     * producing. Returns {@code true} when the loop went back, in which case the caller must END
     * THE CYCLE: the rest of the post-cycle tail describes the path just abandoned.
     *
     * <p>This entry point exists for the runs that never reach any terminal code at all. 55 of the
     * 115 recorded runs die on the harness deadline, which is a SIGTERM — no halt, no end report,
     * no chance to reconsider. For those, mid-run is the only moment there is.
     */
    boolean rewindIfGrinding(CycleContextData context) {
        if (context == null || !rootConvergenceTracker.grinding()) return false;
        return attemptRewind(context, "a full window of cycles landed nothing");
    }

    /**
     * T4-DQ slice 3, AT THE HALT: the run has decided to stop, for whatever reason. Before it
     * does, is there a point that landed work and has not been explored?
     *
     * <p><b>Ungated on the reason, and the field is why.</b> The first cut consulted the rewind
     * only when the convergence brake was halting, which reaches 14 of 115 recorded runs. Widening
     * it to the grind caught more, and then a forced run died at cycle 4 on
     * {@code exhaustion-runaway} — a THIRD path to the same place, with the same question worth
     * asking and nobody asking it. Every honest halt funnels through this one branch, so the
     * question belongs here rather than at each reason in turn
     * ([[feedback-one-invariant-one-home]]).
     *
     * <p>The one exclusion is an operator pause: {@code user-pause} means a person asked the loop
     * to stop, and backing up to keep working would be overriding them. The LLM circuit breaker is
     * excluded too — that pause is a suspension waiting on the endpoint to recover (T1-FL), not a
     * statement that this path is bad, and rewinding would discard good work over a network blip.
     */
    boolean rewindBeforeHalting(CycleContextData context) {
        if (context == null) return false;
        String reason = context.pausedReason();
        if (reason == null || NON_REWINDABLE_PAUSES.contains(reason)) return false;
        return attemptRewind(context, "the run was about to halt (" + reason + ")");
    }

    /**
     * Ask the policy, and if it went back, clear the halt this cycle raised so the run continues.
     * The policy owns the bounds — how many rewinds a run gets, and which fork points remain
     * unexplored — so neither caller needs to know them.
     */
    private boolean attemptRewind(CycleContextData context, String why) {
        if (rewindPolicy == null) return false;
        var rewound = rewindPolicy.considerRewindInsteadOfHalting(
                context.cycleName(), rootConvergenceTracker.cyclesSinceProgress());
        if (rewound.isEmpty()) return false;

        settleAfterRewind(context, rewound.get().carriedContext(), false);
        log.warn("T4-DQ: cycle {} ends at the rewind ({}) — the remainder of its post-cycle work "
                + "describes the path that was just backed out of.", context.cycleName(), why);
        return true;
    }

    /**
     * T4-DQ: an operator asked the loop to return to {@code toCycleId}. Runs under the control
     * loop's own lock (empty when a cycle holds it), performs the restore, and then settles the
     * carried cycle context the same way the in-loop rewind does — which is the part the first
     * field rewinds lacked. Four operator rewinds in a row each restored cleanly and were each
     * undone within ten milliseconds: the checkpoint's holders put {@code systemPaused} back to
     * false, the untouched context still said {@code GOAL_FULFILLED}, and the next tick re-ran the
     * fulfilled terminal — re-pausing, re-preserving, and writing a goal-fulfilled end report over
     * the rewound tree. No cycle ever ran from the restored state.
     *
     * <p>A pause the person asked for is kept: rewinding is not the same as resuming, and the
     * response says which state the loop is left in.
     */
    public Optional<com.walpro.autobuilder.core.checkpoint.RewindService.Result> rewindAsOperator(
            String toCycleId, com.walpro.autobuilder.core.checkpoint.CacheMode mode) {
        if (rewindService == null) return Optional.empty();
        return runExclusively(() -> {
            String at = context != null && context.cycleName() != null ? context.cycleName() : toCycleId;
            var result = rewindService.rewindTo(toCycleId, mode, "operator", at);
            if (result.performed()) {
                settleAfterRewind(context, result.carriedContext(), true);
            }
            return result;
        });
    }

    /**
     * The half of a rewind that the checkpoint cannot carry. The restore puts every holder back,
     * but the cycle context the scheduler reads on its next tick is not a holder: it is the record
     * of the cycle that just RAN, on the path that was just backed out of. Left alone, its result
     * state is re-processed as though it had just happened — a {@code GOAL_FULFILLED} pauses the
     * loop again, a {@code GOAL_EXHAUSTED} raises the exhaustion halt again — and the restored
     * state never gets a cycle. So the context is settled to "continue from here": no halt reason,
     * no exhaustion reason, result {@code CONTINUE}, the same reading the run had when it finished
     * the cycle the checkpoint was taken at. One home for both the policy's rewind and the operator's.
     *
     * <p>Which context gets settled matters as much as the settling. The checkpoint captures the
     * carried context as it stood when the point was taken (T4-DQ, 2026-09-14), and that is what
     * the next tick should read: its consecutive-cycle streaks, relayed rejection reasons and
     * build observations describe the restored tree. Settling the CURRENT context instead — the
     * only option before contexts were captured — hands the restored tree a description of the
     * path just backed out of, and restarts every streak from zero, which is how a rewind staged
     * to sit one cycle short of an escalation threshold could never reach it. The current context
     * remains the fallback for a point that carries none, and the rewind's result says which.
     *
     * @param current       the context of the cycle being backed out of — judged for a user pause,
     *                      and settled from only when {@code restored} is null
     * @param restored      the checkpoint's carried context, or null when the point carries none
     * @param keepUserPause whether a pause a person asked for survives (the operator's rewind keeps
     *                      it; the policy's never fires under one — see {@code NON_REWINDABLE_PAUSES})
     */
    private void settleAfterRewind(CycleContextData current, CycleContextData restored, boolean keepUserPause) {
        if (rootConvergenceTracker != null) {
            rootConvergenceTracker.resyncAfterRewind();
        }
        // A user pause is a fact about NOW, so it is read from the current context whatever the
        // restored one happened to record at the time it was taken.
        boolean userPaused = keepUserPause && current != null && "user-pause".equals(current.pausedReason());
        CycleContextData base = restored != null ? restored : current;
        if (base == null) return;
        // `context` is the service's own field, which the next tick reads.
        this.context = base
                .withPausedReason(userPaused ? "user-pause" : null)
                .withGoalExhaustedReason(null)
                .withCycleResultState(CycleResultState.CONTINUE);
        // The holder restore set these from the checkpoint; this makes the outcome not depend on
        // which tick the checkpoint happened to be written on. A kept user pause keeps its episode
        // flags too, so the next tick does not re-announce, re-preserve and re-report a pause the
        // person already has.
        systemPaused = userPaused;
        pauseEventEmitted = userPaused;
        endReportWrittenThisEpisode = userPaused;
    }

    /** T4-DQ: whether the loop stands paused by a person right now (survives an operator rewind). */
    public boolean userPaused() {
        return context != null && "user-pause".equals(context.pausedReason());
    }

    /**
     * Pauses a rewind must not override: a person asked the loop to stop, or it is waiting for the
     * model endpoint to come back rather than judging the path it is on.
     */
    private static final java.util.Set<String> NON_REWINDABLE_PAUSES =
            java.util.Set.of("user-pause", LLM_BREAKER_PAUSE_REASON);

    /**
     * T3-BY: is this cycle the one that should re-preserve? Counts MERGED cycles only — an
     * unmerged cycle produced no new state, so probing for it would spend a build on nothing —
     * and resets on firing so the interval is between preserves rather than since the run start.
     * Extracted from the cycle tail so the interval arithmetic is testable without a full tick.
     *
     * @param merged whether the just-finished cycle merged a mutation
     * @return true when the caller should preserve now
     */
    boolean dueForMidRunPreserve(boolean merged) {
        if (preserveIntervalCycles <= 0 || !merged) return false;
        if (++cyclesSincePreserve < preserveIntervalCycles) return false;
        cyclesSincePreserve = 0;
        return true;
    }

    /**
     * T2-CC/T1-DN/T2-ES: the promotion decision for an evolution {@code #NoDiff}, and the one
     * place the disagreement between "already implemented" and "criterion unmet" is judged.
     *
     * <p>Extracted from the tick so the REFUSAL BRANCH can be driven by a test. It sits four
     * hundred lines into a scheduled method with sixteen collaborators, so the escalation call
     * inside it was otherwise reachable only in the field — against a condition measured at zero
     * occurrences in 967 recorded cycles. An untestable branch guarding an unobservable condition
     * is not a guard at all.
     *
     * @return the context after promoting, or after refusing and counting the disagreement
     */
    CycleContextData promoteOnNoDiffOrRefuse(CycleContextData context) {
    if (context.hasStrongRequest(BasicSignals.EVOLUTION_NOOP, 0.5)) {
        com.walpro.autobuilder.core.lifecycle.ChannelRequest noOpSignal =
                context.peekRequest(BasicSignals.EVOLUTION_NOOP);
        context = context.consumeRequest(BasicSignals.EVOLUTION_NOOP);
        String noOpSubGoalId = context.currentSubGoalId();
        // T1-EE: evaluation may have fulfilled the signal's originating subgoal mid-cycle and
        // advanced the pointer. Reject the signal if it names a different subgoal than the
        // current one — promoting the wrong subgoal would leave it FULFILLED with zero observation.
        if (noOpSignal != null && noOpSignal.meta() != null) {
            Object signalSubGoalId = noOpSignal.meta().get("subGoalId");
            if (signalSubGoalId != null && !signalSubGoalId.toString().equals(noOpSubGoalId)) {
                log.debug("T1-EE: EVOLUTION_NOOP was for subGoalId={} but current is {} — discarding stale signal",
                        signalSubGoalId, noOpSubGoalId);
                noOpSubGoalId = null;
            }
        }
        if (noOpSubGoalId != null) {
            // T1-DN: outer gate — refuse promotion when concrete state contradicts the LLM.
            String refusalReason = whyRefuseNoDiffPromotion(context);
            // T4-BP step 3 on the #NoDiff route (run 18, 2026-07-06): the step-1 pass used to
            // run AFTER the promotion decision — recording only — so its ✗ landed on an
            // already-COMPLETED goal. That closed-but-unmet contradiction had no owner: T2-DR
            // treated it as a root-closure blocker and gave up the root on work nothing ever
            // retried (false give-up #4). Verify BEFORE deciding and let an unmet criterion
            // refuse the promotion; the pass map is banked either way so the retry ladder and
            // the evolution prompt see exactly the unmet work. Abstain (empty map / failure)
            // keeps the promotion — the closure edge re-verifies with its own gates.
            if (refusalReason == null && criteriaVerificationService != null) {
                Map<Integer, Boolean> verifiedNow = Map.of();
                try {
                    verifiedNow = criteriaVerificationService.verifyAndRecord(context, noOpSubGoalId);
                    if (!verifiedNow.isEmpty()) {
                        goalPlannerService.saveCriteriaPassStatus(noOpSubGoalId, verifiedNow);
                    }
                } catch (Exception e) {
                    log.warn("T4-BP-1: #NoDiff criteria verification failed (no answer — promotion proceeds): {}", e.getMessage());
                }
                if (verifiedNow.containsValue(Boolean.FALSE)) {
                    refusalReason = "verified-criteria-unmet";
                }
            }
            if (refusalReason != null) {
                log.warn("T1-DN: refusing #NoDiff promotion — {} (subGoalId={})", refusalReason, noOpSubGoalId);
                context = context.issueRequest(ChannelRequest.of(BasicSignals.DIVERGENCE_REQUEST, 1.0, "noDiff-trust-gate:" + refusalReason, ChannelRequest.horizonFromDecay(1.0, 0.1)));
                // T2-ES: count the disagreement. The DIVERGENCE_REQUEST above is the loop
                // asking the same question differently, which is what produced 27 identical
                // cycles in the run this ticket was filed from; nothing counted the repeat, so
                // nothing could ever decide that asking again had stopped working.
                context = escalateNoDiffDisagreement(context, noOpSubGoalId, refusalReason);
            } else {
                log.info("Evolution noOp — #NoDiff implies current output satisfies all criteria. Promoting to FULFILLED (subGoalId={}).", noOpSubGoalId);
                // The disagreement is over: this promotion was allowed to proceed.
                context = context.withNoDiffRefusalStreak(0);
                consecutiveStagnatingCyclesWithoutMerge = 0;
                consecutiveSameFingerprintCycles = 0;
                lastEvaluatedScore = Double.NaN;
                context = context.withPlateauCycles(0).consumeRequest(BasicSignals.SCORE_PLATEAU);
                // Route through transitionGoal so banking the acceptance criteria is
                // paired with the status flip; calling applyGoalProgress(FULFILLED)
                // directly skips the bank and leaves criteriaPassStatus empty.
                goalPlannerService.transitionGoal(noOpSubGoalId,
                        com.walpro.autobuilder.goal.GoalStatus.COMPLETED,
                        "evolution #NoDiff — output already satisfies all criteria (T2-CC)");
                context = context
                        .consumeRequest(BasicSignals.SUBGOAL_EXHAUSTED)
                        .consumeRequest(BasicSignals.STAGNATION)
                        .consumeRequest(BasicSignals.REPETITION_DETECTED)
                        .consumeRequest(BasicSignals.LOW_CONFIDENCE)
                        .withStagnationCircuitBreakerActive(false)
                        .withGoalProgress(GoalProgressState.NOT_EVALUATED);
            }
        }
    }
        return context;
    }

    /** T2-ES: how many identical disagreements before the loop changes the question. */
    static final String NODIFF_ESCALATION_PARAM = "escalation.nodiff-disagreement-cycles";
    static final int NODIFF_ESCALATION_DEFAULT = 3;

    /**
     * T2-ES: count one refused {@code #NoDiff} promotion, and when the same subgoal has produced
     * enough of them, escalate instead of asking again.
     *
     * <p>The ticket's measurement: 27 of 110 cycles on one subgoal, every one of them emitting the
     * identical pair of statements — evolution saying the implementation is already present, the
     * promotion gate saying the criterion is unmet. Both were right. What was missing was anything
     * that treated the DISAGREEMENT as information rather than as a reason to re-prompt, and the
     * only thing that eventually stopped it was a run-level emergency brake twenty cycles later.
     *
     * <p>Everything the injected item says is derived: the criterion is the planner's own text, the
     * count is observed, and the structural differences are computed against the project's own
     * peers by the analyzer that already owns that question. No framework or domain vocabulary
     * enters framework code.
     */
    // Package-private: the seam the wiring test drives, because reaching it through a full tick
    // needs every collaborator the service has and would test the scheduler, not this decision.
    CycleContextData escalateNoDiffDisagreement(CycleContextData context, String subGoalId,
                                               String refusalReason) {
        // Only the criteria disagreement counts. A refusal because the build is broken is a
        // different problem with its own actuator, and letting it share this counter would both
        // raise an item with no criterion to name and RESET the streak, masking a real
        // disagreement underneath a passing build.
        if (!isCriteriaDisagreement(refusalReason)) {
            return context;
        }
        int streak = context.noDiffRefusalStreak() + 1;
        context = context.withNoDiffRefusalStreak(streak);
        int threshold = tuningParameterStore != null
                ? (int) Math.round(tuningParameterStore.get(NODIFF_ESCALATION_PARAM, NODIFF_ESCALATION_DEFAULT))
                : NODIFF_ESCALATION_DEFAULT;
        log.info("T2-ES: #NoDiff disagreement {} of {} for subGoalId={}", streak, threshold, subGoalId);
        if (streak < threshold || blockerSubgoalInjector == null) {
            return context;
        }

        List<String> unmet = unmetCriteriaOf(context);
        List<String> differences = List.of();
        if (wiringFactsAnalyzer != null && context.previousOutput() != null) {
            try {
                differences = wiringFactsAnalyzer.peerAnnotationAnomalies(context.previousOutput());
            } catch (RuntimeException e) {
                log.debug("T2-ES: peer anomaly scan failed, escalating without it: {}", e.getMessage());
            }
        }

        var escalation = blockerSubgoalInjector.injectNoDiffDisagreementRedirection(unmet, streak, differences);
        if (behaviourLogger != null) {
            behaviourLogger.noDiffDisagreementEscalated(context.cycleName(), subGoalId, streak,
                    unmet, differences, escalation.injectedGoalId(), escalation.outcome());
        }
        // Whether it injected or was refused, the streak restarts: the next repeat is a new run at
        // the threshold rather than an escalation on every cycle from here on.
        return context.withNoDiffRefusalStreak(0);
    }

    /** Both {@code criteria-unmet} and {@code verified-criteria-unmet} are this disagreement. */
    private static boolean isCriteriaDisagreement(String refusalReason) {
        return refusalReason != null && refusalReason.endsWith("criteria-unmet");
    }

    /**
     * The acceptance criteria currently recorded as failing, in the planner's words.
     *
     * <p><b>The banked map is keyed from ONE.</b> Both writers agree on that: the criteria
     * verification service builds its pass map as {@code passMap.get(i + 1)}, and the operator
     * endpoint writes {@code status.put(criterionIndex, true)} after range-checking from 1. Read
     * from zero and the first failing criterion is invisible while every other one names the text
     * above it — an escalation that quotes the wrong requirement back at the model.
     *
     * <p>Noted rather than fixed here: {@code EvolutionProtocolContract.criteriaProgressBlock}
     * reads the same map from zero. It renders an absent key the same as a false one, so the block
     * reads as pessimistic rather than wrong, but the two conventions should not both exist.
     */
    private static List<String> unmetCriteriaOf(CycleContextData context) {
        Map<Integer, Boolean> status = context.cumulativeCriteriaStatus();
        List<String> criteria = context.currentSubGoalCriteria();
        if (status == null || status.isEmpty() || criteria == null) return List.of();
        List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < criteria.size(); i++) {
            if (Boolean.FALSE.equals(status.get(i + 1))) out.add(criteria.get(i));
        }
        return List.copyOf(out);
    }

    /**
     * T1-DN: returns null when #NoDiff promotion is safe, or a short reason string when it
     * should be refused. Refuses if the build is known-broken or any criterion is unmet.
     */
    private String whyRefuseNoDiffPromotion(CycleContextData context) {
        BuildOutcome buildOutcome = context.buildOutcome();
        if (buildOutcome != null && buildOutcome.failed()) {
            return "build-failed";
        }
        Map<Integer, Boolean> criteriaStatus = context.cumulativeCriteriaStatus();
        if (criteriaStatus != null && criteriaStatus.containsValue(Boolean.FALSE)) {
            return "criteria-unmet";
        }
        return null;
    }

    // T2-AP: update plateauCycles based on this cycle's evaluation outcome.
    private CycleContextData trackScorePlateau(CycleContextData ctx) {
        if (ctx.evaluationOutcome() == null) {
            return ctx; // no evaluation this cycle — plateau unchanged
        }
        double currentScore = ctx.score();
        MutationAcceptanceState state = ctx.mutationState();

        if (state == MutationAcceptanceState.ACCEPTED) {
            int plateau;
            if (!Double.isNaN(lastEvaluatedScore) && Math.abs(currentScore - lastEvaluatedScore) < PLATEAU_BAND) {
                plateau = ctx.plateauCycles() + 1;
            } else {
                plateau = 0; // genuine improvement or first evaluation
            }
            lastEvaluatedScore = currentScore;
            ctx = ctx.withPlateauCycles(plateau);
            if (plateau >= PLATEAU_THRESHOLD) {
                log.warn("Score plateau: {} consecutive flat accepted cycles (score={}). Emitting SCORE_PLATEAU.", plateau, currentScore);
                ctx = ctx.issueRequest(ChannelRequest.of(BasicSignals.SCORE_PLATEAU, 1.0, "score-plateau", ChannelRequest.horizonFromDecay(1.0, 0.05)));
            }
        } else {
            // Mutation rejected — system already reacting, reset plateau
            lastEvaluatedScore = currentScore;
            ctx = ctx.withPlateauCycles(0).consumeRequest(BasicSignals.SCORE_PLATEAU);
        }
        return ctx;
    }

    public void bootstrapOnce() {
        // T1-FD: bootstrap resets/seeds filesystem + goal state — serialize behind any running
        // cycle (same blocking semantics as forceReset; a deliberate heavyweight user action).
        lock.lock();
        try {
            init(); // respects reset-on-start flag and existing bootstrap logic
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Force a full system reset: purges the goal graph, wipes mutation/output/snapshot folders,
     * re-reads {@code bootstrap/seed.txt} as the new root goal, then runs a fresh cycle. Used by
     * the graph browser's "Reset" button so the loop can be restarted without restarting the JVM.
     */
    public void forceReset() {
        lock.lock();
        try {
            log.warn("Manual reset requested via API — clearing graph, filesystem, and goal stack");
            systemResetConfig.resetSystemForce();

            fileService.ensureFolderExists("output");

            consecutiveSubgoalExhaustionsWithoutMerge = 0;
            consecutiveStagnatingCyclesWithoutMerge = 0;
            consecutiveSameFingerprintCycles = 0;
            consecutiveZeroWorkCycles = 0;
            lastEvaluatedScore = Double.NaN;
            systemPaused = false;
            context = null;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
        runAutobuilderCycle();
    }

    /**
     * Runs a coverage retrospective against the root goal and injects any missing features
     * as new first-wave subgoals.  If gaps are found, clears SYSTEM_PAUSED and GOAL_EXHAUSTED
     * from the cycle context so the scheduler can resume.
     *
     * @return list of injected feature descriptions; empty if no gaps found
     */
    public CoverageReviewResult triggerCoverageReview() {
        lock.lock();
        try {
            CoverageReviewResult result = goalPlannerService.triggerManualCoverageReview();
            if (!result.injected().isEmpty() && context != null) {
                context = context
                        .withPausedReason(null)
                        .withGoalExhaustedReason(null)
                        .withCycleResultState(CycleResultState.CONTINUE);
                log.info("coverage-review: cleared SYSTEM_PAUSED + goal-exhausted reason — loop will resume on next cycle");
            }
            return result;
        } finally {
            lock.unlock();
        }
    }
}
