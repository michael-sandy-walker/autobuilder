package com.walpro.autobuilder.protocol.evaluation;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1-FS follow-on: the file-keyed prompt blocks describe the tree being judged, so a path that is
 * not in that tree is noise the model has to reason around — the failure T1-FS measured, where a
 * 269-path block made the model call a 10-file project "severely fragmented across multiple
 * packages", a description of the union of 71 runs' trees.
 *
 * <p>Scoping the score READS to the current run removed 259 of those 261 paths. The two that
 * survived are this run's own: a mutation is scored per file at evaluation time, BEFORE the
 * accept/reject decision, and the map is carried forward whatever that decision was. Run
 * 2026-08-23_02-12-34 ended with 19 paths of which 2 had never reached the tree.
 */
class EvaluationProtocolContractScoreBlockTest {

    private static final String REAL = "src/main/java/com/example/library/domain/Book.java";
    private static final String NEVER_MERGED =
            "src/main/java/com/example/library/domain/exception/EntityNotFoundException.java";

    private static CycleContextData context(Map<String, String> tree) {
        Map<String, List<Double>> history = new LinkedHashMap<>();
        history.put(REAL, List.of(0.8, 0.75));
        history.put(NEVER_MERGED, List.of(0.8));
        Map<String, Double> deltas = new LinkedHashMap<>();
        deltas.put(REAL, -0.05);
        deltas.put(NEVER_MERGED, 0.8);
        return CycleContextData.builder()
                .cycleName("c")
                .previousOutput(tree)
                .scoreHistoryByFile(history)
                .recentDeltas(deltas)
                .build();
    }

    @Test
    void aScoredFileThatNeverReachedTheTreeIsNotRendered() {
        // The rejected mutation of cycle-1787444599094 staged these files and scored them 0.8; the
        // cycle was REJECTED on a compile probe and they never reached output.
        String prompt = EvaluationProtocolContract.getUserPrompt(context(Map.of(REAL, "class Book {}")));

        assertThat(prompt).contains(REAL);
        assertThat(prompt).doesNotContain(NEVER_MERGED);
    }

    @Test
    void anAbsentFileMapFiltersNothingRatherThanBlankingTheBlock() {
        // Failing open is deliberate: an empty block would be a worse error than an extra line,
        // and the file map is not guaranteed to be populated at every call site.
        String prompt = EvaluationProtocolContract.getUserPrompt(context(null));

        assertThat(prompt).contains(REAL);
        assertThat(prompt).contains(NEVER_MERGED);
    }

    @Test
    void anEmptyFileMapAlsoFiltersNothing() {
        String prompt = EvaluationProtocolContract.getUserPrompt(context(Map.of()));

        assertThat(prompt).contains(NEVER_MERGED);
    }

    @Test
    void theDeltaBlockIsFilteredOnTheSameRule() {
        // Both blocks are keyed by file and both accumulate; filtering one and not the other would
        // leave the phantom visible in the half nobody looked at.
        String prompt = EvaluationProtocolContract.getUserPrompt(context(Map.of(REAL, "class Book {}")));

        int deltasAt = prompt.indexOf("--- RECENT SCORE DELTAS ---");
        assertThat(deltasAt).isGreaterThan(0);
        assertThat(prompt.substring(deltasAt)).doesNotContain(NEVER_MERGED);
    }
}
