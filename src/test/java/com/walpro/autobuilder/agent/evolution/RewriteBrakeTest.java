package com.walpro.autobuilder.agent.evolution;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** T6-T: a file rewritten to no effect on the failing tests is changed by edits next. */
class RewriteBrakeTest {

    private static final String PATH = "src/main/java/zeta/Quill.java";

    private static String quill(int from, int to) {
        StringBuilder sb = new StringBuilder("package zeta;\nclass Quill {\n");
        for (int i = from; i < to; i++) sb.append("    int nib").append(i).append("() { return ").append(i).append("; }\n");
        return sb.append("}\n").toString();
    }

    /**
     * Ladder-rows 2026-10-14_18-10-40: the edits that fixed a part replaced 3-19% of the class's lines, the rewrites
     * 26-54%; the line is drawn where nothing fell.
     */
    @Test
    void aVersionReplacingMoreThanAQuarterOfTheLinesIsARewrite_oneEditingAPartIsNot() {
        String before = quill(0, 20);
        assertThat(RewriteBrake.rewrites(before, before.replace("return 3;", "return 33;"))).isFalse();
        assertThat(RewriteBrake.rewrites(before, quill(0, 12) + quill(100, 108))).as("8 of 22 lines replaced").isTrue();
        assertThat(RewriteBrake.rewrites(before, quill(0, 17) + quill(100, 103))).as("3 of 22").isFalse();
    }

    @Test
    void aRewriteThatLeftTheTestsAsTheyWereIsHeld_untilAChangeToItMakesThemBetter() {
        RewriteBrake brake = new RewriteBrake();
        String before = quill(0, 20);
        String rewrite = quill(50, 70);

        brake.tested(Map.of(PATH, before), List.of(new FileMutation(PATH, rewrite)), false);
        assertThat(brake.held()).containsExactly(PATH);
        assertThat(brake.holds(PATH, before, quill(60, 80))).as("another rewrite").isTrue();
        assertThat(brake.holds(PATH, before, before.replace("return 3;", "return 33;"))).as("an edit").isFalse();

        brake.tested(Map.of(PATH, before), List.of(new FileMutation(PATH, before.replace("return 3;", "return 33;"))), true);
        assertThat(brake.held()).isEmpty();
    }

    @Test
    void anEditThatLeftTheTestsAsTheyWereHoldsNothing() {
        RewriteBrake brake = new RewriteBrake();
        String before = quill(0, 20);
        brake.tested(Map.of(PATH, before), List.of(new FileMutation(PATH, before.replace("return 3;", "return 33;"))), false);
        assertThat(brake.held()).isEmpty();
    }

    /** A design that really must be redone is not barred for good. */
    @Test
    void afterFourRewritesAskedAgainTheFileIsReleased() {
        RewriteBrake brake = new RewriteBrake();
        brake.tested(Map.of(PATH, quill(0, 20)), List.of(new FileMutation(PATH, quill(50, 70))), false);
        for (int i = 0; i < RewriteBrake.RELEASE_AFTER - 1; i++) brake.refused(PATH);
        assertThat(brake.held()).containsExactly(PATH);
        brake.refused(PATH);
        assertThat(brake.held()).isEmpty();
    }
}
