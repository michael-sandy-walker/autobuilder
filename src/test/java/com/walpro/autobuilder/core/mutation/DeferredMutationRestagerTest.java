package com.walpro.autobuilder.core.mutation;

import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.CompileProbeService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4-BH: when the re-probe of a ready-to-restage deferred mutation runs into a package
 * fragmentation conflict (a later merge landed a flat version of one of the deferred types),
 * the restager must surface the dedicated terminal state instead of conflating it with a
 * generic compile failure.
 */
@ExtendWith(MockitoExtension.class)
class DeferredMutationRestagerTest {

    @Mock DeferredMutationLedger ledger;
    @Mock CompileProbeService compileProbeService;
    @Mock FileService fileService;

    @InjectMocks DeferredMutationRestager restager;

    private DeferredMutation candidate;
    private CycleContextData ctx;

    @BeforeEach
    void setUp() {
        // A deferred mutation whose only TOUCHES file is a path that has been removed on disk
        // (skeleton stub rolled back at original cycle) — drift check returns null and skips.
        candidate = new DeferredMutation(
                "def-001",
                "attempt-1",
                "cycle-origin",
                "sg-1",
                "g-1",
                null,
                null,
                1L,
                Instant.now(),
                "mutations/cycle-origin",
                DeferredMutationState.READY_TO_RESTAGE,
                Map.of("src/main/java/com/example/demo/service/BookService.java", "hash-skeleton"),
                List.of()
        );
        ctx = CycleContextData.builder()
                .cycleName("cycle-restage")
                .outputPath("output")
                .currentSubGoalId("sg-1")
                .build();
    }

    @Test
    void attemptRestage_T4BH_mapsFragmentationProbeResult_toDroppedPackageFragmentation() throws Exception {
        // Drift check passes — the on-disk skeleton was removed at the original rollback, so
        // readAndHashIfPresent returns null and the comparison is skipped.
        when(fileService.exists(any())).thenReturn(false);

        // Mutation files loaded successfully from disk: stub the file walk by writing real files.
        // Easier path here: stub the restager's loadMutationFiles transitively. Since loadMutationFiles
        // uses Files.walk directly, we instead drive the restage via a mutation dir that exists.
        // Simpler: bypass by stubbing the probe to fail at the fragmentation step (the only branch
        // we're testing). loadMutationFiles will succeed if the dir is empty? No — it returns []
        // and the restager drops with "files_missing". So we need a real on-disk mutation dir.
        // To keep this unit test isolated, point the restager at a tmp file-base and create files.
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("restager-frag-");
        java.nio.file.Path mDir = tmp.resolve("mutations/cycle-origin");
        java.nio.file.Files.createDirectories(mDir);
        java.nio.file.Files.writeString(
                mDir.resolve("src_main_java_BookService.java"),
                "package com.example.demo.service; public class BookService {}");
        org.springframework.test.util.ReflectionTestUtils.setField(restager, "fileBasePath", tmp.toString());

        // Probe rejects with FRAGMENTATION_REJECTED.
        BuildOutcome fragOutcome = new BuildOutcome(
                BuildResult.FRAGMENTATION_REJECTED,
                "T4-BH: package fragmentation — type BookService already exists at src/main/java/com/example/demo/BookService.java",
                0L, "", "cycle-restage");
        when(compileProbeService.probe(any(CycleContextData.class), eq(false)))
                .thenReturn(new CompileProbeService.ProbeResult(fragOutcome, null));

        DeferredMutationRestager.RestageResult result = restager.attemptRestage(candidate, ctx);

        // Terminal state: DROPPED_PACKAGE_FRAGMENTATION, not DROPPED_REPROBE_FAILED.
        verify(ledger).markDroppedPackageFragmentation("def-001");
        verify(ledger, never()).markDroppedReProbeFailed(any());
        verify(ledger, never()).markRestaged(any());

        assertThat(result).isInstanceOf(DeferredMutationRestager.RestageResult.Dropped.class);
        DeferredMutationRestager.RestageResult.Dropped d = (DeferredMutationRestager.RestageResult.Dropped) result;
        assertThat(d.reason()).isEqualTo("package_fragmentation");
        assertThat(d.detail()).contains("BookService");

        // Cleanup tmp.
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(tmp)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {} });
        }
    }

    @Test
    void attemptRestage_T4BH_compileErrorStillRoutesToReProbeFailed_notFragmentation() throws Exception {
        // Sanity check: the new FRAGMENTATION_REJECTED branch must not steal COMPILE_ERROR cases.
        when(fileService.exists(any())).thenReturn(false);

        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("restager-compile-");
        java.nio.file.Path mDir = tmp.resolve("mutations/cycle-origin");
        java.nio.file.Files.createDirectories(mDir);
        java.nio.file.Files.writeString(mDir.resolve("Foo.java"), "broken");
        org.springframework.test.util.ReflectionTestUtils.setField(restager, "fileBasePath", tmp.toString());

        BuildOutcome compileOutcome = new BuildOutcome(
                BuildResult.COMPILE_ERROR, "symbol: class List", 0L, "", "cycle-restage");
        when(compileProbeService.probe(any(CycleContextData.class), eq(false)))
                .thenReturn(new CompileProbeService.ProbeResult(compileOutcome, null));

        restager.attemptRestage(candidate, ctx);

        verify(ledger).markDroppedReProbeFailed("def-001");
        verify(ledger, never()).markDroppedPackageFragmentation(any());

        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(tmp)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {} });
        }
    }
}
