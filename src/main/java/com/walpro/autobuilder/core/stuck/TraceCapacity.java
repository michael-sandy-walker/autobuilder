package com.walpro.autobuilder.core.stuck;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * T6-T: how many execution traces may run at once -- by the memory this machine has available NOW and what one trace
 * was measured to take, never by a constant. A trace is a build and a test JVM held under the debugger; the box also
 * holds the local model, so the ceiling moves with it (2026-10-07: 1 GB available of 31 while a game ran beside a
 * batch, 7 GB after). Until one trace has been measured running alone, one at a time.
 */
@Slf4j
final class TraceCapacity {

    private TraceCapacity() {
    }

    /** What one trace took from the memory available, measured; -1 until a trace has run alone. */
    private static final AtomicLong costKb = new AtomicLong(-1);
    private static final AtomicInteger running = new AtomicInteger();
    private static final long SAMPLE_MS = 250;

    /** Whether a trace's cost has been measured. */
    static boolean known() {
        return costKb.get() > 0;
    }

    /** At least one and at most {@code tests}: as many as half the memory available now holds. */
    static int parallel(int tests) {
        long cost = costKb.get(), available = availableKb();
        if (tests <= 1 || cost <= 0 || available <= 0) return 1;
        return (int) Math.max(1, Math.min(tests, (available / 2) / cost));
    }

    /** Runs one trace; when it runs alone, how far the memory available fell is its measured cost. */
    static <T> T measured(Callable<T> trace) throws Exception {
        long before = availableKb();
        AtomicLong lowest = new AtomicLong(before);
        boolean alone = running.incrementAndGet() == 1;
        Thread sampler = null;
        if (before > 0) {
            sampler = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    long now = availableKb();
                    if (now > 0) lowest.accumulateAndGet(now, Math::min);
                    try {
                        Thread.sleep(SAMPLE_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "trace-memory-sampler");
            sampler.setDaemon(true);
            sampler.start();
        }
        try {
            return trace.call();
        } finally {
            if (sampler != null) sampler.interrupt();
            boolean stillAlone = running.getAndDecrement() == 1;
            long fell = before - lowest.get();
            if (alone && stillAlone && fell > 0) {
                costKb.accumulateAndGet(fell, Math::max);
                log.info("T4-DY: one trace took {} MB of the memory available", fell / 1024);
            }
        }
    }

    /** MemAvailable from /proc/meminfo, in kB; -1 where the system does not say. */
    static long availableKb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemAvailable:")) return Long.parseLong(line.replaceAll("\\D+", ""));
            }
        } catch (Exception e) {
            // not a system that reports it: one at a time
        }
        return -1L;
    }
}
