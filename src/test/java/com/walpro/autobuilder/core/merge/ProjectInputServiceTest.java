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
        service = new ProjectInputService(goals, files, manifest, gate, verifier, none);
    }

    private ProjectInputService.Report bring() throws IOException {
        return service.bring(new ProjectInputService.Request("knots", "run-k", brought, INTENT, "operator"));
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

        assertThat(goals.requirements().obligationsOrClauses()).contains("A knot holds.");
        assertThat(goals.requirements().answeredByGate("tests")).contains("The suite passes.");
        assertThat(files.readFile("meta/inputs/" + input.id() + "/placed.txt")).contains("org/knot/Knot.java");
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
        assertThat(goals.getGoalById(r.goalId()).getMetadata().get("acceptanceCriteria")).asList().containsExactly("Knot is final");
        GoalEntity carried = goals.getAllGoals().stream().filter(g -> "Tie knots".equals(g.getDescription())).findFirst().orElseThrow();
        assertThat(carried.getMetadata().get("criteriaPassStatus").toString()).contains("2=false").contains("1=true");
    }

    @Test
    void theInputsFilesAreNotTheLoopsChanges_andItsTestsAreProtected() throws IOException {
        bring();

        assertThat(manifest.changes()).hasValueSatisfying(c -> assertThat(c.changedOrAdded()).isEmpty());
        assertThat(new ProtectedPaths(files).isProtected("src/test/java/org/knot/KnotTest.java")).isTrue();
        assertThat(new ProtectedPaths(files).isProtected("src/main/java/org/knot/Knot.java")).isFalse();
    }

    @Test
    void thereIsNothingToBringItIntoBeforeAProjectExists_andAnInputNeedsAnIntent() throws Exception {
        assertThatThrownBy(() -> service.bring(new ProjectInputService.Request("knots", "r", brought, " ", "operator")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
