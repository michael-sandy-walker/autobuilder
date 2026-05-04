package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Overrides evaluation to ACCEPTED (CRITICAL confidence) when the mutation touches a
 * build-config file (pom.xml, build.gradle, settings.gradle) AND the current build state
 * shows COMPILE_ERROR.
 *
 * <p>The LLM evaluator cannot run the build, so it sees the stale {@code --- BUILD RESULT ---}
 * block from the previous cycle and concludes the build still fails — even though the proposed
 * mutation is the fix. This produces a false REJECTED result that blocks merge, which prevents
 * the post-merge {@link com.walpro.autobuilder.core.build.BuildExecutor} from verifying the fix.
 *
 * <p>Gating on {@code buildOutcome.result() == COMPILE_ERROR} (rather than BUILD_FIX subgoal
 * metadata) makes this heuristic resilient to subgoal restructuring by the planner: it fires
 * whenever the build is concretely failing and the mutation targets the likely cause, regardless
 * of how the goal tree is currently shaped.
 *
 * <p>Preconditions (both must hold):
 * <ol>
 *   <li>The most recent carried {@link BuildOutcome} has result {@link BuildResult#COMPILE_ERROR}.</li>
 *   <li>The mutation touches at least one build-config file.</li>
 * </ol>
 *
 * <p>If the pom.xml fix doesn't resolve the error, the post-merge build gate ({@code BuildResultHeuristic})
 * will catch it next cycle and inject a new BUILD_FIX subgoal.
 */
@Slf4j
@Component
public class BuildFixAcceptanceHeuristic implements EvaluationHeuristic {

    private static final String NAME = "BuildFixAcceptanceHeuristic";

    private static final Set<String> BUILD_CONFIG_FILES = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"
    );

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome original) {
        if (input == null) return HeuristicOpinion.none(NAME);

        BuildOutcome build = input.buildOutcome();
        if (build == null || build.result() != BuildResult.COMPILE_ERROR) return HeuristicOpinion.none(NAME);

        EvolutionOutcome evolution = input.evolutionOutcome();
        if (evolution == null || !touchesBuildConfig(evolution)) return HeuristicOpinion.none(NAME);

        log.info("{}: build COMPILE_ERROR + build-config mutation — overriding to ACCEPTED; " +
                "post-merge build gate (T4-AN-1) is the authoritative result", NAME);

        return HeuristicOpinion.of(
                NAME,
                1.0,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "Build-config mutation while COMPILE_ERROR is active — LLM cannot evaluate pre-merge; " +
                        "deferring to post-merge BuildExecutor"
        );
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
