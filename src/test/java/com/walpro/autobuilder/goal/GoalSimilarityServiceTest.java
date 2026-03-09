package com.walpro.autobuilder.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.walpro.autobuilder.graph.goal.GoalGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GoalSimilarityServiceTest {

    private GoalGraphService goalGraphService;
    private GoalSimilarityService service;

    @BeforeEach
    void setUp() {
        goalGraphService = mock(GoalGraphService.class);
        service = new GoalSimilarityService(goalGraphService);
    }

    @Test
    void isDuplicate_returnsFalse_forNullOrBlank() {
        assertThat(service.isDuplicate(null)).isFalse();
        assertThat(service.isDuplicate("   ")).isFalse();

        verifyNoInteractions(goalGraphService);
    }

    @Test
    void isDuplicate_queriesGraphUsingKeywords() {
        // "Implement persistence layer now" → keywords: "implement", "persistence", "layer"
        String candidate = "Implement persistence layer now";
        when(goalGraphService.findDescriptionCandidates(anyString(), eq(50)))
                .thenReturn(List.of());

        boolean dup = service.isDuplicate(candidate);

        assertThat(dup).isFalse();

        ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
        verify(goalGraphService, atLeastOnce()).findDescriptionCandidates(q.capture(), eq(50));

        // All query terms must be lowercase
        q.getAllValues().forEach(kw -> assertThat(kw).isEqualTo(kw.toLowerCase()));
        // At least one keyword must be a meaningful word (longer than 4 chars)
        assertThat(q.getAllValues()).anyMatch(kw -> kw.length() > 4);
        // Keywords must come from the description content words
        assertThat(q.getAllValues()).anyMatch(kw ->
                Set.of("implement", "persistence", "layer").contains(kw));
    }

    @Test
    void extractKeywords_returnsUpToThreeContentWords() {
        List<String> kws = GoalSimilarityService.extractKeywords("Implement REST endpoint for user management");
        assertThat(kws).hasSize(3);
        assertThat(kws).containsExactly("implement", "endpoint", "management");
    }

    @Test
    void extractKeywords_fallsBackToPrefixWhenNoContentWords() {
        // All words are <= 4 chars: "Add new REST API"
        List<String> kws = GoalSimilarityService.extractKeywords("add new api");
        assertThat(kws).hasSize(1);
        assertThat(kws.get(0).length()).isLessThanOrEqualTo(12);
    }

    @Test
    void isDuplicate_returnsTrue_whenAnyCandidateExceedsThreshold() {
        // Make it extremely likely to exceed 0.88 by using identical strings after normalize
        String candidate = "Add unit tests for planner throttling";
        when(goalGraphService.findDescriptionCandidates(anyString(), eq(50)))
                .thenReturn(List.of("Add unit tests for planner throttling"));

        assertThat(service.isDuplicate(candidate)).isTrue();
    }

    @Test
    void isDuplicate_ignoresNullCandidatesFromGraph() {
        String candidate = "Improve merge determinism";
        when(goalGraphService.findDescriptionCandidates(anyString(), eq(50)))
                .thenReturn(Arrays.asList(null, "something else"));

        assertThat(service.isDuplicate(candidate)).isFalse();
    }

    @Test
    void similarity_returnsZero_whenEitherBlank() {
        assertThat(service.similarity(null, "x")).isZero();
        assertThat(service.similarity("x", null)).isZero();
        assertThat(service.similarity("   ", "x")).isZero();
        assertThat(service.similarity("x", "   ")).isZero();
    }

    @Test
    void similarity_isOne_forIdenticalAfterNormalization() {
        float s = service.similarity(" A   B  ", "A B");
        assertThat(s).isGreaterThan(0.99f);
    }
}