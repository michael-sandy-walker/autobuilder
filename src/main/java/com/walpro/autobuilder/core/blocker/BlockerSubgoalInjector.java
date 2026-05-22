package com.walpro.autobuilder.core.blocker;

import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.build.BuildErrorClassifier;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildErrorClassifierAgent;
import com.walpro.autobuilder.core.build.BuildFixTargets;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildStderrLines;
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

    private final GoalPlannerService goalPlannerService;
    private final BuildErrorClassifierAgent buildErrorClassifierAgent;

    /**
     * T1-DM: ensure exactly one active BUILD_FIX subgoal exists when the post-merge build fails.
     * Idempotent: no-op when an active BUILD_FIX subgoal is already present.
     * <p>
     * T2-CY: {@code contextPaths} carries the merged file list (or, for synthetic outcomes
     * from rollback evidence, the rolled-back paths). Used to name the failed file in the
     * subgoal description and acceptance criteria when stderr lacks a file-coordinate prefix
     * (e.g. MISSING_POM has no per-file diagnostic).
     */
    public void injectBuildFixSubgoalIfAbsent(BuildOutcome buildOutcome, java.util.List<String> contextPaths) {
        injectBuildFixSubgoalIfAbsent(buildOutcome, contextPaths, 0, null);
    }

    /**
     * T2-DD overload: when {@code rollbackStreak >= 2}, the same {@code (errorKind, path)} has
     * rolled back ≥2 consecutive cycles. Inject a *divergence* subgoal instead of another
     * same-locus retry — the description offers two paths (create missing artifact, or remove
     * the dangling reference) and the criteria require either resolution.
     */
    public void injectBuildFixSubgoalIfAbsent(BuildOutcome buildOutcome, java.util.List<String> contextPaths,
                                              int rollbackStreak, String streakKey) {
        if (hasActiveBuildFixSubgoal()) {
            log.debug("T1-DM: build-fix subgoal already active — skipping injection");
            return;
        }

        String stderr = buildOutcome.stderrTail();
        // T4-AU initial slice: classify upfront so the kind drives both the description's
        // failed-path selection AND the criteria. Without the kind, MISSING_POM ended up with
        // a Java filename in the description (the LLM was told to "fix BookController.java"
        // for a missing pom.xml — observed in 2026-05-07 03:53 run).
        // T4-BD: route through the adaptive classifier so summaryLine is populated when the
        // LLM-backed agent is active; fall back to the shared single-line picker otherwise.
        Classification classification = buildErrorClassifierAgent.classify(stderr);
        String summary = classification.summaryLine() != null
                ? classification.summaryLine()
                : BuildStderrLines.pickBestSingleLine(stderr);
        String failedPath = resolveFailedPath(stderr, classification.kind(), contextPaths);

        // T2-DD: divergence branch — locus-stuck. Override description + criteria so the planner
        // cannot keep targeting the same file. Stays a BUILD_FIX subgoal (carries the metadata
        // tag) so build-aware gating still applies, but its description is divergence-shaped.
        boolean divergence = rollbackStreak >= 2 && failedPath != null
                && classification.kind() == BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL;
        String description;
        if (divergence) {
            String missingSymbol = extractMissingSymbol(summary);
            String namedSymbol = missingSymbol != null ? missingSymbol : "the missing symbol";
            description = "Diverge — " + rollbackStreak + " consecutive rollbacks on " + failedPath
                    + " with same error. Either create " + namedSymbol
                    + " in its own file, or remove the reference to " + namedSymbol + " from " + failedPath
                    + ". Do NOT propose another edit to " + failedPath + " that reintroduces the same error.";
        } else if (rollbackStreak >= 2 && failedPath != null) {
            description = "Diverge — " + rollbackStreak + " consecutive rollbacks on " + failedPath
                    + ": " + summary + ". Propose a different file or revert the prior change.";
        } else {
            description = failedPath != null
                    ? "Fix compile error in " + failedPath + ": " + summary
                    : "Fix compile error: " + summary;
        }

        String newId = "goal-" + UUID.randomUUID();
        try {
            goalPlannerService.addSubGoal(newId, description, true);
        } catch (RuntimeException e) {
            log.warn("T1-DM: failed to inject BUILD_FIX subgoal: {}", e.getMessage());
            return;
        }

        GoalEntity injected = goalPlannerService.getGoalById(newId);
        if (injected != null) {
            injected.getMetadata().put(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX);
            injected.getMetadata().put(METADATA_KEY_BUILD_ERROR_KIND, classification.kind().name());
            goalPlannerService.saveGoalMetadata(injected);
        }
        // T2-CY: when a file is identified, prepend a per-file criterion so the evaluator and
        // evolver have a concrete repair target in addition to the generic mvn-compile criterion.
        java.util.List<String> criteria;
        if (divergence) {
            String missingSymbol = extractMissingSymbol(summary);
            String namedSymbol = missingSymbol != null ? missingSymbol : "the missing symbol";
            // T2-DD: divergence criteria — accept EITHER resolution path.
            criteria = java.util.List.of(
                    namedSymbol + ".java exists and defines " + namedSymbol + " (creation path)",
                    namedSymbol + " no longer appears in " + failedPath + " (removal path)",
                    "mvn compile exits 0 with no errors"
            );
        } else {
            criteria = classification.criteria();
            if (failedPath != null) {
                java.util.List<String> withPath = new java.util.ArrayList<>(criteria.size() + 1);
                withPath.add(failedPath + " compiles without errors");
                withPath.addAll(criteria);
                criteria = withPath;
            }
        }
        goalPlannerService.setAcceptanceCriteria(newId, criteria);

        if (divergence) {
            log.info("T2-DD: injected BUILD_FIX divergence subgoal [{}] — streak={} key={} desc='{}'",
                    newId, rollbackStreak, streakKey, description);
        } else {
            log.info("T1-DM: injected BUILD_FIX subgoal [{}] as current — '{}'", newId, description);
        }
    }

    /**
     * T2-DD: best-effort extraction of the missing class/symbol name from a "cannot find symbol"
     * stderr summary. Returns the symbol simple name (e.g. {@code "BookAlreadyLoanedException"})
     * or {@code null} if no recognisable symbol appears in the summary.
     */
    static String extractMissingSymbol(String summary) {
        if (summary == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "symbol:\\s*(?:class|variable|method)\\s+([A-Za-z_$][A-Za-z0-9_$]*)",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(summary);
        if (m.find()) return m.group(1);
        m = java.util.regex.Pattern.compile("class\\s+([A-Z][A-Za-z0-9_$]*)").matcher(summary);
        if (m.find()) return m.group(1);
        return null;
    }

    /**
     * T2-CY: extract the file the compile error refers to.
     * <p>
     * T4-AU initial slice: error-class-aware preference comes FIRST. For MISSING_POM,
     * {@link BuildFixTargets#preferredRepairTarget} returns {@code "pom.xml"} regardless of
     * what stderr or contextPaths contain — fixing the observed defect where MISSING_POM
     * ended up with a Java filename in the description.
     * <p>
     * For everything else: prefer the file the compiler pointed at (Maven file-coordinate
     * prefix); fall back to the first non-blank {@code contextPaths} entry. Returns a
     * normalised relative path (or {@code null}) when no file can be identified.
     */
    private static String resolveFailedPath(String stderr,
                                            BuildErrorClassifier.Kind kind,
                                            java.util.List<String> contextPaths) {
        // T4-AU: error-class-aware override — currently only fires for MISSING_POM.
        String preferred = BuildFixTargets.preferredRepairTarget(kind, contextPaths);
        if (preferred != null) {
            return normaliseRelativePath(preferred);
        }
        if (stderr != null) {
            java.util.regex.Matcher m = FILE_COORD_PREFIX.matcher(stderr);
            if (m.find()) {
                return normaliseRelativePath(m.group(1));
            }
        }
        if (contextPaths != null) {
            for (String p : contextPaths) {
                if (p == null || p.isBlank()) continue;
                return normaliseRelativePath(p);
            }
        }
        return null;
    }

    /**
     * T2-CY: turn an absolute path into something usable in a goal description. If the path
     * crosses an {@code /output/} boundary, take everything after it. Otherwise — if it starts
     * at {@code src/} — return as-is. Else fall back to the basename so we don't leak operator
     * paths into LLM prompts.
     */
    private static String normaliseRelativePath(String raw) {
        if (raw == null) return null;
        int outputIdx = raw.lastIndexOf("/output/");
        if (outputIdx >= 0) return raw.substring(outputIdx + "/output/".length());
        if (raw.startsWith("src/") || !raw.startsWith("/")) return raw;
        // Last-resort: basename only — operator absolute paths with no `/output/` segment.
        int slash = raw.lastIndexOf('/');
        return slash >= 0 ? raw.substring(slash + 1) : raw;
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
     * T1-DR: retire every active BUILD_FIX subgoal as SUBSUMED when the build clears.
     * SUBSUMED (not GIVEN_UP) because the subgoal's purpose was met without it shipping its
     * own work — flipping to GIVEN_UP would have looked like failed work to the closure rule.
     */
    public void clearActiveBuildFixSubgoals() {
        GoalEntity current = goalPlannerService.getCurrentSubGoal();
        if (isActiveBuildFix(current)) {
            log.info("T1-DR: build now COMPILE_OK — marking current BUILD_FIX subgoal [{}] SUBSUMED", current.getId());
            goalPlannerService.transitionGoal(current.getId(), GoalStatus.SUBSUMED,
                    "Build fixed — BUILD_FIX subgoal no longer needed",
                    com.walpro.autobuilder.goal.SubsumptionReason.COVERED);
        }
        for (GoalEntity g : goalPlannerService.getRemainingGoals()) {
            if (isActiveBuildFix(g)) {
                log.info("T1-DR: build now COMPILE_OK — marking remaining BUILD_FIX subgoal [{}] SUBSUMED", g.getId());
                goalPlannerService.transitionGoal(g.getId(), GoalStatus.SUBSUMED,
                        "Build fixed — BUILD_FIX subgoal no longer needed",
                        com.walpro.autobuilder.goal.SubsumptionReason.COVERED);
            }
        }
    }

    private static boolean isActiveBuildFix(GoalEntity g) {
        if (g == null || g.getMetadata() == null) return false;
        if (g.getStatus() != GoalStatus.ACTIVE) return false;
        return BlockerTypes.BUILD_FIX.equals(g.getMetadata().get(BlockerTypes.METADATA_KEY));
    }

    // T2-CY: Maven file-coordinate prefix (e.g. "/abs/path/to/Foo.java:[12,5]" or
    // "Foo.java:[12,5]"). Group 1 is the file path. Java extension only — non-Java compile
    // errors out of scope.
    private static final Pattern FILE_COORD_PREFIX = Pattern.compile(
            "(\\S+\\.java):\\[\\d+,\\d+\\]"
    );
}
