package com.walpro.autobuilder.analysis.pattern;

import com.walpro.autobuilder.analysis.problem.ProblemContext;
import com.walpro.autobuilder.analysis.problem.ProblemEntry;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.ChannelRequest;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.DerivedFacts;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class PatternSignalAnalyzer {

    // T5-I: optional so unit tests constructing the analyzer directly need no logger stub.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.observability.behaviour.BehaviourLogger behaviourLogger;

    public void setBehaviourLogger(com.walpro.autobuilder.observability.behaviour.BehaviourLogger logger) {
        this.behaviourLogger = logger;
    }

    // T5-I: fired-only emission — quiet evaluations stay silent.
    private void emitPatternDetected(CycleContextData ctx, String rule, String evidence, String signalEmitted) {
        if (behaviourLogger != null) {
            behaviourLogger.patternDetected(ctx != null ? ctx.cycleName() : null,
                    "PatternSignalAnalyzer:" + rule, evidence, signalEmitted);
        }
    }

    public CycleContextData analyze(CycleContextData ctx) {
        if (ctx == null) return null;

        CycleContextData out = ctx;

        out = applyFingerprintRepetition(out);

        // If the scope-guard already told us “planner should reconcile”, do not
        // add extra “confidence/stagnation” signals that may mislead orchestration.
        if (DerivedFacts.scopeOutOfScope(out)) {
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

        emitPatternDetected(ctx, "fingerprint-repeat",
                "evolution fingerprint repeated " + count + "x in the recent window", "REPETITION_DETECTED");
        return ctx.issueRequest(ChannelRequest.of(BasicSignals.REPETITION_DETECTED, 1.0, "pattern:fingerprint-repeat", ChannelRequest.horizonFromDecay(1.0, 0.15)));
    }

    private CycleContextData applyNoMeaningfulChange(CycleContextData ctx) {
        // Only meaningful when evolution actually ran this cycle — otherwise projectDiff is stale
        // from the previous evolution cycle and would produce false STAGNATION reinforcement in
        // planner-only or analysis-only cycles.
        if (!ctx.evolutionRanThisCycle()) return ctx;
        if (ctx.projectDiff() == null) return ctx;

        boolean noFilesChanged = ctx.projectDiff().changedFiles() == null || ctx.projectDiff().changedFiles().isEmpty();
        boolean nothingDeleted = ctx.projectDiff().deletedPaths() == null || ctx.projectDiff().deletedPaths().isEmpty();

        if (noFilesChanged && nothingDeleted) {
            emitPatternDetected(ctx, "empty-diff",
                    "evolution ran this cycle and produced an empty diff", "STAGNATION");
            // reuse existing stagnation framing until you introduce a dedicated signal type
            return ctx.issueRequest(ChannelRequest.of(BasicSignals.STAGNATION, 0.7, "pattern:empty-diff", ChannelRequest.horizonFromDecay(0.7, 0.20)));
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
                || ctx.hasStrongRequest(BasicSignals.STAGNATION, 0.3);

        if (!stagnating) return ctx;

        emitPatternDetected(ctx, "thrash",
                "evaluation outcome flipped " + a + "->" + b + " while stagnating", "LOW_CONFIDENCE");
        return ctx.issueRequest(ChannelRequest.of(BasicSignals.LOW_CONFIDENCE, 0.8, "pattern:thrash", ChannelRequest.horizonFromDecay(0.8, 0.10)));
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

        emitPatternDetected(ctx, "focus-drift",
                touchedCount + "/" + referencedPaths.size() + " problem-referenced paths touched by the diff",
                "LOW_CONFIDENCE");
        return ctx.issueRequest(ChannelRequest.of(BasicSignals.LOW_CONFIDENCE, 0.6, "pattern:focus-drift", ChannelRequest.horizonFromDecay(0.6, 0.12)));
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