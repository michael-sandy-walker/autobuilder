package com.walpro.autobuilder.protocol.build;

import com.walpro.autobuilder.core.build.BuildErrorClassifier;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.merge.RolledBackReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** T1-DX: tests for the shared {@link RolledBackPromptBlock} formatter. */
class RolledBackPromptBlockTest {

    @Test
    void render_nullCtx_returnsNull() {
        assertThat(RolledBackPromptBlock.render(null)).isNull();
    }

    @Test
    void render_noEvidence_returnsNull() {
        CycleContextData ctx = CycleContextData.builder().cycleName("c").build();
        assertThat(RolledBackPromptBlock.render(ctx)).isNull();
    }

    @Test
    void render_evidencePresent_emitsRolledBackBlock() {
        RolledBackReport evidence = new RolledBackReport(
                "cycle-rb",
                List.of("src/main/java/Foo.java", "src/main/java/Bar.java"),
                "cannot find symbol: Foo",
                BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL);
        CycleContextData ctx = CycleContextData.builder().cycleName("c").lastRollback(evidence).build();

        String block = RolledBackPromptBlock.render(ctx);

        assertThat(block).isNotNull();
        assertThat(block)
                .contains("--- LAST CYCLE ROLLED BACK ---")
                .contains("Previous cycle: cycle-rb")
                .contains("CANNOT_FIND_SYMBOL")
                .contains("cannot find symbol: Foo")
                .contains("Restored paths (2)")
                .contains("- src/main/java/Foo.java")
                .contains("- src/main/java/Bar.java")
                .contains("structurally different approach");
    }

    @Test
    void render_emptyRestoredPaths_stillRendersHeaderAndCount() {
        RolledBackReport evidence = new RolledBackReport(
                "cycle-rb", List.of(), "no POM in this directory", BuildErrorClassifier.Kind.MISSING_POM);
        CycleContextData ctx = CycleContextData.builder().cycleName("c").lastRollback(evidence).build();

        String block = RolledBackPromptBlock.render(ctx);

        assertThat(block).isNotNull();
        assertThat(block).contains("Restored paths (0)").contains("MISSING_POM");
    }
}
