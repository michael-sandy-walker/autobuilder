package com.walpro.autobuilder.protocol.evaluation;

import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-AN-1: package-private tests for the {@code --- BUILD RESULT ---} block emitted by
 * {@link EvaluationProtocolContract#buildResultBlock(CycleContextData)}.
 */
class EvaluationProtocolContractBuildBlockTest {

    @Test
    void buildResultBlock_nullOutcome_returnsNull() {
        CycleContextData ctx = CycleContextData.builder().cycleName("c").build();
        assertThat(EvaluationProtocolContract.buildResultBlock(ctx)).isNull();
    }

    @Test
    void buildResultBlock_compileOk_returnsNull() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c")
                .buildOutcome(new BuildOutcome(BuildResult.COMPILE_OK, "", 100L, "mvn compile", "c"))
                .build();
        assertThat(EvaluationProtocolContract.buildResultBlock(ctx)).isNull();
    }

    @Test
    void buildResultBlock_skipped_returnsNull() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c")
                .buildOutcome(BuildOutcome.skipped("disabled"))
                .build();
        assertThat(EvaluationProtocolContract.buildResultBlock(ctx)).isNull();
    }

    @Test
    void buildResultBlock_compileError_includesResultAndStderr() {
        BuildOutcome failed = new BuildOutcome(
                BuildResult.COMPILE_ERROR,
                "[ERROR] cannot find symbol\n[ERROR] symbol: class Foo\n[ERROR] location: class Bar",
                1234L,
                "mvn compile",
                "c"
        );
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c")
                .buildOutcome(failed)
                .build();
        String block = EvaluationProtocolContract.buildResultBlock(ctx);

        assertThat(block).isNotNull();
        assertThat(block)
                .contains("--- BUILD RESULT ---")
                .contains("Result: COMPILE_ERROR")
                .contains("Command: mvn compile")
                .contains("Duration: 1234ms")
                .contains("cannot find symbol")
                .contains("symbol: class Foo")
                .contains("does not compile");
    }

    @Test
    void buildResultBlock_timeout_includesResultLine() {
        BuildOutcome timed = new BuildOutcome(BuildResult.TIMEOUT, "(no stderr captured)", 120_000L, "mvn compile", "c");
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c")
                .buildOutcome(timed)
                .build();
        String block = EvaluationProtocolContract.buildResultBlock(ctx);

        assertThat(block).isNotNull();
        assertThat(block)
                .contains("Result: TIMEOUT")
                .contains("Duration: 120000ms");
    }

    @Test
    void buildResultBlock_truncatesStderrTo20Lines() {
        StringBuilder bigStderr = new StringBuilder();
        for (int i = 1; i <= 50; i++) {
            bigStderr.append("line-").append(i).append("\n");
        }
        BuildOutcome failed = new BuildOutcome(BuildResult.COMPILE_ERROR, bigStderr.toString().trim(), 100L, "mvn compile", "c");
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c")
                .buildOutcome(failed)
                .build();
        String block = EvaluationProtocolContract.buildResultBlock(ctx);

        assertThat(block).isNotNull();
        // Last 20 lines should be present; earlier ones should not.
        assertThat(block).contains("line-50").contains("line-31");
        assertThat(block).doesNotContain("line-1\n").doesNotContain("line-30\n");
    }
}
