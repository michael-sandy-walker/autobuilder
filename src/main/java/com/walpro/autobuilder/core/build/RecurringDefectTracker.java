package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.build.inject.FixProposal;
import com.walpro.autobuilder.core.mutation.BlockerExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T2-EF: run-level accounting of recurring build-failure shapes, decoupled from subgoal identity.
 *
 * <p>The T2-DD same-failure streak ({@code ContextLineageTracker.sameFailureStreak}) resets on a
 * merge or a subgoal change. A codebase-level recurring defect (a missing or misplaced symbol)
 * persists on disk while the planner reshuffles subgoals above it, so the recurrence spans subgoal
 * boundaries the per-subgoal streak discards. In run {@code 2026-06-13_05:30→06:16} the streak
 * fired {@code BUILD_FIX_LOCUS_STUCK} 0× despite {@code ErrorResponse} ×145 / {@code LoanService}
 * ×184, because 23 subgoal changes + 27 merges reset it ≈ every 1.5 cycles.
 *
 * <p>This tracker is fed from the <em>post-inner-loop residual</em> probe outcome (what
 * {@code CompileProbeService.probe} returns after {@code tryInjectAndReProbe} has stripped what it
 * deterministically could). That de-noises the diagnosis: a solved-but-recurring fix — the
 * {@code symbol-import:local} import re-derived &amp; discarded 49× in the run — does not
 * masquerade as the dominant defect, because the residual shows the <em>true</em> remaining
 * problem (the class's existence/placement), not the import the inner loop already handles.
 *
 * <p>Dominance trigger (relative, no fixed N — see {@code feedback_relative_thresholds}): a shape
 * is dominant when it is a <em>strict majority</em> of the run's residual failed probes (one defect
 * accounting for more than all the others combined — structural, not an invented magnitude), it has
 * recurred under {@code ≥2} distinct subgoals (structural "spans subgoals"), and it is still
 * appearing recently (seen within a window proportional to the failure diversity, so a resolved
 * defect ages out instead of firing forever).
 *
 * <p>State is process-local, like the T2-DY {@code FixAttemptLedger}: the goal is to steer the
 * within-run loop, not cross-run learning.
 */
@Slf4j
@Component
public class RecurringDefectTracker {

    private final Map<String, Shape> shapes = new ConcurrentHashMap<>();
    private long failedProbeTotal = 0;
    private long sequence = 0;

    /**
     * Observe one probe's residual outcome. On COMPILE_ERROR the failure shape is tallied under the
     * current subgoal. On COMPILE_OK the fixes that greened the build are recorded as "had a green
     * fix" for their token, so a later redirection can say the deterministic fix is correct and the
     * gap lies elsewhere (the standing-fix knowledge that enriches the hint, not a pre-apply path).
     */
    public synchronized void observe(BuildOutcome outcome, List<FixProposal> appliedFixes, String subgoalId) {
        if (outcome == null) return;
        if (outcome.result() == BuildResult.COMPILE_OK) {
            recordGreenFixes(appliedFixes);
            return;
        }
        if (outcome.result() != BuildResult.COMPILE_ERROR) return; // TIMEOUT/SKIPPED carry no shape

        String key = failureKey(outcome.stderrTail());
        if (key == null) return;
        sequence++;
        failedProbeTotal++;
        Shape shape = shapes.computeIfAbsent(key, Shape::new);
        shape.count++;
        shape.lastSeenSeq = sequence;
        if (subgoalId != null && !subgoalId.isBlank()) {
            shape.subgoalIds.add(subgoalId);
        }
    }

    private void recordGreenFixes(List<FixProposal> appliedFixes) {
        if (appliedFixes == null) return;
        for (FixProposal p : appliedFixes) {
            if (p == null || p.record() == null) continue;
            String token = p.record().missingToken();
            String kind = p.record().kind() != null ? p.record().kind().name() : null;
            if (token == null || kind == null) continue;
            Shape shape = shapes.get(kind + "|" + token);
            if (shape != null) shape.hadGreenFix = true;
        }
    }

    /**
     * Returns the dominant recurring defect when the relative trigger is met, else empty.
     * Strict majority of residual failed probes + spans ≥2 subgoals + seen recently.
     */
    public synchronized Optional<DominantDefect> dominantDefect() {
        if (failedProbeTotal == 0 || shapes.isEmpty()) return Optional.empty();

        Shape top = null;
        for (Shape s : shapes.values()) {
            if (top == null || s.count > top.count) top = s;
        }
        if (top == null) return Optional.empty();

        double share = (double) top.count / failedProbeTotal;
        boolean majority = share > 0.5;                       // one defect > all others combined
        boolean spansSubgoals = top.subgoalIds.size() >= 2;    // genuinely cross-subgoal
        // Recency: seen within a window proportional to the failure diversity, so a resolved defect
        // ages out. Window = number of distinct shapes (≥1) — relative to the run's own spread.
        boolean recent = top.lastSeenSeq >= sequence - shapes.size();

        if (!(majority && spansSubgoals && recent)) return Optional.empty();
        return Optional.of(new DominantDefect(top.key, share, top.subgoalIds.size(), top.hadGreenFix));
    }

    /**
     * Failure identity for a failed probe: {@code errorKind|token} when the diagnostic names a
     * symbol, else {@code errorKind|normalisedShape}. Mirrors
     * {@code ContextLineageTracker.probeFailureKey} so the run-level tally and the per-subgoal
     * streak key the same defects.
     */
    static String failureKey(String stderr) {
        if (stderr == null || stderr.isBlank()) return null;
        BuildErrorClassifier.Classification c = BuildErrorClassifier.classify(stderr);
        if (c == null || c.kind() == null) return null;
        String kind = c.kind().name();
        String token = BuildErrorClassifier.extractToken(stderr, c.kind()).orElse(null);
        if (token != null) return kind + "|" + token;
        String line = BuildStderrLines.pickBestSingleLine(stderr);
        if (line == null || line.isBlank()) return null;
        return kind + "|" + BlockerExtractor.normaliseShape(line);
    }

    /** Drops all state. For tests; production state is per-process and naturally per-run. */
    public synchronized void clear() {
        shapes.clear();
        failedProbeTotal = 0;
        sequence = 0;
    }

    /**
     * The dominant recurring defect. {@code key} is {@code errorKind|token}; {@code missingToken}
     * is the token half when present (the symbol the redirection should target). {@code hadGreenFix}
     * is true when a deterministic fix greened a probe for this token — the import is right, the
     * gap is the symbol's existence/placement.
     */
    public record DominantDefect(String key, double share, int subgoalSpan, boolean hadGreenFix) {
        public String errorKind() {
            int i = key.indexOf('|');
            return i < 0 ? key : key.substring(0, i);
        }
        public String missingToken() {
            int i = key.indexOf('|');
            return i < 0 ? null : key.substring(i + 1);
        }
    }

    private static final class Shape {
        final String key;
        long count = 0;
        long lastSeenSeq = 0;
        boolean hadGreenFix = false;
        final Set<String> subgoalIds = new HashSet<>();

        Shape(String key) {
            this.key = key;
        }
    }
}
