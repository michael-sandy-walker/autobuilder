package com.walpro.autobuilder.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.walpro.autobuilder.analysis.structure.CommandSurfaceQuery;
import com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery;
import com.walpro.autobuilder.analysis.structure.SpecEndpointBehaviourQuery;
import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.build.BootGate;
import com.walpro.autobuilder.core.build.ExercisableCriterionQuery;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.graph.goal.GoalGraphService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * T2-FL: the loop promotes a work item the artifact has already been OBSERVED to satisfy.
 *
 * <p>Built from run {@code 2026-09-22_15-59-33} — {@code terminal=deadline}, a 23/23 artifact,
 * and four ACTIVE leaves naming endpoints that artifact served. The leaf descriptions, their
 * acceptance criteria and the request mappings below are that run's, verbatim from its archived
 * goal tree and output tree, so these tests fail if the promotion stops resolving the situation
 * it was built for.
 *
 * <p>The non-service case is here for the same reason it is in the mechanism: nothing in the
 * promotion names a protocol, and a command-line target is promoted on the exit statuses its
 * criteria state without a single endpoint in the tree.
 */
@ExtendWith(MockitoExtension.class)
class ObservedLeafPromotionTest {

    @Mock private FileService fileService;
    @Mock private GoalGraphService goalGraphService;
    @Mock private GoalPlannerConfigProperties goalPlannerConfig;

    /** The specification run 2026-09-22_15-59-33 was given, abbreviated to the parts probed. */
    private static final String SEED = """
            Evolve the Java 21 Spring Boot application into a library book-lending REST API.

            Members: POST /members (create; email must be unique — 409 on duplicate),
             GET /members/{id} (404 if absent).

            Loans: POST /loans (lend a book; 404 if either not found; 409 if book is already
             on loan; a member may hold at most 3 active loans — 422 if exceeded),
             POST /loans/{id}/return (mark returned; 404 if loan absent; 409 if already
             returned), GET /loans?memberId={id} (list all loans for a member).

            Success statuses: a create returns HTTP 201 with the created object as the body;
            a successful read returns HTTP 200.
            """;

    /** The request mappings that run's final tree carried — the artifact the smoke scored 23/23. */
    private static final Map<String, String> SERVED_ARTIFACT = Map.of(
            "src/main/java/com/example/library/api/MemberController.java", """
            @RestController
            @RequestMapping("/members")
            class MemberController {
                @PostMapping Object create(Object b) { return b; }
                @GetMapping Object all() { return null; }
                @GetMapping("/{id}") Object one(Long id) { return null; }
            }
            """,
            "src/main/java/com/example/library/api/LoanController.java", """
            @RestController
            @RequestMapping("/loans")
            class LoanController {
                @PostMapping Object create(Object b) { return b; }
                @PostMapping("/{id}/return") Object give(Long id) { return null; }
                @GetMapping Object forMember(Long memberId) { return null; }
            }
            """);

    /** The four leaves left ACTIVE at that run's deadline, with the criteria they were given. */
    private static final Map<String, List<String>> RESIDUAL_LEAVES = new LinkedHashMap<>(Map.of(
            "Implement GET /members/{id} endpoint in MemberController", List.of(
                    "GET /members/{id} returns HTTP 200 with the member body when the member exists",
                    "GET /members/{id} returns HTTP 404 with JSON body {\"message\": \"<reason>\"} when the member does not exist"),
            "Implement POST /loans endpoint in LoanController", List.of(
                    "POST /loans returns HTTP 201 with the created loan body on success",
                    "POST /loans returns HTTP 404 with JSON body {\"message\": \"<reason>\"} when bookId or memberId is not found",
                    "POST /loans returns HTTP 409 with JSON body {\"message\": \"<reason>\"} when the book is already on an active loan",
                    "POST /loans returns HTTP 422 with JSON body {\"message\": \"<reason>\"} when the member already holds 3 active loans"),
            "Implement POST /loans/{id}/return endpoint in LoanController", List.of(
                    "POST /loans/{id}/return returns HTTP 200 with the updated loan body on success",
                    "POST /loans/{id}/return returns HTTP 404 with JSON body {\"message\": \"<reason>\"} when the loan does not exist",
                    "POST /loans/{id}/return returns HTTP 409 with JSON body {\"message\": \"<reason>\"} when the loan is already returned")));

    // ------------------------------------------- a leaf asserting only the launch (T2-FL)

    @Test
    void aLeafAssertingOnlyThatTheArtifactRunsIsPromotedWhenTheLaunchWasObserved() throws IOException {
        // The exact criterion run 2026-09-23_18-35-30 declined three times as unreadable, while
        // that run's boot gate ran twice and establishes precisely it.
        GoalPlannerService service = serviceWith(SEED, SERVED_ARTIFACT, new LinkedHashMap<>(Map.of(
                "Make the application boot",
                List.of("The application starts successfully without exiting with code 1."))));

        int promoted = service.promoteSatisfiedLeaves(observing(List.of("launch \u2192 runs")));

        assertThat(promoted).isEqualTo(1);
        assertThat(service.getRemainingGoals()).isEmpty();
    }

    @Test
    void thatSameLeafKeepsItsPlaceWhenTheLaunchWasNotObserved() throws IOException {
        // The claim now PARSES, so the decline has to come from the evidence rather than from the
        // reader -- which is the difference this change is supposed to make.
        GoalPlannerService service = serviceWith(SEED, SERVED_ARTIFACT, new LinkedHashMap<>(Map.of(
                "Make the application boot",
                List.of("The application starts successfully without exiting with code 1."))));

        assertThat(service.promoteSatisfiedLeaves(observing(List.of("GET /books \u2192 200")))).isZero();
        assertThat(service.getRemainingGoals()).hasSize(1);
    }

    // ------------------------------------------------- the sweep says what it did (T2-FL)
    //
    // Run 2026-09-23_17-47-43 promoted nothing and could not say why: the refusals went to
    // log.debug and the run logged at INFO, emitting zero DEBUG lines all run, so "declined on
    // every leaf" and "never had a candidate" were byte-identical silence. These three tests pin
    // the distinction that ambiguity cost, one per reading the watch has to tell apart.

    @Test
    void aSweepThatPromotesReportsTheCandidatesItConsidered() throws IOException {
        GoalPlannerService service = serviceWith(SEED, SERVED_ARTIFACT, RESIDUAL_LEAVES);
        BehaviourLogger behaviourLogger = mock(BehaviourLogger.class);
        service.setBehaviourLogger(behaviourLogger);

        service.promoteSatisfiedLeaves(observing(claimsOf(service, RESIDUAL_LEAVES)));

        verify(behaviourLogger).observedLeafPromotion(
                eq("observed-leaf-promotion"), eq(3), eq(3), eq(0), eq(0), any());
    }

    @Test
    void aSweepThatDeclinesEveryLeafSaysSoAndNamesWhatItObjectedTo() throws IOException {
        GoalPlannerService service = serviceWith(SEED, SERVED_ARTIFACT, RESIDUAL_LEAVES);
        BehaviourLogger behaviourLogger = mock(BehaviourLogger.class);
        service.setBehaviourLogger(behaviourLogger);

        // An abstained observation judged nothing, so every leaf carrying a behavioural criterion
        // is declined -- the shape the watch reads as OPPORTUNITY-NO-FIRE.
        service.promoteSatisfiedLeaves(BootGate.BootObservation.abstained());

        ArgumentCaptor<List<String>> reasons = ArgumentCaptor.forClass(List.class);
        verify(behaviourLogger).observedLeafPromotion(
                anyString(), eq(3), eq(0), eq(3), eq(0), reasons.capture());
        assertThat(reasons.getValue()).isNotEmpty();
    }

    @Test
    void aSweepWithNoCandidateLeafIsDistinguishableFromOneThatDeclinedThemAll() throws IOException {
        // The leaf states no criteria, so the sweep has no question to ask about it. candidates=0
        // is what separates this from the test above; both promote nothing.
        GoalPlannerService service = serviceWith(SEED, SERVED_ARTIFACT,
                new LinkedHashMap<>(Map.of("Implement GET /members/{id} endpoint in MemberController", List.of())));
        BehaviourLogger behaviourLogger = mock(BehaviourLogger.class);
        service.setBehaviourLogger(behaviourLogger);

        service.promoteSatisfiedLeaves(observing(List.of("GET /members/{} \u2192 200")));

        verify(behaviourLogger).observedLeafPromotion(
                anyString(), eq(0), eq(0), eq(0), eq(0), any());
    }

    // ---------------------------------------------------------------- the archived run

    @Test
    void theResidualLeavesOfADeadlineRunArePromotedWhenTheArtifactWasObservedToSatisfyThem() throws IOException {
        GoalPlannerService service = serviceWith(SEED, SERVED_ARTIFACT, RESIDUAL_LEAVES);

        int promoted = service.promoteSatisfiedLeaves(observing(claimsOf(service, RESIDUAL_LEAVES)));

        assertThat(promoted).isEqualTo(3);
        assertThat(service.getRemainingGoals()).isEmpty();
    }

    @Test
    void aLeafKeepsItsPlaceWhenOneOfItsCriteriaWasNeverJudged() throws IOException {
        GoalPlannerService service = serviceWith(SEED, SERVED_ARTIFACT, RESIDUAL_LEAVES);
        List<String> claims = new ArrayList<>(claimsOf(service, RESIDUAL_LEAVES));
        // The 422 the run's own sweep reported `unprobeable` — the member-loan cap needs a
        // precondition the probe could not build. Everything else about that leaf was observed.
        claims.remove("POST /loans → 422");

        int promoted = service.promoteSatisfiedLeaves(observing(claims));

        assertThat(promoted).isEqualTo(2);
        assertThat(service.getRemainingGoals())
                .extracting(GoalEntity::getDescription)
                .containsExactly("Implement POST /loans endpoint in LoanController");
    }

    @Test
    void anObservationThatEstablishedNothingPromotesNothing() throws IOException {
        GoalPlannerService service = serviceWith(SEED, SERVED_ARTIFACT, RESIDUAL_LEAVES);

        assertThat(service.promoteSatisfiedLeaves(BootGate.BootObservation.abstained())).isZero();
        assertThat(service.getRemainingGoals()).hasSize(3);
    }

    @Test
    void aLeafDeclaringNoCriteriaIsNeverPromoted() throws IOException {
        GoalPlannerService service = serviceWith(SEED, SERVED_ARTIFACT,
                new LinkedHashMap<>(Map.of("Implement GET /members/{id} endpoint in MemberController", List.of())));

        assertThat(service.promoteSatisfiedLeaves(observing(List.of("GET /members/{} → 200")))).isZero();
        assertThat(service.getRemainingGoals()).hasSize(1);
    }

    @Test
    void aLeafIsNotPromotedWhileTheEndpointItsDescriptionDemandsHasNoRequestMapping() throws IOException {
        Map<String, String> withoutLoans = Map.of(
                "src/main/java/com/example/library/api/MemberController.java",
                SERVED_ARTIFACT.get("src/main/java/com/example/library/api/MemberController.java"));
        GoalPlannerService service = serviceWith(SEED, withoutLoans, new LinkedHashMap<>(Map.of(
                "Implement POST /loans endpoint in LoanController",
                List.of("POST /loans returns HTTP 201 with the created loan body on success"))));

        // The criterion was observed passing — against some other tree, or an earlier one. The
        // description's own demand is absent from this output, so the structure outranks it.
        assertThat(service.promoteSatisfiedLeaves(observing(List.of("POST /loans → 201")))).isZero();
        assertThat(service.getRemainingGoals()).hasSize(1);
    }

    // ------------------------------------------------- the coverage-retro family (structural)

    /**
     * T2-FL, from run {@code 2026-09-23_02-48-06}: the leaf that held the root open for that whole
     * run. Its criterion demands only that the endpoint EXIST, {@code GET /loans} was served by
     * {@code LoanController} the entire time, and the first shipped version refused it for want of
     * a behavioural claim a structural criterion is never going to have.
     *
     * <p>This is the coverage-retro family, measured at 16 then 17 across the same 40-run window
     * that gave the planner-authored family its 3 then 14 — so it is not a corner case, and
     * promoting it is INSIDE the false-completion boundary rather than against it: the criterion
     * states no runtime behaviour, so there is nothing a structure check could miss.
     */
    @Test
    void aLeafDemandingOnlyThatAnEndpointExistIsPromotedWhenTheSourcesProvideIt() throws IOException {
        GoalPlannerService service = serviceWith(SEED, SERVED_ARTIFACT, new LinkedHashMap<>(Map.of(
                "Provide the GET /loans endpoint named in the goal specification " + COVERAGE_TAIL,
                List.of("A request mapping for GET /loans exists in the application sources"))));

        // No observation at all: a structural criterion needs none, and being gated on one is the
        // defect this test pins.
        int promoted = service.promoteSatisfiedLeaves(BootGate.BootObservation.abstained());

        assertThat(promoted).isEqualTo(1);
        assertThat(service.getRemainingGoals()).isEmpty();
    }

    @Test
    void aLeafDemandingAnEndpointTheSourcesDoNotProvideIsNotPromoted() throws IOException {
        Map<String, String> withoutLoans = Map.of(
                "src/main/java/com/example/library/api/MemberController.java",
                SERVED_ARTIFACT.get("src/main/java/com/example/library/api/MemberController.java"));
        GoalPlannerService service = serviceWith(SEED, withoutLoans, new LinkedHashMap<>(Map.of(
                "Provide the GET /loans endpoint named in the goal specification " + COVERAGE_TAIL,
                List.of("A request mapping for GET /loans exists in the application sources"))));

        assertThat(service.promoteSatisfiedLeaves(BootGate.BootObservation.abstained())).isZero();
        assertThat(service.getRemainingGoals()).hasSize(1);
    }

    /**
     * The structural clause inverts a template this codebase owns. A criterion that merely reads
     * like it — prose about an endpoint, without the template — must not be decided by it.
     */
    @Test
    void aCriterionThatMerelyResemblesTheTemplateIsNotDecidedByIt() throws IOException {
        GoalPlannerService service = serviceWith(SEED, SERVED_ARTIFACT, new LinkedHashMap<>(Map.of(
                "Provide the GET /loans endpoint named in the goal specification " + COVERAGE_TAIL,
                List.of("GET /loans should probably exist somewhere in the sources"))));

        assertThat(service.promoteSatisfiedLeaves(BootGate.BootObservation.abstained())).isZero();
        assertThat(service.getRemainingGoals()).hasSize(1);
    }

    private static final String COVERAGE_TAIL =
            "— no request mapping for it exists in the output.";

    // ---------------------------------------------------------------- the non-service target

    private static final String COMMAND_SEED = """
            Evolve the Java 21 application into a COMMAND-LINE tool that summarises delimited files.

            count <file> — print the number of data rows to stdout as a bare integer.
            Exit 3 when the command word is not one of the two above.
            """;

    @Test
    void aTargetWithNoServedSurfaceIsPromotedOnTheExitStatusesItsCriteriaState() throws IOException {
        GoalPlannerService service = serviceWith(COMMAND_SEED,
                Map.of("src/main/java/com/example/Main.java", "class Main { }"),
                new LinkedHashMap<>(Map.of("Implement the unknown-command path", List.of(
                        "Running `java -jar app.jar unknown` exits with code 3"))));

        List<String> claims = claimsOf(service, Map.of("Implement the unknown-command path", List.of(
                "Running `java -jar app.jar unknown` exits with code 3")));
        assertThat(claims).isNotEmpty(); // the claim is readable without any endpoint in sight

        assertThat(service.promoteSatisfiedLeaves(observing(claims))).isEqualTo(1);
        assertThat(service.getRemainingGoals()).isEmpty();
    }

    // ---------------------------------------------------------------- what the probe is asked

    @Test
    void theObservationAsksTheOpenWorkItemsQuestionsAndNotOnlyTheSpecificationsOwn() throws IOException {
        GoalPlannerService service = serviceWith(SEED, SERVED_ARTIFACT, RESIDUAL_LEAVES);
        ExercisableCriterionQuery claimQuery =
                new ExercisableCriterionQuery(new CommandSurfaceQuery(), "java -jar {artifact}");

        List<String> specOnly = service.specDerivedRequirements();
        List<String> observed = service.requirementsWorthObserving();

        // The claim no specification prose states: a success status written once for every
        // resource does not attach to a path the way an error status does, so this reaches the
        // probe only because a work item asked for it.
        assertThat(claimsIn(claimQuery, specOnly, SEED)).doesNotContain("GET /members/{} → 200");
        assertThat(claimsIn(claimQuery, observed, SEED)).contains("GET /members/{} → 200");

        // One question, two spellings, one probe. The specification and the work item both state
        // that a loan on an already-lent book answers 409; the first spelling to be read keeps the
        // slot and the second never reaches the probe, which is what pays for the additions
        // instead of the list simply growing by every criterion the tree holds.
        List<String> claims = claimsIn(claimQuery, observed, SEED);
        assertThat(claims).doesNotHaveDuplicates();
        assertThat(observed).contains("POST /loans returns HTTP 409 — if book is already");
        assertThat(observed).doesNotContain(
                "POST /loans returns HTTP 409 with JSON body {\"message\": \"<reason>\"} when the book is already on an active loan");
    }

    // ---------------------------------------------------------------- fixtures

    private static BootGate.BootObservation observing(List<String> claims) {
        return new BootGate.BootObservation(true, List.of(), claims, 0L, Optional.empty());
    }

    private static List<String> claimsIn(ExercisableCriterionQuery q, List<String> criteria, String spec) {
        return criteria.stream().map(c -> q.claimOf(c, spec)).flatMap(Optional::stream).toList();
    }

    /** The claims a probe would record for these criteria, derived the way the gate derives them. */
    private static List<String> claimsOf(GoalPlannerService service, Map<String, List<String>> leaves) {
        ExercisableCriterionQuery q = new ExercisableCriterionQuery(new CommandSurfaceQuery(), "java -jar {artifact}");
        String spec = service.getRootGoal().getDescription();
        return leaves.values().stream().flatMap(List::stream)
                .map(c -> q.claimOf(c, spec)).flatMap(Optional::stream).distinct().toList();
    }

    private GoalPlannerService serviceWith(String seed, Map<String, String> output,
                                           Map<String, List<String>> leaves) throws IOException {
        lenient().doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        lenient().doNothing().when(goalGraphService).setParent(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).save(any(GoalEntity.class));
        lenient().when(goalPlannerConfig.isStructureQueryEnabled()).thenReturn(true);

        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
        service.setExercisableCriterionQuery(
                new ExercisableCriterionQuery(new CommandSurfaceQuery(), "java -jar {artifact}"));
        service.setEndpointSurfaceQuery(new EndpointSurfaceQuery());
        service.setSpecBehaviourQuery(new SpecEndpointBehaviourQuery());
        service.setCurrentCycleContext(CycleContextData.builder()
                .cycleName("observed-leaf-promotion")
                .outputPath("output")
                .previousOutput(output)
                .build());

        GoalEntity root = new GoalEntity("G", seed, "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(root.getId(), root);

        int n = 0;
        for (var leaf : leaves.entrySet()) {
            GoalEntity g = new GoalEntity("L" + n++, leaf.getKey(), "planner", GoalType.SUBGOAL, "G");
            g.setStatus(GoalStatus.ACTIVE);
            if (!leaf.getValue().isEmpty()) {
                g.getMetadata().put("acceptanceCriteria", leaf.getValue());
            }
            goalMap.put(g.getId(), g);
        }
        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        ReflectionTestUtils.setField(service, "rootGoalId", "G");
        return service;
    }
}
