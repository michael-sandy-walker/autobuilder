package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.TestRunGate;
import com.walpro.autobuilder.core.build.TestRunReport;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.goal.DeclaredTestSuite;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * T6-T: rejects a change that makes MORE of the operator's tests fail than the project as it is, on a target
 * that declares its suite an authority.
 *
 * <p><b>The harm, from the field.</b> Ladder-rows run 2026-10-06_00-25-31 reached 27/29 at 01:52 with one test
 * failing; at 02:01 a candidate failing seven was merged -- the model scored it ACCEPTED while its own
 * assessment said "7 tests failed", and the problem-reduction heuristic abstained because a bookkeeping
 * signal had expired -- and the run ended at 21/29. Across 119 archived runs with a declared suite, 13 of 348
 * merges raised the failing count; in none did the tree later fall below the count it had before, and in at
 * least six the raised count was where the run ended.
 *
 * <p><b>Why a rising COUNT, not any newly failing test.</b> A partial fix that lowers the count, or trades one
 * failing test for another, still lands: the incremental repair the originating-gate check was built to let
 * through (T4-CC). Only a change that leaves the suite worse than it found it is refused, with the tests it
 * broke named so the next attempt knows what to keep.
 *
 * <p><b>A suite that does not finish</b> (cut at the timeout) is rejected the same way, naming the classes that never
 * finished: a hang is worse than any failing test, and its partial report must never be judged as the suite.
 *
 * <p><b>Why gated on the declaration.</b> As {@link StagedTestFailureHeuristic}: a greenfield target writes its
 * own tests and may write one before its code; there the suite is not yet the operator's authority.
 */
@Slf4j
@Component
public class TestRegressionHeuristic implements EvaluationHeuristic {

    private static final String NAME = "TestRegressionHeuristic";
    private static final int MAX_LISTED = 6;

    private final ObjectProvider<TestRunGate> testRunGate;
    private final ObjectProvider<DeclaredTestSuite> declaredSuite;

    public TestRegressionHeuristic(ObjectProvider<TestRunGate> testRunGate, ObjectProvider<DeclaredTestSuite> declaredSuite) {
        this.testRunGate = testRunGate;
        this.declaredSuite = declaredSuite;
    }

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome outcome) {
        TestRunGate gate = testRunGate.getIfAvailable();
        if (gate == null) return HeuristicOpinion.none(NAME);
        // Whatever follows, this evaluation's candidate run replaces the last one: a merge must never promote
        // the failing tests of an earlier, rejected candidate.
        gate.candidateRan(null);
        if (input == null || outcome == null || !gate.isLive()) return HeuristicOpinion.none(NAME);
        DeclaredTestSuite declared = declaredSuite.getIfAvailable();
        if (declared == null || !declared.isDeclared()) return HeuristicOpinion.none(NAME);
        BuildOutcome probe = input.lastProbeOutcome();
        if (probe == null || probe.result() != BuildResult.COMPILE_OK) return HeuristicOpinion.none(NAME);
        Optional<TestRunReport> report = gate.freshReport();
        // T6-T: a change whose suite does not finish is worse than any tree -- nothing can be judged by it, and a
        // merged hang cost run 2026-10-06_11-50-45 its whole result (final tree 2/29, its preserved one 26/29).
        if (report.isEmpty() && gate.lastRunTimedOut()) {
            String rationale = "With this change the project's own suite does not finish: "
                    + gate.lastBlocker().orElse("the run was stopped at the timeout")
                    + ". Find the loop that does not end before anything else.";
            log.info("{}: {}", NAME, rationale);
            return HeuristicOpinion.ofDefectWithScore(NAME, 0.9, MutationAcceptanceState.REJECTED,
                    GoalProgressState.STAGNATING, rationale, Math.max(0.0, outcome.score() - 0.10));
        }
        if (report.isEmpty()) {
            // T6-T: its tests did not compile -- which test files do not is what a merge hands the tree.
            gate.candidateDidNotCompile();
            return HeuristicOpinion.none(NAME);
        }
        gate.candidateRan(report.get());

        Optional<Set<String>> tree = gate.treeFailing();
        if (tree.isEmpty()) return HeuristicOpinion.none(NAME);
        Set<String> now = TestRunGate.failingNames(report.get());
        if (now.size() <= tree.get().size()) return HeuristicOpinion.none(NAME);

        List<String> broke = new ArrayList<>();
        for (String t : now) if (!tree.get().contains(t)) broke.add(t);
        String listed = String.join(", ", broke.subList(0, Math.min(MAX_LISTED, broke.size())))
                + (broke.size() > MAX_LISTED ? " and " + (broke.size() - MAX_LISTED) + " more" : "");
        String rationale = "This change makes the project's own suite worse: " + now.size() + " test(s) fail with it, "
                + tree.get().size() + " without it. It breaks " + (broke.isEmpty() ? "tests that pass now" : listed)
                + ". Keep what passes now passing while fixing the rest.";
        log.info("{}: {}", NAME, rationale);
        return HeuristicOpinion.ofDefectWithScore(NAME, 0.9, MutationAcceptanceState.REJECTED,
                GoalProgressState.STAGNATING, rationale, Math.max(0.0, outcome.score() - 0.10));
    }

    /** T6-T: the veto is a measurement -- the suite run with and without the candidate -- so a veto grind never silences it. */
    @Override
    public boolean measured() {
        return true;
    }
}
