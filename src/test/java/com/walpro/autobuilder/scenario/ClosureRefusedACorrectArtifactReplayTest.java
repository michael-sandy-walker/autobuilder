package com.walpro.autobuilder.scenario;

import com.walpro.autobuilder.analysis.structure.CommandSurfaceQuery;
import com.walpro.autobuilder.analysis.structure.InputFormatExample;
import com.walpro.autobuilder.core.build.CommandExerciseSynthesizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1-FY replayed against the run it was filed from.
 *
 * <p>Run {@code 2026-09-11_19-58-35} ended {@code terminal=deadline} at <b>19/19 with zero failing
 * checks</b> — a correct artifact — after the closure edge refused it four times between 20:22 and
 * 20:46, injecting a work item each time. The refusal, verbatim from the log: <em>"check
 * &lt;well_formed_file&gt; → exit 2 saying "line &lt;n&gt;: expected &lt;h&gt; fields, found
 * &lt;f&gt;", but running it exited 0"</em>. Exit 0 on a well-formed file is what the specification
 * requires; this was walling by the oracle.
 *
 * <p><b>Replayed at synthesis, which is where the defect lives.</b> The refusal needed no model and
 * no chance: given that criterion and that seed, the synthesizer built the exercise every time. So
 * the whole population of that run's criteria which mention an exit AND a row shape is put through,
 * and the invariant asserted over all of them rather than over the one that happened to fire.
 *
 * <p>The seed below is the Commands block of {@code data/clitarget/bootstrap/seed.txt} as the run
 * read it, not a fixture written for this test.
 */
class ClosureRefusedACorrectArtifactReplayTest {

    private static final String SEED = """
            FIELD SYNTAX. Fields are separated by commas. A field MAY be wrapped in double quotes, and a
            quoted field may contain commas and newlines, which are then part of the value and NOT
            separators. Inside a quoted field, two consecutive double quotes mean one literal double quote.
            Quotes are removed from the value. So the line  a,"b,c","d""e"  has THREE fields: a / b,c / d"e.

            Commands:

            check <file> — read the file and report malformed rows. Exit 0 when every row is well formed,
            exit 2 when at least one row is malformed.

            count <file> — print the number of data rows to stdout as a bare integer. Exit 0.

            column <file> <name> — print every value of the named column. Exit 4 when the named column is
            not present in the header.
            """;

    /** Every criterion this run banked that mentions an exit AND a row shape. Verbatim from its log. */
    private static final List<String> RUN_CRITERIA = List.of(
            "Exit 0 when every row is well formed, exit 2 when at least one row is malformed",
            "The `check` command exits with code 0 when every row is well-formed.",
            "The `check` command exits with code 2 when at least one row is malformed.",
            "The `check` command reads a file, reports malformed rows to stdout in the format "
                    + "\"line <n>: expected <h> fields, found <f>\", and exits with code 2 if any malformed "
                    + "rows are found, or 0 if all rows are well-formed.",
            "When invoked with `check <file>` where all rows are well-formed, the tool exits with code 0 "
                    + "and prints nothing to stdout.");

    private static Optional<CommandExerciseSynthesizer.Exercise> synth(String criterion) {
        CommandSurfaceQuery q = new CommandSurfaceQuery();
        return CommandExerciseSynthesizer.synthesize(criterion, q.declaredInSpec(SEED),
                q.unrecognisedWord(SEED), InputFormatExample.readFrom(SEED));
    }

    /**
     * The invariant the four refusals broke: no exercise may demand a NON-ZERO status from a file
     * the specification calls well formed. Asserted over the whole population, because asserting it
     * over the one criterion that fired would pass the moment the model rephrases it.
     */
    @Test
    void noExerciseDemandsAFailureStatusFromAWellFormedFile() {
        for (String criterion : RUN_CRITERIA) {
            Optional<CommandExerciseSynthesizer.Exercise> e = synth(criterion);
            if (e.isEmpty()) continue;
            boolean wellFormedFixture =
                    e.get().fixture() == CommandExerciseSynthesizer.Fixture.WELL_FORMED_FILE;
            boolean demandsFailure = e.get().assertsExitStatus() && e.get().expectedExitStatus() != 0;
            assertThat(wellFormedFixture && demandsFailure)
                    .withFailMessage("criterion would refuse a correct artifact: %s -> %s",
                            criterion, e.get().describe())
                    .isFalse();
        }
    }

    /** The criterion that actually caused the four refusals, pinned on its own. */
    @Test
    void theCompoundCriterionBuildsNoExerciseAtAll() {
        assertThat(synth(RUN_CRITERIA.get(3))).isEmpty();
    }

    /**
     * ⚠ The counterweight. Silencing this whole family would also be a way to pass the test above,
     * so a criterion stating ONE outcome must still be exercised — this is how the run's other
     * closure checks kept their teeth.
     */
    @Test
    void aSingleOutcomeCriterionFromTheSameRunStillSynthesizes() {
        var e = synth(RUN_CRITERIA.get(2));   // "exits with code 2 when at least one row is malformed"
        assertThat(e).isPresent();
        assertThat(e.get().expectedExitStatus()).isEqualTo(2);
        assertThat(e.get().fixture()).isEqualTo(CommandExerciseSynthesizer.Fixture.MALFORMED_FILE);
    }
}
