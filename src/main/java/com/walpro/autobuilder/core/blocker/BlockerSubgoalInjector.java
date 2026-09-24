package com.walpro.autobuilder.core.blocker;

import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.build.BootGate;
import com.walpro.autobuilder.core.build.BuildErrorClassifier;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildErrorClassifierAgent;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.FileRoleClassifier;
import com.walpro.autobuilder.core.build.BuildStderrLines;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
     * T4-DL: optional so no existing construction site changes. Used only to ask whether ANY
     * published artifact contains the missing class — the one question that decides whether
     * "make the package available" is even achievable.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @lombok.Setter
    private com.walpro.autobuilder.core.build.inject.CoordinateSearchClient coordinateSearchClient;

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
        // T2-EQ field verification 2026-07-27: a packaging failure is NOT located in any file that
        // just merged — it is about a file that does not exist. Naming a merged path made the leaf
        // read "Fix compile error in Book.java: The main class cannot be found", whose first
        // criterion ("Book.java compiles without errors") was ALREADY TRUE, so the surface scan
        // answered "all criteria present" and evolution returned #NoDiff four times in three
        // minutes. Same shape as the MISSING_POM defect T4-AU's initial slice fixed. A stderr file
        // coordinate is still honoured if the tool supplies one; only the merged-path guess is
        // withheld.
        boolean packagingFailure = buildOutcome.result() == BuildResult.PACKAGE_ERROR;
        String failedPath = packagingFailure
                ? resolveFailedPath(stderr, classification.kind(), java.util.List.of())
                : resolveFailedPath(stderr, classification.kind(), contextPaths);

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
            // T2-EQ: a failure the tool reported after the sources compiled is not a compile error,
            // and saying so invites the agent to look for one where none exists.
            String prefix = packagingFailure
                    ? BlockerTypes.BUILD_FIX_TOOL_ERROR_PREFIX
                    : BlockerTypes.COMPILE_FIX_DESCRIPTION_PREFIX;
            description = failedPath != null
                    ? prefix + " in " + failedPath + ": " + summary
                    : prefix + ": " + summary;
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
            // T2-EQ: stamp the check that produced this work item (T4-CC's existing key), so the
            // COMPILE_OK cleanup can tell a leaf it has actually resolved from one it has not.
            if (packagingFailure) {
                injected.getMetadata().put(BlockerTypes.ORIGINATING_GATE_KEY, BlockerTypes.GATE_PACKAGE);
            }
            goalPlannerService.saveGoalMetadata(injected);
        }
        // T2-CY: when a file is identified, prepend a per-file criterion so the evaluator and
        // evolver have a concrete repair target in addition to the generic mvn-compile criterion.
        java.util.List<String> criteria;
        String missingSymbol = extractMissingSymbol(summary);
        if (divergence) {
            String namedSymbol = missingSymbol != null ? missingSymbol : "the missing symbol";
            // T2-DD: divergence criteria — accept EITHER resolution path.
            criteria = java.util.List.of(
                    namedSymbol + ".java exists and defines " + namedSymbol + " (creation path)",
                    namedSymbol + " no longer appears in " + failedPath + " (removal path)",
                    "mvn compile exits 0 with no errors"
            );
        } else if (unobtainablePackageSymbol(classification, stderr, missingSymbol) != null) {
            // T4-DL: the package is not merely absent from THIS project — no published artifact
            // contains the class at all. "The missing package is available on the compile
            // classpath" is then an unachievable criterion, and asking for it sends the loop
            // hunting a dependency that does not exist (T4-DH's 26 invented coordinates are the
            // same failure from the other side). Offer the achievable path instead, mirroring the
            // creation/removal pair above.
            String fqn = unobtainablePackageSymbol(classification, stderr, missingSymbol);
            log.info("T4-DL: no published artifact contains {} — the import cannot be satisfied by "
                    + "any dependency; steering to the removal path", fqn);
            criteria = java.util.List.of(
                    fqn + " is no longer imported or referenced (no published artifact provides it)",
                    "the behaviour it was used for is provided by an API the project can obtain",
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
     * T2-ES: inject a divergence subgoal for a STRUCTURAL defect one deterministic heuristic has
     * reported on {@code streak} consecutive cycles against the same subject. The third member of
     * the divergence family, and the first whose defect the compiler cannot see: T2-DD is keyed on
     * a failed file path ("stop editing file X"), T2-EF on a missing symbol ("create the type"),
     * and this on a finding that survives every rewrite of a file that compiles cleanly.
     *
     * <p>Why it exists, measured. Run {@code 2026-09-13_09-44-03} rejected seven consecutive
     * mutations over fifteen minutes for one parallel store. Detection worked and the relay
     * worked — the persistent-warning block carried the finding, with file and line numbers, into
     * the evolution prompt under the directive that the mutation MUST address it. The only thing
     * that changed between the second attempt and the seventh was the integer in that warning.
     * Escalation that means saying the same thing louder is what this replaces: the work item
     * names the mutually sufficient ways out, so the next proposal chooses between shapes instead
     * of re-deciding whether the warning is true.
     *
     * <p>{@code subject} and {@code resolutionPaths} are supplied by the finding heuristic and
     * derived from the parsed target code — no framework or target-domain vocabulary enters here.
     * This method renders them; it does not interpret them.
     */
    /**
     * What an escalation did: the work item it injected, or why it injected none. Returned so the
     * caller can RECORD the outcome — the first field escalation (2026-09-14) emitted its event
     * and injected nothing, because a planner-written blocker subgoal was already active, and the
     * skip was a debug line nobody saw.
     */
    public record StructuralDefectEscalation(String injectedGoalId, String skippedBecause) {
        public static StructuralDefectEscalation injected(String goalId) {
            return new StructuralDefectEscalation(goalId, null);
        }
        public static StructuralDefectEscalation skipped(String why) {
            return new StructuralDefectEscalation(null, why);
        }
        public boolean injected() {
            return injectedGoalId != null;
        }
        public String outcome() {
            return injected() ? "injected" : "skipped: " + skippedBecause;
        }
    }

    public StructuralDefectEscalation injectStructuralDefectRedirection(String heuristicName,
                                                  String subject,
                                                  java.util.List<String> resolutionPaths,
                                                  int streak) {
        if (subject == null || subject.isBlank()) {
            return StructuralDefectEscalation.skipped("no structural subject");
        }
        if (resolutionPaths == null || resolutionPaths.isEmpty()) {
            // Without alternatives there is nothing to say that the warning has not already said,
            // and injecting anyway would re-ask through a more expensive channel.
            log.info("T2-ES: {} reported '{}' for {} cycles but offered no resolution paths — "
                    + "not escalating", heuristicName, subject, streak);
            return StructuralDefectEscalation.skipped("no resolution paths");
        }
        if (hasActiveBuildFixSubgoal()) {
            log.info("T2-ES: a blocker subgoal is already active — skipping structural-defect "
                    + "redirection for {} ({} reported it for {} cycles)", subject, heuristicName, streak);
            return StructuralDefectEscalation.skipped("a blocker subgoal is already active");
        }

        StringBuilder description = new StringBuilder(BlockerTypes.DIVERGENCE_DESCRIPTION_PREFIX)
                .append(streak).append(" consecutive proposals were rejected for the same structural defect")
                .append(" concerning '").append(subject).append("'. Re-proposing the same shape cannot clear it.")
                .append(" Choose ONE of these and implement it completely:");
        for (String path : resolutionPaths) {
            description.append("\n  - ").append(path);
        }
        description.append("\nDo NOT propose another edit that leaves the defect in place.");

        String newId = "goal-" + UUID.randomUUID();
        try {
            goalPlannerService.addSubGoal(newId, description.toString(), true);
        } catch (RuntimeException e) {
            log.warn("T2-ES: failed to inject structural-defect redirection subgoal: {}", e.getMessage());
            return StructuralDefectEscalation.skipped("injection failed: " + e.getMessage());
        }

        GoalEntity injected = goalPlannerService.getGoalById(newId);
        if (injected != null) {
            injected.getMetadata().put(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX);
            // The defect compiles, so a compile observation can attest to nothing about it. The
            // stamp is what keeps the COMPILE_OK cleanup off this item (T2-EQ's carve-out).
            injected.getMetadata().put(BlockerTypes.ORIGINATING_GATE_KEY,
                    BlockerTypes.GATE_STRUCTURAL_DEFECT);
            goalPlannerService.saveGoalMetadata(injected);
        }

        // Any ONE path clears the defect, so each is its own criterion — the same shape T2-DD uses
        // for its creation/removal pair. Phrased as requirements about the code, never about the
        // loop's own state, so they stay confirmable after the defect is gone.
        goalPlannerService.setAcceptanceCriteria(newId, java.util.List.copyOf(resolutionPaths));

        log.info("T2-ES: injected structural-defect redirection subgoal [{}] — {} reported '{}' for "
                        + "{} consecutive cycles; offering {} resolution path(s)",
                newId, heuristicName, subject, streak, resolutionPaths.size());
        return StructuralDefectEscalation.injected(newId);
    }

    /**
     * T2-DD: best-effort extraction of the missing class/symbol name from a "cannot find symbol"
     * stderr summary. Returns the symbol simple name (e.g. {@code "BookAlreadyLoanedException"})
     * or {@code null} if no recognisable symbol appears in the summary.
     */
    /**
     * T2-ES, the case the ticket is named for: evolution keeps reporting the work as already
     * implemented while an acceptance criterion stays unmet. Both statements are usually TRUE —
     * the file exists and the behaviour is absent — and the loop's only response was to ask
     * evolution again, differently, which produces another identical answer. The disagreement is
     * itself the information, so this changes the question instead of repeating it.
     *
     * <p>Every word of the injected item is derived from the run's own state: the criterion text
     * comes from the planner, the count is observed, and the structural differences are computed
     * against the project's OWN peers. Nothing here names an ecosystem, a framework or a target
     * domain concept, and nothing may be added that does.
     *
     * @param unmetCriteria       the acceptance criteria still failing, in the planner's words
     * @param streak              consecutive cycles of the same disagreement
     * @param structuralDifferences derived peer anomalies, empty when the project shows none
     */
    public StructuralDefectEscalation injectNoDiffDisagreementRedirection(
            java.util.List<String> unmetCriteria, int streak,
            java.util.List<String> structuralDifferences) {
        if (unmetCriteria == null || unmetCriteria.isEmpty()) {
            // Without a criterion there is no contradiction to show, and an item saying only
            // "investigate" is re-asking through a more expensive channel.
            log.info("T2-ES: #NoDiff disagreement repeated {} times but no unmet criterion is on "
                    + "record — not escalating", streak);
            return StructuralDefectEscalation.skipped("no unmet criterion on record");
        }
        if (hasActiveBuildFixSubgoal()) {
            log.info("T2-ES: a blocker subgoal is already active — skipping #NoDiff disagreement "
                    + "redirection after {} repeats", streak);
            return StructuralDefectEscalation.skipped("a blocker subgoal is already active");
        }

        StringBuilder description = new StringBuilder(BlockerTypes.DIVERGENCE_DESCRIPTION_PREFIX)
                .append("for ").append(streak).append(" consecutive cycles this work has been reported")
                .append(" as already implemented while these acceptance criteria stayed unmet:");
        for (String criterion : unmetCriteria) {
            description.append("\n  - ").append(criterion);
        }
        description.append("\nBoth statements can be true at once: the code can exist and the required")
                .append(" behaviour still be absent. Proposing the same implementation again cannot")
                .append(" change that, so do not re-send it.");
        if (structuralDifferences != null && !structuralDifferences.isEmpty()) {
            description.append("\nThese types differ from their own peers in this project:");
            for (String difference : structuralDifferences) {
                description.append("\n  - ").append(difference);
            }
            description.append("\nEither bring a named difference into line with its peers, or change")
                    .append(" the code so a criterion above is demonstrably satisfied.");
        } else {
            description.append("\nInvestigate why the code that exists does not behave as the criteria")
                    .append(" require, and change what you find — not the implementation you have")
                    .append(" already sent.");
        }

        String newId = "goal-" + UUID.randomUUID();
        try {
            goalPlannerService.addSubGoal(newId, description.toString(), true);
        } catch (RuntimeException e) {
            log.warn("T2-ES: failed to inject #NoDiff disagreement redirection subgoal: {}", e.getMessage());
            return StructuralDefectEscalation.skipped("injection failed: " + e.getMessage());
        }

        GoalEntity injected = goalPlannerService.getGoalById(newId);
        if (injected != null) {
            injected.getMetadata().put(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX);
            // The code compiles — that is the whole shape of this defect — so the COMPILE_OK
            // cleanup must not retire the item. Same carve-out as the structural-defect gate.
            injected.getMetadata().put(BlockerTypes.ORIGINATING_GATE_KEY,
                    BlockerTypes.GATE_NODIFF_DISAGREEMENT);
            goalPlannerService.saveGoalMetadata(injected);
        }

        // The criteria are the planner's own words for the behaviour that is missing, so they stay
        // confirmable after the defect is gone — which a criterion about the loop's state would not.
        goalPlannerService.setAcceptanceCriteria(newId, java.util.List.copyOf(unmetCriteria));

        log.info("T2-ES: injected #NoDiff disagreement redirection subgoal [{}] — {} repeats, "
                        + "{} unmet criterion(s), {} structural difference(s) named",
                newId, streak, unmetCriteria.size(),
                structuralDifferences == null ? 0 : structuralDifferences.size());
        return StructuralDefectEscalation.injected(newId);
    }

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
     *
     * <p>T2-EQ: "the build clears" here means the sources COMPILE, which stopped covering every
     * blocker the moment a work item could carry a packaging failure. Leaves stamped with the
     * package gate are left alone — a compile-OK observation says nothing about them, and
     * subsuming one destroyed and re-created the same work item on every merge (observed
     * 2026-07-27: three identical leaves in three minutes, each announced to the evolution agent
     * as "already resolved" while the failure was live). {@link #clearPackagingBuildFixSubgoals()}
     * is the observation that CAN retire them.
     *
     * <p>That carve-out was written for one gate and the reasoning holds for all of them: an
     * observation may only retire the work items raised by the check that made the same
     * observation. The boot, test and acceptance-criteria gates each stamp their items too, and
     * compile-OK attests to none of them. Run {@code 2026-08-30_00-06-00}: the criteria gate
     * raised a work item naming four unmet requirements at 00:15:12, a merge compiled at 00:16:00
     * and retired it, and the root completed 17 seconds later with those requirements still
     * unmet. So this now retires only items carrying NO gate stamp — plain compile fixes, which
     * are exactly what a compile observation can speak for. A gate-stamped item is retired by its
     * own gate, or worked and fulfilled like any other subgoal.
     */
    public void clearActiveBuildFixSubgoals() {
        retireActiveBuildFixSubgoals("build now COMPILE_OK", g -> !hasOriginatingGate(g));
    }

    /**
     * T2-EQ: retire the work items the package gate raised, once that same gate reports the tree
     * packages. The mirror of {@link #clearActiveBuildFixSubgoals()} — each observation retires
     * only what it can actually attest to.
     */
    public void clearPackagingBuildFixSubgoals() {
        retireActiveBuildFixSubgoals("packaging now succeeds", this::isFromPackageGate);
    }

    /**
     * T4-DW: retire the work items the boot gate raised, once that same gate observes the claim
     * they make. The third mirror of {@link #clearActiveBuildFixSubgoals()}, built for the reason
     * the first two were: each observation retires only what it can attest to.
     *
     * <p><b>Why an observation and not the gate's return value.</b> {@code BootGate} answers with
     * the problem, so "clean" and "abstained" are the same empty — eleven abstention paths share
     * it. Measured over the 40-run firestarter window that motivated this: boot-gate leaves alive
     * at run end went 4 to 17, and two runs that ended at a hand-verified 23/23 kept a leaf that
     * later boot-gate OK lines had already answered. Retiring on those lines would have been
     * right twice and wrong wherever the OK was an abstention, so the gate now says what it
     * ESTABLISHED and this reads that instead.
     *
     * <p>Claims are matched, never guessed from the item's prose: the injector stamps
     * {@link BlockerTypes#BOOT_CLAIM_KEY} when it raises the item, and an item carrying no stamp
     * (an older run's, or one raised by a path that does not know its claim) is left alone.
     */
    public void clearBootBuildFixSubgoals(BootGate.BootObservation observation) {
        if (observation == null) return;
        retireGateStampedSubgoals(BlockerTypes.GATE_BOOT,
                "the boot gate observed the claim this item makes",
                g -> bootClaimObserved(g, observation));
    }

    private boolean bootClaimObserved(GoalEntity g, BootGate.BootObservation observation) {
        Object claim = g.getMetadata() == null ? null : g.getMetadata().get(BlockerTypes.BOOT_CLAIM_KEY);
        if (BlockerTypes.CLAIM_SERVES.equals(claim)) {
            return observation.servesCleanly();
        }
        if (BlockerTypes.CLAIM_CRITERION.equals(claim)) {
            // Every criterion the item was raised from must have been PROBED AGAIN and passed.
            // An empty list here would otherwise retire on "nothing was contradicted", which is
            // exactly what a sweep that judged nothing reports.
            List<String> quoted = criteriaOf(g);
            return !quoted.isEmpty() && quoted.stream().allMatch(observation::observedPassing);
        }
        // CLAIM_ENTRY_POINT is answered by a source scan, not by a launch — see
        // clearEntryPointBuildFixSubgoals; CLAIM_ARCHIVE_ENTRY_POINT by the build config and the
        // jar — see clearArchiveEntryPointSubgoals. An unstamped item is nobody's to retire.
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<String> criteriaOf(GoalEntity g) {
        Object raw = g.getMetadata() == null ? null : g.getMetadata().get(BlockerTypes.BOOT_CLAIM_CRITERIA_KEY);
        if (raw instanceof List<?> list) {
            return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return List.of();
    }

    /**
     * T4-DW: retire the entry-point requirement when the sources declare an entry point again.
     *
     * <p>Separate from the launch observation on purpose. The claim is about the SOURCES, so the
     * evidence is the same scan that raised it ({@code EntryPointScan}) — no package, no boot, and
     * therefore available on every cycle, including the cycles where the tree does not compile.
     * That is the state the entry-point item lives in, and the reason T4-CZ raises it there.
     */
    public void clearEntryPointBuildFixSubgoals(Map<String, String> outputFiles) {
        if (outputFiles == null || outputFiles.isEmpty()) return;
        if (!com.walpro.autobuilder.core.persistence.EntryPointScan.hasJavaEntryPointInSources(outputFiles)) {
            return;
        }
        retireGateStampedSubgoals(BlockerTypes.GATE_BOOT,
                "the sources declare an entry point again",
                g -> BlockerTypes.CLAIM_ENTRY_POINT.equals(
                        g.getMetadata() == null ? null : g.getMetadata().get(BlockerTypes.BOOT_CLAIM_KEY)));
    }

    /**
     * T4-DW: retire the archive-entry-point item once the entry point is declared.
     *
     * <p>Its own mirror because its evidence is neither a launch nor the source scan: the claim is
     * about the ARCHIVE, and the answer is the build config and the jar ({@code PackageGate
     * .archiveEntryPointDeclared()}). Available every cycle, including the ones where the tree
     * does not compile — measured on flightsim run {@code 2026-09-24_17-47-47}, where the
     * deterministic repair declared the entry point 21 s after this item was raised, and the item,
     * stamped {@code serves}, then outlived it for the rest of the run.
     *
     * <p>{@code declared} is asked only when such an item exists, and at most once: the evidence
     * reads the source tree, and an ordinary cycle should not pay for it.
     *
     * @return how many items were retired, so the caller can let a recurrence be raised again
     */
    public int clearArchiveEntryPointSubgoals(java.util.function.BooleanSupplier declared) {
        if (declared == null) return 0;
        Boolean[] answer = {null};
        return retireGateStampedSubgoals(BlockerTypes.GATE_BOOT,
                "the archive's entry point is now declared",
                g -> BlockerTypes.CLAIM_ARCHIVE_ENTRY_POINT.equals(
                        g.getMetadata() == null ? null : g.getMetadata().get(BlockerTypes.BOOT_CLAIM_KEY))
                        && (answer[0] != null ? answer[0] : (answer[0] = declared.getAsBoolean())));
    }

    /**
     * T4-DW: the retirement loop for gate-stamped items, which are not necessarily BUILD_FIX-tagged
     * — T4-CZ's entry-point requirement and T4-CY's observed-defect item are ordinary coverage
     * items carrying a gate stamp. {@link #retireActiveBuildFixSubgoals} filters on the tag and so
     * cannot see them.
     */
    private int retireGateStampedSubgoals(String gate, String reason,
                                          java.util.function.Predicate<GoalEntity> retire) {
        int retired = 0;
        List<GoalEntity> candidates = new ArrayList<>();
        GoalEntity current = goalPlannerService.getCurrentSubGoal();
        if (current != null) candidates.add(current);
        candidates.addAll(goalPlannerService.getRemainingGoals());
        for (GoalEntity g : candidates) {
            if (g == null || g.getStatus() != GoalStatus.ACTIVE) continue;
            if (g.getMetadata() == null
                    || !gate.equals(g.getMetadata().get(BlockerTypes.ORIGINATING_GATE_KEY))) {
                continue;
            }
            if (!retire.test(g)) continue;
            log.info("T4-DW: {} — marking {} work item [{}] SUBSUMED", reason, gate, g.getId());
            goalPlannerService.transitionGoal(g.getId(), GoalStatus.SUBSUMED,
                    "The " + gate + " observed the claim this item makes — no longer needed",
                    com.walpro.autobuilder.goal.SubsumptionReason.COVERED);
            retired++;
        }
        return retired;
    }

    private void retireActiveBuildFixSubgoals(String reason, java.util.function.Predicate<GoalEntity> retire) {
        GoalEntity current = goalPlannerService.getCurrentSubGoal();
        if (isActiveBuildFix(current) && retire.test(current)) {
            log.info("T1-DR: {} — marking current BUILD_FIX subgoal [{}] SUBSUMED", reason, current.getId());
            goalPlannerService.transitionGoal(current.getId(), GoalStatus.SUBSUMED,
                    "Build fixed — BUILD_FIX subgoal no longer needed",
                    com.walpro.autobuilder.goal.SubsumptionReason.COVERED);
        }
        for (GoalEntity g : goalPlannerService.getRemainingGoals()) {
            if (isActiveBuildFix(g) && retire.test(g)) {
                log.info("T1-DR: {} — marking remaining BUILD_FIX subgoal [{}] SUBSUMED", reason, g.getId());
                goalPlannerService.transitionGoal(g.getId(), GoalStatus.SUBSUMED,
                        "Build fixed — BUILD_FIX subgoal no longer needed",
                        com.walpro.autobuilder.goal.SubsumptionReason.COVERED);
            }
        }
    }

    /** True when the work item names the completion-edge check that raised it. */
    private boolean hasOriginatingGate(GoalEntity g) {
        return g != null && g.getMetadata() != null
                && g.getMetadata().get(BlockerTypes.ORIGINATING_GATE_KEY) != null;
    }

    private boolean isFromPackageGate(GoalEntity g) {
        return g != null && g.getMetadata() != null
                && BlockerTypes.GATE_PACKAGE.equals(g.getMetadata().get(BlockerTypes.ORIGINATING_GATE_KEY));
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

    /**
     * T4-DL: the fully-qualified class whose package cannot be obtained from ANY published
     * artifact, or null. Tri-state by construction — only a positive ABSENT answer counts, so an
     * unreachable repository leaves today's behaviour exactly as it was.
     */
    private String unobtainablePackageSymbol(
            com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification classification,
            String stderr, String symbolFromSummary) {
        if (coordinateSearchClient == null || classification == null) return null;
        if (classification.kind() != com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind.PACKAGE_DOES_NOT_EXIST) {
            return null;
        }
        // The package name comes from the stderr, not from Classification#missingArtifact — the
        // pattern-fallback path leaves that field null and only the adaptive classifier fills it,
        // so reading it here silently disabled the whole branch (caught by its own test).
        String pkg = classification.missingArtifact() != null && !classification.missingArtifact().isBlank()
                ? classification.missingArtifact()
                : com.walpro.autobuilder.core.build.BuildErrorClassifier
                        .extractToken(stderr, classification.kind()).orElse(null);
        // The symbol comes from the FULL stderr, not the picked summary line: when the picker
        // chooses the "package … does not exist" line, that line carries no symbol at all, and
        // the branch silently never fired (its own test caught this).
        String symbol = symbolFromSummary != null && !symbolFromSummary.isBlank()
                ? symbolFromSummary
                : extractMissingSymbol(stderr);
        if (pkg == null || symbol == null || symbol.isBlank()) return null;
        String fqn = pkg + "." + symbol;
        return coordinateSearchClient.publishesClass(fqn)
                == com.walpro.autobuilder.core.build.inject.CoordinateSearchClient.Availability.ABSENT
                ? fqn : null;
    }
}
