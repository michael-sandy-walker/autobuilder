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
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * T2-ET: a work item the boot gate raised may not be closed by a score while the gate still
 * observes the defect it was raised for.
 *
 * <p>The shape measured across the September archive: the item declares no acceptance criteria,
 * so the T4-CU check abstained and a model score closed it; the self-check then declined to
 * re-raise a finding identical to its last one, and the defect was lost for the rest of the run.
 */
@ExtendWith(MockitoExtension.class)
class GoalPlannerServiceOriginatingObservationTest {

    @Mock private FileService fileService;
    @Mock private GoalGraphService goalGraphService;
    @Mock private GoalPlannerConfigProperties goalPlannerConfig;

    private static final String DEFECT = "the widget exits at startup (exit 1) - no launcher declared";
    private static final String QUOTED = "RUN frob --count returns exit 3";

    private GoalPlannerService service;
    private GoalEntity leaf;
    private final List<String> observedAsked = new ArrayList<>();
    private final List<List<String>> criteriaAsked = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        lenient().doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        lenient().doNothing().when(goalGraphService).markCompleted(anyString());

        service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
        GoalEntity root = new GoalEntity("G", "root", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        leaf = new GoalEntity("A", DEFECT, "coverage-retro", GoalType.SUBGOAL, "G");
        leaf.setStatus(GoalStatus.ACTIVE);
        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put("G", root);
        goalMap.put("A", leaf);
        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        ReflectionTestUtils.setField(service, "rootGoalId", "G");
        ReflectionTestUtils.setField(service, "currentSubGoalId", "A");
    }

    private void stamp(String claim) {
        leaf.getMetadata().put(BlockerTypes.ORIGINATING_GATE_KEY, BlockerTypes.GATE_BOOT);
        leaf.getMetadata().put(BlockerTypes.BOOT_CLAIM_KEY, claim);
    }

    /** Reproduces or not as told, and records which question it was asked. */
    private void withVerifier(boolean reproduces) {
        service.setClosureClaimVerifier(new ClosureClaimVerifier() {
            @Override
            public Optional<ClaimRefutation> refute(String goalId, List<String> criteria, String spec) {
                criteriaAsked.add(criteria);
                return reproduces ? Optional.of(new ClaimRefutation("still refuted", criteria)) : Optional.empty();
            }

            @Override
            public Optional<ClaimRefutation> refuteObservedDefect(String goalId, String defect, String spec) {
                observedAsked.add(defect);
                return reproduces ? Optional.of(ClaimRefutation.unattributed(defect)) : Optional.empty();
            }
        });
    }

    @Test
    void aWholeArtifactItemStaysActiveWhileItsDefectIsStillObserved() {
        stamp(BlockerTypes.CLAIM_SERVES);
        withVerifier(true);

        service.transitionGoal("A", GoalStatus.COMPLETED, "fulfilled (score=0.80): score-based closure");

        assertThat(service.getGoalById("A").getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(observedAsked).as("asked about the item's own defect").containsExactly(DEFECT);
    }

    @Test
    void aWholeArtifactItemClosesOnceItsDefectIsNoLongerObserved() {
        stamp(BlockerTypes.CLAIM_SERVES);
        withVerifier(false);

        service.transitionGoal("A", GoalStatus.COMPLETED, "fulfilled (score=0.80): score-based closure");

        assertThat(service.getGoalById("A").getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

    @Test
    void aCriterionItemHasTheCriteriaItQuotesProbedAgain() {
        stamp(BlockerTypes.CLAIM_CRITERION);
        leaf.getMetadata().put(BlockerTypes.BOOT_CLAIM_CRITERIA_KEY, List.of(QUOTED));
        withVerifier(true);

        // The promotion path: the one that injects a repair item for an attributed refusal.
        service.promoteCurrentSubGoal();

        assertThat(service.getGoalById("A").getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(criteriaAsked).containsExactly(List.of(QUOTED));
        @SuppressWarnings("unchecked")
        Map<String, GoalEntity> goals = (Map<String, GoalEntity>) ReflectionTestUtils.getField(service, "goalMap");
        assertThat(goals).as("the refused item stays the work - no twin repair item").containsOnlyKeys("G", "A");
    }

    @Test
    void anItemNoGateRaisedIsNotAskedAboutAnObservation() {
        withVerifier(true);

        service.transitionGoal("A", GoalStatus.COMPLETED, "fulfilled (score=0.80): score-based closure");

        assertThat(observedAsked).isEmpty();
        assertThat(service.getGoalById("A").getStatus())
                .as("no criteria and no stamp - the ordinary path, unchanged")
                .isEqualTo(GoalStatus.COMPLETED);
    }

    // ── T4-DW: the archive-entry-point claim votes with the build config and the jar ─────────

    private void withPackageGate(boolean entryPointDeclared) {
        com.walpro.autobuilder.core.build.PackageGate gate =
                org.mockito.Mockito.mock(com.walpro.autobuilder.core.build.PackageGate.class);
        lenient().when(gate.archiveEntryPointDeclared()).thenReturn(entryPointDeclared);
        ReflectionTestUtils.setField(service, "packageGate", gate);
    }

    @Test
    void anArchiveEntryPointItemStaysActiveWhileTheEntryPointIsUndeclared() {
        stamp(BlockerTypes.CLAIM_ARCHIVE_ENTRY_POINT);
        withVerifier(false);
        withPackageGate(false);

        service.transitionGoal("A", GoalStatus.COMPLETED, "fulfilled (score=0.80): score-based closure");

        assertThat(service.getGoalById("A").getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(observedAsked).as("answered by the archive, not by a launch").isEmpty();
    }

    @Test
    void anArchiveEntryPointItemClosesOnceTheEntryPointIsDeclared() {
        stamp(BlockerTypes.CLAIM_ARCHIVE_ENTRY_POINT);
        withVerifier(false);
        withPackageGate(true);

        service.transitionGoal("A", GoalStatus.COMPLETED, "fulfilled (score=0.80): score-based closure");

        assertThat(service.getGoalById("A").getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

    /** With no gate to ask, the item cannot be shown fixed — an abstention is not a pass. */
    @Test
    void anArchiveEntryPointItemStaysActiveWhenNothingCanAnswer() {
        stamp(BlockerTypes.CLAIM_ARCHIVE_ENTRY_POINT);
        withVerifier(false);

        service.transitionGoal("A", GoalStatus.COMPLETED, "fulfilled (score=0.80): score-based closure");

        assertThat(service.getGoalById("A").getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void anInjectedDefectIsStampedWithTheClaimTheGateNamed() {
        boolean injected = service.injectObservedBehaviouralDefect(
                "the packaged artifact declares no entry point, so it cannot be launched",
                List.of(), BlockerTypes.CLAIM_ARCHIVE_ENTRY_POINT);

        assertThat(injected).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, GoalEntity> goals = (Map<String, GoalEntity>) ReflectionTestUtils.getField(service, "goalMap");
        assertThat(goals.values())
                .filteredOn(g -> g.getDescription().contains("declares no entry point"))
                .singleElement()
                .satisfies(g -> assertThat(g.getMetadata().get(BlockerTypes.BOOT_CLAIM_KEY))
                        .isEqualTo(BlockerTypes.CLAIM_ARCHIVE_ENTRY_POINT));
    }
}
