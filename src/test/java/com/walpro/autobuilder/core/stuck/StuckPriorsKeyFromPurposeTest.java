package com.walpro.autobuilder.core.stuck;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.purpose.PurposeService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-EC slice 1: the stuck layer's cross-run priors were stored under the SHA-256 of the raw {@code bootstrap/seed.txt};
 * it now takes the key from the seed's input. Over every existing target's seed, the key is unchanged -- whether
 * the root is the trimmed file (seed-file bootstrap) or the text as given (operator).
 */
class StuckPriorsKeyFromPurposeTest {

    static Stream<Path> seeds() throws Exception {
        try (Stream<Path> dirs = Files.list(Path.of("data"))) {
            return dirs.map(d -> d.resolve("bootstrap/seed.txt")).filter(Files::isRegularFile).sorted().toList().stream();
        }
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void theKeyIsTheOneTheRawSeedFileGave(Path seedFile) throws Exception {
        String raw = Files.readString(seedFile);
        for (String root : new String[]{raw.trim(), raw}) {
            Path dir = Files.createTempDirectory("priors-key-");
            Files.createDirectories(dir.resolve("bootstrap"));
            Files.writeString(dir.resolve("bootstrap/seed.txt"), raw);
            PurposeService purpose = PurposeService.fileOnly(new FileService(dir.toString(), new ObjectMapper()));
            purpose.recordSeed(root, "test");
            assertThat(purpose.seedKey()).isEqualTo(StuckPriors.taskKey(raw));
        }
    }
}
