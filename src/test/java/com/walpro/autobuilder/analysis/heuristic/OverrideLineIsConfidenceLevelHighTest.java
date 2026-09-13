package com.walpro.autobuilder.analysis.heuristic;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2-FJ: the line at which a heuristic's confidence flips the mutation state is
 * {@link ConfidenceLevel#HIGH}, and it has exactly one home.
 *
 * <p>Three heuristics used to spell that line as the literal {@code 0.75}. That is also the value
 * of a rung in {@code ScoreRegressionHeuristic}'s own confidence ladder, so the rung meaning
 * "override" and the line performing the override were the same number by coincidence rather than
 * by reference — and {@code confidenceScale} could push the former below the latter.
 */
class OverrideLineIsConfidenceLevelHighTest {

    private static final Path HEURISTIC_DIR =
            Path.of("src/main/java/com/walpro/autobuilder/analysis/heuristic");

    @Test
    void noHeuristicSpellsTheOverrideLineAsALiteral() throws IOException {
        try (Stream<Path> files = Files.list(HEURISTIC_DIR)) {
            List<String> offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains("confidence >= 0.75");
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(p -> p.getFileName().toString())
                    .toList();

            assertThat(offenders)
                    .as("the override line belongs to ConfidenceLevel.HIGH, not to a literal")
                    .isEmpty();
        }
    }

    @Test
    void highIsTheOverrideLineAndTheLadderRungThatMeansOverride() {
        // Named here so that moving ConfidenceLevel.HIGH is a decision someone makes on purpose:
        // it simultaneously moves every heuristic's override line AND ScoreRegression's ladder rung.
        assertThat(ConfidenceLevel.HIGH.threshold()).isEqualTo(0.75);
        assertThat(ConfidenceLevel.fromScore(ConfidenceLevel.HIGH.threshold()))
                .isEqualTo(ConfidenceLevel.HIGH);
        assertThat(ConfidenceLevel.fromScore(ConfidenceLevel.HIGH.threshold() - 1e-9))
                .isEqualTo(ConfidenceLevel.MEDIUM);
    }
}
