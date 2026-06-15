package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-AN-5: functional tests for the build-tool diagnostic probe core — detection, command
 * execution + output capture, per-cycle caching, and failure handling. Exercises the real
 * {@link ProcessBuilder} path with portable commands (no mocking of the runner).
 */
class BuildToolProbeTest {

    private BuildToolProbe probe(boolean enabled, Map<String, String> detection,
                                 Map<String, List<String>> diagnostics) {
        BuildToolProbeProperties props = new BuildToolProbeProperties();
        props.setDiagnosticsEnabled(enabled);
        props.setToolDetection(detection);
        props.setDiagnostics(diagnostics);
        return new BuildToolProbe(props);
    }

    @Test
    void detectTools_byFilePresence(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        BuildToolProbe p = probe(true, Map.of("pom.xml", "maven", "build.gradle", "gradle"), Map.of());
        assertThat(p.detectTools(dir.toString())).containsExactly("maven");
    }

    @Test
    void diagnose_runsConfiguredCommand_andCapturesOutput(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        BuildToolProbe p = probe(true,
                Map.of("pom.xml", "maven"),
                Map.of("maven", List.of("echo plugin-update-available")));

        String diag = p.diagnose(dir.toString(), "cycle-1");

        assertThat(diag)
                .contains("$ echo plugin-update-available")   // the command is echoed
                .contains("plugin-update-available");          // its real stdout is captured
    }

    @Test
    void diagnose_disabled_returnsNull(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        BuildToolProbe p = probe(false, Map.of("pom.xml", "maven"),
                Map.of("maven", List.of("echo x")));
        assertThat(p.diagnose(dir.toString(), "cycle-1")).isNull();
    }

    @Test
    void diagnose_noToolDetected_returnsNull(@TempDir Path dir) {
        BuildToolProbe p = probe(true, Map.of("pom.xml", "maven"),
                Map.of("maven", List.of("echo x")));
        assertThat(p.diagnose(dir.toString(), "cycle-1")).isNull(); // empty dir → no pom → no tool
    }

    @Test
    void diagnose_failedCommand_isInformationalNotThrown(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        BuildToolProbe p = probe(true,
                Map.of("pom.xml", "maven"),
                Map.of("maven", List.of("definitely-not-a-real-command-xyz")));

        String diag = p.diagnose(dir.toString(), "cycle-1");

        assertThat(diag).contains("diagnostic failed"); // IOException folded in, never thrown
    }

    @Test
    void diagnose_cachesByCycleId(@TempDir Path dir) throws IOException {
        // A per-run-varying command (nanosecond clock): same cycleId returns the cached output,
        // a new cycleId re-runs and yields a different timestamp.
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        BuildToolProbe p = probe(true, Map.of("pom.xml", "maven"),
                Map.of("maven", List.of("date +%s%N")));

        String a = p.diagnose(dir.toString(), "cycle-1");
        String b = p.diagnose(dir.toString(), "cycle-1"); // same cycle → cached, identical
        String c = p.diagnose(dir.toString(), "cycle-2"); // new cycle → re-runs, different clock

        assertThat(b).isEqualTo(a);
        assertThat(c).isNotEqualTo(a);
    }
}
