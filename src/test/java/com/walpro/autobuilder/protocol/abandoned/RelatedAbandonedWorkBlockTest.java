package com.walpro.autobuilder.protocol.abandoned;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.GoalType;
import com.walpro.autobuilder.goal.AbandonedGoalCluster;

class RelatedAbandonedWorkBlockTest {

    @Test
    void rendersNullWhenNoClusters() {
        CycleContextData ctx = CycleContextData.builder().relatedAbandonedGoals(List.of()).build();
        assertThat(RelatedAbandonedWorkBlock.render(ctx, RelatedAbandonedWorkBlock.Audience.PLANNER)).isNull();
        assertThat(RelatedAbandonedWorkBlock.render(ctx, RelatedAbandonedWorkBlock.Audience.ANALYSIS)).isNull();
    }

    @Test
    void rendersClusterHeaderWithMergedFilesAndLiveIslands() {
        GoalEntity root = givenUp("g-root", "Implement loans endpoints", "after stagnating cycles");
        GoalEntity active = active("g-active", "POST /loans");
        GoalEntity merged = givenUp("g-merged", "Create MemberService", null);
        merged.getMetadata().put("mergeCount", 1);

        AbandonedGoalCluster cluster = new AbandonedGoalCluster(
                root,
                Instant.parse("2026-05-19T23:56:00Z"),
                root.getResolution(),
                List.of(active),
                List.of(merged),
                List.of("MemberService.java"),
                Map.of(1, true, 2, false)
        );

        CycleContextData ctx = CycleContextData.builder().relatedAbandonedGoals(List.of(cluster)).build();

        String rendered = RelatedAbandonedWorkBlock.render(ctx, RelatedAbandonedWorkBlock.Audience.PLANNER);

        assertThat(rendered).isNotNull();
        assertThat(rendered).contains("--- RELATED ABANDONED WORK ---");
        assertThat(rendered).contains("Implement loans endpoints");
        assertThat(rendered).contains("Live islands");
        assertThat(rendered).contains("g-active");
        assertThat(rendered).contains("Merge-bearing descendants");
        assertThat(rendered).contains("MemberService.java");
        assertThat(rendered).contains("Criteria progress before give-up: 1 / 2 passed");
    }

    @Test
    void planAudienceMentionsReactivationOrRemoval() {
        GoalEntity root = givenUp("g-root", "abandoned thing", null);
        AbandonedGoalCluster cluster = new AbandonedGoalCluster(root, null, null, List.of(), List.of(), List.of(), Map.of());
        CycleContextData ctx = CycleContextData.builder().relatedAbandonedGoals(List.of(cluster)).build();

        String rendered = RelatedAbandonedWorkBlock.render(ctx, RelatedAbandonedWorkBlock.Audience.PLANNER);
        assertThat(rendered).containsAnyOf("reactivated", "removed", "extending");
    }

    @Test
    void analysisAudienceMentionsDiagnosticFraming() {
        GoalEntity root = givenUp("g-root", "abandoned thing", null);
        AbandonedGoalCluster cluster = new AbandonedGoalCluster(root, null, null, List.of(), List.of(), List.of(), Map.of());
        CycleContextData ctx = CycleContextData.builder().relatedAbandonedGoals(List.of(cluster)).build();

        String rendered = RelatedAbandonedWorkBlock.render(ctx, RelatedAbandonedWorkBlock.Audience.ANALYSIS);
        assertThat(rendered).containsAnyOf("diagnose", "continuation", "Findings", "overlap");
    }

    private static GoalEntity active(String id, String description) {
        GoalEntity g = new GoalEntity(id, description, "role", GoalType.SUBGOAL, "g-root");
        g.setStatus(GoalStatus.ACTIVE);
        g.setMetadata(new HashMap<>());
        return g;
    }

    private static GoalEntity givenUp(String id, String description, String resolution) {
        GoalEntity g = new GoalEntity(id, description, "role", GoalType.SUBGOAL, null);
        g.setStatus(GoalStatus.GIVEN_UP);
        if (resolution != null) g.setResolution(resolution);
        g.setMetadata(new HashMap<>());
        return g;
    }
}
