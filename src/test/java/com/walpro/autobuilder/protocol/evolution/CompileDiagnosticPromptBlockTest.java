package com.walpro.autobuilder.protocol.evolution;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.mutation.BlockerExtractor.ClassifiedDiagnostic;
import com.walpro.autobuilder.core.mutation.CompileAuthorErrors;

import static org.assertj.core.api.Assertions.assertThat;

/** T2-DT: tests for {@link CompileDiagnosticPromptBlock}. */
class CompileDiagnosticPromptBlockTest {

    private static CycleContextData ctxWith(String subgoalId, List<ClassifiedDiagnostic> diags) {
        return CycleContextData.builder()
                .cycleName("cycle-1")
                .currentSubGoalId(subgoalId)
                .lastAuthorErrors(new CompileAuthorErrors(diags))
                .build();
    }

    @Test
    void staticContext_steeringFiresWithHint() {
        CycleContextData ctx = ctxWith("sg-A", List.of(
                new ClassifiedDiagnostic("LoanService.java", 64, 18,
                        "non-static method getBookById(Long) cannot be referenced from a static context")));

        CompileDiagnosticPromptBlock.RenderResult r = CompileDiagnosticPromptBlock.evaluate(ctx);

        assertThat(r.fires()).isTrue();
        assertThat(r.blockText())
                .contains("--- PREVIOUS COMPILE ERRORS (your last attempt was rolled back) ---")
                .contains("LoanService.java:64")
                .contains("cannot be referenced from a static context")
                .contains("Hint: class is instance-scoped");
        assertThat(r.errorCount()).isEqualTo(1);
        assertThat(r.rendered()).isEqualTo(1);
        assertThat(r.hintsMatched()).isEqualTo(1);
    }

    @Test
    void incompatibleTypes_steeringFiresWithHint() {
        CycleContextData ctx = ctxWith("sg-A", List.of(
                new ClassifiedDiagnostic("BookController.java", 39, 22,
                        "incompatible types: ResponseEntity<Book> cannot be converted to ResponseEntity<Object>")));

        CompileDiagnosticPromptBlock.RenderResult r = CompileDiagnosticPromptBlock.evaluate(ctx);

        assertThat(r.fires()).isTrue();
        assertThat(r.blockText())
                .contains("BookController.java:39")
                .contains("incompatible types: ResponseEntity<Book>")
                .contains("Hint: declared return type");
        assertThat(r.hintsMatched()).isEqualTo(1);
    }

    @Test
    void subgoalShift_doesNotSuppress_steeringStillFires() {
        // T1-EW regression-lock: when T1-DM injects a BUILD_FIX subgoal in response to the
        // same probe rejection that wrote the carrier, the currentSubGoalId changes between
        // the rejection cycle and the next cycle. The carrier is keyed to compile state on
        // disk, not goal state — the subgoal change must not silently disable steering.
        // Pre-T1-EW this returned `suppressionReason=subgoal-shift` with no event emitted.
        CycleContextData base = ctxWith("sg-original", List.of(
                new ClassifiedDiagnostic("Foo.java", 1, 1,
                        "non-static method bar(Long) cannot be referenced from a static context")));
        CycleContextData shifted = base.withCurrentSubGoalId("sg-build-fix-injected");

        CompileDiagnosticPromptBlock.RenderResult r = CompileDiagnosticPromptBlock.evaluate(shifted);

        assertThat(r.fires()).isTrue();
        assertThat(r.blockText())
                .contains("--- PREVIOUS COMPILE ERRORS")
                .contains("Foo.java:1");
        assertThat(r.suppressed()).isFalse();
    }

    @Test
    void mixedAuthorAndDeferrable_onlyAuthorErrorsRendered() {
        // The block consumes only the authorErrors list — by definition DEFERRABLE diagnostics
        // are owned by T4-BE/T4-BF and never appear in this list. Verify the steering renders
        // exactly the author-errors fed in and does not invent or drop entries.
        CycleContextData ctx = ctxWith("sg-A", List.of(
                new ClassifiedDiagnostic("A.java", 10, 1, "incompatible types: int cannot be converted to String"),
                new ClassifiedDiagnostic("B.java", 20, 2, "unreachable statement")));

        CompileDiagnosticPromptBlock.RenderResult r = CompileDiagnosticPromptBlock.evaluate(ctx);

        assertThat(r.fires()).isTrue();
        assertThat(r.rendered()).isEqualTo(2);
        assertThat(r.blockText())
                .contains("A.java:10")
                .contains("B.java:20")
                .doesNotContain("cannot find symbol")
                .doesNotContain("DEFERRABLE");
    }

    @Test
    void hintMiss_diagnosticSurfacesWithoutHint() {
        CycleContextData ctx = ctxWith("sg-A", List.of(
                new ClassifiedDiagnostic("Z.java", 5, 1,
                        "some-novel-author-error-shape we have not seen before")));

        CompileDiagnosticPromptBlock.RenderResult r = CompileDiagnosticPromptBlock.evaluate(ctx);

        assertThat(r.fires()).isTrue();
        assertThat(r.blockText())
                .contains("Z.java:5")
                .contains("some-novel-author-error-shape")
                .doesNotContain("Hint:");
        assertThat(r.hintsMatched()).isEqualTo(0);
        assertThat(r.rendered()).isEqualTo(1);
    }

    // ── T2-DV: bundle identical-shape author-errors + relative cap ────────────

    @Test
    void bundling_twelveIdenticalShapeErrors_collapseToOneEntryWithCountSuffix() {
        // 12 same-shape errors across 12 different files — pre-T2-DV would have consumed all
        // 5 slots with one shape; post-T2-DV collapses to one bundled entry "[×12]".
        List<ClassifiedDiagnostic> twelve = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            twelve.add(new ClassifiedDiagnostic("F" + i + ".java", 10 + i, 1,
                    "non-static method getBookById(Long) cannot be referenced from a static context"));
        }
        CycleContextData ctx = ctxWith("sg-A", twelve);

        CompileDiagnosticPromptBlock.RenderResult r = CompileDiagnosticPromptBlock.evaluate(ctx);

        assertThat(r.fires()).isTrue();
        assertThat(r.errorCount()).isEqualTo(12);
        assertThat(r.rendered()).isEqualTo(1);
        assertThat(r.blockText())
                .contains("F0.java:10")
                .contains("[×12]") // × = ×
                .contains("Hint: class is instance-scoped")
                .doesNotContain("omitted");
    }

    @Test
    void bundling_nineErrorsAcrossThreeShapes_threeEntriesNoTruncation() {
        // 3 shapes × 3 occurrences. distinctShapes=3 → cap = min(8, max(3, 1)) = 3 → all render.
        List<ClassifiedDiagnostic> nine = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            nine.add(new ClassifiedDiagnostic("S" + i + ".java", 10 + i, 1,
                    "non-static method m() cannot be referenced from a static context"));
        }
        for (int i = 0; i < 3; i++) {
            nine.add(new ClassifiedDiagnostic("I" + i + ".java", 20 + i, 1,
                    "incompatible types: int cannot be converted to String"));
        }
        for (int i = 0; i < 3; i++) {
            nine.add(new ClassifiedDiagnostic("U" + i + ".java", 30 + i, 1, "unreachable statement"));
        }
        CycleContextData ctx = ctxWith("sg-A", nine);

        CompileDiagnosticPromptBlock.RenderResult r = CompileDiagnosticPromptBlock.evaluate(ctx);

        assertThat(r.fires()).isTrue();
        assertThat(r.errorCount()).isEqualTo(9);
        assertThat(r.rendered()).isEqualTo(3);
        assertThat(r.blockText())
                .contains("S0.java:10").contains("[×3]")
                .contains("I0.java:20")
                .contains("U0.java:30")
                .doesNotContain("omitted");
    }

    @Test
    void cap_twelveShapesEachOnce_rendersTopEightWithDroppedShapeFooter() {
        // distinctShapes=12 → cap = min(MAX_ENTRIES=8, 12) = 8 → render 8 + footer "4 more distinct error shapes".
        // Use 12 distinct novel shapes (none in HINTS) so severity sort is stable on declaration
        // order of byShape map (Integer.MAX_VALUE for all unknowns → stable LinkedHashMap order).
        List<ClassifiedDiagnostic> twenty = new ArrayList<>();
        for (int s = 0; s < 12; s++) {
            // 1-2 occurrences each so total = 20
            int reps = s < 8 ? 1 : 3;
            for (int i = 0; i < reps; i++) {
                twenty.add(new ClassifiedDiagnostic("F" + s + "_" + i + ".java", 10 + i, 1,
                        "novel-shape-" + s + " has not been seen before"));
            }
        }
        CycleContextData ctx = ctxWith("sg-A", twenty);

        CompileDiagnosticPromptBlock.RenderResult r = CompileDiagnosticPromptBlock.evaluate(ctx);

        assertThat(r.fires()).isTrue();
        assertThat(r.errorCount()).isEqualTo(20);
        assertThat(r.rendered()).isEqualTo(8);
        assertThat(r.blockText())
                .contains("F0_0.java:10")
                .contains("F7_0.java:10")
                .doesNotContain("F8_0.java")
                .contains("... and 4 more distinct error shapes omitted; fix the above first.");
    }

    @Test
    void cap_fiveDistinctShapes_rendersAllFive_noTruncation() {
        // Regression-lock against the old min(8, max(3, distinctShapes/2)) formula:
        // 5 distinct shapes used to cap at 3 (max(3, 5/2)=max(3,2)=3) — truncating valid
        // information even though we have prompt budget for 8. New formula renders all 5.
        List<ClassifiedDiagnostic> five = new ArrayList<>();
        for (int s = 0; s < 5; s++) {
            five.add(new ClassifiedDiagnostic("F" + s + ".java", 10 + s, 1,
                    "novel-shape-" + s + " has not been seen before"));
        }
        CycleContextData ctx = ctxWith("sg-A", five);

        CompileDiagnosticPromptBlock.RenderResult r = CompileDiagnosticPromptBlock.evaluate(ctx);

        assertThat(r.fires()).isTrue();
        assertThat(r.errorCount()).isEqualTo(5);
        assertThat(r.rendered()).isEqualTo(5);
        assertThat(r.blockText()).doesNotContain("omitted");
    }

    @Test
    void cap_threeShapesEachOnce_rendersAllNoTruncation() {
        // distinctShapes=3 → cap = min(MAX_ENTRIES=8, 3) = 3 → all render.
        CycleContextData ctx = ctxWith("sg-A", List.of(
                new ClassifiedDiagnostic("A.java", 1, 1, "unreachable statement"),
                new ClassifiedDiagnostic("B.java", 2, 1, "incompatible types: int cannot be converted to String"),
                new ClassifiedDiagnostic("C.java", 3, 1, "non-static method m() cannot be referenced from a static context")));

        CompileDiagnosticPromptBlock.RenderResult r = CompileDiagnosticPromptBlock.evaluate(ctx);

        assertThat(r.fires()).isTrue();
        assertThat(r.errorCount()).isEqualTo(3);
        assertThat(r.rendered()).isEqualTo(3);
        // Top-by-severity ordering: static-context (rank 0) → incompatible-types (rank 1) → unreachable (rank 4).
        assertThat(r.blockText())
                .contains("C.java:3")
                .contains("B.java:2")
                .contains("A.java:1")
                .doesNotContain("[×") // singletons render without [×N] suffix
                .doesNotContain("omitted");
    }

    @Test
    void noProbeFailure_noBlock() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-1")
                .currentSubGoalId("sg-A")
                .build();

        CompileDiagnosticPromptBlock.RenderResult r = CompileDiagnosticPromptBlock.evaluate(ctx);

        assertThat(r.blockText()).isNull();
        assertThat(r.fires()).isFalse();
        assertThat(r.suppressed()).isFalse();
        assertThat(r.suppressionReason()).isEqualTo("no-author-errors");
    }

    @Test
    void metaAgentSuppress_blockSuppressed_reasonRecorded() {
        CycleContextData ctx = ctxWith("sg-A", List.of(
                new ClassifiedDiagnostic("X.java", 1, 1, "unreachable statement")))
                .withSignal(Signal.of(BasicSignals.STEERING_SUPPRESS, 1.0, 0.2, "meta-test"));

        CompileDiagnosticPromptBlock.RenderResult r = CompileDiagnosticPromptBlock.evaluate(ctx);

        assertThat(r.blockText()).isNull();
        assertThat(r.fires()).isFalse();
        assertThat(r.suppressed()).isTrue();
        assertThat(r.suppressionReason()).isEqualTo("meta-agent");
    }
}
