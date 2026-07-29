package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2-CI. The field case: run {@code 2026-07-27_01-01-01} merged one file six times in three
 * minutes, alternating between carrying {@code @RestController} and not carrying it. Every merge was
 * ACCEPTED, one at 0.85, and none was a rollback — the artifact flipped between registered and inert
 * three times while nothing observed that it had gone backwards.
 *
 * <p>The ticket had once been closed as subsumed by the compile probe. These tests exist partly to
 * pin why that was wrong: every "before" and "after" below compiles.
 */
class MergeRegressionHeuristicTest {

    private final MergeRegressionHeuristic heuristic = new MergeRegressionHeuristic();
    private final EvaluationOutcome outcome = Mockito.mock(EvaluationOutcome.class);

    private static final String PATH = "src/main/java/a/LoanController.java";

    /** The codebase's "no opinion": HeuristicOpinion.none() carries this state, never null. */
    private static final MutationAcceptanceState ABSTAIN = MutationAcceptanceState.NOT_EVALUATED;

    private static String controller(String annotations, String extraMethod) {
        return """
                package a;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;
                %s
                @RequestMapping("/loans")
                public class LoanController {
                    public String list() { return "[]"; }
                %s
                }
                """.formatted(annotations, extraMethod);
    }

    private HeuristicOpinion evaluate(Map<String, String> established, Map<String, String> changed) {
        ProjectDiffResult diff = Mockito.mock(ProjectDiffResult.class);
        Mockito.when(diff.changedFiles()).thenReturn(changed);
        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-1")
                .previousOutput(established)
                .projectDiff(diff)
                .build();
        return heuristic.evaluate(context, outcome);
    }

    @Test
    void aMutationThatDropsAnAnnotationTheTypeAlreadyHadIsRejected() {
        HeuristicOpinion opinion = evaluate(
                Map.of(PATH, controller("@RestController", "")),
                Map.of(PATH, controller("", "    public String one() { return \"x\"; }")));

        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.rationale()).contains("LoanController").contains("@RestController");
        // A regression is not partial credit; a mid score would be averaged away by the negotiator.
        assertThat(opinion.suggestedScore()).isLessThan(0.5);
        assertThat(opinion.defectFinding()).isTrue();
    }

    @Test
    void bothVersionsCompile_whichIsWhyTheCompileProbeCannotCatchThis() {
        // Not a claim about javac — a statement of the shape: nothing about either text is invalid,
        // the difference is only that one type is registered and the other is not.
        String before = controller("@RestController", "");
        String after = controller("", "");
        assertThat(before).doesNotContain("ERROR");
        assertThat(after).doesNotContain("ERROR");
        assertThat(evaluate(Map.of(PATH, before), Map.of(PATH, after)).mutationState())
                .isEqualTo(MutationAcceptanceState.REJECTED);
    }

    @Test
    void keepingTheAnnotationWhileChangingTheCodeIsNotARegression() {
        assertThat(evaluate(
                Map.of(PATH, controller("@RestController", "")),
                Map.of(PATH, controller("@RestController", "    public String two() { return \"y\"; }")))
                .mutationState()).isEqualTo(ABSTAIN);
    }

    @Test
    void addingAnAnnotationIsNeverARegression() {
        assertThat(evaluate(
                Map.of(PATH, controller("", "")),
                Map.of(PATH, controller("@RestController", "")))
                .mutationState()).isEqualTo(ABSTAIN);
    }

    @Test
    void swappingOneAnnotationForAnotherAbstains_itIsARedesignNotALoss() {
        // The loop is entitled to change its mind about which annotation a type carries. Only a
        // strict loss is a regression, which is also what keeps a package migration silent: moving
        // to a different annotation package rewrites imports and leaves the simple names alone.
        assertThat(evaluate(
                Map.of(PATH, controller("@RestController", "")),
                Map.of(PATH, controller("@Controller", "")))
                .mutationState()).isEqualTo(ABSTAIN);
    }

    @Test
    void aNewFileEstablishesAndCannotRevert() {
        assertThat(evaluate(
                Map.of("src/main/java/a/Other.java", controller("@RestController", "")),
                Map.of(PATH, controller("", "")))
                .mutationState()).isEqualTo(ABSTAIN);
    }

    @Test
    void aTypeTheCandidateNoLongerDeclaresIsARemovalNotAnAnnotationChange() {
        assertThat(evaluate(
                Map.of(PATH, controller("@RestController", "")),
                Map.of(PATH, "package a;\npublic class Renamed {}\n"))
                .mutationState()).isEqualTo(ABSTAIN);
    }

    @Test
    void anUnparseableCandidateIsLeftToTheCompileGate() {
        assertThat(evaluate(
                Map.of(PATH, controller("@RestController", "")),
                Map.of(PATH, "package a; public class LoanController { this is not java"))
                .mutationState()).isEqualTo(ABSTAIN);
    }

    @Test
    void withNothingEstablishedYetThereIsNoInvariantToProtect() {
        assertThat(evaluate(Map.of(), Map.of(PATH, controller("", ""))).mutationState()).isEqualTo(ABSTAIN);
        assertThat(evaluate(null, Map.of(PATH, controller("", ""))).mutationState()).isEqualTo(ABSTAIN);
    }

    @Test
    void noDiffMeansNoOpinion() {
        assertThat(evaluate(Map.of(PATH, controller("@RestController", "")), Map.of())
                .mutationState()).isEqualTo(ABSTAIN);
        assertThat(heuristic.evaluate(null, outcome).mutationState()).isEqualTo(ABSTAIN);
    }

    @Test
    void aNonJavaFileIsNotInspected() {
        assertThat(evaluate(
                Map.of("src/main/resources/application.yml", "server:\n  port: 8080\n"),
                Map.of("src/main/resources/application.yml", "server:\n  port: 9090\n"))
                .mutationState()).isEqualTo(ABSTAIN);
    }

    @Test
    void theRuleHoldsForAnnotationsThisFrameworkHasNeverHeardOf() {
        // No marker list is hardcoded: whatever the type carried is the invariant.
        String before = "package a;\n@Whatsit\n@Thingummy\npublic class T { }\n";
        String after = "package a;\n@Whatsit\npublic class T { }\n";
        assertThat(evaluate(Map.of(PATH, before), Map.of(PATH, after)).rationale())
                .contains("@Thingummy");
    }
}
