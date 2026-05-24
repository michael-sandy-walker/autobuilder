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
                .lastAuthorErrors(new CompileAuthorErrors(subgoalId, diags))
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
    void subgoalShift_noBlock_noSignal() {
        CycleContextData base = ctxWith("sg-A", List.of(
                new ClassifiedDiagnostic("Foo.java", 1, 1,
                        "non-static method bar(Long) cannot be referenced from a static context")));
        CycleContextData shifted = base.withCurrentSubGoalId("sg-B");

        CompileDiagnosticPromptBlock.RenderResult r = CompileDiagnosticPromptBlock.evaluate(shifted);

        assertThat(r.blockText()).isNull();
        assertThat(r.fires()).isFalse();
        assertThat(r.suppressed()).isFalse();
        assertThat(r.suppressionReason()).isEqualTo("subgoal-shift");
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

    @Test
    void capEnforcement_top5BySeverity_truncationFooter() {
        List<ClassifiedDiagnostic> twelve = new ArrayList<>();
        // 4 static-context (highest severity)
        for (int i = 0; i < 4; i++) {
            twelve.add(new ClassifiedDiagnostic("S" + i + ".java", 10 + i, 1,
                    "non-static method m() cannot be referenced from a static context"));
        }
        // 4 incompatible-types
        for (int i = 0; i < 4; i++) {
            twelve.add(new ClassifiedDiagnostic("I" + i + ".java", 20 + i, 1,
                    "incompatible types: int cannot be converted to String"));
        }
        // 4 unreachable-statement (lowest of the three)
        for (int i = 0; i < 4; i++) {
            twelve.add(new ClassifiedDiagnostic("U" + i + ".java", 30 + i, 1, "unreachable statement"));
        }

        CycleContextData ctx = ctxWith("sg-A", twelve);

        CompileDiagnosticPromptBlock.RenderResult r = CompileDiagnosticPromptBlock.evaluate(ctx);

        assertThat(r.fires()).isTrue();
        assertThat(r.errorCount()).isEqualTo(12);
        assertThat(r.rendered()).isEqualTo(5);
        // Should render all 4 static-context + 1 incompatible-types (top 5 by severity).
        assertThat(r.blockText())
                .contains("S0.java:10")
                .contains("S1.java:11")
                .contains("S2.java:12")
                .contains("S3.java:13")
                .contains("I0.java:20")
                .doesNotContain("U0.java")
                .contains("... and 7 more errors omitted; fix the above first.");
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
