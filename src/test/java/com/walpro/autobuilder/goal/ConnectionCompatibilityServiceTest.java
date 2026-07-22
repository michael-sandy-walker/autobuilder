package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.goal.ConnectionCompatibilityService.ConnectionCheckResult;
import com.walpro.autobuilder.goal.ConnectionCompatibilityService.ConnectionKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * T4-CN slice 1: one test per row of the connection-kind table (goal↔goal merge).
 * The check must answer with the same rules the real fold enforces — refusals are
 * feedback payloads carrying the owning rule, allowed answers carry a dry-run of
 * the fold's actual effect.
 */
@ExtendWith(MockitoExtension.class)
class ConnectionCompatibilityServiceTest {

    @Mock private GoalPlannerService goalPlannerService;
    @Mock private GoalAdoptionService goalAdoptionService;
    @Mock private GoalSimilarityService goalSimilarityService;

    private ConnectionCompatibilityService service;

    @BeforeEach
    void setUp() {
        GoalConsolidationService consolidation =
                new GoalConsolidationService(goalPlannerService, goalAdoptionService, goalSimilarityService);
        consolidation.setSimilarityFloor(0.5);
        service = new ConnectionCompatibilityService(goalPlannerService, consolidation, goalSimilarityService);
    }

    private GoalEntity subgoal(String id, String description, GoalStatus status) {
        GoalEntity g = new GoalEntity(id, description, "role", GoalType.SUBGOAL, "root");
        g.setStatus(status);
        lenient().when(goalPlannerService.getGoalById(id)).thenReturn(g);
        return g;
    }

    private void similarity(float score) {
        lenient().when(goalSimilarityService.similarity(anyString(), anyString())).thenReturn(score);
    }

    // ==================================================================================
    // Refusal rows
    // ==================================================================================

    @Test
    void selfMergeRefused() {
        ConnectionCheckResult result = service.checkConnection("a", "a");
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("itself");
    }

    @Test
    void unknownNodeAnswersAsCrossLineageGated() {
        subgoal("a", "known goal", GoalStatus.ACTIVE);
        ConnectionCheckResult result = service.checkConnection("a", "ghost");
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("ghost").contains("T4-CJ slice 2");
    }

    @Test
    void rootGoalNeverFolds() {
        subgoal("a", "some work", GoalStatus.ACTIVE);
        GoalEntity root = new GoalEntity("root", "the goal", "role", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        when(goalPlannerService.getGoalById("root")).thenReturn(root);
        ConnectionCheckResult result = service.checkConnection("a", "root");
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("root");
    }

    @Test
    void undescribedGoalRefused() {
        subgoal("a", "described", GoalStatus.ACTIVE);
        subgoal("b", null, GoalStatus.ACTIVE);
        ConnectionCheckResult result = service.checkConnection("a", "b");
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("description-based");
    }

    @Test
    void buildFixRefused() {
        subgoal("a", "fix the build", GoalStatus.ACTIVE)
                .getMetadata().put(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX);
        subgoal("b", "fix compilation", GoalStatus.ACTIVE);
        ConnectionCheckResult result = service.checkConnection("a", "b");
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("BUILD_FIX");
    }

    @Test
    void subsumedGoalRefused() {
        subgoal("a", "already folded work", GoalStatus.SUBSUMED);
        subgoal("b", "active work", GoalStatus.ACTIVE);
        ConnectionCheckResult result = service.checkConnection("a", "b");
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("at most one fold");
    }

    @Test
    void ancestorDescendantRefused() {
        GoalEntity parent = subgoal("parent", "build the feature", GoalStatus.ACTIVE);
        GoalEntity child = subgoal("child", "build the feature endpoint", GoalStatus.ACTIVE);
        child.setParentId("parent");
        ConnectionCheckResult result = service.checkConnection("parent", "child");
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("decomposition");
    }

    @Test
    void completedPairRefused() {
        subgoal("a", "done work", GoalStatus.COMPLETED);
        subgoal("b", "done work too", GoalStatus.COMPLETED);
        ConnectionCheckResult result = service.checkConnection("a", "b");
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("COMPLETED");
    }

    @Test
    void activeCompletedPairRefusedNamingTheGap() {
        subgoal("a", "list all books", GoalStatus.ACTIVE);
        subgoal("b", "list books endpoint", GoalStatus.COMPLETED);
        ConnectionCheckResult result = service.checkConnection("a", "b");
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("no fold case");
    }

    @Test
    void givenUpPairRefused() {
        subgoal("a", "abandoned work", GoalStatus.GIVEN_UP);
        subgoal("b", "abandoned work too", GoalStatus.GIVEN_UP);
        ConnectionCheckResult result = service.checkConnection("a", "b");
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("keep the work");
    }

    @Test
    void belowSimilarityFloorRefusedWithScore() {
        subgoal("a", "implement loans", GoalStatus.ACTIVE);
        subgoal("b", "write documentation", GoalStatus.ACTIVE);
        similarity(0.2f);
        ConnectionCheckResult result = service.checkConnection("a", "b");
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("0.20").contains("0.50");
        assertThat(result.similarity()).isEqualTo((double) 0.2f);
    }

    // ==================================================================================
    // Allowed rows + dry-run consequences
    // ==================================================================================

    @Test
    void activeDuplicatesAllowedWithCarryDryRun() {
        GoalEntity a = subgoal("a", "create the loan endpoint", GoalStatus.ACTIVE);
        GoalEntity b = subgoal("b", "create loan endpoint", GoalStatus.ACTIVE);
        a.getMetadata().put("acceptanceCriteria", List.of("POST /loans returns 201", "loan persisted"));
        b.getMetadata().put("acceptanceCriteria", List.of("POST /loans returns 201", "409 when book on loan"));
        a.getMetadata().put("mergeCount", 3);
        b.getMetadata().put("mergeCount", 1);
        when(goalPlannerService.getGoalMetadataInt(a, "mergeCount")).thenReturn(3);
        when(goalPlannerService.getGoalMetadataInt(b, "mergeCount")).thenReturn(1);
        when(goalPlannerService.countUnmetCriteria("b")).thenReturn(1);
        similarity(0.93f);

        ConnectionCheckResult result = service.checkConnection("a", "b");

        assertThat(result.kind()).isEqualTo(ConnectionKind.GOAL_MERGE);
        assertThat(result.allowed()).isTrue();
        assertThat(result.reason()).contains("active-duplicate").contains("duplicate-grade");
        // a has more merged work behind it, so a is kept and b absorbed
        assertThat(result.consequences().keptGoalId()).isEqualTo("a");
        assertThat(result.consequences().absorbedGoalId()).isEqualTo("b");
        assertThat(result.consequences().absorbedStatusAfter()).isEqualTo("SUBSUMED");
        // one of b's two criteria is new to a, the other is already covered
        assertThat(result.consequences().criteriaCarried()).isEqualTo(1);
        assertThat(result.consequences().criteriaAlreadyCovered()).isEqualTo(1);
        assertThat(result.consequences().mergeCountAfter()).isEqualTo(4);
        assertThat(result.consequences().unmetCriteriaOnAbsorbed()).isEqualTo(1);
    }

    @Test
    void currentSubgoalIsNeverAbsorbed() {
        GoalEntity a = subgoal("a", "create the loan endpoint", GoalStatus.ACTIVE);
        GoalEntity b = subgoal("b", "create loan endpoint", GoalStatus.ACTIVE);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(b);
        similarity(0.93f);

        ConnectionCheckResult result = service.checkConnection("a", "b");

        assertThat(result.allowed()).isTrue();
        assertThat(result.consequences().keptGoalId()).isEqualTo("b");
        assertThat(result.consequences().absorbedGoalId()).isEqualTo("a");
    }

    @Test
    void reviewerGradeSimilarityAllowedButNamesTheReview() {
        subgoal("a", "add loan validation", GoalStatus.ACTIVE);
        subgoal("b", "validate loan requests", GoalStatus.ACTIVE);
        similarity(0.6f);

        ConnectionCheckResult result = service.checkConnection("a", "b");

        assertThat(result.allowed()).isTrue();
        assertThat(result.reason()).contains("reviewer-grade").contains("equivalence review");
    }

    @Test
    void givenUpCoveredByActiveCarriesCriteria() {
        GoalEntity givenUp = subgoal("g", "expose loan history", GoalStatus.GIVEN_UP);
        subgoal("k", "expose the loan history endpoint", GoalStatus.ACTIVE);
        givenUp.getMetadata().put("acceptanceCriteria", List.of("GET /loans/history returns 200"));
        when(goalPlannerService.countUnmetCriteria("g")).thenReturn(1);
        similarity(0.9f);

        ConnectionCheckResult result = service.checkConnection("g", "k");

        assertThat(result.allowed()).isTrue();
        assertThat(result.consequences().foldCase()).isEqualTo("given-up-covered");
        assertThat(result.consequences().keptGoalId()).isEqualTo("k");
        assertThat(result.consequences().criteriaCarried()).isEqualTo(1);
        assertThat(result.consequences().unmetCriteriaOnAbsorbed()).isEqualTo(1);
    }

    @Test
    void givenUpCoveredByCompletedCarriesNothing() {
        GoalEntity givenUp = subgoal("g", "expose loan history", GoalStatus.GIVEN_UP);
        subgoal("k", "expose the loan history endpoint", GoalStatus.COMPLETED);
        givenUp.getMetadata().put("acceptanceCriteria", List.of("GET /loans/history returns 200"));
        when(goalPlannerService.countUnmetCriteria("g")).thenReturn(0);
        similarity(0.9f);

        ConnectionCheckResult result = service.checkConnection("g", "k");

        assertThat(result.allowed()).isTrue();
        assertThat(result.consequences().foldCase()).isEqualTo("given-up-covered");
        assertThat(result.consequences().keptGoalId()).isEqualTo("k");
        assertThat(result.consequences().criteriaCarried()).isZero();
        // 0 unmet criteria: this fold would not unblock root closure, and the answer says so
        assertThat(result.consequences().unmetCriteriaOnAbsorbed()).isZero();
    }
}
