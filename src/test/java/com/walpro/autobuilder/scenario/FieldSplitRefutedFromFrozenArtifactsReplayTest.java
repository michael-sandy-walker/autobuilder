package com.walpro.autobuilder.scenario;

import com.walpro.autobuilder.analysis.structure.CommandSurfaceQuery;
import com.walpro.autobuilder.analysis.structure.InputFormatExample;
import com.walpro.autobuilder.core.build.CommandExerciseSynthesizer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-DT's positive path, made deterministic.
 *
 * <p>The probe refutes only an artifact that is actually wrong in a way the specification's worked
 * example can catch, so a live run proves it only when the model happens to write that defect —
 * which it did on 2026-08-30 and again on 2026-09-10, and did NOT on the verification run of
 * 2026-09-11. Waiting for variance to cooperate is not a verification strategy, so both artifacts
 * are frozen under {@code src/test/resources/scenarios/} with the observation each produces.
 *
 * <ul>
 *   <li>{@code clitarget-field-split-broken} — run {@code 2026-09-10_21-46-44}, GOAL_FULFILLED at
 *   15/19, prints {@code b,c,de}. The source-reading verifier called this satisfied.</li>
 *   <li>{@code clitarget-field-split-correct} — run {@code 2026-09-10_12-33-26}, GOAL_FULFILLED at
 *   19/19, prints {@code b,c}. The control that must NOT be refused.</li>
 * </ul>
 *
 * <p>Each observation was measured by building the frozen tree and running it on exactly the
 * fixture {@code BootGate.ground()} stages for a discriminating column probe; the command is
 * recorded in each manifest. What is replayed here is everything the loop decides without the
 * model: the synthesis, the expected value taken from the specification, and the verdict.
 */
class FieldSplitRefutedFromFrozenArtifactsReplayTest {

    /** Verbatim from {@code data/clitarget/bootstrap/seed.txt}. */
    private static final String SEED = """
              FIELD SYNTAX. Fields are separated by commas. A field MAY be wrapped in double quotes, and a
              quoted field may contain commas and newlines, which are then part of the value and NOT
              separators. Inside a quoted field, two consecutive double quotes mean one literal double quote.
              Quotes are removed from the value. So the line  a,"b,c","d""e"  has THREE fields: a / b,c / d"e.

              count <file> — print the number of data rows to stdout as a bare integer.
              column <file> <name> — print every value of the named column, one per line, in file order.
            """;

    private static final String PARSE_CLAIM =
            "A file containing `a,\"b,c\",\"d\"\"e\"` is parsed into one row with three fields: "
                    + "`a`, `b,c`, `d\"e`.";

    private CommandExerciseSynthesizer.Exercise exercise() {
        CommandSurfaceQuery surfaceQuery = new CommandSurfaceQuery();
        Map<String, CommandSurfaceQuery.Command> surface = surfaceQuery.declaredInSpec(SEED);
        return CommandExerciseSynthesizer.synthesize(PARSE_CLAIM, surface,
                surfaceQuery.unrecognisedWord(SEED), InputFormatExample.readFrom(SEED)).orElseThrow();
    }

    /** What the frozen artifact printed for the staged fixture, recorded beside its tree. */
    private String observed(String scenario) throws Exception {
        Path manifest = Path.of("src/test/resources/scenarios", scenario, "manifest.properties");
        for (String line : Files.readAllLines(manifest)) {
            if (line.startsWith("observed_column_col2=")) {
                return line.substring("observed_column_col2=".length());
            }
        }
        throw new IllegalStateException("scenario " + scenario + " records no observation");
    }

    @Test
    void bothScenariosAreFrozenWithTheirProvenance() {
        assertThat(Scenario.load("clitarget-field-split-broken").manifest("target")).isEqualTo("clitarget");
        assertThat(Scenario.load("clitarget-field-split-correct").manifest("target")).isEqualTo("clitarget");
        // Source, not a binary: the trees carry the parser that produced each observation.
        assertThat(Scenario.load("clitarget-field-split-broken").javaFiles()).isNotEmpty();
    }

    @Test
    void theExpectedValueComesFromTheSpecificationNotFromTheCriterion() {
        assertThat(exercise().expectedStdout()).hasValue("b,c");
        assertThat(exercise().column())
                .hasValue(CommandExerciseSynthesizer.ColumnChoice.DISCRIMINATING);
    }

    @Test
    void theBrokenArtifactIsRefuted() throws Exception {
        Optional<String> contradiction =
                exercise().contradictedBy(observed("clitarget-field-split-broken"));

        assertThat(contradiction).isPresent();
        assertThat(contradiction.get())
                .contains("printed \"b,c,de\"")
                .contains("specification states \"b,c\"");
    }

    @Test
    void theCorrectArtifactIsNotRefused() throws Exception {
        // The control that matters most. A probe that blocks a correct run is worse than no probe,
        // and this is the walling risk T4-DT was filed with.
        assertThat(exercise().contradictedBy(observed("clitarget-field-split-correct")))
                .isEmpty();
    }

    @Test
    void containmentWouldHaveMissedIt() throws Exception {
        // Pinned because the message assertion sitting beside this one in BootGate uses contains(),
        // and "simplifying" the value check to match it would silently restore the defect.
        String broken = observed("clitarget-field-split-broken");
        String expected = exercise().expectedStdout().orElseThrow();

        assertThat(broken).contains(expected);
        assertThat(exercise().contradictedBy(broken)).isPresent();
    }
}
