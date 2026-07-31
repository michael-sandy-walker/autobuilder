package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T4-CU: the refutal half, against a real child process rather than a mocked gate.
 *
 * <p><b>Why this exists.</b> {@link BehaviouralClosureVerifier} calls the boot gate with an EMPTY
 * specification, deliberately — it asks about the work item's own claims, not the whole spec. That
 * choice could easily have made the check inert, and it was originally verified by reading the code
 * path: the spec-declared endpoint set only governs how a NON-serving artifact is reported, while
 * criterion probing iterates the supplied list and the deterministic synthesizer never reads the
 * specification. Reading is not running. This launches {@link BootGateProbeServer} the way a
 * packaged application would be launched and checks that the chain actually refutes.
 *
 * <p>The unit tests around this class mock the gate, so they can only prove the wiring downstream
 * of a contradiction. Nothing else proves a contradiction is produced in the first place.
 */
class ClosureClaimRefutalEndToEndTest {

    @TempDir
    Path tempDir;

    private final BuildExecutor buildExecutor = mock(BuildExecutor.class);

    /**
     * The stub answers 500 to POST /orders, so a criterion demanding 400 is false about it — and
     * 400 is a status the deterministic synthesizer can actually build a request for.
     *
     * <p>The first cut of this test used {@code GET /failing returns HTTP 200} and failed, which is
     * the finding that justifies running rather than reading: {@link DeterministicProbeSynthesizer}
     * only builds probes for the REJECTION statuses (400/404/409), so a criterion asserting a
     * success status is parseable and unprobeable. The old "true claim" control was passing for
     * the same reason — nothing was probed — so it proved nothing either.
     */
    private static final String FALSE_CLAIM = "POST /orders returns HTTP 400";

    /** The stub answers 400 to POST /carts, so this claim holds and must NOT be refuted. */
    private static final String TRUE_CLAIM = "POST /carts returns HTTP 400";

    private BehaviouralClosureVerifier verifier() throws Exception {
        Path target = tempDir.resolve("target");
        Files.createDirectories(target);
        Files.writeString(target.resolve("app.jar"), "not a real archive");

        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.resolveWorkingDir(tempDir.toString())).thenReturn(tempDir.toString());

        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String testClasses = new File(BootGateProbeServer.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath()).getAbsolutePath();
        String command = javaBin + " -cp " + testClasses + " " + BootGateProbeServer.class.getName() + " {port}";

        BootGate bootGate = new BootGate(true, command, "", 15000, 3000,
                buildExecutor, new EndpointSurfaceQuery());
        bootGate.setFileBasePath(tempDir.toString());
        bootGate.setCriterionProbeSynthesizer(new CriterionProbeSynthesizer(null));  // deterministic path only; the model fallback is off by default

        // Packageability is a precondition the verifier checks first and is not what this test is
        // about; it is stubbed to "packageable" so the boot half is what gets exercised.
        PackageGate packageGate = mock(PackageGate.class);
        when(packageGate.verifyPackageable(anyString())).thenReturn(Optional.empty());

        return new BehaviouralClosureVerifier(packageGate, bootGate);
    }

    @Test
    void aClaimTheRunningApplicationContradictsIsRefuted() throws Exception {
        Optional<String> refuted = verifier().refute("goal-1", List.of(FALSE_CLAIM));

        assertThat(refuted).as("an empty spec text must not neuter the criterion probe").isPresent();
        assertThat(refuted.get()).contains("/orders");
    }

    @Test
    void aClaimTheRunningApplicationUpholdsIsNotRefuted() throws Exception {
        // The control. Without it, a verifier that refuted everything — the far more damaging
        // failure, since it would block every closure — would pass the test above.
        assertThat(verifier().refute("goal-1", List.of(TRUE_CLAIM))).isEmpty();
    }

    @Test
    void aTrueClaimAlongsideAFalseOneStillRefutes() throws Exception {
        // A work item usually carries several criteria and only one of them is wrong.
        Optional<String> refuted = verifier().refute("goal-1", List.of(TRUE_CLAIM, FALSE_CLAIM));

        assertThat(refuted).isPresent();
        assertThat(refuted.get()).contains("/orders");
    }
}
