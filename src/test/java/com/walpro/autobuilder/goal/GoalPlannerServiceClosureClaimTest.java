package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.graph.goal.GoalGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;

/**
 * T4-CU: a claim the running artifact refutes must not become a banked fact.
 *
 * <p>Reconstructs the chain measured in run {@code 2026-07-30_21-25-43}: a subgoal closed on
 * criteria stating that {@code POST /books} answers 400, while the artifact answered 201; the
 * banked ✓ then let the parent that had correctly diagnosed the same defect be retired as
 * exhausted.
 */
@ExtendWith(MockitoExtension.class)
class GoalPlannerServiceClosureClaimTest {

    @Mock private FileService fileService;
    @Mock private GoalGraphService goalGraphService;
    @Mock private GoalPlannerConfigProperties goalPlannerConfig;

    private static final String CRITERION =
            "POST /books with title longer than 120 characters returns HTTP 400";

    private static final String CONTRADICTION =
            "POST /books -> 400, but the running application answered 201 to POST /books {}";

    private GoalPlannerService service;

    @BeforeEach
    void setUp() throws IOException {
        lenient().doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        lenient().doNothing().when(goalGraphService).markCompleted(anyString());

        service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);

        GoalEntity root = new GoalEntity("G", "root", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        GoalEntity leaf = new GoalEntity("A", "Implement input validation for the Book model",
                "planner", GoalType.SUBGOAL, "G");
        leaf.setStatus(GoalStatus.ACTIVE);
        leaf.getMetadata().put("acceptanceCriteria", List.of(CRITERION));

        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put("G", root);
        goalMap.put("A", leaf);
        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        ReflectionTestUtils.setField(service, "rootGoalId", "G");
        ReflectionTestUtils.setField(service, "currentSubGoalId", "A");
    }

    /** Answers whatever it is told to, so the test controls the one fact under examination. */
    private void withVerifier(Optional<String> verdict) {
        service.setClosureClaimVerifier((goalId, criteria, specText) -> verdict);
    }

    @Test
    void aRefutedClaimLeavesTheGoalActiveInsteadOfClosingIt() {
        withVerifier(Optional.of(CONTRADICTION));

        service.transitionGoal("A", GoalStatus.COMPLETED, "evaluator says done");

        assertThat(service.getGoalById("A").getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void aRefutedClaimDoesNotBankItsCriteriaAsTrue() {
        // The banked ✓ is what the rest of the run inherits — the later gates, the closure edge,
        // and the decomposition that retired the parent all read it.
        withVerifier(Optional.of(CONTRADICTION));

        service.transitionGoal("A", GoalStatus.COMPLETED, "evaluator says done");

        assertThat(service.getCriteriaPassStatus(service.getGoalById("A"))).doesNotContainValue(Boolean.TRUE);
    }

    /**
     * T2-FB: the livelock. Criteria banked by earlier work stay banked through a refusal, so
     * T3-BL's "all criteria banked" early exit re-declares FULFILLED, T4-CU refuses again, and
     * the loop spins doing nothing — measured in run {@code 2026-08-01_19-29-42} as 78 early
     * exits on one subgoal, 148 package-gate runs against 26 in a comparable run, ~16 of its 50
     * minutes, ended by the T4-I brake. The refusal must drop the bank the refuted claim rested
     * on, exactly as the closure-edge reopen path already does for the same reason.
     */
    @Test
    void aRefusedPromotionDropsTheBankThatWouldReMakeTheClaim() {
        service.saveCriteriaPassStatus("A", Map.of(1, true));
        assertThat(service.allCriteriaDefinedAndBanked("A"))
                .as("precondition — this is exactly what arms T3-BL's early exit")
                .isTrue();
        withVerifier(Optional.of(CONTRADICTION));

        service.promoteCurrentSubGoal();

        assertThat(service.getGoalById("A").getStatus())
                .as("the loop still stays on the work — the refusal is not a promotion")
                .isEqualTo(GoalStatus.ACTIVE);
        assertThat(service.allCriteriaDefinedAndBanked("A"))
                .as("T3-BL's early-exit read must be false, or the claim is re-made next cycle")
                .isFalse();
    }

    /**
     * The other half, so the drop is not mistaken for "refusals always clear banks": a claim that
     * is NOT refuted keeps its bank and closes. Without this the test above would still pass if
     * the bank were cleared unconditionally.
     */
    @Test
    void anUnrefutedClaimKeepsItsBankAndCloses() {
        service.saveCriteriaPassStatus("A", Map.of(1, true));
        withVerifier(Optional.empty());

        service.promoteCurrentSubGoal();

        assertThat(service.getGoalById("A").getStatus()).isEqualTo(GoalStatus.COMPLETED);
        assertThat(service.allCriteriaDefinedAndBanked("A")).isTrue();
    }

    @Test
    void theContradictionIsRecordedOnTheGoalSoTheReasonSurvives() {
        withVerifier(Optional.of(CONTRADICTION));

        service.transitionGoal("A", GoalStatus.COMPLETED, "evaluator says done");

        assertThat(service.getGoalById("A").getMetadata())
                .containsEntry("closureRefutedBy", CONTRADICTION);
    }

    @Test
    void anUnrefutedClaimClosesExactlyAsBefore() {
        withVerifier(Optional.empty());

        service.transitionGoal("A", GoalStatus.COMPLETED, "evaluator says done");

        assertThat(service.getGoalById("A").getStatus()).isEqualTo(GoalStatus.COMPLETED);
        assertThat(service.getCriteriaPassStatus(service.getGoalById("A"))).containsValue(Boolean.TRUE);
    }

    @Test
    void withoutAVerifierClosureBehavesExactlyAsBefore() {
        // The verifier is optional so a target nothing understands, and every existing test, keep
        // the behaviour they had.
        service.transitionGoal("A", GoalStatus.COMPLETED, "evaluator says done");

        assertThat(service.getGoalById("A").getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

    @Test
    void aVerifierThatThrowsIsTreatedAsUncheckedRatherThanAsRefuted() {
        // A check that can fail closed would block closure on its own defects. The worst case has
        // to be that the check did not run, which is where the loop already was.
        service.setClosureClaimVerifier((goalId, criteria, specText) -> {
            throw new IllegalStateException("boot timed out");
        });

        service.transitionGoal("A", GoalStatus.COMPLETED, "evaluator says done");

        assertThat(service.getGoalById("A").getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

    @Test
    void aGoalWithNoCriteriaIsNeverSentToTheVerifier() {
        service.getGoalById("A").getMetadata().remove("acceptanceCriteria");
        service.setClosureClaimVerifier((goalId, criteria, specText) -> {
            throw new AssertionError("nothing to check, so nothing should have been asked");
        });

        service.transitionGoal("A", GoalStatus.COMPLETED, "evaluator says done");

        assertThat(service.getGoalById("A").getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

    @Test
    void promotionIsAlsoCovered_notOnlyTheTransitionApi() {
        // The gap this test exists for: the check was first placed in transitionGoal(COMPLETED),
        // but promoteCurrentSubGoal calls markGoalCompleted DIRECTLY — and that is the path the
        // loop actually takes when a subgoal is fulfilled. The check covered the API nobody used
        // for this and missed the one everybody does.
        withVerifier(Optional.of(CONTRADICTION));

        service.promoteCurrentSubGoal();

        // The metadata is the discriminator: only this ticket's code writes it. The status
        // assertion rides along because a refused promotion must leave the goal where it was.
        assertThat(service.getGoalById("A").getMetadata()).containsEntry("closureRefutedBy", CONTRADICTION);
        assertThat(service.getGoalById("A").getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void promotionDoesNotAdvancePastAGoalWhoseClaimIsRefuted() {
        // Advancing would leave the defect behind with nothing pointing at it, which is how the
        // measured chain ended: the parent gave up because its child claimed success.
        withVerifier(Optional.of(CONTRADICTION));

        boolean adopted = service.promoteCurrentSubGoal();

        assertThat(adopted).isFalse();     // nothing was adopted; the loop stays on this work item
        assertThat(service.getCurrentSubGoal()).isNotNull();
        assertThat(service.getCurrentSubGoal().getId()).isEqualTo("A");
    }

    @Test
    void promotionRecordsNoRefutationWhenNothingIsRefuted() {
        // The control for the two tests above, and deliberately asserted on the metadata rather
        // than on the resulting status. This fixture is a root with a single leaf, which is too
        // thin to pin what promotion does with the pointer afterwards — and the positive
        // promotion path is already covered properly by GoalPlannerServiceClosureTest's own
        // multi-level fixtures. What is unambiguous here is that closureRefutedBy can only be
        // written by this ticket's code, so its presence and absence discriminate cleanly.
        withVerifier(Optional.empty());

        service.promoteCurrentSubGoal();

        assertThat(service.getGoalById("A").getMetadata()).doesNotContainKey("closureRefutedBy");
    }

    @Test
    void aRepairedArtifactClosesTheGoalOnTheNextAttempt() {
        // Refusal is not a terminal verdict on the work item: it stands only while the
        // contradiction reproduces. This is what keeps a refused goal from being stuck forever.
        withVerifier(Optional.of(CONTRADICTION));
        service.transitionGoal("A", GoalStatus.COMPLETED, "evaluator says done");
        assertThat(service.getGoalById("A").getStatus()).isEqualTo(GoalStatus.ACTIVE);

        withVerifier(Optional.empty());
        service.transitionGoal("A", GoalStatus.COMPLETED, "evaluator says done");

        assertThat(service.getGoalById("A").getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }
}
