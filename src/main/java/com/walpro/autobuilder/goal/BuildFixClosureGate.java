package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.core.blocker.BlockerSubgoalInjector;
import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T1-EX: closes phantom {@code [BUILD_FIX]} subgoals whose referenced diagnostic is no longer
 * present. {@link BlockerSubgoalInjector} ships the creation side of the BUILD_FIX lifecycle;
 * without a corresponding closure mechanism, subgoals filed against transient compile errors
 * survive indefinitely once the diagnostic is gone, blocking promotion and forcing the planner
 * to absorb them via replan as the only escape path.
 *
 * <p>Two trigger paths:
 * <ul>
 *   <li>{@code compile_ok} — the latest probe cleared. Every active BUILD_FIX is obsolete.
 *       Subsumes the prior {@code BlockerSubgoalInjector.clearActiveBuildFixSubgoals} call site.</li>
 *   <li>{@code diagnostic_disappeared} — the build is still failing, but neither the subgoal's
 *       stamped {@code symbol} nor its stamped {@code file} appears in the current stderr. The
 *       error that produced this subgoal has been displaced.</li>
 * </ul>
 *
 * <p>Closed subgoals transition to {@link GoalStatus#SUBSUMED} with reason
 * {@link SubsumptionReason#COVERED}, mirroring the prior pre-T1-EX clearance semantics so the
 * T2-DR closure rule and abandoned-goal queries treat them as intentionally retired.
 * {@code transitionGoal} already advances {@code currentSubGoalId} via {@code findNextSiblingOrAncestor}
 * / {@code resolveNextActiveLeaf} when the closed subgoal was current, so the closure gate piggybacks
 * on the existing T1-EL stale-pointer plumbing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuildFixClosureGate {

    /** Extracts a leading file path from a description shaped like the {@code BlockerSubgoalInjector}
     *  rollback-streak templates: {@code "... on <path>: ..."} or {@code "... on <path> with ..."}. */
    private static final Pattern DESCRIPTION_PATH_PATTERN = Pattern.compile(
            "(?:on|in)\\s+([\\w./-]+\\.\\w+)");

    /** Matches the legacy "Fix compile error in PATH: SUMMARY" / divergence "create SYMBOL" shape. */
    private static final Pattern DESCRIPTION_SYMBOL_PATTERN = Pattern.compile(
            "(?:Either create|symbol:\\s*(?:class|variable|method))\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    private final GoalPlannerService goalPlannerService;

    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    /**
     * Walks every active BUILD_FIX subgoal and closes those whose referenced diagnostic is no
     * longer present in {@code currentBuildOutcome}. Safe to call with a {@code null} outcome —
     * treated as "no probe state available, no closure decisions today."
     *
     * @return ids of closed subgoals; empty when nothing was closed
     */
    public List<String> closeObsoleteBuildFixSubgoals(BuildOutcome currentBuildOutcome, String cycleId) {
        if (currentBuildOutcome == null) return List.of();

        List<GoalEntity> active = collectActiveBuildFixSubgoals();
        if (active.isEmpty()) return List.of();

        boolean compileOk = currentBuildOutcome.result() == BuildResult.COMPILE_OK;
        String stderr = currentBuildOutcome.stderrTail() == null ? "" : currentBuildOutcome.stderrTail();

        List<String> closedIds = new ArrayList<>();
        for (GoalEntity subgoal : active) {
            BuildFixReference ref = extractReference(subgoal);
            String trigger;
            if (compileOk) {
                trigger = "compile_ok";
            } else if (referenceMissing(ref, stderr)) {
                trigger = "diagnostic_disappeared";
            } else {
                continue;
            }
            closeOne(subgoal, ref, cycleId, trigger);
            closedIds.add(subgoal.getId());
        }
        return closedIds;
    }

    private void closeOne(GoalEntity subgoal, BuildFixReference ref, String cycleId, String trigger) {
        log.info("T1-EX: closing phantom BUILD_FIX subgoal [{}] symbol={} file={} trigger={}",
                subgoal.getId(), ref.symbol(), ref.file(), trigger);
        try {
            goalPlannerService.transitionGoal(
                    subgoal.getId(),
                    GoalStatus.SUBSUMED,
                    "build-fix obsolete: " + trigger,
                    SubsumptionReason.COVERED);
        } catch (RuntimeException e) {
            log.warn("T1-EX: transitionGoal SUBSUMED failed for [{}]: {}", subgoal.getId(), e.getMessage());
            return;
        }
        if (behaviourLogger != null) {
            try {
                behaviourLogger.buildFixSubgoalClosed(cycleId, subgoal.getId(), ref.symbol(), ref.file(), trigger);
            } catch (Exception e) {
                log.debug("T1-EX: buildFixSubgoalClosed emit failed: {}", e.getMessage());
            }
        }
    }

    private List<GoalEntity> collectActiveBuildFixSubgoals() {
        Set<GoalEntity> out = new LinkedHashSet<>();
        GoalEntity current = goalPlannerService.getCurrentSubGoal();
        if (isActiveBuildFix(current)) out.add(current);
        for (GoalEntity g : goalPlannerService.getRemainingGoals()) {
            if (isActiveBuildFix(g)) out.add(g);
        }
        return new ArrayList<>(out);
    }

    private static boolean isActiveBuildFix(GoalEntity g) {
        if (g == null || g.getMetadata() == null) return false;
        if (g.getStatus() != GoalStatus.ACTIVE) return false;
        return BlockerTypes.BUILD_FIX.equals(g.getMetadata().get(BlockerTypes.METADATA_KEY));
    }

    /**
     * "Reference missing from current diagnostics" is conservative: only return true when neither
     * the stamped {@code symbol} (if any) nor the stamped {@code file} (if any) appears as a token
     * in {@code stderr}. If both are unknown (no metadata, no description match) we cannot make
     * a sound decision — return false so the subgoal stays open and the next compile cycle gets
     * another shot. This matches the ticket's "we trust the merge" principle from T1-EW.
     */
    private static boolean referenceMissing(BuildFixReference ref, String stderr) {
        String symbol = ref.symbol();
        String file = ref.file();
        boolean haveSymbol = symbol != null && !symbol.isBlank();
        boolean haveFile = file != null && !file.isBlank();
        if (!haveSymbol && !haveFile) return false;
        if (haveSymbol && containsToken(stderr, symbol)) return false;
        if (haveFile && stderr.contains(file)) return false;
        // basename fallback for absolute / output-prefixed file paths in stderr
        if (haveFile) {
            String basename = file;
            int slash = file.lastIndexOf('/');
            if (slash >= 0) basename = file.substring(slash + 1);
            if (!basename.equals(file) && stderr.contains(basename)) return false;
        }
        return true;
    }

    private static boolean containsToken(String haystack, String token) {
        if (haystack == null || token == null || token.isEmpty()) return false;
        Pattern p = Pattern.compile("\\b" + Pattern.quote(token) + "\\b");
        return p.matcher(haystack).find();
    }

    static BuildFixReference extractReference(GoalEntity subgoal) {
        String symbol = null;
        String file = null;
        if (subgoal.getMetadata() != null) {
            Object s = subgoal.getMetadata().get(BlockerSubgoalInjector.METADATA_KEY_BUILD_FIX_SYMBOL);
            Object f = subgoal.getMetadata().get(BlockerSubgoalInjector.METADATA_KEY_BUILD_FIX_FILE);
            if (s instanceof String str && !str.isBlank()) symbol = str;
            if (f instanceof String str && !str.isBlank()) file = str;
        }
        // Description-regex fallback for legacy subgoals filed before T1-EX stamped metadata.
        if (symbol == null || file == null) {
            String desc = subgoal.getDescription();
            if (desc != null) {
                if (file == null) {
                    Matcher m = DESCRIPTION_PATH_PATTERN.matcher(desc);
                    if (m.find()) file = m.group(1);
                }
                if (symbol == null) {
                    Matcher m = DESCRIPTION_SYMBOL_PATTERN.matcher(desc);
                    if (m.find()) symbol = m.group(1);
                }
            }
        }
        return new BuildFixReference(symbol, file);
    }

    /** Tuple carrying the {@code (symbol, file)} a BUILD_FIX subgoal refers to. Either field may be
     *  null when neither the stamped metadata nor the description regex could recover it. */
    public record BuildFixReference(String symbol, String file) {}
}
