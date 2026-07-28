package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4-CY: the loop observing its own running output mid-run.
 *
 * <p>Ground truth: over 16 recorded runs, 6 never emptied their backlog, so every gate — package,
 * boot, criterion probes — ran zero times and the artifact shipped unexamined; two of those scored
 * 10/23 and 11/23 with a booting application nobody had looked at. And on 2026-07-28 a run produced
 * a hand-verified 23/23 and ended on a deadline, unable to tell it apart from the 19/23 it had
 * earlier declared complete.
 */
class BehaviouralSelfCheckTest {

    private final PackageGate packageGate = mock(PackageGate.class);
    private final BootGate bootGate = mock(BootGate.class);
    private BehaviouralSelfCheck check;

    private static final String SPEC = "Library API. POST /members (name required). GET /books (list all).";
    private static final List<String> REQS = List.of("POST /members returns HTTP 400 when name is missing");

    private static CycleContextData ctx() {
        return CycleContextData.builder().cycleName("cycle-1").build();
    }

    @BeforeEach
    void setUp() {
        check = new BehaviouralSelfCheck(packageGate, bootGate);
        check.setIntervalCycles(1);
    }

    @Test
    void reportsWhatTheRunningApplicationContradicts() {
        when(packageGate.verifyPackageable(anyString())).thenReturn(Optional.empty());
        when(bootGate.verifyBootsAndServes(anyString(), any(), any()))
                .thenReturn(Optional.of("POST /members → 400, but the running application answered 201"));

        assertThat(check.observe(ctx(), SPEC, () -> REQS))
                .contains("POST /members → 400, but the running application answered 201");
    }

    @Test
    void saysNothingWhenTheApplicationAgreesWithTheSpec() {
        when(packageGate.verifyPackageable(anyString())).thenReturn(Optional.empty());
        when(bootGate.verifyBootsAndServes(anyString(), any(), any())).thenReturn(Optional.empty());

        assertThat(check.observe(ctx(), SPEC, () -> REQS)).isEmpty();
    }

    @Test
    void observesOnlyOnTheInterval() {
        check.setIntervalCycles(3);
        when(packageGate.verifyPackageable(anyString())).thenReturn(Optional.empty());
        when(bootGate.verifyBootsAndServes(anyString(), any(), any())).thenReturn(Optional.empty());

        check.observe(ctx(), SPEC, () -> REQS);
        check.observe(ctx(), SPEC, () -> REQS);
        verify(packageGate, never()).verifyPackageable(anyString());

        check.observe(ctx(), SPEC, () -> REQS);
        verify(packageGate, times(1)).verifyPackageable(anyString());
    }

    @Test
    void anUnpackageableArtifactIsNotThisChecksFinding() {
        // The build machinery owns packageability and has its own gates. This check only observes
        // BEHAVIOUR, so it must not turn a build problem into a behavioural work item.
        when(packageGate.verifyPackageable(anyString()))
                .thenReturn(Optional.of("cannot package: no main class"));

        assertThat(check.observe(ctx(), SPEC, () -> REQS)).isEmpty();
        verify(bootGate, never()).verifyBootsAndServes(anyString(), any(), any());
    }

    @Test
    void theSameFindingIsNotReRaisedWhileItIsStillOpen() {
        when(packageGate.verifyPackageable(anyString())).thenReturn(Optional.empty());
        when(bootGate.verifyBootsAndServes(anyString(), any(), any()))
                .thenReturn(Optional.of("POST /members → 400, but the application answered 201"));

        assertThat(check.observe(ctx(), SPEC, () -> REQS)).isPresent();
        assertThat(check.observe(ctx(), SPEC, () -> REQS)).isEmpty();
    }

    @Test
    void aNewFindingAfterACleanObservationIsRaisedAgain() {
        when(packageGate.verifyPackageable(anyString())).thenReturn(Optional.empty());
        when(bootGate.verifyBootsAndServes(anyString(), any(), any()))
                .thenReturn(Optional.of("defect A"), Optional.empty(), Optional.of("defect A"));

        assertThat(check.observe(ctx(), SPEC, () -> REQS)).contains("defect A");
        assertThat(check.observe(ctx(), SPEC, () -> REQS)).isEmpty();   // clean — memory cleared
        assertThat(check.observe(ctx(), SPEC, () -> REQS)).contains("defect A");
    }

    @Test
    void abstainsWithNothingToAskAbout() {
        assertThat(check.observe(ctx(), null, () -> REQS)).isEmpty();
        assertThat(check.observe(ctx(), "   ", () -> REQS)).isEmpty();
        assertThat(check.observe(ctx(), SPEC, List::of)).isEmpty();
        assertThat(check.observe(ctx(), SPEC, null)).isEmpty();
        verify(packageGate, never()).verifyPackageable(anyString());
    }

    @Test
    void aKnownBrokenBuildSkipsTheInterval_withoutSpendingAPackage() {
        // The loop compiles every cycle; consulting that result costs nothing and avoids a
        // `mvn package` certain to fail. Run 2026-07-28_21-39-59 spent all four of its
        // observations exactly that way and saw nothing all run.
        var outcome = mock(com.walpro.autobuilder.core.build.BuildOutcome.class);
        when(outcome.result()).thenReturn(BuildResult.COMPILE_ERROR);
        CycleContextData broken = CycleContextData.builder()
                .cycleName("cycle-1").lastProbeOutcome(outcome).build();

        assertThat(check.observe(broken, SPEC, () -> REQS)).isEmpty();
        verify(packageGate, never()).verifyPackageable(anyString());
    }

    @Test
    void anUnknownBuildStateStillAttempts_soALookIsNeverInvented() {
        when(packageGate.verifyPackageable(anyString())).thenReturn(Optional.empty());
        when(bootGate.verifyBootsAndServes(anyString(), any(), any())).thenReturn(Optional.empty());

        assertThat(check.observe(ctx(), SPEC, () -> REQS)).isEmpty();
        verify(packageGate, times(1)).verifyPackageable(anyString());
    }

    @Test
    void canBeSwitchedOff() {
        check.setEnabled(false);
        assertThat(check.observe(ctx(), SPEC, () -> REQS)).isEmpty();
        verify(packageGate, never()).verifyPackageable(anyString());
    }
}
