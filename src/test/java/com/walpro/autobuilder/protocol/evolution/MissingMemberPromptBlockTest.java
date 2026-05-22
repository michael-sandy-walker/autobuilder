package com.walpro.autobuilder.protocol.evolution;

import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-BB: tests for {@link MissingMemberPromptBlock}. */
class MissingMemberPromptBlockTest {

    @Test
    void render_nullCtx_returnsNull() {
        assertThat(MissingMemberPromptBlock.render(null)).isNull();
    }

    @Test
    void render_noProbeOutcome_returnsNull() {
        CycleContextData ctx = CycleContextData.builder().cycleName("c").build();
        assertThat(MissingMemberPromptBlock.render(ctx)).isNull();
    }

    @Test
    void render_probeCompileOk_returnsNull() {
        BuildOutcome ok = new BuildOutcome(BuildResult.COMPILE_OK, "", 10L, "mvn", "c");
        CycleContextData ctx = CycleContextData.builder().cycleName("c").lastProbeOutcome(ok).build();
        assertThat(MissingMemberPromptBlock.render(ctx)).isNull();
    }

    @Test
    void render_targetTypeNotInOutput_returnsNull() {
        BuildOutcome eo = probeFailure(
                "[ERROR] no suitable constructor found for Book(long,java.lang.String)");
        Map<String, String> prev = new LinkedHashMap<>();
        prev.put("src/main/java/com/example/Other.java", "class Other {}");
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .lastProbeOutcome(eo).previousOutput(prev).build();

        assertThat(MissingMemberPromptBlock.render(ctx)).isNull();
    }

    @Test
    void render_constructor_emitsBlockTargetingExistingFile() {
        BuildOutcome eo = probeFailure(
                "[ERROR] /src/main/java/com/example/LoanService.java:[42,17] " +
                "no suitable constructor found for Book(long,java.lang.String,java.lang.String)");
        Map<String, String> prev = new LinkedHashMap<>();
        prev.put("src/main/java/com/example/Book.java", "class Book {}");
        prev.put("src/main/java/com/example/LoanService.java", "class LoanService {}");
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .lastProbeOutcome(eo).previousOutput(prev).build();

        String block = MissingMemberPromptBlock.render(ctx);

        assertThat(block).isNotNull();
        assertThat(block).contains("--- MISSING MEMBER — ADD TO Book.java ---");
        assertThat(block).contains("src/main/java/com/example/Book.java");
        assertThat(block).contains("constructor Book(long,java.lang.String,java.lang.String)");
        assertThat(block).doesNotContain("LoanService.java ---"); // the caller is not the target
    }

    @Test
    void render_method_emitsBlockTargetingLocationFile() {
        BuildOutcome eo = probeFailure(String.join("\n",
                "[ERROR] /src/main/java/com/example/LoanController.java:[30,29] cannot find symbol",
                "[ERROR]   symbol:   method createLoan(java.lang.Long,java.lang.Long)",
                "[ERROR]   location: class com.example.LoanService"));
        Map<String, String> prev = new LinkedHashMap<>();
        prev.put("src/main/java/com/example/LoanController.java", "class LoanController {}");
        prev.put("src/main/java/com/example/LoanService.java", "class LoanService {}");
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .lastProbeOutcome(eo).previousOutput(prev).build();

        String block = MissingMemberPromptBlock.render(ctx);

        assertThat(block).isNotNull();
        assertThat(block).contains("--- MISSING MEMBER — ADD TO LoanService.java ---");
        assertThat(block).contains("src/main/java/com/example/LoanService.java");
        assertThat(block).contains("method createLoan(java.lang.Long,java.lang.Long)");
    }

    @Test
    void render_replayConstructorAndMissingMethodFromMay22_groupsByTargetFile() {
        // Replay shape of the 2026-05-22 01:12 run: constructor mismatches on Book/Member,
        // then a missing-method mismatch on LoanService.createLoan. Three target files, three
        // blocks; each block names the file already in output/.
        BuildOutcome eo = probeFailure(String.join("\n",
                "[ERROR] /src/main/java/com/example/LoanController.java:[10,3] " +
                        "no suitable constructor found for Book(long,java.lang.String,java.lang.String)",
                "[ERROR] /src/main/java/com/example/LoanController.java:[11,3] " +
                        "no suitable constructor found for Member(long,java.lang.String,java.lang.String)",
                "[ERROR] /src/main/java/com/example/LoanController.java:[20,3] cannot find symbol",
                "[ERROR]   symbol:   method createLoan(java.lang.Long,java.lang.Long)",
                "[ERROR]   location: class com.example.LoanService"));
        Map<String, String> prev = new LinkedHashMap<>();
        prev.put("src/main/java/com/example/Book.java", "class Book {}");
        prev.put("src/main/java/com/example/Member.java", "class Member {}");
        prev.put("src/main/java/com/example/LoanService.java", "class LoanService {}");
        prev.put("src/main/java/com/example/LoanController.java", "class LoanController {}");
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .lastProbeOutcome(eo).previousOutput(prev).build();

        String block = MissingMemberPromptBlock.render(ctx);

        assertThat(block).isNotNull();
        assertThat(block).contains("--- MISSING MEMBER — ADD TO Book.java ---");
        assertThat(block).contains("--- MISSING MEMBER — ADD TO Member.java ---");
        assertThat(block).contains("--- MISSING MEMBER — ADD TO LoanService.java ---");
    }

    private static BuildOutcome probeFailure(String stderr) {
        return new BuildOutcome(BuildResult.COMPILE_ERROR, stderr, 10L, "mvn -q compile", "test-cycle");
    }
}
