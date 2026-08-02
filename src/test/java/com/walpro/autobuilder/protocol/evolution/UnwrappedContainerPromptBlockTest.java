package com.walpro.autobuilder.protocol.evolution;

import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** T2-FD: tests for {@link UnwrappedContainerPromptBlock}. */
class UnwrappedContainerPromptBlockTest {

    private static CycleContextData ctxFor(String stderr) {
        return CycleContextData.builder().cycleName("c")
                .lastProbeOutcome(new BuildOutcome(BuildResult.COMPILE_ERROR, stderr, 1L, "mvn", "c"))
                .build();
    }

    @Test
    void render_nullCtx_returnsNull() {
        assertThat(UnwrappedContainerPromptBlock.render(null)).isNull();
    }

    @Test
    void render_compileOk_returnsNull() {
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .lastProbeOutcome(new BuildOutcome(BuildResult.COMPILE_OK, "", 1L, "mvn", "c"))
                .build();
        assertThat(UnwrappedContainerPromptBlock.render(ctx)).isNull();
    }

    @Test
    void render_theFieldShapeThatLostAProposal() {
        // Verbatim from run 2026-08-02_17-05-13, the run that proved T1-FR and then lost its
        // compliant proposal to this diagnostic with nothing steering it.
        String stderr = String.join("\n",
                "[ERROR] /home/u/p/firestarter/output/src/main/java/com/example/demo/service/"
                        + "LoanService.java:[37,17] cannot find symbol",
                "[ERROR]   symbol:   method orElseThrow(()->new Lo[...]Id()))",
                "[ERROR]   location: class com.example.demo.model.Loan");

        String block = UnwrappedContainerPromptBlock.render(ctxFor(stderr));

        assertThat(block).contains("--- UNWRAPPED VALUE — FIX THE CALL, NOT THE TYPE ---");
        assertThat(block).contains("orElseThrow() chained onto a plain Loan");
        assertThat(block).contains("src/main/java/com/example/demo/service/LoanService.java");
        assertThat(block).doesNotContain("/home/");
    }

    @Test
    void render_saysNotToAddTheMethod_theOppositeOfTheMemberBlock() {
        String stderr = String.join("\n",
                "[ERROR] /p/src/main/java/A.java:[1,1] cannot find symbol",
                "[ERROR]   symbol:   method isPresent()",
                "[ERROR]   location: class com.example.Book");

        String block = UnwrappedContainerPromptBlock.render(ctxFor(stderr));

        assertThat(block).contains("Do NOT add");
        assertThat(MissingMemberPromptBlock.render(ctxFor(stderr))).isNull();
    }

    @Test
    void render_namedReceiver_isNotThisShape() {
        // A named receiver means the object really is of that type, so the member genuinely is
        // missing — that is the member block's case, and this one must stay quiet.
        String stderr = String.join("\n",
                "[ERROR] /p/src/main/java/A.java:[1,1] cannot find symbol",
                "[ERROR]   symbol:   method isEmpty()",
                "[ERROR]   location: variable cart of type com.example.Cart");

        assertThat(UnwrappedContainerPromptBlock.render(ctxFor(stderr))).isNull();
    }

    @Test
    void render_ordinaryMissingMethod_isNotThisShape() {
        String stderr = String.join("\n",
                "[ERROR] /p/src/main/java/A.java:[1,1] cannot find symbol",
                "[ERROR]   symbol:   method findByIsbn(java.lang.String)",
                "[ERROR]   location: interface com.example.BookRepository");

        assertThat(UnwrappedContainerPromptBlock.render(ctxFor(stderr))).isNull();
    }

    @Test
    void render_severalCalls_areDedupedByTypeAndMethod() {
        String one = String.join("\n",
                "[ERROR] /p/src/main/java/A.java:[1,1] cannot find symbol",
                "[ERROR]   symbol:   method orElseThrow()",
                "[ERROR]   location: class com.example.Loan");
        String block = UnwrappedContainerPromptBlock.render(ctxFor(one + "\n" + one));

        assertThat(block.split("orElseThrow\\(\\) chained")).hasSize(2);
    }
}
