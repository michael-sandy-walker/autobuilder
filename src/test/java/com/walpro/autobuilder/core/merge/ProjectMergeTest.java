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

/** One project integrated into another as a new child, by layout. */
class ProjectMergeTest {

    private static final String MAIN = "src/main/java/org/warp/Coil.java";
    private static final String ADOPTED = "package org.warp;\npublic class Coil {\n    public int turns() { return 1; }\n}\n";
    private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");

    @TempDir
    Path tmp;

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

    @Test
    void integrateLaysOutTheChildByLayoutUnderANewRootHoldingTheGoal() throws IOException {
        Path a = parent("a", ADOPTED, "ASpecTest", "Coil.turns() returns 1", true, "Coil.turns() returns 1\nrule: change only Coil\n");
        Path b = tmp.resolve("b");
        write(b.resolve("output/pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>org.knot</groupId>"
                + "<artifactId>knots</artifactId><version>1.0</version><dependencies><dependency><groupId>org.rope</groupId>"
                + "<artifactId>rope</artifactId><version>2</version></dependency></dependencies></project>");
        write(b.resolve("output/src/main/java/org/knot/Knot.java"), "package org.knot;\npublic class Knot { }\n");
        write(b.resolve("output/src/test/java/org/knot/KnotSpecTest.java"), "package org.knot;\nclass KnotSpecTest { }\n");
        write(b.resolve("meta/GoalStack.json"), "{\"goalTree\": {\"id\": \"root-b\", \"description\": \"Tie knots\", \"type\": \"GOAL\","
                + " \"status\": \"COMPLETED\", \"metadata\": {\"acceptanceCriteria\": [\"Knot exists\"]}, \"subGoals\": []}}");
        write(b.resolve("bootstrap/seed.txt"), "Tie knots.");
        write(b.resolve("bootstrap/protected-paths.txt"), "src/test/**\n");
        write(a.resolve("output/pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>org.warp</groupId>"
                + "<artifactId>coil</artifactId><version>0.1</version></project>");
        String goal = "  Integrate knots into coil.\n";
        ProjectMerge.Parent into = new ProjectMerge.Parent("coil", "run-a", a), brought = new ProjectMerge.Parent("knots", "run-b", b);

        ProjectMerge.Result packages = ProjectMerge.integrate(into, brought, ProjectMerge.Layout.PACKAGES, goal, List.of("Coil ties a Knot"), NOW);
        assertThat(packages.tree()).containsKeys(MAIN, "src/main/java/org/knot/Knot.java", "src/test/java/org/knot/KnotSpecTest.java");
        assertThat(packages.tree().get("pom.xml")).contains("<artifactId>coil</artifactId>").contains("<artifactId>rope</artifactId>");
        JsonNode root = packages.goalStack().path("goalTree");
        assertThat(root.path("description").asText()).isEqualTo(goal);
        assertThat(root.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(root.path("subGoals")).extracting(n -> n.path("metadata").path("mergedFrom").asText()).containsExactly("coil", "knots");
        String obligations = packages.bootstrap().get("seed.obligations.txt");
        assertThat(com.walpro.autobuilder.goal.SeedObligationsFile.read(obligations, goal).state().name()).isEqualTo("PRISTINE");
        assertThat(obligations).contains("Coil ties a Knot").contains("Coil.turns() returns 1").doesNotContain("change only Coil");

        ProjectMerge.Result modules = ProjectMerge.integrate(into, brought, goal, List.of("x"), NOW);   // the default layout
        assertThat(modules.tree()).containsKeys("coil/" + MAIN, "knots/src/main/java/org/knot/Knot.java", "coil/pom.xml", "knots/pom.xml");
        assertThat(modules.tree().get("pom.xml")).contains("<packaging>pom</packaging>").contains("<module>knots</module>")
                .contains("<module>coil</module>");
        assertThat(modules.tree().get("coil/pom.xml")).contains("<artifactId>knots</artifactId>");
        assertThat(modules.bootstrap().get("protected-paths.txt")).contains("coil/src/test/**", "knots/src/test/**",
                "members: coil/src/main/**");
    }
}
