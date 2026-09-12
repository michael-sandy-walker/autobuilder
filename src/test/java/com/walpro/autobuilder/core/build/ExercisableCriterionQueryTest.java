package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.analysis.structure.CommandSurfaceQuery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-DK: the closure edge must be able to form an opinion on a target that does not serve HTTP.
 *
 * <p>The criteria and the specification below are verbatim from run {@code 2026-08-30_00-06-00} —
 * the false completion on the delimited-text command-line target: root COMPLETED, {@code
 * smoke=17/19}, failing the two yardstick checks covering the requirement the seed states twice.
 * Throughout that run {@code T4-CU} logged <i>"none of its 4 criterion(s) can be executed"</i>,
 * every time, because the selection asked a question only an artifact that serves can answer.
 *
 * <p><b>⚠ WHAT THIS DOES AND DOES NOT RECOVER — pinned here so no one reads the routing fix as
 * closing the run it was found in.</b> Widening the selection lets the verifier probe this
 * target's SURFACE criteria, where it previously abstained at every closure and bought nothing.
 * It does NOT recover the four criteria that actually failed: those are claims about an input
 * file's CONTENT, and {@link CommandExerciseSynthesizer} abstains on them deliberately and with a
 * measurement behind it — a fixture is only sensitive when the property under test differs
 * between the header and the rows. That abstention is unchanged and is asserted below, so the
 * remaining gap stays visible rather than being buried under a green suite.
 */
class ExercisableCriterionQueryTest {

    private final ExercisableCriterionQuery query =
            new ExercisableCriterionQuery(new CommandSurfaceQuery());

    /** Verbatim: the four criteria the gate refused closure on at 00:15:12 and then let through. */
    private static final List<String> CONTENT_CRITERIA = List.of(
            "A file containing a quoted field with an embedded newline is parsed as a single logical row",
            "The reader correctly tracks the physical line number where each logical row starts",
            "check <file> prints \"line <n>: expected <h> fields, found <f>\" for each malformed row, "
                    + "where <n> is the physical line number of the start of that logical row",
            "count <file> prints the number of logical data rows (excluding header) as a bare integer to stdout");

    /** Verbatim from the same run's criteria — claims about the surface rather than the content. */
    private static final List<String> SURFACE_CRITERIA = List.of(
            "Running a known command with the wrong number of arguments exits 5",
            "Running with an unrecognised command word prints \"unknown command: <word>\" to stderr and exits 3");

    /** Verbatim from {@code data/clitarget/bootstrap/seed.txt}, including its worked example. */
    private static final String CLI_SPEC = """
              Evolve the Java 21 application into a COMMAND-LINE tool that validates and summarises delimited
              text files. It is not a service: it has no network surface, no HTTP, and no long-running process.

              Invocation: java -jar app.jar <command> [arguments]

              FIELD SYNTAX. Fields are separated by commas. A field MAY be wrapped in double quotes, and a
              quoted field may contain commas and newlines, which are then part of the value and NOT
              separators. Inside a quoted field, two consecutive double quotes mean one literal double quote.
              Quotes are removed from the value. So the line  a,"b,c","d""e"  has THREE fields: a / b,c / d"e.

              Commands:

              check <file> — read the file and report malformed rows. A row is malformed when its field count
              differs from the header's.

              count <file> — print the number of data rows (excluding the header) to stdout as a bare integer.
              A row spanning several physical lines inside a quoted field counts as ONE row. Exit 0.

              column <file> <name> — print every value of the named column, one per line, in file order, with
              quoting removed. Exit 0. Exit 4 when the named column is not present in the header, printing
              "no such column: <name>" to stderr.

              Common rules: exit 1 when the file argument names a path that does not exist, printing
              "no such file: <path>" to stderr. Exit 3 when the command word is not one of the three above,
              printing "unknown command: <word>" to stderr. Exit 5 when the wrong number of arguments is
              given for a known command.
            """;

    @Test
    void theOldHttpOnlyRuleCouldFormNoOpinionOnThisTargetAtAll() {
        // Not hypothetical: this is the predicate the closure verifier used. Every criterion the
        // run declared failed it, which is why a model reading source was the only evidence left.
        assertThat(CONTENT_CRITERIA)
                .allSatisfy(c -> assertThat(BehaviouralCriterionParser.exercisable(c)).isEmpty());
        assertThat(SURFACE_CRITERIA)
                .allSatisfy(c -> assertThat(BehaviouralCriterionParser.exercisable(c)).isEmpty());
    }

    @Test
    void theSpecAwareQuerySelectsTheSurfaceCriteriaTheOldRuleDropped() {
        // The behaviour change, stated as the input that now yields a different output.
        assertThat(query.exercisable(SURFACE_CRITERIA, CLI_SPEC))
                .containsExactlyElementsOf(SURFACE_CRITERIA);
    }

    /**
     * T4-DT INVERTED THIS. It was {@code theContentCriteriaStillAbstainAndThatIsTheRemainingGap},
     * asserting none of the four could be probed and naming format synthesis as what would close
     * it. The ticket named this test as its own acceptance criterion, so it flips here rather than
     * being deleted: a claim about a PARSE is now exercised against the specification's own worked
     * example.
     */
    @Test
    void aClaimAboutTheParseIsNowProbedAgainstTheSpecificationsWorkedExample() {
        String parseClaim = CONTENT_CRITERIA.get(0);   // "…is parsed as a single logical row"
        assertThat(query.exercisable(List.of(parseClaim), CLI_SPEC)).containsExactly(parseClaim);
    }

    /**
     * T4-DU INVERTED THIS FURTHER, hours after T4-DT inverted the test above it, and the reason the
     * old comment gave is the one that expired. It read: <em>"the check-prints-a-message criterion
     * names a ONE-argument operation, so there is no column to read a value from"</em>. True, and
     * beside the point — the message names the physical line its bad row sits on, and the fixture
     * knows that number because it wrote the file. No column is needed.
     *
     * <p>So the residual is now TWO criteria, not three, and both remaining ones abstain for the
     * same stated reason: they assert a parse the specification never wrote out.
     */
    @Test
    void aQuotedMessageCriterionIsNowProbedToo() {
        String message = CONTENT_CRITERIA.stream()
                .filter(c -> c.contains("line <n>"))
                .findFirst().orElseThrow();
        assertThat(query.exercisable(List.of(message), CLI_SPEC)).containsExactly(message);
    }

    @Test
    void theOtherContentCriteriaStillAbstainAndEachHasItsOwnReason() {
        // What is LEFT after T4-DT and T4-DU: "The reader correctly tracks the physical line
        // number" and "count <file> prints the number of logical data rows" assert no parse the
        // specification ever wrote out, so there is nothing for a fixture to be built from. That is
        // a real residual and not a want of either capability — it is the ceiling
        // [[project-yardstick-must-not-outrun-the-spec]] describes, and no probe closes it.
        List<String> stillUnprobeable = CONTENT_CRITERIA.stream()
                .filter(c -> !c.equals(CONTENT_CRITERIA.get(0)) && !c.contains("line <n>"))
                .toList();
        assertThat(stillUnprobeable).hasSize(2);
        assertThat(query.exercisable(stillUnprobeable, CLI_SPEC)).isEmpty();
    }

    /**
     * The invariant `CriteriaExecutabilityReadout` depends on: addressing the surface is a SUPERSET
     * of being exercisable, or its cause decomposition reports a negative shortfall. The readout's
     * own javadoc records that near-miss, which is why the question moved here rather than being
     * re-derived there from an HTTP regex.
     */
    @Test
    void addressingTheSurfaceIsASupersetOfBeingExercisable() {
        List<String> all = new java.util.ArrayList<>(SURFACE_CRITERIA);
        all.addAll(CONTENT_CRITERIA);
        assertThat(query.addressingSurface(all, CLI_SPEC))
                .containsAll(query.exercisable(all, CLI_SPEC));
    }

    /**
     * ⚠ The behaviour change this move carries, stated as a measurement rather than left implicit.
     * The readout's old predicate was {@code exercisable || \b(GET|POST|…)\s+/}, which on a COMMAND
     * target can never add anything — so {@code namedButUnparsed} was structurally 0 there and the
     * readout was blind to criteria that address the surface but cannot be probed. Over the 357
     * criteria the clitarget corpus has banked the count goes 130 to 260, and that bucket becomes
     * reportable. Both consumers are readouts, not gates, so nothing acts on the number.
     */
    @Test
    void aCommandCriterionAddressesTheSurfaceEvenWhenItCannotBeExercised() {
        String namesTheCommandButStatesNothingProbeable =
                "count is the command used to summarise a file for the reader";
        assertThat(query.exercisable(List.of(namesTheCommandButStatesNothingProbeable), CLI_SPEC))
                .isEmpty();
        assertThat(query.addressingSurface(List.of(namesTheCommandButStatesNothingProbeable), CLI_SPEC))
                .containsExactly(namesTheCommandButStatesNothingProbeable);
    }

    @Test
    void withoutTheSpecificationTheCommandHalfStillCannotAnswer() {
        // The second half of the same defect: the verifier handed the gate an empty string, so a
        // criterion naming a declared operation had no surface to be grounded against even when
        // one existed. Both halves had to move for either to matter.
        assertThat(query.exercisable(SURFACE_CRITERIA, "")).isEmpty();
        assertThat(query.exercisable(SURFACE_CRITERIA, null)).isEmpty();
    }

    @Test
    void anHttpCriterionIsStillSelectedWithNoSpecificationAtAll() {
        // The serving path never needed the specification and still does not — widening the
        // question must not narrow the case that already worked.
        String http = "POST /books with title longer than 120 characters returns HTTP 400";
        assertThat(query.exercisable(List.of(http), null)).containsExactly(http);
    }

    @Test
    void aStructuralCriterionIsSelectedByNeitherHalf() {
        // Two thirds of criteria are claims about structure. Widening the question must not turn
        // those into probes: that is what buys a package build and a boot to observe nothing.
        String structural = "The Book model declares validation annotations for title and isbn";
        assertThat(query.exercisable(List.of(structural), CLI_SPEC)).isEmpty();
    }

    @Test
    void nullAndEmptyInputsYieldNoSelection() {
        assertThat(query.exercisable(null, CLI_SPEC)).isEmpty();
        assertThat(query.exercisable(List.of(), CLI_SPEC)).isEmpty();
    }
}
