package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.analysis.structure.CommandSurfaceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import com.walpro.autobuilder.goal.ClaimRefutation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4-CU: the verifier's contract is that it refutes and never confirms, and that everything
 * expensive sits behind a decision made in memory. Both are asserted against the collaborators,
 * since "did not package the artifact" is the actual claim for two thirds of closures.
 */
@ExtendWith(MockitoExtension.class)
class BehaviouralClosureVerifierTest {

    @Mock private PackageGate packageGate;
    @Mock private BootGate bootGate;

    private BehaviouralClosureVerifier verifier;

    /** Verbatim from run 2026-07-30_21-25-43's goal tree — the closure that was false. */
    private static final String EXECUTABLE =
            "POST /books with title longer than 120 characters returns HTTP 400";

    /** The shape two thirds of criteria have: a claim about structure, not about behaviour. */
    private static final String STRUCTURAL =
            "The Book model declares validation annotations for title and isbn";

    @BeforeEach
    void setUp() {
        verifier = new BehaviouralClosureVerifier(packageGate, bootGate,
                new ExercisableCriterionQuery(new CommandSurfaceQuery()));
        lenient().when(packageGate.verifyPackageable(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void theModelFallbackIsOnByDefaultAndReachesTheGate() {
        // T4-CU residual (1): this edge is the ONE caller BootGate reserves the fallback for, and
        // it passed false from the day it shipped. The assertion is on the argument, because
        // "designed on, wired off" is exactly how that went unnoticed for seven weeks.
        when(bootGate.verifyBootsAndServesWithAttribution(anyString(), isNull(), any(), anyBoolean()))
                .thenReturn(Optional.empty());

        verifier.refute("goal-1", List.of(EXECUTABLE), null);

        verify(bootGate).verifyBootsAndServesWithAttribution(anyString(), isNull(), any(), eq(true));
    }

    @Test
    void theModelFallbackCanBeTurnedOffWithoutARebuild() {
        // The off-switch has to be real: the cap is 5 fallbacks per sweep and the breaker opens on
        // 5 consecutive failures, so one sweep against an unhealthy model is enough to open it.
        verifier.setModelFallbackAtClosure(false);
        when(bootGate.verifyBootsAndServesWithAttribution(anyString(), isNull(), any(), anyBoolean()))
                .thenReturn(Optional.empty());

        verifier.refute("goal-1", List.of(EXECUTABLE), null);

        verify(bootGate).verifyBootsAndServesWithAttribution(anyString(), isNull(), any(), eq(false));
    }

    @Test
    void aCriterionTheArtifactRefutesBlocksTheClaim() {
        when(bootGate.verifyBootsAndServesWithAttribution(anyString(), isNull(), any(), anyBoolean()))
                .thenReturn(Optional.of(new ClaimRefutation(
                        "POST /books -> 400, but the running application answered 201",
                        List.of(EXECUTABLE))));

        assertThat(verifier.refute("goal-1", List.of(EXECUTABLE), null))
                .hasValueSatisfying(r -> {
                    assertThat(r.description()).contains("answered 201");
                    // T4-CU: the criterion travels with the verdict. Asserted here because the
                    // whole point of the widening is that this survives the return.
                    assertThat(r.refutedCriteria()).containsExactly(EXECUTABLE);
                });
    }

    @Test
    void aCriterionTheArtifactUpholdsIsNotRefuted() {
        when(bootGate.verifyBootsAndServesWithAttribution(anyString(), isNull(), any(), anyBoolean()))
                .thenReturn(Optional.empty());

        assertThat(verifier.refute("goal-1", List.of(EXECUTABLE), null)).isEmpty();
    }

    /**
     * T1-FW. The filter asked {@code parse(...).isPresent()}, which succeeds for a requirement
     * naming an input PATTERN -- and the synthesizer then abstains on every one of them. So a
     * claim carrying only criteria of that shape cleared the in-memory decision this class exists
     * to make, bought a package build and a boot, and probed nothing. RED before the filter moved
     * to {@code exercisable}: {@code verifyPackageable} was called.
     */
    @Test
    void aClaimWhoseCriteriaNothingCanGroundBuysNoPackageAndNoBoot() {
        String ungroundable = "GET /loans?memberId={id} returns HTTP 200 with the member's loans";

        assertThat(verifier.refute("goal-1", List.of(ungroundable), null)).isEmpty();

        verify(packageGate, never()).verifyPackageable(anyString());
        verify(bootGate, never()).verifyBootsAndServesWithAttribution(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void criteriaThatCannotBeExecutedCostNothingAtAll() {
        // The common case — 302 of 452 closed subgoals across 32 corpora have only criteria like
        // this. If they reached the gates, the check would cost a package build and a boot on every
        // closure instead of a third of them.
        assertThat(verifier.refute("goal-1", List.of(STRUCTURAL), null)).isEmpty();

        verify(packageGate, never()).verifyPackageable(anyString());
        verify(bootGate, never()).verifyBootsAndServesWithAttribution(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void onlyTheExecutableSubsetIsSentToTheGate() {
        // A work item usually mixes both kinds. Passing the structural ones through would ask the
        // gate to execute something it cannot, and its abstention would read as a pass.
        when(bootGate.verifyBootsAndServesWithAttribution(anyString(), isNull(), any(), anyBoolean()))
                .thenReturn(Optional.empty());

        verifier.refute("goal-1", List.of(STRUCTURAL, EXECUTABLE, STRUCTURAL), null);

        verify(bootGate).verifyBootsAndServesWithAttribution(anyString(), isNull(), eq(List.of(EXECUTABLE)), anyBoolean());
    }

    @Test
    void anArtifactThatWillNotPackageIsNotEvidenceOfAFalseClaim() {
        // That is the build machinery's problem and it has its own gates. Refuting here would block
        // a closure on an unrelated defect.
        when(packageGate.verifyPackageable(anyString())).thenReturn(Optional.of("package failed"));

        assertThat(verifier.refute("goal-1", List.of(EXECUTABLE), null)).isEmpty();
        verify(bootGate, never()).verifyBootsAndServesWithAttribution(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void theWorkItemsOwnClaimsAreCheckedRatherThanTheWholeSpecification() {
        // An empty specification text is deliberate: the closure edge already asks whether the
        // artifact satisfies the spec. Folding the spec in here would decide one work item's fate
        // on requirements it never claimed.
        when(bootGate.verifyBootsAndServesWithAttribution(anyString(), isNull(), any(), anyBoolean()))
                .thenReturn(Optional.empty());

        verifier.refute("goal-1", List.of(EXECUTABLE), null);

        verify(bootGate).verifyBootsAndServesWithAttribution(anyString(), isNull(), any(), anyBoolean());
    }

    @Test
    void noCriteriaMeansNoOpinion() {
        assertThat(verifier.refute("goal-1", List.of(), null)).isEmpty();
        assertThat(verifier.refute("goal-1", null, null)).isEmpty();
    }

    @Test
    void theSwitchRestoresTheOlderBehaviourExactly() {
        verifier.setEnabled(false);

        assertThat(verifier.refute("goal-1", List.of(EXECUTABLE), null)).isEmpty();
        verify(packageGate, never()).verifyPackageable(anyString());
    }
}
