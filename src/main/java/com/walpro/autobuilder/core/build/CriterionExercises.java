package com.walpro.autobuilder.core.build;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * T4-EK: criteria that carry the exercise that earned them.
 *
 * <p>A criterion is text, and each verifier builds its own exercise from the text: a request sequence from a sentence
 * about a route, a run from a sentence about a command. For a criterion the loop wrote itself that is the only way. A
 * criterion reverse-engineered from a project the loop did not build was EARNED by one exact exercise -- it held on the
 * tree and broke with its way in taken out -- and a re-built exercise may differ from it: another body, another setup,
 * another file. So the exercise is kept beside the criterion, in {@code meta/criterion-exercises.json}, and a verifier
 * runs THAT when there is one. Criteria without one are verified as before; nothing else reads this file.
 *
 * <p>Keyed by the criterion's exact text. Read again whenever the file changes.
 */
@Slf4j
@Component
@com.walpro.autobuilder.core.checkpoint.StateHolder(
        value = com.walpro.autobuilder.core.checkpoint.StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "a cache of meta/criterion-exercises.json, re-read whenever the file changes; the file is the state")
public class CriterionExercises {

    public static final String FILE = "meta/criterion-exercises.json";

    /**
     * One stored exercise.
     *
     * @param kind     {@code service-style}: {@code requests} answered with {@code expected} as the last status;
     *                 {@code command-style}: one run with {@code args} after {@code files} are written, ending with
     *                 {@code expected} as its exit status and an output containing {@code outputContains} (when set)
     */
    public record Stored(String criterion, String kind, List<CriterionProbeSynthesizer.ProbeRequest> requests, int expected,
                         Map<String, String> files, List<String> args, String outputContains) {
    }

    /**
     * T4-EO: how much a sequence can change of what the start it runs on provides -- its requests that are not a GET.
     * One start serves many sequences, so what one changes is what the later ones find; whoever runs several on one
     * start runs them in this order (fewest first, alike in the order given). The exercise that keeps a claim and the
     * gate that later checks it must agree on it: a claim kept in one order and checked in another is contradicted by
     * its neighbours, not by the project (field, 2026-11-11: five criteria that sign in as a seeded user ran after the
     * one that renames that user, and the loop began changing code that was right).
     */
    public static long changes(List<CriterionProbeSynthesizer.ProbeRequest> sequence) {
        return sequence.stream().filter(r -> !"GET".equals(r.method())).count();
    }

    /**
     * The order itself: fewest changing requests first; alike in that, by what the changing requests are -- a key both
     * sides can compute, since a kept sequence differs from the proposed one only by pages (GETs) put in.
     */
    public static final java.util.Comparator<List<CriterionProbeSynthesizer.ProbeRequest>> EXERCISE_ORDER =
            java.util.Comparator.<List<CriterionProbeSynthesizer.ProbeRequest>>comparingLong(CriterionExercises::changes)
                    .thenComparing(s -> s.stream().filter(r -> !"GET".equals(r.method())).map(Object::toString)
                            .collect(java.util.stream.Collectors.joining(" | ")));

    private final ObjectMapper json = new ObjectMapper();
    private final Path file;
    private volatile long readAt = -1;
    private volatile Map<String, Stored> byCriterion = Map.of();

    public CriterionExercises(@Value("${file.base-path:.}") String fileBasePath) {
        this.file = Path.of(fileBasePath).resolve(FILE);
    }

    public Optional<Stored> of(String criterion) {
        if (criterion == null) return Optional.empty();
        return Optional.ofNullable(current().get(criterion));
    }

    public boolean isEmpty() {
        return current().isEmpty();
    }

    /** Writes {@code stored}, replacing what the file held. */
    public void write(List<Stored> stored) throws IOException {
        Files.createDirectories(file.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), stored);
        readAt = -1;
    }

    /** Adds {@code more} to what the file holds; an exercise already stored under the same criterion is replaced. */
    public synchronized void append(List<Stored> more) throws IOException {
        if (more.isEmpty()) return;
        Map<String, Stored> all = new LinkedHashMap<>(current());
        for (Stored s : more) all.put(s.criterion(), s);
        write(List.copyOf(all.values()));
    }

    private Map<String, Stored> current() {
        try {
            if (!Files.isRegularFile(file)) return Map.of();
            long modified = Files.getLastModifiedTime(file).toMillis();
            if (modified != readAt) {
                List<Stored> all = json.readValue(file.toFile(), new TypeReference<List<Stored>>() { });
                Map<String, Stored> m = new LinkedHashMap<>();
                for (Stored s : all) m.putIfAbsent(s.criterion(), s);
                byCriterion = m;
                readAt = modified;
            }
        } catch (IOException e) {
            log.warn("T4-EK: {} could not be read ({}) -- criteria are verified as if none were stored", file, e.getMessage());
            return Map.of();
        }
        return byCriterion;
    }
}
