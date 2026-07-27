package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

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
        // to execute and therefore no observation either way.
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
                        + " Path-variable endpoints like GET /items/{id} are not probeable and must not"
                        + " produce failures; POST /items is exercised (T4-CB) and answers 200 here.");

        assertThat(blocker).isEmpty();
    }

    @Test
    void relativeFileBasePath_artifactPathStaysValidAcrossChildProcessCwdChange() throws Exception {
        // T1-FJ field bug (2026-07-07 run 22 relaunch): production's file.base-path resolves to a
        // RELATIVE string (FILE_BASE_PATH=firestarter -> working-dir=firestarter/output).
        // buildCommand() embeds artifact.toString() literally into the {artifact} token, but
        // launchAndObserve() ALSO points the CHILD PROCESS's cwd at the artifact's own parent
        // directory via ProcessBuilder.directory(...) — a relative artifact path then re-resolves
        // against that NEW cwd instead of the one it was actually relative to, and every real java
        // process died at launch with "Unable to access jarfile <path>", blocking every completion
        // attempt on the gate's own bug rather than a real defect (fired 3x in the field before
        // being caught). @TempDir is always absolute, so no prior test exercised this interaction —
        // this one uses a genuinely relative fileBasePath (relative to the JVM's own cwd, exactly
        // like the production FILE_BASE_PATH config).
        Path relBase = Path.of("target", "boot-gate-relpath-test-" + System.nanoTime());
        Files.createDirectories(relBase.resolve("target"));
        writeMinimalJar(relBase.resolve("target/app.jar"));
        try {
            when(buildExecutor.isEnabled()).thenReturn(true);
            when(buildExecutor.resolveWorkingDir(relBase.toString())).thenReturn(relBase.toString());
            BootGate g = new BootGate(true, "java -jar {artifact}", "", 5000, 3000,
                    buildExecutor, new EndpointSurfaceQuery());
            g.setFileBasePath(relBase.toString());

            Optional<String> blocker = g.verifyBootsAndServes("c", "Produce a batch data-cleanup tool.");

            // The jar has no Main-Class, so java still fails to launch it — but that failure must
            // be "no main manifest attribute" (proves the file was FOUND and OPENED), never
            // "Unable to access jarfile" (proves the path itself failed to resolve).
            assertThat(blocker).isPresent();
            assertThat(blocker.get()).doesNotContain("Unable to access jarfile");
        } finally {
            try (Stream<Path> walk = Files.walk(relBase)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    @Test
    void postEndpointCrashing_blocksWithNamedRequestAndBody() throws Exception {
        // T4-CB ground truth: the 2026-07-11 false completion fulfilled with POST /loans crashing
        // on an id cast — writes were never exercised. /orders answers 500 to POST (200 to GET).
        // A model source exists, so the synthesized body (not just {}) reaches the endpoint.
        Path model = tempDir.resolve("src/main/java/com/example/Order.java");
        Files.createDirectories(model.getParent());
        Files.writeString(model, "public class Order { private String name; private Long bookId; }");
        packagedArtifact(); // after the source — a source newer than the jar would abstain
        Optional<String> blocker = gate(true, probeServerCommand(), "", 30_000)
                .verifyBootsAndServes("c", "Build a REST service exposing GET /items and POST /orders endpoints.");

        assertThat(blocker).isPresent();
        assertThat(blocker.get()).contains("POST /orders").contains("500").contains("exercised");
        assertThat(blocker.get()).contains("\"name\":\"value\"");
    }

    @Test
    void postEndpointAnsweringValidation400_neverBlocks() throws Exception {
        // A 400 on a synthesized/empty body is the validation surface WORKING — precision-first,
        // the write probe blocks only on 5xx or connection death.
        packagedArtifact();
        assertThat(gate(true, probeServerCommand(), "", 30_000)
                .verifyBootsAndServes("c", "Build a REST service exposing GET /items and POST /carts endpoints."))
                .isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // T4-CU: acceptance criteria executed against the artifact the gate just booted.
    // Ground truth (run 2026-07-27_16-41-48): the criterion "POST /books with invalid input
    // returns HTTP 400" was stamped SPEC, marked ✓ by the evaluator reading the sources, and
    // false — the endpoint answered 201 and the run closed GOAL_FULFILLED at smoke 19/23.
    // ---------------------------------------------------------------------------------------

    private static final String SPEC_ITEMS_AND_CARTS =
            "Build a REST service exposing GET /items and POST /carts endpoints.";

    /** A gate whose probe synthesizer returns {@code sequence} for every criterion it is asked about. */
    private BootGate gateProbing(List<CriterionProbeSynthesizer.ProbeRequest> sequence) {
        BootGate g = gate(true, probeServerCommand(), "", 30_000);
        CriterionProbeSynthesizer synthesizer = mock(CriterionProbeSynthesizer.class);
        when(synthesizer.synthesize(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(sequence));
        g.setCriterionProbeSynthesizer(synthesizer);
        return g;
    }

    @Test
    void criterionContradictedByTheRunningApplication_blocksAndQuotesIt() throws Exception {
        // /carts answers 400 to a POST; the criterion claims 201. Only a request can settle this.
        packagedArtifact();
        Optional<String> blocker = gateProbing(List.of(
                new CriterionProbeSynthesizer.ProbeRequest("POST", "/carts", "{\"name\":\"x\"}")))
                .verifyBootsAndServes("c", SPEC_ITEMS_AND_CARTS,
                        List.of("POST /carts creates a cart and returns HTTP 201 with the cart body"));

        assertThat(blocker).isPresent();
        assertThat(blocker.get())
                .contains("POST /carts creates a cart and returns HTTP 201")
                .contains("POST /carts → 201")
                .contains("answered 400");
    }

    @Test
    void criterionTheApplicationSatisfies_passes() throws Exception {
        packagedArtifact();
        assertThat(gateProbing(List.of(
                new CriterionProbeSynthesizer.ProbeRequest("POST", "/carts", "{}")))
                .verifyBootsAndServes("c", SPEC_ITEMS_AND_CARTS,
                        List.of("POST /carts with invalid input returns HTTP 400")))
                .isEmpty();
    }

    @Test
    void onlyTheFinalResponseIsAsserted_earlierRequestsAreSetup() throws Exception {
        // The setup request answers 200 on /items; the asserted one answers 400 on /carts.
        packagedArtifact();
        assertThat(gateProbing(List.of(
                new CriterionProbeSynthesizer.ProbeRequest("GET", "/items", null),
                new CriterionProbeSynthesizer.ProbeRequest("POST", "/carts", "{}")))
                .verifyBootsAndServes("c", SPEC_ITEMS_AND_CARTS,
                        List.of("POST /carts with invalid input returns HTTP 400")))
                .isEmpty();
    }

    @Test
    void unparseableCriteriaAreSkipped_notFailed() throws Exception {
        packagedArtifact();
        assertThat(gateProbing(List.of(
                new CriterionProbeSynthesizer.ProbeRequest("POST", "/carts", "{}")))
                .verifyBootsAndServes("c", SPEC_ITEMS_AND_CARTS,
                        List.of("The cart total is calculated correctly",
                                "GET /items/{id} returns HTTP 404 for an unknown id")))
                .isEmpty();
    }

    @Test
    void noSynthesizerOrDisabled_behavesExactlyAsBefore() throws Exception {
        packagedArtifact();
        // No synthesizer wired at all (the gate's prior shape).
        assertThat(gate(true, probeServerCommand(), "", 30_000)
                .verifyBootsAndServes("c", SPEC_ITEMS_AND_CARTS,
                        List.of("POST /carts creates a cart and returns HTTP 201")))
                .isEmpty();

        // Wired but switched off.
        BootGate off = gateProbing(List.of(
                new CriterionProbeSynthesizer.ProbeRequest("POST", "/carts", "{}")));
        off.setCriterionProbeEnabled(false);
        assertThat(off.verifyBootsAndServes("c", SPEC_ITEMS_AND_CARTS,
                List.of("POST /carts creates a cart and returns HTTP 201")))
                .isEmpty();
    }

    @Test
    void theTwoArgFormPassesNoCriteria_soExistingCallersAreUnaffected() throws Exception {
        packagedArtifact();
        assertThat(gateProbing(List.of(
                new CriterionProbeSynthesizer.ProbeRequest("POST", "/carts", "{}")))
                .verifyBootsAndServes("c", SPEC_ITEMS_AND_CARTS))
                .isEmpty();
    }

    /** A real, openable jar with no Main-Class — enough for {@code java -jar} to find and open it. */
    private static void writeMinimalJar(Path jarPath) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
            // empty archive besides the manifest — sufficient for path-resolution testing
        }
    }
}
