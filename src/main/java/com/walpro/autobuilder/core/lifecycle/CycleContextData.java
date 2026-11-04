package com.walpro.autobuilder.core.lifecycle;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.walpro.autobuilder.agent.analysis.model.AnalysisOutcome;
import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringResult;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.analysis.coverage.SeedCoverageReadout;
import com.walpro.autobuilder.analysis.problem.ProblemContext;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.inject.InjectorFixRecord;
import com.walpro.autobuilder.core.deliberation.DeliberationResult;
import com.walpro.autobuilder.core.merge.MergeRollbackPoint;
import com.walpro.autobuilder.core.merge.PackageFragmentationReport;
import com.walpro.autobuilder.core.merge.RolledBackReport;
import com.walpro.autobuilder.core.mutation.CompileAuthorErrors;
import com.walpro.autobuilder.goal.GoalProgressApplicationResult;
import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.AbandonedGoalCluster;
import com.walpro.autobuilder.analysis.heuristic.HeuristicFinding;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MergeTrace;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import lombok.Builder;
import lombok.With;

@Builder(toBuilder = true)
@With
public record CycleContextData(
        String cycleName,
        String outputPath,
        String mutationPath,
        String snapshotPath,
        Map<String, String> previousOutput,
        String projectSummary,
        ProjectDiffResult projectDiff,

        // Goal tracking
        String currentGoalDescription,
        String currentSubGoalDescription,
        String currentGoalId,
        String currentSubGoalId,
        String analysisScopeGoalId,
        String analysisScopeSubGoalId,
        String lastAnalysisScopeGoalId,
        String lastAnalysisScopeSubGoalId,
        List<GoalEntity> remainingGoals,
        List<GoalEntity> completedGoals,
        List<GoalEntity> abandonedGoals,
        List<String> skippedGoals,
        Integer goalDepth,
        String bestMatchSubGoalId,

        // Evaluation
        // T4-BX: authored reasons the last mutation was rejected — the evaluator's feedbackMessage
        // plus the rationale of each heuristic that voted against it. Relayed VERBATIM (prose, for
        // an LLM to read — not parsed by code) so the next evolution attempt and the planner learn
        // WHY the previous try failed instead of repeating it. Carried one cycle by
        // ContextLineageTracker, cleared on merge. Replaces a dead String that was always null.
        List<String> rejectionFeedback,
        String recommendation,
        EvolutionOutcome evolutionOutcome,
        EvaluationOutcome evaluationOutcome,
        PlannerOutcome plannerOutcome,
        AnalysisOutcome analysisOutcome,
        String evaluationFeedback,
        InterpretationResult evaluationInterpretation,
        MutationAcceptanceState mutationState,
        GoalProgressState goalProgress,
        CycleResultState cycleResultState,

        // Status flags
        boolean subgoalPromoted,
        boolean merged,
        boolean evolutionRanThisCycle,
//    boolean recombined,
//    boolean repeatedRecombination,

        // Fingerprints + lineage
        String lastEvolutionFingerprint,
        String lastEvaluatedEvolutionFingerprint,
        String lastRecombinationFingerprint,
        List<String> recentEvolutionFingerprints,
        String previousAcceptedCycleName,
        String parentCycleName,
        String recombinationSourceCycleName,
        EvolutionOutcome lastEvolutionOutcome,
        EvaluationOutcome lastEvaluationOutcome,
        PlannerOutcome lastPlannerOutcome,
        AnalysisOutcome lastAnalysisOutcome,
        String lastAttemptId,
        String parentAttemptId,

        // Merge
        String mergedPath,
        String mergedBaseContent,
        List<FileVariant> mergedVariants,
        MergeTrace mergeTrace,

        // Evaluation context
        List<HeuristicFinding> findings,
        Map<String, List<Double>> scoreHistoryByFile,
        Map<String, Double> recentDeltas,
        double score,
        Double lastScore,

        // T2-CE: per-subgoal accepted-mutation score baseline, re-fetched from the graph each cycle.
        // Null when no ACCEPTED evaluation has been recorded for the current subgoal yet (first mutation).
        // Used by ScoreRegressionHeuristic and ProblemReductionHeuristic as the within-subgoal comparison
        // baseline; prevents cross-subgoal contamination from lastScore (which carries the global project
        // score from the last merge, potentially from a completely different subgoal).
        Double lastAcceptedSubgoalScore,

        // T2-CE: recent accepted-evaluation score history for the current subgoal (newest-first, up to 5).
        // Re-fetched from the graph alongside lastAcceptedSubgoalScore. Available for meta-deliberation
        // trend rendering: "subgoal scores: 0.70 → 0.68 → 0.65" signals structural stagnation early.
        List<Double> subgoalScoreHistory,
        Double evaluationBias,
        boolean scoringComputed,
        ScoringResult scoringResult,

        // Meta
        Map<String, Double> lastFitnessScores,
        DeliberationResult deliberationResult,
        GoalProgressApplicationResult lastGoalProgressApplication,

        // Problems
        ProblemContext problemContext,
        ProblemContext previousProblemContext,

        // Interpretation output (carried into next cycle for agent prompts)
        String interpretationSummary,
        String interpretationDirectives,

        // T2-AA: accumulated stagnation history for the current subgoal across all its activations.
        // Populated from GoalEntity.metadata["cumulativeStagnatingCycles"] each cycle.
        int cumulativeStagnatingCycles,

        // T1-AH: stagnation circuit breaker — set by AutobuilderService when stagnating cycles
        // without a merge exceed the threshold. Suppresses REPLAN_REQUESTED from interpretation
        // and blocks new subgoal injection (prune-only planner mode) until a merge resets it.
        boolean stagnationCircuitBreakerActive,

        // T2-AJ: file count from the most recently accepted (merged) mutation.
        // Set by MergeAgent after a successful merge; carried forward by ContextLineageTracker.
        // Used by ScoreRegressionHeuristic to widen tolerance for multi-file mutations.
        int lastAcceptedMutationFileCount,

        // T2-AP: number of consecutive accepted mutations where |score - prevScore| < 0.03.
        // Incremented by AutobuilderService; reset on score improvement or mutation rejection.
        // When >= PLATEAU_THRESHOLD, SCORE_PLATEAU signal is emitted and the evolution prompt
        // is instructed to attempt a structurally different approach.
        int plateauCycles,

        // T2-AT: tracks how many consecutive cycles each heuristic has fired at CRITICAL confidence
        // on the current subgoal. Keyed by heuristic name; reset when subgoal changes.
        // Updated by EvaluationAgent after each negotiation; carried forward by ContextLineageTracker.
        Map<String, Integer> criticalHeuristicStreaks,

        // T2-AT: human-readable warning strings derived from criticalHeuristicStreaks when a
        // heuristic reaches >= 2 consecutive CRITICAL cycles. Injected into the evolution prompt
        // as explicit directives so the agent can break the pattern.
        List<String> persistentHeuristicWarnings,

        // T1-BC: base package detected from existing .java files in output/.
        // Null when no Java files exist yet (first evolution — LLM picks freely).
        // Set each cycle by CycleContextCoordinator; injected into evolution prompt as a hard constraint.
        String establishedBasePackage,

        // T3-AW: acceptance criteria for the current subgoal, generated by the planner alongside each subgoal.
        // Persisted in GoalEntity.metadata["acceptanceCriteria"]; hydrated each cycle by CycleContextCoordinator.
        // Rendered as a --- ACCEPTANCE CRITERIA --- block in the evaluation prompt.
        List<String> currentSubGoalCriteria,

        // T3-AZ: per-criterion pass/fail lines parsed from the CriteriaCheck: block in the evaluator response.
        // Each entry is "[N] ✓ <criterion>" or "[N] ✗ <criterion> — <reason>".
        // Set by EvaluationAgent after LLM response is parsed; used by SubGoalFulfillmentHeuristic (T3-BA)
        // to veto FULFILLED when any criterion is unmet.
        List<String> criteriaAssessments,

        // T1-BG: cumulative per-criterion pass status for the current subgoal across all cycles.
        // index → true once the criterion has ever passed; never reverts to false.
        // Null means no criteria have been assessed yet for this subgoal.
        // Reset to null by CycleContextCoordinator when currentSubGoalId changes.
        Map<Integer, Boolean> cumulativeCriteriaStatus,

        // T2-BL: criteria pass count as of the end of the previous evaluation cycle.
        // Used by GoalExhaustionDetector to detect active criteria progress and suppress
        // Rules A and D when the count is increasing. Updated by EvaluationAgent after
        // each cumulativeCriteriaStatus merge. Reset to 0 when currentSubGoalId changes.
        int lastCriteriaPassCount,

        // T3-BK: per-criterion consecutive-fail cycle count for the current subgoal.
        // index → number of consecutive cycles the criterion has been assessed ✗.
        // Reset to 0 when a criterion passes; incremented on each ✗ assessment.
        // Persisted to GoalEntity.metadata["criteriaFailStreak"]; restored on restart.
        // Null means no criteria have been assessed yet for this subgoal.
        Map<Integer, Integer> criteriaConsecutiveFailCount,

        // T3-BH: parentId of the current subgoal, used to locate siblings for plan context.
        // Null when the current subgoal is a direct child of the root goal (no sibling context needed).
        String currentSubGoalParentId,

        // T3-BH: plan rationale stored by the planner in the parent goal's metadata["planRationale"].
        // 1-2 sentences describing what the sibling subgoal batch collectively achieves.
        // Null when no rationale was emitted (single-subgoal plan or older data).
        String planRationale,

        // T3-BR: number of times the evaluator has re-run on a repeated evolution fingerprint
        // (without the fingerprint changing) for the current subgoal. Incremented by EvaluationAgent
        // on each same-fingerprint run; reset to 0 by ContextLineageTracker when fingerprint or
        // subgoal changes. When the cap is reached, ExecutionEligibilityExplainer blocks further
        // evaluation and EvaluationAgent emits REPLAN_REQUESTED.
        int evaluatorRepeatRetries,

        // T4-AN-1: outcome of the post-merge compile gate. Set by MergeAgent when the build
        // executor is enabled (autobuilder.build.enabled=true). Carried forward across cycles
        // by ContextLineageTracker — represents the latest known compilation state of the
        // target codebase, not a cycle-scoped fact. Surfaced in the evaluation prompt as a
        // --- BUILD RESULT --- block.
        BuildOutcome buildOutcome,

        // T4-BD: outcome of the most recent compile probe (set by EvaluationAgent / MergeAgent
        // when CompileProbeService runs). Carried forward by ContextLineageTracker. Replaces the
        // "Compile probe failed:\n<stderr>" magic-string prefix that EvaluationAgent previously
        // baked into EvaluationOutcome.feedbackMessage. Read by EvolutionProtocolContract's
        // missing-symbol extractors and by BuildResultPromptBlock when buildOutcome is absent
        // or clean (the probe ran but the post-merge build hasn't, e.g. the evaluator rejected).
        BuildOutcome lastProbeOutcome,

        // T1-DQ: number of consecutive cycles in which a build-config mutation was accepted under
        // COMPILE_ERROR context. Incremented by MergeAgent after each such merge; reset to 0 when
        // COMPILE_OK is observed or when the subgoal changes. T5-BL 2026-09-24: its readers (the
        // build-fix escalator and acceptance heuristic) are deleted, unreachable since the
        // pre-merge probe; the field stays only because checkpoints serialise this record.
        int consecutiveBuildFixAttempts,

        // T1-DW: pre-merge snapshot for the current cycle. Set by MergeAgent immediately before
        // writing merged files; used to restore the previous state when the post-merge build gate
        // returns COMPILE_ERROR. Null between cycles (not carried forward by ContextLineageTracker).
        MergeRollbackPoint lastRollbackPoint,

        // T1-DX: set true by MergeAgent on the rollback path so CycleExecutor's downstream
        // closure helpers (applyEvaluationProgressIfPresent, applyCompletion) skip — without
        // this guard, mutationState=ACCEPTED + score≥threshold promotes the subgoal to
        // FULFILLED on phantom progress because rollback only undoes the filesystem.
        boolean rolledBackThisCycle,

        // T1-DX: forward signal carried exactly one cycle after rollback. Populated by MergeAgent;
        // cleared by ContextLineageTracker after the next cycle's evolution runs. Rendered as a
        // --- LAST CYCLE ROLLED BACK --- block in the next cycle's evolution and planner prompts
        // so the LLM has a structural reason to vary its approach.
        RolledBackReport lastRollback,

        // T2-DD: consecutive same-failure streak keyed on the failure's identity. Originally
        // (errorKind, primaryRolledBackPath) from rollback observations only — but since T1-EC
        // moved failure pre-merge, probe-rejected candidates are the channel that actually
        // carries failures (54 probe rejections / 0 rollbacks in run 2026-06-13_01-34-29), so
        // consecutive same-shape probe rejections (errorKind + missing token, or normalised
        // shape) count too. Same key as the previous cycle increments; a passing probe, a
        // merge, or a subgoal change resets; cycles with no build observation carry the streak
        // unchanged — the streak counts failed attempts, not wall-clock cycles. Reaching ≥2
        // (the first repeat) fires BUILD_FIX_LOCUS_STUCK so the planner is forced off the
        // same approach.
        String sameFailureStreakKey,
        int sameFailureStreak,

        // T2-ES: consecutive cycles in which evolution reported #NoDiff for THIS subgoal and the
        // promotion was refused because a criterion is unmet. The disagreement the ticket is named
        // for, counted — nothing counted it before, so the only response available was to ask
        // again. Reset on subgoal change and on a promotion that is allowed to proceed.
        int noDiffRefusalStreak,

        // T4-BA: per-cycle flag — true on the cycle whose MergeAgent guard rejected a
        // package-fragmenting mutation. Mirrors rolledBackThisCycle: never carried forward;
        // ContextLineageTracker uses it to gate the single-cycle TTL on packageFragmentation.
        boolean packageFragmentationThisCycle,

        // T4-BA: forward signal carried exactly one cycle after the fragmentation guard fires.
        // Rendered as a --- PACKAGE FRAGMENTATION REJECTED --- block in the next cycle's
        // evolution prompt so the LLM consolidates the type instead of re-proposing the split.
        PackageFragmentationReport packageFragmentation,

        // Deterministic compile-fix records applied by CompileFixInjector during this cycle's
        // probe — provenance for "this file change came from the injector, not the LLM". Rendered
        // as --- INJECTOR FIXES THIS CYCLE --- in evaluator/planner prompts; persisted in
        // mutations/<cycleId>/meta.json; mirrored to the behaviour log and the graph.
        List<InjectorFixRecord> injectorFixes,

        // T2-DL: abandoned-goal clusters textually related to the current subgoal/root goal.
        // Populated by CycleContextCoordinator each cycle from AbandonedGoalQueryService. Surfaced
        // in analysis + planner prompts as --- RELATED ABANDONED WORK --- so agents see prior
        // attempts (live islands, merge-bearing descendants, orphaned merged files) before
        // proposing new subgoals.
        List<AbandonedGoalCluster> relatedAbandonedGoals,

        // T4-DA: how far the plan AS A WHOLE still resembles the specification — which of the
        // seed's clauses no work item articulates, and which are articulated but would be proven
        // by no acceptance criterion. Recomputed each cycle by CycleContextCoordinator from the
        // root description and every goal in the tree; deterministic, no model call. Surfaced as
        // --- SPECIFICATION COVERAGE --- in the planner, evolution and analysis prompts. Null
        // when the comparison could not be grounded, which is not the same as finding nothing.
        SeedCoverageReadout seedCoverage,

        // T1-EI: skipped-proposal → matching GIVEN_UP goal id, populated by the planner when a
        // dedup-block resolves against an abandoned-goal match. Surfaced in the planner prompt's
        // PROPOSAL FEEDBACK block so the LLM can choose #SubGoalReactivate or remove+replace
        // instead of re-proposing verbatim.
        Map<String, String> skippedProposalMatches,

        // T2-DT: structured author-error diagnostics from the most recent rolled-back compile
        // probe, paired with the originating subgoal id. Populated by EvaluationAgent after
        // BlockerExtractor classification; consumed by CompileDiagnosticPromptBlock in the next
        // cycle's evolution prompt for the same subgoal. Cleared by ContextLineageTracker on
        // merge or subgoal change so stale diagnostics never contaminate a fresh attempt.
        CompileAuthorErrors lastAuthorErrors,

        // T4-AN-5: build-tool diagnostic text captured on the last probe failure (e.g. the output
        // of `mvn dependency:tree`), surfaced in the next cycle's evolution/planner prompt. Carried
        // one cycle and cleared on merge, like lastAuthorErrors. @With generates withBuildDiagnostic.
        String buildDiagnostic,

        // T4-BZ-2: per-cycle scope-guard result — the carrier behind DerivedFacts.scopeOutOfScope/
        // subgoalScopeMismatch (formerly the stored SCOPE_OUT_OF_SCOPE / SUBGOAL_SCOPE_MISMATCH
        // signals). Written by DefaultSubgoalScopeGuard each cycle; null = in scope or not
        // assessed; never carried forward.
        ScopeGuardResult scopeGuardResult,

        // T4-BZ-2: reason recorded by a halt-deciding mechanism (convergence stall, runaway
        // guards, empty-backlog limit, build-blocked deadlock, root COMPLETED/GIVEN_UP at cycle
        // start). The carrier behind DerivedFacts.goalExhausted() = this != null (formerly the
        // stored GOAL_EXHAUSTED signal). Carried forward by ContextLineageTracker — a recorded
        // halt reason is sticky; the REST resume paths clear it where they cleared the signal.
        String goalExhaustedReason,

        // T4-BZ-2: per-cycle flag set by EvolutionAgent.updateFingerprint when this cycle's
        // evolution produced a different fingerprint than the previous attempt (formerly the
        // stored EVOLUTION_FINGERPRINT_CHANGED signal, whose multi-cycle decay lingering was
        // accidental). Never carried forward.
        boolean evolutionFingerprintChangedThisCycle,

        // T4-BZ-2: evolution wrote files that no merge has landed yet — the carrier behind
        // DerivedFacts.evolutionOccurred (formerly the stored EVOLUTION_OCCURRED signal). Set by
        // EvolutionAgent when a mutation writes files; carried forward by ContextLineageTracker
        // and cleared on merge or subgoal change (the same edge that resets the evolution
        // fingerprints). NOT set by no-change/#NoDiff attempts — the fingerprint alone is not
        // this fact (fingerprints are computed for no-op outcomes too, for repetition tracking).
        boolean unmergedEvolutionWork,

        // T4-BZ-3: the typed request channel — carrier for the request-family types (formerly
        // stored+decayed signals). One open request per kind; re-issue raises urgency;
        // consume-on-read by the addressed mechanism; expiry horizons replace decay for the
        // never-consumed case. Rides the context so lineage, subgoal scoping and fixtures see
        // it like everything else.
        RequestChannel requests,

        // T4-BZ-4: per-role participation windows — carrier for the ROLE_* identity family
        // (formerly stored+decayed signals). Grants come from the same mechanisms that emitted
        // role signals; the selection pass reads window boost instead of strength arithmetic;
        // lifetime is a stated validity horizon instead of decay.
        ParticipationWindows windows,

        // T4-BZ-5: the pause toggle — carrier behind DerivedFacts.systemPaused (formerly the
        // stored SYSTEM_PAUSED signal). Sticky like goalExhaustedReason (carried by lineage);
        // written with the halting mechanism's reason; REST resume paths clear it.
        String pausedReason,

        // T4-BZ-5: operator switches (formerly the emitter-less STEERING_SUPPRESS /
        // RESTAGE_SUPPRESS signals, settable only via REST). Plain booleans.
        boolean steeringSuppressed,
        boolean restageSuppressed

) {
    public CycleContextData {
        if (lastFitnessScores == null) {
            lastFitnessScores = new LinkedHashMap<>();
        }
        if (injectorFixes == null) {
            injectorFixes = List.of();
        }
        if (requests == null) {
            requests = RequestChannel.empty();
        }
        if (windows == null) {
            windows = ParticipationWindows.empty();
        }
    }

    // ------------------------------------------------------------------
    // T4-BZ-4: unified window reads/writes. Migration bridge until T4-BZ-5: a stored ROLE_*
    // signal is still honored (recorded fixtures / REST pause-and-poke). Writes MIGRATE the
    // stored twin instead of merely clearing it — grant/shrink first absorb the twin's
    // accumulated strength/age/decay into a window, then apply the operation; dropping the
    // twin outright would collapse a recorded 3.0 role strength to the fresh grant's boost.
    // ------------------------------------------------------------------

    public boolean hasWindow(String role) {
        return windows.hasActive(role);
    }

    public boolean hasWindowAtLeast(String role, double minBoost) {
        return windows.hasActive(role) && windows.boost(role) >= minBoost;
    }

    public double windowBoost(String role) {
        return windows.boost(role);
    }

    public CycleContextData grantWindow(ParticipationWindow window) {
        if (window == null || window.role() == null) return this;
        return withWindows(windows.grant(window));
    }

    public CycleContextData shrinkWindow(String role) {
        if (role == null) return this;
        return withWindows(windows.shrink(role));
    }

    public CycleContextData revokeWindow(String role) {
        if (role == null) return this;
        return withWindows(windows.revoke(role));
    }

    // ------------------------------------------------------------------
    // T4-BZ-3/5: unified request reads/writes on the sole carrier (the migration bridge and
    // the stored twin left with the store).
    // ------------------------------------------------------------------

    public boolean hasOpenRequest(SignalType kind) {
        return requests.hasOpen(kind);
    }

    public boolean hasStrongRequest(SignalType kind, double minUrgency) {
        return requests.hasOpen(kind) && requests.urgency(kind) >= minUrgency;
    }

    public double requestUrgency(SignalType kind) {
        return requests.urgency(kind);
    }

    public ChannelRequest peekRequest(SignalType kind) {
        return requests.peek(kind);
    }

    public CycleContextData issueRequest(ChannelRequest request) {
        if (request == null || request.kind() == null) return this;
        return withRequests(requests.issue(request));
    }

    public CycleContextData consumeRequest(SignalType kind) {
        return withRequests(requests.consume(kind));
    }

    /**
     * T2-AO/I11 verified consumption: the request leaves the channel only when the addressed
     * mechanism's action verifiably took effect; otherwise it stays open (visible), instead
     * of being optimistically cleared.
     */
    public CycleContextData consumeRequestIfVerified(SignalType kind, boolean verified) {
        return verified ? consumeRequest(kind) : this;
    }

}
