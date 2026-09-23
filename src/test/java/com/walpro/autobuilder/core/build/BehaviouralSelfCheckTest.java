package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
        check.setEnabled(true);
        check.setIntervalCycles(1);
    }

    @Test
    void reportsWhatTheRunningApplicationContradicts() {
        when(packageGate.verifyPackageable(anyString())).thenReturn(Optional.empty());
        when(bootGate.observeBootsAndServes(anyString(), any(), any(), anyBoolean()))
                .thenReturn(refuted("POST /members → 400, but the running application answered 201"));

        assertThat(check.observe(ctx(), SPEC, () -> REQS))
                .contains("POST /members → 400, but the running application answered 201");
    }

    @Test
    void saysNothingWhenTheApplicationAgreesWithTheSpec() {
        when(packageGate.verifyPackageable(anyString())).thenReturn(Optional.empty());
        when(bootGate.observeBootsAndServes(anyString(), any(), any(), anyBoolean())).thenReturn(clean());

        assertThat(check.observe(ctx(), SPEC, () -> REQS)).isEmpty();
    }

    @Test
    void observesOnlyOnTheInterval() {
        check.setIntervalCycles(3);
        when(packageGate.verifyPackageable(anyString())).thenReturn(Optional.empty());
        when(bootGate.observeBootsAndServes(anyString(), any(), any(), anyBoolean())).thenReturn(clean());

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
        verify(bootGate, never()).observeBootsAndServes(anyString(), any(), any(), anyBoolean());
    }

    @Test
    void theSameFindingIsNotReRaisedWhileItIsStillOpen() {
        when(packageGate.verifyPackageable(anyString())).thenReturn(Optional.empty());
        when(bootGate.observeBootsAndServes(anyString(), any(), any(), anyBoolean()))
                .thenReturn(refuted("POST /members → 400, but the application answered 201"));

        assertThat(check.observe(ctx(), SPEC, () -> REQS)).isPresent();
        assertThat(check.observe(ctx(), SPEC, () -> REQS)).isEmpty();
    }

    @Test
    void aNewFindingAfterACleanObservationIsRaisedAgain() {
        when(packageGate.verifyPackageable(anyString())).thenReturn(Optional.empty());
        when(bootGate.observeBootsAndServes(anyString(), any(), any(), anyBoolean()))
                .thenReturn(refuted("defect A"), clean(), refuted("defect A"));

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
                .cycleName("cycle-1").buildOutcome(outcome).build();

        assertThat(check.observe(broken, SPEC, () -> REQS)).isEmpty();
        verify(packageGate, never()).verifyPackageable(anyString());
    }

    @Test
    void aRejectedCandidatesFailedProbe_doesNotWithholdTheLook_whenTheTreeOnDiskCompiles() {
        // Run 2026-09-23_20-20-50: from 20:33 every candidate failed its compile probe and was
        // rolled back, while the merged tree compiled at the end of every cycle. The check read
        // the candidate's probe and skipped four looks in a row. The probe describes a tree that
        // was rolled back; the post-merge build describes the one on disk.
        var failedCandidate = mock(com.walpro.autobuilder.core.build.BuildOutcome.class);
        when(failedCandidate.result()).thenReturn(BuildResult.COMPILE_ERROR);
        var mergedTree = mock(com.walpro.autobuilder.core.build.BuildOutcome.class);
        when(mergedTree.result()).thenReturn(BuildResult.COMPILE_OK);
        CycleContextData ctx = CycleContextData.builder().cycleName("cycle-1")
                .lastProbeOutcome(failedCandidate).buildOutcome(mergedTree).build();
        when(packageGate.verifyPackageable(anyString())).thenReturn(Optional.empty());
        when(bootGate.observeBootsAndServes(anyString(), any(), any(), anyBoolean())).thenReturn(clean());

        check.observe(ctx, SPEC, () -> REQS);

        verify(packageGate, times(1)).verifyPackageable(anyString());
    }

    @Test
    void aCheapAbstentionDoesNotConsumeTheInterval() {
        // The defect this fixes cost a whole run: 2026-07-28_21-39-59 got four looks in 41 cycles
        // and the tree was mid-repair at every one, so it abstained four times and observed nothing.
        // A skip that spent no time must leave the check ready to look on the NEXT cycle, otherwise
        // the interval is spent waiting rather than observing.
        check.setIntervalCycles(4);
        var outcome = mock(com.walpro.autobuilder.core.build.BuildOutcome.class);
        when(outcome.result()).thenReturn(BuildResult.COMPILE_ERROR);
        CycleContextData broken = CycleContextData.builder()
                .cycleName("cycle-broken").buildOutcome(outcome).build();
        when(packageGate.verifyPackageable(anyString())).thenReturn(Optional.empty());
        when(bootGate.observeBootsAndServes(anyString(), any(), any(), anyBoolean())).thenReturn(clean());

        // Cycles 1-3 are inside the interval; cycle 4 reaches the check and abstains on the build.
        for (int cycle = 1; cycle <= 4; cycle++) {
            assertThat(check.observe(broken, SPEC, () -> REQS)).isEmpty();
        }
        verify(packageGate, never()).verifyPackageable(anyString());

        // Cycle 5, build now healthy: the check looks immediately rather than waiting to cycle 8.
        assertThat(check.observe(ctx(), SPEC, () -> REQS)).isEmpty();
        verify(packageGate, times(1)).verifyPackageable(anyString());
    }

    @Test
    void anObservationThatSpentTimeDoesRestartTheInterval() {
        // The other half: a real look costs a package build and a boot, so it must not repeat every
        // cycle. Only the paths that spend time reset the counter.
        check.setIntervalCycles(4);
        when(packageGate.verifyPackageable(anyString())).thenReturn(Optional.empty());
        when(bootGate.observeBootsAndServes(anyString(), any(), any(), anyBoolean())).thenReturn(clean());

        for (int cycle = 1; cycle <= 4; cycle++) {
            check.observe(ctx(), SPEC, () -> REQS);
        }
        verify(packageGate, times(1)).verifyPackageable(anyString());

        check.observe(ctx(), SPEC, () -> REQS); // cycle 5 — inside the fresh interval
        verify(packageGate, times(1)).verifyPackageable(anyString());
    }

    @Test
    void everyReasonForNotObservingIsRecorded_soSilenceIsNeverAmbiguous() {
        // criterion_probe_runs=0 stood for fourteen runs because nothing said the check had not
        // looked. An abstention is fine; being unable to tell it from a clean observation is not.
        var logger = mock(com.walpro.autobuilder.observability.behaviour.BehaviourLogger.class);
        check.setBehaviourLogger(logger);

        assertThat(check.observe(ctx(), SPEC, List::of)).isEmpty();

        verify(logger, times(1)).buildFixAttempt(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any(), any(), org.mockito.ArgumentMatchers.eq("ABSTAIN"),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void anUnknownBuildStateStillAttempts_soALookIsNeverInvented() {
        when(packageGate.verifyPackageable(anyString())).thenReturn(Optional.empty());
        when(bootGate.observeBootsAndServes(anyString(), any(), any(), anyBoolean())).thenReturn(clean());

        assertThat(check.observe(ctx(), SPEC, () -> REQS)).isEmpty();
        verify(packageGate, times(1)).verifyPackageable(anyString());
    }

    @Test
    void aRunawayRequirementListIsCappedBeforeAnyCostHasBeenMeasured() {
        // Run 2026-07-28_23-16-15: an uncapped list of 73/77 requirements turned two observations
        // into 29.5 and 14.9 minutes of a 90-minute run and tripped the LLM circuit breaker.
        // T2-FL: the backstop still holds on the FIRST observation, when there is no measured cost
        // to spend against — but it is a pathology bound now, far above any real set.
        List<String> many = java.util.stream.IntStream.range(0, 400)
                .mapToObj(i -> "GET /items returns HTTP 200 variant " + i).toList();
        when(packageGate.verifyPackageable(anyString())).thenReturn(Optional.empty());
        when(bootGate.observeBootsAndServes(anyString(), any(), any(), anyBoolean())).thenReturn(clean());

        check.observe(ctx(), SPEC, () -> many);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(bootGate).observeBootsAndServes(anyString(), any(), captor.capture(), anyBoolean());
        assertThat(captor.getValue()).hasSize(BehaviouralSelfCheck.MAX_REQUIREMENTS);
    }

    /**
     * T2-FL: the bound is what the budget BUYS at the cost this run measured, and the cost is the
     * PROBE PHASE — not the whole observation. Charging the launch to the requirements made the
     * estimate rise as the count fell, so one slow boot ratcheted the bound down: on run
     * 2026-09-23_02-48-06 a 65 s observation took it from 76 to 6 and the next sweep asked 6 of 16
     * answerable claims while reporting "probed=6 skipped=0 contradicted=0".
     */
    @Test
    void theBoundIsWhatTheBudgetBuysAtTheProbeCostThisRunMeasured() {
        List<String> many = java.util.stream.IntStream.range(0, 120)
                .mapToObj(i -> "GET /items returns HTTP 200 variant " + i).toList();
        when(packageGate.verifyPackageable(anyString())).thenReturn(Optional.empty());
        check.setIntervalCycles(1);

        // 120 requirements probed in 12 s = 100 ms each; the 20 s budget buys 200, so the
        // backstop bounds it and the full list is admitted.
        when(bootGate.observeBootsAndServes(anyString(), any(), any(), anyBoolean()))
                .thenReturn(cleanCosting(12_000));
        check.observe(ctx(), SPEC, () -> many);

        // Same list probed in 120 s = 1000 ms each; the budget now buys 20.
        when(bootGate.observeBootsAndServes(anyString(), any(), any(), anyBoolean()))
                .thenReturn(cleanCosting(120_000));
        check.observe(ctx(), SPEC, () -> many);
        check.observe(ctx(), SPEC, () -> many);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(bootGate, org.mockito.Mockito.atLeast(3))
                .observeBootsAndServes(anyString(), any(), captor.capture(), anyBoolean());
        assertThat(captor.getAllValues().get(captor.getAllValues().size() - 1)).hasSize(20);
    }

    /**
     * T2-FL: a LAUNCH that takes forever says nothing about what a requirement costs, so it must
     * not shrink the next observation. This is the run-2026-09-23_02-48-06 defect in unit form.
     */
    @Test
    void aSlowLaunchDoesNotRationTheNextObservation() {
        List<String> many = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> "GET /items returns HTTP 200 variant " + i).toList();
        when(packageGate.verifyPackageable(anyString())).thenReturn(Optional.empty());
        check.setIntervalCycles(1);

        // 65 seconds of observation, of which the probes were 200 ms: a slow boot, cheap questions.
        when(bootGate.observeBootsAndServes(anyString(), any(), any(), anyBoolean()))
                .thenReturn(cleanCosting(200));
        check.observe(ctx(), SPEC, () -> many);
        check.observe(ctx(), SPEC, () -> many);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(bootGate, org.mockito.Mockito.atLeast(2))
                .observeBootsAndServes(anyString(), any(), captor.capture(), anyBoolean());
        assertThat(captor.getAllValues().get(captor.getAllValues().size() - 1)).hasSize(20);
    }

    /** A clean observation whose criterion probing took {@code probeMs}. */
    private static BootGate.BootObservation cleanCosting(long probeMs) {
        return new BootGate.BootObservation(true, java.util.List.of(), java.util.List.of(),
                probeMs, Optional.empty());
    }

    @Test
    void canBeSwitchedOff() {
        check.setEnabled(false);
        assertThat(check.observe(ctx(), SPEC, () -> REQS)).isEmpty();
        verify(packageGate, never()).verifyPackageable(anyString());
    }

    /**
     * T4-DW: the self-check now asks the gate for its OBSERVATION, not only its verdict, because
     * retiring work on {@code Optional.empty()} would retire it on the gate's eleven abstention
     * paths too. These helpers keep the existing tests saying what they said — a finding, or none.
     */
    private static BootGate.BootObservation refuted(String description) {
        return new BootGate.BootObservation(false, java.util.List.of(), java.util.List.of(), 0L,
                Optional.of(new com.walpro.autobuilder.goal.ClaimRefutation(description, java.util.List.of())));
    }

    private static BootGate.BootObservation clean() {
        return new BootGate.BootObservation(true, java.util.List.of(), java.util.List.of(), 0L, Optional.empty());
    }

}
