package com.walpro.autobuilder.core.service.merge;

import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.persistence.MutationSnapshotService;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.model.FileInfo;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileVariantCollectorTest {

    @Mock
    FileService fileService;

    @Mock
    MutationSnapshotService mutationSnapshotService;

    FileVariantCollector collector;

    @BeforeEach
    void setUp() {
        collector = new FileVariantCollector(fileService, mutationSnapshotService);
    }

    @Test
    void collect_skipsAlreadyMergedCycles() throws Exception {
        String relativePath = "pom.xml";

        Path folder1 = Path.of("mutations/cycle-1");
        Path folder2 = Path.of("mutations/cycle-2");

        when(fileService.listSubfolders("mutations")).thenReturn(List.of(folder1, folder2));

        // cycle-1 is already merged -> should be skipped without resolving files
        List<FileVariant> result = collector.collect(relativePath, List.of("cycle-1"), null);

        assertThat(result).isEmpty();

        // Only cycle-2 should even proceed to resolveSecurePath checks
        verify(fileService, never()).resolveSecurePath(startsWith("mutations/cycle-1/"));
        verify(fileService, atLeastOnce()).resolveSecurePath(startsWith("mutations/cycle-2/"));
    }

    @Test
    void collect_skipsWhenVariantOrMetaMissing() throws Exception {
        String relativePath = "pom.xml";
        Path folder = Path.of("mutations/cycle-1");

        when(fileService.listSubfolders("mutations")).thenReturn(List.of(folder));

        Path variantPath = Path.of("/abs/mutations/cycle-1/pom.xml");
        Path metaPath = Path.of("/abs/mutations/cycle-1/meta.json");

        when(fileService.resolveSecurePath("mutations/cycle-1/" + relativePath)).thenReturn(variantPath);
        when(fileService.resolveSecurePath("mutations/cycle-1/meta.json")).thenReturn(metaPath);

        // Variant missing
        when(fileService.existsResolved(variantPath)).thenReturn(false);
//        when(fileService.existsResolved(metaPath)).thenReturn(true);

        List<FileVariant> result = collector.collect(relativePath, List.of(), null);

        assertThat(result).isEmpty();
        verifyNoInteractions(mutationSnapshotService);
    }

    @Test
    void collect_skipsWhenMetaReadFails() throws Exception {
        String relativePath = "pom.xml";
        Path folder = Path.of("mutations/cycle-1");

        when(fileService.listSubfolders("mutations")).thenReturn(List.of(folder));

        Path variantPath = Path.of("/abs/mutations/cycle-1/pom.xml");
        Path metaPath = Path.of("/abs/mutations/cycle-1/meta.json");

        when(fileService.resolveSecurePath("mutations/cycle-1/" + relativePath)).thenReturn(variantPath);
        when(fileService.resolveSecurePath("mutations/cycle-1/meta.json")).thenReturn(metaPath);

        when(fileService.existsResolved(variantPath)).thenReturn(true);
        when(fileService.existsResolved(metaPath)).thenReturn(true);

        when(mutationSnapshotService.readMutationMetaResolved(metaPath))
                .thenThrow(new IOException("bad json"));

        List<FileVariant> result = collector.collect(relativePath, List.of(), null);

        assertThat(result).isEmpty();
        verify(fileService, never()).readStringResolved(variantPath);
    }

    @Test
    void collect_skipsInvalidSubgoalMetadata_bootstrapOrMissingSubgoalId() throws Exception {
        String relativePath = "pom.xml";
        Path folder = Path.of("mutations/cycle-1");

        when(fileService.listSubfolders("mutations")).thenReturn(List.of(folder));

        Path variantPath = Path.of("/abs/mutations/cycle-1/pom.xml");
        Path metaPath = Path.of("/abs/mutations/cycle-1/meta.json");

        when(fileService.resolveSecurePath("mutations/cycle-1/" + relativePath)).thenReturn(variantPath);
        when(fileService.resolveSecurePath("mutations/cycle-1/meta.json")).thenReturn(metaPath);

        when(fileService.existsResolved(variantPath)).thenReturn(true);
        when(fileService.existsResolved(metaPath)).thenReturn(true);

        // currentSubGoalId != null but blank => invalid meta
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-1")
                .outputPath("output")
                .currentSubGoalId("   ")
                .currentSubGoalDescription("Write tests")
                .build();

        when(mutationSnapshotService.readMutationMetaResolved(metaPath)).thenReturn(ctx);

        List<FileVariant> result = collector.collect(relativePath, List.of(), null);

        assertThat(result).isEmpty();
        verify(fileService, never()).readStringResolved(variantPath);
    }

    @Test
    void collect_skipsWhenMetaMarkedMerged() throws Exception {
        String relativePath = "pom.xml";
        Path folder = Path.of("mutations/cycle-1");

        when(fileService.listSubfolders("mutations")).thenReturn(List.of(folder));

        Path variantPath = Path.of("/abs/mutations/cycle-1/pom.xml");
        Path metaPath = Path.of("/abs/mutations/cycle-1/meta.json");

        when(fileService.resolveSecurePath("mutations/cycle-1/" + relativePath)).thenReturn(variantPath);
        when(fileService.resolveSecurePath("mutations/cycle-1/meta.json")).thenReturn(metaPath);

        when(fileService.existsResolved(variantPath)).thenReturn(true);
        when(fileService.existsResolved(metaPath)).thenReturn(true);

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-1")
                .outputPath("output")
                .currentSubGoalId("goal-1")
                .currentSubGoalDescription("Write tests")
                .merged(true)
                .build();

        when(mutationSnapshotService.readMutationMetaResolved(metaPath)).thenReturn(ctx);

        List<FileVariant> result = collector.collect(relativePath, List.of(), null);

        assertThat(result).isEmpty();
        verify(fileService, never()).readStringResolved(variantPath);
    }

    @Test
    void collect_skipsRejectedCycle() throws Exception {
        String relativePath = "pom.xml";
        Path folder = Path.of("mutations/cycle-1");

        when(fileService.listSubfolders("mutations")).thenReturn(List.of(folder));

        Path variantPath = Path.of("/abs/mutations/cycle-1/pom.xml");
        Path metaPath = Path.of("/abs/mutations/cycle-1/meta.json");

        when(fileService.resolveSecurePath("mutations/cycle-1/" + relativePath)).thenReturn(variantPath);
        when(fileService.resolveSecurePath("mutations/cycle-1/meta.json")).thenReturn(metaPath);

        when(fileService.existsResolved(variantPath)).thenReturn(true);
        when(fileService.existsResolved(metaPath)).thenReturn(true);

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-1")
                .outputPath("output")
                .currentSubGoalId("goal-1")
                .currentSubGoalDescription("Write tests")
                .merged(false)
                .mutationState(MutationAcceptanceState.REJECTED)
                .build();

        when(mutationSnapshotService.readMutationMetaResolved(metaPath)).thenReturn(ctx);

        List<FileVariant> result = collector.collect(relativePath, List.of(), null);

        assertThat(result).isEmpty();
        verify(fileService, never()).readStringResolved(variantPath);
    }

    @Test
    void collect_skipsNullMutationStateCycle() throws Exception {
        String relativePath = "pom.xml";
        Path folder = Path.of("mutations/cycle-1");

        when(fileService.listSubfolders("mutations")).thenReturn(List.of(folder));

        Path variantPath = Path.of("/abs/mutations/cycle-1/pom.xml");
        Path metaPath = Path.of("/abs/mutations/cycle-1/meta.json");

        when(fileService.resolveSecurePath("mutations/cycle-1/" + relativePath)).thenReturn(variantPath);
        when(fileService.resolveSecurePath("mutations/cycle-1/meta.json")).thenReturn(metaPath);

        when(fileService.existsResolved(variantPath)).thenReturn(true);
        when(fileService.existsResolved(metaPath)).thenReturn(true);

        // mutationState left null (not set) — evaluation-only or incomplete cycle
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-1")
                .outputPath("output")
                .currentSubGoalId("goal-1")
                .currentSubGoalDescription("Write tests")
                .merged(false)
                .build();

        when(mutationSnapshotService.readMutationMetaResolved(metaPath)).thenReturn(ctx);

        List<FileVariant> result = collector.collect(relativePath, List.of(), null);

        assertThat(result).isEmpty();
        verify(fileService, never()).readStringResolved(variantPath);
    }

    @Test
    void collect_includesBuildConfigVariantRegardlessOfSubgoal() throws Exception {
        // pom.xml is project-wide: subGoalId mismatch must not exclude it.
        // This is the scenario where the planner replaced a BUILD_FIX subgoal with a plain
        // subgoal — the original pom.xml mutation (goal-OLD) should still be mergeable.
        String relativePath = "pom.xml";
        Path folder = Path.of("mutations/cycle-1");

        when(fileService.listSubfolders("mutations")).thenReturn(List.of(folder));

        Path variantPath = Path.of("/abs/mutations/cycle-1/pom.xml");
        Path metaPath = Path.of("/abs/mutations/cycle-1/meta.json");

        when(fileService.resolveSecurePath("mutations/cycle-1/" + relativePath)).thenReturn(variantPath);
        when(fileService.resolveSecurePath("mutations/cycle-1/meta.json")).thenReturn(metaPath);

        when(fileService.existsResolved(variantPath)).thenReturn(true);
        when(fileService.existsResolved(metaPath)).thenReturn(true);
        when(fileService.readStringResolved(variantPath)).thenReturn("<project/>");

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-1")
                .outputPath("output")
                .currentSubGoalId("goal-OLD")
                .currentSubGoalDescription("Old subgoal")
                .merged(false)
                .mutationState(MutationAcceptanceState.ACCEPTED)
                .build();

        when(mutationSnapshotService.readMutationMetaResolved(metaPath)).thenReturn(ctx);

        List<FileVariant> result = collector.collect(relativePath, List.of(), "goal-ACTIVE");

        assertThat(result).hasSize(1);
    }

    @Test
    void collect_skipsJavaFileVariantBelongingToDifferentSubgoal() throws Exception {
        // Non-build-config files (Java sources) remain subgoal-scoped.
        String relativePath = "src/main/java/Foo.java";
        Path folder = Path.of("mutations/cycle-1");

        when(fileService.listSubfolders("mutations")).thenReturn(List.of(folder));

        Path variantPath = Path.of("/abs/mutations/cycle-1/src/main/java/Foo.java");
        Path metaPath = Path.of("/abs/mutations/cycle-1/meta.json");

        when(fileService.resolveSecurePath("mutations/cycle-1/" + relativePath)).thenReturn(variantPath);
        when(fileService.resolveSecurePath("mutations/cycle-1/meta.json")).thenReturn(metaPath);

        when(fileService.existsResolved(variantPath)).thenReturn(true);
        when(fileService.existsResolved(metaPath)).thenReturn(true);

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-1")
                .outputPath("output")
                .currentSubGoalId("goal-OLD")
                .currentSubGoalDescription("Old subgoal")
                .merged(false)
                .mutationState(MutationAcceptanceState.ACCEPTED)
                .build();

        when(mutationSnapshotService.readMutationMetaResolved(metaPath)).thenReturn(ctx);

        List<FileVariant> result = collector.collect(relativePath, List.of(), "goal-ACTIVE");

        assertThat(result).isEmpty();
        verify(fileService, never()).readStringResolved(variantPath);
    }

    @Test
    void collect_includesAcceptedCycle() throws Exception {
        String relativePath = "pom.xml";
        Path folder = Path.of("mutations/cycle-1");

        when(fileService.listSubfolders("mutations")).thenReturn(List.of(folder));

        Path variantPath = Path.of("/abs/mutations/cycle-1/pom.xml");
        Path metaPath = Path.of("/abs/mutations/cycle-1/meta.json");

        when(fileService.resolveSecurePath("mutations/cycle-1/" + relativePath)).thenReturn(variantPath);
        when(fileService.resolveSecurePath("mutations/cycle-1/meta.json")).thenReturn(metaPath);

        when(fileService.existsResolved(variantPath)).thenReturn(true);
        when(fileService.existsResolved(metaPath)).thenReturn(true);

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-1")
                .outputPath("output")
                .currentSubGoalId("goal-1")
                .currentSubGoalDescription("Write tests")
                .merged(false)
                .mutationState(MutationAcceptanceState.ACCEPTED)
                .build();

        when(mutationSnapshotService.readMutationMetaResolved(metaPath)).thenReturn(ctx);
        when(fileService.readStringResolved(variantPath)).thenReturn("<xml/>");

        List<FileVariant> result = collector.collect(relativePath, List.of(), "goal-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).originCycleId()).isEqualTo("cycle-1");
    }

    @Test
    void collect_returnsVariantWithContentCycleIdAndSubgoal() throws Exception {
        String relativePath = "pom.xml";
        Path folder = Path.of("mutations/cycle-1");

        when(fileService.listSubfolders("mutations")).thenReturn(List.of(folder));

        Path variantPath = Path.of("/abs/mutations/cycle-1/pom.xml");
        Path metaPath = Path.of("/abs/mutations/cycle-1/meta.json");

        when(fileService.resolveSecurePath("mutations/cycle-1/" + relativePath)).thenReturn(variantPath);
        when(fileService.resolveSecurePath("mutations/cycle-1/meta.json")).thenReturn(metaPath);

        when(fileService.existsResolved(variantPath)).thenReturn(true);
        when(fileService.existsResolved(metaPath)).thenReturn(true);

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-1")
                .outputPath("output")
                .currentSubGoalId("goal-1")
                .currentSubGoalDescription("Write tests")
                .merged(false)
                .mutationState(MutationAcceptanceState.ACCEPTED)
                .build();

        when(mutationSnapshotService.readMutationMetaResolved(metaPath)).thenReturn(ctx);
        when(fileService.readStringResolved(variantPath)).thenReturn("<xml/>");

        List<FileVariant> result = collector.collect(relativePath, List.of(), "goal-1");

        assertThat(result).hasSize(1);
        FileVariant v = result.get(0);

        assertThat(v.originCycleId()).isEqualTo("cycle-1");
        assertThat(v.content()).isEqualTo("<xml/>");
        assertThat(v.subgoals()).hasSize(1);
        assertThat(v.subgoals().iterator().next().id()).isEqualTo("goal-1");
        assertThat(v.subgoals().iterator().next().description()).isEqualTo("Write tests");
    }

    @Test
    void getAllVariantPaths_collectsPathsFromUnmergedCycles_andSkipsMetaJson_andNormalizesSeparators() throws Exception {
        Path folder = Path.of("mutations/cycle-1");

        when(fileService.listSubfolders("mutations")).thenReturn(List.of(folder));

        Path metaPath = Path.of("/abs/mutations/cycle-1/meta.json");
        when(fileService.resolveSecurePath("mutations/cycle-1/meta.json")).thenReturn(metaPath);
        when(fileService.existsResolved(metaPath)).thenReturn(true);

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-1")
                .outputPath("output")
                .merged(false)
                .mutationState(MutationAcceptanceState.ACCEPTED)
                .build();

        when(mutationSnapshotService.readMutationMetaResolved(metaPath)).thenReturn(ctx);

        FileInfo pom = mock(FileInfo.class);
        when(pom.getName()).thenReturn("pom.xml");

        FileInfo meta = mock(FileInfo.class);
        when(meta.getName()).thenReturn("meta.json");

        FileInfo winPath = mock(FileInfo.class);
        when(winPath.getName()).thenReturn("src\\main\\java\\A.java");

        when(fileService.listFiles("mutations/cycle-1")).thenReturn(List.of(meta, pom, winPath));

        Set<String> paths = collector.getAllVariantPaths(CycleContextData.builder().cycleName("x").outputPath("o").build());

        assertThat(paths).contains("pom.xml");
        assertThat(paths).contains("src/main/java/A.java");
        assertThat(paths).doesNotContain("meta.json");
    }

    @Test
    void hasAnyVariant_returnsTrue_whenAtLeastOnePathHasVariants() throws Exception {
        FileVariantCollector spy = spy(collector);

        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-x")
                .outputPath("output")
                .build();

        doReturn(new LinkedHashSet<>(List.of("a.txt", "b.txt"))).when(spy).getAllVariantPaths(context);

        doReturn(List.of(mock(FileVariant.class))).when(spy).collect(eq("a.txt"), anyList(), any());
        // if it ever checks b, return empty
//        doReturn(List.of()).when(spy).collect(eq("b.txt"), anyList(), any());

        boolean any = spy.hasAnyVariant(context);

        assertThat(any).isTrue();
        verify(spy).collect(eq("a.txt"), anyList(), any());
        verify(spy, never()).collect(eq("b.txt"), anyList(), any());
    }

    @Test
    void hasAnyVariant_returnsFalse_whenNoPathsOrNoVariants() throws Exception {
        FileVariantCollector spy = spy(collector);

        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-x")
                .outputPath("output")
                .build();

        doReturn(Set.of()).when(spy).getAllVariantPaths(context);

        assertThat(spy.hasAnyVariant(context)).isFalse();

        doReturn(new LinkedHashSet<>(List.of("a.txt"))).when(spy).getAllVariantPaths(context);
        doReturn(List.of()).when(spy).collect(eq("a.txt"), anyList(), any());

        assertThat(spy.hasAnyVariant(context)).isFalse();
    }
}