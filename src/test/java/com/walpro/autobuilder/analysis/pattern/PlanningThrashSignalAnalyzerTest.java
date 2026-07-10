package com.walpro.autobuilder.analysis.pattern;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.planner.PlannerInflowStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanningThrashSignalAnalyzerTest {

    private AgentGraphService agentGraphService;
    private GoalPlannerService goalPlannerService;
    private PlanningThrashSignalAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        agentGraphService = mock(AgentGraphService.class);
        goalPlannerService = mock(GoalPlannerService.class);
        analyzer = new PlanningThrashSignalAnalyzer(agentGraphService, goalPlannerService);

        // Default: empty baseline windows (fresh environment)
        when(agentGraphService.getPlannerInflowSince(any(), any())).thenReturn(new PlannerInflowStats(0, 0));
    }

    @Test
    void analyze_whenAllInjectedGoalsCompleted_releasesThrattleViaCaughtUpCondition() {
        // Simulates the live bug: planner injected 4 goals (inflow=4), all 4 are now completed
        // (outflow=4, ratio=1.0). Fresh environment means baseline windows are empty, so
        // inflowRelease=2 — the old release condition (inflow<=2) would never fire.
        // The caughtUp condition (ratio<=1.0 && outflow>0) must release the throttle.
        String rootGoalId = "root-1";

        // 4 completed subgoals, each with lastUpdated set recently
        String recentTs = Instant.now().minusSeconds(60).toString();
        List<GoalEntity> completedGoals = List.of(
                completedGoal("g1", recentTs),
                completedGoal("g2", recentTs),
                completedGoal("g3", recentTs),
                completedGoal("g4", recentTs)
        );
        when(goalPlannerService.getAllGoals()).thenReturn(completedGoals);

        // current window: inflow=4, but all 4 also completed → outflow=4, ratio=1.0
        when(agentGraphService.getPlannerInflowSince(eq(rootGoalId), any()))
                .thenReturn(new PlannerInflowStats(1, 4));

        // Context already has PLANNING_THROTTLED set (simulating previous throttle decision)
        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId(rootGoalId)
                .build()
                .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(BasicSignals.PLANNING_THROTTLED, 1.0, "inflow=4, outflow=0, ratio=4.0", com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.0)));

        CycleContextData result = analyzer.analyze(ctx);

        assertThat(result.hasOpenRequest(BasicSignals.PLANNING_THROTTLED))
                .as("PLANNING_THROTTLED should be released when all injected goals are completed")
                .isFalse();
    }

    @Test
    void analyze_whenOutflowStillZero_doesNotReleaseThrottle() {
        // Inflow=4, outflow=0 — backlog not yet cleared, throttle must stay.
        String rootGoalId = "root-1";

        when(goalPlannerService.getAllGoals()).thenReturn(List.of()); // no completed goals

        when(agentGraphService.getPlannerInflowSince(eq(rootGoalId), any()))
                .thenReturn(new PlannerInflowStats(1, 4));

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId(rootGoalId)
                .build()
                .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(BasicSignals.PLANNING_THROTTLED, 1.0, "inflow=4, outflow=0, ratio=4.0", com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.0)));

        CycleContextData result = analyzer.analyze(ctx);

        assertThat(result.hasOpenRequest(BasicSignals.PLANNING_THROTTLED))
                .as("PLANNING_THROTTLED should remain when outflow is still zero")
                .isTrue();
    }

    @Test
    void analyze_whenAllInjectedGoalsGivenUp_releasesThrattleViaCaughtUpCondition() {
        // Mirrors the COMPLETED variant but with GIVEN_UP goals — the scenario from the live log
        // where inflow=6, outflow=0 because all 6 subgoals were given up, not completed.
        String rootGoalId = "root-1";

        String recentTs = Instant.now().minusSeconds(60).toString();
        List<GoalEntity> givenUpGoals = List.of(
                givenUpGoal("g1", recentTs),
                givenUpGoal("g2", recentTs),
                givenUpGoal("g3", recentTs),
                givenUpGoal("g4", recentTs)
        );
        when(goalPlannerService.getAllGoals()).thenReturn(givenUpGoals);

        when(agentGraphService.getPlannerInflowSince(eq(rootGoalId), any()))
                .thenReturn(new PlannerInflowStats(1, 4));

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId(rootGoalId)
                .build()
                .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(BasicSignals.PLANNING_THROTTLED, 1.0, "inflow=4, outflow=0, ratio=4.0", com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.0)));

        CycleContextData result = analyzer.analyze(ctx);

        assertThat(result.hasOpenRequest(BasicSignals.PLANNING_THROTTLED))
                .as("PLANNING_THROTTLED should be released when all injected goals are given up")
                .isFalse();
    }

    @Test
    void analyze_initialPlanningBurst_doesNotThrottle() {
        // Simulates the startup scenario: planner injects 5 subgoals, nothing has been worked
        // through yet. Baseline windows are all empty (fresh environment). The throttle must NOT
        // fire — there is no deviation from baseline because there is no baseline.
        String rootGoalId = "root-fresh";

        // Returning the same cumulative value for all window boundaries yields inflow=0 in every
        // baseline window (inflowSince - inflowUntil = 5 - 5 = 0), so inflowStats.median stays 0
        // and hasBaseline=false suppresses both outlier checks.
        when(agentGraphService.getPlannerInflowSince(eq(rootGoalId), any()))
                .thenReturn(new PlannerInflowStats(1, 5));
        when(goalPlannerService.getAllGoals()).thenReturn(List.of());

        CycleContextData ctx = CycleContextData.builder()
                .currentGoalId(rootGoalId)
                .build();

        CycleContextData result = analyzer.analyze(ctx);

        assertThat(result.hasOpenRequest(BasicSignals.PLANNING_THROTTLED))
                .as("PLANNING_THROTTLED should not be set on initial planning burst with no baseline history")
                .isFalse();
    }

    // --- helpers ---

    private GoalEntity completedGoal(String id, String lastUpdated) {
        GoalEntity g = new GoalEntity();
        g.setId(id);
        g.setStatus(GoalStatus.COMPLETED);
        // Use the completed field (persisted to/from Neo4j) rather than metadata["lastUpdated"]
        // which is wiped when refreshFromGraph() replaces the in-memory goal map.
        g.setCompleted(Instant.parse(lastUpdated));
        return g;
    }

    private GoalEntity givenUpGoal(String id, String givenUpTs) {
        GoalEntity g = new GoalEntity();
        g.setId(id);
        g.setStatus(GoalStatus.GIVEN_UP);
        g.setGivenUp(Instant.parse(givenUpTs));
        return g;
    }
}
