package com.walpro.autobuilder.core.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CycleContextData} is written to every cycle's {@code meta.json} and read back, so any
 * record riding on it must round-trip through Jackson.
 *
 * <p><b>Why this test exists.</b> A record's getter-shaped derived accessor is SERIALIZED as a
 * property, and the canonical constructor has no parameter for it, so the read-back throws. The
 * consequence is not a lost file: {@code MutationSnapshotService} falls back to writing a fresh
 * {@code meta.json} with {@code mutationState=REJECTED}, so **every mutation the evaluator
 * accepted is recorded as rejected and nothing merges**. Observed on run
 * {@code 2026-07-30_17-37-09}: 9 mutations ACCEPTED, 0 merged, 38 {@code
 * mutation_meta_persist_failed} events, all from one unannotated {@code isEmpty()}.
 *
 * <p>The convention was already there — four of the five records on the context carry
 * {@code @JsonIgnore} on exactly such an accessor, and {@code CompileAuthorErrors} has a closed
 * ticket (T1-EV) for this same defect. What was missing was anything that enforced it, so the
 * fifth was free to reintroduce it. This is that enforcement, and it fails on the TYPE rather
 * than on a remembered list, so a record added tomorrow is covered without anyone editing a test.
 */
class CycleContextDataRoundTripTest {

    /**
     * A property that carries no state and cannot be reconstructed: an accessor Jackson writes
     * out but the canonical constructor cannot read back.
     */
    private static List<String> unreadableProperties(Class<?> type) {
        if (!type.isRecord()) return List.of();
        Set<String> components = java.util.Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());

        List<String> offenders = new ArrayList<>();
        for (var method : type.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) continue;
            if (method.getParameterCount() != 0) continue;
            if (method.isSynthetic()) continue;
            if (method.getAnnotation(com.fasterxml.jackson.annotation.JsonIgnore.class) != null) continue;
            if (type.getAnnotation(com.fasterxml.jackson.annotation.JsonIgnoreProperties.class) != null) continue;

            String name = method.getName();
            String property = null;
            if (name.startsWith("is") && name.length() > 2
                    && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
                property = Character.toLowerCase(name.charAt(2)) + name.substring(3);
            } else if (name.startsWith("get") && name.length() > 3) {
                property = Character.toLowerCase(name.charAt(3)) + name.substring(4);
            }
            if (property != null && !components.contains(property)) offenders.add(name + "() → \"" + property + "\"");
        }
        return offenders;
    }

    @Test
    void everyRecordOnTheContextCanBeReadBackAfterBeingWritten() {
        List<String> failures = new ArrayList<>();
        for (RecordComponent component : CycleContextData.class.getRecordComponents()) {
            Class<?> type = component.getType();
            List<String> offenders = unreadableProperties(type);
            if (!offenders.isEmpty()) {
                failures.add(component.getName() + " (" + type.getSimpleName() + "): " + offenders);
            }
        }

        assertThat(failures)
                .withFailMessage("""
                        These context fields serialize a property their constructor cannot read back, \
                        so meta.json fails to load and MutationSnapshotService rewrites it with \
                        mutationState=REJECTED — every accepted mutation is recorded as rejected and \
                        nothing merges. Annotate the derived accessor @JsonIgnore.
                        %s""", failures)
                .isEmpty();
    }

    @Test
    void theSeedCoverageReadoutSurvivesTheTripThatBrokeIt() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var readout = new com.walpro.autobuilder.analysis.coverage.SeedCoverageReadout(
                List.of(new com.walpro.autobuilder.analysis.coverage.SeedCoverageReadout.Finding(
                        "404 if either not found;", List.of("either", "found"))),
                List.of());

        String json = mapper.writeValueAsString(readout);

        assertThat(json).doesNotContain("\"empty\"");
        assertThat(mapper.readValue(json,
                com.walpro.autobuilder.analysis.coverage.SeedCoverageReadout.class)).isEqualTo(readout);
    }
}
