package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.inject.FixProposal;
import com.walpro.autobuilder.core.build.inject.InjectorFixRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2-EF: run-level dominance accounting, decoupled from subgoal identity. The trigger is relative
 * (strict majority of residual failed probes + spans ≥2 subgoals) — no fixed N.
 */
class RecurringDefectTrackerTest {

    RecurringDefectTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new RecurringDefectTracker();
    }

    /** A CANNOT_FIND_SYMBOL stderr naming {@code symbol} — keys to {@code CANNOT_FIND_SYMBOL|symbol}. */
    private static BuildOutcome err(String symbol) {
        String stderr = "[ERROR] /x/src/main/java/com/example/demo/error/ErrorHandler.java:[12,8] cannot find symbol\n"
                + "  symbol:   class " + symbol + "\n  location: class com.example.demo.error.ErrorHandler";
        return new BuildOutcome(BuildResult.COMPILE_ERROR, stderr, 100L, "mvn compile", "c");
    }

    private static BuildOutcome green() {
        return new BuildOutcome(BuildResult.COMPILE_OK, "", 50L, "mvn compile", "c");
    }

    @Test
    void empty_whenNoFailures() {
        assertThat(tracker.dominantDefect()).isEmpty();
    }

    @Test
    void fires_whenOneShapeIsMajorityAcrossTwoSubgoals() {
        tracker.observe(err("ErrorResponse"), List.of(), "sg-1");
        tracker.observe(err("ErrorResponse"), List.of(), "sg-1");
        tracker.observe(err("ErrorResponse"), List.of(), "sg-2"); // 3/4, spans sg-1 + sg-2
        tracker.observe(err("Widget"), List.of(), "sg-1");          // minority

        Optional<RecurringDefectTracker.DominantDefect> d = tracker.dominantDefect();
        assertThat(d).isPresent();
        assertThat(d.get().errorKind()).isEqualTo("CANNOT_FIND_SYMBOL");
        assertThat(d.get().missingToken()).isEqualTo("ErrorResponse");
        assertThat(d.get().subgoalSpan()).isEqualTo(2);
        assertThat(d.get().share()).isGreaterThan(0.5);
        assertThat(d.get().hadGreenFix()).isFalse();
    }

    @Test
    void notFired_whenConfinedToOneSubgoal() {
        // Dominates the count, but the recurrence does not span subgoals — this is exactly the
        // tight within-subgoal case T2-DD's per-subgoal streak already handles, so the run-level
        // trigger must stay quiet.
        tracker.observe(err("ErrorResponse"), List.of(), "sg-1");
        tracker.observe(err("ErrorResponse"), List.of(), "sg-1");
        tracker.observe(err("ErrorResponse"), List.of(), "sg-1");
        assertThat(tracker.dominantDefect()).isEmpty();
    }

    @Test
    void notFired_withoutStrictMajority() {
        // Two co-equal defects (each 0.5) — neither is "more than all others combined", and a
        // targeted redirection to one would be ill-defined, so the trigger stays quiet.
        tracker.observe(err("ErrorResponse"), List.of(), "sg-1");
        tracker.observe(err("ErrorResponse"), List.of(), "sg-2");
        tracker.observe(err("Widget"), List.of(), "sg-1");
        tracker.observe(err("Widget"), List.of(), "sg-2");
        assertThat(tracker.dominantDefect()).isEmpty();
    }

    @Test
    void greenProbe_marksHadGreenFix() {
        tracker.observe(err("ErrorResponse"), List.of(), "sg-1");
        tracker.observe(err("ErrorResponse"), List.of(), "sg-2");
        tracker.observe(err("ErrorResponse"), List.of(), "sg-2");

        // A deterministic fix greened a probe for ErrorResponse — the import is right; the gap
        // is the symbol's existence/placement (the knowledge that enriches the redirect hint).
        FixProposal greenFix = new FixProposal(
                List.of(new FileMutation("X.java", "import com.example.demo.error.ErrorResponse;")),
                new InjectorFixRecord("symbol-import:local", BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL,
                        "ErrorResponse", List.of("X.java"), "add import"));
        tracker.observe(green(), List.of(greenFix), "sg-2");

        Optional<RecurringDefectTracker.DominantDefect> d = tracker.dominantDefect();
        assertThat(d).isPresent();
        assertThat(d.get().hadGreenFix()).isTrue();
    }

    @Test
    void clear_resetsState() {
        tracker.observe(err("ErrorResponse"), List.of(), "sg-1");
        tracker.observe(err("ErrorResponse"), List.of(), "sg-2");
        tracker.clear();
        assertThat(tracker.dominantDefect()).isEmpty();
    }
}
