package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.agent.analysis.model.AnalysisOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.deliberation.DefaultDeliberationValidator;
import com.walpro.autobuilder.core.deliberation.DeliberationDecision;
import com.walpro.autobuilder.core.deliberation.DeliberationMode;
import com.walpro.autobuilder.core.deliberation.DeliberationResult;
import com.walpro.autobuilder.core.deliberation.DeliberationService;
import com.walpro.autobuilder.core.deliberation.RuleBasedDeliberationProposer;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.ChannelRequest;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * T4-BZ-4 / I5: the run-20 livelock-family deadlock guards, pinned at BEHAVIOR level before
 * the participation-window migration touches the machinery they live in. Every read goes
 * through the carrier-agnostic unified API (windowBoost/hasWindowAtLeast honor both the
 * stored ROLE_* twin and a window; request reads honor channel and stored form), so these
 * tests must stay green, UNCHANGED, across the carrier swap.
 *
 * Guards pinned (design-note I5):
 *  1. evalFloor — while a candidate is pending evaluation, deliberation re-opens evaluation's
 *     participation every cycle (>= the 0.10 eligibility floor), so a decayed/expired
 *     evaluation participation cannot deadlock mode=EVALUATE.
 *  2. analysis-unlock — when analysis proposes executable subgoals for the current scope, the
 *     engine consumes REPLAN_REQUESTED and opens evolution's participation, so the system
 *     leaves the replanning orbit with evolution actually able to run.
 *  3. circuit-breaker — with the stagnation circuit breaker active, deliberation returns
 *     UNDIRECTED (all roles stay gate-allowed; REPLAN would be prune-only and could starve
 *     evolution) and injects DIVERGENCE_REQUEST for the breakout attempt.
 */
class DeadlockGuardInvariantTest {

    private final RuleBasedDeliberationProposer proposer = new RuleBasedDeliberationProposer();
    private final DefaultDeliberationValidator validator = new DefaultDeliberationValidator();

    // ------------------------------------------------------------------
    // Guard 1: evalFloor re-injection under pending evaluation
    // ------------------------------------------------------------------

    @Test
    void pendingCandidate_reopensEvaluationParticipationAboveTheEligibilityFloor_evenWhenAbsent() {
        // A candidate evolution exists (fingerprint newer than last evaluated, diff present)
        // but evaluation's participation has fully decayed away — the historic deadlock shape.
        CycleContextData ctx = CycleContextData.builder().build()
                .withCycleName("c-1")
                .withParentCycleName("c-0")
                .withCurrentGoalId("root-1")
                .withCurrentSubGoalId("sg-1")
                .withLastEvolutionFingerprint("fp-new")
                .withLastEvaluatedEvolutionFingerprint("fp-old")
                .withProjectDiff(mock(ProjectDiffResult.class, withSettings().lenient()));

        assertThat(ctx.hasWindowAtLeast("evaluation", 0.10))
                .as("precondition: evaluation participation is gone")
                .isFalse();

        DeliberationResult dr = validator.validate(proposer.propose(ctx));
        CycleContextData after = applyProposals(ctx, dr);

        assertThat(dr.mode()).isEqualTo(DeliberationMode.EVALUATE);
        assertThat(after.hasWindowAtLeast("evaluation", 0.10))
                .as("I5 evalFloor: deliberation must re-open evaluation participation above the floor")
                .isTrue();
    }

    // ------------------------------------------------------------------
    // Guard 2: analysis-unlock opens evolution participation
    // ------------------------------------------------------------------

    @Test
    void analysisProposingExecutableWork_consumesReplanRequest_andOpensEvolutionParticipation() {
        AnalysisOutcome unlocked = new AnalysisOutcome(List.of(), List.of(),
                List.of("implement the missing repository"), null, "ok", "COGNITIVE", false, false);

        ExecutionAgent analysis = new ExecutionAgent() {
            @Override public String name() { return "analysis"; }
            @Override public String role() { return "analysis"; }
            @Override public Set<com.walpro.autobuilder.core.lifecycle.SignalType> getSupportedSignals() {
                return BasicSignals.ANALYSIS_SIGNALS;
            }
            @Override public CycleContextData execute(CycleContextData c) {
                return c.withAnalysisOutcome(unlocked);
            }
        };

        DeliberationService deliberation = mock(DeliberationService.class, withSettings().lenient());
        lenient().when(deliberation.deliberate(any(CycleContextData.class)))
                .thenAnswer(inv -> new DeliberationDecision(inv.getArgument(0),
                        new DeliberationResult(DeliberationMode.REPLAN, 0.8, "replanning",
                                List.of(), List.of(), java.util.Map.of(), List.of())));

        ExecutionEligibilityExplainer explainer =
                mock(ExecutionEligibilityExplainer.class, withSettings().lenient());
        lenient().when(explainer.canExecute(any(), any())).thenReturn(false);
        lenient().when(explainer.canExecute(eq("analysis"), any())).thenReturn(true);
        lenient().when(explainer.explainBlockers(any(), any())).thenReturn(List.of("blocked"));
        lenient().when(explainer.explainBlockers(eq("analysis"), any())).thenReturn(List.of());

        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                List.of(analysis), deliberation, new DeliberationExecutionGate(), explainer);

        CycleContextData ctx = CycleContextData.builder().build()
                .withCycleName("c-2")
                .withCurrentGoalId("root-1")
                .withCurrentSubGoalId("sg-1")
                .grantWindow(com.walpro.autobuilder.core.lifecycle.ParticipationWindow.of("analysis", 1.0, 0.0, "test"))
                .issueRequest(ChannelRequest.of(BasicSignals.REPLAN_REQUESTED, 1.0, "test",
                        ChannelRequest.horizonFromDecay(1.0, 0.2)));

        CycleContextData after = engine.runCycle(ctx);

        assertThat(after.hasOpenRequest(BasicSignals.REPLAN_REQUESTED))
                .as("I5 analysis-unlock: the replan request is consumed")
                .isFalse();
        assertThat(after.hasWindowAtLeast("evolution", 0.10))
                .as("I5 analysis-unlock: evolution participation opens so execution can resume")
                .isTrue();
    }

    // ------------------------------------------------------------------
    // Guard 3: circuit breaker keeps all roles gate-allowed + requests divergence
    // ------------------------------------------------------------------

    @Test
    void stagnationCircuitBreaker_yieldsUndirectedWithAllRolesAllowed_andDivergenceRequest() {
        CycleContextData ctx = CycleContextData.builder().build()
                .withCycleName("c-3")
                .withParentCycleName("c-2")
                .withCurrentGoalId("root-1")
                .withCurrentSubGoalId("sg-1")
                .withStagnationCircuitBreakerActive(true);

        DeliberationResult dr = validator.validate(proposer.propose(ctx));

        assertThat(dr.mode())
                .as("I5 circuit-breaker: UNDIRECTED, never the prune-only REPLAN that deadlocks")
                .isEqualTo(DeliberationMode.UNDIRECTED);
        assertThat(new DeliberationExecutionGate().allowedRoles(DeliberationMode.UNDIRECTED))
                .contains("planner", "analysis", "evolution", "evaluation", "merge");

        CycleContextData after = applyProposals(ctx, dr);
        assertThat(after.hasOpenRequest(BasicSignals.DIVERGENCE_REQUEST))
                .as("I5 circuit-breaker: divergence is requested for the breakout attempt")
                .isTrue();
    }

    /** Applies a deliberation result to the context exactly as the engine's pass-header does. */
    private static CycleContextData applyProposals(CycleContextData ctx, DeliberationResult dr) {
        CycleContextData out = ctx;
        for (ChannelRequest r : dr.proposedRequests()) {
            if (r != null) out = out.issueRequest(r);
        }
        for (com.walpro.autobuilder.core.lifecycle.ParticipationWindow w : dr.proposedWindows()) {
            if (w != null) out = out.grantWindow(w);
        }
        return out;
    }
}
