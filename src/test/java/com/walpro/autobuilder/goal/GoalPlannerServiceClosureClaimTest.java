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
        service.setClosureClaimVerifier((goalId, criteria) -> verdict);
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
        service.setClosureClaimVerifier((goalId, criteria) -> {
            throw new IllegalStateException("boot timed out");
        });

        service.transitionGoal("A", GoalStatus.COMPLETED, "evaluator says done");

        assertThat(service.getGoalById("A").getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

    @Test
    void aGoalWithNoCriteriaIsNeverSentToTheVerifier() {
        service.getGoalById("A").getMetadata().remove("acceptanceCriteria");
        service.setClosureClaimVerifier((goalId, criteria) -> {
            throw new AssertionError("nothing to check, so nothing should have been asked");
        });

        service.transitionGoal("A", GoalStatus.COMPLETED, "evaluator says done");

        assertThat(service.getGoalById("A").getStatus()).isEqualTo(GoalStatus.COMPLETED);
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
