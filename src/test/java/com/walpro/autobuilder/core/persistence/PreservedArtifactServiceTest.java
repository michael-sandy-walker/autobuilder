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

    private void measuredSnapshot(String cycle, Instant at, String markerFile,
                                  com.walpro.autobuilder.core.build.TreeTestMeasure tests) throws IOException {
        Path dir = tempDir.resolve("snapshots/" + cycle);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(markerFile), "content-of-" + cycle);
        objectMapper.writeValue(dir.resolve(MutationSnapshotService.SNAPSHOT_STATE_FILE).toFile(),
                new MutationSnapshotService.SnapshotState(cycle, false, "COMPILE_OK", true, at, tests));
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
    void lineageProvenance_makesTheEntryPointBarLineageRelative() throws IOException {
        // T4-CD slice 2: a resumed run whose parent preserved an entry-point-bearing artifact
        // must not preserve a tree that lost it — even if none of ITS OWN snapshots had one.
        Files.createDirectories(tempDir.resolve("meta"));
        objectMapper.writeValue(tempDir.resolve("meta/preserved-artifact-provenance.json").toFile(),
                new PreservedArtifactService.Provenance("parent-cycle", true, true,
                        "COMPILE_OK", "COMPILE_OK", 1, 7, "cycle-of-the-parent", Instant.now()));
        Instant t0 = Instant.parse("2026-07-17T10:00:00Z");
        snapshot("cycle-1", false, "COMPILE_OK", t0, "no-entry.txt");

        service.preserve(haltContext());

        // nothing passes the lineage bar — abstain (no overwrite of the parent's preserved copy)
        assertThat(tempDir.resolve("meta/preserved-artifact/no-entry.txt")).doesNotExist();
    }

    @Test
    void lineageProvenance_withoutEntryPoint_keepsRunRelativeBar() throws IOException {
        // A library-shaped lineage (parent never had an entry point) stays run-relative.
        Files.createDirectories(tempDir.resolve("meta"));
        objectMapper.writeValue(tempDir.resolve("meta/preserved-artifact-provenance.json").toFile(),
                new PreservedArtifactService.Provenance("parent-cycle", false, false,
                        "COMPILE_OK", "COMPILE_OK", 1, 7, "cycle-of-the-parent", Instant.now()));
        Instant t0 = Instant.parse("2026-07-17T10:00:00Z");
        snapshot("cycle-1", false, "COMPILE_OK", t0, "lib.txt");

        service.preserve(haltContext());

        assertThat(tempDir.resolve("meta/preserved-artifact/lib.txt")).exists();
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

    /**
     * T6-T, field52 run 1: the 18:52 tree left 4 of the operator's tests failing, the 18:27 tree 6, and a
     * later unmerged-looking tree was never measured. The preserve takes the fewest failing, not the newest.
     */
    @Test
    void prefersTheTreeLeavingFewestOfTheOperatorsTestsFailing() throws IOException {
        Instant t0 = Instant.parse("2026-10-08T16:27:00Z");
        measuredSnapshot("cycle-a", t0, "six-failing.txt", new com.walpro.autobuilder.core.build.TreeTestMeasure(true, 6));
        measuredSnapshot("cycle-b", t0.plusSeconds(1500), "four-failing.txt",
                new com.walpro.autobuilder.core.build.TreeTestMeasure(true, 4));
        measuredSnapshot("cycle-c", t0.plusSeconds(1600), "seven-failing.txt",
                new com.walpro.autobuilder.core.build.TreeTestMeasure(true, 7));
        measuredSnapshot("cycle-d", t0.plusSeconds(1700), "not-compiling.txt",
                new com.walpro.autobuilder.core.build.TreeTestMeasure(false, 1));
        snapshot("cycle-e", false, "COMPILE_OK", t0.plusSeconds(1800), "unmeasured.txt");

        service.preserve(haltContext());

        Path preserved = tempDir.resolve("meta/preserved-artifact");
        assertThat(preserved.resolve("four-failing.txt")).exists();
        assertThat(preserved.resolve("unmeasured.txt")).doesNotExist();
        PreservedArtifactService.Provenance p = objectMapper.readValue(
                tempDir.resolve("meta/preserved-artifact-provenance.json").toFile(), PreservedArtifactService.Provenance.class);
        assertThat(p.fromCycle()).isEqualTo("cycle-b");
        assertThat(p.treeTests()).isEqualTo(new com.walpro.autobuilder.core.build.TreeTestMeasure(true, 4));
    }

    @Test
    void anImprovingMergeIsPreservedAtOnceAndAnEqualOneIsNot() throws IOException {
        var four = new com.walpro.autobuilder.core.build.TreeTestMeasure(true, 4);
        assertThat(service.improvesOnPreserved(null)).isFalse();
        assertThat(service.improvesOnPreserved(four)).isTrue();   // nothing preserved yet

        measuredSnapshot("cycle-a", Instant.parse("2026-10-08T16:27:00Z"), "six.txt",
                new com.walpro.autobuilder.core.build.TreeTestMeasure(true, 6));
        service.preserve(haltContext());

        assertThat(service.improvesOnPreserved(four)).isTrue();
        assertThat(service.improvesOnPreserved(new com.walpro.autobuilder.core.build.TreeTestMeasure(true, 6))).isFalse();
        assertThat(service.improvesOnPreserved(new com.walpro.autobuilder.core.build.TreeTestMeasure(false, 0))).isFalse();
    }
}
