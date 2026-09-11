package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.analysis.structure.CommandSurfaceQuery;
import com.walpro.autobuilder.analysis.structure.InputFormatExample;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-DD: turns a criterion that names a DECLARED OPERATION into something runnable, for the
 * criteria that state a requirement about the surface rather than about the input's content.
 *
 * <p>The gap, measured on run {@code 2026-08-26_11-02-00}: the loop authored 30 criteria and none
 * named a full invocation. They name an operation and a condition — <i>"Running a known command
 * with the wrong number of arguments exits 5"</i> — which {@link CommandCriterionParser} declines
 * because it reads literal invocations only. The declared surface supplies what the criterion
 * leaves implicit: which words are operations, and how many arguments each takes.
 *
 * <p><b>⚠ WHAT THIS DELIBERATELY DOES NOT DO, and the measurement that decided it.</b> Six of that
 * run's eleven criteria are about the CONTENT of an input file — <i>"check exits 0 when every row
 * is well formed"</i> — and synthesising such a file needs the input FORMAT, not just the surface.
 * The tempting shortcut is to repeat a sample line the specification already gives. It was tried
 * against the defective artifact that run produced and it PASSES: a fixture whose header and data
 * row are the same line miscounts both identically, so the disagreement cancels. **A fixture is
 * only sensitive when the property under test differs between the header and the rows** — which
 * requires reading the format. Shipping the shortcut would have added coverage with no power to
 * detect, which is the failure this whole seam exists to remove. Those six abstain until format
 * synthesis exists.
 *
 * <p>Everything here is abstain-by-default: an unmatched criterion, an undeclared surface, or a
 * condition needing content all yield empty, and empty means "no opinion", never "unmet".
 */
public final class CommandExerciseSynthesizer {

    private CommandExerciseSynthesizer() {
    }

    /** A fixture the exercise needs on disk before it runs. */
    public enum Fixture {
        /** No file argument is involved. */
        NONE,
        /** A path that must NOT exist. */
        ABSENT_PATH,
        /** A file of zero bytes. */
        EMPTY_FILE,
        /** A file every row of which is well formed, built so a careless reader disagrees with it. */
        WELL_FORMED_FILE,
        /** A file carrying one row that is definitely not well formed. */
        MALFORMED_FILE
    }

    /** Which name to ask an operation for, when the criterion is about a named part of the input. */
    public enum ColumnChoice {
        /** A name the built fixture's header really carries. */
        PRESENT,
        /** A name it definitely does not. */
        ABSENT,
        /**
         * T4-DT: the name of the column holding the part that CONTAINS the separator — the one a
         * careless reader merges with its neighbour. Any other column would be printed correctly
         * by naive splitting too, so asserting one would add coverage with no power to detect.
         */
        DISCRIMINATING
    }

    /**
     * One runnable exercise. {@code args} carries a single {@code null} where the fixture's path
     * belongs, so the caller — which owns the temporary directory — substitutes the real path it
     * created and nothing here has to invent one.
     */
    public record Exercise(String criterionText, List<String> args, Fixture fixture,
                           Optional<ColumnChoice> column,
                           int expectedExitStatus, Optional<String> expectedMessage,
                           Optional<String> expectedStdout) {

        /** Every exercise whose verdict is a status, and optionally a message it must have said. */
        public Exercise(String criterionText, List<String> args, Fixture fixture,
                        Optional<ColumnChoice> column, int expectedExitStatus,
                        Optional<String> expectedMessage) {
            this(criterionText, args, fixture, column, expectedExitStatus, expectedMessage,
                    Optional.empty());
        }
        /**
         * T4-DT: the contradiction between what the specification says this exercise should print
         * and what the tool printed, or empty when there is none.
         *
         * <p>It lives on the exercise because the exercise is what carries the expectation, and it
         * is a named function rather than inline gate logic so a frozen scenario can replay it: the
         * artifacts of runs {@code 2026-09-10_21-46-44} and {@code 2026-09-10_12-33-26} are kept
         * under {@code src/test/resources/scenarios/} with the observation each produces for the
         * very fixture the gate stages. Untestable inline logic is how the T6-F defect survived.
         *
         * <p><b>⚠ EQUALITY, NOT CONTAINMENT, and that is the whole detecting power.</b> The broken
         * artifact printed {@code b,c,de} where the specification states {@code b,c}, and
         * {@code "b,c,de".contains("b,c")} is TRUE — so the containment rule the gate uses for a
         * quoted MESSAGE would call that artifact satisfied, exactly as the source-reading verifier
         * did. The two assertions look alike and must not be made alike.
         */
        public Optional<String> contradictedBy(String output) {
            if (expectedStdout.isEmpty() || output == null) return Optional.empty();
            String printed = output.strip();
            if (printed.equals(expectedStdout.get())) return Optional.empty();
            return Optional.of("\"" + criterionText + "\" — " + describe()
                    + ", but it printed \"" + printed + "\" where the specification "
                    + "states \"" + expectedStdout.get() + "\"");
        }

        public String describe() {
            List<String> shown = new ArrayList<>();
            for (String a : args) shown.add(a == null ? "<" + fixture.name().toLowerCase() + ">" : a);
            column.ifPresent(c -> shown.add("<" + c.name().toLowerCase() + "-name>"));
            return String.join(" ", shown) + " → exit " + expectedExitStatus
                    + expectedMessage.map(m -> " saying \"" + m + "\"").orElse("");
        }
    }

    private static final Pattern EXIT_STATUS = Pattern.compile(
            "\\bexit(?:s|ed|ing)?\\s+(?:with\\s+)?(?:status\\s+|code\\s+)?(?:is\\s+)?(\\d{1,3})\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DISJUNCTION = Pattern.compile(
            "\\bexit\\w*\\s+(?:with\\s+)?(?:status\\s+|code\\s+)?(?:is\\s+)?\\d{1,3}\\s+or\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATION = Pattern.compile("\\bnever\\b", Pattern.CASE_INSENSITIVE);

    /** The message a criterion quotes, with its placeholder left in place for grounding. */
    private static final Pattern QUOTED_MESSAGE = Pattern.compile("\"([^\"]*<[^>]+>[^\"]*)\"");

    /** Conditions this can build WITHOUT knowing the input's format. */
    private static final Pattern UNRECOGNISED_OPERATION = Pattern.compile(
            "\\bunrecogni[sz]ed|\\bunknown\\s+command|\\bnot\\s+one\\s+of\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WRONG_ARITY = Pattern.compile(
            "\\bwrong\\s+number\\s+of\\s+arguments?|\\bincorrect\\s+number\\s+of\\s+arguments?"
                    + "|\\btoo\\s+(?:many|few)\\s+arguments?", Pattern.CASE_INSENSITIVE);
    private static final Pattern ABSENT_FILE = Pattern.compile(
            "\\b(?:non-?existent|does\\s+not\\s+exist|missing)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMPTY_FILE = Pattern.compile(
            "\\bempty\\b|\\bzero-?\\s?byte\\b", Pattern.CASE_INSENSITIVE);

    /** Conditions about the CONTENT of an input, which need the format and therefore the example. */
    private static final Pattern WELL_FORMED = Pattern.compile(
            "\\bevery\\s+\\w+\\s+is\\s+well[-\\s]formed|\\ball\\s+\\w+\\s+are\\s+well[-\\s]formed"
                    + "|\\bwell[-\\s]formed\\b(?!.*\\bmalformed\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MALFORMED = Pattern.compile("\\bmalformed\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ABSENT_NAME = Pattern.compile(
            "\\bnot\\s+(?:present\\s+)?in\\s+the\\s+\\w+|\\bnot\\s+present\\b|\\bunknown\\s+\\w+\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SUCCEEDS = Pattern.compile(
            "\\bon\\s+success\\b|\\bsucceeds?\\b|\\bwhen\\s+the\\s+\\w+\\s+exists\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * A claim about what the tool should PRINT for the specification's own worked example.
     *
     * <p>The expected value comes from the SPECIFICATION, never from the criterion: the seed states
     * "the line {@code a,"b,c","d""e"} has THREE fields: a / b,c / d"e", so a correct reader asked
     * for the second column of a file whose single row is that sample must print {@code b,c}. The
     * artifact of run {@code 2026-09-10_21-46-44} prints {@code b,c,de} -- it merges the second and
     * third fields and drops the escaped quote -- while the source-reading verifier called the
     * same claim satisfied.
     *
     * <p><b>Abstains unless everything is pinned.</b> The specification must spell the parts out,
     * one of them must contain the separator (any other column a careless reader also gets right,
     * so asserting it detects nothing), the criterion must be about the parse, and exactly one
     * two-argument operation must be available to print a column. A criterion stating a NON-ZERO
     * exit status is left to the status path, since it is asserting a failure rather than a value.
     */
    private static Optional<Exercise> valueExercise(String criterion,
                                                    Map<String, CommandSurfaceQuery.Command> surface,
                                                    Optional<InputFormatExample.Example> format,
                                                    java.util.Set<Integer> statedCodes) {
        if (format.isEmpty() || !PARSE_RESULT.matcher(criterion).find()) return Optional.empty();
        if (statedCodes.size() == 1 && statedCodes.iterator().next() != 0) return Optional.empty();
        InputFormatExample.Example example = format.get();
        OptionalInt discriminating = example.discriminatingPartIndex();
        if (discriminating.isEmpty()) return Optional.empty();

        // The named operation when the criterion names one, else the sole two-argument operation.
        // The unnamed fallback is legitimate here for the reason T4-DK left it in place: it applies
        // only when NOTHING is named, and it declines when the surface offers a choice.
        List<CommandSurfaceQuery.Command> binaries = surface.values().stream()
                .filter(c -> c.arity() == 2).toList();
        Optional<CommandSurfaceQuery.Command> op = surface.keySet().stream()
                .filter(w -> Pattern.compile("(?i)(?:^|[^a-z0-9_-])" + Pattern.quote(w) + "(?:[^a-z0-9_-]|$)")
                        .matcher(criterion).find())
                .map(surface::get)
                .filter(c -> c.arity() == 2)
                .findFirst()
                .or(() -> binaries.size() == 1 ? Optional.of(binaries.get(0)) : Optional.empty());
        if (op.isEmpty()) return Optional.empty();

        List<String> args = new ArrayList<>();
        args.add(op.get().word());
        args.add(null);
        return Optional.of(new Exercise(criterion.strip(), args, Fixture.WELL_FORMED_FILE,
                Optional.of(ColumnChoice.DISCRIMINATING), 0, Optional.empty(),
                Optional.of(example.parts().get(discriminating.getAsInt()))));
    }

    /** A claim about how an input PARSES, rather than about a status the tool exits with. */
    private static final Pattern PARSE_RESULT = Pattern.compile(
            "\\bparses?\\s+(?:in)?to\\b|\\bis\\s+parsed\\b|\\bfields?\\s*:"
                    + "|\\bevery\\s+value\\b|\\bvalues?\\s+of\\s+the\\b|\\bquoting\\s+removed\\b",
            Pattern.CASE_INSENSITIVE);

    private static Exercise fileExercise(String criterion, CommandSurfaceQuery.Command op,
                                         Fixture fixture, Optional<ColumnChoice> column,
                                         int expected, Optional<String> message) {
        List<String> args = new ArrayList<>();
        args.add(op.word());
        args.add(null);   // the caller substitutes the staged fixture's path
        return new Exercise(criterion.strip(), args, fixture, column, expected, message);
    }

    /**
     * The exercise this criterion licenses, or empty when it names none this can build.
     *
     * @param surface the operations the specification declares; an empty surface yields empty,
     *                so a specification without a command surface gets no opinion at all.
     */
    public static Optional<Exercise> synthesize(String criterion,
                                                Map<String, CommandSurfaceQuery.Command> surface,
                                                Optional<String> unrecognisedWord) {
        return synthesize(criterion, surface, unrecognisedWord, Optional.empty());
    }

    /**
     * @param format the specification's own worked example, when it works one. Absent means no
     *               fixture about an input's CONTENT can be built, and those criteria abstain —
     *               which is the state everything shipped before this parameter existed.
     */
    public static Optional<Exercise> synthesize(String criterion,
                                                Map<String, CommandSurfaceQuery.Command> surface,
                                                Optional<String> unrecognisedWord,
                                                Optional<InputFormatExample.Example> format) {
        if (criterion == null || criterion.isBlank() || surface == null || surface.isEmpty()) {
            return Optional.empty();
        }
        if (DISJUNCTION.matcher(criterion).find() || NEGATION.matcher(criterion).find()) {
            return Optional.empty();
        }
        java.util.Set<Integer> codes = new java.util.LinkedHashSet<>();
        Matcher sm = EXIT_STATUS.matcher(criterion);
        while (sm.find()) codes.add(Integer.parseInt(sm.group(1)));

        // T4-DT: a claim about the PARSE is judged before the exit-status path, because everything
        // below assumes the verdict IS a status and returns empty when the criterion states none.
        // "A file containing a,"b,c","d""e" is parsed into one row with three fields: a, b,c, d"e"
        // states no status at all, so it fell out here and the only judge left was a model reading
        // the parser -- which read it and answered wrong, twice in twelve days.
        Optional<Exercise> value = valueExercise(criterion, surface, format, codes);
        if (value.isPresent()) return value;

        if (codes.size() != 1) return Optional.empty();
        int expected = codes.iterator().next();

        Optional<String> message = Optional.empty();
        Matcher qm = QUOTED_MESSAGE.matcher(criterion);
        if (qm.find()) message = Optional.of(qm.group(1));

        // An operation the criterion names explicitly, if any — the leading word of a criterion
        // like "check on an empty (zero-byte) file exits 2".
        Optional<String> named = surface.keySet().stream()
                .filter(w -> Pattern.compile("(?i)(?:^|[^a-z0-9_-])" + Pattern.quote(w) + "(?:[^a-z0-9_-]|$)")
                        .matcher(criterion).find())
                .findFirst();

        if (UNRECOGNISED_OPERATION.matcher(criterion).find() && unrecognisedWord.isPresent()) {
            return Optional.of(new Exercise(criterion.strip(), List.of(unrecognisedWord.get()),
                    Fixture.NONE, Optional.empty(), expected, message));
        }
        if (WRONG_ARITY.matcher(criterion).find()) {
            // An operation taking arguments, invoked with none — the smallest unambiguous way to
            // give the wrong number, and it needs no fixture because it must fail before reading.
            Optional<CommandSurfaceQuery.Command> op = named.map(surface::get)
                    .filter(c -> c.arity() > 0)
                    .or(() -> surface.values().stream().filter(c -> c.arity() > 0).findFirst());
            if (op.isEmpty()) return Optional.empty();
            return Optional.of(new Exercise(criterion.strip(), List.of(op.get().word()),
                    Fixture.NONE, Optional.empty(), expected, message));
        }
        if (ABSENT_FILE.matcher(criterion).find()) {
            // When the criterion NAMES a command, that command is the exercise or there is no
            // exercise. The unnamed fallback — first command of the right arity — exists for
            // criteria that name none ("Running with a non-existent file path …"), and it used to
            // fire here too whenever the named command merely had the wrong arity. Measured on
            // clitarget run 2026-08-29_23-08-29: the criterion "column on a non-existent column
            // name … exits 4" names `column`, whose arity is 2, so the filter discarded it and the
            // fallback substituted `check` — a different command with a different contract. The
            // tool answered exit 1 (correct for `check` on an absent path), the gate recorded it
            // as the `column` criterion failing, and closure was refused 13 times against an
            // artifact that scored 19/19 and answers this criterion exactly as specified.
            // Substituting a command the requirement did not name cannot test that requirement,
            // so a named-but-unfitting command abstains instead.
            Optional<CommandSurfaceQuery.Command> op = named.isPresent()
                    ? named.map(surface::get).filter(c -> c.arity() == 1)
                    : surface.values().stream().filter(c -> c.arity() == 1).findFirst();
            if (op.isEmpty()) return Optional.empty();
            List<String> args = new ArrayList<>();
            args.add(op.get().word());
            args.add(null); // the caller substitutes the absent path
            return Optional.of(new Exercise(criterion.strip(), args, Fixture.ABSENT_PATH, Optional.empty(), expected, message));
        }
        if (EMPTY_FILE.matcher(criterion).find()) {
            Optional<CommandSurfaceQuery.Command> op = named.map(surface::get).filter(c -> c.arity() == 1);
            if (op.isEmpty()) return Optional.empty();
            List<String> args = new ArrayList<>();
            args.add(op.get().word());
            args.add(null);
            return Optional.of(new Exercise(criterion.strip(), args, Fixture.EMPTY_FILE, Optional.empty(), expected, message));
        }
        // Criteria about the CONTENT of an input. Buildable only where the specification worked
        // an example, because that is what pins the format; see the class note for why repeating
        // a sample instead was measured to detect nothing.
        if (format.isPresent()) {
            Optional<CommandSurfaceQuery.Command> unary = named.map(surface::get).filter(c -> c.arity() == 1);
            if (WELL_FORMED.matcher(criterion).find() && unary.isPresent()) {
                return Optional.of(fileExercise(criterion, unary.get(), Fixture.WELL_FORMED_FILE,
                        Optional.empty(), expected, message));
            }
            if (MALFORMED.matcher(criterion).find() && unary.isPresent()) {
                return Optional.of(fileExercise(criterion, unary.get(), Fixture.MALFORMED_FILE,
                        Optional.empty(), expected, message));
            }
            Optional<CommandSurfaceQuery.Command> binary = named.map(surface::get).filter(c -> c.arity() == 2);
            if (binary.isPresent()) {
                if (ABSENT_NAME.matcher(criterion).find()) {
                    return Optional.of(fileExercise(criterion, binary.get(), Fixture.WELL_FORMED_FILE,
                            Optional.of(ColumnChoice.ABSENT), expected, message));
                }
                if (SUCCEEDS.matcher(criterion).find()) {
                    return Optional.of(fileExercise(criterion, binary.get(), Fixture.WELL_FORMED_FILE,
                            Optional.of(ColumnChoice.PRESENT), expected, message));
                }
            }
        }
        return Optional.empty();
    }
}
