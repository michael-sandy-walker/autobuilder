package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Detects when test files were changed but the evaluation feedback suggests failures or
 * assertion mismatches — i.e., the generated code and its tests are incoherent.
 *
 * <p>This heuristic cannot execute code. It works by examining:
 * <ol>
 *   <li>Whether test files are present in the diff (file names ending in Test.java / *Tests.java)
 *   <li>Whether the evaluation feedback or outcome contains language indicating test failures
 *       or assertion mismatches
 * </ol>
 *
 * <p>When both signals are present the heuristic penalises the score and marks the mutation
 * as STAGNATING, so the evaluator doesn't accept incoherent code.
 */
@Component
public class TestCoherenceHeuristic implements EvaluationHeuristic {

    private static final String NAME = "TestCoherenceHeuristic";

    private static final Pattern TEST_FAILURE_PATTERN = Pattern.compile(
            "test.*fail|fail.*test|assert.*error|assertionerror|" +
            "expected.*but.*was|does not match|mismatch|" +
            "compilation.*error|does not compile|cannot compile|" +
            "will fail|would fail|test.*broken|broken.*test",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome outcome) {
        if (input == null || outcome == null) return HeuristicOpinion.none(NAME);
        if (input.projectDiff() == null) return HeuristicOpinion.none(NAME);

        boolean testFilesChanged = input.projectDiff().changedFiles() != null
                && input.projectDiff().changedFiles().keySet().stream()
                        .anyMatch(path -> path.endsWith("Test.java") || path.endsWith("Tests.java")
                                || path.contains("/test/"));

        if (!testFilesChanged) return HeuristicOpinion.none(NAME);

        String feedback = input.evaluationFeedback() != null ? input.evaluationFeedback() : "";
        String outcomeFeedback = outcome.feedbackMessage() != null ? outcome.feedbackMessage() : "";
        String combined = feedback + " " + outcomeFeedback;

        boolean failureSignalsPresent = TEST_FAILURE_PATTERN.matcher(combined).find();

        if (!failureSignalsPresent) return HeuristicOpinion.none(NAME);

        String rationale = "Test files changed and evaluation feedback indicates test failures or " +
                "assertion mismatches — accepting this mutation would embed incoherent code.";

        return HeuristicOpinion.ofWithScore(
                NAME,
                0.80,
                MutationAcceptanceState.REJECTED,
                GoalProgressState.STAGNATING,
                rationale,
                Math.min(outcome.score() != null ? outcome.score() * 0.6 : 0.4, 0.5)
        );
    }
}
