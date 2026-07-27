package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.blocker.BlockerSubgoalInjector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T2-EQ: the packaging requirement binds at the halt edge, not per merge.
 *
 * <p>Both placements were field-tested on 2026-07-27. Per merge, the check hijacked the run at its
 * first merge; the run halted at 8.2 min having produced one file. These tests pin the revised
 * contract: ask once, where the run is ending, and hand the answer to the loop instead of dying.
 */
class PreHaltPackagingCheckTest {

    private BlockerSubgoalInjector injector;
    private PackageGate gate;
    private PreHaltPackagingCheck check;

    @BeforeEach
    void setUp() {
        injector = mock(BlockerSubgoalInjector.class);
        gate = mock(PackageGate.class);
        check = new PreHaltPackagingCheck(injector);
        check.setPackageGate(gate);
    }

    @Test
    void notPackageable_injectsRepairItemAndTellsCallerNotToHalt() {
        when(gate.verifyPackageable(anyString())).thenReturn(Optional.of("Unable to find main class"));
        // No item open yet; the injection makes one active.
        when(injector.hasActiveBuildFixSubgoal()).thenReturn(false, true);

        assertThat(check.rescueIfNotPackageable("cycle-1")).isTrue();

        org.mockito.ArgumentCaptor<BuildOutcome> outcome =
                org.mockito.ArgumentCaptor.forClass(BuildOutcome.class);
        verify(injector).injectBuildFixSubgoalIfAbsent(outcome.capture(), any());
        assertThat(outcome.getValue().result()).isEqualTo(BuildResult.PACKAGE_ERROR);
        // The build tool's own words are the payload — nothing here knows what an entry point is.
        assertThat(outcome.getValue().stderrTail()).contains("Unable to find main class");
    }

    @Test
    void notPackageable_passesNoContextPaths_soNoHealthyFileIsNamed() {
        when(gate.verifyPackageable(anyString())).thenReturn(Optional.of("Unable to find main class"));
        when(injector.hasActiveBuildFixSubgoal()).thenReturn(false, true);

        check.rescueIfNotPackageable("cycle-1");

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<String>> paths =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(injector).injectBuildFixSubgoalIfAbsent(any(), paths.capture());
        assertThat(paths.getValue()).isEmpty();
    }

    @Test
    void alreadyOpenItem_doesNotBlockTheHalt() {
        when(gate.verifyPackageable(anyString())).thenReturn(Optional.of("Unable to find main class"));
        when(injector.hasActiveBuildFixSubgoal()).thenReturn(true);

        // The loop was already handed this work and stalled anyway — one rescue per failure.
        assertThat(check.rescueIfNotPackageable("cycle-1")).isFalse();
        verify(injector, never()).injectBuildFixSubgoalIfAbsent(any(), any());
    }

    @Test
    void packagesCleanly_retiresTheItemThisCheckRaised() {
        when(gate.verifyPackageable(anyString())).thenReturn(Optional.empty());

        assertThat(check.rescueIfNotPackageable("cycle-1")).isFalse();

        // Only the gate that raised it can attest that it is done.
        verify(injector).clearPackagingBuildFixSubgoals();
        verify(injector, never()).injectBuildFixSubgoalIfAbsent(any(), any());
    }

    @Test
    void absentGate_keepsPreviousBehaviour() {
        check.setPackageGate(null);

        assertThat(check.rescueIfNotPackageable("cycle-1")).isFalse();
        verify(injector, never()).injectBuildFixSubgoalIfAbsent(any(), any());
        verify(injector, never()).clearPackagingBuildFixSubgoals();
    }
}
