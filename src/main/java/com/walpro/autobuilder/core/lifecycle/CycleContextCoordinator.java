package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.core.lifecycle.ParticipationWindow;
import com.walpro.autobuilder.analysis.structure.DefaultProjectStructureSummarizer;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.AbandonedGoalCluster;
import com.walpro.autobuilder.goal.AbandonedGoalQueryService;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.graph.evaluation.EvaluationGraphFacade;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static com.walpro.autobuilder.core.lifecycle.BasicSignals.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class CycleContextCoordinator {

    private final FileService fileService;
    private final GoalPlannerService goalPlannerService;
    private final DefaultProjectStructureSummarizer projectStructureSummarizer;
    private final ContextLineageTracker lineageTracker;
    private final CycleStartSignalSeeder cycleStartSignalSeeder;
    private final EvaluationGraphFacade evaluationGraphFacade;
    private final AbandonedGoalQueryService abandonedGoalQueryService;

    /** T4-DA: whole-plan-against-whole-seed coverage. Optional so existing tests construct freely. */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.analysis.coverage.SeedCoverageAnalyzer seedCoverageAnalyzer;

    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    public Optional<CycleContextData> prepareRuntimeContext(CycleContextData previous) {
        try {
            String cycleName = "cycle-" + Instant.now().toEpochMilli();
            Path outputPath = fileService.resolveSecurePath("output");
            Map<String, String> currentOutput = fileService.readFolderContentsResolved(outputPath);
            String summary = projectStructureSummarizer.summarizeProjectStructure(outputPath.toString());
            String detectedBasePackage = projectStructureSummarizer.detectBasePackage(outputPath);

            // T1-EL: repair currentSubGoalId if it points at a non-ACTIVE goal before any
            // downstream read picks up the stale pointer. Cascade aftermath, lost promotions,
            // and other state-asymmetry cases can leave the pointer referencing a GIVEN_UP /
            // COMPLETED goal — surfacing it here gives a single deterministic recovery site.
            GoalPlannerService.SubGoalRepair repair = goalPlannerService.repairCurrentSubGoalIfStale();
            if (repair != null && behaviourLogger != null) {
                behaviourLogger.currentSubgoalRepaired(cycleName, repair.oldId(), repair.newId(), repair.reason());
            }

            GoalEntity goal = goalPlannerService.getRootGoal();
            GoalEntity subgoal = goalPlannerService.getCurrentSubGoal();

            int depth = computeGoalDepth(subgoal == null ? null : subgoal.getId());
            int cumulativeStagnating = subgoal != null
                    ? goalPlannerService.getGoalMetadataInt(subgoal, "cumulativeStagnatingCycles")
                    : 0;

            // T1-BG/T3-BI: load cumulative criteria pass status from the subgoal entity.
            // Always read from the entity so subgoal changes automatically yield a fresh map,
            // and restarts recover previously banked criterion passes.
            Map<Integer, Boolean> criteriaPassStatus = subgoal != null
                    ? goalPlannerService.getCriteriaPassStatus(subgoal)
                    : null;

            // T3-BK: load per-criterion consecutive-fail streak counts from the subgoal entity.
            Map<Integer, Integer> criteriaFailStreak = subgoal != null
                    ? goalPlannerService.getCriteriaFailStreak(subgoal)
                    : null;

            @SuppressWarnings("unchecked")
            List<String> subgoalCriteria = subgoal != null && subgoal.getMetadata() != null
                    ? (List<String>) subgoal.getMetadata().getOrDefault("acceptanceCriteria", List.of())
                    : List.of();

            // T3-BH: load plan rationale from the current subgoal's parent goal metadata.
            String currentSubGoalParentId = subgoal != null ? subgoal.getParentId() : null;
            String planRationale = null;
            if (currentSubGoalParentId != null) {
                GoalEntity parentGoal = goalPlannerService.getGoalById(currentSubGoalParentId);
                if (parentGoal != null) {
                    planRationale = goalPlannerService.getPlanRationale(parentGoal);
                }
            }

            // T2-DL: surface related GIVEN_UP clusters so analysis + planner see prior work
            // before proposing new subgoals. Prefer the current subgoal's description; fall
            // back to the root goal when no subgoal is active (the failure mode where the
            // abandoned-goal query matters most — planner is about to re-propose from scratch).
            String abandonedGoalProbe = subgoal != null && subgoal.getDescription() != null
                    ? subgoal.getDescription()
                    : (goal != null ? goal.getDescription() : null);
            List<AbandonedGoalCluster> relatedAbandoned = abandonedGoalProbe != null
                    ? abandonedGoalQueryService.findRelatedAbandonedGoals(abandonedGoalProbe)
                    : List.of();

            // T4-DA: the whole-plan-against-the-whole-seed comparison, recomputed every cycle.
            // Deliberately not bound to a closure or give-up edge: the coverage question was only
            // ever asked when a run was already trying to finish, so the three frozen runs of
            // 2026-07-30 — all ending on the deadline with an active backlog — never asked it once.
            // Cheap enough to run unconditionally (string comparison over the goal tree, no model
            // call), so there is no interval to get wrong.
            com.walpro.autobuilder.analysis.coverage.SeedCoverageReadout seedCoverage =
                    seedCoverageAnalyzer != null && goal != null
                            ? analyzeWithWrittenObligations()
                            : null;

            // T2-EX: how much of this run is verifiable at all. Measurement only — nothing reads
            // it back and nothing steers on it, per the ticket, until the share has been seen over
            // several runs. Logged rather than carried on the context: it decides nothing this
            // cycle, and a field on the context is the surface that sterilised run 17-37-09.
            reportCriteriaExecutability();
            reportRepeatProposals();
            reportInjectorFiles();

            // T2-CE: fetch per-subgoal score baseline from the graph each cycle.
            // This is the score of the most recent ACCEPTED evaluation for the current subgoal —
            // used by ScoreRegressionHeuristic and ProblemReductionHeuristic instead of the
            // global lastScore to prevent cross-subgoal baseline contamination.
            String activeSubGoalId = subgoal != null ? subgoal.getId() : null;
            Double lastAcceptedSubgoalScore = null;
            List<Double> subgoalScoreHistory = List.of();
            if (activeSubGoalId != null) {
                var scoreOpt = evaluationGraphFacade.findLatestAcceptedScoreForSubgoal(activeSubGoalId);
                lastAcceptedSubgoalScore = (scoreOpt != null) ? scoreOpt.orElse(null) : null;
                var history = evaluationGraphFacade.findScoreHistoryForSubgoal(activeSubGoalId, 5);
                subgoalScoreHistory = (history != null) ? history : List.of();
            }

            CycleContextData base = (previous == null ? CycleContextData.builder() : previous.toBuilder())
                .cycleName(cycleName)
                .outputPath(outputPath.toString())
                .previousOutput(currentOutput)
                .projectSummary(summary)
                .currentGoalDescription(goal != null ? goal.getDescription() : null)
                .currentGoalId(goal != null ? goal.getId() : null)
                .currentSubGoalDescription(subgoal != null ? subgoal.getDescription() : null)
                .currentSubGoalId(subgoal != null ? subgoal.getId() : null)
                .remainingGoals(goalPlannerService.getRemainingGoals())
                .completedGoals(goalPlannerService.getCompletedGoals())
                .abandonedGoals(goalPlannerService.getAbandonedGoals())
                .rejectionFeedback(null)
                .recommendation(null)
                .evolutionOutcome(null)
                .evaluationOutcome(null)
                .plannerOutcome(null)
                .evaluationFeedback(null)
                .evaluationInterpretation(null)
                .mutationState(null)
                .subgoalPromoted(false)
                .merged(false)
                .evolutionRanThisCycle(false)
                .mergedPath(null)
                .mergedBaseContent(null)
                .mergedVariants(List.of())
                .mergeTrace(lineageTracker.emptyMergeTrace())
                .findings(List.of())
                .goalDepth(depth)
                .recentEvolutionFingerprints(previous != null && previous.recentEvolutionFingerprints() != null
                        ? previous.recentEvolutionFingerprints()
                        : List.of())
                .scoringComputed(false)
                .cumulativeStagnatingCycles(cumulativeStagnating)
                .establishedBasePackage(detectedBasePackage)
                .currentSubGoalCriteria(subgoalCriteria.isEmpty() ? null : subgoalCriteria)
                // T1-BG/T3-BI: always sourced from the entity so subgoal switches and restarts are handled transparently
                .cumulativeCriteriaStatus(criteriaPassStatus != null && !criteriaPassStatus.isEmpty() ? criteriaPassStatus : null)
                // T2-BL: reset lastCriteriaPassCount when the active subgoal changes so the detector
                // does not compare the new subgoal's pass count against the old subgoal's baseline.
                .lastCriteriaPassCount(subgoalChanged(previous, subgoal) ? 0
                        : (previous != null ? previous.lastCriteriaPassCount() : 0))
                // T1-BS/T1-CL: clear lastAttemptId on subgoal change OR when the previous cycle
                // performed a promotion (subgoalPromoted=true). Without the promotion guard the ID
                // carried from the promotion cycle points at a COMPLETED subgoal's attempt, causing
                // isAcceptedNotMergedAttempt to permanently block evolution on the new subgoal.
                .lastAttemptId((subgoalChanged(previous, subgoal) || (previous != null && previous.subgoalPromoted())) ? null
                        : (previous != null ? previous.lastAttemptId() : null))
                // T3-BK: always sourced from the entity; new subgoals have no streak data (null → fresh start).
                .criteriaConsecutiveFailCount(criteriaFailStreak != null && !criteriaFailStreak.isEmpty() ? criteriaFailStreak : null)
                // T2-CE: always re-fetched from the graph — not carried from previous context.
                .lastAcceptedSubgoalScore(lastAcceptedSubgoalScore)
                .subgoalScoreHistory(subgoalScoreHistory)
                // T3-BH: sibling coordination context
                .currentSubGoalParentId(currentSubGoalParentId)
                .planRationale(planRationale)
                // T2-DL: abandoned-goal awareness for analysis + planner.
                .relatedAbandonedGoals(relatedAbandoned)
                // T4-DA: does the plan, taken as a whole, still resemble the specification.
                .seedCoverage(seedCoverage)
                .workRules(rulesTheSpecificationDoesNotCarry())
                .build();

            // T2-BK: replace subgoal-scoped signals in the carried context with the entity-backed
            // state for the current subgoal. This isolates per-subgoal signal history: switching
            // to a new subgoal gives a clean slate (its entity has no persisted signals yet), while
            // same-subgoal continuation and restarts recover the correctly persisted decayed state.
            // T4-BZ-3 (I6): per-subgoal isolation on the channel carrier — carried requests
            // of scoped kinds are dropped, the entity-backed set restores. A new subgoal
            // (no persisted slot) gets a clean slate. Structural kinds (REPLAN_REQUESTED etc.)
            // are not in SUBGOAL_SCOPED_SIGNALS and pass through unchanged.
            base = base.withRequests(base.requests().withoutKinds(BasicSignals.SUBGOAL_SCOPED_SIGNALS));
            java.util.List<ChannelRequest> entityRequests = subgoal != null
                    ? goalPlannerService.getSubgoalRequests(subgoal)
                    : java.util.List.of();
            for (ChannelRequest r : entityRequests) {
                base = base.withRequests(base.requests().issue(r));
            }
            if (!entityRequests.isEmpty()) {
                log.debug("Restored {} subgoal-scoped open request(s) from entity for subgoal {}",
                        entityRequests.size(), subgoal != null ? subgoal.getId() : null);
            }

            base = cycleStartSignalSeeder.seedIfEmpty(base);

            if (goal == null) {
                base = base.grantWindow(ParticipationWindow.of("planner", 1.0, 0.1, "no root goal"));
            } else if (subgoal == null && goal.getStatus() == GoalStatus.COMPLETED) {
                // Fix T1-AD/A: root is completed and no subgoals remain — nominal terminal state
                log.info("Root goal COMPLETED and no active subgoals remain; injecting GOAL_EXHAUSTED to halt loop.");
                base = base.withGoalExhaustedReason("completion-terminal");
            } else if (subgoal == null && goal.getStatus() == GoalStatus.GIVEN_UP) {
                // T1-BZ: root was given up (majority of direct children failed) — halt rather than replanning.
                log.warn("Root goal GIVEN_UP with no active subgoals — injecting GOAL_EXHAUSTED to halt loop.");
                base = base.withGoalExhaustedReason("root-given-up-terminal");
            } else if (subgoal == null) {
                // T1-DH: before triggering the T1-AD/B re-plan attempt, give the root a chance to
                // auto-complete on the work that finished this cycle. Without this check the planner
                // re-runs whenever currentSubGoalId goes null at the boundary between the last
                // first-wave promotion and the next cycle, even when the first-wave completion
                // ratio is already above threshold — wasting LLM calls on duplicate proposals.
                goalPlannerService.autoCompleteRootIfSatisfied();
                GoalEntity refreshed = goalPlannerService.getRootGoal();
                if (refreshed != null && refreshed.getStatus() == GoalStatus.COMPLETED) {
                    log.info("T1-DH: pre-plan auto-complete marked root COMPLETED — emitting GOAL_EXHAUSTED to halt instead of re-planning.");
                    base = base.withGoalExhaustedReason("T1-DH: pre-plan auto-complete");
                } else if (refreshed != null && refreshed.getStatus() == GoalStatus.GIVEN_UP) {
                    log.warn("T1-DH: pre-plan auto-complete marked root GIVEN_UP — emitting GOAL_EXHAUSTED to halt.");
                    base = base.withGoalExhaustedReason("T1-DH: pre-plan auto-given-up");
                } else {
                    // Fix T1-AD/B: subgoals exhausted but root not yet completed — allow one replan attempt
                    base = base.grantWindow(ParticipationWindow.of("planner", 1.0, 0.1, "no active subgoal"));
                }
            }

            // T4-BN-3/T4-BZ-2: TEST_REPAIR_FOCUS gating reads base.currentGoalDescription()
            // (set from the same root `goal` above) via DerivedFacts at read time.
            return Optional.of(lineageTracker.enrichLineage(base, previous));
        } catch (Exception e) {
            log.error("Failed to prepare runtime context", e);
            return Optional.empty();
        }
    }
    
    private static boolean subgoalChanged(CycleContextData previous, GoalEntity subgoal) {
        if (previous == null) return false;
        String prevId = previous.currentSubGoalId();
        String newId = subgoal != null ? subgoal.getId() : null;
        if (prevId == null && newId == null) return false;
        if (prevId == null || newId == null) return true;
        return !prevId.equals(newId);
    }

    private int computeGoalDepth(String goalId) {
        int depth = 0;
        while (goalId != null) {
            GoalEntity g = goalPlannerService.getGoalById(goalId);
            if (g == null) break;
            goalId = g.getParentId();
            depth++;
        }
        return depth;
    }

    /**
     * T4-DK: the same exercisability question the probe path asks. Injected rather than reached
     * for statically, so the readout cannot drift back to a serving-only answer.
     */
    @Autowired(required = false)
    @Setter
    private com.walpro.autobuilder.core.build.ExercisableCriterionQuery exercisableCriterionQuery;

    /** T2-EX: last reported counts, so a steady plan does not repeat the same line every cycle. */
    private com.walpro.autobuilder.observability.CriteriaExecutabilityReadout.Counts lastExecutability;

    /**
     * T2-EX: reports what share of the run's acceptance criteria can be turned into a request at
     * all. Emitted only when the figure moves, because a plan that is not changing has nothing new
     * to say and a per-cycle repeat would bury it.
     */
    private void reportCriteriaExecutability() {
        var counts = com.walpro.autobuilder.observability.CriteriaExecutabilityReadout
                .over(goalPlannerService.getAllGoals(), exercisableCriterionQuery,
                        goalPlannerService.specification());
        if (counts.isEmpty() || counts.equals(lastExecutability)) return;
        lastExecutability = counts;
        log.info("T2-EX: criteria executability — {} authored, {} address the surface, {} executable ({}%);"
                        + " {} address it but do not parse, {} name an input nothing can ground,"
                        + " {} are elaboration beyond the specification",
                counts.authored(), counts.addressingSurface(), counts.executable(),
                Math.round(counts.executableShare() * 100), counts.namedButUnparsed(),
                counts.ungroundableInput(), counts.elaboration());
    }

    /** T2-EY: optional run-level rejection ledger — wired in production, null in unit tests. */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.mutation.RejectedProposalLedger rejectedProposalLedger;

    /** T2-EY: last reported readout, so a run that is not repeating itself says nothing. */
    private com.walpro.autobuilder.core.mutation.RejectedProposalLedger.Readout lastRepeatReadout;

    /**
     * T2-EY: reports how much of the run is the loop re-proposing what it has already been refused.
     * The count is the ticket's measurement half and exists because 29 identical firings read as 29
     * independent events while nothing counted them; {@code refused} is what the gate actually
     * stopped, kept separate so the benefit is never inferred from the exposure.
     */
    private void reportRepeatProposals() {
        if (rejectedProposalLedger == null) return;
        var readout = rejectedProposalLedger.readout();
        if (readout.isEmpty() || readout.equals(lastRepeatReadout)) return;
        lastRepeatReadout = readout;
        log.info("T2-EY: repeat proposals — {} distinct rejected, {} re-proposed, {} refused before evaluation,"
                        + " worst repeated {}x",
                readout.distinctRejected(), readout.repeatedProposals(), readout.refusals(),
                readout.worstRepeatCount());
    }

    /** T2-EZ: optional run-level injector ledger — wired in production, null in unit tests. */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.core.build.inject.FixAttemptLedger fixAttemptLedger;

    /** T2-EZ: last reported readout, so a run whose injector is idle says nothing. */
    private com.walpro.autobuilder.core.build.inject.FixAttemptLedger.Readout lastInjectorReadout;

    /**
     * T2-EZ: reports what the compile-fix injector built and how much of it the tree kept.
     * {@code created} is the effort, {@code kept} is what the preservation rule saved, and
     * {@code re-derived} is the exposure that remains — a run where kept rises and re-derived
     * does not fall would mean the preserved files are not the ones being re-derived, which is
     * exactly the reading a single number would hide.
     */
    private void reportInjectorFiles() {
        if (fixAttemptLedger == null) return;
        var readout = fixAttemptLedger.readout();
        if (readout.isEmpty() || readout.equals(lastInjectorReadout)) return;
        lastInjectorReadout = readout;
        log.info("T2-EZ: injector files — {} created on a red build, {} kept, {} fix(es) re-derived across"
                        + " cycles, worst {}x",
                readout.created(), readout.preserved(), readout.rederived(), readout.worstFires());
    }

    /**
     * T6-S: hand the analyzer the target's written obligations, when it has them, so the coverage
     * readout counts the reviewed split rather than one derived per run. Absent falls through to
     * the derived split, unchanged.
     */
    /**
     * T4-EK slice (c): the obligations marked rule:, for a project whose specification cannot carry them -- a seed
     * confirmed from a reverse-engineered account states only WHAT the project does, so how work on it is done lives
     * in the obligations alone and would otherwise reach no agent. Any other project's rules are sentences of its
     * specification (the obligations file restates them, usually reworded -- 22 of the 25 toy cases with rules have
     * at least one whose wording is not literally the seed's), which the agents read already: those are not handed
     * over a second time, and their prompts stay as they were.
     */
    private List<String> rulesTheSpecificationDoesNotCarry() {
        try {
            if (!goalPlannerService.seedIsEarned()) return List.of();
            return goalPlannerService.requirements().rules();
        } catch (RuntimeException e) {
            log.debug("T4-EK: the project's rules could not be read -- {}", e.getMessage());
            return List.of();
        }
    }

    private com.walpro.autobuilder.analysis.coverage.SeedCoverageReadout analyzeWithWrittenObligations() {
        return seedCoverageAnalyzer.analyze(goalPlannerService.specification(), goalPlannerService.getAllGoals(),
                goalPlannerService.requirements().obligationsOrClauses());
    }

}
