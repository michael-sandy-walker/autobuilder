package com.walpro.autobuilder.core.merge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService;
import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.build.AdoptionManifest;
import com.walpro.autobuilder.core.build.ProtectedPaths;
import com.walpro.autobuilder.core.build.TestRunGate;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.SeedObligationsGenerator;
import com.walpro.autobuilder.goal.purpose.InputEntry;
import com.walpro.autobuilder.graph.goal.GoalGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** T4-EC slice 4: one project brought into a finished one, as one operation on its live state. */
class ProjectInputServiceTest {

    private static final String SEED = "Build a coil.";
    private static final String INTENT = "Coils can be tied with knots.";

    @TempDir
    Path base;
    @TempDir
    Path brought;

    private FileService files;
    private GoalPlannerService goals;
    private AdoptionManifest manifest;
    private ProjectInputService service;
    private final CriteriaVerificationService verifier = mock(CriteriaVerificationService.class);
    private final InputComparison comparison = mock(InputComparison.class);

    private static void write(Path p, String text) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        files = new FileService(base.toString(), new ObjectMapper());
        write(base.resolve("output/pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>o</groupId><artifactId>coil</artifactId><version>1</version></project>");
        write(base.resolve("output/src/main/java/org/warp/Coil.java"), "package org.warp;\npublic class Coil { }\n");
        write(brought.resolve("output/src/main/java/org/knot/Knot.java"), "package org.knot;\npublic class Knot { }\n");
        write(brought.resolve("output/src/test/java/org/knot/KnotTest.java"), "package org.knot;\nclass KnotTest { }\n");
        write(brought.resolve("bootstrap/seed.txt"), "Tie knots.");
        write(brought.resolve("bootstrap/seed.obligations.txt"), com.walpro.autobuilder.goal.SeedObligationsFile.render("Tie knots.",
                List.of("A knot holds.", "gate: tests The suite passes.")));
        write(brought.resolve("meta/GoalStack.json"), """
                {"goalTree": {"id": "root-k", "description": "Tie knots", "type": "GOAL", "status": "COMPLETED",
                  "metadata": {"acceptanceCriteria": ["Knot exists", "Knot is final"], "criteriaPassStatus": {"1": true, "2": true}},
                  "subGoals": []}}
                """);

        goals = new GoalPlannerService(files, mock(GoalGraphService.class), mock(GoalPlannerConfigProperties.class));
        goals.init();
        goals.addInitialGoal("root", SEED);
        goals.getRootGoal().setStatus(GoalStatus.COMPLETED);

        manifest = new AdoptionManifest(files);
        TestRunGate gate = mock(TestRunGate.class);
        when(gate.runOnTree(anyString(), anyBoolean())).thenReturn(Optional.empty());
        when(gate.freshReport()).thenReturn(Optional.empty());
        when(verifier.structuralAnswer(anyString(), any(), any())).thenReturn(null);
        when(verifier.verifyWithTestRun(anyString(), any(), any())).thenReturn(Map.of(1, true, 2, false));
        ObjectProvider<SeedObligationsGenerator> none = mock(ObjectProvider.class);
        service = new ProjectInputService(goals, files, manifest, gate, verifier, none, comparison);
    }

    private ProjectInputService.Report bring() throws IOException {
        return service.bring(new ProjectInputService.Request("knots", "run-k", brought, INTENT, "operator", false));
    }

    @Test
    void itLandsAsOneOperation_codeLedgerGoalsAndObligations() throws IOException {
        ProjectInputService.Report r = bring();

        assertThat(base.resolve("output/src/main/java/org/knot/Knot.java")).exists();
        assertThat(goals.purpose().ledger().entries()).hasSize(2);
        InputEntry input = goals.purpose().ledger().entries().get(1);
        assertThat(input.kind()).isEqualTo(InputEntry.KIND_PROJECT);
        assertThat(input.content()).isEqualTo(INTENT);
        assertThat(input.sourceRunId()).isEqualTo("run-k");

        assertThat(goals.getRootGoal().getStatus()).as("a finished root reopens").isEqualTo(GoalStatus.ACTIVE);
        GoalEntity landed = goals.getGoalById(r.goalId());
        assertThat(landed.getDescription()).isEqualTo(INTENT);
        assertThat(landed.getParentId()).isEqualTo("root");
        assertThat(landed.getMetadata()).containsEntry("inputId", input.id());
        assertThat(goals.getCurrentSubGoal().getId()).isEqualTo(r.goalId());
        assertThat(goals.getAllGoals()).filteredOn(g -> "Tie knots".equals(g.getDescription())).singleElement()
                .satisfies(g -> {
                    assertThat(g.getParentId()).isEqualTo(r.goalId());
                    assertThat(g.getMetadata()).containsEntry("inputId", input.id()).containsEntry("sourceGoalId", "root-k");
                });

        service.keep(r.inputId(), "operator");
        assertThat(goals.requirements().obligationsOrClauses()).contains("A knot holds.");
        assertThat(goals.requirements().answeredByGate("tests")).contains("The suite passes.");
        assertThat(files.readFile("meta/inputs/" + input.id() + "/placed.txt")).contains("org/knot/Knot.java");
    }

    /** field122: closure read the intent's "use" as met; what it names is recorded for structure to answer at closure. */
    @Test
    void theTypesTheIntentSaysToUseAreRecordedByPath() throws IOException {
        when(comparison.typesToUse(anyString(), anyString(), any())).thenReturn(List.of("Knot"));
        bring();
        InputEntry input = goals.purpose().ledger().entries().get(1);
        assertThat(files.readFile("meta/inputs/" + input.id() + IntentUse.USES_FILE).strip())
                .isEqualTo("src/main/java/org/knot/Knot.java");
    }

    /** field119: a goal with no criteria could never close, and the run sat on it from 39/39 to its deadline. */
    @Test
    void theInputsGoalClosesOnWhatTheIntentAsks() throws IOException {
        ProjectInputService.Report r = bring();

        assertThat(goals.getGoalById(r.goalId()).getMetadata().get("acceptanceCriteria")).asList().isNotEmpty();
    }

    @Test
    void whatTheMergedTreeDoesNotSatisfyStaysAsWork_andIsNotLeftToABootSideEffect() throws IOException {
        when(verifier.verifyWithTestRun(anyString(), any(), any())).thenAnswer(i -> {
            List<String> asked = i.getArgument(1);
            java.util.Map<Integer, Boolean> out = new java.util.HashMap<>();
            for (int n = 0; n < asked.size(); n++) out.put(n + 1, !asked.get(n).equals("Knot is final"));
            return out;
        });
        ProjectInputService.Report r = bring();

        assertThat(r.unmet()).containsExactly("Knot is final");
        assertThat(r.byModel()).isGreaterThan(0);
        assertThat(goals.getGoalById(r.goalId()).getMetadata().get("acceptanceCriteria")).asList().contains("Knot is final");
        GoalEntity carried = goals.getAllGoals().stream().filter(g -> "Tie knots".equals(g.getDescription())).findFirst().orElseThrow();
        assertThat(carried.getMetadata().get("criteriaPassStatus").toString()).contains("2=false").contains("1=true");
    }

    @Test
    void aWithdrawnInputLeavesTheTreeAndTheRequirements_theLedgerKeepsIt_andLaterWorkStays() throws IOException {
        seedLine();
        differs(TURNS, true);
        ProjectInputService.Report r = bring();
        write(base.resolve("output/src/main/java/org/warp/Later.java"), "package org.warp;\nclass Later { }\n");

        service.withdraw(r.inputId(), "operator");

        assertThat(base.resolve("output/src/main/java/org/knot/Knot.java")).doesNotExist();
        assertThat(base.resolve("output/src/main/java/org/warp/Later.java")).exists();
        assertThat(goals.purpose().ledger().entries()).hasSize(2);
        assertThat(goals.requirements().obligationsOrClauses()).as("what it replaced counts again").contains("A coil has at most 3 turns.")
                .doesNotContain("the turn limit: at most 9 turns", "A knot holds.");
        assertThat(goals.specification()).isEqualTo(SEED);
        assertThat(goals.getGoalById(r.goalId()).getStatus()).isEqualTo(GoalStatus.SUBSUMED);
        assertThat(goals.getAllGoals()).anyMatch(g -> g.getDescription().startsWith("The project builds and works without")
                && g.getStatus() == GoalStatus.ACTIVE);
        assertThat(new ProtectedPaths(files).isProtected("src/test/java/org/knot/KnotTest.java")).isFalse();
        assertThat(manifest.changes()).hasValueSatisfying(c -> assertThat(c.removed()).isEmpty());
    }

    @Test
    void aWithdrawnInputsOpenConflictsWaitForNobody_andTheSeedCannotBeWithdrawn() throws IOException {
        seedLine();
        differs(TURNS, false);
        ProjectInputService.Report r = bring();
        assertThat(goals.purpose().openConflicts()).hasSize(1);

        service.withdraw(r.inputId(), "operator");

        assertThat(goals.purpose().openConflicts()).isEmpty();
        String seed = goals.purpose().ledger().seed().id();
        assertThatThrownBy(() -> service.withdraw(seed, "operator")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.withdraw(r.inputId(), "operator")).isInstanceOf(IllegalArgumentException.class);
    }

    /** T4-EC slice 7: a half-made change is not landed over. */
    @Test
    void workUnderWayFinishesFirst() throws IOException {
        goals.addSubGoal("under-way", "Wind the coil", true);

        ProjectInputService.Report r = bring();

        assertThat(goals.getCurrentSubGoal().getId()).isEqualTo("under-way");
        assertThat(goals.getGoalById(r.goalId()).getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void theInputsFilesAreNotTheLoopsChanges_andWhilePendingItsTestsAreProtected() throws IOException {
        ProjectInputService.Report r = bring();

        assertThat(manifest.changes()).hasValueSatisfying(c -> assertThat(c.changedOrAdded()).isEmpty());
        assertThat(new ProtectedPaths(files).isProtected("src/test/java/org/knot/KnotTest.java")).isTrue();
        assertThat(new ProtectedPaths(files).isProtected("src/main/java/org/knot/Knot.java")).isFalse();

        service.keep(r.inputId(), "operator");
        assertThat(new ProtectedPaths(files).isProtected("src/test/java/org/knot/KnotTest.java"))
                .as("T4-EG: kept, its tests are the project's tests").isFalse();
    }

    /**
     * field118: the loop turned a third party's public constructor into a private factory, breaking its own tests --
     * what a PENDING input declares is kept. T4-EG: once kept it is the project's code (field, 2026-11-05: an imported
     * controller's message could not change to meet a test, and the run spent half an hour on one letter).
     */
    @Test
    void whatAPendingInputDeclaresIsKept_aKeptOneIsTheProjectsToChange() throws IOException {
        write(brought.resolve("output/src/main/java/org/knot/Knot.java"), "package org.knot;\npublic class Knot {\n    public Knot(int n) { }\n}\n");
        ProjectInputService.Report r = bring();
        String path = "src/main/java/org/knot/Knot.java";
        String rewritten = "package org.knot;\npublic class Knot {\n    private Knot(int n) { }\n    public static Knot of(int n) { return new Knot(n); }\n}\n";

        assertThat(new ProtectedPaths(files).preserve("evolution", path, rewritten)).contains("public Knot(int n)").contains("of(int n)");

        service.keep(r.inputId(), "operator");
        assertThat(new ProtectedPaths(files).preserve("evolution", path, rewritten)).isEqualTo(rewritten);
    }

    @Test
    void thereIsNothingToBringItIntoBeforeAProjectExists_andAnInputNeedsAnIntent() throws Exception {
        assertThatThrownBy(() -> service.bring(new ProjectInputService.Request("knots", "r", brought, " ", "operator", false)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void differs(InputComparison.Difference d, boolean settled) {
        when(comparison.sameConcept(anyString(), any(), anyString())).thenReturn(List.of("Coil"));
        when(comparison.differences(anyString(), anyString(), anyString())).thenReturn(List.of(d));
        when(comparison.settledByIntent(anyString(), any())).thenReturn(settled);
        when(comparison.stating(any(), any())).thenReturn(List.of("A coil has at most 3 turns."));
    }

    private void seedLine() throws IOException {
        files.writeFile(com.walpro.autobuilder.goal.SeedObligationsFile.PATH,
                com.walpro.autobuilder.goal.SeedObligationsFile.render(SEED, List.of("A coil has at most 3 turns.")));
    }

    private static final InputComparison.Difference TURNS = new InputComparison.Difference("the turn limit", "at most 3 turns", "at most 9 turns");

    @Test
    void aDifferenceTheIntentSettlesIsACorrection_theOldLineStopsCounting_andTheNewRuleIsARequirement() throws IOException {
        seedLine();
        differs(TURNS, true);

        ProjectInputService.Report r = bring();

        assertThat(r.corrections()).containsExactly("the turn limit");
        assertThat(r.conflicts()).isEmpty();
        service.keep(r.inputId(), "operator");
        assertThat(goals.requirements().obligationsOrClauses()).doesNotContain("A coil has at most 3 turns.")
                .contains("the turn limit: at most 9 turns");
        assertThat(goals.specification()).contains("the turn limit: at most 9 turns (this replaces: at most 3 turns)")
                .doesNotContain("---");
    }

    @Test
    void aDifferenceNothingSettlesIsHeld_andADecisionForTheInputReplacesTheRuleAndBecomesWork() throws IOException {
        seedLine();
        differs(TURNS, false);

        ProjectInputService.Report r = bring();

        assertThat(r.conflicts()).singleElement().satisfies(c -> assertThat(c.subject()).isEqualTo("the turn limit"));
        assertThat(goals.requirements().obligationsOrClauses()).as("still the receiving rule while open").contains("A coil has at most 3 turns.");
        assertThat(goals.purpose().openConflicts()).hasSize(1);

        var decided = service.decide(r.conflicts().get(0).id(), "input", "operator");

        assertThat(decided.status()).isEqualTo("decided");
        assertThat(decided.by()).isEqualTo("operator");
        assertThat(goals.purpose().openConflicts()).isEmpty();
        assertThat(goals.requirements().obligationsOrClauses()).as("decided, but not yet the project's")
                .contains("A coil has at most 3 turns.");
        service.keep(r.inputId(), "operator");
        assertThat(goals.requirements().obligationsOrClauses()).doesNotContain("A coil has at most 3 turns.")
                .contains("the turn limit: at most 9 turns");
        assertThat(goals.getAllGoals()).anyMatch(g -> g.getDescription().startsWith("the turn limit") && g.getStatus() == GoalStatus.ACTIVE);
    }

    /** field120: decided for 200 characters, and closure went on demanding the 120 the project's goals had banked. */
    @Test
    void aDecidedRuleReachesTheCriteriaBankedOnTheProjectsGoalsToo() throws IOException {
        goals.addSubGoal("done", "Limit the turns", false);
        goals.setAcceptanceCriteria("done", List.of("A coil of 4 turns is refused."));
        differs(TURNS, false);
        org.mockito.ArgumentCaptor<List<String>> asked = org.mockito.ArgumentCaptor.forClass(List.class);
        when(comparison.stating(asked.capture(), any())).thenReturn(List.of("A coil of 4 turns is refused."));

        ProjectInputService.Report r = bring();
        service.decide(r.conflicts().get(0).id(), "input", "operator");
        service.keep(r.inputId(), "operator");

        assertThat(asked.getValue()).contains("A coil of 4 turns is refused.");
        assertThat(goals.purpose().linesOut()).contains("A coil of 4 turns is refused.");
    }

    @Test
    void aDecisionForTheReceivingProjectChangesNothing_andAConflictIsDecidedOnce() throws IOException {
        seedLine();
        differs(TURNS, false);
        String id = bring().conflicts().get(0).id();

        service.decide(id, "receiving", "operator");

        assertThat(goals.requirements().obligationsOrClauses()).contains("A coil has at most 3 turns.");
        assertThat(goals.specification()).doesNotContain("decided:");
        assertThatThrownBy(() -> service.decide(id, "input", "operator")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.decide(id, "either", "operator")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCarriedClaimThatCannotBeTrueHereIsHeldOutOfTheRequirementsAndOutOfTheWork() throws IOException {
        when(comparison.contradicts(anyString(), anyString(), org.mockito.ArgumentMatchers.eq("Knot is final"))).thenReturn(true);
        when(verifier.verifyWithTestRun(anyString(), any(), any())).thenAnswer(i -> {
            List<String> asked = i.getArgument(1);
            java.util.Map<Integer, Boolean> out = new java.util.HashMap<>();
            for (int n = 0; n < asked.size(); n++) out.put(n + 1, !asked.get(n).equals("Knot is final"));
            return out;
        });
        write(brought.resolve("bootstrap/seed.obligations.txt"), com.walpro.autobuilder.goal.SeedObligationsFile.render("Tie knots.",
                List.of("A knot holds.", "Knot is final")));

        ProjectInputService.Report r = bring();

        assertThat(r.conflicts()).singleElement().satisfies(c -> assertThat(c.heldLine()).isEqualTo("Knot is final"));
        assertThat(r.unmet()).as("not work to do").isEmpty();
        assertThat(r.met()).as("every criterion but the claim").isEqualTo(r.criteria() - 1);
        assertThat(goals.requirements().obligationsOrClauses()).as("pending, none of it counts yet")
                .doesNotContain("Knot is final", "A knot holds.");

        service.decide(r.conflicts().get(0).id(), "input", "operator");
        service.keep(r.inputId(), "operator");
        assertThat(goals.requirements().obligationsOrClauses()).as("decided to stand").contains("Knot is final", "A knot holds.");
    }

    @Test
    void aFileBothHaveIsHeld_theReceivingOneStays_untilADecisionForTheInputPutsItsCopyInPlace() throws IOException {
        write(base.resolve("output/src/main/java/org/knot/Knot.java"), "package org.knot;\nclass Ours { }\n");
        ProjectInputService.Report r = bring();

        assertThat(r.conflicts()).singleElement().satisfies(c -> assertThat(c.subject()).endsWith("org/knot/Knot.java"));
        assertThat(Files.readString(base.resolve("output/src/main/java/org/knot/Knot.java"))).contains("Ours");

        service.decide(r.conflicts().get(0).id(), "input", "operator");
        assertThat(Files.readString(base.resolve("output/src/main/java/org/knot/Knot.java"))).contains("public class Knot");
    }

    @Test
    void aLandingShowsEachSideWithWhatItRefersTo() throws IOException {
        write(brought.resolve("output/src/main/java/org/knot/Rope.java"), "package org.knot;\npublic class Rope { Knot knot; }\n");
        when(comparison.sameConcept(anyString(), any(), org.mockito.ArgumentMatchers.contains("class Rope"))).thenReturn(List.of("Coil"));
        org.mockito.ArgumentCaptor<String> other = org.mockito.ArgumentCaptor.forClass(String.class);
        when(comparison.differences(anyString(), anyString(), other.capture())).thenReturn(List.of());

        bring();

        assertThat(other.getValue()).contains("class Rope").contains("class Knot");
    }

    /** T4-ED: neither side as it is -- the operator's own rule replaces the receiving one and becomes work. */
    @Test
    void aDifferenceDecidedInTheOperatorsOwnWords_replacesTheRuleAndBecomesWork() throws IOException {
        seedLine();
        differs(TURNS, false);
        ProjectInputService.Report r = bring();
        String id = r.conflicts().get(0).id();

        assertThatThrownBy(() -> service.decide(id, "own", "operator", " ")).isInstanceOf(IllegalArgumentException.class);
        var decided = service.decide(id, "own", "operator", "at most 5 turns");
        service.keep(r.inputId(), "operator");

        assertThat(decided.decision()).isEqualTo("own");
        assertThat(decided.resolution()).isEqualTo("at most 5 turns");
        assertThat(goals.purpose().openConflicts()).isEmpty();
        assertThat(goals.requirements().obligationsOrClauses()).doesNotContain("A coil has at most 3 turns.")
                .contains("the turn limit: at most 5 turns").doesNotContain("the turn limit: at most 9 turns");
        assertThat(goals.getAllGoals()).anyMatch(g -> g.getDescription().startsWith("the turn limit: at most 5")
                && g.getStatus() == GoalStatus.ACTIVE);
    }

    @Test
    void mergingAFileBothHave_keepsTheReceivingFileAndMakesTheMergeWork() throws IOException {
        write(base.resolve("output/src/main/java/org/knot/Knot.java"), "package org.knot;\nclass Ours { }\n");
        ProjectInputService.Report r = bring();

        service.decide(r.conflicts().get(0).id(), "own", "operator", "keep both constructors");

        assertThat(Files.readString(base.resolve("output/src/main/java/org/knot/Knot.java"))).contains("Ours");
        assertThat(goals.getAllGoals()).anyMatch(g -> g.getDescription().startsWith("Merge src/main/java/org/knot/Knot.java")
                && g.getDescription().contains("keep both constructors") && g.getStatus() == GoalStatus.ACTIVE);
    }

    @Test
    void aClaimDecidedInTheOperatorsOwnWords_staysOutAndTheirRequirementTakesItsPlace() throws IOException {
        when(comparison.contradicts(anyString(), anyString(), org.mockito.ArgumentMatchers.eq("Knot is final"))).thenReturn(true);
        when(verifier.verifyWithTestRun(anyString(), any(), any())).thenAnswer(i -> {
            List<String> asked = i.getArgument(1);
            java.util.Map<Integer, Boolean> out = new java.util.HashMap<>();
            for (int n = 0; n < asked.size(); n++) out.put(n + 1, !asked.get(n).equals("Knot is final"));
            return out;
        });
        write(brought.resolve("bootstrap/seed.obligations.txt"), com.walpro.autobuilder.goal.SeedObligationsFile.render("Tie knots.",
                List.of("A knot holds.", "Knot is final")));
        ProjectInputService.Report r = bring();

        service.decide(r.conflicts().get(0).id(), "own", "operator", "Knot may be extended by Rope only");
        service.keep(r.inputId(), "operator");

        assertThat(goals.requirements().obligationsOrClauses()).doesNotContain("Knot is final")
                .contains("Knot may be extended by Rope only");
    }

    @Test
    void nothingIsUnderWayOnceAnInputHasLanded_andTheOptionsAskAboutTheOpenConflict() throws IOException {
        seedLine();
        differs(TURNS, false);
        String id = bring().conflicts().get(0).id();

        assertThat(service.progress()).isEmpty();
        when(comparison.options(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(new InputComparison.Option("at most 5 turns", "a middle ground")));
        assertThat(service.options(id)).extracting(InputComparison.Option::resolution).containsExactly("at most 5 turns");
        org.mockito.Mockito.verify(comparison).options(anyString(), org.mockito.ArgumentMatchers.eq(INTENT),
                org.mockito.ArgumentMatchers.contains("rule"), org.mockito.ArgumentMatchers.eq("the turn limit"),
                org.mockito.ArgumentMatchers.eq("at most 3 turns"), org.mockito.ArgumentMatchers.eq("at most 9 turns"));
        service.decide(id, "receiving", "operator");
        assertThatThrownBy(() -> service.options(id)).isInstanceOf(IllegalArgumentException.class);
    }

    /** T4-ED: an import waits to be kept. */
    @Test
    void anImportCanWaitForConfirmation_untilItIsKeptOrTakenBackOut() throws IOException {
        seedLine();
        differs(TURNS, false);
        ProjectInputService.Report r = service.bring(new ProjectInputService.Request("knots", "run-k", brought, INTENT, "operator", false));

        assertThat(service.pending()).singleElement().satisfies(p -> {
            assertThat(p.inputId()).isEqualTo(r.inputId());
            assertThat(p.label()).isEqualTo("knots");
            assertThat(p.intent()).isEqualTo(INTENT);
            assertThat(p.newFiles()).isEqualTo(r.placed().size());
            assertThat(p.rulesThatDiffer()).isEqualTo(1);
        });
        assertThat(goals.purpose().openConflicts()).as("a pending import is not a conflict").hasSize(1);

        service.decide(goals.purpose().openConflicts().get(0).id(), "receiving", "operator");
        service.keep(r.inputId(), "operator");

        assertThat(service.pending()).isEmpty();
        assertThatThrownBy(() -> service.keep(r.inputId(), "operator")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCancelledImportWaitsForNothing() throws IOException {
        ProjectInputService.Report r = service.bring(new ProjectInputService.Request("knots", "run-k", brought, INTENT, "operator", false));

        service.withdraw(r.inputId(), "operator");

        assertThat(service.pending()).isEmpty();
    }

    /** T4-EG: every import lands pending, scripted or not; keeping it is the one way into the project. */
    @Test
    void everyImportLandsPending_andCountsForNothingUntilKept() throws IOException {
        String before = goals.purpose().specification();
        ProjectInputService.Report r = bring();

        assertThat(service.pending()).extracting(ProjectInputService.Import::inputId).containsExactly(r.inputId());
        assertThat(goals.purpose().specification()).as("a pending import is a proposal").isEqualTo(before);
    }

    /** T4-EG: a kept import's goals are the project's goals, and their rules count; a pending one's do not yet. */
    @Test
    void aKeptImportsGoalsStateTheProjectsRules_aPendingOnesDoNot() throws IOException {
        ProjectInputService.Report r = bring();
        @SuppressWarnings("unchecked")
        List<String> criteria = (List<String>) goals.getGoalById(r.goalId()).getMetadata().get("acceptanceCriteria");
        assertThat(criteria).isNotEmpty();

        assertThat(goals.statedRules()).doesNotContainAnyElementsOf(criteria);
        service.keep(r.inputId(), "operator");
        assertThat(goals.statedRules()).containsAll(criteria);
    }

    /** T4-EG: the pending stage is where conflicts are settled -- or the import is not continued. */
    @Test
    void anImportIsNotKeptWhileItsConflictsAreOpen() throws IOException {
        seedLine();
        differs(TURNS, false);
        ProjectInputService.Report r = bring();
        assertThat(goals.purpose().openConflicts()).hasSize(1);

        assertThatThrownBy(() -> service.keep(r.inputId(), "operator")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decide its conflicts");

        service.decide(goals.purpose().openConflicts().get(0).id(), "receiving", "operator");
        service.keep(r.inputId(), "operator");
        assertThat(service.pending()).isEmpty();
    }

    /** T4-ED: field -- one project brought in three times, 59 open conflicts, most of them repeats. */
    @Test
    void theSameTreeIsNotBroughtInTwice_untilTheFirstIsTakenBackOut() throws IOException {
        ProjectInputService.Report first = bring();

        assertThatThrownBy(this::bring).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already brought in").hasMessageContaining("take that import back out");

        service.withdraw(first.inputId(), "operator");
        assertThat(bring().inputId()).isNotEqualTo(first.inputId());
    }

    /** T4-ED: every import still in, kept or pending, each one takeable back out -- the home screen's list. */
    @Test
    void theImportsListShowsWhatIsStillIn_keptOrPending() throws IOException {
        ProjectInputService.Report kept = bring();
        service.keep(kept.inputId(), "operator");
        write(brought.resolve("output/src/main/java/org/knot/Extra.java"), "package org.knot;\npublic class Extra { }\n");
        ProjectInputService.Report waiting = service.bring(new ProjectInputService.Request("knots-2", "run-k2", brought, INTENT,
                "operator", false));

        assertThat(service.imports()).extracting(ProjectInputService.Import::inputId, ProjectInputService.Import::pending,
                        ProjectInputService.Import::label)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(kept.inputId(), false, "knots"),
                        org.assertj.core.groups.Tuple.tuple(waiting.inputId(), true, "knots-2"));
        assertThat(service.imports().get(0).newFiles()).isEqualTo(kept.placed().size());

        service.withdraw(kept.inputId(), "operator");

        assertThat(service.imports()).extracting(ProjectInputService.Import::inputId).containsExactly(waiting.inputId());
    }

    /**
     * T4-ED field: in a project of modules, two inputs with the same artifact id land in one module; the second's file
     * both have is named by its place here ("knots/..."), while the input holds it at its own root. The held copy was
     * looked for under the module name and the import failed.
     */
    @Test
    void inAProjectOfModules_aFileBothHaveIsHeldFromTheInputsOwnPath() throws IOException {
        Files.delete(base.resolve("output/src/main/java/org/warp/Coil.java"));
        write(base.resolve("output/pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>o</groupId><artifactId>all</artifactId>"
                + "<version>1</version><packaging>pom</packaging><modules></modules></project>");
        bring();
        assertThat(base.resolve("output/knots/src/main/java/org/knot/Knot.java")).exists();
        write(brought.resolve("output/src/main/java/org/knot/Knot.java"), "package org.knot;\npublic class Knot { int turns; }\n");

        ProjectInputService.Report second = service.bring(new ProjectInputService.Request("knots", "run-k2", brought, INTENT, "operator", false));

        assertThat(second.conflicts()).anySatisfy(c -> assertThat(c.subject()).isEqualTo("knots/src/main/java/org/knot/Knot.java"));
        String id = second.conflicts().stream().filter(c -> c.heldPath() != null).findFirst().orElseThrow().id();
        service.decide(id, "input", "operator");
        assertThat(Files.readString(base.resolve("output/knots/src/main/java/org/knot/Knot.java"))).contains("int turns");
    }
}
