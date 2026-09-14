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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A delete directive has no footprint in the mutation directory — only written files land there —
 * so the folder comparison alone can never see one. Found 2026-09-14 when a mutation that deleted
 * one of two parallel stores was diffed as "0 deleted paths" and rejected for the store it removed.
 */
class ProjectDiffServiceDeletionTest {

    @TempDir Path tmp;
    private ProjectDiffService service;

    @BeforeEach
    void setUp() throws IOException {
        FileService files = new FileService(tmp.toString(), new ObjectMapper());
        DiffConfigProperties config = new DiffConfigProperties();
        config.setExcludeFolders(java.util.List.of());
        config.setExcludeFiles(java.util.List.of());
        service = new ProjectDiffService(files, new DiffService(files, config));
        Files.createDirectories(tmp.resolve("mutations/prev/src"));
        Files.createDirectories(tmp.resolve("mutations/cur/src"));
        Files.writeString(tmp.resolve("mutations/prev/src/BookRepository.java"), "class BookRepository {}");
        Files.writeString(tmp.resolve("mutations/cur/src/BookStore.java"), "class BookStore {}");
    }

    @Test
    @DisplayName("an explicit deletion is a deleted path, even when the previous mutation directory never held the file")
    void explicitDeletionsAreDeletedPaths() {
        ProjectDiffResult diff = service.computeDiff(tmp.resolve("mutations/cur"), tmp.resolve("mutations/prev"),
                Set.of("src/BookRepository.java", "src/MergedEarlier.java"));

        assertThat(diff.deletedPaths()).contains("src/BookRepository.java", "src/MergedEarlier.java");
        assertThat(diff.changedFiles()).containsKey("src/BookStore.java");
    }

    @Test
    @DisplayName("the overlay applies both halves: changed files written, deleted paths removed")
    void overlayHonoursDeletions() {
        ProjectDiffResult diff = service.computeDiff(tmp.resolve("mutations/cur"), tmp.resolve("mutations/prev"),
                Set.of("src/BookRepository.java"));

        var current = diff.overlay(java.util.Map.of("src/BookRepository.java", "class BookRepository {}",
                "src/Book.java", "class Book {}"));

        assertThat(current).containsKeys("src/Book.java", "src/BookStore.java")
                .doesNotContainKey("src/BookRepository.java");
    }

    @Test
    @DisplayName("without explicit deletions nothing changes: the two-argument form is the empty set")
    void noDeletionsIsTheOldBehaviour() {
        ProjectDiffResult diff = service.computeDiff(tmp.resolve("mutations/cur"), tmp.resolve("mutations/prev"));
        assertThat(diff.deletedPaths()).isEmpty();
    }
}
