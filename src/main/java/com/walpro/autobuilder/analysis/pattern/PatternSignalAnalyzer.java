package com.walpro.autobuilder.analysis.pattern;

import com.walpro.autobuilder.analysis.problem.ProblemContext;
import com.walpro.autobuilder.analysis.problem.ProblemEntry;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class PatternSignalAnalyzer {

    public CycleContextData analyze(CycleContextData ctx) {
        if (ctx == null) return null;

        CycleContextData out = ctx;

        out = applyFingerprintRepetition(out);

        // If the scope-guard already told us “planner should reconcile”, do not
        // add extra “confidence/stagnation” signals that may mislead orchestration.
        if (out.hasSignal(BasicSignals.SCOPE_VERDICT_OUT_OF_SCOPE)) {
            return out;
        }

        out = applyNoMeaningfulChange(out);
        out = applyThrash(out);
        out = applyFocusDrift(out);

        return out;
    }

    private CycleContextData applyFingerprintRepetition(CycleContextData ctx) {
        String fp = ctx.lastEvolutionFingerprint();
        if (fp == null || fp.isBlank()) return ctx;

        List<String> recent = ctx.recentEvolutionFingerprints();
        if (recent == null || recent.isEmpty()) return ctx;

        long count = recent.stream().filter(fp::equals).count();
        if (count < 2) return ctx; // needs repetition, not just presence

        return ctx.withSignal(Signal.of(BasicSignals.REPETITION_DETECTED, 1.0, 0.15, "pattern:fingerprint-repeat"));
    }

    private CycleContextData applyNoMeaningfulChange(CycleContextData ctx) {
        if (ctx.projectDiff() == null) return ctx;

        boolean noFilesChanged = ctx.projectDiff().changedFiles() == null || ctx.projectDiff().changedFiles().isEmpty();
        boolean nothingDeleted = ctx.projectDiff().deletedPaths() == null || ctx.projectDiff().deletedPaths().isEmpty();

        if (noFilesChanged && nothingDeleted) {
            // reuse existing stagnation framing until you introduce a dedicated signal type
            return ctx.withSignal(Signal.of(BasicSignals.STAGNATION, 0.7, 0.20, "pattern:empty-diff"));
        }

        return ctx;
    }

    private CycleContextData applyThrash(CycleContextData ctx) {
        var last = ctx.lastEvaluationOutcome();
        var now = ctx.evaluationOutcome();
        if (last == null || now == null) return ctx;

        MutationAcceptanceState a = last.mutationState();
        MutationAcceptanceState b = now.mutationState();
        if (a == null || b == null) return ctx;

        boolean flipped = (a == MutationAcceptanceState.ACCEPTED && b == MutationAcceptanceState.REJECTED)
                || (a == MutationAcceptanceState.REJECTED && b == MutationAcceptanceState.ACCEPTED);

        if (!flipped) return ctx;

        // “thrash” = flip + stagnation signal or stagnating goal progress
        boolean stagnating = ctx.goalProgress() == GoalProgressState.STAGNATING
                || ctx.hasStrongSignal(BasicSignals.STAGNATION, 0.3);

        if (!stagnating) return ctx;

        return ctx.withSignal(Signal.of(BasicSignals.LOW_CONFIDENCE, 0.8, 0.10, "pattern:thrash"));
    }

    private CycleContextData applyFocusDrift(CycleContextData ctx) {
        // If problem context references paths, prefer changes touching those.
        ProblemContext pc = ctx.problemContext();
        if (pc == null || pc.open() == null || pc.open().isEmpty()) return ctx;
        if (ctx.projectDiff() == null || ctx.projectDiff().changedFiles() == null) return ctx;

        Set<String> referencedPaths = referencedPaths(pc.open());
        if (referencedPaths.isEmpty()) return ctx;

        Set<String> changedPaths = ctx.projectDiff().changedFiles().keySet();

        // Only fire if a majority of referenced paths were not touched.
        // A single missed path out of many open problems is not actionable signal.
        long touchedCount = referencedPaths.stream().filter(changedPaths::contains).count();
        if (touchedCount * 2 >= referencedPaths.size()) return ctx;

        return ctx.withSignal(Signal.of(BasicSignals.LOW_CONFIDENCE, 0.6, 0.12, "pattern:focus-drift"));
    }

    private static Set<String> referencedPaths(List<ProblemEntry> open) {
        Set<String> paths = new HashSet<>();
        for (ProblemEntry p : open) {
            if (p == null || p.keywords() == null) continue;
            for (String kw : p.keywords()) {
                if (kw != null && kw.startsWith("path:")) {
                    String path = kw.substring("path:".length()).trim();
                    if (!path.isBlank()) paths.add(path);
                }
            }
        }
        return paths;
    }
}