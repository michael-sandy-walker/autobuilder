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

    /**
     * ⚠ The format-bearing variant, and it is not interchangeable with the one above. Whole
     * branches of synthesis — the absent-column exercise among them — live inside
     * {@code if (format.isPresent())}, so a criterion routed through {@link #synth} alone cannot
     * reach them and comes back empty for a reason that has nothing to do with the criterion.
     * A test written that way passes or fails on the harness rather than on the behaviour, which
     * is the same trap {@code T4-DU} fell into one layer up ([[feedback-llm-probe-realistic-context]]).
     */
    private static Optional<CommandExerciseSynthesizer.Exercise> synthWithFormat(String criterion) {
        String spec = SPEC + "\nSo the line  a,\"b,c\",\"d\"\"e\"  has THREE fields: a / b,c / d\"e.\n";
        return CommandExerciseSynthesizer.synthesize(criterion, QUERY.declaredInSpec(spec),
                QUERY.unrecognisedWord(spec),
                com.walpro.autobuilder.analysis.structure.InputFormatExample.readFrom(spec));
    }

    /**
     * T1-FY, and the criterion text is VERBATIM from run {@code 2026-09-11_19-58-35} rather than
     * written for the test — the run where it made the probe demand exit 2 from a well-formed file,
     * refuse a 19/19 artifact four times, and inject a work item each time.
     *
     * <p>Three guards already existed for this and each was defeated by where the words sat: the
     * status count saw one status because the second is a bare number, {@code DISJUNCTION} needs the
     * {@code or} adjacent to the digits, and the {@code well-formed} lookahead is on a different
     * alternative than the one that matched. The fix counts the elided status instead of adding a
     * fourth pattern.
     */
    @Test
    void aCriterionStatingTwoOutcomesSynthesizesNothing() {
        assertThat(synth("The `check` command reads a file, reports malformed rows to stdout in the "
                + "format \"line <n>: expected <h> fields, found <f>\", and exits with code 2 if any "
                + "malformed rows are found, or 0 if all rows are well-formed.")).isEmpty();
    }

    @Test
    void theElidedSecondStatusIsCounted() {
        assertThat(CommandExerciseSynthesizer.statusesIn("exits with code 2 if any malformed rows "
                + "are found, or 0 if all rows are well-formed")).containsExactlyInAnyOrder(2, 0);
    }

    /**
     * ⚠ The counterweight, measured before it was written: over the 357 distinct criteria the
     * clitarget corpus has actually banked, this change removed exactly ONE exercise (125 → 124).
     * Eight others pair a well-formed fixture with a non-zero status quite legitimately — an absent
     * COLUMN in a perfectly good file — and every one of them still synthesizes. A guard that also
     * silenced those would have traded one false refusal for eight lost checks.
     */
    @Test
    void aSingleOutcomeCriterionNamingBothShapesStillSynthesizes() {
        var e = synthWithFormat("column <file> <name> exits 4 when the named column is not present in the "
                + "header, printing \"no such column: <name>\" to stderr");
        assertThat(e).isPresent();
        assertThat(e.get().expectedExitStatus()).isEqualTo(4);
    }

    /** "one of the three above" is not a status, so a bare number without a prior mention is ignored. */
    @Test
    void aBareNumberWithNoStatusMentionIsNotAStatus() {
        assertThat(CommandExerciseSynthesizer.statusesIn(
                "the command word is not one of the three above")).isEmpty();
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
    /**
     * Field case, clitarget run 2026-08-29_23-08-29. The criterion names `column`, whose arity is
     * 2, so the arity-1 filter discarded it and the unnamed fallback substituted `check` — a
     * different command with a different contract. The tool answered exit 1, correct for `check`
     * on an absent path, and the gate recorded it as the `column` criterion failing, refusing
     * closure 13 times against an artifact that scored 19/19 and meets this criterion exactly.
     */
    @Test
    void aNamedCommandOfTheWrongArityAbstainsRatherThanSubstitutingAnother() {
        var e = synth("column on a non-existent column name prints \"no such column: <name>\" "
                + "to stderr and exits 4");

        assertThat(e).isEmpty();
    }

    /** The fallback still serves the case it was written for: a criterion naming no command. */
    @Test
    void anUnnamedAbsentPathCriterionStillUsesTheFallback() {
        var e = synth("Running with a non-existent file path prints \"no such file: <path>\" "
                + "to stderr and exits 1");

        assertThat(e).isPresent();
        assertThat(e.get().fixture()).isEqualTo(CommandExerciseSynthesizer.Fixture.ABSENT_PATH);
        assertThat(e.get().args().get(0)).isEqualTo("check");
    }

}
