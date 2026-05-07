package com.walpro.autobuilder.core.blocker;

import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.build.BuildErrorClassifier;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildFixTargets;
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
     * <p>
     * T2-CY: {@code contextPaths} carries the merged file list (or, for synthetic outcomes
     * from rollback evidence, the rolled-back paths). Used to name the failed file in the
     * subgoal description and acceptance criteria when stderr lacks a file-coordinate prefix
     * (e.g. MISSING_POM has no per-file diagnostic).
     */
    public void injectBuildFixSubgoalIfAbsent(BuildOutcome buildOutcome, java.util.List<String> contextPaths) {
        if (hasActiveBuildFixSubgoal()) {
            log.debug("T1-DM: build-fix subgoal already active — skipping injection");
            return;
        }

        String stderr = buildOutcome.stderrTail();
        String summary = summariseStderr(stderr);
        // T4-AU initial slice: classify upfront so the kind drives both the description's
        // failed-path selection AND the criteria. Without the kind, MISSING_POM ended up with
        // a Java filename in the description (the LLM was told to "fix BookController.java"
        // for a missing pom.xml — observed in 2026-05-07 03:53 run).
        Classification classification = BuildErrorClassifier.classify(stderr);
        String failedPath = resolveFailedPath(stderr, classification.kind(), contextPaths);
        String description = failedPath != null
                ? "Fix compile error in " + failedPath + ": " + summary
                : "Fix compile error: " + summary;

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
        java.util.List<String> criteria = classification.criteria();
        if (failedPath != null) {
            java.util.List<String> withPath = new java.util.ArrayList<>(criteria.size() + 1);
            withPath.add(failedPath + " compiles without errors");
            withPath.addAll(criteria);
            criteria = withPath;
        }
        goalPlannerService.setAcceptanceCriteria(newId, criteria);

        log.info("T1-DM: injected BUILD_FIX subgoal [{}] as current — '{}'", newId, description);
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

    // T2-CY: Maven file-coordinate prefix (e.g. "/abs/path/to/Foo.java:[12,5]" or
    // "Foo.java:[12,5]"). Group 1 is the file path. Java extension only — non-Java compile
    // errors out of scope.
    private static final Pattern FILE_COORD_PREFIX = Pattern.compile(
            "(\\S+\\.java):\\[\\d+,\\d+\\]"
    );

    // T2-CX: Maven's "COMPILATION ERROR :" / "BUILD FAILURE" framing is non-diagnostic header
    // text that the previous summariser would pick as the "first informative [ERROR] line",
    // producing useless BUILD_FIX descriptions. Skip these explicitly so we walk past them
    // to the real diagnostic line below.
    private static final java.util.Set<String> NON_DIAGNOSTIC_BANNERS = java.util.Set.of(
            "COMPILATION ERROR :",
            "COMPILATION ERROR:",
            "BUILD FAILURE",
            "BUILD ERROR"
    );

    // T2-CX: classifier-matchable patterns — lines matching these produce a non-UNKNOWN
    // BuildErrorClassifier verdict. Tried FIRST so the summary is something downstream
    // classification can actually identify.
    private static final java.util.List<Pattern> CLASSIFIER_MATCHABLE_PATTERNS = java.util.List.of(
            Pattern.compile("no POM in this directory|could not find a POM", Pattern.CASE_INSENSITIVE),
            Pattern.compile("symbol:\\s*(?:class|variable|method)\\s+\\S+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("package\\s+\\S+\\s+does not exist", Pattern.CASE_INSENSITIVE)
    );

    // T2-CX: broader diagnostic shapes — useful as informative summaries even when the
    // classifier returns UNKNOWN. Tried as a second-tier preference, ahead of generic
    // [ERROR] lines but behind the classifier-matchable patterns above.
    private static final java.util.List<Pattern> DIAGNOSTIC_PATTERNS = java.util.List.of(
            Pattern.compile("cannot find symbol", Pattern.CASE_INSENSITIVE),
            Pattern.compile("class\\s+\\S+\\s+is public, should be declared", Pattern.CASE_INSENSITIVE),
            Pattern.compile("[';]\\s*expected", Pattern.CASE_INSENSITIVE),
            Pattern.compile("incompatible types"),
            Pattern.compile("method\\s+\\S+\\s+in (class|interface)"),
            Pattern.compile("error: ", Pattern.CASE_INSENSITIVE)
    );

    /**
     * T1-DX: also called by {@code MergeAgent} when capturing rollback evidence.
     * <p>
     * T2-CX: walks the stderr stripped of ANSI escapes and {@code [ERROR]} prefixes, picking
     * the FIRST line that looks like a real javac/Maven diagnostic (matching one of
     * {@link #DIAGNOSTIC_PATTERNS}). Falls back to the prior behaviour — first informative
     * {@code [ERROR]} line — when no diagnostic match is found, so existing call sites stay
     * non-regressive. Skips Maven's banner lines (e.g. {@code "COMPILATION ERROR :"}) which
     * are not diagnostic content even though they're the first informative {@code [ERROR]}
     * line in a typical Maven failure.
     */
    public static String summariseStderr(String stderrTail) {
        if (stderrTail == null || stderrTail.isBlank()) return "(no stderr captured)";

        // Pre-T2-CX behaviour: take only [ERROR]-prefixed lines, strip the prefix and framing.
        java.util.List<String> errorLines = stderrTail.lines()
                .map(line -> ANSI_ESCAPE.matcher(line).replaceAll("").strip())
                .filter(line -> line.startsWith("[ERROR]"))
                .map(line -> line.substring("[ERROR]".length()).strip())
                .filter(stripped -> !stripped.isEmpty()
                        && !stripped.startsWith("-> [Help")
                        && !NON_DIAGNOSTIC_BANNERS.contains(stripped))
                .toList();

        if (!errorLines.isEmpty()) {
            // T2-CX preference order:
            //   1. classifier-matchable patterns (produce non-UNKNOWN verdict downstream)
            //   2. broader diagnostic shapes (informative summary even if classifier says UNKNOWN)
            //   3. first informative [ERROR] line (non-regressive fallback for unrecognised shapes)
            for (String line : errorLines) {
                if (matchesAny(line, CLASSIFIER_MATCHABLE_PATTERNS)) return truncate(line);
            }
            for (String line : errorLines) {
                if (matchesAny(line, DIAGNOSTIC_PATTERNS)) return truncate(line);
            }
            return truncate(errorLines.get(0));
        }

        // No [ERROR] lines at all — fall back to first non-empty line of any kind. Preserves
        // pre-T2-CX behaviour for non-Maven build tools whose stderr lacks the [ERROR] prefix.
        return stderrTail.lines()
                .map(line -> ANSI_ESCAPE.matcher(line).replaceAll("").strip())
                .filter(s -> !s.isEmpty())
                .findFirst()
                // Deep fallback (every line empty after ANSI strip): strip ANSI from the raw
                // input so we don't emit raw escape bytes into a goal description.
                .orElseGet(() -> ANSI_ESCAPE.matcher(stderrTail).replaceAll("").strip())
                .transform(BlockerSubgoalInjector::truncate);
    }

    private static boolean matchesAny(String line, java.util.List<Pattern> patterns) {
        for (Pattern p : patterns) {
            if (p.matcher(line).find()) return true;
        }
        return false;
    }

    private static String truncate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
