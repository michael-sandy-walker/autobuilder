package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * T4-CY: the loop checks its own behaviour DURING the run, instead of only at the edge it may
 * never reach.
 *
 * <p><b>Why this exists.</b> Every correctness check the loop owns — package gate, boot gate,
 * criterion probes — hangs off {@code autoCompleteRootIfSatisfied}, which early-returns while any
 * subgoal is ACTIVE. Measured over 16 runs: <b>6 never emptied their backlog at all</b>, so no gate
 * ran even once and the artifact shipped unexamined (two of those scored 10/23 and 11/23 with a
 * booting application nobody looked at). Of the runs that did reach closure, the outcome was still
 * mixed. So the correctness signal was conditional on a state ~40% of runs never achieve.
 *
 * <p>The deeper form of the same problem: on 2026-07-28 a run produced a hand-verified <b>23/23
 * artifact and ended on a deadline</b>, because nothing in the loop could tell that artifact apart
 * from the 19/23 it had earlier declared GOAL_FULFILLED. A loop that cannot observe its own output
 * cannot converge on it; it can only arrive by luck. Three of sixteen runs scored ≥22, and no
 * process metric predicts which — the distribution has to move, and self-correction is the only
 * mechanism that moves it.
 *
 * <p><b>What it does.</b> Every {@code interval} cycles, when the artifact is packageable, it boots
 * it and asks the specification's own stated behaviours (via the criterion probes). Contradictions
 * come back as a work item describing the defect — {@code "POST /members → 400, but the running
 * application answered 201"} — which is something the loop can FIX, unlike a gate verdict, which
 * it can only be blocked by.
 *
 * <p><b>Cost, measured before building:</b> package gate median 2.2 s, boot gate median 2.6 s
 * (n=51/23 across a day of runs) against a ~60 s cycle — under 10%, and only on the interval.
 *
 * <p><b>What this is NOT.</b> It is not "run the gates more often": that hypothesis was tested
 * against the corpus and REFUTED — the runs with the most closure attempts scored worst, because
 * repeated blocking is a symptom of not converging. This never blocks anything. It observes, and
 * only speaks when the running artifact contradicts a stated requirement.
 */
@Slf4j
@Component
public class BehaviouralSelfCheck {

    /**
     * Cycles between observations. Deliberately not 1 — the artifact needs time to change.
     *
     * <p>Was 8, measured too coarse: run {@code 2026-07-28_21-39-59} ran 41 cycles and got only
     * FOUR looks (cycles 8, 16, 25, 33), and the tree happened to be mid-repair at every one, so
     * the check abstained four times and observed nothing all run. Four samples is too few to land
     * in a healthy window. At 4 the same run gets ~10 looks for well under 1% of its wall clock.
     */
    @Value("${autobuilder.build.self-check.interval-cycles:4}")
    private int intervalCycles = 4;

    /**
     * OFF by default as of 2026-07-29 — the mechanism is correct and its COST is not.
     *
     * <p>Run {@code 2026-07-29_03-23-43}: 11 requirements (the spec-derived fix worked), and the
     * two observations still took <b>19.7 and 14.9 minutes</b>, tripping the LLM circuit breaker
     * and leaving 23 cycles / 14-23. The time is not in booting or probing — it is in
     * probe-SYNTHESIS calls that TIME OUT: two failures roughly ten minutes apart, because
     * {@code LLMClient} escalates its read timeout across retries and this check makes model calls
     * synchronously inside the cycle, competing with the loop's own usage of the same local model.
     *
     * <p>Re-enable only once probe synthesis is DETERMINISTIC. Everything the common cases need is
     * already available without a model: {@code RequestBodySynthesizer} derives bodies from the
     * artifact's own model fields, {@code &#123;&#125;} violates any required-field constraint, an
     * unused large id gives the absent-resource case, and sending a valid body twice gives the
     * duplicate case. That path costs milliseconds and cannot time out.
     */
    @Value("${autobuilder.build.self-check.enabled:false}")
    private boolean enabled = false;

    /** Upper bound on requirements per observation — a runaway list must not consume a run. */
    static final int MAX_REQUIREMENTS = 15;

    private final PackageGate packageGate;
    private final BootGate bootGate;

    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    private int cyclesSinceCheck = 0;
    private String lastFinding = null;

    @Autowired
    public BehaviouralSelfCheck(PackageGate packageGate, BootGate bootGate) {
        this.packageGate = packageGate;
        this.bootGate = bootGate;
    }

    /**
     * Observes the artifact if the interval has elapsed. Returns a defect description when the
     * running application contradicts a stated requirement, otherwise empty.
     *
     * <p>Abstains — returns empty — whenever it cannot observe honestly: disabled, interval not
     * elapsed, no specification, no requirements to ask about, or the artifact not packageable
     * (that last one is the build's problem and already has its own machinery; this check has no
     * opinion on it). Repeating the same finding is suppressed, so a defect the loop has not yet
     * fixed does not re-inject every interval.
     */
    public Optional<String> observe(CycleContextData context, String specText,
                                    Supplier<List<String>> requirements) {
        if (!enabled) return Optional.empty();
        if (++cyclesSinceCheck < Math.max(1, intervalCycles)) return Optional.empty();
        cyclesSinceCheck = 0;

        if (specText == null || specText.isBlank()) return Optional.empty();
        List<String> reqs = requirements != null ? requirements.get() : List.of();
        if (reqs == null || reqs.isEmpty()) return Optional.empty();
        // Hard cap. Each requirement can cost an LLM probe-synthesis call on first sight, and on
        // run 2026-07-28_23-16-15 an uncapped list of 73/77 turned two observations into 29.5 and
        // 14.9 minutes of a 90-minute run and tripped the circuit breaker. The spec-derived set is
        // ~11, so this never binds in normal operation — it exists so a pathological list cannot
        // consume a run again.
        if (reqs.size() > MAX_REQUIREMENTS) {
            log.warn("T4-CY: {} requirements exceeds the cap of {} — observing the first {} only",
                    reqs.size(), MAX_REQUIREMENTS, MAX_REQUIREMENTS);
            reqs = reqs.subList(0, MAX_REQUIREMENTS);
        }

        String cycleId = context != null ? context.cycleName() : "self-check";

        // The loop already compiles every cycle, so its own probe result says whether packaging
        // could possibly succeed. Consulting it costs nothing and skips a `mvn package` that is
        // certain to fail — in run 2026-07-28_21-39-59 all four observations were spent exactly
        // that way. Only a KNOWN-broken build is skipped: an absent or unknown result still
        // attempts, so this can lose a look but never invent one.
        if (context != null && context.lastProbeOutcome() != null
                && context.lastProbeOutcome().result() == BuildResult.COMPILE_ERROR) {
            log.debug("T4-CY: last compile probe was COMPILE_ERROR — skipping this interval");
            return Optional.empty();
        }

        long start = System.currentTimeMillis();

        // Packageability is a precondition for observing, never a finding of this check: a tree
        // that cannot package is the build machinery's problem and it has its own gates.
        if (packageGate.verifyPackageable(cycleId).isPresent()) {
            log.info("T4-CY: artifact not packageable — no behavioural observation this interval");
            return Optional.empty();
        }

        Optional<String> finding = bootGate.verifyBootsAndServes(cycleId, specText, reqs);
        long elapsed = System.currentTimeMillis() - start;
        log.info("T4-CY: behavioural self-check ran (durationMs={}, requirements={}, finding={})",
                elapsed, reqs.size(), finding.isPresent() ? "yes" : "none");
        if (behaviourLogger != null) {
            behaviourLogger.buildFixAttempt(cycleId, "behavioural-self-check", 0, null, null,
                    null, List.of(), finding.isPresent() ? "DEFECT" : "CLEAN", elapsed);
        }
        if (finding.isEmpty()) {
            lastFinding = null;
            return Optional.empty();
        }
        if (finding.get().equals(lastFinding)) {
            log.info("T4-CY: same finding as last observation — not re-raising while it is still open");
            return Optional.empty();
        }
        lastFinding = finding.get();
        return finding;
    }

    public void setIntervalCycles(int intervalCycles) {
        this.intervalCycles = intervalCycles;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setBehaviourLogger(BehaviourLogger behaviourLogger) {
        this.behaviourLogger = behaviourLogger;
    }

    /** Resets the interval counter and the suppression memory (new run / new lineage). */
    public void reset() {
        cyclesSinceCheck = 0;
        lastFinding = null;
    }
}
