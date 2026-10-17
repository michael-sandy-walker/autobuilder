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
    void render_pathIsProjectRelative_notTheHostsAbsolutePath() {
        // javac reports absolute paths; the first field firing put
        // /home/…/firestarter/output/src/main/java/… into the prompt.
        Map<String, String> prev = new LinkedHashMap<>();
        prev.put("src/main/java/com/example/demo/service/LoanService.java", "class LoanService {}");
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .lastProbeOutcome(probeFailure(missingType(
                        "/home/u/proj/firestarter/output/src/main/java/com/example/demo/service/"
                                + "LoanService.java", "BookAlreadyLoanedException")))
                .previousOutput(prev).build();

        String block = MissingTypePromptBlock.render(ctx);

        assertThat(block).contains("src/main/java/com/example/demo/service/LoanService.java");
        assertThat(block).doesNotContain("/home/");
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

    /** field77 (rule-extraction, 2026-10-17): the error lines as javac wrote them. */
    private static final String ASKED_OF_ANOTHER_CLASS = String.join("\n",
            "[ERROR] /home/u/data/rule-extraction/output/src/main/java/com/walpro/autobuilder/core/coldread/ColdReadService.java:[78,42] cannot find symbol",
            "[ERROR]   symbol:   class Endpoint",
            "[ERROR]   location: class com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery");

    /**
     * T6-T, field77: the code named EndpointSurfaceQuery.Endpoint, which the change moves out to a record of its own;
     * the block said "create Endpoint", the model did, every cycle, and the reference never changed.
     */
    @Test
    void aTypeAskedOfAnotherClassThatLacksIt_isNotProposedForCreation_theReferenceIsNamed() {
        Map<String, String> prev = new LinkedHashMap<>();
        prev.put("src/main/java/com/walpro/autobuilder/core/coldread/ColdReadService.java", "class ColdReadService {}");
        prev.put("src/main/java/com/walpro/autobuilder/analysis/structure/EndpointSurfaceQuery.java", "class EndpointSurfaceQuery {}");
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .lastProbeOutcome(probeFailure(ASKED_OF_ANOTHER_CLASS)).previousOutput(prev).build();

        String block = MissingTypePromptBlock.render(ctx);

        assertThat(block).contains("--- A TYPE ASKED OF A CLASS THAT DOES NOT DECLARE IT ---")
                .contains("- EndpointSurfaceQuery.Endpoint -- written in src/main/java/com/walpro/autobuilder/core/coldread/ColdReadService.java; EndpointSurfaceQuery declares no type Endpoint")
                .doesNotContain("CREATE IN THIS PROPOSAL");
    }

    /** javac names the enclosing class for an unqualified type too: that is a type to create, as before. */
    @Test
    void anUnqualifiedTypeLookedUpInTheFilesOwnClass_isStillProposedForCreation() {
        Map<String, String> prev = new LinkedHashMap<>();
        prev.put("src/main/java/com/example/LoanController.java", "class LoanController {}");
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .lastProbeOutcome(probeFailure(String.join("\n",
                        "[ERROR] /out/src/main/java/com/example/LoanController.java:[20,9] cannot find symbol",
                        "[ERROR]   symbol:   class LoanService",
                        "[ERROR]   location: class com.example.LoanController")))
                .previousOutput(prev).build();

        assertThat(MissingTypePromptBlock.render(ctx)).contains("--- MISSING TYPE — CREATE IN THIS PROPOSAL ---", "- LoanService")
                .doesNotContain("ASKED OF A CLASS");
    }
}
