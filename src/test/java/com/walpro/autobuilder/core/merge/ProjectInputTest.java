package com.walpro.autobuilder.core.merge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-EC slice 4: what one project brings to the project receiving it. */
class ProjectInputTest {

    private static final String MAIN = "src/main/java/org/warp/Coil.java";
    private static final String ADOPTED = "package org.warp;\npublic class Coil {\n    public int turns() { return 1; }\n}\n";
    private static final String POM = "<project><modelVersion>4.0.0</modelVersion><groupId>org.warp</groupId>"
            + "<artifactId>coil</artifactId><version>0.1</version></project>";

    @TempDir
    Path tmp;

    private static void write(Path p, String text) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    private static Map<String, String> receiving() {
        Map<String, String> tree = new TreeMap<>();
        tree.put("pom.xml", POM);
        tree.put(MAIN, ADOPTED);
        return tree;
    }

    private Path project(String name) throws IOException {
        Path dir = tmp.resolve(name);
        write(dir.resolve("output/pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>org.knot</groupId>"
                + "<artifactId>knots</artifactId><version>1.0</version><dependencies><dependency><groupId>org.rope</groupId>"
                + "<artifactId>rope</artifactId><version>2</version></dependency></dependencies></project>");
        write(dir.resolve("output/README.md"), "knots");
        write(dir.resolve("output/src/main/java/org/knot/Knot.java"), "package org.knot;\npublic class Knot { }\n");
        write(dir.resolve("output/src/test/java/org/knot/KnotTest.java"), "package org.knot;\nclass KnotTest { }\n");
        write(dir.resolve("meta/run-identity.json"), "{\"lineageId\":\"lineage-knots\"}");
        write(dir.resolve("bootstrap/seed.txt"), "Tie knots.");
        write(dir.resolve("bootstrap/seed.obligations.txt"), com.walpro.autobuilder.goal.SeedObligationsFile.render("Tie knots.",
                List.of("A knot holds.", "rule: Keep it short.", "gate: tests The suite passes.")));
        write(dir.resolve("bootstrap/protected-paths.txt"), "# ours\nmembers: src/main/**\n");
        write(dir.resolve("meta/GoalStack.json"), """
                {"goalTree": {"id": "root-k", "description": "Tie knots", "type": "GOAL", "status": "COMPLETED",
                  "metadata": {"acceptanceCriteria": ["Knot exists"], "criteriaPassStatus": {"1": true}}, "subGoals": []}}
                """);
        return dir;
    }

    @Test
    void withoutACommonBaseTheCodeIsPlacedBesideTheReceivingCode_whichStaysWhereItIs() throws IOException {
        var landing = ProjectInput.land(receiving(), Map.of(), new ProjectMerge.Parent("knots", "run-k", project("k")));

        assertThat(landing.files()).containsKeys("src/main/java/org/knot/Knot.java", "src/test/java/org/knot/KnotTest.java", "pom.xml");
        assertThat(landing.files()).doesNotContainKeys(MAIN, "README.md");
        assertThat(landing.files().get("pom.xml")).contains("<artifactId>coil</artifactId>").contains("<artifactId>rope</artifactId>");
        assertThat(landing.placed()).containsExactly("src/main/java/org/knot/Knot.java", "src/test/java/org/knot/KnotTest.java");
        assertThat(landing.conflicts()).isEmpty();
        assertThat(landing.notes()).anyMatch(n -> n.contains("org.rope:rope"));
    }

    @Test
    void itsGoalsObligationsAndTestsComeWithIt() throws IOException {
        var landing = ProjectInput.land(receiving(), Map.of(), new ProjectMerge.Parent("knots", "run-k", project("k")));

        assertThat(landing.tree().path("type").asText()).isEqualTo("SUBGOAL");
        assertThat(landing.tree().path("metadata").path("sourceGoalId").asText()).isEqualTo("root-k");
        assertThat(landing.sourceLineage()).isEqualTo("lineage-knots");
        assertThat(landing.obligations()).containsExactly("A knot holds.", "gate: tests The suite passes.");
        assertThat(landing.protectedLines()).containsExactly("members: src/main/**", "src/test/java/org/knot/KnotTest.java");
        assertThat(landing.criteria()).extracting(ProjectMerge.Criterion::text).contains("Knot exists", "A knot holds.");
    }

    @Test
    void aFileBothHaveIsAConflict_theReceivingOnesKept() throws IOException {
        Path dir = project("k");
        write(dir.resolve("output").resolve(MAIN), "package org.warp;\npublic class Coil { }\n");
        var landing = ProjectInput.land(receiving(), Map.of(), new ProjectMerge.Parent("knots", "run-k", dir));

        assertThat(landing.files()).doesNotContainKey(MAIN);
        assertThat(landing.conflicts()).singleElement().satisfies(c -> assertThat(c).contains(MAIN));
    }

    @Test
    void aThirdPartyTreeBringsItsCodeAndTestsOnly() throws IOException {
        Path dir = tmp.resolve("third");
        write(dir.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>org.t</groupId><artifactId>t</artifactId><version>1</version></project>");
        write(dir.resolve("src/main/java/org/t/T.java"), "package org.t;\npublic class T { }\n");
        write(dir.resolve("src/test/java/org/t/TTest.java"), "package org.t;\nclass TTest { }\n");
        var landing = ProjectInput.land(receiving(), Map.of(), new ProjectMerge.Parent("t", null, dir));

        assertThat(landing.placed()).hasSize(2);
        assertThat(landing.tree()).isNull();
        assertThat(landing.obligations()).isEmpty();
        assertThat(landing.protectedLines()).containsExactly("src/test/java/org/t/TTest.java");
        assertThat(landing.sourceLineage()).isNull();
    }

    @Test
    void withACommonBaseTheCodeMergesThreeWayDeclarationByDeclaration() throws IOException {
        Path dir = project("k");
        write(dir.resolve("output").resolve(MAIN), ADOPTED.replace("    public int turns", "    public int knots() { return 3; }\n    public int turns"));
        write(dir.resolve("meta/adoption-baseline").resolve(MAIN), ADOPTED);
        Map<String, String> ours = receiving();
        ours.put(MAIN, ADOPTED.replace("    public int turns", "    public int loops() { return 2; }\n    public int turns"));

        var landing = ProjectInput.land(ours, Map.of(MAIN, ADOPTED), new ProjectMerge.Parent("knots", "run-k", dir));

        assertThat(landing.conflicts()).isEmpty();
        assertThat(landing.files().get(MAIN)).contains("loops()").contains("knots()").contains("turns()");
    }

    @Test
    void differentAdoptedTextsShareNoBase_soTheFileIsAConflictNotAMerge() throws IOException {
        Path dir = project("k");
        write(dir.resolve("output").resolve(MAIN), "package org.warp;\npublic class Coil { int other; }\n");
        write(dir.resolve("meta/adoption-baseline").resolve(MAIN), ADOPTED.replace("1", "9"));
        var landing = ProjectInput.land(receiving(), Map.of(MAIN, ADOPTED), new ProjectMerge.Parent("knots", "run-k", dir));

        assertThat(landing.conflicts()).anyMatch(c -> c.contains(MAIN));
    }
}
