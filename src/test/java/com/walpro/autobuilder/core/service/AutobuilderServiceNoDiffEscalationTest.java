package com.walpro.autobuilder.core.service;

import com.walpro.autobuilder.core.blocker.BlockerSubgoalInjector;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.goal.GoalPlannerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T2-ES, the case the ticket was filed from: evolution reports the work as already implemented,
 * the promotion gate reports the criterion unmet, and the loop asks again. The run that produced
 * the ticket spent 27 of its 110 cycles that way, emitting the identical pair of statements every
 * time, and nothing counted the repeat — so nothing could decide that asking again had stopped
 * working. These tests are about the counting and the decision, not about the text.
 */
class AutobuilderServiceNoDiffEscalationTest {

    private AutobuilderService service;
    private BlockerSubgoalInjector injector;

    private static final List<String> CRITERIA = List.of(
            "the first stated behaviour is observable",
            "the second stated behaviour is observable");

    @BeforeEach
    void setUp() {
        service = new AutobuilderService(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        injector = mock(BlockerSubgoalInjector.class);
        when(injector.injectNoDiffDisagreementRedirection(any(), anyInt(), any()))
                .thenReturn(BlockerSubgoalInjector.StructuralDefectEscalation.injected("goal-new"));
        service.setBlockerSubgoalInjector(injector);
    }

    /**
     * The banked map is keyed from ONE, which is what both writers produce: the criteria
     * verification service builds {@code passMap.get(i + 1)} and the operator endpoint range-checks
     * from 1. So {1:true, 2:false} means the FIRST criterion passed and the SECOND failed.
     */
    private static CycleContextData disagreeing(int streak) {
        return CycleContextData.builder()
                .cycleName("cycle-1")
                .currentSubGoalId("sub-1")
                .currentSubGoalCriteria(CRITERIA)
                .cumulativeCriteriaStatus(Map.of(1, true, 2, false))
                .noDiffRefusalStreak(streak)
                .build();
    }

    @Test
    @DisplayName("the third identical disagreement escalates, the first two do not")
    void escalatesOnTheThirdRepeatNotBefore() {
        CycleContextData c1 = service.escalateNoDiffDisagreement(disagreeing(0), "sub-1", "criteria-unmet");
        assertThat(c1.noDiffRefusalStreak()).isEqualTo(1);
        verify(injector, never()).injectNoDiffDisagreementRedirection(any(), anyInt(), any());

        CycleContextData c2 = service.escalateNoDiffDisagreement(disagreeing(c1.noDiffRefusalStreak()), "sub-1", "criteria-unmet");
        assertThat(c2.noDiffRefusalStreak()).isEqualTo(2);
        verify(injector, never()).injectNoDiffDisagreementRedirection(any(), anyInt(), any());

        service.escalateNoDiffDisagreement(disagreeing(c2.noDiffRefusalStreak()), "sub-1", "verified-criteria-unmet");

        verify(injector, times(1)).injectNoDiffDisagreementRedirection(
                eq(List.of("the second stated behaviour is observable")), eq(3), any());
    }

    @Test
    @DisplayName("only the failing criteria are handed over, in the planner's words")
    void onlyUnmetCriteriaAreEscalated() {
        service.escalateNoDiffDisagreement(disagreeing(2), "sub-1", "criteria-unmet");

        verify(injector).injectNoDiffDisagreementRedirection(
                eq(List.of("the second stated behaviour is observable")), eq(3), eq(List.of()));
    }

    @Test
    @DisplayName("the streak restarts after an escalation, so it does not fire every cycle from then on")
    void theStreakRestartsAfterEscalating() {
        CycleContextData after = service.escalateNoDiffDisagreement(disagreeing(2), "sub-1", "criteria-unmet");
        assertThat(after.noDiffRefusalStreak()).isZero();
    }

    @Test
    @DisplayName("the derived peer differences are passed through when the analyzer finds any")
    void derivedStructuralDifferencesAreHandedOver() {
        var analyzer = mock(com.walpro.autobuilder.analysis.structure.WiringFactsAnalyzer.class);
        when(analyzer.peerAnnotationAnomalies(any())).thenReturn(List.of("Thing differs from its peers"));
        ReflectionTestUtils.setField(service, "wiringFactsAnalyzer", analyzer);
        CycleContextData ctx = disagreeing(2).withPreviousOutput(Map.of("A.java", "class A {}"));

        service.escalateNoDiffDisagreement(ctx, "sub-1", "criteria-unmet");

        verify(injector).injectNoDiffDisagreementRedirection(
                any(), eq(3), eq(List.of("Thing differs from its peers")));
    }

    @Test
    @DisplayName("with no criteria on record the disagreement is still counted, and the injector decides")
    void withNoCriteriaTheInjectorStillDecides() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-1").currentSubGoalId("sub-1").noDiffRefusalStreak(2).build();

        service.escalateNoDiffDisagreement(ctx, "sub-1", "criteria-unmet");

        // The service does not second-guess the actuator: it hands over what it has, and the
        // injector refuses an item that would say nothing. One home for that judgement.
        verify(injector).injectNoDiffDisagreementRedirection(eq(List.of()), eq(3), eq(List.of()));
    }

    @Test
    @DisplayName("without an injector the streak is still counted — pre-T2-ES behaviour, not a crash")
    void withoutAnInjectorNothingBreaks() {
        service.setBlockerSubgoalInjector(null);

        CycleContextData after = service.escalateNoDiffDisagreement(disagreeing(2), "sub-1", "criteria-unmet");

        assertThat(after.noDiffRefusalStreak()).isEqualTo(3);
    }

    /**
     * A refusal because the build is broken is a different problem with its own actuator. Sharing
     * this counter would raise an item with no criterion to name, and worse, the escalation attempt
     * resets the streak — so a real disagreement underneath a broken build would never reach three.
     */
    @Test
    @DisplayName("a build-failure refusal is not this disagreement and is not counted")
    void aBuildFailureRefusalIsNotCounted() {
        CycleContextData after = service.escalateNoDiffDisagreement(disagreeing(2), "sub-1", "build-failed");

        assertThat(after.noDiffRefusalStreak())
                .as("the criteria streak must survive a cycle refused for a different reason")
                .isEqualTo(2);
        verify(injector, never()).injectNoDiffDisagreementRedirection(any(), anyInt(), any());
    }


    /**
     * The off-by-one this pins was live in the committed code and would have quoted the wrong
     * requirement back at the model. With a single criterion banked false at key 1, reading from
     * zero finds nothing at all and the escalation names no criterion — so the injector refuses and
     * the whole mechanism goes quiet exactly when it should fire.
     */
    @Test
    @DisplayName("a single failing criterion banked at key 1 is found, not missed")
    void theFirstCriterionFailingIsNotMissed() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-1").currentSubGoalId("sub-1")
                .currentSubGoalCriteria(List.of("the only stated behaviour is observable"))
                .cumulativeCriteriaStatus(Map.of(1, false))
                .noDiffRefusalStreak(2)
                .build();

        service.escalateNoDiffDisagreement(ctx, "sub-1", "criteria-unmet");

        verify(injector).injectNoDiffDisagreementRedirection(
                eq(List.of("the only stated behaviour is observable")), eq(3), any());
    }


    // ---- the seam: a refusal must actually reach the escalation ----
    //
    // Everything above drives escalateNoDiffDisagreement directly, which tests the decision and
    // not the wiring. The call lives inside the refusal branch of the promotion decision, four
    // hundred lines into the scheduled tick, and the condition it guards has occurred zero times
    // in 967 recorded cycles — so the field cannot cover it either. These drive the branch.

    private AutobuilderService serviceWithPlanner(GoalPlannerService planner) {
        AutobuilderService s = new AutobuilderService(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(s, "goalPlannerService", planner);
        s.setBlockerSubgoalInjector(injector);
        return s;
    }

    /** A cycle whose evolution said #NoDiff, on a subgoal with a criterion banked false. */
    private static CycleContextData noDiffWithUnmetCriterion(int streak) {
        return CycleContextData.builder()
                .cycleName("cycle-1")
                .currentSubGoalId("sub-1")
                .currentSubGoalCriteria(CRITERIA)
                .cumulativeCriteriaStatus(Map.of(1, true, 2, false))
                .noDiffRefusalStreak(streak)
                .build()
                .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(
                        com.walpro.autobuilder.core.lifecycle.BasicSignals.EVOLUTION_NOOP,
                        1.0, "evolution", 5));
    }

    @Test
    @DisplayName("a refused #NoDiff promotion reaches the escalation, and a third one raises the item")
    void theRefusalBranchReachesTheEscalation() {
        GoalPlannerService planner = mock(GoalPlannerService.class);
        AutobuilderService s = serviceWithPlanner(planner);

        CycleContextData after = s.promoteOnNoDiffOrRefuse(noDiffWithUnmetCriterion(2));

        verify(injector).injectNoDiffDisagreementRedirection(
                eq(List.of("the second stated behaviour is observable")), eq(3), any());
        // The refusal must not promote: that is the contradiction being preserved, not resolved.
        verify(planner, never()).transitionGoal(any(), any(), anyString());
        assertThat(after.noDiffRefusalStreak()).isZero();
    }

    @Test
    @DisplayName("the first refusal counts without escalating, so the branch is not a one-shot")
    void theRefusalBranchCountsBeforeItEscalates() {
        AutobuilderService s = serviceWithPlanner(mock(GoalPlannerService.class));

        CycleContextData after = s.promoteOnNoDiffOrRefuse(noDiffWithUnmetCriterion(0));

        assertThat(after.noDiffRefusalStreak()).isEqualTo(1);
        verify(injector, never()).injectNoDiffDisagreementRedirection(any(), anyInt(), any());
    }

    /**
     * The other side of the branch: when the criteria agree with the model there is no
     * disagreement, the subgoal is promoted, and nothing is counted. This is the case that
     * actually happens in the field, which is why the refusal side needed a test of its own.
     */
    @Test
    @DisplayName("when the criteria agree, the subgoal is promoted and nothing is counted")
    void anAgreedNoDiffPromotesAndCountsNothing() {
        GoalPlannerService planner = mock(GoalPlannerService.class);
        AutobuilderService s = serviceWithPlanner(planner);
        CycleContextData agreed = CycleContextData.builder()
                .cycleName("cycle-1").currentSubGoalId("sub-1")
                .currentSubGoalCriteria(CRITERIA)
                .cumulativeCriteriaStatus(Map.of(1, true, 2, true))
                .noDiffRefusalStreak(2)
                .build()
                .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(
                        com.walpro.autobuilder.core.lifecycle.BasicSignals.EVOLUTION_NOOP,
                        1.0, "evolution", 5));

        CycleContextData after = s.promoteOnNoDiffOrRefuse(agreed);

        verify(planner).transitionGoal(eq("sub-1"), eq(com.walpro.autobuilder.goal.GoalStatus.COMPLETED), anyString());
        verify(injector, never()).injectNoDiffDisagreementRedirection(any(), anyInt(), any());
        assertThat(after.noDiffRefusalStreak()).as("a promotion clears the disagreement").isZero();
    }

    @Test
    @DisplayName("no #NoDiff signal, no decision: the branch is not entered at all")
    void withoutTheSignalNothingHappens() {
        AutobuilderService s = serviceWithPlanner(mock(GoalPlannerService.class));
        CycleContextData plain = CycleContextData.builder()
                .cycleName("cycle-1").currentSubGoalId("sub-1").noDiffRefusalStreak(2).build();

        CycleContextData after = s.promoteOnNoDiffOrRefuse(plain);

        assertThat(after.noDiffRefusalStreak()).isEqualTo(2);
        verify(injector, never()).injectNoDiffDisagreementRedirection(any(), anyInt(), any());
    }

}
