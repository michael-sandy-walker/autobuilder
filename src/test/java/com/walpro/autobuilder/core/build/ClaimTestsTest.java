package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T4-EK: a reverse-engineered project's claim tests live in its meta and visit its tree only for a test run. */
class ClaimTestsTest {

    @TempDir
    Path project;

    private Path tree() throws Exception {
        return Files.createDirectories(project.resolve("output"));
    }

    @Test
    void testsAreKeptInMetaPlacedForARunAndTakenOutWithTheDirectoriesMadeForThem() throws Exception {
        ClaimTests tests = new ClaimTests(project.toString());
        tests.write("org/x/EarnedK1Test.java", "class EarnedK1Test {}");
        tests.write("EarnedK2Test.java", "class EarnedK2Test {}");
        Path tree = tree();

        assertThat(project.resolve("meta/claim-tests/org/x/EarnedK1Test.java")).hasContent("class EarnedK1Test {}");
        assertThat(tree.resolve("src")).doesNotExist();
        try (ClaimTests.Placed placed = tests.place(tree)) {
            assertThat(tree.resolve("src/test/java/org/x/EarnedK1Test.java")).hasContent("class EarnedK1Test {}");
            assertThat(tree.resolve("src/test/java/EarnedK2Test.java")).exists();
            assertThat(placed.source("EarnedK1Test")).isEqualTo("class EarnedK1Test {}");
            assertThat(placed.source("SomethingElse")).isNull();
        }
        assertThat(tree.resolve("src")).as("nothing of it is left in the project's tree").doesNotExist();
    }

    @Test
    void theProjectsOwnFilesAndDirectoriesAreLeftAsTheyWere() throws Exception {
        ClaimTests tests = new ClaimTests(project.toString());
        tests.write("org/x/EarnedK1Test.java", "class EarnedK1Test {}");
        tests.write("org/x/OwnTest.java", "class OwnTest { /* the claim's */ }");
        Path own = Files.createDirectories(tree().resolve("src/test/java/org/x")).resolve("OwnTest.java");
        Files.writeString(own, "class OwnTest { /* the project's */ }");

        try (ClaimTests.Placed placed = tests.place(tree())) {
            assertThat(own).hasContent("class OwnTest { /* the project's */ }");
            assertThat(placed.source("OwnTest")).as("not placed, so not run").isNull();
        }
        assertThat(own).hasContent("class OwnTest { /* the project's */ }");
        assertThat(own.resolveSibling("EarnedK1Test.java")).doesNotExist();
        assertThat(own.getParent()).as("a directory the project had stays").isDirectory();
    }

    @Test
    void aTestLeftBehindByARunThatWasCutShortIsRemovedByTheNext() throws Exception {
        ClaimTests tests = new ClaimTests(project.toString());
        tests.write("EarnedK1Test.java", "class EarnedK1Test {}");
        Path left = Files.createDirectories(tree().resolve("src/test/java")).resolve("EarnedK1Test.java");
        Files.writeString(left, "class EarnedK1Test {}");

        tests.place(tree()).close();

        assertThat(left).doesNotExist();
    }

    @Test
    void aProjectWithoutClaimTestsPlacesNothingAndAPathOutsideIsRefused() throws Exception {
        ClaimTests tests = new ClaimTests(project.toString());
        try (ClaimTests.Placed placed = tests.place(tree())) {
            assertThat(placed.isEmpty()).isTrue();
        }
        assertThat(tree().resolve("src")).doesNotExist();
        assertThatThrownBy(() -> tests.write("../../output/src/main/java/X.java", "class X {}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
