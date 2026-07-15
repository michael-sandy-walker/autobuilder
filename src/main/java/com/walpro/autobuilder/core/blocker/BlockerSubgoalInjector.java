package com.walpro.autobuilder.core.blocker;

import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.build.BuildErrorClassifier;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildErrorClassifierAgent;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.FileRoleClassifier;
import com.walpro.autobuilder.core.build.BuildStderrLines;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
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
    private final FileService fileService;
    private final FileRoleClassifier fileRoleClassifier;

    /**
     * T1-DM: ensure exactly one active BUILD_FIX subgoal exists when the post-merge build fails.
     * Idempotent: no-op when an active BUILD_FIX subgoal is already present.
     * <p>
     * T2-CY: {@code contextPaths} carries the merged file list (or, for synthetic outcomes
     * from a rollback observation, the rolled-back paths). Used to name the failed file in the
     * subgoal description and acceptance criteria when stderr lacks a file-coordinate prefix
     * (e.g. MISSING_POM has no per-file diagnostic).
     */
    public void injectBuildFixSubgoalIfAbsent(BuildOutcome buildOutcome, java.util.List<String> contextPaths) {
        injectBuildFixSubgoalIfAbsent(buildOutcome, contextPaths, 0, null);
    }

    /**
     * T2-DD overload: when {@code sameFailureStreak >= 2}, the same failure identity has
     * recurred on consecutive attempts — rolled back post-merge, or (the live channel since
     * T1-EC) probe-rejected pre-merge. Inject a *divergence* subgoal instead of another
     * same-locus retry — the description offers two paths (create missing artifact, or remove
     * the dangling reference) and the criteria require either resolution.
     */
    public void injectBuildFixSubgoalIfAbsent(BuildOutcome buildOutcome, java.util.List<String> contextPaths,
                                              int sameFailureStreak, String streakKey) {
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
        // T1-EX: pre-injection guard — when the diagnostic names a `(symbol, file)` and the file
        // currently does not reference that symbol on disk, the diagnostic is phantom: a stale or
        // cascade-induced error pointing at something that already doesn't exist. Skipping here
        // prevents the BUILD_FIX subgoal from being filed in the first place. Skipped only on the
        // default branch — the same-failure-streak divergence path is a different shape ("locus
        // stuck after repeated failed attempts") whose intent is to redirect the planner rather
        // than fix a specific live diagnostic.
        boolean divergence = sameFailureStreak >= 2 && failedPath != null
                && classification.kind() == BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL;
        if (!divergence && sameFailureStreak < 2 && failedPath != null) {
            String candidateSymbol = extractMissingSymbol(summary);
            if (candidateSymbol != null && !fileReferencesSymbol(failedPath, candidateSymbol)) {
                log.info("T1-EX: skipping BUILD_FIX subgoal — file {} does not reference symbol {} (phantom diagnostic)",
                        failedPath, candidateSymbol);
                return;
            }
        }

        String description;
        if (divergence) {
            String missingSymbol = extractMissingSymbol(summary);
            String namedSymbol = missingSymbol != null ? missingSymbol : "the missing symbol";
            description = BlockerTypes.DIVERGENCE_DESCRIPTION_PREFIX + sameFailureStreak + " consecutive failed attempts on " + failedPath
                    + " with same error. Either create " + namedSymbol
                    + " in its own file, or remove the reference to " + namedSymbol + " from " + failedPath
                    + ". Do NOT propose another edit to " + failedPath + " that reintroduces the same error.";
        } else if (sameFailureStreak >= 2 && failedPath != null) {
            description = BlockerTypes.DIVERGENCE_DESCRIPTION_PREFIX + sameFailureStreak + " consecutive failed attempts on " + failedPath
                    + ": " + summary + ". Propose a different file or revert the prior change.";
        } else {
            description = failedPath != null
                    ? BlockerTypes.COMPILE_FIX_DESCRIPTION_PREFIX + " in " + failedPath + ": " + summary
                    : BlockerTypes.COMPILE_FIX_DESCRIPTION_PREFIX + ": " + summary;
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
                    newId, sameFailureStreak, streakKey, description);
        } else {
            log.info("T1-DM: injected BUILD_FIX subgoal [{}] as current — '{}'", newId, description);
        }
    }

    /**
     * T2-EF: inject a SYMBOL-centric divergence subgoal for a defect that dominates the run's
     * residual compile failures across multiple subgoals (driven by {@code RecurringDefectTracker}
     * via {@code BuildBlockGate.ensureRecurringDefectRedirection}). Unlike the locus-stuck T2-DD
     * divergence above — which is keyed on a failed file path ("stop editing file X") — this is
     * keyed on the missing symbol: it recurs across many files/subgoals, so the work item is
     * "create the type", not "stop editing one file". {@code token} is the missing symbol's simple
     * name (runtime data from the diagnostic, never a hardcoded framework name).
     */
    public void injectRecurringDefectRedirection(String errorKind, String token, int subgoalSpan, boolean hadGreenFix) {
        if (token == null || token.isBlank()) return;
        if (hasActiveBuildFixSubgoal()) {
            log.debug("T2-EF: build-fix subgoal already active — skipping recurring-defect redirection for {}", token);
            return;
        }
        String description = BlockerTypes.DIVERGENCE_DESCRIPTION_PREFIX + "the type '" + token + "' is missing but is referenced across "
                + subgoalSpan + " subgoals; it dominates this run's compile failures. Create " + token
                + " in its own file with the members its callers use, or correct its package if it already exists elsewhere."
                + (hadGreenFix
                    ? " An import for " + token + " has already compiled cleanly, so the import is correct —"
                      + " the gap is the type's existence or package placement."
                    : "");
        String newId = "goal-" + UUID.randomUUID();
        try {
            goalPlannerService.addSubGoal(newId, description, true);
        } catch (RuntimeException e) {
            log.warn("T2-EF: failed to inject recurring-defect redirection subgoal: {}", e.getMessage());
            return;
        }
        GoalEntity injected = goalPlannerService.getGoalById(newId);
        if (injected != null) {
            injected.getMetadata().put(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX);
            if (errorKind != null) injected.getMetadata().put(METADATA_KEY_BUILD_ERROR_KIND, errorKind);
            goalPlannerService.saveGoalMetadata(injected);
        }
        goalPlannerService.setAcceptanceCriteria(newId, java.util.List.of(
                token + ".java exists and defines " + token,
                "mvn compile exits 0 with no errors"));
        log.info("T2-EF: injected recurring-defect redirection subgoal [{}] for {} (spans {} subgoals, hadGreenFix={}) — '{}'",
                newId, token, subgoalSpan, hadGreenFix, description);
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
     * T4-AU: error-class-aware preference comes FIRST. For MISSING_POM,
     * {@link FileRoleClassifier#preferredRepairTarget} names the build descriptor regardless
     * of what stderr or contextPaths contain — fixing the observed defect where MISSING_POM
     * ended up with a Java filename in the description.
     * <p>
     * For everything else: prefer the file the compiler pointed at (file-coordinate prefix —
     * strictly more precise than any role-based guess); fall back to
     * {@link FileRoleClassifier#preferredFallbackTarget} (role-aware: source-shaped kinds pick
     * a non-test source candidate over positional order). Returns a normalised relative path
     * (or {@code null}) when no file can be identified.
     */
    private String resolveFailedPath(String stderr,
                                     BuildErrorClassifier.Kind kind,
                                     java.util.List<String> contextPaths) {
        String preferred = fileRoleClassifier.preferredRepairTarget(kind, contextPaths);
        if (preferred != null) {
            return normaliseRelativePath(preferred);
        }
        if (stderr != null) {
            java.util.regex.Matcher m = FILE_COORD_PREFIX.matcher(stderr);
            if (m.find()) {
                return normaliseRelativePath(m.group(1));
            }
        }
        String fallback = fileRoleClassifier.preferredFallbackTarget(kind, contextPaths);
        return fallback != null ? normaliseRelativePath(fallback) : null;
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

    /**
     * T1-EX: best-effort check whether {@code failedPath} (relative to {@code output/}) references
     * {@code symbol} as a bare identifier. Returns {@code true} (don't-skip) when the file can't
     * be read or doesn't exist yet — newly-missing files are not "phantom"; they are exactly the
     * case BUILD_FIX subgoals were invented for.
     */
    private boolean fileReferencesSymbol(String failedPath, String symbol) {
        if (failedPath == null || symbol == null || symbol.isBlank()) return true;
        String relPath = failedPath.startsWith("output/") ? failedPath : "output/" + failedPath;
        try {
            if (!fileService.exists(relPath)) return true;
            String content = fileService.readString(relPath);
            if (content == null) return true;
            Pattern bare = Pattern.compile("\\b" + Pattern.quote(symbol) + "\\b");
            return bare.matcher(content).find();
        } catch (IOException e) {
            return true;
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
