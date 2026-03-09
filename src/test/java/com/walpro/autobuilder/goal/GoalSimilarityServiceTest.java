package com.walpro.autobuilder.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.List;

import com.walpro.autobuilder.graph.goal.GoalGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    void isDuplicate_callsFindAllDescriptions_once() {
        when(goalGraphService.findAllDescriptions(anyInt())).thenReturn(List.of());

        service.isDuplicate("Implement persistence layer now");

        verify(goalGraphService, times(1)).findAllDescriptions(anyInt());
    }

    @Test
    void isDuplicate_returnsTrue_whenAnyCandidateExceedsThreshold() {
        String candidate = "Add unit tests for planner throttling";
        when(goalGraphService.findAllDescriptions(anyInt()))
                .thenReturn(List.of("Add unit tests for planner throttling"));

        assertThat(service.isDuplicate(candidate)).isTrue();
    }

    @Test
    void isDuplicate_ignoresNullCandidatesFromGraph() {
        String candidate = "Improve merge determinism";
        when(goalGraphService.findAllDescriptions(anyInt()))
                .thenReturn(Arrays.asList(null, "something else"));

        assertThat(service.isDuplicate(candidate)).isFalse();
    }

    @Test
    void isDuplicate_matchesGivenUpGoals() {
        // GIVEN_UP goals must be included so re-proposals of failed goals are blocked
        String candidate = "Refactor HelloController to add REST endpoint";
        when(goalGraphService.findAllDescriptions(anyInt()))
                .thenReturn(List.of("Refactor HelloController to add REST endpoint"));

        assertThat(service.isDuplicate(candidate)).isTrue();
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
