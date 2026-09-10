package com.walpro.autobuilder.scenario;

import com.walpro.autobuilder.analysis.structure.CommandSurfaceQuery;
import com.walpro.autobuilder.analysis.structure.InputFormatExample;
import com.walpro.autobuilder.core.build.CommandExerciseSynthesizer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replays the closure of run {@code 2026-09-10_21-46-44} (clitarget), which completed
 * {@code GOAL_FULFILLED} at 15/19 on failing checks 1, 2, 4 and 5 — the within-line field
 * splitting — while checks 6 and 7, the multi-line pair that had been the standing failure on this
 * target since August, passed. The run solved the hard case and broke the easy one.
 *
 * <p>The chain, from the run's own log: criterion 9 — <i>"A file containing {@code a,"b,c","d""e"}
 * is parsed into one row with three fields: {@code a}, {@code b,c}, {@code d"e}"</i> — was answered
 * SATISFIED by the model reading the source, and the yardstick check for precisely that assertion
 * failed. Running the archived artifact settles it: {@code check} on the seed's own worked line
 * reports <i>"expected 3 fields, found 2"</i>, and {@code column} on the middle field prints
 * {@code b,c,de}. The verdict was not unevidenced, it was wrong about code the model had in front
 * of it — which is why the answer is an execution probe and not a better prompt.
 *
 * <p>The specification had already stated the correct answer. {@code InputFormatExample} kept the
 * COUNT and discarded the PARTS, and {@code Exercise} had no channel for a printed value, so the
 * oracle sat in the seed unused.
 */
class ParseClaimVerifiedWithoutExecutionReplayTest {

    /** Verbatim from {@code data/clitarget/bootstrap/seed.txt}. */
    private static final String SEED = """
              FIELD SYNTAX. Fields are separated by commas. A field MAY be wrapped in double quotes, and a
              quoted field may contain commas and newlines, which are then part of the value and NOT
              separators. Inside a quoted field, two consecutive double quotes mean one literal double quote.
              Quotes are removed from the value. So the line  a,"b,c","d""e"  has THREE fields: a / b,c / d"e.

              check <file> — read the file and report malformed rows.
              count <file> — print the number of data rows (excluding the header) to stdout as a bare integer.
              column <file> <name> — print every value of the named column, one per line, in file order.
            """;

    /** Verbatim: criterion 9 of that run's completion verification. */
    private static final String CRITERION_9 =
            "A file containing `a,\"b,c\",\"d\"\"e\"` is parsed into one row with three fields: "
                    + "`a`, `b,c`, `d\"e`.";

    private Optional<CommandExerciseSynthesizer.Exercise> synthesize(String criterion) {
        return synthesize(criterion, SEED);
    }

    private Optional<CommandExerciseSynthesizer.Exercise> synthesize(String criterion, String spec) {
        CommandSurfaceQuery surfaceQuery = new CommandSurfaceQuery();
        Map<String, CommandSurfaceQuery.Command> surface = surfaceQuery.declaredInSpec(spec);
        return CommandExerciseSynthesizer.synthesize(criterion, surface,
                surfaceQuery.unrecognisedWord(spec), InputFormatExample.readFrom(spec));
    }

    @Test
    void theSpecificationsOwnStatedPartsAreKeptRatherThanCountedAndDiscarded() {
        InputFormatExample.Example example = InputFormatExample.readFrom(SEED).orElseThrow();

        assertThat(example.trueParts()).isEqualTo(3);
        assertThat(example.parts()).containsExactly("a", "b,c", "d\"e");
        // The middle field is the one a careless reader merges with its neighbour; the other two
        // it would print correctly by splitting naively, so asserting them detects nothing.
        assertThat(example.discriminatingPartIndex()).hasValue(1);
    }

    @Test
    void theClaimTheVerifierGotWrongIsNowAnExecutableProbe() {
        CommandExerciseSynthesizer.Exercise exercise = synthesize(CRITERION_9).orElseThrow();

        assertThat(exercise.args()).containsExactly("column", null);   // null is the staged fixture
        assertThat(exercise.column()).hasValue(CommandExerciseSynthesizer.ColumnChoice.DISCRIMINATING);
        assertThat(exercise.expectedExitStatus()).isZero();
        // The oracle comes from the SPECIFICATION, never from the criterion being closed.
        assertThat(exercise.expectedStdout()).hasValue("b,c");
    }

    @Test
    void theProbeSeparatesTheBrokenArtifactFromTheCorrectOne() {
        // Both readings are real: run 2026-09-10_21-46-44's artifact prints "b,c,de" for this
        // column and run 2026-09-10_12-33-26's prints "b,c". The assertion has to refute the first
        // and pass the second, or it either misses the defect or walls a correct run.
        String expected = synthesize(CRITERION_9).orElseThrow().expectedStdout().orElseThrow();

        assertThat("b,c".strip()).isEqualTo(expected);
        assertThat("b,c,de".strip()).isNotEqualTo(expected);
        // ⚠ AND IT MUST BE EQUALITY. "b,c,de".contains("b,c") is true, so a containment check —
        // which is what the message assertion beside it uses — would have called the broken
        // artifact satisfied, exactly as the source-reading verifier did.
        assertThat("b,c,de".contains(expected))
                .as("containment cannot see this defect; only equality can")
                .isTrue();
    }

    @Test
    void aSpecificationThatStatesNoPartsYieldsNoOracleRatherThanAGuess() {
        // The common case, and the one that must stay silent: a worked example with a count and no
        // spelled-out parse. Inventing an expected value here would wall a run on an obligation
        // nobody stated, which is the failure direction T1-FW records.
        String countOnly = SEED.replace("has THREE fields: a / b,c / d\"e.", "has THREE fields.");
        InputFormatExample.Example example = InputFormatExample.readFrom(countOnly).orElseThrow();

        assertThat(example.trueParts()).isEqualTo(3);
        assertThat(example.hasParts()).isFalse();
        assertThat(example.discriminatingPartIndex()).isEmpty();
        assertThat(synthesize(CRITERION_9, countOnly)).isEmpty();
    }
}
