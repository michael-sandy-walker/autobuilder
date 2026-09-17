package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.graph.goal.GoalGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * T4-CU: the three things that were blocked by the port returning prose, asserted now that it
 * returns the criteria.
 *
 * <p>The refusal branch used to log, clear the whole banked criteria map and return. It could not
 * do better: the refutation arrived as one sentence, and matching that back to a criterion would
 * have been code consuming another component's prose for control flow. So the repair item had
 * nothing to name (this ticket's own field DoD), and a criterion that genuinely held was re-earned
 * along with the one that did not.
 *
 * <p>The control that matters most here is the UNATTRIBUTED case: a boot failure is a real
 * refutation that belongs to no single criterion, and treating empty as "no criterion was at
 * fault" would quietly stop clearing a bank that must be cleared.
 */
@ExtendWith(MockitoExtension.class)
class ClaimRefutationAttributionTest {

    @Mock private FileService fileService;
    @Mock private GoalGraphService goalGraphService;
    @Mock private GoalPlannerConfigProperties goalPlannerConfig;

    private static final String HELD = "GET /books returns HTTP 200 with the book list";
    private static final String REFUTED = "POST /books with a title over 120 characters returns HTTP 400";

    private GoalPlannerService service;
    private GoalEntity leaf;

    @BeforeEach
    void setUp() throws IOException {
        lenient().doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        lenient().doNothing().when(goalGraphService).markCompleted(anyString());

        service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);

        GoalEntity root = new GoalEntity("G", "root", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        leaf = new GoalEntity("A", "Implement Book validation", "planner", GoalType.SUBGOAL, "G");
        leaf.setStatus(GoalStatus.ACTIVE);
        leaf.getMetadata().put("acceptanceCriteria", List.of(HELD, REFUTED));
        // Both criteria banked ✓ — the state a refuted closure arrives in.
        leaf.getMetadata().put("criteriaPassStatus", new LinkedHashMap<>(Map.of(1, true, 2, true)));

        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put("G", root);
        goalMap.put("A", leaf);
        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        ReflectionTestUtils.setField(service, "rootGoalId", "G");
        ReflectionTestUtils.setField(service, "currentSubGoalId", "A");
    }

    /**
     * Read through the service's own accessor, not the raw metadata: the bank's keys are Strings
     * on disk and Integers in the API, and asserting against the raw map made this test pass or
     * fail on that conversion rather than on the narrowing under examination.
     */
    private Map<Integer, Boolean> bank() {
        return service.getCriteriaPassStatus(leaf);
    }

    /** True when no bank is stored at all, which is a different state from an empty one. */
    private boolean bankAbsent() {
        return leaf.getMetadata().get("criteriaPassStatus") == null;
    }

    @Test
    void anAttributedRefutationDropsOnlyTheRefutedCriterionAndLeavesTheOneThatHeld() {
        boolean cleared = service.clearCriteriaBankAfterRefutation("A", List.of(REFUTED));

        assertThat(cleared).isTrue();
        assertThat(bank())
                .as("the criterion the artifact upheld must not be re-earned for its neighbour's fault")
                .containsExactly(Map.entry(1, Boolean.TRUE));
    }

    @Test
    void anUnattributedRefutationStillDropsTheWholeBank() {
        // A boot failure refutes the claim without belonging to any one criterion. Reading empty
        // as "nothing was at fault" would leave a refuted claim banked and re-declarable.
        boolean cleared = service.clearCriteriaBankAfterRefutation("A", List.of());

        assertThat(cleared).isTrue();
        assertThat(bankAbsent()).as("nothing may stay banked when the refutation cannot be placed").isTrue();
    }

    @Test
    void aCriterionTextThatIsNotInTheDeclaredListForcesTheWideDrop() {
        // The bank is keyed by POSITION in acceptanceCriteria. A refuted text that cannot be
        // located there must not be guessed at — dropping everything is the safe answer.
        boolean cleared = service.clearCriteriaBankAfterRefutation("A",
                List.of("POST /members returns HTTP 409 for a duplicate"));

        assertThat(cleared).isTrue();
        assertThat(bankAbsent()).isTrue();
    }

    @Test
    void everyCriterionBeingRefutedNarrowsToAnEmptyBankRatherThanDeclining() {
        // Narrowing to nothing is a legitimate result and must not be confused with "do not
        // narrow" — which is why the internal helper signals refusal with null, not an empty map.
        boolean cleared = service.clearCriteriaBankAfterRefutation("A", List.of(HELD, REFUTED));

        assertThat(cleared).isTrue();
        assertThat(bankAbsent()).as("an empty bank is stored, not removed").isFalse();
        assertThat(bank()).isEmpty();
    }

    @Test
    void theOneArgumentFormKeepsTheWholeBankBehaviourItAlwaysHad() {
        assertThat(service.clearCriteriaBankAfterRefutation("A")).isTrue();
        assertThat(bankAbsent()).isTrue();
    }

    @Test
    void aGoalWithNoBankReportsThatNothingWasCleared() {
        leaf.getMetadata().remove("criteriaPassStatus");

        assertThat(service.clearCriteriaBankAfterRefutation("A", List.of(REFUTED))).isFalse();
    }

    @Test
    void aRefutedClosureRaisesARepairItemWhoseDoneWhenIsTheRefutedCriteria() {
        service.setClosureClaimVerifier((goalId, criteria, specText) ->
                Optional.of(new ClaimRefutation("the running application answered 201", List.of(REFUTED))));

        service.promoteCurrentSubGoal();

        // T4-CU's field DoD in unit form: the refutation becomes work that names what to repair.
        List<GoalEntity> injected = service.getAllGoals().stream()
                .filter(g -> g.getDescription() != null && g.getDescription().startsWith("[BUILD_FIX]"))
                .toList();
        assertThat(injected).as("a refuted closure must leave something pointing at the defect")
                .hasSize(1);
        GoalEntity item = injected.get(0);
        assertThat(item.getMetadata().get(BlockerTypes.ORIGINATING_GATE_KEY))
                .as("the criteria-gate stamp is what stops a COMPILE_OK observation retiring it")
                .isEqualTo(BlockerTypes.GATE_CRITERIA);
        assertThat(item.getMetadata().get("acceptanceCriteria"))
                .as("the done-when is the refuted criteria themselves, not the log sentence")
                .isEqualTo(List.of(REFUTED));
    }

    @Test
    void theRefusalIsRecordedAsACountableEventNamingTheGateAndTheCriteria() {
        // The census that motivated this: ten gates can block closure and every one of them
        // reported it with a log.warn alone, so "which gate refused, and on what" could only be
        // answered by a regex over an archived log.
        BehaviourLogger logger = mock(BehaviourLogger.class);
        ReflectionTestUtils.setField(service, "behaviourLogger", logger);
        service.setClosureClaimVerifier((goalId, criteria, specText) ->
                Optional.of(new ClaimRefutation("the running application answered 201", List.of(REFUTED))));

        service.promoteCurrentSubGoal();

        ArgumentCaptor<List<String>> criteria = ArgumentCaptor.forClass(List.class);
        verify(logger).closureRefused(any(), eq("T4-CU"), eq("A"),
                contains("answered 201"), criteria.capture());
        assertThat(criteria.getValue())
                .as("the gate field is the gate's own ticket id and the criteria travel with it")
                .containsExactly(REFUTED);
    }

    @Test
    void recordingSurvivesAnAbsentLogger() {
        // The field is @Autowired(required = false) and null in most unit contexts; a refusal
        // must not depend on observability being wired.
        ReflectionTestUtils.setField(service, "behaviourLogger", null);
        service.setClosureClaimVerifier((goalId, criteria, specText) ->
                Optional.of(new ClaimRefutation("answered 201", List.of(REFUTED))));

        service.promoteCurrentSubGoal();

        assertThat(service.getGoalById("A").getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void anUnattributedRefutationRaisesNoRepairItem() {
        // An artifact that will not boot is the build gates' problem and they raise their own
        // items. An item saying only "this claim is false" re-asks the same question at more cost.
        service.setClosureClaimVerifier((goalId, criteria, specText) ->
                Optional.of(ClaimRefutation.unattributed("the application exits at startup (exit 1)")));

        service.promoteCurrentSubGoal();

        assertThat(service.getAllGoals().stream()
                .filter(g -> g.getDescription() != null && g.getDescription().startsWith("[BUILD_FIX]")))
                .isEmpty();
    }
}
