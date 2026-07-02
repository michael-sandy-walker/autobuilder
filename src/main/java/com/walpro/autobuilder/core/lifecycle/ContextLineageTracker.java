package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.model.MergeTrace;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class ContextLineageTracker {

    /** T2-EF: optional run-level recurring-defect tracker. Null in pure-unit tests that construct
     *  the tracker directly; set by Spring in production and via the setter in wiring tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.core.build.RecurringDefectTracker recurringDefectTracker;

    public void setRecurringDefectTracker(com.walpro.autobuilder.core.build.RecurringDefectTracker tracker) {
        this.recurringDefectTracker = tracker;
    }

    public CycleContextData enrichLineage(CycleContextData baseContext, CycleContextData previousContext) {
        CycleContextData enriched = enrichLineageCore(baseContext, previousContext);
        // T1-DU: emit BUILD_BROKEN whenever the codebase is effectively broken — either the
        // carried buildOutcome is COMPILE_ERROR, OR the previous cycle was rolled back
        // (T1-DZ: T1-DW restores buildOutcome to the previous OK value on rollback, so the
        // rollback evidence is the only cross-cycle signal that the codebase is broken in
        // the bootstrap-no-pom case). decayPerCycle=0.0 — must persist until COMPILE_OK is
        // observed or rollback evidence ages out (single-cycle TTL via lastRollback).
        com.walpro.autobuilder.core.build.BuildOutcome outcome = enriched.buildOutcome();
        boolean buildBroken =
                (outcome != null && outcome.result() == com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR)
                        || enriched.lastRollback() != null;
        if (buildBroken) {
            String source = (outcome != null && outcome.result() == com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR)
                    ? "buildOutcome"
                    : "lastRollback";
            enriched = enriched.withSignal(Signal.of(BasicSignals.BUILD_BROKEN, 1.0, 0.0, source));
        } else {
            enriched = enriched.withoutSignal(BasicSignals.BUILD_BROKEN);
        }

        // T2-DD: emit BUILD_FIX_LOCUS_STUCK when the consecutive same-failure streak reaches 2.
        // The streak is computed in enrichLineageCore from previousContext's failure evidence —
        // rollback or pre-merge probe rejection, the latter being the live channel since T1-EC.
        // Signal source carries "<errorKind>|<identity>|streak=<n>" so downstream
        // (BlockerSubgoalInjector, planner protocol) can render specific guidance.
        if (enriched.sameFailureStreak() >= 2 && enriched.sameFailureStreakKey() != null) {
            String source = enriched.sameFailureStreakKey() + "|streak=" + enriched.sameFailureStreak();
            enriched = enriched.withSignal(Signal.of(BasicSignals.BUILD_FIX_LOCUS_STUCK, 1.0, 0.0, source));
        } else {
            enriched = enriched.withoutSignal(BasicSignals.BUILD_FIX_LOCUS_STUCK);
        }

        // T2-EF: emit RECURRING_DEFECT_DOMINANT when a failure shape dominates the run's residual
        // failure mix across ≥2 distinct subgoals — the cross-subgoal recurrence the per-subgoal
        // streak above resets away. Consults the run-level RecurringDefectTracker (fed the residual
        // probe outcome). Source carries errorKind|token|share|subgoals for the log + downstream.
        if (recurringDefectTracker != null) {
            var dominant = recurringDefectTracker.dominantDefect();
            if (dominant.isPresent()) {
                var d = dominant.get();
                String source = d.key() + "|share=" + String.format(java.util.Locale.ROOT, "%.2f", d.share())
                        + "|subgoals=" + d.subgoalSpan();
                enriched = enriched.withSignal(Signal.of(BasicSignals.RECURRING_DEFECT_DOMINANT, 1.0, 0.0, source));
            } else {
                enriched = enriched.withoutSignal(BasicSignals.RECURRING_DEFECT_DOMINANT);
            }
        }
        return enriched;
    }

    private CycleContextData enrichLineageCore(CycleContextData baseContext, CycleContextData previousContext) {
        SameFailureStreak streak = resolveSameFailureStreak(baseContext, previousContext);
        return baseContext
                // If last cycle merged, clear both fingerprints so pendingFingerprint and
                // notYetEvaluated both resolve to false in the next cycle. Leaving
                // lastEvolutionFingerprint non-null while clearing lastEvaluatedEvolutionFingerprint
                // causes !equals(X, null) = true, which manufactures a spurious EVALUATE cycle
                // (hasCandidate=true, notYetEvaluated=true) even though there is nothing to merge.
                .withLastEvolutionFingerprint(
                        (previousContext != null && previousContext.merged()) || subgoalChanged(baseContext, previousContext)
                                ? null
                                : (previousContext != null ? previousContext.lastEvolutionFingerprint() : null)
                )
                .withLastEvaluatedEvolutionFingerprint(
                        (previousContext != null && previousContext.merged()) || subgoalChanged(baseContext, previousContext)
                                ? null
                                : (previousContext != null ? previousContext.lastEvaluatedEvolutionFingerprint() : null)
                )
                .withLastRecombinationFingerprint(previousContext != null ? previousContext.lastRecombinationFingerprint() : null)
                .withRecentEvolutionFingerprints(previousContext != null ? previousContext.recentEvolutionFingerprints() : List.of())
                .withPreviousAcceptedCycleName(previousContext != null ? previousContext.previousAcceptedCycleName() : null)
                .withParentCycleName(previousContext != null ? previousContext.cycleName() : null)
                .withRecombinationSourceCycleName(
                        previousContext != null && previousContext.hasSignal(BasicSignals.DIVERGENCE_REQUEST)
                                ? (previousContext.recombinationSourceCycleName() != null
                                        ? previousContext.recombinationSourceCycleName()
                                        : previousContext.cycleName())
                                : null
                )
                // T1-BO: reset lastEvolutionOutcome on subgoal change so Rule C
                // (consecutive-no-evolution) counts only within the same subgoal's lifetime.
                .withLastEvolutionOutcome(
                        subgoalChanged(baseContext, previousContext) ? null
                        : (previousContext != null ? previousContext.lastEvolutionOutcome() : null)
                )
                .withLastEvaluationOutcome(previousContext != null ? previousContext.lastEvaluationOutcome() : null)
                .withLastPlannerOutcome(previousContext != null ? previousContext.lastPlannerOutcome() : null)
                .withScoreHistoryByFile(previousContext != null ? previousContext.scoreHistoryByFile() : null)
                .withRecentDeltas(previousContext != null ? previousContext.recentDeltas() : null)
                .withLastAcceptedMutationFileCount(previousContext != null ? previousContext.lastAcceptedMutationFileCount() : 0)
                .withPlateauCycles(previousContext != null ? previousContext.plateauCycles() : 0)
                // T2-AT: reset streaks + warnings when the subgoal changes; otherwise carry forward.
                .withCriticalHeuristicStreaks(resolveStreaks(baseContext, previousContext))
                .withPersistentHeuristicWarnings(resolveWarnings(baseContext, previousContext))
                // T3-BR: carry the evaluator-repeat-retry counter forward only when the subgoal
                // is unchanged AND the previous evolution fingerprint is preserved (i.e. no merge,
                // no subgoal change, no fingerprint reset). The fingerprint reset above already
                // sets lastEvolutionFingerprint to null on those events; mirror that condition here.
                .withEvaluatorRepeatRetries(resolveEvaluatorRepeatRetries(baseContext, previousContext))
                // T4-AN-1: carry the build outcome forward unconditionally — it reflects the
                // codebase's compilation state, which persists across cycles regardless of
                // subgoal changes. MergeAgent overwrites it whenever a new build runs.
                .withBuildOutcome(previousContext != null ? previousContext.buildOutcome() : null)
                // T4-BD: carry the latest probe outcome forward. Like buildOutcome, reflects a
                // cross-cycle compilation snapshot — EvaluationAgent overwrites it whenever
                // CompileProbeService runs. Source of truth for the next cycle's evolution
                // prompt's --- BUILD RESULT --- + missing-symbol extractors when the post-merge
                // build hasn't run or last cleared.
                .withLastProbeOutcome(previousContext != null ? previousContext.lastProbeOutcome() : null)
                // T1-DQ: carry the build-fix attempt counter forward; reset on subgoal change or COMPILE_OK.
                .withConsecutiveBuildFixAttempts(resolveConsecutiveBuildFixAttempts(baseContext, previousContext))
                // T1-DX: carry the rollback evidence forward exactly one cycle so the next
                // cycle's planner / evolution prompts render a --- LAST CYCLE ROLLED BACK ---
                // block. Cleared on the cycle after that — single-cycle TTL prevents the LLM
                // from treating stale rollback info as the present state.
                .withLastRollback(resolveLastRollback(previousContext))
                // T1-DX: rolledBackThisCycle is per-cycle state — never carried forward.
                .withRolledBackThisCycle(false)
                // T4-BA: carry package-fragmentation rejection evidence forward exactly one
                // cycle (same single-cycle TTL shape as lastRollback) so the next evolution
                // prompt renders a --- PACKAGE FRAGMENTATION REJECTED --- block.
                .withPackageFragmentation(resolvePackageFragmentation(previousContext))
                .withPackageFragmentationThisCycle(false)
                // T2-DD: streak math derived from previousContext's failure evidence (rollback
                // or pre-merge probe rejection — the live channel since T1-EC).
                .withSameFailureStreakKey(streak.key())
                .withSameFailureStreak(streak.count())
                // T2-DT: carry author-error diagnostics forward only when the subgoal is
                // unchanged and the previous cycle did not merge. Subgoal change resets the
                // steering — author-errors are tied to the attempt's intent. A merge means
                // the implementation work landed; any pending diagnostics belonged to a
                // superseded mutation.
                .withLastAuthorErrors(resolveLastAuthorErrors(baseContext, previousContext))
                // T4-AN-5: carry the build-tool diagnostic forward one cycle, cleared on merge —
                // same lifecycle as lastAuthorErrors (a merge means the work landed; the diagnostic
                // described a superseded broken state).
                .withBuildDiagnostic(resolveBuildDiagnostic(baseContext, previousContext));
    }

    private static String resolveBuildDiagnostic(CycleContextData baseContext, CycleContextData previousContext) {
        if (previousContext == null) return null;
        if (previousContext.merged()) return null;
        return previousContext.buildDiagnostic();
    }

    private static int resolveEvaluatorRepeatRetries(CycleContextData baseContext, CycleContextData previousContext) {
        if (previousContext == null) return 0;
        if (subgoalChanged(baseContext, previousContext)) return 0;
        if (previousContext.merged()) return 0;
        return previousContext.evaluatorRepeatRetries();
    }

    private static boolean subgoalChanged(CycleContextData baseContext, CycleContextData previousContext) {
        return previousContext != null
                && !Objects.equals(previousContext.currentSubGoalId(), baseContext.currentSubGoalId());
    }

    private static Map<String, Integer> resolveStreaks(CycleContextData baseContext, CycleContextData previousContext) {
        if (previousContext == null || subgoalChanged(baseContext, previousContext)) {
            return new HashMap<>();
        }
        Map<String, Integer> prev = previousContext.criticalHeuristicStreaks();
        return prev != null ? new HashMap<>(prev) : new HashMap<>();
    }

    private static List<String> resolveWarnings(CycleContextData baseContext, CycleContextData previousContext) {
        if (previousContext == null || subgoalChanged(baseContext, previousContext)) {
            return List.of();
        }
        List<String> prev = previousContext.persistentHeuristicWarnings();
        return prev != null ? prev : List.of();
    }

    /**
     * T1-DX: carry rollback evidence forward exactly one cycle. The previous cycle's
     * {@code rolledBackThisCycle=true} is the only signal we keep — that means the very
     * next cycle (this one) sees the evidence and renders it in prompts. Two cycles after
     * the rollback, {@code previousContext.rolledBackThisCycle()} is false (carry-forward
     * already cleared it), so we drop the evidence.
     */
    private static com.walpro.autobuilder.core.merge.RolledBackReport resolveLastRollback(CycleContextData previousContext) {
        if (previousContext == null) return null;
        if (!previousContext.rolledBackThisCycle()) return null;
        return previousContext.lastRollback();
    }

    /**
     * T4-BA: carry package-fragmentation evidence forward exactly one cycle. The previous
     * cycle's {@code packageFragmentationThisCycle=true} is the gate — the very next cycle
     * sees the evidence and renders it; two cycles later the flag has been cleared by the
     * carry-forward above, so the evidence is dropped.
     */
    private static com.walpro.autobuilder.core.merge.PackageFragmentationReport resolvePackageFragmentation(CycleContextData previousContext) {
        if (previousContext == null) return null;
        if (!previousContext.packageFragmentationThisCycle()) return null;
        return previousContext.packageFragmentation();
    }

    /**
     * T2-DT: carry the previous cycle's classified author-errors forward into this cycle's
     * evolution prompt. Cleared on merge because a successful merge resolves the errors that
     * were on disk.
     *
     * <p>T1-EW: removed subgoal-shift suppression. The carrier describes a property of the
     * codebase (compile-probe errors at file:line), not of the goal hierarchy. Subgoal
     * changes — including the T1-DM BUILD_FIX injection that fires on the very rejection
     * the carrier describes — must not invalidate the steering signal.
     */
    private static com.walpro.autobuilder.core.mutation.CompileAuthorErrors resolveLastAuthorErrors(
            CycleContextData baseContext, CycleContextData previousContext) {
        if (previousContext == null) return null;
        if (previousContext.merged()) return null;
        return previousContext.lastAuthorErrors();
    }

    private static int resolveConsecutiveBuildFixAttempts(CycleContextData baseContext, CycleContextData previousContext) {
        if (previousContext == null) return 0;
        if (subgoalChanged(baseContext, previousContext)) return 0;
        com.walpro.autobuilder.core.build.BuildOutcome prev = previousContext.buildOutcome();
        if (prev != null && prev.result() == com.walpro.autobuilder.core.build.BuildResult.COMPILE_OK) return 0;
        return previousContext.consecutiveBuildFixAttempts();
    }

    /**
     * T2-DD: streak key is {@code "<errorKind>|<primaryRolledBackPath>"}. A "fresh" key arises
     * each time the previous cycle rolled back; null otherwise. The previous cycle's key is
     * compared against the carried prior key to detect streak continuation.
     */
    /** T2-DD streak state: key identifying the repeated failure + consecutive attempt count. */
    private record SameFailureStreak(String key, int count) {
        static final SameFailureStreak NONE = new SameFailureStreak(null, 0);
    }

    private static SameFailureStreak resolveSameFailureStreak(CycleContextData baseContext,
            CycleContextData previousContext) {
        if (previousContext == null) return SameFailureStreak.NONE;
        // A new subgoal means the old locus is no longer what the loop is attempting.
        if (subgoalChanged(baseContext, previousContext)) return SameFailureStreak.NONE;

        String key = null;
        if (previousContext.rolledBackThisCycle()) {
            key = rollbackKey(previousContext.lastRollback());
        } else {
            com.walpro.autobuilder.core.build.BuildOutcome probe = previousContext.lastProbeOutcome();
            boolean probeRanThisCycle = probe != null && probe.isFromCycle(previousContext.cycleName());
            if (probeRanThisCycle && !probe.failed()) {
                return SameFailureStreak.NONE; // candidate compiled — genuine reset
            }
            if (probeRanThisCycle) {
                key = probeFailureKey(probe);
            }
        }
        if (key == null) {
            if (previousContext.merged()) return SameFailureStreak.NONE;
            // No build evidence this cycle (planning/analysis cycle): carry — the streak
            // counts failed attempts, not wall-clock cycles.
            return new SameFailureStreak(previousContext.sameFailureStreakKey(),
                    previousContext.sameFailureStreak());
        }
        int count = key.equals(previousContext.sameFailureStreakKey())
                ? previousContext.sameFailureStreak() + 1
                : 1;
        return new SameFailureStreak(key, count);
    }

    /**
     * Failure identity for a failed pre-merge probe: errorKind plus the missing token when the
     * diagnostic names one — the same missing symbol recurring across different candidate files
     * is one logical defect (run 2026-06-13_01-34-29: `ErrorResponse` failed from three files).
     * Falls back to the normalised diagnostic shape for token-less kinds.
     */
    private static String probeFailureKey(com.walpro.autobuilder.core.build.BuildOutcome probe) {
        String stderr = probe.stderrTail();
        if (stderr == null || stderr.isBlank()) return null;
        var classification = com.walpro.autobuilder.core.build.BuildErrorClassifier.classify(stderr);
        if (classification == null || classification.kind() == null) return null;
        String kind = classification.kind().name();
        String token = com.walpro.autobuilder.core.build.BuildErrorClassifier
                .extractToken(stderr, classification.kind()).orElse(null);
        if (token != null) return kind + "|" + token;
        String line = com.walpro.autobuilder.core.build.BuildStderrLines.pickBestSingleLine(stderr);
        if (line == null || line.isBlank()) return null;
        return kind + "|" + com.walpro.autobuilder.core.mutation.BlockerExtractor.normaliseShape(line);
    }

    private static String rollbackKey(com.walpro.autobuilder.core.merge.RolledBackReport rb) {
        if (rb == null) return null;
        String kind = rb.errorKind() != null ? rb.errorKind().name() : "UNKNOWN";
        String path = null;
        if (rb.restoredPaths() != null) {
            for (String p : rb.restoredPaths()) {
                if (p != null && !p.isBlank() && p.endsWith(".java")) {
                    path = p;
                    break;
                }
            }
            if (path == null && !rb.restoredPaths().isEmpty()) {
                path = rb.restoredPaths().get(0);
            }
        }
        if (path == null) return null;
        return kind + "|" + path;
    }

    public MergeTrace emptyMergeTrace() {
        return new MergeTrace(new ArrayList<>(), new HashMap<>(), new HashMap<>());
    }
}
