package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.blocker.BlockerSubgoalInjector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * T2-EQ: give the run one chance to become packageable before it dies.
 *
 * <p>The problem this exists for: {@link PackageGate} was reachable only from the root-closure
 * path, and {@code autoCompleteRootIfSatisfied} early-returns while any subgoal is still ACTIVE —
 * which is exactly the state a stalling run is in. Measured consequence: <b>0 gate invocations
 * across 65 cycles</b> of a run that halted with an artifact that could never have been packaged.
 * The requirement was not merely unmet; it was never expressed.
 *
 * <p>Why here and not per merge: an early tree is a partial skeleton, and "every class compiles"
 * is the right bar for it — "the whole project packages" is an END-STAGE property. Verifying it
 * after every merge demands a whole-project property of a half-built one, and the field showed
 * exactly that harm: a run hijacked at its first merge (2026-07-27 run 1 — 27 cycles, one file,
 * stalled at 8.2 min) and a run that answered by writing TWO entry points and then had to repair
 * the ambiguity it had just created (run 2). So the check belongs where the run is ENDING, not
 * where it is building.
 *
 * <p>Bounded by construction: a repair item is created only when none is already open, so a run
 * gets one rescue per unresolved packaging failure. If the loop was handed the item and stalled
 * anyway, the next halt is real and proceeds.
 */
@Slf4j
@Component
public class PreHaltPackagingCheck {

    private final BlockerSubgoalInjector blockerSubgoalInjector;

    /** Optional — absent means packaging is simply not verified (pre-T2-EQ behaviour). */
    @Autowired(required = false)
    private PackageGate packageGate;

    public PreHaltPackagingCheck(BlockerSubgoalInjector blockerSubgoalInjector) {
        this.blockerSubgoalInjector = blockerSubgoalInjector;
    }

    /** Test seam. */
    public void setPackageGate(PackageGate packageGate) {
        this.packageGate = packageGate;
    }

    /**
     * Runs the packaging check at a halt edge.
     *
     * @return {@code true} when a repair work item was just created — the caller has fresh,
     *         gate-measured work in hand and must not halt. {@code false} in every other case
     *         (packages fine, gate unavailable, or the item was already open and unworked).
     */
    public boolean rescueIfNotPackageable(String cycleId) {
        if (packageGate == null) return false;

        Optional<String> failure = packageGate.verifyPackageable(cycleId);
        if (failure.isEmpty()) {
            // Packages cleanly: retire any repair item this check raised earlier. Only the gate
            // that raised it can attest that it is done — the compile-OK cleanup cannot.
            blockerSubgoalInjector.clearPackagingBuildFixSubgoals();
            return false;
        }
        if (blockerSubgoalInjector.hasActiveBuildFixSubgoal()) {
            log.info("T2-EQ: artifact still not packageable at the halt edge, but a build-fix item "
                    + "is already open — the loop had its chance; not blocking the halt.");
            return false;
        }

        log.warn("T2-EQ: halt edge reached with an artifact the build tool cannot package — {}",
                failure.get());
        // Merged paths are deliberately not passed: a packaging failure is not located in a file
        // that changed, it is about one that does not exist. Naming a healthy file produced a
        // work item whose criteria were already satisfied (field-verified 2026-07-27).
        blockerSubgoalInjector.injectBuildFixSubgoalIfAbsent(
                new BuildOutcome(BuildResult.PACKAGE_ERROR, failure.get(), 0L, "package-verify", cycleId),
                List.of());
        return blockerSubgoalInjector.hasActiveBuildFixSubgoal();
    }
}
