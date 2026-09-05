package com.walpro.autobuilder.analysis.diff;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.config.DiffConfigProperties;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The diff has to survive a tree the loop did not write.
 *
 * <p>Run of 2026-09-05 on the first imported project: eighteen cycles, sixteen mutations proposed,
 * <b>zero scored</b>. The Maven wrapper ships a jar, reading it as text threw, and the whole diff
 * failed on every cycle — so nothing was ever judged and the planner replanned the same six
 * subgoals three times over. A tree the loop wrote contains only text the loop wrote; every
 * imported one has binaries in it.
 */
class DiffServiceExclusionTest {

    @TempDir
    Path tmp;

    private DiffService diffService;

    @BeforeEach
    void setUp() {
        DiffConfigProperties config = new DiffConfigProperties();
        config.setExcludeFolders(List.of("logs", "responses", "target", ".mvn"));
        config.setExcludeFiles(List.of("meta.json"));
        diffService = new DiffService(new FileService(tmp.toString(), new ObjectMapper()), config);
    }

    @Test
    @DisplayName("a binary anywhere in the tree does not cost the whole diff")
    void aBinaryDoesNotBreakTheDiff() throws IOException {
        Files.createDirectories(tmp.resolve("new/src"));
        Files.writeString(tmp.resolve("new/src/A.java"), "class A {}");
        // Not under an excluded folder, and not text: exactly the wrapper-jar shape.
        Files.write(tmp.resolve("new/vendored.bin"), new byte[]{(byte) 0xC3, 0x28, 0x00, (byte) 0xFF});

        DiffReport report = diffService.compareFolders(null, "new", java.util.Set.of());

        assertThat(report).isNotNull();
        assertThat(report.entries()).extracting(DiffEntry::path)
                .as("the source file is still seen; only the unreadable one is left out")
                .contains("src/A.java")
                .doesNotContain("vendored.bin");
    }

    @Test
    @DisplayName("excluded folders match a path SEGMENT, not just a prefix")
    void exclusionsMatchSegmentsNotPrefixes() throws IOException {
        Files.createDirectories(tmp.resolve("new/.mvn/wrapper"));
        Files.createDirectories(tmp.resolve("new/src/main/java"));
        Files.writeString(tmp.resolve("new/.mvn/wrapper/MavenWrapperDownloader.java"), "class W {}");
        Files.writeString(tmp.resolve("new/src/main/java/A.java"), "class A {}");

        DiffReport report = diffService.compareFolders(null, "new", java.util.Set.of());

        assertThat(report.entries()).extracting(DiffEntry::path)
                .as("build tooling is not the project, wherever in the tree it sits")
                .contains("src/main/java/A.java")
                .doesNotContain(".mvn/wrapper/MavenWrapperDownloader.java");
    }

    @Test
    @DisplayName("a project's own tests are part of its source and stay visible")
    void aProjectsOwnTestsAreVisible() throws IOException {
        Files.createDirectories(tmp.resolve("new/src/test/java"));
        Files.writeString(tmp.resolve("new/src/test/java/ATest.java"), "class ATest {}");

        DiffReport report = diffService.compareFolders(null, "new", java.util.Set.of());

        assertThat(report.entries()).extracting(DiffEntry::path)
                .as("a change the loop makes to a test must be visible to the loop that made it")
                .contains("src/test/java/ATest.java");
    }
}
