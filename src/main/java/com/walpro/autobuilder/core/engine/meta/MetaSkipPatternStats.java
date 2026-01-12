package com.walpro.autobuilder.core.engine.meta;

import java.time.Instant;

public final class MetaSkipPatternStats {

    private long count;
    private Instant lastSeen;

    public void inc() {
        count++;
        lastSeen = Instant.now();
    }

    public long count() { return count; }
    public Instant lastSeen() { return lastSeen; }
}
