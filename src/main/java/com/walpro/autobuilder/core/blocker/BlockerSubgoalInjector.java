package com.walpro.autobuilder.core.blocker;

import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.build.BuildErrorClassifier;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * T5-K: deterministic blocker-subgoal lifecycle — inject, query, and clean up subgoals
 * that represent hard blockers (e.g. compile failures) requiring a dedicated fix cycle.
 * Extracted from {@code MergeAgent} so T1-DT can also call injection outside the merge phase.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BlockerSubgoalInjector {

    static final String METADATA_KEY_BUILD_ERROR_KIND = "buildErrorKind";

    private static final Pattern ANSI_ESCAPE = BuildErrorClassifier.ANSI_ESCAPE;

    private final GoalPlannerService goalPlannerService;

    /**
     * T1-DM: ensure exactly one active BUILD_FIX subgoal exists when the post-merge build fails.
     * Idempotent: no-op when an active BUILD_FIX subgoal is already present.
     */
    public void injectBuildFixSubgoalIfAbsent(BuildOutcome buildOutcome) {
        if (hasActiveBuildFixSubgoal()) {
            log.debug("T1-DM: build-fix subgoal already active — skipping injection");
            return;
        }

        String description = "Fix compile error: " + summariseStderr(buildOutcome.stderrTail());
        String newId = "goal-" + UUID.randomUUID();
        try {
            goalPlannerService.addSubGoal(newId, description, true);
        } catch (RuntimeException e) {
            log.warn("T1-DM: failed to inject BUILD_FIX subgoal: {}", e.getMessage());
            return;
        }

        Classification classification = BuildErrorClassifier.classify(buildOutcome.stderrTail());
        GoalEntity injected = goalPlannerService.getGoalById(newId);
        if (injected != null) {
            injected.getMetadata().put(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX);
            injected.getMetadata().put(METADATA_KEY_BUILD_ERROR_KIND, classification.kind().name());
            goalPlannerService.saveGoalMetadata(injected);
        }
        goalPlannerService.setAcceptanceCriteria(newId, classification.criteria());

        log.info("T1-DM: injected BUILD_FIX subgoal [{}] as current — '{}'", newId, description);
    }

    public boolean hasActiveBuildFixSubgoal() {
        GoalEntity current = goalPlannerService.getCurrentSubGoal();
        if (isActiveBuildFix(current)) return true;
        for (GoalEntity g : goalPlannerService.getRemainingGoals()) {
            if (isActiveBuildFix(g)) return true;
        }
        return false;
    }

    /**
     * T1-DR: mark all active BUILD_FIX subgoals as GIVEN_UP when the build returns COMPILE_OK.
     */
    public void clearActiveBuildFixSubgoals() {
        GoalEntity current = goalPlannerService.getCurrentSubGoal();
        if (isActiveBuildFix(current)) {
            log.info("T1-DR: build now COMPILE_OK — marking current BUILD_FIX subgoal [{}] GIVEN_UP", current.getId());
            goalPlannerService.markGoalGivenUp(current.getId(), "Build fixed — BUILD_FIX subgoal no longer needed");
        }
        for (GoalEntity g : goalPlannerService.getRemainingGoals()) {
            if (isActiveBuildFix(g)) {
                log.info("T1-DR: build now COMPILE_OK — marking remaining BUILD_FIX subgoal [{}] GIVEN_UP", g.getId());
                goalPlannerService.markGoalGivenUp(g.getId(), "Build fixed — BUILD_FIX subgoal no longer needed");
            }
        }
    }

    private static boolean isActiveBuildFix(GoalEntity g) {
        if (g == null || g.getMetadata() == null) return false;
        if (g.getStatus() != GoalStatus.ACTIVE) return false;
        return BlockerTypes.BUILD_FIX.equals(g.getMetadata().get(BlockerTypes.METADATA_KEY));
    }

    /** T1-DX: also called by {@code MergeAgent} when capturing rollback evidence. */
    public static String summariseStderr(String stderrTail) {
        if (stderrTail == null || stderrTail.isBlank()) return "(no stderr captured)";
        String summary = stderrTail.lines()
                .map(line -> ANSI_ESCAPE.matcher(line).replaceAll("").strip())
                .filter(line -> line.startsWith("[ERROR]"))
                .map(line -> line.substring("[ERROR]".length()).strip())
                .filter(stripped -> !stripped.isEmpty() && !stripped.startsWith("-> [Help"))
                .findFirst()
                .orElseGet(() -> stderrTail.lines()
                        .map(line -> ANSI_ESCAPE.matcher(line).replaceAll("").strip())
                        .filter(s -> !s.isEmpty())
                        .findFirst()
                        .orElse(stderrTail.strip()));
        return summary.length() > 200 ? summary.substring(0, 200) + "…" : summary;
    }
}
