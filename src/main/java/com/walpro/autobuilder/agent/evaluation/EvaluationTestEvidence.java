package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.core.build.TestRunGate;
import com.walpro.autobuilder.core.build.TestRunPromptBlock;
import com.walpro.autobuilder.core.build.TestRunReport;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.goal.DeclaredTestSuite;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The project's own test run, shown to the per-cycle evaluator as an OBSERVATION.
 *
 * <p>stuck-questions run 2026-09-26_14-24 merged a correct implementation in its first cycle (the
 * yardstick read 29/29) and never fulfilled the subgoal: its criterion "StuckQuestionsSpecTest passes
 * in full" was put to a model reading source, which answered "Cannot verify test execution status from
 * static analysis", and the unmet-criterion veto held the subgoal open for every later cycle. The
 * completion verifier has seen the test run since 2026-09-24; the evaluator grading the same kind of
 * criterion every cycle never did -- a runtime fact asked of the one reader that cannot observe it.
 *
 * <p>Only where the operator declared the suite an authority ({@link DeclaredTestSuite}), the same
 * condition the staged-test heuristic uses. A report of exactly this tree is reused; otherwise the
 * suite runs once. No report, no block: the prompt is then exactly what it was.
 */
@Slf4j
@Component
public class EvaluationTestEvidence {

    /** Stated with the block, because the evaluator's system prompt is shared with every other caller. */
    static final String RULE = "TEST RUN RULE: the section below is an OBSERVATION of the project's own tests "
            + "executing on exactly the files being judged, not a claim. Judge any criterion about tests running "
            + "or passing from it, never by predicting outcomes from the source.";

    private static final int BUDGET_CHARS = 4_000;

    private final ObjectProvider<TestRunGate> testRunGate;
    private final ObjectProvider<DeclaredTestSuite> declaredSuite;

    public EvaluationTestEvidence(ObjectProvider<TestRunGate> testRunGate, ObjectProvider<DeclaredTestSuite> declaredSuite) {
        this.testRunGate = testRunGate;
        this.declaredSuite = declaredSuite;
    }

    public Optional<String> block(CycleContextData context) {
        TestRunGate gate = testRunGate.getIfAvailable();
        DeclaredTestSuite declared = declaredSuite.getIfAvailable();
        if (context == null || gate == null || !gate.isLive() || declared == null || !declared.isDeclared()) {
            return Optional.empty();
        }
        Optional<TestRunReport> report = gate.freshReport();
        if (report.isEmpty()) {
            gate.verifyTestsPass(context.cycleName() + "-eval-tests");
            report = gate.freshReport();
        }
        if (report.isEmpty()) return Optional.empty();
        List<String> shown = new ArrayList<>();
        if (context.projectDiff() != null && context.projectDiff().changedFiles() != null) {
            shown.addAll(context.projectDiff().changedFiles().keySet());
        }
        String rendered = TestRunPromptBlock.render(report.get(), shown, BUDGET_CHARS);
        if (rendered == null || rendered.isBlank()) return Optional.empty();
        log.info("EvaluationTestEvidence: the evaluator is shown the project's test run ({} chars)", rendered.length());
        return Optional.of(RULE + "\n" + rendered);
    }
}
