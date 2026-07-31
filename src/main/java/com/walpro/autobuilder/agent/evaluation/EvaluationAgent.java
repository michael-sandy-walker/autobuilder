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
import com.walpro.autobuilder.core.build.PackageFragmentationCheck;
import com.walpro.autobuilder.core.build.inject.FixProposal;
import com.walpro.autobuilder.core.build.inject.InjectorFixRecord;
import com.walpro.autobuilder.core.build.inject.WellKnownPackageRegistry;
import com.walpro.autobuilder.core.engine.ExecutionAgent;
import com.walpro.autobuilder.core.merge.MergeRollbackPoint;
import com.walpro.autobuilder.core.merge.MergeRollbackService;
import com.walpro.autobuilder.core.merge.PackageFragmentationReport;
import com.walpro.autobuilder.core.mutation.BlockerExtractor;
import com.walpro.autobuilder.core.mutation.DeferredMutationLedger;
import com.walpro.autobuilder.core.mutation.MutationBlocker;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.ChannelRequest;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.DerivedFacts;
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

    /** T2-EY: run-level record of standing rejections — wired in production, null in unit tests. */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.mutation.RejectedProposalLedger rejectedProposalLedger;

    /** T1-ET: optional registry — when present, BlockerExtractor upgrades MISSING_PACKAGE
     *  blockers to PACKAGE_REWRITE for registry-known deprecated prefixes. Null in unit tests. */
    @Setter
    @Autowired(required = false)
    private WellKnownPackageRegistry wellKnownPackageRegistry;

    /** T4-AN-5: optional build-tool diagnostic probe — wired in production, null in unit tests. */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.build.BuildToolProbe buildToolProbe;

    /** T4-CC: completion-edge gates re-run against the staged candidate on gate-born blocker
     *  subgoals — the deterministic result takes precedence over the LLM assessment. Null in
     *  unit tests. */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.build.PackageGate packageGate;
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.build.BootGate bootGate;
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.build.TestRunGate testRunGate;

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

        // T5-J: skip the evaluation meta-step when there is nothing to evaluate — no changed or
        // deleted files this cycle (evolution returned #NoDiff, or the engine selected evaluation
        // on a no-mutation cycle). Running the LLM here rubber-stamps already-settled code and
        // emits a phantom mutation_scored{mutationId:null}, one wasted evaluator call per empty
        // cycle. The T3-BR criteria-banking re-run (fingerprint repeats while defined criteria are
        // not all banked) is preserved: it legitimately re-evaluates existing code to bank the
        // remaining criteria, so it is exempt from the skip.
        boolean nothingProduced = (changedFiles == null || changedFiles.isEmpty())
                && (deletedPaths == null || deletedPaths.isEmpty());
        if (nothingProduced && !criteriaBankingWarranted(context)) {
            log.info("EvaluationAgent T5-J: no mutation to evaluate this cycle (empty diff, criteria settled) — skipping the meta-step");
            // T5-J follow-up (2026-07-16 evening run): settle the candidate as evaluated. The
            // full path below records lastEvaluatedEvolutionFingerprint; when the skip left it
            // behind lastEvolutionFingerprint, the deliberation rule "candidate pending
            // evaluation" pinned mode=EVALUATE with evaluation-only windows every cycle —
            // REPLAN/DIVERGENCE requests starved ~55 cycles until the T4-I brake (the pre-T5-J
            // phantom evaluation had settled the fingerprint as an accidental side effect).
            // An empty diff IS the evaluation: the candidate is indistinguishable from the
            // settled output, so there is nothing left pending.
            return context.withLastEvaluatedEvolutionFingerprint(context.lastEvolutionFingerprint());
        }

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
                && !DerivedFacts.evolutionFingerprintChanged(context);

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
                    context = context.issueRequest(ChannelRequest.of(BasicSignals.REPLAN_REQUESTED, 1.0, "evaluator-repeat-cap", ChannelRequest.horizonFromDecay(1.0, 0.2)));
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

        // T2-EY: a proposal this run already rejected, with nothing merged since, cannot have
        // become right — nothing else changes the tree it is judged against. Refuse it here,
        // before the compile probe and before the evaluator, and hand back the reason it was
        // given the first time. This is the same cheap-before-expensive ordering as the probe
        // below, one step earlier: a map lookup ahead of a local build ahead of a remote call.
        //
        // Why this exists at all: T4-BX already relays the rejection reason into the next
        // evolution prompt and the words demonstrably arrive. Nothing made them a constraint on
        // what the next proposal may CONTAIN, so the generator was free to re-emit the same bytes,
        // and did — in 15 of the 33 measured run corpora.
        RepeatRefusal repeatRefusal = standingRejectionFor(context, currentFingerprint, fingerprintUnchanged);
        if (repeatRefusal != null) {
            return applyRepeatRefusal(context, currentFingerprint, repeatRefusal);
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
            // (BuildResultPromptBlock fallback, CompileDiagnosticPromptBlock,
            // MissingMemberPromptBlock) read structured data instead of string-matching a
            // "Compile probe failed:" prefix on EvaluationOutcome.feedbackMessage.
            if (probeResult != null && probeResult.outcome() != null
                    && probeResult.outcome().result() != BuildResult.SKIPPED) {
                context = context.withLastProbeOutcome(probeResult.outcome());
            }
            if (probeResult.outcome().result() == BuildResult.COMPILE_ERROR && !probeResult.filesOnDisk()) {
                log.info("EvaluationAgent T1-EC: compile probe failed — skipping LLM call, returning REJECTED");
            } else if (probeResult.outcome().result() == BuildResult.COMPILE_ERROR) {
                // T1-FI: the probe STEERED — a test-only compile failure on a mixed mutation keeps
                // the files on disk by design (T4-BN-2/T4-BN-3 scoped probe) so the main work is
                // not sacrificed over a test break. That is not a gate rejection: treat it like
                // COMPILE_OK — remember the rollback point and let the normal LLM evaluation
                // decide. Short-circuiting to REJECTED here left the staged files (good main +
                // broken test) live in output/ while every downstream consumer believed they were
                // rolled back — unmerged, unrecorded, invisible (run 21, 2026-07-07: fired 6×,
                // the broken tests accumulated until the final package failure).
                log.info("EvaluationAgent T1-FI: probe steered a test-only failure (files kept on disk) — proceeding to LLM evaluation");
                context = context.withLastRollbackPoint(probeResult.rollbackPoint());
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
        } else if (probeResult != null && probeResult.outcome().result() == BuildResult.COMPILE_ERROR
                && !probeResult.filesOnDisk()) {
            // T4-BD: feedbackMessage carries only the brief reason — the structured stderr lives
            // on ctx.lastProbeOutcome() and downstream renderers read it from there.
            // T1-FI: guarded on !filesOnDisk() — a steered test-only failure keeps its files and
            // takes the normal LLM-evaluation branch below instead of this gate short-circuit.
            rawOutcome = new EvaluationOutcome(0.0, MutationAcceptanceState.REJECTED,
                    GoalProgressState.NOT_EVALUATED,
                    "Compile probe failed — see ctx.lastProbeOutcome() stderrTail.", null);
            context = context.withCriteriaAssessments(List.of());
            // T4-BE: when the rejection is fully attributable to extractable blockers, persist
            // the mutation to the deferred-mutation ledger so a later merge that resolves the
            // blockers can re-stage it instead of letting the implementation work die with
            // the rollback. Partial / unexplained stderr → skip; never defer something we
            // can't reason about.
            // T2-DT: same call also threads structured author-error diagnostics onto context
            // so the next cycle's evolution prompt can render the PREVIOUS COMPILE ERRORS block.
            context = tryDeferRejectedMutation(context, probeResult, scoring.globalScore());
        } else if (probeResult != null && probeResult.outcome().result() == BuildResult.FRAGMENTATION_REJECTED) {
            // T4-BH: probe-time package-fragmentation rejection — REJECT without LLM evaluation
            // and without deferring to the ledger (the fragmentation is structural; deferring
            // would just re-fire the same rejection on every restage attempt).
            rawOutcome = new EvaluationOutcome(0.0, MutationAcceptanceState.REJECTED,
                    GoalProgressState.NOT_EVALUATED,
                    "Package fragmentation rejected by probe — see ctx.lastProbeOutcome() stderrTail.", null);
            context = context.withCriteriaAssessments(List.of());
            // T4-BA: carry the conflict one cycle forward so the next evolution prompt's PACKAGE
            // FRAGMENTATION block tells the LLM where the type already lives — a per-cycle
            // placement correction. (Previously dead on the probe path; only MergeAgent set it.)
            // With T4-BL consolidation enabled this branch only fires for the cases consolidation
            // deliberately skips (default-package target, ambiguous double-write, restage path).
            context = recordFragmentationObservation(context, probeResult);
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

        // T4-CC: on a gate-born blocker subgoal, the originating gate's result on the staged
        // candidate takes precedence over the LLM assessment (which falsely rejected the correct
        // packaging fix on 2026-07-16, 90 s before an honest halt). Deterministic defect
        // findings keep their decision; only the model's assessment is superseded.
        EvaluationOutcome preGateOutcome = finalOutcome;
        finalOutcome = applyOriginatingGateCheck(context, negotiationResult, finalOutcome);
        boolean gateAccepted = finalOutcome != preGateOutcome
                && finalOutcome.mutationState() == MutationAcceptanceState.ACCEPTED;

        if (behaviourLogger != null) {
            List<String> overrides = negotiationResult.criticalOpinions() != null
                    ? negotiationResult.criticalOpinions().stream().map(HeuristicOpinion::name).toList()
                    : List.of();
            if (gateAccepted) {
                overrides = new java.util.ArrayList<>(overrides);
                overrides.add("OriginatingGateCheck");
            }
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
        // T4-CC: not on a gate-checked acceptance — the fix measurably resolved its blocker, and
        // the low LLM score is recorded as background, not as an evaluation failure.
        if (!gateAccepted && !result.success() && result.goalProgress() != GoalProgressState.STAGNATING) {
            double score = finalOutcome.score() != null ? finalOutcome.score() : 0.0;
            double strength = Math.min(1.0, (0.75 - score) / 0.20); // 1.0 at ≤0.55, ~0.25 at 0.70, ~0.05 at 0.74
            double urgency = Math.max(0.05, strength);
            context = context.issueRequest(ChannelRequest.of(BasicSignals.LOW_CONFIDENCE, urgency,
                    "evaluation", ChannelRequest.horizonFromDecay(urgency, 0.15)));
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

        // T4-BX: relay the authored reasons for a SEMANTIC rejection to the next cycle so the
        // evolution agent (which today receives none of them) and the planner learn what NOT to do.
        // Build failures are excluded — they already reach the next prompt through the compile
        // diagnostic / build-result blocks; this channel is for the evaluator's + heuristics' own
        // judgement of the code. Verbatim prose, for an LLM to read (not code to parse).
        // T1-FI: a steered test-only failure (files kept) that the LLM then rejects is a SEMANTIC
        // rejection — the authored reasons should relay. Only the gate short-circuits (no files
        // on disk) are build-failure rejections.
        boolean buildFailureRejection = probeResult != null && probeResult.outcome() != null
                && ((probeResult.outcome().result() == BuildResult.COMPILE_ERROR && !probeResult.filesOnDisk())
                        || probeResult.outcome().result() == BuildResult.FRAGMENTATION_REJECTED);
        if (result.mutationState() == MutationAcceptanceState.REJECTED) {
            List<String> reasons = collectRejectionReasons(finalOutcome, negotiationResult);
            if (!buildFailureRejection && !reasons.isEmpty()) {
                context = context.withRejectionFeedback(reasons);
                log.info("T4-BX: relaying {} rejection reason(s) to the next evolution/planner prompt", reasons.size());
            }
            // T2-EY: record the verdict against the proposal's CONTENT, so an identical proposal
            // later in this run is refused instead of re-evaluated. Build failures are recorded
            // too — they are 62.6% of all rejections in the measured corpus and repeat like any
            // other. What keeps a since-fixable compile error from being refused wrongly is the
            // ledger's own condition (nothing merged since), not the exclusion of the category.
            if (rejectedProposalLedger != null) {
                String recorded = !reasons.isEmpty() ? reasons.get(0)
                        : finalOutcome != null ? finalOutcome.feedbackMessage() : null;
                rejectedProposalLedger.recordRejection(context.lastEvolutionFingerprint(),
                        treeStateOf(context), recorded, context.currentSubGoalId());
            }
        }

        // T1-DC: emit ROLE_MERGE on ACCEPTED so the merge agent in the signal-driven loop
        // can run inline this cycle, before the planner can disturb the active subgoal pointer.
        // Also clear the probe rollback point: a snapshot only describes the pre-probe state, so
        // it is no longer the right "undo" target once the merge is about to commit. Without this
        // clear, the next REJECTED cycle's rollback path could fire against this stale snapshot
        // and undo files that were ACCEPTED in this cycle.
        if (result.mutationState() == MutationAcceptanceState.ACCEPTED) {
            // T4-BZ-4: same-cycle merge window (decay-equivalent 1.0 → validity 1, dies at tick).
            context = context.grantWindow(
                            com.walpro.autobuilder.core.lifecycle.ParticipationWindow.of("merge", 1.0, 1.0, "evaluation-accepted"))
                    .withLastRollbackPoint(null);
        }

        // T1-EC: the probe wrote files to output/ and left them staged. If the LLM has now
        // rejected them, roll back to the pre-probe state so the working tree reflects the
        // last-accepted mutation, not the rejected one.
        //
        // Guarded on probeResult.filesOnDisk(): the probe leaves files staged (and returns a
        // non-null rollbackPoint) on COMPILE_OK and on a STEERED test-only COMPILE_ERROR (T1-FI).
        // On a gate-path COMPILE_ERROR the probe has already rolled back internally and returns
        // null. Firing this branch without the filesOnDisk gate would either be a no-op or, if
        // context.lastRollbackPoint() leaked from a prior cycle, would undo files ACCEPTED earlier.
        if (result.mutationState() == MutationAcceptanceState.REJECTED
                && probeResult != null
                && probeResult.filesOnDisk()
                && context.lastRollbackPoint() != null) {
            MergeRollbackPoint rollbackPoint = context.lastRollbackPoint();
            // T4-AQ-rollback-fix: the mutation compiled clean and is being undone because the LLM
            // evaluation rejected it — NOT because the build broke. Preserve the compile-fix
            // injector's deterministic infrastructure (a bootstrapped pom.xml, skeletons): it made
            // the build green and is not the evaluated LLM work, so reverting it just strands the
            // project (re-bootstrap then gets T2-DY-blacklisted → wedge on "no POM").
            java.util.Set<String> injectorPaths = context.injectorFixes().stream()
                    .flatMap(f -> f.touchedPaths().stream())
                    .collect(java.util.stream.Collectors.toSet());
            mergeRollbackService.rollback(rollbackPoint, context.outputPath(), fileService, injectorPaths);
            log.info("EvaluationAgent T1-EC: LLM rejected after compile-OK probe — rolling back {} staged files, preserving injector-created infrastructure",
                    rollbackPoint.originalContents().size());
            context = context.withLastRollbackPoint(null);
        }

        // T1-DI: persist mutationState to meta.json now so FileVariantCollector can find
        // ACCEPTED variants within the same cycle (meta-step path). Without this,
        // CycleExecutor.persistEvaluationResultToMeta runs post-cycle — too late for the
        // fitness-ranked merge pass that follows meta-step execution.
        persistMutationStateToMeta(context);

        if (result.goalProgress() == GoalProgressState.STAGNATING) {
            context = context.issueRequest(ChannelRequest.of(BasicSignals.STAGNATION, 1.0,
                    "evaluation", ChannelRequest.horizonFromDecay(1.0, 0.2)));
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
     * T4-BA: on a probe-time package-fragmentation rejection, store the conflict as
     * {@link PackageFragmentationReport} on the context so it is carried one cycle forward
     * (TTL'd by {@code ContextLineageTracker}). The next cycle's evolution prompt renders it as a
     * PACKAGE FRAGMENTATION placement hint so the LLM puts code in the existing package instead of
     * repeating the split. No-op when the probe carried no structured conflict.
     */
    static CycleContextData recordFragmentationObservation(CycleContextData context,
                                                        CompileProbeService.ProbeResult probeResult) {
        if (probeResult == null) return context;
        PackageFragmentationCheck.Conflict c = probeResult.fragmentationConflict();
        if (c == null) return context;
        PackageFragmentationReport observation = new PackageFragmentationReport(
                context.cycleName(),
                List.of(new PackageFragmentationReport.Conflict(
                        c.simpleName(), c.existingPath(), c.candidatePath())));
        return context
                .withPackageFragmentationThisCycle(true)
                .withPackageFragmentation(observation);
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
    // T2-DT: returns the updated context so the caller can thread Outcome.authorErrors onto
    // the cycle. Always returns a context (input unchanged on early exits).
    CycleContextData tryDeferRejectedMutation(CycleContextData context,
                                              CompileProbeService.ProbeResult probeResult,
                                              Double heuristicScore) {
        if (probeResult == null || probeResult.outcome() == null) return context;
        String stderr = probeResult.outcome().stderrTail();
        if (stderr == null || stderr.isBlank()) return context;

        String manifest = wellKnownPackageRegistry == null
                ? null
                : wellKnownPackageRegistry.readManifestOrEmpty(context.outputPath() + "/pom.xml");
        BlockerExtractor.Outcome outcome = BlockerExtractor.extract(stderr, wellKnownPackageRegistry, manifest);
        emitExtractionOutcomeEvent(context, outcome);

        // T2-DT: capture classified author-errors on context whenever the extractor surfaced
        // any so the next cycle's evolution prompt renders the PREVIOUS COMPILE ERRORS block.
        // T1-EW: carrier identity is now the diagnostic list itself; no subgoal stamp.
        // Staleness is handled by the carrier's natural lifecycle (cleared on merge, overwritten
        // on next probe).
        if (outcome.authorErrors() != null && !outcome.authorErrors().isEmpty()) {
            context = context.withLastAuthorErrors(
                    new com.walpro.autobuilder.core.mutation.CompileAuthorErrors(
                            outcome.authorErrors()));
        }

        // T4-AN-5: capture the build tool's own diagnostic (e.g. `mvn dependency:tree`) for this
        // failed probe, surfaced in the next cycle's evolution/planner prompt. Pom-level, so it is
        // valid after the source rollback; the pom lives at outputPath (same dir read above).
        // No-op unless a tool is detected and diagnostics are enabled in config.
        if (buildToolProbe != null && context.outputPath() != null) {
            String diag = buildToolProbe.diagnose(context.outputPath(), context.cycleName());
            if (diag != null && !diag.isBlank()) {
                context = context.withBuildDiagnostic(diag);
            }
        }

        if (outcome.result() != BlockerExtractor.Outcome.Result.DEFERRED) return context;
        if (deferredMutationLedger == null) return context;
        if (context.evolutionOutcome() == null
                || context.evolutionOutcome().addedOrChangedFiles() == null
                || context.evolutionOutcome().addedOrChangedFiles().isEmpty()) return context;

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
        return context;
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
     * T5-J / T3-BR: true when the evaluator should re-run even though this cycle produced no diff,
     * because the fingerprint repeats but the current subgoal's defined acceptance criteria are not
     * all banked yet — the re-evaluation banks the remaining ones, enabling T3-BL's FULFILLED early
     * exit. Mirrors the {@code criteriaIncomplete} computation in {@link #execute} exactly, so the
     * T5-J skip and the T3-BR re-run agree on this case.
     */
    /**
     * T4-CC: decisive gate acceptance. When the current subgoal was created by a failing
     * completion-edge gate ({@link BlockerTypes#ORIGINATING_GATE_KEY} stamp) and the candidate
     * is staged compile-clean, re-run that gate against the staged tree:
     * <ul>
     *   <li>gate PASSES → the fix measurably resolves the blocker; flip a (non-defect-marked)
     *       LLM REJECTED to ACCEPTED, keeping the model's assessment in the message as
     *       background;</li>
     *   <li>gate STILL FAILS → keep the reject but carry the gate's actual output as the
     *       reason, replacing the LLM's guess (T4-BX relays this into the next prompts).</li>
     * </ul>
     * Deterministic defect findings (critical heuristic opinions) keep their decision — they
     * report a different defect than the gate measures. An LLM ACCEPTED assessment is never
     * flipped on a gate failure: partial fixes landing incrementally is the shape the T4-BP-4
     * validation run succeeded through (8 gate refusals with merges in between).
     */
    EvaluationOutcome applyOriginatingGateCheck(CycleContextData context,
                                                NegotiationResult negotiationResult,
                                                EvaluationOutcome outcome) {
        if (outcome == null || outcome.mutationState() != MutationAcceptanceState.REJECTED) {
            return outcome;
        }
        if (negotiationResult.criticalOpinions() != null && !negotiationResult.criticalOpinions().isEmpty()) {
            return outcome;
        }
        String subGoalId = context.currentSubGoalId();
        if (subGoalId == null) {
            return outcome;
        }
        String gate = goalPlannerService.getGoalMetadataString(subGoalId, BlockerTypes.ORIGINATING_GATE_KEY);
        if (gate == null) {
            return outcome;
        }
        var probe = context.lastProbeOutcome();
        if (probe == null || probe.result() != BuildResult.COMPILE_OK) {
            return outcome;
        }
        java.util.Optional<String> gateFailure = runOriginatingGate(gate, context);
        if (gateFailure == null) {
            return outcome; // gate bean unavailable — nothing decisive to say
        }
        if (gateFailure.isEmpty()) {
            log.info("T4-CC: originating {} passed on the staged candidate — decisive accept "
                            + "(LLM assessment kept as background: state={}, score={})",
                    gate, outcome.mutationState(), outcome.score());
            return new EvaluationOutcome(outcome.score(), MutationAcceptanceState.ACCEPTED,
                    GoalProgressState.PROGRESSING,
                    "Originating " + gate + " passed on the staged candidate — decisive acceptance (T4-CC). "
                            + "The LLM assessment (recorded, not applied) was REJECTED: "
                            + truncate(outcome.feedbackMessage(), 220),
                    outcome.recommendation());
        }
        log.info("T4-CC: originating {} still failing on the staged candidate — reject stands, "
                + "gate output replaces the LLM reason", gate);
        return new EvaluationOutcome(outcome.score(), MutationAcceptanceState.REJECTED,
                outcome.goalProgress(),
                "Originating " + gate + " still failing — " + truncate(gateFailure.get(), 400),
                outcome.recommendation());
    }

    /** Runs the stamped gate against the staged candidate; null when the gate bean is absent. */
    private java.util.Optional<String> runOriginatingGate(String gate, CycleContextData context) {
        String cycleId = context.cycleName();
        try {
            switch (gate) {
                case BlockerTypes.GATE_PACKAGE -> {
                    return packageGate != null ? packageGate.verifyPackageable(cycleId) : null;
                }
                case BlockerTypes.GATE_BOOT -> {
                    if (bootGate == null) return null;
                    GoalEntity root = goalPlannerService.getRootGoal();
                    // T4-CU: hand over the behavioural requirements here too. This re-run is the
                    // boot-gate invocation that actually FIRES in the field — the closure path
                    // reaches it rarely, and in run 2026-07-28_10-46-51 all THREE boot-gate runs
                    // came through here while the closure path never got that far. Calling the
                    // two-arg form passed an empty criteria list, so the criterion probes were
                    // skipped on every one of them: nine runs, criterion_probe_runs=0 throughout,
                    // including the run that ended at a verified 23/23 without the loop ever being
                    // able to tell it apart from the 19/23 it had happily declared complete.
                    return bootGate.verifyBootsAndServes(cycleId,
                            root != null ? root.getDescription() : "",
                            goalPlannerService.specBoundBehaviouralCriteria());
                }
                case BlockerTypes.GATE_TEST -> {
                    if (testRunGate == null) return null;
                    GoalEntity root = goalPlannerService.getRootGoal();
                    boolean testsRequired = root != null
                            && com.walpro.autobuilder.core.build.TestRunGate.specRequiresTests(root.getDescription());
                    return testRunGate.verifyTestsPass(cycleId, testsRequired);
                }
                default -> {
                    log.warn("T4-CC: unknown originating gate stamp '{}' on subgoal — skipping decisive check", gate);
                    return null;
                }
            }
        } catch (Exception e) {
            log.warn("T4-CC: originating gate {} threw — falling back to the negotiated outcome: {}",
                    gate, e.getMessage());
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String clean = s.replaceAll("\\s+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "…";
    }

    private boolean criteriaBankingWarranted(CycleContextData ctx) {
        String currentFingerprint = ctx.lastEvolutionFingerprint();
        String lastEvaluatedFingerprint = ctx.lastEvaluatedEvolutionFingerprint();
        boolean fingerprintUnchanged = currentFingerprint != null
                && currentFingerprint.equals(lastEvaluatedFingerprint)
                && !DerivedFacts.evolutionFingerprintChanged(ctx);
        return fingerprintUnchanged
                && ctx.currentSubGoalId() != null
                && goalPlannerService.hasAcceptanceCriteria(ctx.currentSubGoalId())
                && !goalPlannerService.allCriteriaDefinedAndBanked(ctx.currentSubGoalId());
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
     * T4-BX: gather the authored reasons a mutation was rejected — the evaluator's
     * {@code feedbackMessage} plus the rationale of every heuristic that voted REJECTED or
     * STAGNATING. Each rationale is prefixed with its author's name so the reader knows who said
     * it. Skips empty / "No concerns identified." rationales. The strings are returned verbatim for
     * an LLM prompt to read; nothing here parses them.
     */
    /**
     * T2-EY: a repeat that is about to be refused, carrying what the ledger knows about it.
     *
     * @param priorRejections including the one being repeated
     * @param subgoalSpan     how many subgoals have proposed it; &gt;1 means replanning has not
     *                        dislodged it, which is the plan's problem rather than the mutation's
     */
    private record RepeatRefusal(int priorRejections, String reason, int subgoalSpan) {}

    /**
     * T2-EY: a fingerprint of the tree a proposal is judged against, which scopes the verdict.
     *
     * <p>Derived from {@code previousOutput()} — the output tree as this cycle found it — rather
     * than tracked by notifying the ledger on every write. A merge is not the only writer: the
     * compile-fix injector's files are preserved through the rejection rollback by design, and
     * consolidation writes too. Deriving it means a writer nobody remembered to announce still
     * invalidates the verdict, and the failure mode of forgetting one would be a wrong refusal.
     *
     * <p>Returns null when the tree is unknown, which the ledger reads as "do not act": an
     * unscoped verdict is exactly the case the scoping exists to prevent.
     */
    private String treeStateOf(CycleContextData context) {
        Map<String, String> tree = context != null ? context.previousOutput() : null;
        if (tree == null || tree.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        tree.keySet().stream().sorted().forEach(path -> sb
                .append(path).append('|')
                .append(java.util.Objects.hashCode(tree.get(path))).append('\n'));
        return com.walpro.autobuilder.util.HashingUtils.hashSha256(sb.toString());
    }

    /**
     * The standing rejection for this proposal, or null when there is none to act on.
     *
     * <p>Deliberately silent when the ledger is absent (unit tests) or when the fingerprint is
     * unchanged — that case already has its own settled handling above and is not a fresh proposal
     * repeating a refused one.
     */
    private RepeatRefusal standingRejectionFor(CycleContextData context, String fingerprint,
                                               boolean fingerprintUnchanged) {
        if (rejectedProposalLedger == null || fingerprintUnchanged) return null;
        return rejectedProposalLedger.standingRejection(fingerprint, treeStateOf(context))
                .map(r -> new RepeatRefusal(r.priorRejections(), r.reason(), r.subgoalSpan()))
                .orElse(null);
    }

    /**
     * Refuse the repeat: settle it as REJECTED without a probe or an evaluator call, relay the
     * original reason, and escalate.
     *
     * <p>The escalation is graded because the two signatures measured on 2026-07-31 want different
     * answers. A repeat inside one subgoal is the mutation's problem, so DIVERGENCE_REQUEST asks
     * for a different mutation. A repeat that has survived a change of subgoal — or a second
     * refusal of the same bytes — is evidence the plan is asking for something the generator cannot
     * produce, so REPLAN_REQUESTED goes up as well. Escalating on the second occurrence rather than
     * the tenth is the point: the run this was filed from spent 33 cycles on one of these.
     *
     * <p>No rollback is needed and none is attempted — the probe has not run, so nothing is staged.
     */
    private CycleContextData applyRepeatRefusal(CycleContextData context, String fingerprint,
                                                RepeatRefusal repeat) {
        String relayed = repeat.reason() != null && !repeat.reason().isBlank()
                ? repeat.reason()
                : "no reason was recorded with the original rejection";
        String feedback = "This exact mutation was already rejected in this run ("
                + repeat.priorRejections() + " time(s)) and nothing has merged since, so the"
                + " judgement still stands. It was refused without re-evaluating it. Original"
                + " reason: " + relayed;

        log.warn("EvaluationAgent T2-EY: refusing a proposal already rejected {}x this run across {} subgoal(s)"
                        + " — no evaluator call. fingerprint={}",
                repeat.priorRejections(), repeat.subgoalSpan(), fingerprint);

        rejectedProposalLedger.noteRefusal(fingerprint, treeStateOf(context), context.currentSubGoalId());
        if (behaviourLogger != null) {
            behaviourLogger.mutationRejected(context.cycleName(), fingerprint,
                    "T2-EY: repeat of a standing rejection — " + relayed);
        }

        EvaluationOutcome outcome = new EvaluationOutcome(0.0, MutationAcceptanceState.REJECTED,
                GoalProgressState.NOT_EVALUATED, feedback, null);

        CycleContextData out = context
                .withEvaluationOutcome(outcome)
                .withLastEvaluationOutcome(outcome)
                .withMutationState(MutationAcceptanceState.REJECTED)
                .withGoalProgress(GoalProgressState.NOT_EVALUATED)
                .withEvaluationFeedback(feedback)
                .withCriteriaAssessments(List.of())
                // The candidate has been judged. Without this the deliberation rule "candidate
                // pending evaluation" pins mode=EVALUATE on the same fingerprint every cycle —
                // the starvation T5-J was filed for, which a short-circuit is exactly the shape to
                // reintroduce.
                .withLastEvaluatedEvolutionFingerprint(fingerprint)
                // The reason reaches the next evolution prompt through T4-BX's existing relay
                // rather than a second channel saying the same thing.
                .withRejectionFeedback(List.of(feedback))
                .issueRequest(ChannelRequest.of(BasicSignals.REPETITION_DETECTED, 1.0,
                        "repeat-proposal-refused", ChannelRequest.horizonFromDecay(1.0, 0.05)))
                .issueRequest(ChannelRequest.of(BasicSignals.DIVERGENCE_REQUEST, 1.0,
                        "repeat-proposal-refused", ChannelRequest.horizonFromDecay(1.0, 0.10)));

        if (repeat.subgoalSpan() > 1 || repeat.priorRejections() >= 2) {
            log.warn("EvaluationAgent T2-EY: the same refused proposal has survived {} subgoal(s) and {} rejection(s)"
                    + " — asking the planner to change the work item, not the mutation",
                    repeat.subgoalSpan(), repeat.priorRejections());
            out = out.issueRequest(ChannelRequest.of(BasicSignals.REPLAN_REQUESTED, 1.0,
                    "repeat-proposal-refused", ChannelRequest.horizonFromDecay(1.0, 0.2)));
        }

        // The rejection must reach meta.json even though no evaluation ran. Evolution has already
        // written this cycle's mutation directory, so a meta.json left without a state describes
        // a refused proposal as unjudged — and the preserved-artifact selection reads exactly
        // those snapshots. Leaving it out would let a refused mutation be chosen as a run's best
        // artifact. This is the same file whose unreadability sterilised run 17-37-09; the failure
        // mode there was a state that could not be read back, here it would be a state never
        // written, and both end with the record disagreeing with what happened.
        persistMutationStateToMeta(out);

        // Deliberately NOT recorded to the graph as an evaluation. No evaluation happened — the
        // verdict is the earlier one, which is already in the graph. Recording a second would
        // double-count a judgement that was made once.
        return out;
    }

    static List<String> collectRejectionReasons(EvaluationOutcome finalOutcome, NegotiationResult negotiation) {
        List<String> reasons = new ArrayList<>();
        String message = finalOutcome != null ? finalOutcome.feedbackMessage() : null;
        if (isMeaningfulReason(message)) {
            reasons.add(message.trim());
        }
        List<HeuristicOpinion> opinions = negotiation != null ? negotiation.allOpinions() : null;
        if (opinions != null) {
            for (HeuristicOpinion op : opinions) {
                if (op == null) continue;
                boolean dissenting = op.mutationState() == MutationAcceptanceState.REJECTED
                        || op.goalProgress() == GoalProgressState.STAGNATING;
                if (!dissenting) continue;
                String rationale = op.rationale();
                if (!isMeaningfulReason(rationale)) continue;
                String line = op.name() + ": " + rationale.trim();
                if (!reasons.contains(line)) {
                    reasons.add(line);
                }
            }
        }
        return reasons;
    }

    private static boolean isMeaningfulReason(String s) {
        return s != null && !s.isBlank() && !s.trim().equalsIgnoreCase("No concerns identified.");
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