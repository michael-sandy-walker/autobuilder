package com.walpro.autobuilder.protocol.evaluation;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2-FI, second home. The evaluator's prompt cropped every changed file at 150 lines OR 4000
 * characters, whichever came first — roughly a hundred lines of Java — and then asked the model to
 * SCORE the mutation. The closure verifier's identical cap produced a measured false completion the
 * same day: shown a call site but not the method behind it, the model answered from the requirement
 * text, confidently and wrongly, 5 samples out of 5.
 *
 * <p>This matters more than it looks: the answer to this prompt is {@code llmScore}, the number
 * `T2-FJ` is about. A coarse score formed on a cropped view is two defects wearing one symptom.
 */
class EvaluationFileContentBudgetTest {

    /** A realistic changed file: ~7kB, the size of the clitarget Main.java that was cropped. */
    private static String javaFileOf(int lines) {
        StringBuilder sb = new StringBuilder("package a;\npublic class Main {\n");
        for (int i = 0; i < lines; i++) sb.append("    int field").append(i).append(" = ").append(i).append(";\n");
        sb.append("    // THE-TAIL-MARKER\n}\n");
        return sb.toString();
    }

    @Test
    void aRealisticChangedFileIsShownWhole() {
        String content = javaFileOf(215);
        assertThat(content.length()).isGreaterThan(4_000);   // the old cap would have cut it

        String block = EvaluationProtocolContract.getUserPrompt(context(content), 40_000);

        assertThat(block).contains("THE-TAIL-MARKER").doesNotContain("NOT fully shown");
    }

    /** A pathological file is still bounded, and says so in words a reader cannot miss. */
    @Test
    void aFileBeyondTheBudgetIsTruncatedAndSaysSo() {
        String block = EvaluationProtocolContract.getUserPrompt(context(javaFileOf(20_000)), 40_000);

        assertThat(block).contains("NOT fully shown").doesNotContain("THE-TAIL-MARKER");
    }

    private static CycleContextData context(String content) {
        return CycleContextData.builder()
                .cycleName("cycle-1")
                .currentSubGoalDescription("evaluate")
                .projectDiff(new ProjectDiffResult(null, Map.of("Main.java", content), Set.of()))
                .build();
    }
}
