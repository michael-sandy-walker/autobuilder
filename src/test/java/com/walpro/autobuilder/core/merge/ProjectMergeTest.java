package com.walpro.autobuilder.core.merge;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Two parents that grew one adopted tree, merged into one child: code, goal tree, criteria, seed. */
class ProjectMergeTest {

    private static final String MAIN = "src/main/java/org/warp/Coil.java";
    private static final String ADOPTED = "package org.warp;\npublic class Coil {\n    public int turns() { return 1; }\n}\n";
    private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");

    @TempDir
    Path tmp;

    @Test
    void theChildCarriesBothParentsCodeTreesAndCriteriaWithProvenance() throws IOException {
        Path a = parent("a", ADOPTED.replace("    public int turns", "    public int loops() { return 2; }\n    public int turns"),
                "ASpecTest", "Coil.loops() returns 2", true,
                "Coil.loops() returns 2\nrule: change only Coil\ngate: tests The whole test suite passes\n");
        Path b = parent("b", ADOPTED.replace("    public int turns", "    public int knots() { return 3; }\n    public int turns"),
                "BSpecTest", "Coil.loops() returns 2", null, "Coil.knots() returns 3\n");

        ProjectMerge.Result r = ProjectMerge.merge(new ProjectMerge.Parent("a", "run-a", a),
                new ProjectMerge.Parent("b", "run-b", b), NOW);

        assertThat(r.conflicts()).isEmpty();
        assertThat(r.tree().get(MAIN)).contains("loops()").contains("knots()").contains("turns()");
        assertThat(r.tree()).containsKeys("src/test/java/org/warp/ASpecTest.java", "src/test/java/org/warp/BSpecTest.java");

        JsonNode root = r.goalStack().path("goalTree");
        assertThat(root.path("type").asText()).isEqualTo("GOAL");
        assertThat(root.path("subGoals")).hasSize(2);
        JsonNode fromA = root.path("subGoals").get(0);
        assertThat(fromA.path("type").asText()).isEqualTo("SUBGOAL");
        assertThat(fromA.path("metadata").path("mergedFrom").asText()).isEqualTo("a");
        assertThat(fromA.path("metadata").path("sourceGoalId").asText()).isEqualTo("root-a");
        assertThat(fromA.path("metadata").path("sourceLineageId").asText()).isEqualTo("lineage-a");
        JsonNode leafA = fromA.path("subGoals").get(0);
        assertThat(leafA.path("id").asText()).isNotEqualTo("leaf-a");
        assertThat(leafA.path("metadata").has("criteriaPassStatus")).isFalse();
        assertThat(leafA.path("metadata").path("parentCriteriaPassStatus").path("1").asBoolean()).isTrue();

        // The same text from both parents is one criterion with two origins; rules are not carried; gates are tagged.
        Map<String, ProjectMerge.Criterion> byText = new java.util.HashMap<>();
        r.criteria().forEach(c -> byText.put(c.text(), c));
        assertThat(byText.get("Coil.loops() returns 2").origins()).extracting(ProjectMerge.Origin::parent)
                .containsExactly("a", "a", "b");
        assertThat(byText).containsKey("Coil.knots() returns 3").doesNotContainKey("change only Coil");
        assertThat(byText.get("The whole test suite passes").gateTests()).isTrue();
        assertThat(r.bootstrap().get("seed.txt")).contains("## a").contains("## b");

        // Before manifests existed: adopted text plus the paths protected whole are what is as adopted.
        assertThat(r.manifest()).containsKeys(MAIN, "src/test/java/org/warp/ASpecTest.java", "src/test/java/org/warp/BSpecTest.java");
        assertThat(r.manifest().get(MAIN)).isEqualTo(ProjectMerge.sha256(ADOPTED));

        // A re-run gives the same tree.
        ProjectMerge.Result again = ProjectMerge.merge(new ProjectMerge.Parent("a", "run-a", a),
                new ProjectMerge.Parent("b", "run-b", b), NOW);
        assertThat(again.goalStack()).isEqualTo(r.goalStack());
    }

    @Test
    void parentsThatDidNotStartFromTheSameTextAreRefused() throws IOException {
        Path a = parent("a", ADOPTED, "ASpecTest", "x", null, "x\n");
        Path b = parent("b", ADOPTED, "BSpecTest", "y", null, "y\n");
        Files.writeString(b.resolve("meta/adoption-baseline").resolve(MAIN), ADOPTED.replace("1", "9"));
        assertThatThrownBy(() -> ProjectMerge.merge(new ProjectMerge.Parent("a", "ra", a), new ProjectMerge.Parent("b", "rb", b), NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("same adopted text");
    }

    private Path parent(String name, String main, String specTest, String criterion, Boolean verdict, String obligations)
            throws IOException {
        Path dir = tmp.resolve(name);
        write(dir.resolve("output").resolve(MAIN), main);
        write(dir.resolve("output/pom.xml"), "<project/>");
        write(dir.resolve("output/src/test/java/org/warp/" + specTest + ".java"),
                "package org.warp;\nclass " + specTest + " {\n    @org.junit.jupiter.api.Test void t() { }\n}\n");
        write(dir.resolve("meta/adoption-baseline").resolve(MAIN), ADOPTED);
        write(dir.resolve("meta/run-identity.json"), "{\"lineageId\":\"lineage-" + name + "\"}");
        String seed = "Grow the coil, parent " + name + ".";
        write(dir.resolve("bootstrap/seed.txt"), seed);
        write(dir.resolve("bootstrap/seed.obligations.txt"),
                com.walpro.autobuilder.goal.SeedObligationsFile.render(seed, List.of(obligations.strip().split("\n"))));
        write(dir.resolve("bootstrap/protected-paths.txt"), "src/test/**\nmembers: src/main/**\n");
        String status = verdict == null ? "" : ", \"criteriaPassStatus\": {\"1\": " + verdict + "}";
        write(dir.resolve("meta/GoalStack.json"), """
                {"currentGoalId": "root-%1$s", "goalTree": {"id": "root-%1$s", "description": "Grow the coil", "type": "GOAL",
                  "status": "COMPLETED", "metadata": {},
                  "subGoals": [{"id": "leaf-%1$s", "description": "Add a method", "type": "SUBGOAL", "status": "COMPLETED",
                    "metadata": {"acceptanceCriteria": ["%2$s"]%3$s}, "subGoals": []}]}}
                """.formatted(name, criterion, status));
        return dir;
    }

    private static void write(Path p, String text) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }
}
