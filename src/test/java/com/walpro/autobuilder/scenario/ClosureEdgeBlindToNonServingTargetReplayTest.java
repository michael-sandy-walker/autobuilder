package com.walpro.autobuilder.scenario;

import com.walpro.autobuilder.analysis.structure.CommandSurfaceQuery;
import com.walpro.autobuilder.core.build.BehaviouralClosureVerifier;
import com.walpro.autobuilder.core.build.BootGate;
import com.walpro.autobuilder.core.build.ExercisableCriterionQuery;
import com.walpro.autobuilder.core.build.PackageGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Replays run {@code 2026-08-30_00-06-00} (clitarget) at the CLOSURE-CLAIM CHECK, which is the
 * link that let the run close at all. {@link CriteriaWorkItemDissolvedByCompileReplayTest} replays
 * the same run one step earlier, where the work item was dissolved; this one asks why nothing
 * caught the closure that followed.
 *
 * <p>The archived chain, timestamps from {@code autobuilder.log}:
 * <ol>
 *   <li>00:15:12 — a model reading the source answers {@code ✗} for four criteria and the
 *   completion edge refuses closure. Correct: the row reader really was wrong.</li>
 *   <li>00:16:16 — the same reading answers {@code ✓} for all seventeen, and the root
 *   completes. The artifact fails the two yardstick checks covering the requirement.</li>
 *   <li>Throughout, {@code T4-CU} logs <i>"closure claim not checked — none of its 4 criterion(s)
 *   can be executed"</i>. The execution-capable check never spoke, at any closure, all run.</li>
 * </ol>
 *
 * <p><b>Why it could not speak.</b> The verifier selected its probe list by asking whether a
 * criterion names an HTTP verb and a path. This target has no network surface, so the list was
 * empty at every closure and the check abstained every time — while {@code CommandExerciseSynthesizer},
 * which turns exactly these criteria into runnable invocations, sat wired into the very gate the
 * verifier delegates to. A second blocker sat behind it: the verifier handed that gate an empty
 * specification, and a command criterion can only be grounded against the surface the
 * specification states.
 *
 * <p><b>What this test pins, and what it deliberately does not.</b> It pins that the check now
 * REACHES the artifact on a non-serving target, and — using the production path, by passing the
 * argument production used to pass — that it did not before. It does not claim the four criteria
 * that failed are now caught: they are claims about an input file's CONTENT, the synthesizer
 * abstains on those by design, and {@code theContentCriteriaAreStillNotProbedAndThatIsTheGap}
 * asserts that rather than leaving it to be discovered later.
 */
@ExtendWith(MockitoExtension.class)
class ClosureEdgeBlindToNonServingTargetReplayTest {

    @Mock private PackageGate packageGate;
    @Mock private BootGate bootGate;

    private BehaviouralClosureVerifier verifier;

    /** Verbatim from the run's goal tree — the four the completion edge listed as unconfirmed. */
    private static final List<String> CONTENT_CRITERIA = List.of(
            "A file containing a quoted field with an embedded newline is parsed as a single logical row",
            "The reader correctly tracks the physical line number where each logical row starts",
            "check <file> prints \"line <n>: expected <h> fields, found <f>\" for each malformed row, "
                    + "where <n> is the physical line number of the start of that logical row",
            "count <file> prints the number of logical data rows (excluding header) as a bare integer to stdout");

    /** Verbatim from the same tree — claims about the surface rather than about a file's content. */
    private static final List<String> SURFACE_CRITERIA = List.of(
            "Running a known command with the wrong number of arguments exits 5",
            "Running with an unrecognised command word prints \"unknown command: <word>\" to stderr and exits 3");

    /** The run's specification, abridged to the declarations the surface query reads. */
    private static final String SPEC = """
              Evolve the Java 21 application into a COMMAND-LINE tool that validates and summarises
              delimited text files. It is not a service: it has no network surface, no HTTP.

              Invocation: java -jar app.jar <command> [arguments]

              check <file> — read the file and report malformed rows.
              count <file> — print the number of data rows (excluding the header) to stdout as a bare integer.
              column <file> <name> — print every value of the named column, one per line, in file order.

              Common rules: exit 1 when the file argument names a path that does not exist, printing
              "no such file: <path>" to stderr. Exit 3 when the command word is not one of the three above,
              printing "unknown command: <word>" to stderr. Exit 5 when the wrong number of arguments is
              given for a known command.
            """;

    @BeforeEach
    void setUp() {
        verifier = new BehaviouralClosureVerifier(packageGate, bootGate,
                new ExercisableCriterionQuery(new CommandSurfaceQuery()));
        lenient().when(packageGate.verifyPackageable(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void theArchivedRunNeverReachedTheArtifactAtAnyClosure() {
        // The defect, reproduced through the production path by passing what production passed:
        // an empty specification. Nothing is packaged, nothing is booted, nothing is observed.
        assertThat(verifier.refute("goal-ba65c7b0", SURFACE_CRITERIA, "")).isEmpty();

        verify(packageGate, never()).verifyPackageable(anyString());
        verify(bootGate, never()).verifyBootsAndServesWithAttribution(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void withItsOwnSpecificationTheCheckNowReachesTheArtifact() {
        when(bootGate.verifyBootsAndServesWithAttribution(anyString(), anyString(), any(), anyBoolean())).thenReturn(Optional.empty());

        verifier.refute("goal-ba65c7b0", SURFACE_CRITERIA, SPEC);

        verify(bootGate).verifyBootsAndServesWithAttribution(anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq(SURFACE_CRITERIA), anyBoolean());
    }

    @Test
    void aRefutationFromTheProbeBlocksTheClosureItWouldHaveWaivedThrough() {
        // The point of reaching the artifact. Where the run had only a model's reading of source,
        // an execution result now outranks it — the check refutes and the claim does not stand.
        when(bootGate.verifyBootsAndServesWithAttribution(anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(Optional.of(new com.walpro.autobuilder.goal.ClaimRefutation(
                        "\"Running a known command with the wrong number of arguments "
                        + "exits 5\" — but running it exited 0", SURFACE_CRITERIA)));

        assertThat(verifier.refute("goal-ba65c7b0", SURFACE_CRITERIA, SPEC))
                .isPresent()
                .get()
                .extracting(com.walpro.autobuilder.goal.ClaimRefutation::description).asString()
                .contains("exited 0");
    }

    @Test
    void theContentCriteriaAreStillNotProbedAndThatIsTheGap() {
        // The four that actually failed this run. Synthesising a fixture for them requires reading
        // the input FORMAT, not echoing the specification's example line: a fixture whose header
        // and data row are the same line miscounts both identically and passes against the very
        // artifact that is broken. The synthesizer abstains rather than add coverage with no power
        // to detect — so this run's specific failure is still not caught, and routing is not what
        // would catch it.
        assertThat(verifier.refute("goal-ba65c7b0", CONTENT_CRITERIA, SPEC)).isEmpty();

        verify(bootGate, never()).verifyBootsAndServesWithAttribution(anyString(), anyString(), any(), anyBoolean());
    }
}
