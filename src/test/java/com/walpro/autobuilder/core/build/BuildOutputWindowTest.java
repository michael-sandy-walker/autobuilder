package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1-GE: the build-output window keeps the cause a compiler reports first.
 *
 * <p>The fixture {@code build-output/t1ge-skeleton-reprobe.txt} is the injector re-probe of run
 * {@code 2026-09-21_19-39-14} at 20:04:07. Its LAST 50 lines are verbatim from the run log — which
 * is all the run kept — and the part before them is reconstructed in the build tool's own shape
 * from that run's package-gate output: the unresolvable import first, then the cascade, then the
 * repeated list and the help text. So the trailing-only window reproduces exactly what the field
 * probe saw.
 */
class BuildOutputWindowTest {

    private static List<String> fixture() throws IOException {
        try (InputStream in = BuildOutputWindowTest.class.getResourceAsStream("/build-output/t1ge-skeleton-reprobe.txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }

    private static String through(BuildOutputWindow w, List<String> lines) {
        lines.forEach(w::add);
        return w.render();
    }

    @Test
    void shortOutput_isKeptWhole_withNoMarker() {
        String rendered = through(new BuildOutputWindow(3, 3), List.of("a", "b", "c", "d", "e"));

        assertThat(rendered).isEqualTo("a\nb\nc\nd\ne");
    }

    @Test
    void longOutput_keepsHeadAndTail_andNamesWhatWasDropped() {
        BuildOutputWindow w = new BuildOutputWindow(2, 2);
        String rendered = through(w, List.of("1", "2", "3", "4", "5", "6", "7"));

        assertThat(rendered).isEqualTo("1\n2\n" + BuildOutputWindow.omissionMarker(3) + "\n6\n7");
        assertThat(w.retainedLines()).isEqualTo(4);
        assertThat(w.totalLines()).isEqualTo(7);
    }

    /** The field's own view, reproduced: the trailing 50 lines do not carry the cause. */
    @Test
    void theOldTrailingWindow_losesTheCause_onTheFieldOutput() throws IOException {
        String tailOnly = through(new BuildOutputWindow(0, 50), fixture());

        assertThat(tailOnly).doesNotContain("does not exist");
        assertThat(BuildErrorClassifier.classify(tailOnly).kind())
                .isNotEqualTo(BuildErrorClassifier.Kind.PACKAGE_DOES_NOT_EXIST);
    }

    /** ...and the window every build now uses reaches the verdict the package gate reached. */
    @Test
    void theDefaultWindow_keepsTheCause_andClassifiesItAsThePackageGateDid() throws IOException {
        String rendered = through(new BuildOutputWindow(), fixture());

        assertThat(rendered).contains("package org.springframework.data.jpa.repository does not exist");
        assertThat(BuildErrorClassifier.classify(rendered).kind())
                .isEqualTo(BuildErrorClassifier.Kind.PACKAGE_DOES_NOT_EXIST);
    }

    /** End to end through the real process path, so the executor cannot bypass the window. */
    @Test
    void buildExecutor_capturesTheCause_fromARealProcess(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("out.txt");
        Files.write(out, fixture());
        Path script = tmp.resolve("build.sh");
        Files.writeString(script, "#!/bin/sh\ncat " + out + "\nexit 1\n");
        script.toFile().setExecutable(true);

        BuildOutcome outcome = new BuildExecutor(true, script.toString(), 10_000L, "")
                .execute(tmp.toString(), "c-1");

        assertThat(outcome.result()).isEqualTo(BuildResult.COMPILE_ERROR);
        assertThat(BuildErrorClassifier.classify(outcome.stderrTail()).kind())
                .isEqualTo(BuildErrorClassifier.Kind.PACKAGE_DOES_NOT_EXIST);
    }
}
