package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Where a project's modules are: the build root, then every module its build file lists, nested ones included. */
class ProjectLayoutTest {

    @TempDir
    Path root;

    @Test
    void aProjectWithoutModulesIsItsRoot() throws IOException {
        Files.writeString(root.resolve("pom.xml"), "<project><artifactId>wick</artifactId></project>");
        assertThat(ProjectLayout.moduleRoots(root)).containsExactly(root.normalize());
    }

    @Test
    void anAggregatorListsItsModulesNestedOnesIncludedAndCommentedOnesNot() throws IOException {
        Files.writeString(root.resolve("pom.xml"),
                "<project><modules><module>lamp</module><!-- <module>old</module> --><module> wick </module></modules></project>");
        Files.createDirectories(root.resolve("lamp/shade"));
        Files.writeString(root.resolve("lamp/pom.xml"), "<project><modules><module>shade</module></modules></project>");
        Files.createDirectories(root.resolve("wick/src/test/java"));
        Files.createDirectories(root.resolve("old"));

        assertThat(ProjectLayout.moduleRoots(root)).containsExactly(root, root.resolve("lamp"), root.resolve("lamp/shade"),
                root.resolve("wick"));
        assertThat(ProjectLayout.underEveryModule(root, "src/test/java")).containsExactly(root.resolve("wick/src/test/java"));
        assertThat(ProjectLayout.moduleOf(root, root.resolve("lamp/shade/src/A.java"))).isEqualTo(root.resolve("lamp/shade"));
        assertThat(ProjectLayout.moduleOf(root, root.resolve("README.md"))).isEqualTo(root);
    }
}
