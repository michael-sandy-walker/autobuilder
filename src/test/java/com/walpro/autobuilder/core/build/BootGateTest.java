package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    /**
     * T4-DD item 3. The sibling of the test above, and its absence was the defect: the clean-exit
     * branch asked whether the specification wanted a served surface and the non-zero branch did
     * not, so an artifact that terminated BY DESIGN was reported as one that crashed at startup.
     * A command-line tool signalling a malformed input through its exit status is doing exactly
     * what its specification asks; the gate holds no expectation to judge that status against and
     * must therefore hold no opinion.
     */
    /**
     * T4-DD item 2, end to end: a terminating artifact's criteria state an exit status, and the
     * gate now exercises them instead of having nothing to ask. The "artifact" is a script the
     * test writes and the launch command is the shell, because what is under test is the gate's
     * read-invoke-compare loop, not a packaging format.
     */
    private void terminatingArtifact() throws Exception {
        Path target = tempDir.resolve("target");
        Files.createDirectories(target);
        // Exits 3 for the word this test's criteria name, and 9 for anything else — so one
        // criterion is satisfied and one is contradicted by the SAME artifact.
        Files.writeString(target.resolve("app.jar"),
                "#!/bin/sh\nif [ \"$1\" = unknown ]; then exit 3; fi\nexit 9\n");
    }

    @Test
    void terminatingArtifact_criterionContradictedByItsExitStatus_blocks() throws Exception {
        terminatingArtifact();
        Optional<String> blocker = gate(true, "sh {artifact}", "", 2000)
                .verifyBootsAndServes("c", "Produce a delimited-text checking tool.",
                        List.of("Running `sh app.jar frobnicate` prints an error and exits with code 3"),
                        false);

        assertThat(blocker).isPresent();
        assertThat(blocker.get()).contains("acceptance criteria fail").contains("exited 9");
    }

    @Test
    void terminatingArtifact_criterionSatisfiedByItsExitStatus_passes() throws Exception {
        terminatingArtifact();
        assertThat(gate(true, "sh {artifact}", "", 2000)
                .verifyBootsAndServes("c", "Produce a delimited-text checking tool.",
                        List.of("Running `sh app.jar unknown` prints an error and exits with code 3"),
                        false)).isEmpty();
    }

    /** A criterion the parser declines is not judged — the artifact's exit 9 goes unremarked. */
    @Test
    void terminatingArtifact_unparseableCriterionIsNotJudged() throws Exception {
        terminatingArtifact();
        assertThat(gate(true, "sh {artifact}", "", 2000)
                .verifyBootsAndServes("c", "Produce a delimited-text checking tool.",
                        List.of("Running `sh app.jar <command>` exits with code 3",
                                "Malformed rows are printed in ascending line number order"),
                        false)).isEmpty();
    }

    /** A specification that declares an operation surface, as the field runs' seeds do. */
    private static final String CLI_SPEC = """
            Produce a delimited-text checking tool.

            Commands:

            check <file> — read the file and report malformed rows. Exit 0 when well formed.
            """;

    /**
     * T4-DD: a criterion naming a DECLARED OPERATION rather than a literal invocation — the shape
     * run 2026-08-26_11-02-00 authored thirty of and executed none of. The surface supplies the
     * operation and its arity; nothing about the input's format is needed.
     */
    @Test
    void terminatingArtifact_criterionNamingADeclaredOperationIsExercised() throws Exception {
        terminatingArtifact();   // exits 3 for "unknown", 9 otherwise
        Optional<String> blocker = gate(true, "sh {artifact}", "", 2000)
                .verifyBootsAndServes("c", CLI_SPEC,
                        List.of("Running a known command with the wrong number of arguments exits 5"),
                        false);

        assertThat(blocker).isPresent();
        assertThat(blocker.get()).contains("exited 9");
    }

    /** The same shape, satisfied: the artifact answers the status the criterion states. */
    @Test
    void terminatingArtifact_declaredOperationCriterionSatisfied_passes() throws Exception {
        terminatingArtifact();
        assertThat(gate(true, "sh {artifact}", "", 2000)
                .verifyBootsAndServes("c", CLI_SPEC,
                        List.of("Running a known command with the wrong number of arguments exits 9"),
                        false)).isEmpty();
    }

    /**
     * The defect an exit status cannot see: the artifact answers the RIGHT status and never says
     * what the criterion requires. Without the message check this passes silently.
     */
    @Test
    void terminatingArtifact_rightStatusButWrongMessage_blocks() throws Exception {
        terminatingArtifact();
        Optional<String> blocker = gate(true, "sh {artifact}", "", 2000)
                .verifyBootsAndServes("c", CLI_SPEC,
                        List.of("Running with a non-existent file path prints \"no such file: <path>\" "
                                + "to stderr and exits 9"),
                        false);

        assertThat(blocker).isPresent();
        assertThat(blocker.get()).contains("never said");
    }

    /**
     * ⚠ FIELD DEFECT, run 2026-08-26_14-53-24, and it is this project's own recurring shape built
     * fresh: a message describing values the exercise never chose had the FIXTURE PATH substituted
     * into a LINE NUMBER, and the resulting nonsense blocked closure six times on an artifact that
     * answered the criterion correctly. The criterion below is verbatim from that run. The exit
     * status matches, so a message check that cannot ground itself must stay silent — an
     * ungrounded message is compared against text the run never chose.
     */
    @Test
    void terminatingArtifact_aMessageNamingValuesTheExerciseNeverChoseIsNotChecked() throws Exception {
        Path target = tempDir.resolve("target");
        Files.createDirectories(target);
        // Exits 2 for check, as the criterion requires, and prints nothing.
        Files.writeString(target.resolve("app.jar"), "#!/bin/sh\n[ \"$1\" = check ] || exit 3\nexit 2\n");

        assertThat(gate(true, "sh {artifact}", "", 2000)
                .verifyBootsAndServes("c", CLI_SPEC_WITH_EXAMPLE,
                        List.of("check on a file with a malformed row prints "
                                + "\"line <n>: expected <h> fields, found <f>\" to stdout and exits 2"),
                        false)).isEmpty();
    }

    /**
     * A criterion about the CONTENT of an input abstains — building the fixture needs the format,
     * and a fixture that does not vary the property under test cannot detect anything.
     */
    @Test
    void terminatingArtifact_criterionAboutInputContentIsNotJudged() throws Exception {
        terminatingArtifact();
        assertThat(gate(true, "sh {artifact}", "", 2000)
                .verifyBootsAndServes("c", CLI_SPEC,
                        List.of("check exits 0 when every row is well formed"),
                        false)).isEmpty();
    }

    /** A specification that declares a surface AND works an example of its input format. */
    private static final String CLI_SPEC_WITH_EXAMPLE = """
            Produce a delimited-text checking tool.

            Commands:

            check <file> — read the file and report malformed rows. Exit 0 when well formed.

            A field may be wrapped in quotes and then carry a separator inside it.
            So the line  a,"b,c","d""e"  has THREE fields: a / b,c / d"e.
            """;

    /** An artifact that divides each line naively — the defect run 2026-08-26_11-02-00 shipped. */
    private void carelessReaderArtifact() throws Exception {
        Path target = tempDir.resolve("target");
        Files.createDirectories(target);
        Files.writeString(target.resolve("app.jar"), """
                #!/bin/sh
                [ "$1" = check ] || exit 3
                h=$(head -1 "$2" | awk -F, '{print NF}')
                r=$(sed -n 2p "$2" | awk -F, '{print NF}')
                [ "$h" = "$r" ] && exit 0
                exit 2
                """);
    }

    /**
     * T4-DD, the case the whole seam exists for. The criterion is about the CONTENT of an input,
     * so the fixture is built from the specification's own worked example: a header of the width
     * the specification states, and the sample as its row. A careless reader divides the two
     * differently and is caught. Verified against three real artifacts before being written here —
     * a correct one contradicts nothing, this shape contradicts exactly this criterion.
     */
    @Test
    void terminatingArtifact_carelessReaderCaughtByAFixtureTheSpecificationImplies_blocks() throws Exception {
        carelessReaderArtifact();
        Optional<String> blocker = gate(true, "sh {artifact}", "", 2000)
                .verifyBootsAndServes("c", CLI_SPEC_WITH_EXAMPLE,
                        List.of("check exits 0 when every row is well formed"), false);

        assertThat(blocker).isPresent();
        assertThat(blocker.get()).contains("acceptance criteria fail").contains("exited 2");
    }

    /**
     * ⚠ The guard that keeps the fixture honest: with no worked example there is no way to know
     * the format, so the same criterion against the same broken artifact is NOT judged. Silence,
     * never a guess — a fixture built without the format cannot tell a correct reader from this one.
     */
    @Test
    void terminatingArtifact_withoutAWorkedExample_aContentCriterionIsNotJudged() throws Exception {
        carelessReaderArtifact();
        String specWithoutExample = """
                Produce a delimited-text checking tool.

                Commands:

                check <file> — read the file and report malformed rows. Exit 0 when well formed.
                """;
        assertThat(gate(true, "sh {artifact}", "", 2000)
                .verifyBootsAndServes("c", specWithoutExample,
                        List.of("check exits 0 when every row is well formed"), false)).isEmpty();
    }

    /** A reader that agrees with the specification's example is not contradicted. */
    @Test
    void terminatingArtifact_carefulReaderIsNotContradicted() throws Exception {
        Path target = tempDir.resolve("target");
        Files.createDirectories(target);
        Files.writeString(target.resolve("app.jar"), "#!/bin/sh\n[ \"$1\" = check ] || exit 3\nexit 0\n");

        assertThat(gate(true, "sh {artifact}", "", 2000)
                .verifyBootsAndServes("c", CLI_SPEC_WITH_EXAMPLE,
                        List.of("check exits 0 when every row is well formed"), false)).isEmpty();
    }

    @Test
    void nonZeroExit_whenSpecNamesNoEndpoints_abstains() throws Exception {
        // The exit code itself is immaterial here, which is the point: with no served surface
        // asked for, a non-zero status is the tool's reporting channel and not a verdict this
        // gate is entitled to read. Its counterpart is startupCrash_blocksWithExitCode, which
        // sends the same command against a spec that DOES name endpoints and still blocks —
        // together they pin the discriminator as the specification's surface, not the status.
        packagedArtifact();
        assertThat(gate(true, "false", "", 2000)
                .verifyBootsAndServes("c", "Produce a delimited-text checking tool. "
                        + "A malformed input file is reported through the exit status.")).isEmpty();
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

            // The spec names endpoints only so that a launch failure is still reported: this
            // test is about the artifact PATH resolving, and it reads its answer off the block.
            Optional<String> blocker = g.verifyBootsAndServes("c", SPEC_WITH_ENDPOINTS);

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
    @Test
    void theModelFallbackIsCappedPerSweep() throws Exception {
        // Bought with a run: 2026-08-05_20-58-50 reached the completion edge with 49 criteria, 30
        // unprobed, fired 30 synchronous model calls at an already-struggling local model, and the
        // circuit breaker opened in the same second as the sweep. The field cannot be relied on to
        // re-test this — the edge is only reached on a root-closure attempt, and the very next run
        // (109 cycles) never got there — so the bound is pinned here instead.
        packagedArtifact();   // without this the gate abstains before it ever probes
        BootGate g = gate(true, probeServerCommand(), "", 30_000);
        CriterionProbeSynthesizer synthesizer = mock(CriterionProbeSynthesizer.class);
        // Nothing is probeable by either path, so every criterion is a fallback candidate.
        when(synthesizer.synthesize(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(Optional.empty());
        g.setCriterionProbeSynthesizer(synthesizer);

        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add("GET /thing" + i + "/{id} returns HTTP 409");
        }
        g.verifyBootsAndServes("cycle-cap", "", many, true);

        // atMost alone would pass vacuously if the model were never consulted at all — the trap
        // that let nine green T2-EY tests guard a gate that had never fired. Assert the path is
        // REACHED as well as bounded.
        verify(synthesizer, org.mockito.Mockito.atLeastOnce())
                .synthesize(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(true));
        verify(synthesizer, org.mockito.Mockito.atMost(BootGate.MAX_MODEL_FALLBACKS_PER_SWEEP))
                .synthesize(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void withoutPermissionTheModelIsNeverConsulted() throws Exception {
        // The three in-cycle callers must keep the deterministic-only path that T4-CY's
        // measurement demanded.
        packagedArtifact();
        BootGate g = gate(true, probeServerCommand(), "", 30_000);
        CriterionProbeSynthesizer synthesizer = mock(CriterionProbeSynthesizer.class);
        when(synthesizer.synthesize(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(Optional.empty());
        g.setCriterionProbeSynthesizer(synthesizer);

        g.verifyBootsAndServes("cycle-nofallback", "",
                List.of("GET /thing/{id} returns HTTP 409"), false);

        verify(synthesizer, never()).synthesize(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(true));
    }

    private BootGate gateProbing(List<CriterionProbeSynthesizer.ProbeRequest> sequence) {
        BootGate g = gate(true, probeServerCommand(), "", 30_000);
        CriterionProbeSynthesizer synthesizer = mock(CriterionProbeSynthesizer.class);
        // T4-DB added a per-call model-fallback flag, so the gate calls the four-argument form.
        // Stubbing only the three-argument one made this mock silently return empty and the test
        // fail with "expected a value" — worth stubbing by the signature the gate actually calls.
        when(synthesizer.synthesize(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(Optional.of(sequence));
        g.setCriterionProbeSynthesizer(synthesizer);
        return g;
    }

    @Test
    void criterionContradictedByTheRunningApplication_blocksAndQuotesIt() throws Exception {
        // /items answers 200 to a POST; the criterion claims 201. Only a request can settle this,
        // and the two statuses being BOTH successful is what makes it settleable: the application
        // accepted the body and still answered the wrong thing, so no doubt about the body's
        // validity can explain it away. This is the live failure of run 2026-08-23_02-12-34, where
        // all five failing smoke checks were a create answering 200 where the spec says 201.
        packagedArtifact();
        Optional<String> blocker = gateProbing(List.of(
                new CriterionProbeSynthesizer.ProbeRequest("POST", "/items", "{\"name\":\"x\"}")))
                .verifyBootsAndServes("c", SPEC_ITEMS_AND_CARTS,
                        List.of("POST /items creates an item and returns HTTP 201 with the item body"));

        assertThat(blocker).isPresent();
        assertThat(blocker.get())
                .contains("POST /items creates an item and returns HTTP 201")
                .contains("POST /items → 201")
                .contains("answered 200");
    }

    @Test
    void aProbeBodyTheApplicationRefusesAbstainsRatherThanReportingAContradiction() throws Exception {
        // /carts answers 400 to a POST. The criterion claims 201, so this LOOKS like the strongest
        // possible contradiction — and it is precisely the one that must not be reported. The body
        // was derived on this side, and no derivation can be sure it satisfies validation it never
        // discovered, so a client error is evidence about the body and not about the endpoint.
        // Reporting it would score a defect against a strict, correct artifact, which is the
        // objection that kept creates unprobed until the ingredient existed (T4-DB).
        packagedArtifact();
        Optional<String> blocker = gateProbing(List.of(
                new CriterionProbeSynthesizer.ProbeRequest("POST", "/carts", "{\"name\":\"x\"}")))
                .verifyBootsAndServes("c", SPEC_ITEMS_AND_CARTS,
                        List.of("POST /carts creates a cart and returns HTTP 201 with the cart body"));

        assertThat(blocker).isEmpty();
    }

    @Test
    void aServerErrorOnACreateIsStillReported_itIsNotARefusal() throws Exception {
        // The abstention above is about REFUSAL. /orders answers 500 to a POST, which is not the
        // application declining a body — it is the application breaking, and the recorded
        // false-completion class this gate exists for.
        packagedArtifact();
        Optional<String> blocker = gateProbing(List.of(
                new CriterionProbeSynthesizer.ProbeRequest("POST", "/orders", "{\"name\":\"x\"}")))
                .verifyBootsAndServes("c", SPEC_ITEMS_AND_CARTS,
                        List.of("POST /orders creates an order and returns HTTP 201 with the order body"));

        assertThat(blocker).isPresent();
        assertThat(blocker.get()).contains("answered 500");
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
    void theSameFailureSummarisesIdentically_soItCannotMintTwinWorkItems() {
        // Field defect (run 2026-07-30_04-36-29): the self-check observed the same two unreachable
        // endpoints seven minutes apart and injected TWO ACTIVE TWINS of one goal. The findings were
        // 1286 characters each and differed in about forty — the ISO timestamp, the pid, the servlet
        // thread name and the ephemeral port. That defeated three layers at once: the suppression
        // guard comparing a finding to the last one, the paraphrase filter, and per-gap idempotency,
        // all of which key on this text. A defect the loop had not yet fixed re-minted itself every
        // interval and inflated the backlog.
        java.util.Deque<String> first = new java.util.ArrayDeque<>(List.of(
                "2026-07-30T04:43:23.864+02:00  INFO 1279961 --- [io-37921-exec-1] o.a.c.C.[Tomcat]"
                        + " : ERROR no mapping for /books on localhost:37921"));
        java.util.Deque<String> second = new java.util.ArrayDeque<>(List.of(
                "2026-07-30T04:50:40.215+02:00  INFO 1281328 --- [io-44943-exec-1] o.a.c.C.[Tomcat]"
                        + " : ERROR no mapping for /books on localhost:44943"));

        String a = BootGate.summariseOutput(first);
        String b = BootGate.summariseOutput(second);

        assertThat(a).isEqualTo(b);
        // The substance survives — only the per-run tokens go.
        assertThat(a).contains("no mapping for /books").doesNotContain("1279961").doesNotContain("04:43:23");
    }

    @Test
    void healthyStartupChatterIsNotAttachedToAWorkItem() {
        // Field defect (run 2026-07-30_21-25-43): when nothing in the output is diagnostic, the
        // summariser fell back to whatever the process last printed. For a defect that is
        // BEHAVIOURAL rather than a crash there is no exception to find, so the tail is ordinary
        // boot logging — and 1119 characters of it were welded into a goal description and
        // re-rendered into prompts 46 times, about 12.9k tokens of text that was never actionable
        // and had gone stale besides. Absence of a diagnostic line is information; the last three
        // lines of a healthy startup are not.
        java.util.Deque<String> healthy = new java.util.ArrayDeque<>(List.of(
                "  INFO 1279961 --- [           main] o.a.c.c.C.[Tomcat].[localhost].[/]"
                        + " : Initializing Spring DispatcherServlet 'dispatcherServlet'",
                "  INFO 1279961 --- [           main] o.s.web.servlet.DispatcherServlet"
                        + " : Initializing Servlet 'dispatcherServlet'",
                "  INFO 1279961 --- [           main] o.s.web.servlet.DispatcherServlet"
                        + " : Completed initialization in 0 ms"));

        String summary = BootGate.summariseOutput(healthy);

        assertThat(summary).doesNotContain("DispatcherServlet").doesNotContain("Tomcat");
        assertThat(summary).contains("nothing diagnostic");
        assertThat(summary.length()).isLessThan(120);
    }

    @Test
    void arealDiagnosticLineStillSurvives() {
        // The T1-FJ path must keep working: when the process DOES log a cause, that cause is the
        // whole point of the summary and must reach the work item.
        java.util.Deque<String> withCause = new java.util.ArrayDeque<>(List.of(
                "  INFO --- [main] o.s.web.servlet.DispatcherServlet : Completed initialization",
                "  ERROR --- [main] o.s.boot.SpringApplication : Application run failed",
                "java.lang.IllegalStateException: Ensure that the compiler uses the '-parameters' flag"));

        String summary = BootGate.summariseOutput(withCause);

        assertThat(summary).contains("-parameters");
        assertThat(summary).doesNotContain("Completed initialization");
    }

    @Test
    void aRefusedSetupRequestIsAnAbstain_notAContradiction() throws Exception {
        // T4-CY: measured on the live specification — the duplicate-loan probe's setup POST is
        // refused when the book and member it references do not exist yet, and the asserted request
        // then answers about a situation that was never created. Here /orders refuses the setup POST
        // with 500 and /missing answers 404 where the criterion claims 409: without the guard that
        // 404 would be reported as a defect the application does not have.
        packagedArtifact();
        assertThat(gateProbing(List.of(
                new CriterionProbeSynthesizer.ProbeRequest("POST", "/orders", "{}"),
                new CriterionProbeSynthesizer.ProbeRequest("GET", "/missing", null)))
                .verifyBootsAndServes("c", SPEC_ITEMS_AND_CARTS,
                        List.of("GET /missing returns HTTP 409 when it already exists")))
                .isEmpty();
    }

    @Test
    void unparseableCriteriaAreSkipped_notFailed() throws Exception {
        // Both of these are unreadable as assertions: the first names no endpoint and no status,
        // the second names an endpoint but no status. NB after T4-CU slice 2 a path variable no
        // longer makes a criterion unparseable — "GET /items/{id} returns HTTP 404" IS readable
        // now, and using it here fed it this stub's unrelated probe. In production the drift
        // guard rejects a sequence that does not end on the asserted endpoint
        // (CriterionProbeSynthesizerTest covers that); mocking the synthesizer bypasses it.
        packagedArtifact();
        assertThat(gateProbing(List.of(
                new CriterionProbeSynthesizer.ProbeRequest("POST", "/carts", "{}")))
                .verifyBootsAndServes("c", SPEC_ITEMS_AND_CARTS,
                        List.of("The cart total is calculated correctly",
                                "GET /items/{id} returns the item when it exists")))
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

    /**
     * T4-DI regression, measured on run 2026-08-28_12-25-36. The bootstrapped pom no longer
     * carries a packaging plugin (correctly — that was one ecosystem's answer), so the artifact
     * built a plain jar and `java -jar` refused with "no main manifest attribute". The keyword
     * filter dropped that line for containing no Exception/ERROR token, so 70 work items told the
     * model "exits at startup (exit 1) — the process logged nothing diagnostic", and the run spent
     * 74 minutes on a defect its own output named in seven words.
     */
    /**
     * T4-DN correction, from run 2026-08-28_13-58-46. Keyword filtering is not merely insufficient
     * for a process that never started — it actively prefers the wrong line. That artifact printed
     * the cause AND its remedy, neither containing an Exception/ERROR token, while Spring's generic
     * wrapper matched and won. The loop was handed the wrapper 133 times and never learned that a
     * DataSource was unconfigured.
     */
    @Test
    void aFailedStartupYieldsTheCause_notTheGenericWrapperThatHappensToSayException() {
        Deque<String> tail = new ArrayDeque<>(List.of(
                "WARN --- ConfigServletWebServerApplicationContext : Exception encountered during"
                        + " context initialization - cancelling refresh attempt",
                "Description:",
                "Failed to configure a DataSource: 'url' attribute is not specified and no embedded"
                        + " datasource could be configured.",
                "Action:",
                "If you want an embedded database (H2, HSQL or Derby), please put it on the classpath."));

        String summary = BootGate.summariseOutput(tail, true);

        assertThat(summary).contains("Failed to configure a DataSource");
        assertThat(summary).doesNotContain("cancelling refresh attempt");
    }

    @Test
    void aLauncherRefusalIsTheDiagnostic_evenWithoutAnErrorKeyword() {
        Deque<String> tail = new ArrayDeque<>(List.of(
                "no main manifest attribute, in /out/target/app-0.0.1-SNAPSHOT.jar"));

        assertThat(BootGate.summariseOutput(tail, true))
                .contains("no main manifest attribute");
    }

    @Test
    void aRunningProcessesRoutineChatterIsStillNotTreatedAsADiagnostic() {
        // The anti-chatter rule exists because boot logging was welded onto behavioural work items
        // (1119 characters re-rendered 46 times). It must survive the fix above: only a process
        // that never STARTED gets its keyword-free output promoted.
        Deque<String> tail = new ArrayDeque<>(List.of(
                "Initializing Spring DispatcherServlet 'dispatcherServlet'",
                "Completed initialization in 12 ms"));

        assertThat(BootGate.summariseOutput(tail, false))
                .isEqualTo("the process logged nothing diagnostic before the observation ended");
        assertThat(BootGate.summariseOutput(tail))
                .isEqualTo("the process logged nothing diagnostic before the observation ended");
    }
    // ---- T1-FV: a derived body carries components the interface never accepts ----

    /**
     * The verbatim case from run 2026-08-28_21-47-39. The derived body added `returned` from the
     * Loan entity; the handler took Map<String, Long>; the boolean could not bind; the artefact
     * answered 400 and was recorded as failing a requirement it in fact met. Reducing the body to
     * what the criterion names is what separates the two.
     */
    @Test
    void minimalBody_dropsAnEntityFieldTheCriterionNeverMentions() {
        String criterion = "POST /loans with a non-existent bookId or memberId returns HTTP 404 "
                + "with JSON body {\"message\": \"<reason>\"}.";
        String derived = "{\"bookId\":999999999,\"memberId\":999999999,\"returned\":true}";

        Optional<String> minimal = BootGate.minimalBodyFor(criterion, derived);

        // Exact, not "contains": this string is the third link in a chain whose other two
        // links were measured against the booted artefact — the derived body drew 400, and
        // THIS body drew the specified 404. An approximate assertion would leave the join
        // between them untested.
        assertThat(minimal).contains("{\"bookId\":999999999,\"memberId\":999999999}");
    }

    /** Nothing dropped means the reduction settles nothing, so there is no second opinion to offer. */
    @Test
    void minimalBody_abstainsWhenTheCriterionNamesEveryField() {
        Optional<String> minimal = BootGate.minimalBodyFor(
                "POST /loans with a bookId and a memberId returns 404",
                "{\"bookId\":1,\"memberId\":2}");

        assertThat(minimal).isEmpty();
    }

    /** Dropping everything would send a body the criterion cannot vouch for either. */
    @Test
    void minimalBody_abstainsWhenTheCriterionNamesNoField() {
        Optional<String> minimal = BootGate.minimalBodyFor(
                "POST /loans returns 404 when the referenced records are absent",
                "{\"bookId\":1,\"returned\":true}");

        assertThat(minimal).isEmpty();
    }

    /**
     * Whole-token matching, not substring. "bookId" in the text must not claim a separate `id`
     * field — that would keep the server-assigned component this exists to drop.
     */
    @Test
    void minimalBody_doesNotLetOneFieldNameClaimAnother() {
        Optional<String> minimal = BootGate.minimalBodyFor(
                "POST /loans with a non-existent bookId returns 404",
                "{\"bookId\":1,\"id\":7}");

        assertThat(minimal).isPresent();
        assertThat(minimal.get()).contains("bookId").doesNotContain("\"id\"");
    }

    /** An unparseable or non-object body offers nothing to reduce. */
    @Test
    void minimalBody_abstainsOnAnythingItCannotRead() {
        assertThat(BootGate.minimalBodyFor("names bookId", "not json at all")).isEmpty();
        assertThat(BootGate.minimalBodyFor("names bookId", "[1,2,3]")).isEmpty();
        assertThat(BootGate.minimalBodyFor("names bookId", "")).isEmpty();
        assertThat(BootGate.minimalBodyFor(null, "{\"bookId\":1}")).isEmpty();
    }

}
