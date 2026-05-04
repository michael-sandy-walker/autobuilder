package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Overrides evaluation to ACCEPTED (CRITICAL confidence) for BUILD_FIX subgoals when the
 * mutation touches a build-config file (pom.xml, build.gradle, settings.gradle).
 *
 * <p>The LLM evaluator cannot run the build, so it sees the stale {@code --- BUILD RESULT ---}
 * block from the previous cycle and concludes the build still fails — even though the proposed
 * mutation is the fix. This produces a false REJECTED verdict that blocks merge, which prevents
 * the post-merge {@link com.walpro.autobuilder.core.build.BuildExecutor} from verifying the fix.
 *
 * <p>This heuristic breaks the deadlock by accepting the mutation unconditionally and delegating
 * the real verdict to the post-merge build gate (T4-AN-1). If the build still fails after merge,
 * {@code MergeAgent.injectBuildFixSubgoalIfAbsent} re-injects a new BUILD_FIX subgoal and the
 * cycle continues. If it passes, the BUILD_FIX subgoal completes normally.
 *
 * <p>Preconditions (both must hold):
 * <ol>
 *   <li>Active subgoal has {@code metadata[blockerType]=BUILD_FIX}.</li>
 *   <li>The mutation touches at least one build-config file.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuildFixAcceptanceHeuristic implements EvaluationHeuristic {

    private static final String NAME = "BuildFixAcceptanceHeuristic";

    private static final Set<String> BUILD_CONFIG_FILES = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"
    );

    private final GoalPlannerService goalPlannerService;

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome original) {
        if (input == null) return HeuristicOpinion.none(NAME);

        String subGoalId = input.currentSubGoalId();
        if (!isActiveBuildFix(subGoalId)) return HeuristicOpinion.none(NAME);

        EvolutionOutcome evolution = input.evolutionOutcome();
        if (evolution == null || !touchesBuildConfig(evolution)) return HeuristicOpinion.none(NAME);

        log.info("{}: BUILD_FIX mutation touches build-config — overriding to ACCEPTED; " +
                "post-merge build gate (T4-AN-1) is the authoritative verdict", NAME);

        return HeuristicOpinion.of(
                NAME,
                1.0,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "BUILD_FIX mutation touching build-config file — LLM cannot evaluate pre-merge; " +
                        "deferring to post-merge BuildExecutor"
        );
    }

    private boolean isActiveBuildFix(String subGoalId) {
        if (subGoalId == null) return false;
        GoalEntity goal = goalPlannerService.getGoalById(subGoalId);
        if (goal == null || goal.getStatus() != GoalStatus.ACTIVE) return false;
        if (goal.getMetadata() == null) return false;
        return BlockerTypes.BUILD_FIX.equals(goal.getMetadata().get(BlockerTypes.METADATA_KEY));
    }

    private static boolean touchesBuildConfig(EvolutionOutcome evolution) {
        List<com.walpro.autobuilder.agent.evolution.model.FileMutation> files =
                evolution.addedOrChangedFiles();
        if (files == null) return false;
        return files.stream().anyMatch(f -> {
            if (f == null || f.path() == null) return false;
            String name = f.path().contains("/")
                    ? f.path().substring(f.path().lastIndexOf('/') + 1)
                    : f.path();
            return BUILD_CONFIG_FILES.contains(name);
        });
    }
}
