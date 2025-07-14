package com.walpro.autobuilder.persistence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import com.walpro.autobuilder.model.SubgoalMetadata;
import lombok.Builder;
import lombok.Getter;
import lombok.With;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@With
@Builder(toBuilder = true)
public class CycleContext {

    private final String name;
    private final Instant timestamp;
    private final double score;
    private final String comment;
    private final boolean merged;
    private final SubgoalMetadata subgoalMetadata;

    // ✅ New fields
    private final MutationAcceptanceState mutationState;
    private final String parentCycleName;
    private final String recombinationSourceCycleName;

    @JsonCreator
    public CycleContext(
            @JsonProperty("name") String name,
            @JsonProperty("timestamp") Instant timestamp,
            @JsonProperty("score") double score,
            @JsonProperty("comment") String comment,
            @JsonProperty("merged") boolean merged,
            @JsonProperty("subgoalMetadata") SubgoalMetadata subgoalMetadata,
            @JsonProperty("mutationState") MutationAcceptanceState mutationState,
            @JsonProperty("parentCycleName") String parentCycleName,
            @JsonProperty("recombinationSourceCycleName") String recombinationSourceCycleName
    ) {
        this.name = name;
        this.timestamp = timestamp;
        this.score = score;
        this.comment = comment;
        this.merged = merged;
        this.subgoalMetadata = subgoalMetadata;
        this.mutationState = mutationState;
        this.parentCycleName = parentCycleName;
        this.recombinationSourceCycleName = recombinationSourceCycleName;
    }

    public String getId() {
        return name + "-" + DateTimeFormatter.ISO_INSTANT.format(timestamp).replace(":", "-");
    }

    public static CycleContext of(
            String name,
            double score,
            String comment,
            boolean merged,
            SubgoalMetadata subgoalMetadata,
            MutationAcceptanceState mutationState,
            String parentCycleName,
            String recombinationSourceCycleName
    ) {
        return CycleContext.builder()
                .name(name)
                .timestamp(Instant.now())
                .score(score)
                .comment(comment)
                .merged(merged)
                .subgoalMetadata(subgoalMetadata)
                .mutationState(mutationState)
                .parentCycleName(parentCycleName)
                .recombinationSourceCycleName(recombinationSourceCycleName)
                .build();
    }

    public static CycleContext of(String name, double score, String comment, boolean merged, SubgoalMetadata metadata) {
        return of(name, score, comment, merged, metadata, null, null, null);
    }

    public static CycleContext of(String name, double score, String comment, SubgoalMetadata metadata) {
        return of(name, score, comment, false, metadata, null, null, null);
    }
}
