package com.walpro.autobuilder.core.service.merge;

import com.walpro.autobuilder.agent.merge.MergeLLMAdapter;
import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.agent.merge.model.MergeNode;
import com.walpro.autobuilder.agent.merge.model.MergeOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.merge.MergeGraphFacade;
import com.walpro.autobuilder.graph.merge.MergeGraphService;
import com.walpro.autobuilder.util.HashingUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MergeProcessorTest {

    @Mock
    MergeLLMAdapter mergeAdapter;

    @Mock
    MergeGraphFacade mergeGraphFacade;

    @Mock
    MergeGraphService mergeGraphService;

    MergeProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new MergeProcessor(mergeAdapter, mergeGraphFacade, mergeGraphService);
    }

    @Test
    void process_returnsSkipped_whenNoVariants() {
        CycleContextData ctx = CycleContextData.builder().cycleName("cycle-x").outputPath("o").build();

        MergeResult result = processor.process("pom.xml", "base", List.of(), false, ctx);

        assertThat(result.skipped()).isTrue();
        assertThat(result.wasSuccessful()).isFalse();

        verifyNoInteractions(mergeAdapter, mergeGraphFacade, mergeGraphService);
    }

    @Test
    void process_promotesSingleNewFile_whenOutputDoesNotExist_andRecordsGraph() {
        CycleContextData ctx = CycleContextData.builder().cycleName("cycle-x").outputPath("o").build();

        FileVariant v = new FileVariant("content-1", Set.of(), "cycle-1");

        MergeResult result = processor.process("a.txt", "", List.of(v), false, ctx);

        assertThat(result.adopted()).isTrue();
        assertThat(result.merged()).isFalse();
        assertThat(result.failed()).isFalse();
        assertThat(result.wasSuccessful()).isTrue();
        assertThat(result.currentContent()).isEqualTo("content-1");
        assertThat(result.contributingCycles()).containsExactly("cycle-1");

        ArgumentCaptor<MergeNode> nodeCaptor = ArgumentCaptor.forClass(MergeNode.class);
        verify(mergeGraphFacade).recordMerge(nodeCaptor.capture());
        verify(mergeGraphService).save(any(MergeNode.class));

        MergeNode node = nodeCaptor.getValue();
        assertThat(node.path()).isEqualTo("a.txt");
        assertThat(node.mergedInCycle()).isEqualTo("cycle-x");
        assertThat(node.contributingCycles()).containsExactly("cycle-1");
        assertThat(node.contentHash()).isEqualTo(HashingUtils.hashSha256("content-1"));

        verifyNoInteractions(mergeAdapter);
    }

    @Test
    void process_mergesViaLLM_whenMultipleVariants_andRecordsGraph_onSuccess() {
        CycleContextData ctx = CycleContextData.builder().cycleName("cycle-x").outputPath("o").build();

        FileVariant v1 = new FileVariant("c1", Set.of(), "cycle-1");
        FileVariant v2 = new FileVariant("c2", Set.of(), "cycle-2");
        FileVariant v3 = new FileVariant("c3", Set.of(), "cycle-2"); // duplicate cycle id on purpose

        MergeOutcome outcome = mock(MergeOutcome.class);
        when(outcome.successful()).thenReturn(true);
        when(outcome.mergedContent()).thenReturn("merged!");

        when(mergeAdapter.merge(eq("pom.xml"), eq("base"), eq(List.of(v1, v2, v3)), eq(ctx)))
                .thenReturn(outcome);

        MergeResult result = processor.process("pom.xml", "base", List.of(v1, v2, v3), true, ctx);

        assertThat(result.merged()).isTrue();
        assertThat(result.adopted()).isFalse();
        assertThat(result.wasSuccessful()).isTrue();
        assertThat(result.previousContent()).isEqualTo("base");
        assertThat(result.currentContent()).isEqualTo("merged!");
        assertThat(result.contributingCycles()).containsExactlyInAnyOrder("cycle-1", "cycle-2");

        ArgumentCaptor<MergeNode> nodeCaptor = ArgumentCaptor.forClass(MergeNode.class);
        verify(mergeGraphFacade).recordMerge(nodeCaptor.capture());
        verify(mergeGraphService).save(any(MergeNode.class));

        MergeNode node = nodeCaptor.getValue();
        assertThat(node.path()).isEqualTo("pom.xml");
        assertThat(node.mergedInCycle()).isEqualTo("cycle-x");
        assertThat(node.contributingCycles()).containsExactlyInAnyOrder("cycle-1", "cycle-2");
        assertThat(node.contentHash()).isEqualTo(HashingUtils.hashSha256("merged!"));
    }

    @Test
    void process_returnsFailed_whenLLMOutcomeNotSuccessful_andDoesNotRecordGraph() {
        CycleContextData ctx = CycleContextData.builder().cycleName("cycle-x").outputPath("o").build();

        FileVariant v1 = new FileVariant("c1", Set.of(), "cycle-1");
        FileVariant v2 = new FileVariant("c2", Set.of(), "cycle-2");

        MergeOutcome outcome = mock(MergeOutcome.class);
        when(outcome.successful()).thenReturn(false);
        when(outcome.reasoning()).thenReturn("nope");

        when(mergeAdapter.merge(eq("pom.xml"), eq("base"), eq(List.of(v1, v2)), eq(ctx)))
                .thenReturn(outcome);

        MergeResult result = processor.process("pom.xml", "base", List.of(v1, v2), true, ctx);

        assertThat(result.failed()).isTrue();
        assertThat(result.wasSuccessful()).isFalse();

        verifyNoInteractions(mergeGraphFacade, mergeGraphService);
    }
}