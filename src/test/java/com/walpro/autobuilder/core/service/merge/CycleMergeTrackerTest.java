package com.walpro.autobuilder.core.service.merge;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.persistence.MutationSnapshotService;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.model.MergeTrace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CycleMergeTrackerTest {

    @Mock
    FileService fileService;

    @Mock
    MutationSnapshotService mutationSnapshotService;

    CycleMergeTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new CycleMergeTracker(fileService, mutationSnapshotService);
    }

    // -----------------------------
    // isAlreadyMerged
    // -----------------------------

    @Test
    void isAlreadyMerged_returnsFalse_whenMetaDoesNotExist() throws Exception {
        String cycleId = "cycle-x";
        Path metaPath = Path.of("mutations/" + cycleId + "/meta.json");

        when(fileService.resolveSecurePath("mutations/" + cycleId + "/meta.json"))
                .thenReturn(metaPath);

        when(fileService.existsResolved(metaPath)).thenReturn(false);

        boolean merged = tracker.isAlreadyMerged(cycleId);

        assertThat(merged).isFalse();
        verify(fileService).existsResolved(metaPath);
        verifyNoInteractions(mutationSnapshotService);
    }

    @Test
    void isAlreadyMerged_returnsTrue_whenMetaMergedTrue() throws Exception {
        String cycleId = "cycle-merged";
        Path metaPath = Path.of("mutations/" + cycleId + "/meta.json");

        when(fileService.resolveSecurePath("mutations/" + cycleId + "/meta.json"))
                .thenReturn(metaPath);

        when(fileService.existsResolved(metaPath)).thenReturn(true);

        CycleContextData meta = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath("output")
                .merged(true)
                .build();

        when(mutationSnapshotService.readMutationMetaResolved(metaPath)).thenReturn(meta);

        boolean merged = tracker.isAlreadyMerged(cycleId);

        assertThat(merged).isTrue();
    }

    @Test
    void isAlreadyMerged_returnsFalse_whenMetaMergedFalse() throws Exception {
        String cycleId = "cycle-not-merged";
        Path metaPath = Path.of("mutations/" + cycleId + "/meta.json");

        when(fileService.resolveSecurePath("mutations/" + cycleId + "/meta.json"))
                .thenReturn(metaPath);

        when(fileService.existsResolved(metaPath)).thenReturn(true);

        CycleContextData meta = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath("output")
                .merged(false)
                .build();

        when(mutationSnapshotService.readMutationMetaResolved(metaPath)).thenReturn(meta);

        boolean merged = tracker.isAlreadyMerged(cycleId);

        assertThat(merged).isFalse();
    }

    @Test
    void isAlreadyMerged_returnsFalse_onIOException() throws Exception {
        String cycleId = "cycle-io";

        when(fileService.resolveSecurePath("mutations/" + cycleId + "/meta.json"))
                .thenThrow(new IOException("boom"));

        boolean merged = tracker.isAlreadyMerged(cycleId);

        assertThat(merged).isFalse();
    }

    // -----------------------------
    // markCyclesAsMerged
    // -----------------------------

    @Test
    void markCyclesAsMerged_noops_whenNoMergeTrace() {
        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-root")
                .outputPath("output")
                .build(); // mergeTrace null

        CycleContextData result = tracker.markCyclesAsMerged(context);

        assertThat(result).isSameAs(context);
        verifyNoInteractions(fileService, mutationSnapshotService);
    }

    @Test
    void markCyclesAsMerged_marksUnmergedCycles_andSetsReturnedContextMerged() throws Exception {
        String rootCycle = "cycle-root";
        String c1 = "cycle-1";
        String c2 = "cycle-2";

        CycleContextData context = CycleContextData.builder()
                .cycleName(rootCycle)
                .outputPath("output")
                .merged(false)
                .build()
                .withMergeTrace(new MergeTrace(
                        new ArrayList<>(List.of(c1, c2)),
                        new HashMap<>(),
                        new HashMap<>()
                ));

        Path meta1 = Path.of("mutations/" + c1 + "/meta.json");
        Path meta2 = Path.of("mutations/" + c2 + "/meta.json");

        when(fileService.resolveSecurePath("mutations/" + c1 + "/meta.json")).thenReturn(meta1);
        when(fileService.resolveSecurePath("mutations/" + c2 + "/meta.json")).thenReturn(meta2);

        CycleContextData ctx1 = CycleContextData.builder().cycleName(c1).outputPath("o").merged(false).build();
        CycleContextData ctx2 = CycleContextData.builder().cycleName(c2).outputPath("o").merged(true).build();

        when(mutationSnapshotService.readMutationMetaResolved(meta1)).thenReturn(ctx1);
        when(mutationSnapshotService.readMutationMetaResolved(meta2)).thenReturn(ctx2);

        // writeMutationMeta expects CycleContextData; it returns ctx.withMutationPath(...)
        when(mutationSnapshotService.writeMutationMeta(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.getArgument(0)); // we only care that it's called with merged=true

        CycleContextData result = tracker.markCyclesAsMerged(context);

        // should write only for ctx1 (was not merged)
        verify(mutationSnapshotService).writeMutationMeta(argThat(c -> c.cycleName().equals(c1) && c.merged()));
        verify(mutationSnapshotService, never()).writeMutationMeta(argThat(c -> c.cycleName().equals(c2)));

        // root context merged flag is about rootCycle only
        assertThat(result.merged()).isFalse();
    }

    @Test
    void markCyclesAsMerged_setsReturnedContextMerged_onlyWhenRootCycleIsInMergedCycles() throws Exception {
        String rootCycle = "cycle-root";

        CycleContextData context = CycleContextData.builder()
                .cycleName(rootCycle)
                .outputPath("output")
                .merged(false)
                .build()
                .withMergeTrace(new MergeTrace(
                        new ArrayList<>(List.of(rootCycle)),
                        new HashMap<>(),
                        new HashMap<>()
                ));

        Path meta = Path.of("mutations/" + rootCycle + "/meta.json");
        when(fileService.resolveSecurePath("mutations/" + rootCycle + "/meta.json")).thenReturn(meta);

        CycleContextData ctx = CycleContextData.builder().cycleName(rootCycle).outputPath("o").merged(false).build();
        when(mutationSnapshotService.readMutationMetaResolved(meta)).thenReturn(ctx);

        when(mutationSnapshotService.writeMutationMeta(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CycleContextData result = tracker.markCyclesAsMerged(context);

        verify(mutationSnapshotService).writeMutationMeta(argThat(c -> c.cycleName().equals(rootCycle) && c.merged()));
        assertThat(result.merged()).isTrue();
    }

    @Test
    void markCyclesAsMerged_continues_onIOException_forOneCycle() throws Exception {
        String rootCycle = "cycle-root";
        String bad = "cycle-bad";
        String ok = "cycle-ok";

        CycleContextData context = CycleContextData.builder()
                .cycleName(rootCycle)
                .outputPath("output")
                .merged(false)
                .build()
                .withMergeTrace(new MergeTrace(
                        new ArrayList<>(List.of(bad, ok)),
                        new HashMap<>(),
                        new HashMap<>()
                ));

        Path badMeta = Path.of("mutations/" + bad + "/meta.json");
        Path okMeta = Path.of("mutations/" + ok + "/meta.json");

        when(fileService.resolveSecurePath("mutations/" + bad + "/meta.json")).thenReturn(badMeta);
        when(fileService.resolveSecurePath("mutations/" + ok + "/meta.json")).thenReturn(okMeta);

        when(mutationSnapshotService.readMutationMetaResolved(badMeta)).thenThrow(new IOException("broken"));
        CycleContextData okCtx = CycleContextData.builder().cycleName(ok).outputPath("o").merged(false).build();
        when(mutationSnapshotService.readMutationMetaResolved(okMeta)).thenReturn(okCtx);

        when(mutationSnapshotService.writeMutationMeta(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CycleContextData result = tracker.markCyclesAsMerged(context);

        // should still mark ok cycle
        verify(mutationSnapshotService).writeMutationMeta(argThat(c -> c.cycleName().equals(ok) && c.merged()));

        // root context merged flag is about rootCycle only
        assertThat(result.merged()).isFalse();
    }

    // -----------------------------
    // track
    // -----------------------------

    @Test
    void track_noops_whenResultNotSuccessful() {
        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-x")
                .outputPath("output")
                .build()
                .withMergeTrace(new MergeTrace(new ArrayList<>(), new HashMap<>(), new HashMap<>()));

        MergeResult failed = MergeResult.asFailed("merge conflict");

        CycleContextData result = tracker.track(context, "pom.xml", failed);

        assertThat(result).isSameAs(context);
        assertThat(context.mergeTrace().mergedCycles()).isEmpty();
        assertThat(context.mergeTrace().postMergeContent()).isEmpty();
    }

    @Test
    void track_noops_whenNoMergeTrace() {
        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-x")
                .outputPath("output")
                .build(); // no mergeTrace

        MergeResult ok = MergeResult.asAdopted("prev", "curr", Set.of("c1"));

        CycleContextData result = tracker.track(context, "pom.xml", ok);

        assertThat(result).isSameAs(context);
        // nothing to assert on trace because it's null; just ensure no crash
    }

    @Test
    void track_appendsContributors_andWritesPreAndPostContent_onAdopted() {
        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-x")
                .outputPath("output")
                .build()
                .withMergeTrace(new MergeTrace(new ArrayList<>(List.of("old")), new HashMap<>(), new HashMap<>()));

        MergeResult adopted = MergeResult.asAdopted("base", "merged", Set.of("c-new"));

        CycleContextData result = tracker.track(context, "pom.xml", adopted);

        assertThat(result).isSameAs(context);
        assertThat(context.mergeTrace().mergedCycles()).contains("old", "c-new");
        assertThat(context.mergeTrace().preMergeContent()).containsEntry("pom.xml", "base");
        assertThat(context.mergeTrace().postMergeContent()).containsEntry("pom.xml", "merged");
    }

    @Test
    void track_writesPreMergeContentOnlyOnce_perPath() {
        CycleMergeTracker tracker = new CycleMergeTracker(null, null);

        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-x")
                .outputPath("output")
                .build()
                .withMergeTrace(new MergeTrace(new ArrayList<>(List.of()), new HashMap<>(), new HashMap<>()));

        tracker.track(context, "pom.xml", MergeResult.asAdopted("base-1", "curr-1", Set.of("c1")));
        tracker.track(context, "pom.xml", MergeResult.asAdopted("base-2", "curr-2", Set.of("c2")));

        assertThat(context.mergeTrace().preMergeContent()).containsEntry("pom.xml", "base-1");
        assertThat(context.mergeTrace().postMergeContent()).containsEntry("pom.xml", "curr-2");
        assertThat(context.mergeTrace().mergedCycles()).contains("c1", "c2");
    }

    @Test
    void track_appendsContributors_andWritesPreAndPostContent_onMerged() {
        CycleMergeTracker tracker = new CycleMergeTracker(null, null);

        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-x")
                .outputPath("output")
                .build()
                .withMergeTrace(new MergeTrace(new ArrayList<>(List.of("old")), new HashMap<>(), new HashMap<>()));

        MergeResult merged = MergeResult.asMerged("base", "merged", Set.of("c-new"));

        CycleContextData result = tracker.track(context, "pom.xml", merged);

        assertThat(result).isSameAs(context);
        assertThat(context.mergeTrace().mergedCycles()).contains("old", "c-new");
        assertThat(context.mergeTrace().preMergeContent()).containsEntry("pom.xml", "base");
        assertThat(context.mergeTrace().postMergeContent()).containsEntry("pom.xml", "merged");
    }

    @Test
    void getMergedCycles_returnsEmptyList_whenMergeTraceNull() {
        CycleMergeTracker tracker = new CycleMergeTracker(mock(com.walpro.autobuilder.core.service.FileService.class),
                mock(com.walpro.autobuilder.core.persistence.MutationSnapshotService.class));

        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-x")
                .outputPath("output")
                .build(); // mergeTrace null

        assertThat(tracker.getMergedCycles(context)).isEmpty();
    }

    @Test
    void getMergedCycles_returnsMergedCycles_whenMergeTracePresent() {
        CycleMergeTracker tracker = new CycleMergeTracker(mock(com.walpro.autobuilder.core.service.FileService.class),
                mock(com.walpro.autobuilder.core.persistence.MutationSnapshotService.class));

        List<String> mergedCycles = new ArrayList<>(List.of("c1", "c2"));

        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-x")
                .outputPath("output")
                .build()
                .withMergeTrace(new MergeTrace(mergedCycles, new HashMap<>(), new HashMap<>()));

        assertThat(tracker.getMergedCycles(context)).containsExactly("c1", "c2");
    }
}