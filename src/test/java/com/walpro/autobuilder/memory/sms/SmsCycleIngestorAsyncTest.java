package com.walpro.autobuilder.memory.sms;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T6-J: the memory ingest moved off the cycle's critical path because it cost 92.9–121.3s per
 * cycle — ~78% of the loop's wall clock — while nothing in a cycle reads that memory back.
 *
 * <p>These tests pin the properties that make the move safe to live with rather than the write
 * itself: the caller must not wait, a full queue must lose work loudly instead of silently or by
 * growing without bound, and turning the switch off must restore the old synchronous behaviour
 * exactly.
 */
class SmsCycleIngestorAsyncTest {

    /** Counts and blocks, so a test can hold the single ingest thread and fill the queue behind it. */
    private static class CountingIngestor extends SmsCycleIngestor {
        final AtomicInteger ingested = new AtomicInteger();
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean block = false;

        CountingIngestor() {
            super(null, null, null);
        }

        @Override
        void ingestCycleBlocking(CycleContextData context) {
            if (block) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            ingested.incrementAndGet();
        }
    }

    private static CycleContextData ctx(String name) {
        return CycleContextData.builder().cycleName(name).build();
    }

    @Test
    void theCallerDoesNotWaitForTheWrite() throws Exception {
        CountingIngestor ingestor = new CountingIngestor();
        ingestor.setAsync(true);
        ingestor.startIngestThread();
        ingestor.block = true;

        long t0 = System.nanoTime();
        ingestor.ingestCycle(ctx("cycle-1"));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        // The whole point: a 94-second write must not be 94 seconds of the cycle.
        assertThat(elapsedMs).isLessThan(500);
        ingestor.release.countDown();
        ingestor.stopIngestThread();
    }

    @Test
    void aFullQueueDropsLoudlyRatherThanGrowingWithoutBound() throws Exception {
        CountingIngestor ingestor = new CountingIngestor();
        ingestor.setAsync(true);
        ingestor.startIngestThread();
        ingestor.block = true;

        // One occupies the thread, sixteen fill the queue, the rest must be refused rather than
        // queued — an unbounded queue would trade a throughput problem for a memory one.
        for (int i = 0; i < 40; i++) {
            ingestor.ingestCycle(ctx("cycle-" + i));
        }

        assertThat(ingestor.droppedCount()).isGreaterThan(0);
        ingestor.release.countDown();
        ingestor.stopIngestThread();
    }

    @Test
    void switchingItOffRestoresTheSynchronousBehaviourExactly() {
        CountingIngestor ingestor = new CountingIngestor();
        ingestor.setAsync(false);
        ingestor.startIngestThread();   // no-op when off

        ingestor.ingestCycle(ctx("cycle-1"));

        // Written by the time the caller returns, as it was before the change.
        assertThat(ingestor.ingested.get()).isEqualTo(1);
        assertThat(ingestor.droppedCount()).isZero();
    }

    @Test
    void aNullContextIsIgnoredInBothModes() {
        CountingIngestor async = new CountingIngestor();
        async.setAsync(true);
        async.startIngestThread();
        async.ingestCycle(null);
        async.stopIngestThread();

        CountingIngestor sync = new CountingIngestor();
        sync.setAsync(false);
        sync.ingestCycle(null);

        assertThat(async.ingested.get()).isZero();
        assertThat(sync.ingested.get()).isZero();
    }

    @Test
    void writesStayInCycleOrder() throws Exception {
        // One thread, so the graph sees ingestions in the order the loop produced them — the
        // change is about WHEN the write happens, not how many run at once.
        StringBuilder order = new StringBuilder();
        SmsCycleIngestor ingestor = new SmsCycleIngestor(null, null, null) {
            @Override
            void ingestCycleBlocking(CycleContextData context) {
                synchronized (order) {
                    order.append(context.cycleName()).append(' ');
                }
            }
        };
        ingestor.setAsync(true);
        ingestor.startIngestThread();

        for (int i = 0; i < 5; i++) ingestor.ingestCycle(ctx("c" + i));
        ingestor.stopIngestThread();

        assertThat(order.toString().trim()).isEqualTo("c0 c1 c2 c3 c4");
    }
}
