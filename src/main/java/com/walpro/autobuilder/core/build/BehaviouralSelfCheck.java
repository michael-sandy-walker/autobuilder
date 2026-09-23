package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.checkpoint.CheckpointableState;
import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;

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
@StateHolder(value = StateBucket.CAPTURE,
        note = "the interval counter decides which cycle observes the running artifact")
public class BehaviouralSelfCheck implements CheckpointableState {


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
     * Switched OFF 2026-07-29 on cost, and back ON the same day once that cost was removed.
     *
     * <p>The disabling was not about the mechanism, which had already found two real defects. Run
     * {@code 2026-07-29_03-23-43} carried only 11 requirements and its two observations still took
     * <b>19.7 and 14.9 minutes</b>, tripping the LLM circuit breaker and leaving 23 cycles / 14-23.
     * The time was never in booting or probing: probe SYNTHESIS made model calls synchronously
     * inside the cycle, competing with the loop's own use of the same local model, and
     * {@code LLMClient} escalates its read timeout across retries — so a single call could block
     * for minutes.
     *
     * <p>{@link DeterministicProbeSynthesizer} now builds those sequences from the artifact's own
     * sources in milliseconds, with the model path off by default. An observation therefore costs a
     * package gate, a boot, and a handful of HTTP requests — the budget this check was designed
     * against and measured for before it was built.
     */
    @Value("${autobuilder.build.self-check.enabled:true}")
    private boolean enabled = true;

    /**
     * T2-FL: the bound on an observation is a TIME IT MAY SPEND, charged against the cost this run
     * has actually measured — not a count of requirements.
     *
     * <p><b>Why the count had to go.</b> It was 15, chosen when a requirement cost a model call:
     * an uncapped list of 73/77 turned two observations into 29.5 and 14.9 minutes. Deterministic
     * synthesis then made a requirement cost about 180 ms — measured on run
     * {@code 2026-09-23_02-16-41}, {@code durationMs=2705} for 15 — so the same number came to
     * mean something roughly 140x stricter than the budget it was chosen to express, and nothing
     * announced the change. <b>A count is a proxy for cost, and a proxy whose exchange rate moves
     * is a constant that quietly redefines itself</b> ([[feedback-relative-thresholds]]: limits
     * relative to the run's environment, constants only for measurable environmental facts).
     *
     * <p>A time budget is such a fact, and it stays true across boxes: a loaded machine measures a
     * higher per-requirement cost and admits fewer requirements for the same wall clock, which is
     * the behaviour a count could only approximate on the machine it was tuned on. Same arithmetic
     * {@code RootConvergenceTracker.budgetAllowsAnotherWindow} already uses — spend against
     * this run's own measured pace rather than against a number someone picked.
     */
    @Value("${autobuilder.build.self-check.requirement-budget-ms:20000}")
    private long requirementBudgetMs = 20_000;

    /**
     * Backstop against pathology only, governing the observations before any cost has been
     * measured — never the operating limit. The original 15 is what this must not silently
     * become again.
     */
    static final int MAX_REQUIREMENTS = 200;

    /**
     * What one requirement cost this run, from the last completed observation. Zero until one has
     * been measured, which is the only state the backstop above governs.
     */
    private long measuredPerRequirementMs;


    private final PackageGate packageGate;
    private final BootGate bootGate;

    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    private int cyclesSinceCheck = 0;
    private String lastFinding = null;

    // T4-DQ: the interval counter decides which cycle looks at the running artifact, and the
    // last finding suppresses a repeat of the same one. Both are relative to the tree a rewind
    // replaces.
    public record Snapshot(int cyclesSinceCheck, String lastFinding) {}

    @Override
    public String stateKey() {
        return "behaviouralSelfCheck";
    }

    @Override
    public Object captureState() {
        return new Snapshot(cyclesSinceCheck, lastFinding);
    }

    @Override
    public void restoreState(com.fasterxml.jackson.databind.JsonNode state,
                             com.fasterxml.jackson.databind.ObjectMapper mapper) {
        cyclesSinceCheck = 0;
        lastFinding = null;
        if (state == null || state.isNull()) return;
        Snapshot snapshot = mapper.convertValue(state, Snapshot.class);
        cyclesSinceCheck = snapshot.cyclesSinceCheck();
        lastFinding = snapshot.lastFinding();
    }

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
    /**
     * T4-DW: what this check saw, not only what it wants fixed.
     *
     * <p>{@code finding} is exactly what {@link #observe} returns — the defect to raise as work,
     * suppressed while the same one is still open. {@code observation} is the gate's positive
     * reading, which a caller needs to RETIRE work the artifact no longer owes. The two are
     * deliberately separate: an abstained interval has neither, and a clean observation has the
     * second without the first.
     */
    public record SelfCheckResult(Optional<String> finding, BootGate.BootObservation observation) {
        static SelfCheckResult none() {
            return new SelfCheckResult(Optional.empty(), BootGate.BootObservation.abstained());
        }
    }

    public Optional<String> observe(CycleContextData context, String specText,
                                    Supplier<List<String>> requirements) {
        return observeWithEvidence(context, specText, requirements).finding();
    }

    public SelfCheckResult observeWithEvidence(CycleContextData context, String specText,
                                    Supplier<List<String>> requirements) {
        if (!enabled) return SelfCheckResult.none();
        if (++cyclesSinceCheck < Math.max(1, intervalCycles)) return SelfCheckResult.none();

        // The interval is deliberately NOT reset here. A cheap abstention below — a broken build, an
        // empty requirement list — must not consume a whole interval: run 2026-07-28_21-39-59 got
        // four looks in 41 cycles and the tree was mid-repair at every one, so the check abstained
        // four times and observed nothing all run. Leaving the counter at the threshold makes the
        // next cycle retry, so the check waits for a moment it can actually use rather than for a
        // fixed number of cycles. It is reset only where an observation is genuinely attempted,
        // because that is the part that costs a package build and a boot.
        if (specText == null || specText.isBlank()) return abstained(context, "no specification text");
        List<String> reqs = requirements != null ? requirements.get() : List.of();
        if (reqs == null || reqs.isEmpty()) {
            return abstained(context, "the specification states no probeable requirements");
        }
        // T2-FL: admit what this run's own measured cost says the budget buys. The count this
        // replaced was sized against a model call per requirement (73/77 requirements once cost
        // 29.5 and 14.9 minutes) and kept its number after deterministic synthesis made a
        // requirement ~180 ms, so it had come to ration a normal set rather than stop a runaway
        // one. Before any observation has been timed there is no measurement to spend against, and
        // the pathology backstop stands in.
        //
        // The caller orders claim-bearing requirements first, so a trim here drops the ones no
        // probe could have judged before it drops a question that has an answer.
        int admissible = admissibleRequirements();
        if (reqs.size() > admissible) {
            log.warn("T4-CY: {} requirements is more than the {} ms budget buys at this run's measured {} ms each — observing the first {}",
                    reqs.size(), requirementBudgetMs,
                    measuredPerRequirementMs > 0 ? measuredPerRequirementMs : "(unmeasured)", admissible);
            reqs = reqs.subList(0, admissible);
        }

        String cycleId = context != null ? context.cycleName() : "self-check";

        // The loop already knows whether the tree ON DISK compiles, so consulting that costs
        // nothing and skips a `mvn package` that is certain to fail — in run 2026-07-28_21-39-59
        // all four observations were spent exactly that way. Only a KNOWN-broken tree is skipped:
        // an absent or unknown result still attempts, so this can lose a look but never invent one.
        //
        // The authority is the post-merge build state, NOT the last compile probe. A probe judges
        // a STAGED candidate, and a rejected one is rolled back — its COMPILE_ERROR describes a
        // tree that no longer exists. Run 2026-09-23_20-20-50 skipped every look from 20:33 on
        // because six rejected candidates failed to compile, while every one of those cycles ended
        // with the merged tree compiling; the observation that could have promoted a leaf was
        // withheld on the answer to someone else's question.
        if (context != null && context.buildOutcome() != null
                && context.buildOutcome().result() == BuildResult.COMPILE_ERROR) {
            return abstained(context, "the tree on disk does not compile (last post-merge build was COMPILE_ERROR)");
        }

        // From here on the check spends real time (a package build, a boot, the probe requests), so
        // this is the point the interval restarts from.
        cyclesSinceCheck = 0;
        long start = System.currentTimeMillis();

        // Packageability is a precondition for observing, never a finding of this check: a tree
        // that cannot package is the build machinery's problem and it has its own gates.
        if (packageGate.verifyPackageable(cycleId).isPresent()) {
            return abstained(context, "the artifact is not packageable");
        }

        BootGate.BootObservation observation =
                bootGate.observeBootsAndServes(cycleId, specText, reqs, false);
        Optional<String> finding = observation.refutation()
                .map(com.walpro.autobuilder.goal.ClaimRefutation::description);
        long elapsed = System.currentTimeMillis() - start;
        // T6-J: deliberately NOT recorded as its own category. This check's elapsed time is
        // almost entirely the packageGate and bootGate calls above, both of which record
        // themselves — a "self-check" category would overlap them and the readout would sum to
        // more than the run. An instrument that double-counts is worse than one that omits.
        // T2-FL, CORRECTED after the field: charge the budget the PROBE PHASE only, which is the
        // marginal cost of a requirement. The first version divided the WHOLE observation by the
        // requirement count, reasoning that overestimating "errs toward admitting fewer, which is
        // the safe direction". That reasoning was wrong twice over. Admitting fewer is not safe
        // when what is being rationed is the loop's only behavioural correctness signal; and
        // because the launch is a FIXED cost, dividing it by n makes the estimate rise as n falls,
        // so the bound ratchets itself downward. Measured on run 2026-09-23_02-48-06: one 65 s
        // observation (a slow boot, not expensive requirements) took the bound from 76 to 6, and
        // the next sweep asked 6 of 16 answerable claims while reporting
        // "probed=6 skipped=0 contradicted=0" — a clean-looking report on a quarter of the
        // questions. The launch is paid whether one criterion is asked or fifty, so the
        // requirements must not be charged for it.
        long probeMs = observation.criterionProbeMs();
        if (!reqs.isEmpty() && probeMs > 0) {
            measuredPerRequirementMs = Math.max(1, probeMs / reqs.size());
        }
        log.info("T4-CY: behavioural self-check ran (durationMs={}, probeMs={}, requirements={}, {} ms each, budget admits {}, finding={})",
                elapsed, probeMs, reqs.size(),
                measuredPerRequirementMs > 0 ? measuredPerRequirementMs : "(unmeasured)",
                admissibleRequirements(), finding.isPresent() ? "yes" : "none");
        if (behaviourLogger != null) {
            behaviourLogger.buildFixAttempt(cycleId, "behavioural-self-check", 0, null, null,
                    null, List.of(), finding.isPresent() ? "DEFECT" : "CLEAN", elapsed);
        }
        if (finding.isEmpty()) {
            lastFinding = null;
            return new SelfCheckResult(Optional.empty(), observation);
        }
        if (finding.get().equals(lastFinding)) {
            log.info("T4-CY: same finding as last observation — not re-raising while it is still open");
            return new SelfCheckResult(Optional.empty(), observation);
        }
        lastFinding = finding.get();
        return new SelfCheckResult(finding, observation);
    }

    /**
     * How many requirements the budget buys at this run's measured cost — the backstop until
     * one has been measured. Never zero: an observation that can ask nothing is not an
     * observation, and a box slow enough for the budget to buy less than one requirement has a
     * problem this bound cannot fix and must not hide.
     */
    private int admissibleRequirements() {
        if (measuredPerRequirementMs <= 0) return MAX_REQUIREMENTS;
        long affordable = requirementBudgetMs / measuredPerRequirementMs;
        return (int) Math.max(1, Math.min(MAX_REQUIREMENTS, affordable));
    }

    /** The abstention above, in the shape {@link #observeWithEvidence} returns. */
    private SelfCheckResult abstained(CycleContextData context, String reason) {
        abstain(context, reason);
        return SelfCheckResult.none();
    }

    /**
     * Records why an interval passed without an observation, at a level a field log actually shows.
     *
     * <p>Every reason for not observing used to be silent or at {@code debug}, so a run in which the
     * check never looked was indistinguishable from one in which it looked and found nothing. That
     * is the same blindness that let {@code criterion_probe_runs=0} stand for fourteen runs — the
     * mechanism was believed to be working because nothing said otherwise. An abstention is a
     * legitimate outcome; being unable to tell it apart from a clean observation is not.
     */
    private Optional<String> abstain(CycleContextData context, String reason) {
        String cycleId = context != null ? context.cycleName() : "self-check";
        log.info("T4-CY: no behavioural observation this interval — {} (cycle {})", reason, cycleId);
        if (behaviourLogger != null) {
            behaviourLogger.buildFixAttempt(cycleId, "behavioural-self-check", 0, null, null,
                    null, List.of(), "ABSTAIN", 0L);
        }
        return Optional.empty();
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
