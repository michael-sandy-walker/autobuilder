package com.walpro.autobuilder.clarity;

import com.walpro.autobuilder.analysis.clarity.ClarityEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClarityEvaluatorTest {

    private final ClarityEvaluator evaluator = new ClarityEvaluator();

    @Test
    void testFullStructureAlignment() {
        Map<String, List<String>> structure = Map.of(
            "questions", List.of("What is the purpose?"),
            "paths", List.of("Explore options"),
            "edges", List.of("Uncertainty", "Possibility"),
            "symbols", List.of("⚙", "🌱"),
            "directions", List.of("North", "Forward"),
            "relationships", List.of("Cause → Effect"),
            "movements", List.of("Shift", "Ripple")
        );

        ClarityEvaluator.ClarityScore score = evaluator.evaluate(structure);

        assertEquals(1.0, score.score(), 0.001);
        assertTrue(score.signals().contains("✅ Questions present"));
        assertTrue(score.signals().contains("✅ Recursive flow: movements → questions"));
    }

    @Test
    void testMissingQuestions() {
        Map<String, List<String>> structure = Map.of(
            "paths", List.of("Go east"),
            "symbols", List.of("🧭"),
            "relationships", List.of("X → Y"),
            "movements", List.of("Step", "Push")
        );

        ClarityEvaluator.ClarityScore score = evaluator.evaluate(structure);

        assertTrue(score.score() < 1.0);
        assertTrue(score.signals().contains("⚠️ No guiding questions"));
    }

    @Test
    void testEmptyStructure() {
        Map<String, List<String>> emptyStructure = Map.of();
        ClarityEvaluator.ClarityScore score = evaluator.evaluate(emptyStructure);

        assertEquals(0.0, score.score(), 0.001);
        assertEquals(4, score.signals().size());
        assertTrue(score.signals().stream().allMatch(s -> s.startsWith("⚠️")));
    }
}
