package com.walpro.autobuilder.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.walpro.autobuilder.graph.goal.GoalGraphService;

class AbandonedGoalQueryServiceTest {

    private GoalGraphService graphService;
    private GoalSimilarityService similarityService;
    private AbandonedGoalQueryService service;

    @BeforeEach
    void setUp() {
        graphService = mock(GoalGraphService.class);
        similarityService = mock(GoalSimilarityService.class);
        service = new AbandonedGoalQueryService(graphService, similarityService);
    }

    @Test
    void returnsEmptyWhenCandidateNullOrBlank() {
        assertThat(service.findRelatedAbandonedGoals(null)).isEmpty();
        assertThat(service.findRelatedAbandonedGoals("   ")).isEmpty();
    }

    @Test
    void surfacesGivenUpClusterWithLiveIslandAndMergedFiles() {
        GoalEntity root = givenUp("g-root", null, "Implement loans endpoints", "after 5 stagnating cycles");
        root.setGivenUp(Instant.parse("2026-05-19T23:56:00Z"));

        GoalEntity active = active("g-active", "g-root", "POST /loans");
        GoalEntity completedMerge = goal("g-merged", "g-root", "Create MemberService", GoalStatus.GIVEN_UP);
        completedMerge.getMetadata().put("mergeCount", 2);
        completedMerge.getMetadata().put("mergedFiles", new ArrayList<>(List.of("MemberService.java")));

        when(graphService.findAll()).thenReturn(List.of(root, active, completedMerge));
        when(similarityService.similarity("Loans", root.getDescription())).thenReturn(0.85f);

        List<AbandonedGoalCluster> result = service.findRelatedAbandonedGoals("Loans", 0.50f, 5);

        assertThat(result).hasSize(1);
        AbandonedGoalCluster cluster = result.get(0);
        assertThat(cluster.root().getId()).isEqualTo("g-root");
        assertThat(cluster.givenUpAt()).isEqualTo(Instant.parse("2026-05-19T23:56:00Z"));
        assertThat(cluster.resolution()).contains("stagnating");
        assertThat(cluster.activeDescendants()).extracting(GoalEntity::getId).containsExactly("g-active");
        assertThat(cluster.mergeBearingDescendants())
                .extracting(GoalEntity::getId).containsExactly("g-merged");
        assertThat(cluster.mergedFiles()).containsExactly("MemberService.java");
    }

    @Test
    void skipsGivenUpDescendantsRolledIntoParentCluster() {
        GoalEntity parent = givenUp("g-parent", null, "Loans subtree", null);
        GoalEntity childGivenUp = givenUp("g-child", "g-parent", "POST /loans", null);

        when(graphService.findAll()).thenReturn(List.of(parent, childGivenUp));
        when(similarityService.similarity("Loans", parent.getDescription())).thenReturn(0.90f);
        when(similarityService.similarity("Loans", childGivenUp.getDescription())).thenReturn(0.85f);

        List<AbandonedGoalCluster> result = service.findRelatedAbandonedGoals("Loans", 0.50f, 5);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).root().getId()).isEqualTo("g-parent");
    }

    @Test
    void filtersByThreshold() {
        GoalEntity root = givenUp("g-root", null, "Unrelated work", null);

        when(graphService.findAll()).thenReturn(List.of(root));
        when(similarityService.similarity("Loans", root.getDescription())).thenReturn(0.20f);

        assertThat(service.findRelatedAbandonedGoals("Loans", 0.50f, 5)).isEmpty();
    }

    @Test
    void capsAtMaxClustersOrderedBySimilarity() {
        GoalEntity high = givenUp("g-1", null, "high", null);
        GoalEntity mid = givenUp("g-2", null, "mid", null);
        GoalEntity low = givenUp("g-3", null, "low", null);

        when(graphService.findAll()).thenReturn(List.of(low, mid, high));
        when(similarityService.similarity("probe", "high")).thenReturn(0.95f);
        when(similarityService.similarity("probe", "mid")).thenReturn(0.75f);
        when(similarityService.similarity("probe", "low")).thenReturn(0.55f);

        List<AbandonedGoalCluster> result = service.findRelatedAbandonedGoals("probe", 0.50f, 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).root().getId()).isEqualTo("g-1");
        assertThat(result.get(1).root().getId()).isEqualTo("g-2");
    }

    @Test
    void surfacesCriteriaPassStatus() {
        GoalEntity root = givenUp("g-root", null, "Implement loans", null);
        Map<Integer, Boolean> status = new HashMap<>();
        status.put(1, true);
        status.put(2, false);
        status.put(3, true);
        root.getMetadata().put("criteriaPassStatus", status);

        when(graphService.findAll()).thenReturn(List.of(root));
        when(similarityService.similarity("Loans", root.getDescription())).thenReturn(0.85f);

        List<AbandonedGoalCluster> result = service.findRelatedAbandonedGoals("Loans", 0.50f, 5);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).criteriaPassStatus())
                .containsEntry(1, true)
                .containsEntry(2, false)
                .containsEntry(3, true);
    }

    private static GoalEntity goal(String id, String parentId, String description, GoalStatus status) {
        GoalEntity g = new GoalEntity(id, description, "role", GoalType.SUBGOAL, parentId);
        g.setStatus(status);
        g.setMetadata(new HashMap<>());
        return g;
    }

    private static GoalEntity active(String id, String parentId, String description) {
        return goal(id, parentId, description, GoalStatus.ACTIVE);
    }

    private static GoalEntity givenUp(String id, String parentId, String description, String resolution) {
        GoalEntity g = goal(id, parentId, description, GoalStatus.GIVEN_UP);
        if (resolution != null) g.setResolution(resolution);
        g.setGivenUp(Instant.now());
        return g;
    }
}
