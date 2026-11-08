package com.walpro.autobuilder.core.service;

import com.walpro.autobuilder.core.checkpoint.CacheMode;
import com.walpro.autobuilder.core.checkpoint.RewindService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.CycleResultState;
import com.walpro.autobuilder.core.lifecycle.RootConvergenceTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4-EL: an operator rewind asked for while a cycle runs is queued and performed at the next cycle
 * boundary, instead of being refused. In a preflight run the ticks follow each other within a
 * millisecond, so the refusal was every request: 2026-11-08 a 3-second retry loop failed for four
 * minutes, and pausing first ends a preflight run as an operator stop.
 *
 * <p>A running cycle is stood in for by another thread holding the control-loop lock.
 */
class AutobuilderServiceQueuedRewindTest {

    private AutobuilderService service;
    private RootConvergenceTracker tracker;
    private RewindService rewinds;
    private Thread cycleThread;
    private CountDownLatch cycleEnds;

    @BeforeEach
    void setUp() {
        tracker = mock(RootConvergenceTracker.class);
        service = new AutobuilderService(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "rootConvergenceTracker", tracker);
        rewinds = mock(RewindService.class);
        service.setRewindService(rewinds);
        ReflectionTestUtils.setField(service, "context",
                CycleContextData.builder().cycleName("cycle-30").build()
                        .withCycleResultState(CycleResultState.CONTINUE));
    }

    @AfterEach
    void endTheCycle() throws InterruptedException {
        if (cycleEnds != null) cycleEnds.countDown();
        if (cycleThread != null) cycleThread.join(5000);
    }

    @Test
    @DisplayName("asked mid-cycle: queued, not refused, and nothing is restored until the boundary")
    void aRequestDuringACycleIsQueued() throws Exception {
        aCycleIsRunning();

        var request = service.requestOperatorRewind("cycle-12", CacheMode.FAITHFUL);

        assertThat(request.queued()).isTrue();
        assertThat(request.result()).isNull();
        verify(rewinds, never()).rewindTo(anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("the next tick performs the queued rewind, settles the context, and runs no cycle itself")
    void theNextTickPerformsIt() throws Exception {
        when(rewinds.rewindTo(anyString(), any(), anyString(), anyString())).thenReturn(performed("cycle-12"));
        aCycleIsRunning();
        service.requestOperatorRewind("cycle-12", CacheMode.FAITHFUL);
        theCycleEnds();

        ReflectionTestUtils.invokeMethod(service, "runAutobuilderCycleLocked");

        verify(rewinds).rewindTo("cycle-12", CacheMode.FAITHFUL, "operator", "cycle-30");
        verify(tracker).resyncAfterRewind();
        assertThat(service.getContext().orElseThrow().cycleResultState()).isEqualTo(CycleResultState.CONTINUE);
    }

    @Test
    @DisplayName("a second request before the boundary replaces the first: the operator's latest choice counts")
    void theLatestRequestWins() throws Exception {
        when(rewinds.rewindTo(anyString(), any(), anyString(), anyString())).thenReturn(performed("cycle-20"));
        aCycleIsRunning();
        service.requestOperatorRewind("cycle-12", CacheMode.FAITHFUL);
        service.requestOperatorRewind("cycle-20", CacheMode.FRESH);
        theCycleEnds();

        ReflectionTestUtils.invokeMethod(service, "runAutobuilderCycleLocked");
        Boolean anotherWaiting = ReflectionTestUtils.invokeMethod(service, "performQueuedOperatorRewind");

        assertThat(anotherWaiting).as("one rewind performed, none left waiting").isFalse();
        verify(rewinds).rewindTo("cycle-20", CacheMode.FRESH, "operator", "cycle-30");
        verify(rewinds, never()).rewindTo(eqCycle("cycle-12"), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("between cycles the rewind still happens at once, as before")
    void anIdleLoopRewindsAtOnce() {
        when(rewinds.rewindTo(anyString(), any(), anyString(), anyString())).thenReturn(performed("cycle-12"));

        var request = service.requestOperatorRewind("cycle-12", CacheMode.FAITHFUL);

        assertThat(request.queued()).isFalse();
        assertThat(request.result().performed()).isTrue();
        verify(rewinds).rewindTo("cycle-12", CacheMode.FAITHFUL, "operator", "cycle-30");
    }

    @Test
    @DisplayName("a queued rewind refused at the boundary leaves the carried context as it was")
    void aRefusalAtTheBoundaryChangesNothing() throws Exception {
        when(rewinds.rewindTo(anyString(), any(), anyString(), anyString()))
                .thenReturn(new RewindService.Result(false, "cycle-12", "no checkpoint for that cycle",
                        null, 0, 0, 0, 0, 0, false, List.of(), null, "none"));
        ReflectionTestUtils.setField(service, "context",
                CycleContextData.builder().cycleName("cycle-30").build()
                        .withCycleResultState(CycleResultState.GOAL_FULFILLED));
        aCycleIsRunning();
        service.requestOperatorRewind("cycle-12", CacheMode.FAITHFUL);
        theCycleEnds();

        ReflectionTestUtils.invokeMethod(service, "performQueuedOperatorRewind");

        assertThat(service.getContext().orElseThrow().cycleResultState()).isEqualTo(CycleResultState.GOAL_FULFILLED);
        verify(tracker, never()).resyncAfterRewind();
    }

    // ---- helpers ----

    private void aCycleIsRunning() throws InterruptedException {
        ReentrantLock lock = (ReentrantLock) ReflectionTestUtils.getField(service, "lock");
        CountDownLatch holding = new CountDownLatch(1);
        cycleEnds = new CountDownLatch(1);
        cycleThread = new Thread(() -> {
            lock.lock();
            try {
                holding.countDown();
                cycleEnds.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }, "a-running-cycle");
        cycleThread.start();
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private void theCycleEnds() throws InterruptedException {
        cycleEnds.countDown();
        cycleThread.join(5000);
    }

    private static String eqCycle(String id) {
        return org.mockito.ArgumentMatchers.eq(id);
    }

    private static RewindService.Result performed(String toCycleId) {
        return new RewindService.Result(true, toCycleId, null, CacheMode.FAITHFUL, 3, 0, 1, 0, 2, true,
                List.of(), null, "none");
    }
}
