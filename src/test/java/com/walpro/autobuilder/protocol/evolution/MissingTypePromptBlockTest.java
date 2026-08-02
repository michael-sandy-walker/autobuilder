package com.walpro.autobuilder.protocol.evolution;

import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.inject.InjectorFixRecord;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** T1-FR: tests for {@link MissingTypePromptBlock}. */
class MissingTypePromptBlockTest {

    private static BuildOutcome probeFailure(String stderr) {
        return new BuildOutcome(BuildResult.COMPILE_ERROR, stderr, 10L, "mvn", "c");
    }

    private static String missingType(String file, String type) {
        return String.join("\n",
                "[ERROR] " + file + ":[20,9] cannot find symbol",
                "[ERROR]   symbol:   class " + type);
    }

    @Test
    void render_nullCtx_returnsNull() {
        assertThat(MissingTypePromptBlock.render(null)).isNull();
    }

    @Test
    void render_probeCompileOk_returnsNull() {
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .lastProbeOutcome(new BuildOutcome(BuildResult.COMPILE_OK, "", 1L, "mvn", "c"))
                .build();
        assertThat(MissingTypePromptBlock.render(ctx)).isNull();
    }

    @Test
    void render_namesTheTypeAndTheFileThatReferencesIt() {
        Map<String, String> prev = new LinkedHashMap<>();
        prev.put("src/main/java/com/example/LoanController.java", "class LoanController {}");
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .lastProbeOutcome(probeFailure(missingType(
                        "/out/src/main/java/com/example/LoanController.java", "LoanService")))
                .previousOutput(prev).build();

        String block = MissingTypePromptBlock.render(ctx);

        assertThat(block).contains("--- MISSING TYPE — CREATE IN THIS PROPOSAL ---");
        assertThat(block).contains("- LoanService");
        assertThat(block).contains("LoanController.java");
    }

    @Test
    void render_typeAlreadyDefinedByAFile_isNotProposedForCreation() {
        Map<String, String> prev = new LinkedHashMap<>();
        prev.put("src/main/java/com/example/LoanService.java", "class LoanService {}");
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .lastProbeOutcome(probeFailure(missingType("/out/A.java", "LoanService")))
                .previousOutput(prev).build();

        assertThat(MissingTypePromptBlock.render(ctx)).isNull();
    }

    @Test
    void render_typeDeclaredByAnImport_isALibraryTypeNotOursToWrite() {
        Map<String, String> prev = new LinkedHashMap<>();
        prev.put("src/main/java/com/example/A.java",
                "import jakarta.validation.Valid;\nclass A {}");
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .lastProbeOutcome(probeFailure(missingType("/out/A.java", "Valid")))
                .previousOutput(prev).build();

        assertThat(MissingTypePromptBlock.render(ctx)).isNull();
    }

    @Test
    void render_typeTheInjectorResolvedAsAnImport_defersToThatVerdict() {
        // The mechanism that decides importable-vs-not has already ruled; re-deciding here is how
        // the block would end up telling the model to write its own `Pattern`.
        Map<String, String> prev = new LinkedHashMap<>();
        prev.put("src/main/java/com/example/A.java", "class A {}");
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .lastProbeOutcome(probeFailure(missingType("/out/A.java", "Pattern")))
                .previousOutput(prev)
                .injectorFixes(List.of(new InjectorFixRecord(
                        "symbol-import:jakarta.validation-api",
                        com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL,
                        "Pattern",
                        List.of("src/main/java/com/example/A.java"), "added import")))
                .build();

        assertThat(MissingTypePromptBlock.render(ctx)).isNull();
    }

    @Test
    void render_typeUsedAsAnAnnotation_isNotProposedForCreation() {
        Map<String, String> prev = new LinkedHashMap<>();
        prev.put("src/main/java/com/example/Book.java", "class Book { @Column String t; }");
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .lastProbeOutcome(probeFailure(missingType("/out/Book.java", "Column")))
                .previousOutput(prev).build();

        assertThat(MissingTypePromptBlock.render(ctx)).isNull();
    }

    @Test
    void render_allTokensFiltered_returnsNullRatherThanAnEmptyHeading() {
        Map<String, String> prev = new LinkedHashMap<>();
        prev.put("src/main/java/com/example/Valid.java", "class Valid {}");
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .lastProbeOutcome(probeFailure(missingType("/out/A.java", "Valid")))
                .previousOutput(prev).build();

        assertThat(MissingTypePromptBlock.render(ctx)).isNull();
    }

    @Test
    void render_anImportIntoTheProjectsOwnPackageIsNotLibraryEvidence() {
        // Verbatim from run 2026-08-01_01-10-45: the model imported a type from its OWN model
        // package before creating it, and javac answered `location: package com.example.demo.model`.
        // Counting that import as "a library supplies this" would filter out the very case the
        // block exists for.
        String stderr = String.join("\n",
                "[ERROR] /out/src/main/java/com/example/demo/controller/LoanController.java:[3,30] "
                        + "cannot find symbol",
                "[ERROR]   symbol:   class Loan",
                "[ERROR]   location: package com.example.demo.model");
        Map<String, String> prev = new LinkedHashMap<>();
        prev.put("src/main/java/com/example/demo/controller/LoanController.java",
                "package com.example.demo.controller;\n"
                        + "import com.example.demo.model.Loan;\nclass LoanController {}");
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .lastProbeOutcome(probeFailure(stderr)).previousOutput(prev).build();

        assertThat(MissingTypePromptBlock.render(ctx)).contains("- Loan");
    }

    @Test
    void render_severalTypes_areListedTogetherInOneBlock() {
        String stderr = missingType("/out/src/main/java/com/example/LoanController.java", "Loan")
                + "\n" + missingType("/out/src/main/java/com/example/LoanController.java",
                                     "LoanNotFoundException");
        Map<String, String> prev = new LinkedHashMap<>();
        prev.put("src/main/java/com/example/LoanController.java", "class LoanController {}");
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .lastProbeOutcome(probeFailure(stderr)).previousOutput(prev).build();

        String block = MissingTypePromptBlock.render(ctx);

        assertThat(block).contains("- Loan ");
        assertThat(block).contains("- LoanNotFoundException");
        assertThat(block.split("--- MISSING TYPE")).hasSize(2);
    }
}
