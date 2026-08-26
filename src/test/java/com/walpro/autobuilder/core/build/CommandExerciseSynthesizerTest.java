package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.analysis.structure.CommandSurfaceQuery;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-DD. Every criterion here is VERBATIM from run {@code 2026-08-26_11-02-00} — the run that
 * fulfilled on a defective artifact having executed none of its own 30 criteria. The abstain cases
 * matter most: they are the six this deliberately cannot build, and the reason is recorded on the
 * class rather than left to be rediscovered.
 */
class CommandExerciseSynthesizerTest {

    private static final CommandSurfaceQuery QUERY = new CommandSurfaceQuery();
    private static final String SPEC = """
            Commands:

            check <file> — read the file and report malformed rows. Exit 0 when every row is well formed.

            count <file> — print the number of data rows. Exit 0.

            column <file> <name> — print every value of the named column. Exit 4 when absent.
            """;

    private static Optional<CommandExerciseSynthesizer.Exercise> synth(String criterion) {
        Map<String, CommandSurfaceQuery.Command> surface = QUERY.declaredInSpec(SPEC);
        return CommandExerciseSynthesizer.synthesize(criterion, surface, QUERY.unrecognisedWord(SPEC));
    }

    @Test
    void anUnrecognisedOperationIsBuiltFromTheDeclaredSurface() {
        var e = synth("Running with an unrecognised command word prints \"unknown command: <word>\" "
                + "to stderr and exits 3");
        assertThat(e).isPresent();
        assertThat(e.get().expectedExitStatus()).isEqualTo(3);
        assertThat(e.get().fixture()).isEqualTo(CommandExerciseSynthesizer.Fixture.NONE);
        assertThat(QUERY.declaredInSpec(SPEC)).doesNotContainKey(e.get().args().get(0));
        assertThat(e.get().expectedMessage()).contains("unknown command: <word>");
    }

    @Test
    void theWrongArgumentCountComesFromTheDeclaredArity() {
        var e = synth("Running a known command with the wrong number of arguments exits 5");
        assertThat(e).isPresent();
        assertThat(e.get().expectedExitStatus()).isEqualTo(5);
        assertThat(e.get().args()).hasSize(1);              // an operation that takes one, given none
        assertThat(QUERY.declaredInSpec(SPEC)).containsKey(e.get().args().get(0));
    }

    @Test
    void anAbsentPathNeedsNoFormatKnowledge() {
        var e = synth("Running with a non-existent file path prints \"no such file: <path>\" "
                + "to stderr and exits 1");
        assertThat(e).isPresent();
        assertThat(e.get().fixture()).isEqualTo(CommandExerciseSynthesizer.Fixture.ABSENT_PATH);
        assertThat(e.get().args()).containsExactly(e.get().args().get(0), null);
        assertThat(e.get().expectedExitStatus()).isEqualTo(1);
    }

    @Test
    void anEmptyFileIsBuildableAndNamesItsOwnOperation() {
        var e = synth("check on an empty (zero-byte) file exits 2");
        assertThat(e).isPresent();
        assertThat(e.get().fixture()).isEqualTo(CommandExerciseSynthesizer.Fixture.EMPTY_FILE);
        assertThat(e.get().args().get(0)).isEqualTo("check");
        assertThat(e.get().expectedExitStatus()).isEqualTo(2);
    }

    /**
     * The six that abstain, and why: each is about the CONTENT of an input file. Building one
     * needs the input FORMAT, and a fixture that does not vary the property under test between
     * header and rows cannot detect the defect this run actually shipped.
     */
    @Test
    void aCriterionAboutAnInputsContentAbstains() {
        assertThat(synth("check exits 0 when every row is well formed")).isEmpty();
        assertThat(synth("check exits 2 when at least one row is malformed")).isEmpty();
        assertThat(synth("column exits 0 on success")).isEmpty();
        assertThat(synth("column exits 4 when the named column is not present in the header, "
                + "printing \"no such column: <name>\" to stderr")).isEmpty();
    }

    @Test
    void aCriterionStatingNoExitStatusAbstains() {
        assertThat(synth("Command words are matched case sensitively")).isEmpty();
        assertThat(synth("column prints every value of the named column, one per line, "
                + "in file order, with quoting removed")).isEmpty();
    }

    @Test
    void aSpecificationWithNoDeclaredSurfaceGetsNoOpinion() {
        assertThat(CommandExerciseSynthesizer.synthesize(
                "Running with an unrecognised command word exits 3", Map.of(), Optional.of("x"))).isEmpty();
    }
}
