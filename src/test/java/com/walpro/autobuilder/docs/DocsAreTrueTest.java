package com.walpro.autobuilder.docs;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5-BS: the documentation names only what exists. Runs {@code scripts/doc_check.py} -- every class, configuration
 * key, script and endpoint that docs/technical.md and docs/functional.md name -- so a rename that orphans the
 * documentation fails the build instead of rotting it, as README.md and CLAUDE.md's "scheduled every 60 s" did.
 */
class DocsAreTrueTest {

    @Test
    void theDocumentsNameOnlyWhatExists() throws Exception {
        Path script = Path.of("scripts/doc_check.py");
        Assumptions.assumeTrue(Files.exists(script), "run from the repository root");
        Process p;
        try {
            p = new ProcessBuilder("python3", script.toString()).redirectErrorStream(true).start();
        } catch (java.io.IOException noPython) {
            Assumptions.abort("python3 is not available");
            return;
        }
        boolean done = p.waitFor(60, TimeUnit.SECONDS);
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(done).as("doc_check finished").isTrue();
        assertThat(p.exitValue()).as(out).isZero();
    }
}
