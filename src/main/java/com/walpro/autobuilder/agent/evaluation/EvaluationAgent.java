package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringService;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.heuristic.EvaluationHeuristic;
import com.walpro.autobuilder.analysis.heuristic.HeuristicNegotiator;
import com.walpro.autobuilder.analysis.heuristic.HeuristicOpinion;
import com.walpro.autobuilder.analysis.heuristic.NegotiationResult;
import com.walpro.autobuilder.analysis.problem.ProblemDetector;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.CompileProbeService;
import com.walpro.autobuilder.core.build.inject.FixProposal;
import com.walpro.autobuilder.core.build.inject.InjectorFixRecord;
import com.walpro.autobuilder.core.engine.ExecutionAgent;
import com.walpro.autobuilder.core.merge.MergeRollbackPoint;
import com.walpro.autobuilder.core.merge.MergeRollbackService;
import com.walpro.autobuilder.core.mutation.BlockerExtractor;
import com.walpro.autobuilder.core.mutation.DeferredMutationLedger;
import com.walpro.autobuilder.core.mutation.MutationBlocker;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.core.persistence.MutationSnapshotService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.graph.evaluation.EvaluationActionRegistrar;
import com.walpro.autobuilder.graph.evaluation.EvaluationGraphFacade;
import com.walpro.autobuilder.graph.inject.CompileFixInjectionRegistrar;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class EvaluationAgent implements ExecutionAgent {

    private final EvaluationLLMAdapter evaluationAdapter;
    private final ScoringService scoringService;
    private final EvaluationInterpreter evaluationInterpreter;
    private final ProblemDetector problemDetector;

    private final HeuristicNegotiator heuristicNegotiator;
    private final List<EvaluationHeuristic> heuristics;

    private final EvaluationGraphFacade graphFacade;
    private final EvaluationActionRegistrar evaluationActionRegistrar;
    private final GoalPlannerService goalPlannerService;
    private final MutationSnapshotService mutationSnapshotService;

    /** T1-EC: compile the tentative merge before the LLM call; rollback on LLM rejection. */
    private final CompileProbeService compileProbeService;
    private final MergeRollbackService mergeRollbackService;
    private final FileService fileService;

    /** T5-H: optional behaviour trace logger. */
    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    /** Optional registrar for CompileFixInjector provenance — wired in production, null in unit tests. */
    @Setter
    @Autowired(required = false)
    private CompileFixInjectionRegistrar compileFixInjectionRegistrar;

    /** T4-BE: optional deferred-mutation ledger — wired in production, null in unit tests. */
    @Setter
    @Autowired(required = false)
    private DeferredMutationLedger deferredMutationLedger;

    private static final String AGENT_ID = "StaticEvaluationAgent";

    @Override
    public CycleContextData execute(CycleContextData context) {
        ProjectDiffResult diff = context.projectDiff();
        if (diff == null) {
            log.warn("EvaluationExecutionAgent projectDiff is null.");
            return context;
        }

        Map<String, String> changedFiles = diff.changedFiles();
        Set<String> deletedPaths = diff.deletedPaths();
        Map<String, String> oldFiles = context.previousOutput();

        String scoringGoalId = context.currentSubGoalId() != null
                ? context.currentSubGoalId()
                : context.currentGoalId();

        var scoring = scoringService.computeScores(
                context.cycleName(),
                scoringGoalId,
                oldFiles,
                changedFiles,
                deletedPaths
        );

        log.info("Scoring computed for cycle={} globalScore={} lastAcceptedGoalScore={} lastGlobalScore={} perFileCount={}",
                context.cycleName(), scoring.globalScore(), scoring.lastAcceptedGoalScore(), scoring.lastGlobalScore(), scoring.perFileScores().size());

        Double comparisonBaseline =
                scoring.lastAcceptedGoalScore() != null
                        ? scoring.lastAcceptedGoalScore()
                        : scoring.lastGlobalScore();

        context = context
                .withScoringResult(scoring)
                .withLastScore(comparisonBaseline)
                .withScoreHistoryByFile(scoring.scoreHistoryByFile())
                .withRecentDeltas(scoring.scoreDeltas())
                .withFindings(scoring.findings())
                .withScoringComputed(true);

        // T3-BC / T5-F: log how many criteria are reaching the evaluator this cycle
        int criteriaCount = context.currentSubGoalCriteria() != null ? context.currentSubGoalCriteria().size() : 0;
        if (criteriaCount > 0) {
            log.info("Criteria delivered to evaluator: {}", criteriaCount);
        } else if (context.currentSubGoalId() != null) {
            log.warn("Criteria delivered to evaluator: 0 (subgoal active — hydration may have failed)");
        }

        // 1) LLM outcome — T1-AV: skip LLM when fingerprint is unchanged
        String currentFingerprint = context.lastEvolutionFingerprint();
        String lastEvaluatedFingerprint = context.lastEvaluatedEvolutionFingerprint();
        boolean fingerprintUnchanged = currentFingerprint != null
                && currentFingerprint.equals(lastEvaluatedFingerprint)
                && !context.hasSignal(BasicSignals.EVOLUTION_FINGERPRINT_CHANGED);

        // T3-BR: when fingerprint is unchanged but defined criteria are not yet all banked,
        // force a fresh LLM call rather than reusing the prior outcome — the prior evaluation
        // may have been partial, or new criteria may have been injected via T3-BE. Track retries
        // per same-fingerprint streak and emit REPLAN_REQUESTED at the cap so the planner can
        // intervene if the evaluator is genuinely unable to make progress.
        boolean criteriaIncomplete = fingerprintUnchanged
                && context.currentSubGoalId() != null
                && goalPlannerService.hasAcceptanceCriteria(context.currentSubGoalId())
                && !goalPlannerService.allCriteriaDefinedAndBanked(context.currentSubGoalId());

        if (criteriaIncomplete) {
            int retries = context.evaluatorRepeatRetries() + 1;
            context = context.withEvaluatorRepeatRetries(retries);
            if (retries >= com.walpro.autobuilder.core.engine.ExecutionEligibilityExplainer.EVALUATOR_REPEAT_RETRY_CAP) {
                // T1-DN: BUILD_FIX carve-out — replanning injects new subgoals that also can't be
                // evaluated positively while the build is red, causing goal explosion. Fall through
                // to stagnation-only instead.
                GoalEntity currentGoal = goalPlannerService.getGoalById(context.currentSubGoalId());
                boolean isBuildFix = currentGoal != null
                        && currentGoal.getStatus() == GoalStatus.ACTIVE
                        && currentGoal.getMetadata() != null
                        && BlockerTypes.BUILD_FIX.equals(currentGoal.getMetadata().get(BlockerTypes.METADATA_KEY));
                if (isBuildFix) {
                    log.warn("EvaluationAgent T3-BR: retry cap reached on BUILD_FIX subgoal {} — skipping REPLAN to avoid goal explosion",
                            context.currentSubGoalId());
                } else {
                    log.warn("EvaluationAgent T3-BR: retry cap ({}) reached on repeated fingerprint with unbanked criteria — emitting REPLAN_REQUESTED", retries);
                    context = context.withSignal(Signal.of(BasicSignals.REPLAN_REQUESTED, 1.0, 0.2, "evaluator-repeat-cap"));
                    // T2-CR observability gate: emit a behaviour event so live-session analysis
                    // can confirm whether this cap path actually fires. T2-CR's demote-and-narrow
                    // refinement is parked until this event appears in real runs.
                    emitCapFireEvent(context, currentGoal, retries);
                }
            } else {
                log.info("EvaluationAgent T3-BR: fingerprint repeated but criteria unbanked — re-evaluating (retry {}/{})",
                        retries, com.walpro.autobuilder.core.engine.ExecutionEligibilityExplainer.EVALUATOR_REPEAT_RETRY_CAP);
            }
            // Force the else-branch below to call the LLM with fresh context.
            fingerprintUnchanged = false;
        }

        // T1-EC: tentative-merge compile probe gates the LLM call. mvn compile is a local
        // process call; the LLM is a remote API call — cheap-before-expensive. On COMPILE_OK
        // the files stay on disk so the eventual MergeAgent commit sees real state. On
        // COMPILE_ERROR the probe restored the working tree; we short-circuit to REJECTED
        // without paying for an LLM call.
        CompileProbeService.ProbeResult probeResult = null;
        if (!fingerprintUnchanged) {
            probeResult = compileProbeService.probe(context);
            // T4-BD: thread the probe outcome onto context so downstream consumers
            // (BuildResultPromptBlock fallback, EvolutionProtocolContract.extractMissingSymbols,
            // MissingMemberPromptBlock) read structured data instead of string-matching a
            // "Compile probe failed:" prefix on EvaluationOutcome.feedbackMessage.
            if (probeResult != null && probeResult.outcome() != null
                    && probeResult.outcome().result() != BuildResult.SKIPPED) {
                context = context.withLastProbeOutcome(probeResult.outcome());
            }
            if (probeResult.outcome().result() == BuildResult.COMPILE_ERROR) {
                log.info("EvaluationAgent T1-EC: compile probe failed — skipping LLM call, returning REJECTED");
            } else if (probeResult.outcome().result() == BuildResult.FRAGMENTATION_REJECTED) {
                // T4-BH: the probe-time package-fragmentation gate refused the write set before
                // any file landed on disk. No LLM call, no merge.
                log.info("EvaluationAgent T4-BH: probe rejected mutation for package fragmentation — skipping LLM call, returning REJECTED");
            } else if (probeResult.filesOnDisk()) {
                // COMPILE_OK with files staged on disk — remember the rollback point so we
                // can restore if the LLM rejects.
                context = context.withLastRollbackPoint(probeResult.rollbackPoint());
            }
            // CompileFixInjector ran inside probe(): fold any applied fixes back into context so
            // the evaluator/planner prompts see the injector-added files in the diff, meta.json
            // records the provenance, and downstream agents know which edits came from the LLM
            // vs. the deterministic resolver chain.
            if (!probeResult.appliedFixes().isEmpty()) {
                context = foldInjectorFixes(context, probeResult);
            }
        }

        EvaluationOutcome rawOutcome;
        if (fingerprintUnchanged && context.lastEvaluationOutcome() != null) {
            log.info("EvaluationAgent: fingerprint unchanged ({}), reusing prior LLM outcome", currentFingerprint);
            rawOutcome = context.lastEvaluationOutcome();
        } else if (fingerprintUnchanged) {
            log.info("EvaluationAgent: fingerprint unchanged ({}) with no prior outcome, short-circuiting to REJECTED", currentFingerprint);
            rawOutcome = new EvaluationOutcome(null, MutationAcceptanceState.REJECTED, GoalProgressState.NOT_EVALUATED,
                    "Fingerprint unchanged; no new evaluation.", null);
        } else if (probeResult != null && probeResult.outcome().result() == BuildResult.COMPILE_ERROR) {
            // T4-BD: feedbackMessage carries only the brief reason — the structured stderr lives
            // on ctx.lastProbeOutcome() and downstream renderers read it from there.
            rawOutcome = new EvaluationOutcome(0.0, MutationAcceptanceState.REJECTED,
                    GoalProgressState.NOT_EVALUATED,
                    "Compile probe failed — see ctx.lastProbeOutcome() stderrTail.", null);
            context = context.withCriteriaAssessments(List.of());
            // T4-BE: when the rejection is fully attributable to extractable blockers, persist
            // the mutation to the deferred-mutation ledger so a later merge that resolves the
            // blockers can re-stage it instead of letting the implementation work die with
            // the rollback. Partial / unexplained stderr → skip; never defer something we
            // can't reason about.
            tryDeferRejectedMutation(context, probeResult, scoring.globalScore());
        } else if (probeResult != null && probeResult.outcome().result() == BuildResult.FRAGMENTATION_REJECTED) {
            // T4-BH: probe-time package-fragmentation rejection — REJECT without LLM evaluation
            // and without deferring to the ledger (the fragmentation is structural; deferring
            // would just re-fire the same rejection on every restage attempt).
            rawOutcome = new EvaluationOutcome(0.0, MutationAcceptanceState.REJECTED,
                    GoalProgressState.NOT_EVALUATED,
                    "Package fragmentation rejected by probe — see ctx.lastProbeOutcome() stderrTail.", null);
            context = context.withCriteriaAssessments(List.of());
        } else {
            ParsedEvaluation parsed = evaluationAdapter.evaluate(context);
            rawOutcome = parsed.outcome();
            // Always update criteriaAssessments so stale ✓ data from a prior accepted cycle
            // is cleared when the LLM omits CriteriaCheck in this cycle (common for rejected mutations).
            context = context.withCriteriaAssessments(parsed.criteriaAssessments());
            if (!parsed.criteriaAssessments().isEmpty()) {
                context = mergeCumulativeCriteriaStatus(context, parsed.criteriaAssessments());
                context = updateCriteriaFailStreak(context, parsed.criteriaAssessments());
                // T1-DY: criteria banking deferred to CycleExecutor.flushPendingCriteriaIfNotRolledBack().
                // T2-BL: update lastCriteriaPassCount so the next cycle's exhaustion detector
                // can compare and suppress Rules A/D when criteria are actively progressing.
                int passCount = context.cumulativeCriteriaStatus() != null
                        ? (int) context.cumulativeCriteriaStatus().values().stream().filter(Boolean::booleanValue).count()
                        : 0;
                context = context.withLastCriteriaPassCount(passCount);
            }
            // T3-BC / T5-F: log CriteriaCheck parse count vs expected
            int assessedCount = parsed.criteriaAssessments().size();
            if (criteriaCount > 0) {
                if (assessedCount == criteriaCount) {
                    log.info("CriteriaCheck lines parsed: {} of {} expected", assessedCount, criteriaCount);
                } else if (assessedCount == 0) {
                    log.warn("CriteriaCheck lines parsed: 0 of {} expected (complete LLM omission)", criteriaCount);
                } else {
                    log.warn("CriteriaCheck lines parsed: {} of {} expected (partial LLM compliance — missing criteria treated as unmet)", assessedCount, criteriaCount);
                }
            }
        }

        // 2) capture "before" for delta checks
        context = context.withPreviousProblemContext(context.problemContext());

        if (behaviourLogger != null) {
            behaviourLogger.mutationScored(
                    context.cycleName(),
                    context.lastEvolutionFingerprint(),
                    rawOutcome.score(),
                    rawOutcome.mutationState() != null ? rawOutcome.mutationState().name() : null,
                    context.criteriaAssessments() != null ? context.criteriaAssessments() : List.of()
            );
        }

        // 3) update ProblemContext BEFORE negotiation
        var updatedProblemContext = problemDetector.detectAndUpdate(context, rawOutcome, scoring.findings());
        context = context.withProblemContext(updatedProblemContext);

        // 4) negotiate outcome using heuristics (now has previous + current ProblemContext)
        NegotiationResult negotiationResult = heuristicNegotiator.negotiate(context, rawOutcome, heuristics);
        EvaluationOutcome finalOutcome = negotiationResult.outcome();

        if (behaviourLogger != null) {
            List<String> overrides = negotiationResult.criticalOpinions() != null
                    ? negotiationResult.criticalOpinions().stream().map(HeuristicOpinion::name).toList()
                    : List.of();
            behaviourLogger.mutationNegotiated(
                    context.cycleName(),
                    context.lastEvolutionFingerprint(),
                    finalOutcome.score(),
                    finalOutcome.mutationState() != null ? finalOutcome.mutationState().name() : null,
                    overrides
            );
            if (finalOutcome.mutationState() == MutationAcceptanceState.REJECTED) {
                behaviourLogger.mutationRejected(
                        context.cycleName(),
                        context.lastEvolutionFingerprint(),
                        finalOutcome.feedbackMessage()
                );
            }
        }

        // IMPORTANT: this is now the canonical "context score"
        context = context.withScore(finalOutcome.score());

        // T2-AT: update per-heuristic CRITICAL streak counters; rebuild persistent warnings
        context = updateCriticalHeuristicStreaks(context, negotiationResult.criticalOpinions());

        // Optional: log heuristic opinions against final outcome
        negotiationResult.allOpinions().forEach(op -> log.info(
                "Heuristic [{}] mutationState: {}, goalProgress: {}, rationale: {}, score={}",
                op.name(), op.mutationState(), op.goalProgress(), op.rationale(), op.suggestedScore()));

        // 5) interpret final outcome
        EvaluationInterpreter.InterpretationResult result =
                evaluationInterpreter.interpret(finalOutcome, comparisonBaseline, false);

        // Emit LOW_CONFIDENCE only for score-based failure; STAGNATING has its own signal below.
        // Graduate strength by distance below the 0.75 threshold so near-misses don't accumulate.
        if (!result.success() && result.goalProgress() != GoalProgressState.STAGNATING) {
            double score = finalOutcome.score() != null ? finalOutcome.score() : 0.0;
            double strength = Math.min(1.0, (0.75 - score) / 0.20); // 1.0 at ≤0.55, ~0.25 at 0.70, ~0.05 at 0.74
            context = context.withSignal(
                    Signal.of(BasicSignals.LOW_CONFIDENCE, Math.max(0.05, strength), 0.15, "evaluation")
            );
        }

        // T1-DC: write mutationState/goalProgress here so same-cycle agents (planner, merge,
        // analysis) in the signal-driven loop see the up-to-date state instead of stale
        // pre-cycle values. This was previously deferred to CycleExecutor.applyEvaluationInterpretationIfPresent
        // which runs after adaptiveExecutionEngine.runCycle() returns — making T1-DA's guard
        // structurally unreachable and starving the signal-driven merge agent of ROLE_MERGE.
        context = context
                .withEvaluationOutcome(finalOutcome)
                .withLastEvaluationOutcome(finalOutcome)
                .withEvaluationInterpretation(result)
                .withMutationState(result.mutationState())
                .withGoalProgress(result.goalProgress())
                .withEvaluationFeedback(finalOutcome.feedbackMessage())
                .withRecommendation(finalOutcome.recommendation())
                .withLastEvaluatedEvolutionFingerprint(context.lastEvolutionFingerprint());

        // T1-DC: emit ROLE_MERGE on ACCEPTED so the merge agent in the signal-driven loop
        // can run inline this cycle, before the planner can disturb the active subgoal pointer.
        // Also clear the probe rollback point: a snapshot only describes the pre-probe state, so
        // it is no longer the right "undo" target once the merge is about to commit. Without this
        // clear, the next REJECTED cycle's rollback path could fire against this stale snapshot
        // and undo files that were ACCEPTED in this cycle.
        if (result.mutationState() == MutationAcceptanceState.ACCEPTED) {
            context = context.withSignal(
                            Signal.of(BasicSignals.ROLE_MERGE, 1.0, 1.0, "evaluation-accepted"))
                    .withLastRollbackPoint(null);
        }

        // T1-EC: the probe wrote files to output/ and they passed compile. If the LLM has now
        // rejected them, roll back to the pre-probe state so the working tree reflects the
        // last-accepted mutation, not the rejected one.
        //
        // Guarded on probeResult.outcome().result() == COMPILE_OK: the probe only stages files
        // (and returns a non-null rollbackPoint) on COMPILE_OK. On COMPILE_ERROR the probe has
        // already rolled back internally and returns null. Firing this branch without the
        // COMPILE_OK gate would either be a no-op or, if context.lastRollbackPoint() leaked from
        // a prior cycle, would undo files ACCEPTED earlier.
        if (result.mutationState() == MutationAcceptanceState.REJECTED
                && probeResult != null
                && probeResult.outcome().result() == BuildResult.COMPILE_OK
                && context.lastRollbackPoint() != null) {
            MergeRollbackPoint rollbackPoint = context.lastRollbackPoint();
            mergeRollbackService.rollback(rollbackPoint, context.outputPath(), fileService);
            log.info("EvaluationAgent T1-EC: LLM rejected after compile-OK probe — rolled back {} staged files",
                    rollbackPoint.originalContents().size());
            context = context.withLastRollbackPoint(null);
        }

        // T1-DI: persist mutationState to meta.json now so FileVariantCollector can find
        // ACCEPTED variants within the same cycle (meta-step path). Without this,
        // CycleExecutor.persistEvaluationResultToMeta runs post-cycle — too late for the
        // fitness-ranked merge pass that follows meta-step execution.
        persistMutationStateToMeta(context);

        if (result.goalProgress() == GoalProgressState.STAGNATING) {
            context = context.withSignal(
                    Signal.of(BasicSignals.STAGNATION, 1.0, 0.2, "evaluation")
            );
        }

        // 6) persist/graph after final outcome
        graphFacade.recordEvaluation(context, finalOutcome);
        evaluationActionRegistrar.registerEvaluation(AGENT_ID, context, negotiationResult);

        return context;
    }

    private void persistMutationStateToMeta(CycleContextData ctx) {
        mutationSnapshotService.persistMutationState(ctx, behaviourLogger);
    }

    /**
     * T4-BE / T4-BF: extract a structured {@link BlockerExtractor.Outcome} from the probe
     * stderr and persist the rejected mutation to the deferred-mutation ledger when the
     * result is {@code DEFERRED}. Every call emits one {@code blocker_extraction_outcome}
     * behaviour event regardless of the outcome — that's the canonical post-hoc surface
     * for "why didn't this rejection make it into the ledger?". Best-effort: failure to
     * defer must never block the rejection itself.
     */
    // T4-BF: package-private for direct unit-testing of the four Outcome.Result paths.
    void tryDeferRejectedMutation(CycleContextData context,
                                  CompileProbeService.ProbeResult probeResult,
                                  Double heuristicScore) {
        if (probeResult == null || probeResult.outcome() == null) return;
        String stderr = probeResult.outcome().stderrTail();
        if (stderr == null || stderr.isBlank()) return;

        BlockerExtractor.Outcome outcome = BlockerExtractor.extract(stderr);
        emitExtractionOutcomeEvent(context, outcome);

        if (outcome.result() != BlockerExtractor.Outcome.Result.DEFERRED) return;
        if (deferredMutationLedger == null) return;
        if (context.evolutionOutcome() == null
                || context.evolutionOutcome().addedOrChangedFiles() == null
                || context.evolutionOutcome().addedOrChangedFiles().isEmpty()) return;

        try {
            deferredMutationLedger.defer(
                    context,
                    context.evolutionOutcome().addedOrChangedFiles(),
                    outcome.blockers(),
                    null,                  // LLM score — no LLM call on compile-probe rejection
                    heuristicScore
            );
        } catch (Exception e) {
            log.warn("T4-BE: deferral failed for cycle={}: {}", context.cycleName(), e.getMessage());
        }
    }

    private void emitExtractionOutcomeEvent(CycleContextData context, BlockerExtractor.Outcome outcome) {
        if (behaviourLogger == null) return;
        try {
            behaviourLogger.blockerExtractionOutcome(
                    context.cycleName(),
                    context.lastAttemptId(),
                    context.currentSubGoalId(),
                    outcome.result().name(),
                    outcome.deferrableCount(),
                    outcome.authorErrorCount(),
                    outcome.unknownCount(),
                    outcome.errorShapes(),
                    outcome.firstUnknownLine()
            );
        } catch (Exception e) {
            log.warn("T4-BF: blocker_extraction_outcome emit failed for cycle={}: {}",
                    context.cycleName(), e.getMessage());
        }
    }

    /**
     * T2-CR observability gate: records that T3-BR's evaluator-repeat-cap path fired. The
     * payload counts banked vs unbanked criteria — these are the exact inputs T2-CR's
     * demote-and-narrow refinement would consume, so the event is also a feasibility check for
     * the eventual fix.
     */
    private void emitCapFireEvent(CycleContextData ctx, GoalEntity currentGoal, int retries) {
        if (behaviourLogger == null) return;
        int banked = 0;
        int unbanked = 0;
        Object raw = currentGoal != null && currentGoal.getMetadata() != null
                ? currentGoal.getMetadata().get("acceptanceCriteria") : null;
        if (raw instanceof java.util.List<?> criteria && !criteria.isEmpty()) {
            java.util.Map<Integer, Boolean> passStatus = goalPlannerService.getCriteriaPassStatus(currentGoal);
            for (int i = 1; i <= criteria.size(); i++) {
                Boolean passing = passStatus != null ? passStatus.get(i) : null;
                if (Boolean.TRUE.equals(passing)) banked++; else unbanked++;
            }
        }
        behaviourLogger.evaluatorRepeatCapFired(
                ctx.cycleName(),
                ctx.currentSubGoalId(),
                retries,
                com.walpro.autobuilder.core.engine.ExecutionEligibilityExplainer.EVALUATOR_REPEAT_RETRY_CAP,
                ctx.lastEvolutionFingerprint(),
                banked,
                unbanked
        );
    }

    /**
     * T1-BG: Merges this cycle's criterion assessments into the cumulative pass/fail map.
     * A criterion that has ever passed (true) is never overwritten by a ✗ result.
     * Omitted indices (LLM partial compliance) are left at their prior value.
     * Lines are expected in the form "[N] ✓ ..." or "[N] ✗ ...".
     */
    private static CycleContextData mergeCumulativeCriteriaStatus(CycleContextData context,
                                                                  List<String> assessments) {
        Map<Integer, Boolean> cumulative = context.cumulativeCriteriaStatus() != null
                ? new HashMap<>(context.cumulativeCriteriaStatus())
                : new HashMap<>();

        java.util.regex.Pattern linePattern = java.util.regex.Pattern.compile("^\\[(\\d+)]\\s*(\u2713|\u2717)");
        for (String line : assessments) {
            java.util.regex.Matcher m = linePattern.matcher(line.trim());
            if (!m.find()) continue;
            int index = Integer.parseInt(m.group(1));
            boolean passed = "\u2713".equals(m.group(2));
            if (passed) {
                cumulative.put(index, true);
            } else {
                // Only record false if not already passed
                cumulative.putIfAbsent(index, false);
            }
        }

        return context.withCumulativeCriteriaStatus(cumulative);
    }

    /**
     * T3-BK: Updates per-criterion consecutive-fail streak counts.
     * A ✓ assessment resets the criterion's streak to 0; a ✗ assessment increments it.
     * Omitted criteria (LLM partial compliance) retain their previous streak value.
     */
    private static CycleContextData updateCriteriaFailStreak(CycleContextData context, List<String> assessments) {
        Map<Integer, Integer> failStreak = context.criteriaConsecutiveFailCount() != null
                ? new HashMap<>(context.criteriaConsecutiveFailCount())
                : new HashMap<>();

        java.util.regex.Pattern linePattern = java.util.regex.Pattern.compile("^\\[(\\d+)]\\s*(\u2713|\u2717)");
        for (String line : assessments) {
            java.util.regex.Matcher m = linePattern.matcher(line.trim());
            if (!m.find()) continue;
            int index = Integer.parseInt(m.group(1));
            boolean passed = "\u2713".equals(m.group(2));
            if (passed) {
                failStreak.put(index, 0);
            } else {
                failStreak.merge(index, 1, Integer::sum);
            }
        }

        return context.withCriteriaConsecutiveFailCount(failStreak.isEmpty() ? null : failStreak);
    }

    /**
     * T2-AT: Increments the consecutive-CRITICAL streak for each heuristic that fired at CRITICAL
     * confidence this cycle. Heuristics not in {@code criticalOpinions} are implicitly reset
     * (removed from the map). When a streak reaches ≥ 2, a human-readable warning is added to
     * {@code persistentHeuristicWarnings} so the evolution prompt can surface it as a directive.
     */
    private static CycleContextData updateCriticalHeuristicStreaks(CycleContextData context,
                                                                   List<HeuristicOpinion> criticalOpinions) {
        Map<String, Integer> prevStreaks = context.criticalHeuristicStreaks();
        Map<String, Integer> newStreaks = new HashMap<>();

        for (HeuristicOpinion op : criticalOpinions) {
            int prev = prevStreaks != null ? prevStreaks.getOrDefault(op.name(), 0) : 0;
            newStreaks.put(op.name(), prev + 1);
        }

        // Build warnings for heuristics with streak >= 2 (consistently repeating CRITICAL)
        List<String> warnings = new ArrayList<>();
        for (HeuristicOpinion op : criticalOpinions) {
            int streak = newStreaks.get(op.name());
            if (streak >= 2) {
                warnings.add("[%s] (critical for %d consecutive cycles): %s"
                        .formatted(op.name(), streak, op.rationale()));
            }
        }

        if (!warnings.isEmpty()) {
            log.info("persistent heuristic warnings ({}):\n{}",
                    warnings.size(),
                    String.join("\n", warnings));
        }

        return context
                .withCriticalHeuristicStreaks(newStreaks)
                .withPersistentHeuristicWarnings(warnings);
    }

    @Override
    public String name() {
        return "evaluation";
    }

    @Override
    public String role() {
        return "evaluation";
    }

    @Override
    public Set<SignalType> getSupportedSignals() {
        return BasicSignals.EVALUATION_SIGNALS;
    }

    /**
     * Merges deterministic compile fixes produced by {@link com.walpro.autobuilder.core.build.inject.CompileFixInjector}
     * back into the cycle context: appends the injector's file mutations to {@code evolutionOutcome}
     * (so diff/eval prompts see them) and records the {@link InjectorFixRecord}s on context (so
     * meta.json, the behaviour log, the graph layer, and the INJECTOR FIXES prompt section all see
     * the same provenance). Injector mutations override LLM mutations on the same path because the
     * injector wrote the file last on disk.
     */
    private CycleContextData foldInjectorFixes(CycleContextData context, CompileProbeService.ProbeResult probeResult) {
        if (probeResult == null || probeResult.appliedFixes().isEmpty()) return context;
        List<FixProposal> appliedFixes = probeResult.appliedFixes();

        List<FileMutation> existing = context.evolutionOutcome() != null
                ? context.evolutionOutcome().addedOrChangedFiles()
                : List.of();
        Map<String, FileMutation> byPath = new java.util.LinkedHashMap<>();
        if (existing != null) {
            for (FileMutation m : existing) {
                if (m == null || m.path() == null) continue;
                byPath.put(m.path(), m);
            }
        }
        List<InjectorFixRecord> records = new ArrayList<>(context.injectorFixes() != null
                ? context.injectorFixes() : List.of());
        // Read the re-probe outcome from probeResult directly. context.buildOutcome() is the
        // post-merge build result, which isn't set until much later in the cycle — using it here
        // produced "UNKNOWN" in the mutation_injected behaviour event.
        String reProbeResult = probeResult.outcome() != null && probeResult.outcome().result() != null
                ? probeResult.outcome().result().name()
                : "UNKNOWN";
        for (FixProposal fp : appliedFixes) {
            for (FileMutation m : fp.mutations()) {
                if (m == null || m.path() == null) continue;
                byPath.put(m.path(), m);
            }
            InjectorFixRecord rec = fp.record();
            records.add(rec);
            if (behaviourLogger != null) {
                behaviourLogger.mutationInjected(context.cycleName(), rec.rule(), rec.kind().name(),
                        rec.missingToken(), rec.touchedPaths(), reProbeResult);
            }
            if (compileFixInjectionRegistrar != null) {
                compileFixInjectionRegistrar.registerInjection(AGENT_ID, context, rec);
            }
        }

        EvolutionOutcome eo = context.evolutionOutcome();
        if (eo != null) {
            context = context.withEvolutionOutcome(eo.withAddedOrChangedFiles(List.copyOf(byPath.values())));
        }
        return context.withInjectorFixes(List.copyOf(records));
    }
}