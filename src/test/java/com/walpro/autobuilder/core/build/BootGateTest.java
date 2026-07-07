package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T4-BV: the boot gate launches the packaged artifact once at the completion edge and turns
 * runtime failures — startup crash, never serving, spec-named endpoints dead at runtime — into
 * closure blockers. Ground truth: false completion #2's jar died at launch, and the 2026-07-02/03
 * fulfilled artifacts answered 404 on spec-named endpoints although every static gate passed.
 * Process behaviour is exercised with real child processes; the serving cases launch
 * {@link BootGateProbeServer} exactly the way a packaged application would be launched.
 */
class BootGateTest {

    @TempDir
    Path tempDir;

    private final BuildExecutor buildExecutor = mock(BuildExecutor.class);

    // No endpoint mention directly before a sentence-final period: the spec parser's path
    // character class includes '.' (same semantics the D2 closure query applies).
    private static final String SPEC_WITH_ENDPOINTS =
            "Build a REST service exposing GET /items, GET /missing and GET /failing endpoints.";

    private BootGate gate(boolean enabled, String commandTemplate, String portEnvVar, long readinessTimeoutMs) {
        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.resolveWorkingDir(tempDir.toString())).thenReturn(tempDir.toString());
        BootGate g = new BootGate(enabled, commandTemplate, portEnvVar, readinessTimeoutMs, 3000,
                buildExecutor, new EndpointSurfaceQuery());
        g.setFileBasePath(tempDir.toString());
        return g;
    }

    /** A dummy packaged archive so artifact discovery succeeds when the command ignores {artifact}. */
    private void packagedArtifact() throws Exception {
        Path target = tempDir.resolve("target");
        Files.createDirectories(target);
        Files.writeString(target.resolve("app.jar"), "not a real archive");
    }

    /** Launch command for {@link BootGateProbeServer} in a fresh JVM, port passed as {@code {port}} arg. */
    private static String probeServerCommand() {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        // Surefire may hide the test classpath behind a booter jar, so resolve the helper's own
        // class-file location instead of trusting java.class.path.
        String testClasses = new File(BootGateProbeServer.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath()).getAbsolutePath();
        return javaBin + " -cp " + testClasses + " " + BootGateProbeServer.class.getName() + " {port}";
    }

    @Test
    void disabledGate_orDisabledExecutor_abstains() throws Exception {
        packagedArtifact();
        assertThat(gate(false, "java -jar {artifact}", "", 500)
                .verifyBootsAndServes("c", SPEC_WITH_ENDPOINTS)).isEmpty();

        BootGate g = gate(true, "java -jar {artifact}", "", 500);
        when(buildExecutor.isEnabled()).thenReturn(false);
        assertThat(g.verifyBootsAndServes("c", SPEC_WITH_ENDPOINTS)).isEmpty();
    }

    @Test
    void noPackagedArtifact_abstains() {
        // Packageability is the package gate's question; without an archive there is nothing
        // to execute and therefore no evidence either way.
        assertThat(gate(true, "true", "", 500).verifyBootsAndServes("c", SPEC_WITH_ENDPOINTS)).isEmpty();
    }

    @Test
    void artifactOlderThanSources_abstains() throws Exception {
        packagedArtifact();
        Path source = tempDir.resolve("src/main/java/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class App {}");
        // Make the archive strictly older than the source it would be judged against.
        tempDir.resolve("target/app.jar").toFile().setLastModified(System.currentTimeMillis() - 60_000);

        assertThat(gate(true, "true", "", 500).verifyBootsAndServes("c", SPEC_WITH_ENDPOINTS)).isEmpty();
    }

    @Test
    void startupCrash_blocksWithExitCode() throws Exception {
        packagedArtifact();
        Optional<String> blocker = gate(true, "false", "", 2000)
                .verifyBootsAndServes("c", SPEC_WITH_ENDPOINTS);

        assertThat(blocker).isPresent();
        assertThat(blocker.get()).contains("exits at startup").contains("exit 1");
    }

    @Test
    void cleanExit_whenSpecNamesEndpoints_blocks() throws Exception {
        // A REST spec's artifact must stay up to serve; exiting 0 immediately is still a failure.
        packagedArtifact();
        Optional<String> blocker = gate(true, "true", "", 2000)
                .verifyBootsAndServes("c", SPEC_WITH_ENDPOINTS);

        assertThat(blocker).isPresent();
        assertThat(blocker.get()).contains("exits immediately");
    }

    @Test
    void cleanExit_whenSpecNamesNoEndpoints_abstains() throws Exception {
        packagedArtifact();
        assertThat(gate(true, "true", "", 2000)
                .verifyBootsAndServes("c", "Produce a batch data-cleanup tool.")).isEmpty();
    }

    @Test
    void neverServesHttp_whenSpecNamesEndpoints_blocks() throws Exception {
        packagedArtifact();
        Optional<String> blocker = gate(true, "sleep 30", "", 600)
                .verifyBootsAndServes("c", SPEC_WITH_ENDPOINTS);

        assertThat(blocker).isPresent();
        assertThat(blocker.get()).contains("never answers HTTP");
    }

    @Test
    void servingArtifact_specEndpointsDeadAtRuntime_blocksNamingThem() throws Exception {
        // The run-4b class: mappings formally present, unreachable in the running application.
        packagedArtifact();
        Optional<String> blocker = gate(true, probeServerCommand(), "", 30_000)
                .verifyBootsAndServes("c", SPEC_WITH_ENDPOINTS);

        assertThat(blocker).isPresent();
        assertThat(blocker.get()).contains("GET /missing").contains("404");
        assertThat(blocker.get()).contains("GET /failing").contains("500");
        assertThat(blocker.get()).doesNotContain("GET /items");
    }

    @Test
    void servingArtifact_allSpecEndpointsLive_passes_viaPortEnvVarHandoff() throws Exception {
        // Port handed over via environment variable only (the command template carries no {port}),
        // mirroring the production SERVER_PORT config for artifacts whose main() drops its args.
        packagedArtifact();
        String command = probeServerCommand().replace(" {port}", "");
        Optional<String> blocker = gate(true, command, "BOOT_GATE_TEST_PORT", 30_000)
                .verifyBootsAndServes("c", "Build a REST service exposing the GET /items endpoint."
                        + " Path-variable endpoints like GET /items/{id} and POST /items are not probeable"
                        + " and must not produce failures.");

        assertThat(blocker).isEmpty();
    }
}
