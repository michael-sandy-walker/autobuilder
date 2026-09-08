package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.goal.ClosureClaimVerifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * T4-CU: the target-side half of {@link ClosureClaimVerifier} — executes a work item's criteria
 * against the running artifact when the loop claims they hold.
 *
 * <p><b>Placement, which is the whole ticket.</b> The capability already existed: {@link BootGate}
 * boots the artifact and executes behavioural criteria, and {@link DeterministicProbeSynthesizer}
 * builds the requests without a model call. It hung off the closure edge alone — the run-level
 * "is everything finished" question — so a single work item could close falsely and the probe would
 * only notice much later, if the run ever reached that edge at all. Nothing probed a subgoal's
 * criteria at the moment that subgoal was marked done. That is what this adds; no new detector.
 *
 * <p><b>Cost, measured before building.</b> Package gate 3.0 s and boot gate 2.6 s per firing
 * (T6-J's per-category timing). Across 32 run corpora 150 of 452 closed subgoals carry a criterion
 * that can be executed at all, so this fires roughly 5 times in a run — under 30 seconds against a
 * 90-minute budget. The other two thirds cost nothing: the parser rejects their criteria before any
 * process starts.
 *
 * <p><b>Ordering is deliberately cheapest-first</b>, the same discipline as the compile probe ahead
 * of the evaluator: reject unexecutable criteria in memory, then consult the last compile result,
 * then package, then boot. Each step can only lose a check, never invent one.
 */
@Slf4j
@Component
public class BehaviouralClosureVerifier implements ClosureClaimVerifier {

    /**
     * The check only ever refutes, so switching it off can only restore false closures — but a
     * switch is what let T4-DA's unconfirmed cost be settled by measurement instead of argument, and
     * every mechanism this project ships now carries one.
     */
    @Value("${autobuilder.goal.verify-closure-claims:true}")
    private boolean enabled = true;

    private final PackageGate packageGate;
    private final BootGate bootGate;

    public BehaviouralClosureVerifier(PackageGate packageGate, BootGate bootGate) {
        this.packageGate = packageGate;
        this.bootGate = bootGate;
    }

    /** Test seam — production wires the field value. */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    @Override
    public Optional<String> refute(String goalId, List<String> criteria) {
        if (!enabled || criteria == null || criteria.isEmpty()) return Optional.empty();

        // Only criteria that state one endpoint's response status can be turned into a request.
        // Filtering here rather than inside the gate keeps the expensive steps behind a decision
        // made in memory: two thirds of closures stop at this line having spent nothing.
        //
        // T1-FW: this asks `exercisable`, not `parse`. A requirement naming an input pattern
        // nothing can ground parses cleanly and is then abstained on by the synthesizer — so
        // asking `parse` let a claim whose criteria were ALL of that kind past this line and buy
        // a package and a boot before probing nothing at all.
        List<String> executable = criteria.stream()
                .filter(c -> c != null && BehaviouralCriterionParser.exercisable(c).isPresent())
                .toList();
        if (executable.isEmpty()) {
            // Two thirds of closures land here. Logged so a run with no closure checks can be read
            // as "nothing was executable" rather than "the check never ran".
            log.info("T4-CU: {} closure claim not checked — none of its {} criterion(s) can be executed",
                    goalId, criteria.size());
            return Optional.empty();
        }

        String checkId = "closure-" + goalId;
        if (packageGate.verifyPackageable(checkId).isPresent()) {
            // A tree that will not package is the build machinery's problem and has its own gates.
            // Refusing the closure here would block on an unrelated defect.
            log.info("T4-CU: cannot check {} closure claim — the artifact is not packageable", goalId);
            return Optional.empty();
        }

        // specText is empty on purpose: the question is not "does this artifact satisfy the
        // specification" — the closure edge already asks that — but "does it satisfy the claims
        // THIS work item is closing on". Passing the spec would fold unrelated requirements into
        // one work item's verdict.
        //
        // Checked rather than assumed, because a blank spec could easily have made this inert:
        // BootGate derives its spec-declared endpoint set from specText, but that set only decides
        // whether a non-serving artifact is reported as a failure. Criterion probing iterates the
        // supplied criteria directly, and the deterministic synthesizer takes only the assertion
        // and the artifact tree — it never reads the specification. So an artifact that serves gets
        // these criteria exercised; one that does not yields no opinion, which is the intended
        // abstention rather than a silent pass.
        Optional<String> contradiction = bootGate.verifyBootsAndServes(checkId, "", executable);
        if (contradiction.isEmpty()) {
            // Deliberately NOT phrased as "the criteria hold". An empty result means no
            // contradiction was FOUND, which includes the case where the gate could construct no
            // request for any of them — the deterministic synthesizer only builds probes for the
            // rejection statuses (400/404/409), so a criterion asserting a success status is
            // parseable here and unprobeable there. Claiming those held would be this ticket's own
            // false-completion defect, one level up. The gate's own line reports probed/skipped.
            log.info("T4-CU: {} closure claim not contradicted — {} criterion(s) offered to the probe",
                    goalId, executable.size());
            return Optional.empty();
        }

        log.warn("T4-CU: {} was about to be closed on a claim the running artifact refutes — {}",
                goalId, contradiction.get());
        return contradiction;
    }
}
