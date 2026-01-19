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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
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
    void canExecute_onlyWhenMergeRoleSignalAndVariantPresent() throws IOException {
        CycleContextData ctxWithoutSignal = CycleContextData.builder().build();
        assertThat(agent.canExecute(ctxWithoutSignal)).isFalse();

        CycleContextData ctxWithSignal = ctxWithoutSignal.withSignal(
                Signal.of(BasicSignals.ROLE_MERGE, 0.5, 0.1, "test")
        );

        when(fileVariantCollector.hasAnyVariant(ctxWithSignal)).thenReturn(true);

        assertThat(agent.canExecute(ctxWithSignal)).isTrue();
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
}
