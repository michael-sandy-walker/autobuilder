package com.walpro.autobuilder.core.service;

import com.walpro.autobuilder.client.LlmCircuitBreaker;
import com.walpro.autobuilder.core.lifecycle.CycleContextCoordinator;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T1-GC: a cycle that cannot start must not retry faster than one that succeeds.
 *
 * <p><b>The defect this pins, measured rather than imagined.</b> On 2026-09-19 the Docker daemon
 * died and took Neo4j with it. {@code prepareRuntimeContext} began failing in about 8ms, and the
 * {@code fast} profile's 1ms scheduler delay became the only thing between iterations:
 * <b>29,500 aborted cycles in 230 seconds, 128 per second, 231 MB of log</b> — on a host already
 * short of the memory that had killed the database. The mechanism was established rather than
 * assumed: {@code Failed to prepare runtime context} and {@code Aborting cycle} each appear
 * exactly 29,500 times, so it was one abort per scheduler tick and not a retry inside one call.
 *
 * <p>The tests drive a scripted clock, so a sixty-second backoff is asserted without waiting one.
 */
class ContextFailureBackoffTest {

    private AutobuilderService service;
    private CycleContextCoordinator coordinator;
    private AtomicLong now;

    @BeforeEach
    void setUp() {
        coordinator = mock(CycleContextCoordinator.class);
        service = new AutobuilderService(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        // By name rather than constructor position: sixteen collaborators, and counting positions
        // is how a test ends up asserting about the wrong one.
        ReflectionTestUtils.setField(service, "contextCoordinator", coordinator);
        // Consulted on every tick before the context is prepared; a closed breaker is the
        // uninteresting case here and keeps this test about the backoff and nothing else.
        LlmCircuitBreaker breaker = mock(LlmCircuitBreaker.class);
        when(breaker.isOpen()).thenReturn(false);
        ReflectionTestUtils.setField(service, "llmCircuitBreaker", breaker);
        ReflectionTestUtils.setField(service, "schedulerEnabled", true);
        now = new AtomicLong(1_000_000L);
        service.setClock(now::get);
    }

    private void failContext() {
        when(coordinator.prepareRuntimeContext(any())).thenReturn(Optional.empty());
    }

    /** Ticks the scheduler n times without advancing the clock, as a hot loop would. */
    private void tick(int n) {
        for (int i = 0; i < n; i++) service.runAutobuilderCycle();
    }

    @Test
    void aStormOfTicksAgainstADeadDependencyReachesTheCoordinatorOnlyOnce() {
        // The whole defect in one assertion: 200 ticks with no time passing must not become 200
        // attempts. Before the fix this was one attempt per tick, and each one logged a stack.
        failContext();

        tick(200);

        verify(coordinator, times(1)).prepareRuntimeContext(any());
    }

    @Test
    void theBackoffDoublesPerConsecutiveFailureAndIsServedByTheClock() {
        failContext();

        service.runAutobuilderCycle();                       // failure 1 -> wait 1s
        now.addAndGet(999);
        service.runAutobuilderCycle();                       // still inside the window
        verify(coordinator, times(1)).prepareRuntimeContext(any());

        now.addAndGet(1);                                    // window served
        service.runAutobuilderCycle();                       // failure 2 -> wait 2s
        verify(coordinator, times(2)).prepareRuntimeContext(any());

        now.addAndGet(1_999);
        service.runAutobuilderCycle();                       // still inside the longer window
        verify(coordinator, times(2)).prepareRuntimeContext(any());

        now.addAndGet(1);
        service.runAutobuilderCycle();                       // failure 3
        verify(coordinator, times(3)).prepareRuntimeContext(any());
    }

    @Test
    void theBackoffIsCappedSoAnOutageNeverStopsTheLoopForever() {
        // Doubling without a ceiling would put the loop to sleep for days on a long outage, which
        // trades a spin for a hang. The cap is the documented scheduler cadence.
        failContext();
        for (int i = 0; i < 30; i++) {
            service.runAutobuilderCycle();
            now.addAndGet(120_000);                          // always serve whatever was asked
        }

        long notBefore = (long) ReflectionTestUtils.getField(service, "contextRetryNotBefore");
        assertThat(notBefore - now.get())
                .as("the wait must never exceed the 60s cap, however long the outage runs")
                .isLessThanOrEqualTo(60_000L);
    }

    @Test
    void aPreparedContextClearsTheBackoffSoRecoveryIsImmediate() {
        failContext();
        service.runAutobuilderCycle();
        assertThat((long) ReflectionTestUtils.getField(service, "contextRetryNotBefore")).isPositive();

        // The dependency comes back. Nothing else should have to happen for the loop to resume.
        when(coordinator.prepareRuntimeContext(any()))
                .thenReturn(Optional.of(CycleContextData.builder().cycleName("c1").build()));
        now.addAndGet(1_000);
        try {
            service.runAutobuilderCycle();
        } catch (RuntimeException expected) {
            // The cycle proceeds past context preparation into collaborators this test does not
            // wire. Reaching them is the point; what they do is another test's business.
        }

        assertThat((long) ReflectionTestUtils.getField(service, "contextRetryNotBefore"))
                .as("a success clears the backoff rather than leaving the loop throttled")
                .isZero();
        assertThat((int) ReflectionTestUtils.getField(service, "consecutiveContextFailures")).isZero();
    }

    @Test
    void theBackoffDoesNotFireWhenNothingHasFailed() {
        // The control. A guard that throttles a healthy loop would be worse than the defect.
        when(coordinator.prepareRuntimeContext(any()))
                .thenReturn(Optional.of(CycleContextData.builder().cycleName("c1").build()));

        for (int i = 0; i < 3; i++) {
            try { service.runAutobuilderCycle(); } catch (RuntimeException ignored) { /* see above */ }
        }

        verify(coordinator, times(3)).prepareRuntimeContext(any());
    }
}
