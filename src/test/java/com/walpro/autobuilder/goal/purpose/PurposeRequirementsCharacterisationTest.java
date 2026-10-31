package com.walpro.autobuilder.goal.purpose;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.SeedObligationsFile;
import com.walpro.autobuilder.goal.SeedRequirements;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-EC slice 3, the proof that nothing moved with one input: over every existing target, with its real seed and
 * obligations file, each answer the purpose gives is line for line what {@link SeedRequirements} gave its callers --
 * whether the root is the text as given (a posted goal) or the trimmed file (a seed-file bootstrap, where a file hashed
 * over the raw text reads as moved).
 */
class PurposeRequirementsCharacterisationTest {

    private static final List<String> BANKED = List.of("A book has a title", "the whole suite passes");

    static Stream<Path> targets() throws Exception {
        try (Stream<Path> dirs = Files.list(Path.of("data"))) {
            return dirs.filter(d -> Files.isRegularFile(d.resolve("bootstrap/seed.txt"))).sorted().toList().stream();
        }
    }

    @ParameterizedTest
    @MethodSource("targets")
    void everyAnswerIsWhatTheSeedAloneGave(Path target) throws Exception {
        String raw = Files.readString(target.resolve("bootstrap/seed.txt"));
        Path obligations = target.resolve("bootstrap/seed.obligations.txt");
        String written = Files.isRegularFile(obligations) ? Files.readString(obligations) : null;
        for (String root : new String[]{raw, raw.trim()}) {
            Path dir = Files.createTempDirectory("purpose-req-");
            Files.createDirectories(dir.resolve("bootstrap"));
            Files.writeString(dir.resolve("bootstrap/seed.txt"), raw);
            if (written != null) Files.writeString(dir.resolve(SeedObligationsFile.PATH), written);
            PurposeService purpose = PurposeService.fileOnly(new FileService(dir.toString(), new ObjectMapper()));
            purpose.recordSeed(root, "test");

            for (String gate : List.of(SeedRequirements.GATE_TESTS, SeedRequirements.GATE_DERIVED)) {
                assertThat(purpose.answeredByGate(gate)).isEqualTo(SeedRequirements.answeredByGate(root, written, gate));
            }
            assertThat(purpose.rules()).isEqualTo(SeedRequirements.rules(root, written));
            assertThat(purpose.obligationsOrClauses()).isEqualTo(SeedRequirements.obligationsOrClauses(root, written));
            assertThat(purpose.verifiableObligationsOrSplit()).isEqualTo(SeedRequirements.verifiableObligationsOrSplit(root, written));
            assertThat(purpose.notAlreadyCoveredVerifiable(BANKED))
                    .isEqualTo(SeedRequirements.notAlreadyCoveredVerifiable(root, BANKED, written));
            assertThat(purpose.specification()).isEqualTo(root);
            assertThat(purpose.writtenText()).isEqualTo(written == null ? "" : written);
        }
    }
}
