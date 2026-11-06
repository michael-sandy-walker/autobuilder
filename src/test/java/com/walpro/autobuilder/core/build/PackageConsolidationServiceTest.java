package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PackageConsolidationServiceTest {

    private static final String OUTPUT = "output";
    private static final String ROOT = "src/main/java/com/example/demo";

    @Mock private FileService fileService;
    @Mock private BehaviourLogger behaviourLogger;

    private PackageConsolidationService service;

    @BeforeEach
    void setUp() throws IOException {
        service = new PackageConsolidationService(new PackageFragmentationCheck(fileService));
        service.setBehaviourLogger(behaviourLogger);
        lenient().when(fileService.listAllRelativeFilePathsUnder(OUTPUT))
                .thenReturn(List.of(ROOT + "/Loan.java", ROOT + "/LoanService.java"));
    }

    private static EvolutionOutcome outcomeOf(FileMutation... mutations) {
        return new EvolutionOutcome(List.of(mutations), List.of(), "summary", "raw");
    }

    @Test
    void noConflict_returnsOutcomeUnchanged() {
        EvolutionOutcome outcome = outcomeOf(
                new FileMutation(ROOT + "/Member.java", "package com.example.demo;\nclass Member {}"));

        assertThat(service.consolidate(outcome, OUTPUT, "cycle-1")).isSameAs(outcome);
    }

    @Test
    void fragmentingWrite_isMovedToExistingPackage_withDeclarationRewritten() {
        EvolutionOutcome outcome = outcomeOf(new FileMutation(
                ROOT + "/model/Loan.java",
                "package com.example.demo.model;\n\npublic class Loan { private boolean returned; }"));

        EvolutionOutcome result = service.consolidate(outcome, OUTPUT, "cycle-1");

        assertThat(result.addedOrChangedFiles()).singleElement().satisfies(m -> {
            assertThat(m.path()).isEqualTo(ROOT + "/Loan.java");
            assertThat(m.content()).startsWith("package com.example.demo;");
            assertThat(m.content()).contains("private boolean returned;");
            assertThat(m.content()).doesNotContain("com.example.demo.model");
        });
        verify(behaviourLogger).packageConsolidationApplied(
                eq("cycle-1"), eq("Loan"), eq(ROOT + "/model/Loan.java"), eq(ROOT + "/Loan.java"));
    }

    @Test
    void anExplicitMove_writingTheNewPathAndDeletingTheOld_isLeftAsWritten() {
        // T4-EH: the 2026-11-03 firestarter run moved its application class up out of a package
        // whose scan missed the controllers, deleting the old file; this rewrite put it back.
        EvolutionOutcome outcome = new EvolutionOutcome(
                List.of(new FileMutation(ROOT + "/model/Loan.java",
                        "package com.example.demo.model;\npublic class Loan {}")),
                List.of(ROOT + "/Loan.java"), "summary", "raw");

        assertThat(service.consolidate(outcome, OUTPUT, "cycle-1")).isSameAs(outcome);
    }

    @Test
    void crossReferences_inSiblingMutations_areRewrittenToExistingPackage() {
        EvolutionOutcome outcome = outcomeOf(
                new FileMutation(ROOT + "/model/Loan.java",
                        "package com.example.demo.model;\npublic class Loan {}"),
                new FileMutation(ROOT + "/LoanController.java",
                        "package com.example.demo;\n"
                                + "import com.example.demo.model.Loan;\n"
                                + "public class LoanController { com.example.demo.model.Loan active; }"));

        EvolutionOutcome result = service.consolidate(outcome, OUTPUT, "cycle-1");

        FileMutation controller = result.addedOrChangedFiles().get(1);
        assertThat(controller.content())
                .contains("import com.example.demo.Loan;")
                .contains("com.example.demo.Loan active;")
                .doesNotContain("com.example.demo.model.Loan");
    }

    @Test
    void mutationSetAlsoWritingExistingPath_isLeftToTheGate() {
        // Ambiguous intent: the set writes both the existing location and a fragmenting one.
        EvolutionOutcome outcome = outcomeOf(
                new FileMutation(ROOT + "/Loan.java", "package com.example.demo;\nclass Loan { /* v1 */ }"),
                new FileMutation(ROOT + "/model/Loan.java", "package com.example.demo.model;\nclass Loan { /* v2 */ }"));

        assertThat(service.consolidate(outcome, OUTPUT, "cycle-1")).isSameAs(outcome);
    }

    @Test
    void defaultPackageTarget_isLeftToTheGate() throws IOException {
        lenient().when(fileService.listAllRelativeFilePathsUnder(OUTPUT))
                .thenReturn(List.of("Loan.java"));
        EvolutionOutcome outcome = outcomeOf(
                new FileMutation(ROOT + "/model/Loan.java", "package com.example.demo.model;\nclass Loan {}"));

        assertThat(service.consolidate(outcome, OUTPUT, "cycle-1")).isSameAs(outcome);
    }

    @Test
    void nonJavaFiles_passThroughUntouched() {
        EvolutionOutcome outcome = outcomeOf(
                new FileMutation(ROOT + "/model/Loan.java", "package com.example.demo.model;\nclass Loan {}"),
                new FileMutation("pom.xml", "<project/>"));

        EvolutionOutcome result = service.consolidate(outcome, OUTPUT, "cycle-1");

        assertThat(result.addedOrChangedFiles().get(0).path()).isEqualTo(ROOT + "/Loan.java");
        assertThat(result.addedOrChangedFiles().get(1)).isEqualTo(new FileMutation("pom.xml", "<project/>"));
    }

    @Test
    void contentWithoutPackageDeclaration_gainsOne() {
        EvolutionOutcome outcome = outcomeOf(
                new FileMutation(ROOT + "/model/Loan.java", "public class Loan {}"));

        EvolutionOutcome result = service.consolidate(outcome, OUTPUT, "cycle-1");

        assertThat(result.addedOrChangedFiles().get(0).content())
                .startsWith("package com.example.demo;");
    }

    @Test
    void nestedSubPackageExisting_consolidatesTowardExistingLocation() throws IOException {
        // Direction is always "existing wins" — including when the existing location is the
        // deeper package and the attempted one is shallower.
        lenient().when(fileService.listAllRelativeFilePathsUnder(OUTPUT))
                .thenReturn(List.of("src/main/java/com/example/demo/member/Member.java"));
        EvolutionOutcome outcome = outcomeOf(new FileMutation(
                ROOT + "/Member.java", "package com.example.demo;\npublic class Member {}"));

        EvolutionOutcome result = service.consolidate(outcome, OUTPUT, "cycle-1");

        assertThat(result.addedOrChangedFiles()).singleElement().satisfies(m -> {
            assertThat(m.path()).isEqualTo("src/main/java/com/example/demo/member/Member.java");
            assertThat(m.content()).startsWith("package com.example.demo.member;");
        });
    }

    @Test
    void nullAndContentlessMutations_passThrough() {
        EvolutionOutcome outcome = outcomeOf(
                new FileMutation(ROOT + "/model/Loan.java", "package com.example.demo.model;\nclass Loan {}"),
                new FileMutation(ROOT + "/Broken.java", null));

        EvolutionOutcome result = service.consolidate(outcome, OUTPUT, "cycle-1");

        assertThat(result.addedOrChangedFiles().get(0).path()).isEqualTo(ROOT + "/Loan.java");
        assertThat(result.addedOrChangedFiles().get(1).content()).isNull();
    }

    @Test
    void scanFailure_returnsOutcomeUnchanged() throws IOException {
        lenient().when(fileService.listAllRelativeFilePathsUnder(any()))
                .thenThrow(new IOException("disk"));
        EvolutionOutcome outcome = outcomeOf(
                new FileMutation(ROOT + "/model/Loan.java", "package com.example.demo.model;\nclass Loan {}"));

        assertThat(service.consolidate(outcome, OUTPUT, "cycle-1")).isSameAs(outcome);
    }
}
