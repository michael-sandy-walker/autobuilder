package com.walpro.autobuilder.analysis.pattern;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.ChannelRequest;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.springframework.stereotype.Component;

/**
 * Evolution ran this cycle and changed nothing — raise stagnation.
 *
 * <p>T5-BH (2026-07-25): this was {@code PatternSignalAnalyzer}, a class holding four unrelated
 * single-cycle predicates under a name that promised pattern analysis it never performed (it read
 * cycle-state fields, not signals, and emitted a fixed signal name per rule). Replay over 237
 * recorded runs showed the other three rules had <b>never fired</b> while this one accounted for
 * every one of the class's 92 firings, and each of their output signals already has live emitters
 * elsewhere. They were deleted rather than kept as capability-shaped decoration:
 *
 * <ul>
 *   <li>{@code fingerprint-repeat} → {@code REPETITION_DETECTED}, which {@code EvolutionAgent}
 *       emits from three sites that see the fingerprint firsthand;</li>
 *   <li>{@code thrash} (evaluation state flipped while stagnating) → {@code LOW_CONFIDENCE}, also
 *       emitted by {@code EvaluationAgent} and the interpretation parser. Replay found the flip
 *       itself is common (3410 across 11503 decisions in 233/237 runs), so the rule was unreachable
 *       through its stagnation conjunct, not through rarity;</li>
 *   <li>{@code focus-drift} → {@code LOW_CONFIDENCE}, same owners.</li>
 * </ul>
 *
 * <p>The surviving rule is deliberately narrow: it fires only when evolution actually ran, because
 * otherwise {@code projectDiff} is stale from an earlier evolution cycle and planner-only or
 * analysis-only cycles would reinforce stagnation falsely.
 */
@Component
public class EmptyDiffDetector {

    // T5-I: optional so unit tests constructing the detector directly need no logger stub.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.observability.behaviour.BehaviourLogger behaviourLogger;

    public void setBehaviourLogger(com.walpro.autobuilder.observability.behaviour.BehaviourLogger logger) {
        this.behaviourLogger = logger;
    }

    public CycleContextData detect(CycleContextData ctx) {
        if (ctx == null) return null;
        if (!ctx.evolutionRanThisCycle()) return ctx;
        if (ctx.projectDiff() == null) return ctx;

        boolean noFilesChanged = ctx.projectDiff().changedFiles() == null || ctx.projectDiff().changedFiles().isEmpty();
        boolean nothingDeleted = ctx.projectDiff().deletedPaths() == null || ctx.projectDiff().deletedPaths().isEmpty();
        if (!noFilesChanged || !nothingDeleted) return ctx;

        if (behaviourLogger != null) {
            behaviourLogger.patternDetected(ctx.cycleName(), "EmptyDiffDetector",
                    "evolution ran this cycle and produced an empty diff", "STAGNATION");
        }
        return ctx.issueRequest(ChannelRequest.of(BasicSignals.STAGNATION, 0.7, "pattern:empty-diff",
                ChannelRequest.horizonFromDecay(0.7, 0.20)));
    }
}
