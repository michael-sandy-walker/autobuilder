package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.inject.FixProposal;
import com.walpro.autobuilder.core.build.inject.InjectorFixRecord;
import com.walpro.autobuilder.core.grounding.SymbolGroundingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T2-EF: run-level dominance over genuinely-missing TYPES. "Missing" is sourced from the compiler's
 * grammar ({@code symbol: class|interface}) and the structural symbol model (genuinely undefined),
 * not scraped from the diagnostic string. Trigger is relative (strict majority + spans ≥2 subgoals).
 */
class RecurringDefectTrackerTest {

    RecurringDefectTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new RecurringDefectTracker();
    }

    /** A {@code cannot find symbol / symbol: class NAME} stderr — names NAME as a missing type. */
    private static BuildOutcome missingType(String name) {
        String stderr = "[ERROR] /x/src/main/java/com/example/demo/error/ErrorHandler.java:[12,8] cannot find symbol\n"
                + "  symbol:   class " + name + "\n  location: class com.example.demo.error.ErrorHandler";
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
    void fires_whenOneTypeIsMajorityAcrossTwoSubgoals() {
        tracker.observe(missingType("ErrorResponse"), List.of(), "sg-1", "c1");
        tracker.observe(missingType("ErrorResponse"), List.of(), "sg-1", "c1");
        tracker.observe(missingType("ErrorResponse"), List.of(), "sg-2", "c1"); // 3/4, spans sg-1+sg-2
        tracker.observe(missingType("Widget"), List.of(), "sg-1", "c1");          // minority

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
        tracker.observe(missingType("ErrorResponse"), List.of(), "sg-1", "c1");
        tracker.observe(missingType("ErrorResponse"), List.of(), "sg-1", "c1");
        tracker.observe(missingType("ErrorResponse"), List.of(), "sg-1", "c1");
        assertThat(tracker.dominantDefect()).isEmpty();
    }

    @Test
    void notFired_withoutStrictMajority() {
        tracker.observe(missingType("ErrorResponse"), List.of(), "sg-1", "c1");
        tracker.observe(missingType("ErrorResponse"), List.of(), "sg-2", "c1");
        tracker.observe(missingType("Widget"), List.of(), "sg-1", "c1");
        tracker.observe(missingType("Widget"), List.of(), "sg-2", "c1");
        assertThat(tracker.dominantDefect()).isEmpty();
    }

    @Test
    void methodResolutionError_isNotAMissingType() {
        // The 2026-06-15 mis-fire: ResponseEntity-builder / missing-method shapes name a METHOD, not
        // a type — the grammar excludes them, so they never drive the create-type redirect.
        String stderr = "[ERROR] LoanService.java:[27,25] cannot find symbol\n"
                + "  symbol:   method bookExists(java.lang.Long)\n"
                + "  location: variable bookService of type com.example.demo.BookService";
        BuildOutcome o = new BuildOutcome(BuildResult.COMPILE_ERROR, stderr, 100L, "mvn compile", "c");
        tracker.observe(o, List.of(), "sg-1", "c1");
        tracker.observe(o, List.of(), "sg-2", "c1");
        tracker.observe(o, List.of(), "sg-3", "c1");
        assertThat(tracker.dominantDefect()).isEmpty();
    }

    @Test
    void symbolModel_gatesAlreadyDefinedTypes() {
        // ErrorResponse is a defined (e.g. nested) type → not missing → gated. The genuinely-missing
        // MemberNotFoundException is what the run is actually stuck on, so it's what should dominate.
        SymbolGroundingService svc = mock(SymbolGroundingService.class);
        when(svc.definedSymbolsInOutput()).thenReturn(Set.of("ErrorResponse"));
        tracker.setSymbolGroundingService(svc); // symbolAllowlisted defaults false on the mock

        tracker.observe(missingType("ErrorResponse"), List.of(), "sg-1", "c1");
        tracker.observe(missingType("ErrorResponse"), List.of(), "sg-2", "c1");
        tracker.observe(missingType("MemberNotFoundException"), List.of(), "sg-1", "c1");
        tracker.observe(missingType("MemberNotFoundException"), List.of(), "sg-2", "c1");
        tracker.observe(missingType("MemberNotFoundException"), List.of(), "sg-3", "c1");

        Optional<RecurringDefectTracker.DominantDefect> d = tracker.dominantDefect();
        assertThat(d).isPresent();
        assertThat(d.get().missingToken()).isEqualTo("MemberNotFoundException");
    }

    @Test
    void greenProbe_marksHadGreenFix() {
        tracker.observe(missingType("ErrorResponse"), List.of(), "sg-1", "c1");
        tracker.observe(missingType("ErrorResponse"), List.of(), "sg-2", "c1");
        tracker.observe(missingType("ErrorResponse"), List.of(), "sg-2", "c1");

        FixProposal greenFix = new FixProposal(
                List.of(new FileMutation("X.java", "import com.example.demo.error.ErrorResponse;")),
                new InjectorFixRecord("symbol-import:local", BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL,
                        "ErrorResponse", List.of("X.java"), "add import"));
        tracker.observe(green(), List.of(greenFix), "sg-2", "c1");

        Optional<RecurringDefectTracker.DominantDefect> d = tracker.dominantDefect();
        assertThat(d).isPresent();
        assertThat(d.get().hadGreenFix()).isTrue();
    }

    @Test
    void clear_resetsState() {
        tracker.observe(missingType("ErrorResponse"), List.of(), "sg-1", "c1");
        tracker.observe(missingType("ErrorResponse"), List.of(), "sg-2", "c1");
        tracker.clear();
        assertThat(tracker.dominantDefect()).isEmpty();
    }
}
