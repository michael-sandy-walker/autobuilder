package com.walpro.autobuilder.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The output bound learned per role: a clean cut grows the cap additively, a runaway halves it down to what the
 * role's own answers needed, and only an answer whose tail repeats itself is a runaway.
 */
class OutputBoundsTest {

    /** The shape the probe found (parameterized-rows, prompt A): one import line repeated to the cap. */
    static String runaway() {
        StringBuilder sb = new StringBuilder("```java [src/main/java/org/ink/Rows.java]\npackage org.ink;\n\n");
        for (String n : new String[]{"AssignExpr", "BinaryExpr", "CastExpr", "UnaryExpr"}) {
            sb.append("import org.ink.expr.").append(n).append(";\n");
        }
        for (int i = 0; i < 400; i++) sb.append("import org.ink.expr.VariableDeclarationExpr;\n");
        return sb.toString();
    }

    static String clean(int lines) {
        StringBuilder sb = new StringBuilder("```java [src/main/java/org/ink/Rows.java]\npackage org.ink;\nclass Rows {\n");
        for (int i = 0; i < lines; i++) sb.append("    int row").append(i).append("() { return ").append(i).append("; }\n");
        return sb.toString();
    }

    @Test
    void onlyAnAnswerTheCapCutWhileItRepeatedItselfIsARunaway() {
        assertThat(OutputBounds.verdict("stop", runaway())).as("the model ended it").isEqualTo(OutputBounds.Verdict.ENDED);
        assertThat(OutputBounds.verdict("length", runaway())).isEqualTo(OutputBounds.Verdict.RUNAWAY);
        assertThat(OutputBounds.verdict("length", clean(300))).isEqualTo(OutputBounds.Verdict.CUT_CLEAN);
        assertThat(OutputBounds.tailRepeats(runaway())).isGreaterThan(0.95);
        assertThat(OutputBounds.tailRepeats(clean(300))).isZero();
    }

    @Test
    void aCleanCutGrowsTheCapByHalfAReserve_aRunawayHalvesItDownToWhatTheRoleNeeded() {
        OutputBounds b = new OutputBounds(4096);
        assertThat(b.capFor("evolution")).as("the first cap is the reserve").isEqualTo(4096);

        assertThat(b.learn("evolution", OutputBounds.Verdict.CUT_CLEAN, 4096, 90_000)).isEqualTo(6144);
        assertThat(b.learn("evolution", OutputBounds.Verdict.CUT_CLEAN, 6144, 130_000)).isEqualTo(8192);
        assertThat(b.learn("evolution", OutputBounds.Verdict.ENDED, 5193, 110_000))
                .as("an ended answer leaves the cap").isEqualTo(8192);
        assertThat(b.learn("evolution", OutputBounds.Verdict.RUNAWAY, 8192, 170_000))
                .as("halved, but not below the longest ended answer plus half a reserve").isEqualTo(7241);
        assertThat(b.learn("evolution", OutputBounds.Verdict.RUNAWAY, 7241, 160_000)).isEqualTo(7241);
        assertThat(b.capFor("planner")).as("each role learns its own").isEqualTo(4096);
    }

    @Test
    void aRunawayNeverTakesTheCapBelowTheReserve() {
        OutputBounds b = new OutputBounds(4096);
        assertThat(b.learn("analysis", OutputBounds.Verdict.RUNAWAY, 4096, 90_000)).isEqualTo(4096);
        assertThat(b.learn("analysis", OutputBounds.Verdict.CUT_CLEAN, 4096, 90_000)).isEqualTo(6144);
        assertThat(b.learn("analysis", OutputBounds.Verdict.RUNAWAY, 6144, 130_000)).as("the growth is undone").isEqualTo(4096);
    }

    @Test
    void theTimeoutFollowsTheCapAtTheRunsOwnPace_neverBelowTheBase() {
        OutputBounds b = new OutputBounds(4096);
        assertThat(b.timeoutFor(16_384, 120_000)).as("nothing observed yet").isEqualTo(120_000);
        b.learn("evolution", OutputBounds.Verdict.ENDED, 3840, 76_800);   // 0.02 s per token
        assertThat(b.timeoutFor(8192, 120_000)).isEqualTo(245_760);       // 8192 x 0.02 x 1.5
        assertThat(b.timeoutFor(1000, 120_000)).isEqualTo(120_000);
    }
}
