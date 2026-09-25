package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.TestRunGate;
import com.walpro.autobuilder.core.build.TestRunReport;
import com.walpro.autobuilder.core.build.ValueObservationProbe;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.SeedObligationsFile;
import com.walpro.autobuilder.goal.SeedRequirements;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * T4-CQ: rejects a mutation whose OWN tests fail, on a target that declares its test suite passing
 * as an obligation ({@code gate: tests} in the obligations file).
 *
 * <p><b>The harm, from the field.</b> structdiff run {@code 2026-09-24_23-33-41}: the suite was green
 * at 00:06, and at 00:10 a merge added six assertions expecting a class name where the parser
 * returns a path. The probe only compiles, so nothing ran the new tests before the merge; the test
 * gate first saw them at the next closure attempt, and the remaining 24 minutes of the run went to
 * repairing them. The run ended 3/11 where the previous one reached 8/11.
 *
 * <p><b>Why only the mutation's own test classes.</b> A failure in a test class the mutation did not
 * touch was red before it and is the repair item's business; blocking unrelated work on it would
 * stall the run. A failure in a class the mutation wrote is the mutation's defect, and the fix is
 * one the seed spells out: observe what the code returns and assert that -- which is why the
 * rationale carries each failure's message (the observed value) and is relayed verbatim.
 *
 * <p><b>Why gated on the declaration.</b> A greenfield target may write a test before the code it
 * tests; rejecting that would forbid test-first work. A target whose obligations make the suite
 * passing a requirement has said a failing test is never progress.
 */
@Slf4j
@Component
public class StagedTestFailureHeuristic implements EvaluationHeuristic {

    private static final String NAME = "StagedTestFailureHeuristic";
    private static final int MAX_LISTED = 6;
    private static final int MAX_MESSAGE = 240;

    private final ObjectProvider<TestRunGate> testRunGate;
    private final ObjectProvider<GoalPlannerService> goalPlanner;
    private final ObjectProvider<FileService> fileService;
    private final ObjectProvider<ValueObservationProbe> valueProbe;

    public StagedTestFailureHeuristic(ObjectProvider<TestRunGate> testRunGate,
                                      ObjectProvider<GoalPlannerService> goalPlanner,
                                      ObjectProvider<FileService> fileService,
                                      ObjectProvider<ValueObservationProbe> valueProbe) {
        this.testRunGate = testRunGate;
        this.goalPlanner = goalPlanner;
        this.fileService = fileService;
        this.valueProbe = valueProbe;
    }

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome outcome) {
        if (input == null || outcome == null) return HeuristicOpinion.none(NAME);
        // Only a candidate that is on disk and compiled this cycle can be run.
        BuildOutcome probe = input.lastProbeOutcome();
        if (probe == null || probe.result() != BuildResult.COMPILE_OK || input.lastRollbackPoint() == null) {
            return HeuristicOpinion.none(NAME);
        }
        List<String> stagedTests = stagedTestSources(input);
        if (stagedTests.isEmpty()) return HeuristicOpinion.none(NAME);
        TestRunGate gate = testRunGate.getIfAvailable();
        if (gate == null || !gate.isLive() || !suiteIsDeclared()) return HeuristicOpinion.none(NAME);

        if (gate.verifyTestsPass(input.cycleName() + "-staged-tests").isEmpty()) {
            return HeuristicOpinion.none(NAME);
        }
        Optional<TestRunReport> report = gate.freshReport();
        if (report.isEmpty()) return HeuristicOpinion.none(NAME);   // no per-test evidence: abstain

        List<TestRunReport.Failure> own = ownFailures(report.get().failing(), stagedTests);
        if (own.isEmpty()) return HeuristicOpinion.none(NAME);

        String rationale = rationale(own) + observed(own, input.cycleName());
        log.info("{}: {}", NAME, rationale);
        return HeuristicOpinion.ofDefectWithScore(NAME, 0.9, MutationAcceptanceState.REJECTED,
                GoalProgressState.STAGNATING, rationale, Math.max(0.0, outcome.score() - 0.10));
    }

    /**
     * What the entry points these failing tests call actually returned, observed by running each
     * test up to the call: a failure message names one value, the observation names all of them.
     */
    private String observed(List<TestRunReport.Failure> own, String cycleName) {
        ValueObservationProbe probe = valueProbe.getIfAvailable();
        FileService files = fileService.getIfAvailable();
        if (probe == null || files == null) return "";
        try {
            List<String> obligations = files.readFile(SeedObligationsFile.PATH).lines()
                    .filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
            var observations = probe.observeFailing(own, obligations, cycleName + "-staged-observe");
            if (observations.isEmpty()) return "";
            return " Observed by running each failing test up to the call: " + observations.stream()
                    .map(ValueObservationProbe.Observation::describe).reduce((a, b) -> a + "; " + b).orElse("")
                    + ". Assert these values, every field you assert.";
        } catch (Exception e) {
            return "";
        }
    }

    private static List<String> stagedTestSources(CycleContextData input) {
        List<String> out = new ArrayList<>();
        if (input.projectDiff() == null || input.projectDiff().changedFiles() == null) return out;
        for (String path : input.projectDiff().changedFiles().keySet()) {
            String p = path.replace('\\', '/');
            if (p.contains("src/test/") && p.endsWith(".java")) out.add(p);
        }
        return out;
    }

    private boolean suiteIsDeclared() {
        GoalPlannerService planner = goalPlanner.getIfAvailable();
        FileService files = fileService.getIfAvailable();
        if (planner == null || files == null) return false;
        GoalEntity root = planner.getRootGoal();
        if (root == null) return false;
        try {
            if (!files.exists(SeedObligationsFile.PATH)) return false;
            return !SeedRequirements.answeredByGate(root.getDescription(),
                    files.readFile(SeedObligationsFile.PATH), SeedRequirements.GATE_TESTS).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** The failures whose test class is one of the staged source files. */
    static List<TestRunReport.Failure> ownFailures(Collection<TestRunReport.Failure> failing,
                                                   Collection<String> stagedPaths) {
        List<TestRunReport.Failure> out = new ArrayList<>();
        if (failing == null) return out;
        for (TestRunReport.Failure f : failing) {
            if (f.className() == null) continue;
            String cls = f.className();
            int nested = cls.indexOf('$');
            if (nested >= 0) cls = cls.substring(0, nested);
            String suffix = "/" + cls.replace('.', '/') + ".java";
            if (stagedPaths.stream().anyMatch(p -> ("/" + p).endsWith(suffix))) out.add(f);
        }
        return out;
    }

    static String rationale(List<TestRunReport.Failure> own) {
        StringBuilder sb = new StringBuilder("the tests this change writes fail when run (")
                .append(own.size()).append("); the project's own test suite passing is an obligation. ")
                .append("Each message below shows what the code actually returned -- assert that value: ");
        int listed = 0;
        for (TestRunReport.Failure f : own) {
            if (listed++ == MAX_LISTED) {
                sb.append(" | ... ").append(own.size() - MAX_LISTED).append(" more");
                break;
            }
            String cls = f.className().substring(f.className().lastIndexOf('.') + 1);
            String msg = f.message() == null ? f.outcome().name() : f.message().strip();
            if (msg.length() > MAX_MESSAGE) msg = msg.substring(0, MAX_MESSAGE) + "...";
            sb.append(listed > 1 ? " | " : "").append(cls).append('.').append(f.method()).append(": ").append(msg);
        }
        return sb.toString();
    }
}
