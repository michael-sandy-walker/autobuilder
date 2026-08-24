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
    void aLongHistoryRendersItsTrendAndItsRecord_notEveryEntry() {
        // The residual T1-FS left open: paths are bounded per run, a path's score ARRAY is not.
        // 14 entries is what one file reached in a 90-minute run.
        List<Double> fourteen = List.of(0.10, 0.20, 0.30, 0.40, 0.50, 0.90, 0.45,
                0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80);

        String rendered = EvaluationProtocolContract.renderScoreHistory(fourteen);

        assertThat(rendered).isEqualTo("0.70 -> 0.75 -> 0.80 (latest of 14, best 0.90)");
        assertThat(rendered).doesNotContain("0.10");
    }

    @Test
    void aShortHistoryIsRenderedWhole_withNoSummaryTail() {
        assertThat(EvaluationProtocolContract.renderScoreHistory(List.of(0.4, 0.55)))
                .isEqualTo("0.40 -> 0.55");
        assertThat(EvaluationProtocolContract.renderScoreHistory(List.of(0.4, 0.55, 0.7)))
                .isEqualTo("0.40 -> 0.55 -> 0.70");
    }

    @Test
    void anEmptyHistorySaysSoRatherThanRenderingBrackets() {
        assertThat(EvaluationProtocolContract.renderScoreHistory(List.of())).isEqualTo("(none)");
        assertThat(EvaluationProtocolContract.renderScoreHistory(null)).isEqualTo("(none)");
    }

    @Test
    void theRenderedBlockWidthDoesNotGrowWithTheRunsLength() {
        // The property the residual is about: two runs of very different length produce the same
        // block width for the same file.
        List<Double> shortRun = List.of(0.4, 0.5, 0.6);
        List<Double> longRun = new java.util.ArrayList<>(shortRun);
        for (int i = 0; i < 200; i++) longRun.add(0.6);

        int shortWidth = EvaluationProtocolContract.renderScoreHistory(shortRun).length();
        int longWidth = EvaluationProtocolContract.renderScoreHistory(longRun).length();

        assertThat(longWidth - shortWidth).isLessThan(30);
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
