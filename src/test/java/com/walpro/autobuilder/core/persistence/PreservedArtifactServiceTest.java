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
        snapshot(cycle, entryPoint, buildResult, at, markerFile, true);
    }

    private void snapshot(String cycle, boolean entryPoint, String buildResult, Instant at,
                          String markerFile, boolean merged) throws IOException {
        Path dir = tempDir.resolve("snapshots/" + cycle);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(markerFile), "content-of-" + cycle);
        objectMapper.writeValue(dir.resolve(MutationSnapshotService.SNAPSHOT_STATE_FILE).toFile(),
                new MutationSnapshotService.SnapshotState(cycle, entryPoint, buildResult, merged, at));
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

    // --- preserve-time build verification (stale-stamp guard, corpus 2026-07-15_03-03-29) ---

    private com.walpro.autobuilder.core.build.JavacDirectProbe probeAnswering(
            java.util.Map<String, com.walpro.autobuilder.core.build.BuildResult> byCycleDirName) {
        var probe = mock(com.walpro.autobuilder.core.build.JavacDirectProbe.class);
        org.mockito.Mockito.when(probe.execute(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(inv -> {
                    String dir = inv.getArgument(0);
                    for (var e : byCycleDirName.entrySet()) {
                        if (dir.endsWith(e.getKey())) {
                            return new com.walpro.autobuilder.core.build.BuildOutcome(
                                    e.getValue(), "", 0L, "verify", null);
                        }
                    }
                    throw new AssertionError("unexpected probe of " + dir);
                });
        return probe;
    }

    @Test
    void staleStampOnNewest_fallsBackToMergeAnchoredCandidate() throws IOException {
        // Field defect regression-pin: the halt-cycle snapshot carries a stale COMPILE_OK stamp
        // (its writes arrived via injection, never faced a build) while the tree is broken. The
        // preserve-time probe must reject it and preserve the newest merge-anchored candidate.
        Instant t0 = Instant.parse("2026-07-15T01:00:00Z");
        snapshot("cycle-merged", true, "COMPILE_OK", t0, "verified.txt", true);
        snapshot("cycle-unmerged-mid", true, "COMPILE_OK", t0.plusSeconds(60), "speculative.txt", false);
        snapshot("cycle-halt", true, "COMPILE_OK", t0.plusSeconds(120), "broken.txt", false);
        var probe = probeAnswering(java.util.Map.of(
                "cycle-halt", com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR,
                "cycle-merged", com.walpro.autobuilder.core.build.BuildResult.COMPILE_OK));
        service.setBuildProbe(probe);

        service.preserve(haltContext());

        // The merge-anchored candidate wins; the unmerged intermediate is never probed
        // (probeAnswering throws on unexpected dirs).
        assertThat(tempDir.resolve("meta/preserved-artifact/verified.txt")).exists();
        assertThat(tempDir.resolve("meta/preserved-artifact/broken.txt")).doesNotExist();
        String provenance = Files.readString(tempDir.resolve("meta/preserved-artifact-provenance.json"));
        assertThat(provenance).contains("\"verifiedBuildResult\" : \"COMPILE_OK\"");
        assertThat(provenance).contains("\"candidatesProbed\" : 2");
    }

    @Test
    void abstainsWhenNoCandidatePassesVerification() throws IOException {
        Instant t0 = Instant.parse("2026-07-15T01:00:00Z");
        snapshot("cycle-a", true, "COMPILE_OK", t0, "a.txt", true);
        snapshot("cycle-b", true, "COMPILE_OK", t0.plusSeconds(60), "b.txt", false);
        var probe = probeAnswering(java.util.Map.of(
                "cycle-a", com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR,
                "cycle-b", com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR));
        service.setBuildProbe(probe);

        service.preserve(haltContext());

        assertThat(tempDir.resolve("meta/preserved-artifact")).doesNotExist();
    }

    @Test
    void skippedVerification_trustsTheStamp() throws IOException {
        // No JDK compiler available (probe degrades to SKIPPED) — keep the stamped behavior
        // rather than abstaining on an environment limitation.
        Instant t0 = Instant.parse("2026-07-15T01:00:00Z");
        snapshot("cycle-1", true, "COMPILE_OK", t0, "only.txt", false);
        var probe = probeAnswering(java.util.Map.of(
                "cycle-1", com.walpro.autobuilder.core.build.BuildResult.SKIPPED));
        service.setBuildProbe(probe);

        service.preserve(haltContext());

        assertThat(tempDir.resolve("meta/preserved-artifact/only.txt")).exists();
        String provenance = Files.readString(tempDir.resolve("meta/preserved-artifact-provenance.json"));
        assertThat(provenance).contains("\"verifiedBuildResult\" : \"SKIPPED\"");
    }
}
