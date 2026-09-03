package com.walpro.autobuilder.core.coldread;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-CQ: someone else's passing test suite is the anchor a cold read can actually check.
 *
 * <p>These cover the decisions rather than the build, which needs Maven and the network: whether a
 * project is verifiable at all, and whether the reading ever writes to the tree it is reading.
 */
class ColdReadVerificationTest {

    @TempDir
    Path tmp;

    private final ColdReadVerification verification = new ColdReadVerification();

    @Test
    @DisplayName("a tree with no build file is not attempted, and says why rather than failing")
    void withoutABuildFileNothingIsAttempted() throws IOException {
        Files.writeString(tmp.resolve("README.md"), "# just some notes");

        ColdReadVerification.Result r = verification.verify(tmp);

        assertThat(r.attempted()).isFalse();
        assertThat(r.greenBaseline()).isFalse();
        assertThat(r.notes()).anyMatch(n -> n.contains("pom.xml"));
    }

    @Test
    @DisplayName("a green baseline needs tests that actually ran, not merely a build that worked")
    void buildingIsNotABaseline() {
        assertThat(new ColdReadVerification.Result(true, "maven", true, 0, 0, 10, java.util.List.of())
                .greenBaseline())
                .as("a project with no tests states nothing about its intended behaviour")
                .isFalse();
        assertThat(new ColdReadVerification.Result(true, "maven", true, 5, 1, 10, java.util.List.of())
                .greenBaseline())
                .isFalse();
        assertThat(new ColdReadVerification.Result(true, "maven", true, 5, 0, 10, java.util.List.of())
                .greenBaseline())
                .isTrue();
    }

    @Test
    @DisplayName("reading a project never writes to it")
    void aReadDoesNotWriteToTheProject() throws IOException {
        Files.writeString(tmp.resolve("pom.xml"), "<project><artifactId>x</artifactId></project>");
        Files.createDirectories(tmp.resolve("src/main/java"));
        Files.writeString(tmp.resolve("src/main/java/A.java"), "class A {}");
        long before = countFiles(tmp);

        verification.verify(tmp);

        assertThat(countFiles(tmp))
                .as("building writes a target directory and can rewrite lock files, so it happens in a copy")
                .isEqualTo(before);
        assertThat(Files.exists(tmp.resolve("target"))).isFalse();
    }

    private static long countFiles(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }
}
