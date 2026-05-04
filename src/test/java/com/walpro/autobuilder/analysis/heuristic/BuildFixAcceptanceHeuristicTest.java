package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BuildFixAcceptanceHeuristicTest {

    @Mock
    private GoalPlannerService goalPlannerService;

    @InjectMocks
    private BuildFixAcceptanceHeuristic heuristic;

    private static EvaluationOutcome rejected() {
        return new EvaluationOutcome(0.25, MutationAcceptanceState.REJECTED,
                GoalProgressState.STAGNATING, "build still fails", null);
    }

    private static GoalEntity activeBuildFixGoal() {
        GoalEntity goal = new GoalEntity();
        goal.setStatus(GoalStatus.ACTIVE);
        Map<String, Object> meta = new HashMap<>();
        meta.put(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX);
        goal.setMetadata(meta);
        return goal;
    }

    private static CycleContextData ctxWith(String subGoalId, EvolutionOutcome evolution) {
        return CycleContextData.builder()
                .cycleName("cycle-test")
                .currentSubGoalId(subGoalId)
                .build()
                .withEvolutionOutcome(evolution);
    }

    // ── happy path ─────────────────────────────────────────────────────────────

    @Test
    void evaluate_buildFixGoalWithPomXml_overridesToAccepted_criticalConfidence() {
        when(goalPlannerService.getGoalById("bf-1")).thenReturn(activeBuildFixGoal());

        EvolutionOutcome evolution = new EvolutionOutcome(
                List.of(new FileMutation("pom.xml", "<project>...</project>")),
                List.of(), "add pom.xml", "#pom");

        HeuristicOpinion op = heuristic.evaluate(ctxWith("bf-1", evolution), rejected());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(op.level()).isEqualTo(ConfidenceLevel.CRITICAL);
        assertThat(op.confidence()).isEqualTo(1.0);
        assertThat(op.goalProgress()).isEqualTo(GoalProgressState.PROGRESSING);
    }

    @Test
    void evaluate_pomXmlInSubdirectory_isRecognisedAsBuildConfig() {
        when(goalPlannerService.getGoalById("bf-2")).thenReturn(activeBuildFixGoal());

        EvolutionOutcome evolution = new EvolutionOutcome(
                List.of(new FileMutation("output/pom.xml", "<project/>"),
                        new FileMutation("src/main/java/Foo.java", "class Foo {}")),
                List.of(), "fix pom", "#raw");

        HeuristicOpinion op = heuristic.evaluate(ctxWith("bf-2", evolution), rejected());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
    }

    @Test
    void evaluate_gradleFile_isRecognisedAsBuildConfig() {
        when(goalPlannerService.getGoalById("bf-3")).thenReturn(activeBuildFixGoal());

        EvolutionOutcome evolution = new EvolutionOutcome(
                List.of(new FileMutation("build.gradle", "plugins { id 'java' }")),
                List.of(), "add gradle", "#raw");

        HeuristicOpinion op = heuristic.evaluate(ctxWith("bf-3", evolution), rejected());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
    }

    // ── guard: non-BUILD_FIX subgoal ────────────────────────────────────────────

    @Test
    void evaluate_regularSubgoal_abstains() {
        GoalEntity regular = new GoalEntity();
        regular.setStatus(GoalStatus.ACTIVE);
        regular.setMetadata(Map.of());
        when(goalPlannerService.getGoalById("sg-1")).thenReturn(regular);

        EvolutionOutcome evolution = new EvolutionOutcome(
                List.of(new FileMutation("pom.xml", "<project/>")),
                List.of(), "add pom", "#raw");

        HeuristicOpinion op = heuristic.evaluate(ctxWith("sg-1", evolution), rejected());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.NOT_EVALUATED);
        assertThat(op.confidence()).isZero();
    }

    // ── guard: BUILD_FIX but no build-config file ───────────────────────────────

    @Test
    void evaluate_buildFixSubgoalJavaOnlyMutation_abstains() {
        when(goalPlannerService.getGoalById("bf-4")).thenReturn(activeBuildFixGoal());

        EvolutionOutcome evolution = new EvolutionOutcome(
                List.of(new FileMutation("src/main/java/Fix.java", "class Fix {}")),
                List.of(), "java only", "#raw");

        HeuristicOpinion op = heuristic.evaluate(ctxWith("bf-4", evolution), rejected());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.NOT_EVALUATED);
    }

    // ── guard: inactive / completed BUILD_FIX subgoal ──────────────────────────

    @Test
    void evaluate_completedBuildFixSubgoal_abstains() {
        GoalEntity completed = new GoalEntity();
        completed.setStatus(GoalStatus.COMPLETED);
        Map<String, Object> meta = new HashMap<>();
        meta.put(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX);
        completed.setMetadata(meta);
        when(goalPlannerService.getGoalById("bf-5")).thenReturn(completed);

        EvolutionOutcome evolution = new EvolutionOutcome(
                List.of(new FileMutation("pom.xml", "<project/>")),
                List.of(), "pom", "#raw");

        HeuristicOpinion op = heuristic.evaluate(ctxWith("bf-5", evolution), rejected());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.NOT_EVALUATED);
    }

    // ── guard: null / empty evolution ──────────────────────────────────────────

    @Test
    void evaluate_nullEvolutionOutcome_abstains() {
        when(goalPlannerService.getGoalById("bf-6")).thenReturn(activeBuildFixGoal());

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c").currentSubGoalId("bf-6").build();

        HeuristicOpinion op = heuristic.evaluate(ctx, rejected());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.NOT_EVALUATED);
    }

    @Test
    void evaluate_nullInput_abstains() {
        HeuristicOpinion op = heuristic.evaluate(null, rejected());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.NOT_EVALUATED);
    }

    @Test
    void evaluate_nullSubGoalId_abstains() {
        CycleContextData ctx = CycleContextData.builder().cycleName("c").build();

        HeuristicOpinion op = heuristic.evaluate(ctx, rejected());

        assertThat(op.mutationState()).isEqualTo(MutationAcceptanceState.NOT_EVALUATED);
    }
}
