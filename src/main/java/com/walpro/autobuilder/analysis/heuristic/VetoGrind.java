package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.core.build.GrindDetector;
import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;
import com.walpro.autobuilder.core.stuck.Recurrence;
import com.walpro.autobuilder.core.stuck.StuckObservation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T6-T: a veto that keeps deciding without the work moving is a grind -- recognised as one, and from then on the
 * heuristic abstains on that finding: the evaluator's verdict stands unless another heuristic objects.
 *
 * <p><b>The gap, from the field.</b> Rule-extraction run 2026-10-18_19-33-28 (band 4): the suite passed from minute 41,
 * and for the next 80 minutes {@code MergeRegressionHeuristic} rejected 22 of 27 candidates, CRITICAL at 0.90, with
 * the same finding -- "SpecEndpointBehaviourQuery loses @Slf4j ... loses @Component" -- while the ticket asked for
 * exactly that removal and the candidates registered the class elsewhere. The grind detector watched builds, refused
 * writes, closures and test failures; nothing watched a verdict that decided the same thing again and again.
 *
 * <p><b>When it is a grind.</b> By {@link Recurrence} -- the run's own ratios, never a count: the finding decided at
 * least two thirds of the negotiations since it first did, over at least a quarter of the run so far.
 *
 * <p><b>What changes.</b> The finding abstains. Demoted to a vote it would still have decided field80: the only other
 * opinion on those candidates was LOW, so one 0.9 REJECTED vote carried every time. The finding is still logged with
 * each candidate, and the evaluator and every other heuristic still judge it.
 *
 * <p><b>Except a measured veto</b> ({@link EvaluationHeuristic#measured()}): stuck-questions run 2026-10-20_10-44-59
 * silenced {@code TestRegressionHeuristic} after 29 candidates that each broke a passing spec row, and the next such
 * candidate merged (27 -> 26 of 29). A measurement that keeps deciding is reported as a grind and keeps deciding.
 */
@Slf4j
@Component
@StateHolder(value = StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "per-run record of this run's own verdicts, as the grind detector; a restored run starts it afresh")
public class VetoGrind {

    /** The longest finding kept: the rule, and what it found, without the paths and the advice after it. */
    static final int FINDING_CHARS = 200;

    private final Recurrence recurrence;
    private final Set<String> yielded = ConcurrentHashMap.newKeySet();
    /** Measured findings already reported as a grind: reported once, never yielded. */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    @Autowired(required = false)
    private GrindDetector grindDetector;

    public VetoGrind() {
        this(Recurrence.ofThisRun());
    }

    VetoGrind(Recurrence recurrence) {
        this.recurrence = recurrence;
    }

    /** What a CRITICAL opinion found, as one line that is the same across candidates that lose the same thing. */
    static String finding(HeuristicOpinion o) {
        String r = o.rationale() == null ? "" : o.rationale().replaceAll("\\s*\\([^)]*\\)", "").replaceAll("\\s+", " ").strip();
        int end = r.indexOf(". ");
        if (end > 0) r = r.substring(0, end);
        if (r.length() > FINDING_CHARS) r = r.substring(0, FINDING_CHARS);
        return o.name() + ": " + r;
    }

    /**
     * One negotiation was decided: by {@code finding}'s veto, or by none (null). A {@code measured} veto (see
     * {@link EvaluationHeuristic#measured()}) is reported as a grind the same way but never yields.
     */
    public void decided(String cycleId, String finding, boolean measured) {
        decided(cycleId, finding, measured, System.currentTimeMillis());
    }

    void decided(String cycleId, String finding, long at) {
        decided(cycleId, finding, false, at);
    }

    synchronized void decided(String cycleId, String finding, boolean measured, long at) {
        boolean persistent = recurrence.observe(finding, at);
        if (!persistent || !(measured ? reported : yielded).add(finding)) return;
        if (measured) {
            log.warn("T6-T: GRIND -- one measured veto keeps deciding while the work does not move; it keeps deciding, since"
                    + " the candidates keep doing what it measured: {}", finding);
        } else {
            log.warn("T6-T: GRIND -- one veto keeps deciding while the work does not move; from now on it abstains, and the"
                    + " evaluator's verdict stands unless another heuristic objects: {}", finding);
        }
        if (grindDetector != null) grindDetector.observe(cycleId, StuckObservation.Source.VETO, finding);
    }

    /** Whether {@code finding}'s veto was recognised as a grind and now abstains. */
    public boolean yielded(String finding) {
        return finding != null && yielded.contains(finding);
    }
}
