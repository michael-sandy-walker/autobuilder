package com.walpro.autobuilder.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PreservedArtifactServiceTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules();

    private FileService fileService;
    private PreservedArtifactService service;
    private BehaviourLogger behaviourLogger;

    @BeforeEach
    void setUp() {
        fileService = new FileService(tempDir.toString(), objectMapper);
        service = new PreservedArtifactService(fileService, objectMapper);
        behaviourLogger = mock(BehaviourLogger.class);
        service.setBehaviourLogger(behaviourLogger);
    }

    private void snapshot(String cycle, boolean entryPoint, String buildResult, Instant at,
                          String markerFile) throws IOException {
        Path dir = tempDir.resolve("snapshots/" + cycle);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(markerFile), "content-of-" + cycle);
        objectMapper.writeValue(dir.resolve(MutationSnapshotService.SNAPSHOT_STATE_FILE).toFile(),
                new MutationSnapshotService.SnapshotState(cycle, entryPoint, buildResult, true, at));
    }

    private CycleContextData haltContext() {
        return CycleContextData.builder().cycleName("cycle-halt").build();
    }

    @Test
    void picksNewestSnapshotPassingTheBar() throws IOException {
        Instant t0 = Instant.parse("2026-07-13T10:00:00Z");
        snapshot("cycle-1", true, "COMPILE_OK", t0, "good.txt");
        snapshot("cycle-2", false, "COMPILE_OK", t0.plusSeconds(60), "no-entry.txt");
        snapshot("cycle-3", true, "COMPILE_ERROR", t0.plusSeconds(120), "broken.txt");

        service.preserve(haltContext());

        Path preserved = tempDir.resolve("meta/preserved-artifact");
        assertThat(preserved.resolve("good.txt")).exists();
        assertThat(preserved.resolve("no-entry.txt")).doesNotExist();
        assertThat(tempDir.resolve("meta/preserved-artifact-provenance.json")).exists();
        verify(behaviourLogger).artifactPreserved(eq("cycle-halt"), eq("cycle-1"), eq(true),
                eq(true), eq("COMPILE_OK"), eq(3));
    }

    @Test
    void entryPointNotRequiredWhenRunNeverHadOne() throws IOException {
        Instant t0 = Instant.parse("2026-07-13T10:00:00Z");
        snapshot("cycle-1", false, "COMPILE_OK", t0, "older.txt");
        snapshot("cycle-2", false, "COMPILE_OK", t0.plusSeconds(60), "newest.txt");

        service.preserve(haltContext());

        assertThat(tempDir.resolve("meta/preserved-artifact/newest.txt")).exists();
        verify(behaviourLogger).artifactPreserved(anyString(), eq("cycle-2"), eq(false),
                eq(false), anyString(), anyInt());
    }

    @Test
    void nullBuildResultIsUnknownNotFailing() throws IOException {
        snapshot("cycle-1", true, null, Instant.parse("2026-07-13T10:00:00Z"), "file.txt");

        service.preserve(haltContext());

        assertThat(tempDir.resolve("meta/preserved-artifact/file.txt")).exists();
    }

    @Test
    void abstainsWithoutSnapshotsAndNeverThrows() {
        service.preserve(haltContext());
        assertThat(tempDir.resolve("meta/preserved-artifact")).doesNotExist();
    }

    @Test
    void abstainsWhenNothingPassesTheBar() throws IOException {
        Instant t0 = Instant.parse("2026-07-13T10:00:00Z");
        snapshot("cycle-1", true, "COMPILE_ERROR", t0, "broken.txt");
        snapshot("cycle-2", false, "COMPILE_OK", t0.plusSeconds(60), "no-entry.txt");

        service.preserve(haltContext());

        assertThat(tempDir.resolve("meta/preserved-artifact")).doesNotExist();
    }

    @Test
    void replaceIsCleanAcrossPreservations() throws IOException {
        Instant t0 = Instant.parse("2026-07-13T10:00:00Z");
        snapshot("cycle-1", true, "COMPILE_OK", t0, "first.txt");
        service.preserve(haltContext());
        assertThat(tempDir.resolve("meta/preserved-artifact/first.txt")).exists();

        snapshot("cycle-2", true, "COMPILE_OK", t0.plusSeconds(60), "second.txt");
        service.preserve(haltContext());

        assertThat(tempDir.resolve("meta/preserved-artifact/second.txt")).exists();
        assertThat(tempDir.resolve("meta/preserved-artifact/first.txt"))
                .as("stale file from the earlier preservation must not survive the clean replace")
                .doesNotExist();
    }

    @Test
    void ignoresUnstampedSnapshots() throws IOException {
        Path unstamped = tempDir.resolve("snapshots/cycle-old");
        Files.createDirectories(unstamped);
        Files.writeString(unstamped.resolve("legacy.txt"), "x");

        service.preserve(haltContext());

        assertThat(tempDir.resolve("meta/preserved-artifact")).doesNotExist();
    }
}
