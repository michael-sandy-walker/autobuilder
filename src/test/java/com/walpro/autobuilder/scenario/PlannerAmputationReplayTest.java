package com.walpro.autobuilder.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.walpro.autobuilder.agent.planner.PlannerAgent;
import com.walpro.autobuilder.agent.planner.PlannerLLMAdapter;
import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.core.grounding.SymbolGroundingService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalSimilarityService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.GoalType;
import com.walpro.autobuilder.goal.AbandonedGoalCluster;
import com.walpro.autobuilder.goal.AbandonedGoalQueryService;
import com.walpro.autobuilder.protocol.abandoned.RelatedAbandonedWorkBlock;
import com.walpro.autobuilder.protocol.planner.PlannerProtocolContract;

/**
 * Replays the 2026-05-19 23:56 planner-amputation deadlock to validate the T2-DL +
 * T1-EI joint behaviour:
 *
 * <ul>
 *   <li>T2-DL — the planner prompt for the cycle right after the cascade contains a
 *   {@code --- RELATED ABANDONED WORK ---} block citing the GIVEN_UP Loans cluster
 *   and the orphaned {@code MemberService.java}.</li>
 *   <li>T1-EI — after at most 3 cycles of dedup-blocked re-proposals, the matched
 *   GIVEN_UP goal is auto-reactivated (returns to ACTIVE), exiting the deadlock.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PlannerAmputationReplayTest {

    @Mock private PlannerLLMAdapter plannerAdapter;
    @Mock private GoalPlannerService goalPlannerService;
    @Mock private GoalSimilarityService similarityService;
    @Mock private SymbolGroundingService groundingService;
    @Mock private AbandonedGoalQueryService abandonedGoalQueryService;

    private PlannerAgent planner;
    private GoalEntity loansCluster;

    @BeforeEach
    void setUp() {
        lenient().when(groundingService.groundSymbolReferences(anyString(), anySet()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(abandonedGoalQueryService.findRelatedAbandonedGoals(anyString()))
                .thenReturn(List.of());

        planner = new PlannerAgent(plannerAdapter, goalPlannerService, similarityService,
                groundingService, abandonedGoalQueryService);

        loansCluster = new GoalEntity("g-loans", "Implement Loans resource (POST, GET, PATCH)",
                "role", GoalType.SUBGOAL, "root-1");
        loansCluster.setStatus(GoalStatus.GIVEN_UP);
        loansCluster.setGivenUp(Instant.parse("2026-05-19T22:07:38Z"));
        loansCluster.setResolution("cascade from per-parent exhaustion");
        loansCluster.setMetadata(new HashMap<>());
        loansCluster.getMetadata().put("mergedFiles", new ArrayList<>(List.of("MemberService.java")));
    }

    @Test
    void relatedAbandonedBlock_rendersAfterCascade() {
        // T2-DL: the cluster + orphaned file must appear in the planner prompt the first cycle
        // after the cascade — the planner has nothing else to go on at this point.
        AbandonedGoalCluster cluster = new AbandonedGoalCluster(
                loansCluster,
                loansCluster.getGivenUp(),
                loansCluster.getResolution(),
                List.of(),
                List.of(loansCluster),
                List.of("MemberService.java"),
                Map.of()
        );

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-18")
                .currentGoalId("root-1")
                .currentGoalDescription("Library REST API")
                .relatedAbandonedGoals(List.of(cluster))
                .build();

        String rendered = PlannerProtocolContract.getUserPrompt(ctx);

        assertThat(rendered).contains("--- RELATED ABANDONED WORK ---");
        assertThat(rendered).contains("g-loans");
        assertThat(rendered).contains("Implement Loans resource");
        assertThat(rendered).contains("MemberService.java");
    }

    @Test
    void audienceSpecificFraming_planner() {
        AbandonedGoalCluster cluster = new AbandonedGoalCluster(
                loansCluster, loansCluster.getGivenUp(), loansCluster.getResolution(),
                List.of(), List.of(), List.of("MemberService.java"), Map.of());

        CycleContextData ctx = CycleContextData.builder()
                .relatedAbandonedGoals(List.of(cluster)).build();

        String rendered = RelatedAbandonedWorkBlock.render(ctx, RelatedAbandonedWorkBlock.Audience.PLANNER);
        assertThat(rendered).containsAnyOf("reactivated", "removed", "extending");
    }

    @Test
    void replayDeadlock_reactivatesWithinThreeCycles() {
        // Phase B start: currentSubGoalId is null (cascade just demolished the Loans subtree).
        // The planner LLM keeps re-proposing the Loans work. Verify that within ≤3 cycles the
        // matched GIVEN_UP goal is auto-reactivated.
        String proposal = "Implement Loans resource (POST, GET, PATCH)";

        PlannerOutcome outcome = mock(PlannerOutcome.class);
        when(outcome.updated()).thenReturn(true);
        when(outcome.newGoals()).thenReturn(List.of(proposal));
        when(plannerAdapter.plan(any(CycleContextData.class))).thenReturn(outcome);

        when(goalPlannerService.sanitizeProposedSubGoals(anyList())).thenReturn(List.of(proposal));
        when(similarityService.snapshotKnownDescriptions()).thenReturn(List.of(loansCluster.getDescription()));
        when(similarityService.isDuplicate(eq(proposal), anyList())).thenReturn(true);
        when(goalPlannerService.getAllGoals()).thenReturn(List.of(loansCluster));
        when(similarityService.findGivenUpMatch(eq(proposal), anyList())).thenReturn(loansCluster);

        AtomicReference<Integer> voteCounter = new AtomicReference<>(0);
        when(goalPlannerService.registerRehabilitationVote(eq("g-loans"), anyString()))
                .thenAnswer(inv -> {
                    int next = voteCounter.get() + 1;
                    voteCounter.set(next);
                    return next;
                });
        when(goalPlannerService.reactivateGoal("g-loans"))
                .thenAnswer(inv -> {
                    loansCluster.setStatus(GoalStatus.ACTIVE);
                    return List.of("g-loans");
                });

        // refreshGoalState stubs
        GoalEntity root = mock(GoalEntity.class);
        lenient().when(root.getId()).thenReturn("root-1");
        lenient().when(root.getDescription()).thenReturn("Library REST API");
        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId("root-1")
                .currentGoalDescription("Library REST API")
                .cycleName("cycle-18")
                .build();

        boolean reactivated = false;
        for (int i = 18; i <= 20 && !reactivated; i++) {
            // Meta-deliberation re-selects the planner each cycle in the live deadlock; emulate
            // that by stripping the PLANNING_THROTTLED signal between cycles so the agent
            // re-enters its body rather than short-circuiting on the throttle gate.
            ctx = ctx.toBuilder()
                    .cycleName("cycle-" + i)
                    .build()
                    .withoutSignal(com.walpro.autobuilder.core.lifecycle.BasicSignals.PLANNING_THROTTLED);
            ctx = planner.execute(ctx);
            reactivated = loansCluster.getStatus() == GoalStatus.ACTIVE;
        }

        assertThat(reactivated)
                .as("T1-EI: matched GIVEN_UP cluster must reactivate within ≤3 cycles of dedup-blocked re-proposals")
                .isTrue();
        verify(goalPlannerService, atLeastOnce()).registerRehabilitationVote(eq("g-loans"), anyString());
        verify(goalPlannerService).reactivateGoal("g-loans");
    }
}
