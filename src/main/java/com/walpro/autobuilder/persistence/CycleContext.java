package com.walpro.autobuilder.persistence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.walpro.autobuilder.model.SubgoalMetadata;
import lombok.Builder;
import lombok.Getter;
import lombok.With;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@With
@Builder
public class CycleContext {

    private final String name;
    private final Instant timestamp;
    private final double score;
    private final String comment;
    private final boolean merged;
    private final SubgoalMetadata subgoalMetadata;

    @JsonCreator
    public CycleContext(
            @JsonProperty("name") String name,
            @JsonProperty("timestamp") Instant timestamp,
            @JsonProperty("score") double score,
            @JsonProperty("comment") String comment,
            @JsonProperty("merged") boolean merged,
            @JsonProperty("subgoalMetadata") SubgoalMetadata subgoalMetadata
    ) {
        this.name = name;
        this.timestamp = timestamp;
        this.score = score;
        this.comment = comment;
        this.merged = merged;
        this.subgoalMetadata = subgoalMetadata;
    }

    public String getId() {
        return name + "-" + DateTimeFormatter.ISO_INSTANT.format(timestamp).replace(":", "-");
    }

    public static CycleContext of(String name, double score, String comment, boolean merged, SubgoalMetadata subgoalMetadata) {
        return CycleContext.builder()
                .name(name)
                .score(score)
                .comment(comment)
                .merged(merged)
                .timestamp(Instant.now())
                .subgoalMetadata(subgoalMetadata)
                .build();
    }

    public static CycleContext of(String name, double score, String comment, SubgoalMetadata subgoalMetadata) {
        return of(name, score, comment, false, subgoalMetadata);
    }
}