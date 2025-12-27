package com.walpro.autobuilder.core.coordinator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.merge.MergeResult;

class MergeCoordinatorMultiFileTest extends MergeCoordinatorTest {

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
                .thenReturn(Set.of(pathA, pathB));

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
        MergeResult resultA = MergeResult.asPromoted("", "merged-pom", Set.of(cycleId));
        MergeResult resultB = MergeResult.asPromoted("", "merged-controller", Set.of(cycleId));

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
        CycleContextData result = coordinator.mergeCompletedBranch(context);

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

        MergeResult ok = MergeResult.asPromoted("", "merged-ok", Set.of(cycleId));
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

        CycleContextData result = coordinator.mergeCompletedBranch(context);

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
}
