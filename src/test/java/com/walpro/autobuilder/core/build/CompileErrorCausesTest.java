package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A failed compile told cause first. Rule-extraction run 2026-10-18_18-30-42: one real error in the file the change
 * wrote, and "cannot find symbol: variable log" in classes it never touched -- printed twice, as Maven does.
 */
class CompileErrorCausesTest {

    private static final String P = "/home/u/output/src/main/java/com/walpro/autobuilder/";

    private static final String FIELD79 = String.join("\n",
            "[INFO] -------------------------------------------------------------",
            "[ERROR] COMPILATION ERROR : ",
            "[INFO] -------------------------------------------------------------",
            "[ERROR] " + P + "analysis/structure/EndpointSurfaceQuery.java:[312,36] method discardedInput(java.lang.String) is already defined in class com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery",
            "[ERROR] " + P + "observability/behaviour/BehaviourLogger.java:[81,13] cannot find symbol",
            "  symbol:   variable log",
            "  location: class com.walpro.autobuilder.observability.behaviour.BehaviourLogger",
            "[ERROR] " + P + "core/build/BuildExecutor.java:[146,17] cannot find symbol",
            "  symbol:   variable log",
            "  location: class com.walpro.autobuilder.core.build.BuildExecutor",
            "[INFO] 3 errors ",
            "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.12.1:compile (default-compile) on project rule-extraction: Compilation failure: Compilation failure: ",
            "[ERROR] " + P + "analysis/structure/EndpointSurfaceQuery.java:[312,36] method discardedInput(java.lang.String) is already defined in class com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery",
            "[ERROR] " + P + "observability/behaviour/BehaviourLogger.java:[81,13] cannot find symbol",
            "[ERROR]   symbol:   variable log",
            "[ERROR]   location: class com.walpro.autobuilder.observability.behaviour.BehaviourLogger",
            "[ERROR] " + P + "core/build/BuildExecutor.java:[146,17] cannot find symbol",
            "[ERROR]   symbol:   variable log",
            "[ERROR]   location: class com.walpro.autobuilder.core.build.BuildExecutor",
            "[ERROR] -> [Help 1]");

    private static final List<String> WROTE = List.of(
            "src/main/java/com/walpro/autobuilder/analysis/structure/EndpointSurfaceQuery.java",
            "src/main/java/com/walpro/autobuilder/analysis/structure/Endpoint.java");

    @Test
    void theErrorsInFilesTheChangeDidNotWrite_areSetAside_andCounted() {
        CompileErrorCauses.Split split = CompileErrorCauses.of(FIELD79, WROTE);

        assertThat(split.causes()).contains("EndpointSurfaceQuery.java:[312,36] method discardedInput")
                .doesNotContain("variable log").doesNotContain("BehaviourLogger.java").doesNotContain("BuildExecutor.java")
                .contains("Failed to execute goal");
        assertThat(split.consequences()).isEqualTo(2);
        assertThat(split.consequenceFiles()).containsExactly("BehaviourLogger", "BuildExecutor");
        assertThat(split.note()).startsWith("2 more error(s) in 2 file(s) the change did not write (BehaviourLogger, BuildExecutor)");
    }

    /** When the change's own files report nothing, every error is what it broke elsewhere: all of them stay. */
    /** The build keeps a window of its output; a detail line whose error line fell in the cut part belongs to nothing kept. */
    @Test
    void aDetailLineWhoseErrorLineWasCutFromTheOutput_isNotKept() {
        String windowed = FIELD79.replace("[ERROR] -> [Help 1]",
                "... (531 line(s) of build output omitted) ...\n[ERROR]   location: class com.walpro.autobuilder.core.build.JavacDirectProbe\n[ERROR] -> [Help 1]");

        assertThat(CompileErrorCauses.of(windowed, WROTE).causes()).doesNotContain("JavacDirectProbe").contains("omitted");
    }

    @Test
    void whenTheChangesOwnFilesReportNothing_everyErrorStays() {
        CompileErrorCauses.Split split = CompileErrorCauses.of(FIELD79,
                List.of("src/main/java/com/walpro/autobuilder/goal/SpecTraceability.java"));

        assertThat(split.causes()).contains("variable log", "discardedInput");
        assertThat(split.consequences()).isZero();
        assertThat(split.note()).isNull();
    }

    @Test
    void withoutTheFilesTheChangeWrote_nothingIsSetAside() {
        assertThat(CompileErrorCauses.of(FIELD79, List.of()).causes()).contains("variable log");
        assertThat(CompileErrorCauses.of(null, WROTE).consequences()).isZero();
    }
}
