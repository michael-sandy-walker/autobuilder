package com.walpro.autobuilder.core.service;

import com.walpro.autobuilder.core.blocker.BlockerSubgoalInjector;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

    /** Criterion at index 1 is failing; index 0 passed. The convention is zero-keyed. */
    private static CycleContextData disagreeing(int streak) {
        return CycleContextData.builder()
                .cycleName("cycle-1")
                .currentSubGoalId("sub-1")
                .currentSubGoalCriteria(CRITERIA)
                .cumulativeCriteriaStatus(Map.of(0, true, 1, false))
                .noDiffRefusalStreak(streak)
                .build();
    }

    @Test
    @DisplayName("the third identical disagreement escalates, the first two do not")
    void escalatesOnTheThirdRepeatNotBefore() {
        CycleContextData c1 = service.escalateNoDiffDisagreement(disagreeing(0), "sub-1");
        assertThat(c1.noDiffRefusalStreak()).isEqualTo(1);
        verify(injector, never()).injectNoDiffDisagreementRedirection(any(), anyInt(), any());

        CycleContextData c2 = service.escalateNoDiffDisagreement(disagreeing(c1.noDiffRefusalStreak()), "sub-1");
        assertThat(c2.noDiffRefusalStreak()).isEqualTo(2);
        verify(injector, never()).injectNoDiffDisagreementRedirection(any(), anyInt(), any());

        service.escalateNoDiffDisagreement(disagreeing(c2.noDiffRefusalStreak()), "sub-1");

        verify(injector, times(1)).injectNoDiffDisagreementRedirection(
                eq(List.of("the second stated behaviour is observable")), eq(3), any());
    }

    @Test
    @DisplayName("only the failing criteria are handed over, in the planner's words")
    void onlyUnmetCriteriaAreEscalated() {
        service.escalateNoDiffDisagreement(disagreeing(2), "sub-1");

        verify(injector).injectNoDiffDisagreementRedirection(
                eq(List.of("the second stated behaviour is observable")), eq(3), eq(List.of()));
    }

    @Test
    @DisplayName("the streak restarts after an escalation, so it does not fire every cycle from then on")
    void theStreakRestartsAfterEscalating() {
        CycleContextData after = service.escalateNoDiffDisagreement(disagreeing(2), "sub-1");
        assertThat(after.noDiffRefusalStreak()).isZero();
    }

    @Test
    @DisplayName("the derived peer differences are passed through when the analyzer finds any")
    void derivedStructuralDifferencesAreHandedOver() {
        var analyzer = mock(com.walpro.autobuilder.analysis.structure.WiringFactsAnalyzer.class);
        when(analyzer.peerAnnotationAnomalies(any())).thenReturn(List.of("Thing differs from its peers"));
        ReflectionTestUtils.setField(service, "wiringFactsAnalyzer", analyzer);
        CycleContextData ctx = disagreeing(2).withPreviousOutput(Map.of("A.java", "class A {}"));

        service.escalateNoDiffDisagreement(ctx, "sub-1");

        verify(injector).injectNoDiffDisagreementRedirection(
                any(), eq(3), eq(List.of("Thing differs from its peers")));
    }

    @Test
    @DisplayName("with no criteria on record the disagreement is still counted, and the injector decides")
    void withNoCriteriaTheInjectorStillDecides() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-1").currentSubGoalId("sub-1").noDiffRefusalStreak(2).build();

        service.escalateNoDiffDisagreement(ctx, "sub-1");

        // The service does not second-guess the actuator: it hands over what it has, and the
        // injector refuses an item that would say nothing. One home for that judgement.
        verify(injector).injectNoDiffDisagreementRedirection(eq(List.of()), eq(3), eq(List.of()));
    }

    @Test
    @DisplayName("without an injector the streak is still counted — pre-T2-ES behaviour, not a crash")
    void withoutAnInjectorNothingBreaks() {
        service.setBlockerSubgoalInjector(null);

        CycleContextData after = service.escalateNoDiffDisagreement(disagreeing(2), "sub-1");

        assertThat(after.noDiffRefusalStreak()).isEqualTo(3);
    }
}
