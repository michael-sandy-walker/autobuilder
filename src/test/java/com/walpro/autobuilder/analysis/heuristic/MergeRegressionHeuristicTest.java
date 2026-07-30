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

    // ---- member losses -------------------------------------------------------------------
    // A mutation directory holds ONLY the files that cycle wrote, but each is the WHOLE file:
    // MutationApplier and MergeAgent both write file content wholesale, nothing splices. So a file
    // re-emitted with only the current subgoal's members deletes the rest. Measured over one run's
    // 28 cycles, three pure member reductions occurred; the four tests below are those cases.

    @Test
    void aFileReEmittedWithOnlyTheCurrentSubgoalsMembersIsRejected() {
        // The costly case: a controller re-emitted with its one path-variable handler, dropping the
        // list and create handlers — both spec requirements — which then simply cease to exist.
        String established = """
                package a;
                @RestController
                public class BookController {
                    private BookService bookService;
                    public String getAllBooks() { return "all"; }
                    public String getBookById(Long id) { return "one"; }
                    public String createBook(String b) { return b; }
                }
                """;
        String candidate = """
                package a;
                @RestController
                public class BookController {
                    private BookService bookService;
                    public String getBookById(Long id) { return "one"; }
                }
                """;
        HeuristicOpinion opinion = evaluate(Map.of(PATH, established), Map.of(PATH, candidate));
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.rationale()).contains("getAllBooks").contains("createBook");
        // The advice has to name the actual remedy, since the file is written wholesale.
        assertThat(opinion.rationale()).contains("COMPLETE file");
    }

    @Test
    void changingWhatATypeIsDoesNotCountAsTruncation() {
        // Field case: a class holding five methods became an empty interface. Changing the KIND of a
        // type is a redesign whatever it costs, and the compile gate owns the consequences.
        String established = """
                package a;
                public class BookRepository {
                    private final java.util.Map<Long, String> books = new java.util.HashMap<>();
                    public String save(String b) { return b; }
                    public String findById(Long id) { return null; }
                }
                """;
        String candidate = "package a;\npublic interface BookRepository { }\n";
        assertThat(evaluate(Map.of(PATH, established), Map.of(PATH, candidate)).mutationState())
                .isEqualTo(ABSTAIN);
    }

    @Test
    void aTypeThatInheritsDeclarationsMayDropARedundantOne() {
        // Field case, with the mutation's own comment saying so: an interface extending a supertype
        // deleted its re-declaration of a method that supertype supplies. This heuristic cannot read
        // the supertype, so it must not guess which re-declarations are load-bearing.
        String established = """
                package a;
                public interface BookRepository extends JpaRepository<Book, Long> {
                    java.util.Optional<Book> findById(Long id);
                }
                """;
        String candidate = """
                package a;
                public interface BookRepository extends JpaRepository<Book, Long> {
                }
                """;
        assertThat(evaluate(Map.of(PATH, established), Map.of(PATH, candidate)).mutationState())
                .isEqualTo(ABSTAIN);
    }

    @Test
    void reworkingHowATypeIsWiredIsNotTruncation() {
        // Field case: a service moved from constructor injection to an annotated field. It lost a
        // constructor and GAINED annotations — a rework of the wiring, not a lost capability. Any
        // gain, member or annotation, means substitution.
        String established = """
                package a;
                public class BookService {
                    private final BookRepository repo;
                    public BookService(BookRepository repo) { this.repo = repo; }
                    public String findAll() { return "all"; }
                }
                """;
        String candidate = """
                package a;
                @Service
                public class BookService {
                    @Autowired
                    private BookRepository repo;
                    public String findAll() { return "all"; }
                }
                """;
        assertThat(evaluate(Map.of(PATH, established), Map.of(PATH, candidate)).mutationState())
                .isEqualTo(ABSTAIN);
    }

    @Test
    void aTruncatedFileIsCaughtEvenWhenItsAnnotationsAreIntact() {
        // The gap this half closes: the annotation check alone saw nothing here, because the
        // stereotype survived and only the methods vanished.
        String established = """
                package a;
                @Service
                public class BookService {
                    private final BookRepository repo;
                    public BookService(BookRepository repo) { this.repo = repo; }
                    public String findAll() { return "all"; }
                    public String findById(Long id) { return "one"; }
                    public String save(String b) { return b; }
                }
                """;
        String candidate = """
                package a;
                @Service
                public class BookService {
                    private final BookRepository repo;
                    public BookService(BookRepository repo) { this.repo = repo; }
                    public String findById(Long id) { return "one"; }
                }
                """;
        HeuristicOpinion opinion = evaluate(Map.of(PATH, established), Map.of(PATH, candidate));
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.rationale()).contains("findAll").contains("save");
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
