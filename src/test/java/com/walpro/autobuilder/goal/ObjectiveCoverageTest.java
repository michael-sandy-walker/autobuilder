package com.walpro.autobuilder.goal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-CQ: the OBJECTIVE a seed states is carried by its obligations file, checked without a model. */
class ObjectiveCoverageTest {

    private static final String SEED = """
            A library of warp coils.

            OBJECTIVE: every coil in CoilSpecTest aligns, and the whole
            harness passes.

            Change only the code.
            """;

    @Test
    void theObjectiveIsSplitIntoItsClauses_acrossAHardWrap() {
        assertThat(ObjectiveCoverage.clausesOf(SEED))
                .containsExactly("every coil in CoilSpecTest aligns", "the whole harness passes");
    }

    @Test
    void aSplitCarryingTheObjectiveInOtherWordsIsComplete() {
        assertThat(ObjectiveCoverage.missing(SEED, List.of(
                "The coils in CoilSpecTest align",
                "gate: tests The whole harness passes"))).isEmpty();
    }

    @Test
    void aSplitThatDroppedTheObjectiveNamesWhatIsMissing() {
        assertThat(ObjectiveCoverage.missing(SEED, List.of(
                "CoilSpecTest exists", "rule: Change only the code")))
                .containsExactly("every coil in CoilSpecTest aligns", "the whole harness passes");
    }

    @Test
    void aSeedWithoutAnObjectiveHasNothingToMiss() {
        assertThat(ObjectiveCoverage.missing("Build a coil registry.", List.of())).isEmpty();
    }

    /**
     * Validation on the recorded seeds: every obligations file in the repository -- model-written
     * and hand-marked alike, each of which a run completed against -- carries its seed's objective.
     */
    @Test
    void everyRecordedObligationsFileCarriesItsSeedsObjective() throws IOException {
        Path data = Path.of("data");
        if (!Files.isDirectory(data)) return;
        List<String> checked = new ArrayList<>();
        try (Stream<Path> targets = Files.list(data)) {
            for (Path t : targets.sorted().toList()) {
                Path seed = t.resolve("bootstrap/seed.txt");
                Path file = t.resolve("bootstrap/" + Path.of(SeedObligationsFile.PATH).getFileName());
                if (!Files.exists(seed) || !Files.exists(file)) continue;
                String seedText = Files.readString(seed);
                if (ObjectiveCoverage.clausesOf(seedText).isEmpty()) continue;
                List<String> lines = Files.readString(file).lines()
                        .filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
                assertThat(ObjectiveCoverage.missing(seedText, lines)).as(t.toString()).isEmpty();
                checked.add(t.getFileName().toString());
            }
        }
        assertThat(checked).as("recorded targets with an objective and a file").isNotEmpty();
    }
}
