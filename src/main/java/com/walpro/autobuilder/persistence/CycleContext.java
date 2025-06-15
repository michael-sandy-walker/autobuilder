package com.walpro.autobuilder.persistence;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

public class CycleContext {

    private final String name;
    private final Instant timestamp;
    private final double score;
    private final String comment;

    public CycleContext(String name, double score, String comment) {
        this.name = name;
        this.timestamp = Instant.now();
        this.score = score;
        this.comment = comment;
    }

    public String getId() {
        return name + "-" + DateTimeFormatter.ISO_INSTANT.format(timestamp).replace(":", "-");
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public double getScore() {
        return score;
    }

    public String getComment() {
        return comment;
    }

    public String getName() {
        return name;
    }
}
