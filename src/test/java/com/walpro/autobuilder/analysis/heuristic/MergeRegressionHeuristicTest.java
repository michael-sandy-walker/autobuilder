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

    {
        heuristic.setEndpointSurfaceQuery(
                new com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery());
    }
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
                Map.of(PATH, controller("", "")));

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
    void addingAMemberWhileDroppingTheAnnotationAbstains_theRecallThisDeliberatelyTradesAway() {
        // Pinning the trade-off rather than leaving it accidental: a mutation that both adds a member
        // and drops a stereotype is NOT reported, because the member-level substitution test cannot
        // tell "I hand-wrote what the annotation generated" from "I added an unrelated method". The
        // field evidence made the first case common and the second case is covered elsewhere — the
        // boot gate catches an unregistered bean, and the peer-annotation rule catches the odd one
        // out. If a run is ever observed losing a stereotype this way, this is the test to revisit.
        assertThat(evaluate(
                Map.of(PATH, controller("@RestController", "")),
                Map.of(PATH, controller("", "    public String extra() { return \"x\"; }")))
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

    // The first FIELD firing (run 2026-07-30_00-48-16) taught the member-level half. One mutation
    // dropped three annotations from a model type and hand-wrote the members they had been
    // generating, while another type in the same mutation lost its stereotype with its members
    // untouched. Both shapes are below, from the real file contents.

    @Test
    void handWritingTheMembersAnAnnotationGeneratedIsASubstitution_notALoss() {
        String established = """
                package a;
                import lombok.Data;
                import lombok.NoArgsConstructor;
                @Data
                @NoArgsConstructor
                public class Book {
                    private Long id;
                    private String title;
                }
                """;
        String candidate = """
                package a;
                public class Book {
                    private Long id;
                    private String title;
                    public Book() { }
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                }
                """;
        assertThat(evaluate(Map.of(PATH, established), Map.of(PATH, candidate)).mutationState())
                .isEqualTo(ABSTAIN);
    }

    @Test
    void losingAStereotypeWithTheMembersUntouchedIsStillRejected() {
        // The true defect in that same field mutation: the repository stopped being registered while
        // a service kept constructor-injecting it, so the context would fail to start. Compiles fine.
        String established = """
                package a;
                import org.springframework.stereotype.Repository;
                @Repository
                public class BookRepository {
                    private final java.util.Map<Long, String> books = new java.util.HashMap<>();
                    public String save(String book) { return book; }
                    public String findById(Long id) { return books.get(id); }
                }
                """;
        String candidate = """
                package a;
                public class BookRepository {
                    private final java.util.Map<Long, String> books = new java.util.HashMap<>();
                    public String save(String book) { return book; }
                    public java.util.Optional<String> findById(Long id) {
                        return java.util.Optional.ofNullable(books.get(id));
                    }
                }
                """;
        HeuristicOpinion opinion = evaluate(Map.of(PATH, established), Map.of(PATH, candidate));
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.rationale()).contains("@Repository");
    }

    @Test
    void aChangedSignatureIsNotANewMember_soItCannotExcuseALostAnnotation() {
        // findById returning an optional instead of the raw type is the same member reimplemented.
        // Comparing signatures rather than names would let any rewrite launder an annotation loss.
        String established = """
                package a;
                @Marker
                public class R {
                    public String findById(Long id) { return null; }
                }
                """;
        String candidate = """
                package a;
                public class R {
                    public java.util.Optional<String> findById(Long id) { return java.util.Optional.empty(); }
                }
                """;
        assertThat(evaluate(Map.of(PATH, established), Map.of(PATH, candidate)).mutationState())
                .isEqualTo(MutationAcceptanceState.REJECTED);
    }

    @Test
    void removingMembersDoesNotExcuseALostAnnotationEither() {
        // Only GAINED members indicate a substitution; losing members alongside the annotation is a
        // reduction on both axes.
        String established = """
                package a;
                @Marker
                public class R {
                    public String a() { return null; }
                    public String b() { return null; }
                }
                """;
        String candidate = "package a;\npublic class R {\n    public String a() { return null; }\n}\n";
        assertThat(evaluate(Map.of(PATH, established), Map.of(PATH, candidate)).mutationState())
                .isEqualTo(MutationAcceptanceState.REJECTED);
    }

    // ---- requirement coverage ------------------------------------------------------------
    // The arm that asks the right question. Member counting is a proxy for harm and a poor one;
    // "an endpoint the SPEC names was served and would not be" is the harm itself. On the four field
    // shapes it separates all four, where member counting scored one true and two false.

    private static final String ENDPOINT_SPEC =
            "Books: POST /books (create), GET /books (list all), GET /books/{id} (single; 404 if absent).";

    private static final String FULL_CONTROLLER = """
            package a;
            @RestController
            @RequestMapping("/books")
            public class BookController {
                @GetMapping public String getAllBooks() { return "all"; }
                @PostMapping public String createBook(@RequestBody String b) { return b; }
                @GetMapping("/{id}") public String getBookById(@PathVariable Long id) { return "one"; }
            }
            """;

    /**
     * Coverage-arm evaluation in PRODUCTION configuration — member losses off. Without that, the
     * member arm answers first on these fixtures and the test would pass for the wrong reason (it
     * did, before this was separated out).
     */
    private HeuristicOpinion evaluateWithSpec(Map<String, String> established,
                                              Map<String, String> changed) {
        MergeRegressionHeuristic production = new MergeRegressionHeuristic();
        production.setEndpointSurfaceQuery(
                new com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery());
        ProjectDiffResult diff = Mockito.mock(ProjectDiffResult.class);
        Mockito.when(diff.changedFiles()).thenReturn(changed);
        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-1")
                .currentGoalDescription(ENDPOINT_SPEC)
                .previousOutput(established)
                .projectDiff(diff)
                .build();
        return production.evaluate(context, outcome);
    }

    @Test
    void aMutationThatStopsServingASpecNamedEndpointIsRejected() {
        String truncated = """
                package a;
                @RestController
                @RequestMapping("/books")
                public class BookController {
                    @GetMapping("/{id}") public String getBookById(@PathVariable Long id) { return "one"; }
                }
                """;
        HeuristicOpinion opinion = evaluateWithSpec(
                Map.of(PATH, FULL_CONTROLLER), Map.of(PATH, truncated));

        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.rationale()).contains("/books").contains("no longer served");
    }

    @Test
    void movingAHandlerToAnotherClassKeepsTheEndpointServed_soNothingIsReported() {
        // What member counting could never get right: the endpoint is a property of the artifact, not
        // of a file, so the comparison is over the whole post-merge view.
        String stripped = """
                package a;
                @RestController
                @RequestMapping("/books")
                public class BookController {
                    @GetMapping("/{id}") public String getBookById(@PathVariable Long id) { return "one"; }
                }
                """;
        String newHome = """
                package a;
                @RestController
                @RequestMapping("/books")
                public class BookQueryController {
                    @GetMapping public String getAllBooks() { return "all"; }
                    @PostMapping public String createBook(@RequestBody String b) { return b; }
                }
                """;
        assertThat(evaluateWithSpec(Map.of(PATH, FULL_CONTROLLER),
                Map.of(PATH, stripped, "src/main/java/a/BookQueryController.java", newHome))
                .mutationState()).isEqualTo(ABSTAIN);
    }

    @Test
    void anEndpointTheSpecNeverNamedIsNotThisChecksBusiness() {
        String withExtra = FULL_CONTROLLER.replace("}\n",
                "    @GetMapping(\"/stats\") public String stats() { return \"s\"; }\n}\n");
        assertThat(evaluateWithSpec(Map.of(PATH, withExtra), Map.of(PATH, FULL_CONTROLLER))
                .mutationState()).isEqualTo(ABSTAIN);
    }

    @Test
    void anEndpointThatWasNeverServedCannotRegress() {
        String noHandlers = "package a;\n@RestController\npublic class BookController { }\n";
        assertThat(evaluateWithSpec(Map.of(PATH, noHandlers), Map.of(PATH, noHandlers))
                .mutationState()).isEqualTo(ABSTAIN);
    }

    @Test
    void withNoSpecificationThereIsNoRequirementToProtect() {
        String truncated = "package a;\n@RestController\n@RequestMapping(\"/books\")\npublic class BookController { }\n";
        // The plain evaluate() supplies no goal description, so the coverage arm has no opinion.
        // Member losses are on in that helper, so this asserts on the RATIONALE, not the verdict.
        assertThat(evaluate(Map.of(PATH, FULL_CONTROLLER), Map.of(PATH, truncated)).rationale())
                .doesNotContain("no longer served");
    }

    @Test
    void droppingMembersAloneIsNotReported() {
        // T5-BL 2026-09-24: the member-loss arm (off since its first field run measured it a net
        // cost) is deleted. A file that only loses members, annotations intact, draws no opinion.
        MergeRegressionHeuristic production = new MergeRegressionHeuristic();
        String established = """
                package a;
                @Service
                public class S {
                    public String a() { return null; }
                    public String b() { return null; }
                }
                """;
        String candidate = "package a;\n@Service\npublic class S {\n    public String a() { return null; }\n}\n";
        ProjectDiffResult diff = Mockito.mock(ProjectDiffResult.class);
        Mockito.when(diff.changedFiles()).thenReturn(Map.of(PATH, candidate));
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .previousOutput(Map.of(PATH, established)).projectDiff(diff).build();

        assertThat(production.evaluate(ctx, outcome).mutationState()).isEqualTo(ABSTAIN);
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
