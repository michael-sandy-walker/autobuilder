package com.walpro.autobuilder.agent.merge;

import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.core.service.merge.CycleMergeTracker;
import com.walpro.autobuilder.core.service.merge.FileVariantCollector;
import com.walpro.autobuilder.core.service.merge.MergeProcessor;
import com.walpro.autobuilder.core.service.merge.MergeResult;
import com.walpro.autobuilder.model.MergeTrace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MergeAgentTest {

    @Mock
    protected FileService fileService;

    @Mock
    protected FileVariantCollector fileVariantCollector;

    @Mock
    protected MergeProcessor mergeProcessor;

    @Mock
    protected CycleMergeTracker cycleMergeTracker;

    protected MergeAgent agent;

    @BeforeEach
    void setUp() {
        agent = new MergeAgent(
                fileService,
                fileVariantCollector,
                mergeProcessor,
                cycleMergeTracker
        );
    }

    @Test
    void role_returnsMerge() {
        assertThat(agent.role()).isEqualTo("merge");
    }

    @Test
    void mergeCompletedBranch_skipsWhenCycleAlreadyMerged() throws Exception {
        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-1")
                .outputPath("output")
                .build();

        when(cycleMergeTracker.isAlreadyMerged("cycle-1")).thenReturn(true);

        CycleContextData result = agent.mergeCompletedBranch(context);

        // Returns original context and does nothing else
        assertThat(result).isSameAs(context);

        verify(cycleMergeTracker).isAlreadyMerged("cycle-1");
        verifyNoInteractions(fileVariantCollector, mergeProcessor, fileService);
        verify(cycleMergeTracker, never()).markCyclesAsMerged(any());
    }

    @Test
    void mergeCompletedBranch_mergesVariantsWritesOutputAndMarksMerged() throws Exception {
        String cycleId = "cycle-1";
        String outputPath = "output";
        String relativePath = "src/main/java/com/example/demo/DemoApplication.java";

        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath(outputPath)
                .build();

        when(cycleMergeTracker.isAlreadyMerged(cycleId)).thenReturn(false);

        // One file to merge
        when(fileVariantCollector.getAllVariantPaths(context))
                .thenReturn(Set.of(relativePath));

        // Provide some variants (we don't care about their content in this test)
        List<FileVariant> variants = List.of(mock(FileVariant.class));
        when(fileVariantCollector.collect(eq(relativePath), anyList()))
                .thenReturn(variants);

        // No existing file in output
        when(fileService.exists(anyString())).thenReturn(false);

        Path resolvedOutputPath = Path.of(outputPath, relativePath);
        when(fileService.resolveSecurePath(anyString()))
                .thenReturn(resolvedOutputPath);

        MergeResult mergeResult = mock(MergeResult.class);
        when(mergeResult.wasSuccessful()).thenReturn(true);
        when(mergeResult.currentContent()).thenReturn("merged-content");

        when(mergeProcessor.process(
                eq(relativePath),
                anyString(),
                eq(variants),
                eq(false),
                any(CycleContextData.class)
        )).thenReturn(mergeResult);

        when(cycleMergeTracker.track(context, relativePath, mergeResult))
                .thenReturn(context);


        when(cycleMergeTracker.markCyclesAsMerged(any(CycleContextData.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        CycleContextData result = agent.mergeCompletedBranch(context);

        // Assert pipeline
        verify(cycleMergeTracker).isAlreadyMerged(cycleId);
        verify(fileVariantCollector).getAllVariantPaths(context);
        verify(fileVariantCollector).collect(eq(relativePath), anyList());

        verify(mergeProcessor).process(
                eq(relativePath),
                anyString(),
                eq(variants),
                eq(false),
                any(CycleContextData.class)
        );

        verify(fileService).writeFileResolved(eq(resolvedOutputPath), eq("merged-content"));
        verify(cycleMergeTracker).track(context, relativePath, mergeResult);
        verify(cycleMergeTracker).markCyclesAsMerged(context);

        // The final context is whatever markCyclesAsMerged returned (here: same as input)
        assertThat(result).isSameAs(context);
    }

    @Test
    void execute_delegatesToMergeCompletedBranch_andWrapsIOException() throws Exception {
        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-io")
                .outputPath("output")
                .build()
                .withSignal(Signal.of(BasicSignals.ROLE_MERGE, 0.5, 0.1, "test"));

        MergeAgent spyCoordinator = spy(new MergeAgent(
                fileService,
                fileVariantCollector,
                mergeProcessor,
                cycleMergeTracker
        ));

        // Happy path: execute() returns what mergeCompletedBranch returns
        CycleContextData merged = CycleContextData.builder()
                .cycleName("cycle-io-merged")
                .outputPath("output")
                .build();

        when(cycleMergeTracker.markCyclesAsMerged(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(spyCoordinator.mergeCompletedBranch(context)).thenReturn(merged);

        CycleContextData result = spyCoordinator.execute(context);
        assertThat(result).isSameAs(merged);

        // IOException wrapping
        MergeAgent throwingSpy = spy(new MergeAgent(
                fileService,
                fileVariantCollector,
                mergeProcessor,
                cycleMergeTracker
        ));

        when(throwingSpy.mergeCompletedBranch(context))
                .thenThrow(new IOException("boom"));

        Throwable thrown = catchThrowable(() -> throwingSpy.execute(context));
        assertThat(thrown)
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void mergeCompletedBranch_consumesRoleMergeSignal() throws Exception {
        String cycleId = "cycle-signal";
        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath("output")
                .build()
                .withSignal(Signal.of(BasicSignals.ROLE_MERGE, 0.5, 0.1, "test"));

        when(cycleMergeTracker.isAlreadyMerged(cycleId)).thenReturn(false);
        when(fileVariantCollector.getAllVariantPaths(context)).thenReturn(Set.of()); // no work

        CycleContextData marked = context; // assume mark returns same instance in this test
        when(cycleMergeTracker.markCyclesAsMerged(any(CycleContextData.class))).thenReturn(marked);

        assertThat(context.hasSignal(BasicSignals.ROLE_MERGE)).isTrue();

        CycleContextData result = agent.mergeCompletedBranch(context);

        assertThat(result.hasSignal(BasicSignals.ROLE_MERGE)).isFalse();
        verify(cycleMergeTracker).markCyclesAsMerged(context);
    }

    @Test
    void mergeCompletedBranch_whenFileExists_readsBaseContentAndPassesItToProcessor() throws Exception {
        String cycleId = "cycle-base";
        String outputPath = "output";
        String relativePath = "pom.xml";

        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath(outputPath)
                .build();

        when(cycleMergeTracker.isAlreadyMerged(cycleId)).thenReturn(false);
        when(fileVariantCollector.getAllVariantPaths(context)).thenReturn(Set.of(relativePath));

        List<FileVariant> variants = List.of(mock(FileVariant.class));
        when(fileVariantCollector.collect(eq(relativePath), anyList())).thenReturn(variants);

        String outputFileRel = outputPath + "/" + relativePath;

        when(fileService.exists(outputFileRel)).thenReturn(true);
        when(fileService.readString(outputFileRel)).thenReturn("existing-content");

        MergeResult mergeResult = mock(MergeResult.class);
        when(mergeResult.wasSuccessful()).thenReturn(true);
        when(mergeResult.currentContent()).thenReturn("merged-content");

        when(mergeProcessor.process(
                eq(relativePath),
                eq("existing-content"),
                eq(variants),
                eq(true),
                eq(context)
        )).thenReturn(mergeResult);

        Path resolved = Path.of(outputPath, relativePath);
        when(fileService.resolveSecurePath(outputFileRel)).thenReturn(resolved);

        when(cycleMergeTracker.track(any(CycleContextData.class), anyString(), any(MergeResult.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(cycleMergeTracker.markCyclesAsMerged(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CycleContextData result = agent.mergeCompletedBranch(context);

        verify(fileService).readString(outputFileRel);
        verify(mergeProcessor).process(relativePath, "existing-content", variants, true, context);
        verify(fileService).writeFileResolved(resolved, "merged-content");
        verify(cycleMergeTracker).markCyclesAsMerged(context);

        assertThat(result).isSameAs(context);
    }

    @Test
    void mergeCompletedBranch_passesAlreadyMergedCyclesToCollector() throws Exception {
        String cycleId = "cycle-trace";
        String outputPath = "output";
        String relativePath = "pom.xml";

        // Build a context that has mergeTrace mergedCycles = ["cycle-old-1", "cycle-old-2"]
        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath(outputPath)
                .build();

        context = context.withMergeTrace(
                new MergeTrace(List.of("cycle-old-1", "cycle-old-2"), new HashMap<>(), new HashMap<>())
        );

        when(cycleMergeTracker.isAlreadyMerged(cycleId)).thenReturn(false);
        when(cycleMergeTracker.getMergedCycles(context)).thenReturn(List.of("cycle-old-1", "cycle-old-2"));
        when(fileVariantCollector.getAllVariantPaths(context)).thenReturn(Set.of(relativePath));

        when(fileVariantCollector.collect(eq(relativePath), eq(List.of("cycle-old-1", "cycle-old-2"))))
                .thenReturn(List.of()); // empty so it stops early

        when(cycleMergeTracker.markCyclesAsMerged(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        agent.mergeCompletedBranch(context);

        verify(fileVariantCollector).collect(relativePath, List.of("cycle-old-1", "cycle-old-2"));
    }

    @Test
    void mergeCompletedBranch_whenCollectorThrows_continuesWithOtherPaths() throws Exception {
        String cycleId = "cycle-collect-io";
        String outputPath = "output";
        String badPath = "a.txt";
        String okPath = "b.txt";

        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath(outputPath)
                .build();

        when(cycleMergeTracker.isAlreadyMerged(cycleId)).thenReturn(false);
        when(fileVariantCollector.getAllVariantPaths(context)).thenReturn(Set.of(badPath, okPath));

        when(fileVariantCollector.collect(eq(badPath), anyList()))
                .thenThrow(new java.io.IOException("boom"));

        List<FileVariant> okVariants = List.of(mock(FileVariant.class));
        when(fileVariantCollector.collect(eq(okPath), anyList())).thenReturn(okVariants);

        when(fileService.exists(anyString())).thenReturn(false);

        MergeResult ok = MergeResult.asAdopted("", "merged", Set.of(cycleId));
        when(mergeProcessor.process(eq(okPath), eq(""), eq(okVariants), eq(false), eq(context))).thenReturn(ok);

        Path resolvedOk = Path.of(outputPath, okPath);
        when(fileService.resolveSecurePath(eq(outputPath + "/" + okPath))).thenReturn(resolvedOk);

        when(cycleMergeTracker.track(any(CycleContextData.class), anyString(), any(MergeResult.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(cycleMergeTracker.markCyclesAsMerged(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        agent.mergeCompletedBranch(context);

        verify(mergeProcessor).process(eq(okPath), eq(""), eq(okVariants), eq(false), eq(context));
        verify(fileService).writeFileResolved(resolvedOk, "merged");
        verify(cycleMergeTracker).markCyclesAsMerged(context);
    }

    @Test
    void mergeCompletedBranch_whenWriteFails_doesNotTrackThatPath() throws Exception {
        String cycleId = "cycle-write-io";
        String outputPath = "output";
        String path = "pom.xml";

        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath(outputPath)
                .build();

        when(cycleMergeTracker.isAlreadyMerged(cycleId)).thenReturn(false);
        when(fileVariantCollector.getAllVariantPaths(context)).thenReturn(Set.of(path));

        List<FileVariant> variants = List.of(mock(FileVariant.class));
        when(fileVariantCollector.collect(eq(path), anyList())).thenReturn(variants);

        when(fileService.exists(anyString())).thenReturn(false);

        MergeResult ok = MergeResult.asAdopted("", "merged", Set.of(cycleId));
        when(mergeProcessor.process(eq(path), eq(""), eq(variants), eq(false), eq(context))).thenReturn(ok);

        Path resolved = Path.of(outputPath, path);
        when(fileService.resolveSecurePath(eq(outputPath + "/" + path))).thenReturn(resolved);

        doThrow(new java.io.IOException("disk full"))
                .when(fileService).writeFileResolved(resolved, "merged");

        when(cycleMergeTracker.markCyclesAsMerged(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        agent.mergeCompletedBranch(context);

        verify(cycleMergeTracker, never()).track(any(), anyString(), any());
        verify(cycleMergeTracker).markCyclesAsMerged(context);
    }

    @Test
    void mergeCompletedBranch_mergesTwoFiles_writesBoth_andMarksMerged() throws Exception {
        String cycleId = "cycle-2";
        String outputPath = "output";

        String pathA = "pom.xml";
        String pathB = "src/main/java/com/example/HelloController.java";

        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath(outputPath)
                .build();

        when(cycleMergeTracker.isAlreadyMerged(cycleId)).thenReturn(false);

        // Two files to merge
        when(fileVariantCollector.getAllVariantPaths(context))
                .thenReturn(new java.util.LinkedHashSet<>(List.of(pathA, pathB)));

        List<FileVariant> variantsA = List.of(mock(FileVariant.class));
        List<FileVariant> variantsB = List.of(mock(FileVariant.class));

        when(fileVariantCollector.collect(eq(pathA), anyList())).thenReturn(variantsA);
        when(fileVariantCollector.collect(eq(pathB), anyList())).thenReturn(variantsB);

        // Assume both files do NOT exist in output yet
        when(fileService.exists(eq("output/" + pathA))).thenReturn(false);
        when(fileService.exists(eq("output/" + pathB))).thenReturn(false);

        // So baseContent should be empty (readString shouldn't be called, but safe to stub)
//        when(fileService.readString(anyString())).thenReturn("");

        // Merge results: both successful
        MergeResult resultA = MergeResult.asAdopted("", "merged-pom", Set.of(cycleId));
        MergeResult resultB = MergeResult.asAdopted("", "merged-controller", Set.of(cycleId));

        when(mergeProcessor.process(eq(pathA), eq(""), eq(variantsA), eq(false), any(CycleContextData.class)))
                .thenReturn(resultA);
        when(mergeProcessor.process(eq(pathB), eq(""), eq(variantsB), eq(false), any(CycleContextData.class)))
                .thenReturn(resultB);

        // write target paths must be resolved per call; easiest: return the actual intended path
        Path resolvedA = Path.of(outputPath, pathA);
        Path resolvedB = Path.of(outputPath, pathB);

        when(fileService.resolveSecurePath(eq(outputPath + "/" + pathA))).thenReturn(resolvedA);
        when(fileService.resolveSecurePath(eq(outputPath + "/" + pathB))).thenReturn(resolvedB);

        // tracking returns same context for simplicity
        when(cycleMergeTracker.track(any(CycleContextData.class), anyString(), any(MergeResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(cycleMergeTracker.markCyclesAsMerged(any(CycleContextData.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        CycleContextData result = agent.mergeCompletedBranch(context);

        // Assert: process called for both
        verify(mergeProcessor).process(eq(pathA), eq(""), eq(variantsA), eq(false), any(CycleContextData.class));
        verify(mergeProcessor).process(eq(pathB), eq(""), eq(variantsB), eq(false), any(CycleContextData.class));

        // Assert: writes happened for both files
        verify(fileService).writeFileResolved(resolvedA, "merged-pom");
        verify(fileService).writeFileResolved(resolvedB, "merged-controller");

        // Assert: tracker called for both
        verify(cycleMergeTracker).track(eq(context), eq(pathA), eq(resultA));
        verify(cycleMergeTracker).track(eq(context), eq(pathB), eq(resultB));

        // Final: marked merged
        verify(cycleMergeTracker).markCyclesAsMerged(context);

        assertThat(result).isSameAs(context);
    }

    @Test
    void mergeCompletedBranch_skipsFailedMerge_butStillWritesSuccessfulFiles_andMarksMerged() throws Exception {
        String cycleId = "cycle-3";
        String outputPath = "output";

        String okPath = "pom.xml";
        String badPath = "src/main/java/com/example/Bad.java";

        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath(outputPath)
                .build();

        when(cycleMergeTracker.isAlreadyMerged(cycleId)).thenReturn(false);
        when(fileVariantCollector.getAllVariantPaths(context)).thenReturn(Set.of(okPath, badPath));

        List<FileVariant> okVariants = List.of(mock(FileVariant.class));
        List<FileVariant> badVariants = List.of(mock(FileVariant.class));

        when(fileVariantCollector.collect(eq(okPath), anyList())).thenReturn(okVariants);
        when(fileVariantCollector.collect(eq(badPath), anyList())).thenReturn(badVariants);

        when(fileService.exists(anyString())).thenReturn(false);

        MergeResult ok = MergeResult.asAdopted("", "merged-ok", Set.of(cycleId));
        MergeResult failed = MergeResult.asFailed();

        when(mergeProcessor.process(eq(okPath), eq(""), eq(okVariants), eq(false), any(CycleContextData.class)))
                .thenReturn(ok);
        when(mergeProcessor.process(eq(badPath), eq(""), eq(badVariants), eq(false), any(CycleContextData.class)))
                .thenReturn(failed);

        Path resolvedOk = Path.of(outputPath, okPath);
        when(fileService.resolveSecurePath(eq(outputPath + "/" + okPath))).thenReturn(resolvedOk);

        when(cycleMergeTracker.track(any(CycleContextData.class), anyString(), any(MergeResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(cycleMergeTracker.markCyclesAsMerged(any(CycleContextData.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CycleContextData result = agent.mergeCompletedBranch(context);

        // ok file written
        verify(fileService).writeFileResolved(resolvedOk, "merged-ok");

        // bad file NOT written
        verify(fileService, never()).writeFileResolved(eq(Path.of(outputPath, badPath)), anyString());

        // tracker invoked only for successful file
        verify(cycleMergeTracker).track(eq(context), eq(okPath), eq(ok));
        verify(cycleMergeTracker, never()).track(eq(context), eq(badPath), eq(failed));

        verify(cycleMergeTracker).markCyclesAsMerged(context);

        assertThat(result).isSameAs(context);
    }

    @Test
    void mergeCompletedBranch_accumulatesContextReturnedByTrack() throws Exception {
        String cycleId = "cycle-track-acc";
        String outputPath = "output";
        String pathA = "a.txt";
        String pathB = "b.txt";

        CycleContextData ctx0 = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath(outputPath)
                .build();

        when(cycleMergeTracker.isAlreadyMerged(cycleId)).thenReturn(false);
        when(fileVariantCollector.getAllVariantPaths(ctx0)).thenReturn(Set.of(pathA, pathB));

        List<FileVariant> variantsA = List.of(mock(FileVariant.class));
        List<FileVariant> variantsB = List.of(mock(FileVariant.class));

        when(fileVariantCollector.collect(eq(pathA), anyList())).thenReturn(variantsA);
        when(fileVariantCollector.collect(eq(pathB), anyList())).thenReturn(variantsB);

        when(fileService.exists(anyString())).thenReturn(false);

        MergeResult rA = MergeResult.asAdopted("", "A", Set.of(cycleId));
        MergeResult rB = MergeResult.asAdopted("", "B", Set.of(cycleId));

        when(mergeProcessor.process(eq(pathA), eq(""), eq(variantsA), eq(false), any(CycleContextData.class))).thenReturn(rA);
        when(mergeProcessor.process(eq(pathB), eq(""), eq(variantsB), eq(false), any(CycleContextData.class))).thenReturn(rB);

        when(fileService.resolveSecurePath(eq(outputPath + "/" + pathA))).thenReturn(Path.of(outputPath, pathA));
        when(fileService.resolveSecurePath(eq(outputPath + "/" + pathB))).thenReturn(Path.of(outputPath, pathB));

        CycleContextData ctx1 = CycleContextData.builder().cycleName(cycleId).outputPath(outputPath).build(); // pretend enriched
        CycleContextData ctx2 = CycleContextData.builder().cycleName(cycleId).outputPath(outputPath).build(); // enriched again

        when(cycleMergeTracker.track(eq(ctx0), eq(pathA), eq(rA))).thenReturn(ctx1);
        when(cycleMergeTracker.track(eq(ctx1), eq(pathB), eq(rB))).thenReturn(ctx2);

        when(cycleMergeTracker.markCyclesAsMerged(eq(ctx2))).thenReturn(ctx2);

        agent.mergeCompletedBranch(ctx0);

        // Key assertions: the second track must receive ctx1, not ctx0
        verify(cycleMergeTracker).track(ctx0, pathA, rA);
        verify(cycleMergeTracker).track(ctx1, pathB, rB);
        verify(cycleMergeTracker).markCyclesAsMerged(ctx2);
    }

    // Dynamic MergeAgent test
    @Test
    void mergeCompletedBranch_updatesMergedCyclesBetweenPaths() throws Exception {
        String cycleId = "cycle-dyn";
        String outputPath = "output";
        String pathA = "a.txt";
        String pathB = "b.txt";

        CycleContextData context = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath(outputPath)
                .build()
                .withMergeTrace(new MergeTrace(
                        new java.util.ArrayList<>(List.of("cycle-old")),
                        new HashMap<>(),
                        new HashMap<>()
                ));

        when(cycleMergeTracker.isAlreadyMerged(cycleId)).thenReturn(false);
        when(fileVariantCollector.getAllVariantPaths(context)).thenReturn(new java.util.LinkedHashSet<>(List.of(pathA, pathB)));

        // Key: getMergedCycles returns different lists per iteration
        when(cycleMergeTracker.getMergedCycles(context))
                .thenReturn(List.of("cycle-old"))
                .thenReturn(List.of("cycle-old", "cycle-new"));

        when(fileVariantCollector.collect(eq(pathA), eq(List.of("cycle-old"))))
                .thenReturn(List.of(mock(FileVariant.class)));

        when(fileVariantCollector.collect(eq(pathB), eq(List.of("cycle-old"))))
                .thenReturn(List.of(mock(FileVariant.class)));

        when(fileService.exists(anyString())).thenReturn(false);

        MergeResult okA = MergeResult.asAdopted("", "A", Set.of("cycle-new"));
        MergeResult okB = MergeResult.asAdopted("", "B", Set.of("cycle-new"));

        when(mergeProcessor.process(eq(pathA), eq(""), anyList(), eq(false), eq(context))).thenReturn(okA);
        when(mergeProcessor.process(eq(pathB), eq(""), anyList(), eq(false), any(CycleContextData.class))).thenReturn(okB);

        when(fileService.resolveSecurePath(eq(outputPath + "/" + pathA))).thenReturn(Path.of(outputPath, pathA));
        when(fileService.resolveSecurePath(eq(outputPath + "/" + pathB))).thenReturn(Path.of(outputPath, pathB));

        // track mutates the trace in-place (real behavior)
        when(cycleMergeTracker.track(eq(context), eq(pathA), eq(okA)))
                .thenAnswer(inv -> {
                    CycleContextData ctx = inv.getArgument(0);
                    ctx.mergeTrace().mergedCycles().addAll(okA.contributingCycles());
                    return ctx;
                });

        when(cycleMergeTracker.track(any(CycleContextData.class), eq(pathB), eq(okB)))
                .thenAnswer(inv -> inv.getArgument(0));

        when(cycleMergeTracker.markCyclesAsMerged(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        agent.mergeCompletedBranch(context);

        verify(fileVariantCollector).collect(pathA, List.of("cycle-old"));
        verify(fileVariantCollector).collect(pathB, List.of("cycle-old"));

        verify(cycleMergeTracker, times(1)).getMergedCycles(context);
    }
}
