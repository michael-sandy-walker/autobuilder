package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production code must read compile-probe stderr from
 * {@link com.walpro.autobuilder.core.lifecycle.CycleContextData#lastProbeOutcome()} rather
 * than string-matching a {@code "Compile probe failed:"} prefix on
 * {@code EvaluationOutcome.feedbackMessage}. The prefix used to launder the typed
 * {@code BuildOutcome} through free text; consumers that string-matched it diverged in
 * subtle ways that the typed surface eliminates.
 */
class CompileProbeFeedbackContractTest {

    @Test
    void noProductionFileMentionsCompileProbeFailedAsAStringLiteral() throws IOException {
        Path mainJava = Path.of("src/main/java");
        assertThat(Files.isDirectory(mainJava)).isTrue();

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(mainJava)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    List<String> lines = Files.readAllLines(p);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        int idx = line.indexOf("Compile probe failed:");
                        if (idx < 0) continue;
                        // Tolerate occurrences inside line/block comments.
                        String beforeMatch = line.substring(0, idx);
                        if (beforeMatch.contains("//")) continue;
                        String trimmed = line.trim();
                        if (trimmed.startsWith("*") || trimmed.startsWith("/*")) continue;
                        offenders.add(p + ":" + (i + 1) + ": " + line.strip());
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        assertThat(offenders)
                .as("Production code must not depend on the retired \"Compile probe failed:\" "
                        + "string-prefix contract — read ctx.lastProbeOutcome() instead.")
                .isEmpty();
    }
}
