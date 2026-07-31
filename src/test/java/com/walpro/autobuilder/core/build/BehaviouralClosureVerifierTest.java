package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        verifier = new BehaviouralClosureVerifier(packageGate, bootGate);
        lenient().when(packageGate.verifyPackageable(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void aCriterionTheArtifactRefutesBlocksTheClaim() {
        when(bootGate.verifyBootsAndServes(anyString(), eq(""), any()))
                .thenReturn(Optional.of("POST /books -> 400, but the running application answered 201"));

        assertThat(verifier.refute("goal-1", List.of(EXECUTABLE)))
                .hasValueSatisfying(s -> assertThat(s).contains("answered 201"));
    }

    @Test
    void aCriterionTheArtifactUpholdsIsNotRefuted() {
        when(bootGate.verifyBootsAndServes(anyString(), eq(""), any())).thenReturn(Optional.empty());

        assertThat(verifier.refute("goal-1", List.of(EXECUTABLE))).isEmpty();
    }

    @Test
    void criteriaThatCannotBeExecutedCostNothingAtAll() {
        // The common case — 302 of 452 closed subgoals across 32 corpora have only criteria like
        // this. If they reached the gates, the check would cost a package build and a boot on every
        // closure instead of a third of them.
        assertThat(verifier.refute("goal-1", List.of(STRUCTURAL))).isEmpty();

        verify(packageGate, never()).verifyPackageable(anyString());
        verify(bootGate, never()).verifyBootsAndServes(anyString(), anyString(), any());
    }

    @Test
    void onlyTheExecutableSubsetIsSentToTheGate() {
        // A work item usually mixes both kinds. Passing the structural ones through would ask the
        // gate to execute something it cannot, and its abstention would read as a pass.
        when(bootGate.verifyBootsAndServes(anyString(), eq(""), any())).thenReturn(Optional.empty());

        verifier.refute("goal-1", List.of(STRUCTURAL, EXECUTABLE, STRUCTURAL));

        verify(bootGate).verifyBootsAndServes(anyString(), eq(""), eq(List.of(EXECUTABLE)));
    }

    @Test
    void anArtifactThatWillNotPackageIsNotEvidenceOfAFalseClaim() {
        // That is the build machinery's problem and it has its own gates. Refuting here would block
        // a closure on an unrelated defect.
        when(packageGate.verifyPackageable(anyString())).thenReturn(Optional.of("package failed"));

        assertThat(verifier.refute("goal-1", List.of(EXECUTABLE))).isEmpty();
        verify(bootGate, never()).verifyBootsAndServes(anyString(), anyString(), any());
    }

    @Test
    void theWorkItemsOwnClaimsAreCheckedRatherThanTheWholeSpecification() {
        // An empty specification text is deliberate: the closure edge already asks whether the
        // artifact satisfies the spec. Folding the spec in here would decide one work item's fate
        // on requirements it never claimed.
        when(bootGate.verifyBootsAndServes(anyString(), eq(""), any())).thenReturn(Optional.empty());

        verifier.refute("goal-1", List.of(EXECUTABLE));

        verify(bootGate).verifyBootsAndServes(anyString(), eq(""), any());
    }

    @Test
    void noCriteriaMeansNoOpinion() {
        assertThat(verifier.refute("goal-1", List.of())).isEmpty();
        assertThat(verifier.refute("goal-1", null)).isEmpty();
    }

    @Test
    void theSwitchRestoresTheOlderBehaviourExactly() {
        verifier.setEnabled(false);

        assertThat(verifier.refute("goal-1", List.of(EXECUTABLE))).isEmpty();
        verify(packageGate, never()).verifyPackageable(anyString());
    }
}
