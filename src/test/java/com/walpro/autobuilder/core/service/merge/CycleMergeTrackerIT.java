package com.walpro.autobuilder.core.service.merge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.persistence.MutationSnapshotService;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.model.MergeTrace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CycleMergeTrackerIT {

    @TempDir
    Path tempDir;

    @Test
    void markCyclesAsMerged_persistsMergedFlagInMutationMetas() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

        // Real services on a temp filesystem
        FileService fileService = new FileService(tempDir.toString(), objectMapper);
        fileService.ensureDefaultFolders(); // creates mutations/, logs/, etc.

        MutationSnapshotService mutationSnapshotService = new MutationSnapshotService(fileService, objectMapper);
        CycleMergeTracker tracker = new CycleMergeTracker(fileService, mutationSnapshotService);

        // Prepare mutation metas on disk:
        // - cycle-1 starts merged=false -> should become merged=true
        // - cycle-2 starts merged=true  -> should remain merged=true
        CycleContextData c1 = CycleContextData.builder()
                .cycleName("cycle-1")
                .outputPath("output")
                .merged(false)
                .build();

        CycleContextData c2 = CycleContextData.builder()
                .cycleName("cycle-2")
                .outputPath("output")
                .merged(true)
                .build();

        mutationSnapshotService.writeMutationMeta(c1);
        mutationSnapshotService.writeMutationMeta(c2);

        // Root context references both cycles as "mergedCycles"
        CycleContextData root = CycleContextData.builder()
                .cycleName("cycle-root")
                .outputPath("output")
                .merged(false)
                .build()
                .withMergeTrace(new MergeTrace(
                        new ArrayList<>(List.of("cycle-1", "cycle-2")),
                        new HashMap<>(),
                        new HashMap<>()
                ));

        // Act
        CycleContextData result = tracker.markCyclesAsMerged(root);

        // Assert: returned context now merged=true (because it marked at least one)
        assertThat(result.merged()).isTrue();

        // Assert: persisted state updated
        CycleContextData c1After = mutationSnapshotService.readMutationMeta("mutations/cycle-1/meta.json");
        CycleContextData c2After = mutationSnapshotService.readMutationMeta("mutations/cycle-2/meta.json");

        assertThat(c1After.merged()).isTrue();
        assertThat(c2After.merged()).isTrue();
    }

    @Test
    void markCyclesAsMerged_skipsMissingMeta_andStillMarksOthers() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

        FileService fileService = new FileService(tempDir.toString(), objectMapper);
        fileService.ensureDefaultFolders();

        MutationSnapshotService mutationSnapshotService = new MutationSnapshotService(fileService, objectMapper);
        CycleMergeTracker tracker = new CycleMergeTracker(fileService, mutationSnapshotService);

        // Only cycle-ok meta exists
        CycleContextData ok = CycleContextData.builder()
                .cycleName("cycle-ok")
                .outputPath("output")
                .merged(false)
                .build();

        mutationSnapshotService.writeMutationMeta(ok);

        CycleContextData root = CycleContextData.builder()
                .cycleName("cycle-root")
                .outputPath("output")
                .merged(false)
                .build()
                .withMergeTrace(new MergeTrace(
                        new ArrayList<>(List.of("cycle-missing", "cycle-ok")),
                        new HashMap<>(),
                        new HashMap<>()
                ));

        CycleContextData result = tracker.markCyclesAsMerged(root);

        assertThat(result.merged()).isTrue(); // cycle-ok should still get marked

        CycleContextData okAfter = mutationSnapshotService.readMutationMeta("mutations/cycle-ok/meta.json");
        assertThat(okAfter.merged()).isTrue();
        assertThat(fileService.exists("mutations/cycle-missing/meta.json")).isFalse();
    }
}