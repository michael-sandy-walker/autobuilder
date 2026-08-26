package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.analysis.structure.CommandSurfaceQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        EMPTY_FILE
    }

    /**
     * One runnable exercise. {@code args} carries a single {@code null} where the fixture's path
     * belongs, so the caller — which owns the temporary directory — substitutes the real path it
     * created and nothing here has to invent one.
     */
    public record Exercise(String criterionText, List<String> args, Fixture fixture,
                           int expectedExitStatus, Optional<String> expectedMessage) {
        public String describe() {
            List<String> shown = new ArrayList<>();
            for (String a : args) shown.add(a == null ? "<" + fixture.name().toLowerCase() + ">" : a);
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

    /**
     * The exercise this criterion licenses, or empty when it names none this can build.
     *
     * @param surface the operations the specification declares; an empty surface yields empty,
     *                so a specification without a command surface gets no opinion at all.
     */
    public static Optional<Exercise> synthesize(String criterion,
                                                Map<String, CommandSurfaceQuery.Command> surface,
                                                Optional<String> unrecognisedWord) {
        if (criterion == null || criterion.isBlank() || surface == null || surface.isEmpty()) {
            return Optional.empty();
        }
        if (DISJUNCTION.matcher(criterion).find() || NEGATION.matcher(criterion).find()) {
            return Optional.empty();
        }
        java.util.Set<Integer> codes = new java.util.LinkedHashSet<>();
        Matcher sm = EXIT_STATUS.matcher(criterion);
        while (sm.find()) codes.add(Integer.parseInt(sm.group(1)));
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
                    Fixture.NONE, expected, message));
        }
        if (WRONG_ARITY.matcher(criterion).find()) {
            // An operation taking arguments, invoked with none — the smallest unambiguous way to
            // give the wrong number, and it needs no fixture because it must fail before reading.
            Optional<CommandSurfaceQuery.Command> op = named.map(surface::get)
                    .filter(c -> c.arity() > 0)
                    .or(() -> surface.values().stream().filter(c -> c.arity() > 0).findFirst());
            if (op.isEmpty()) return Optional.empty();
            return Optional.of(new Exercise(criterion.strip(), List.of(op.get().word()),
                    Fixture.NONE, expected, message));
        }
        if (ABSENT_FILE.matcher(criterion).find()) {
            Optional<CommandSurfaceQuery.Command> op = named.map(surface::get)
                    .filter(c -> c.arity() == 1)
                    .or(() -> surface.values().stream().filter(c -> c.arity() == 1).findFirst());
            if (op.isEmpty()) return Optional.empty();
            List<String> args = new ArrayList<>();
            args.add(op.get().word());
            args.add(null); // the caller substitutes the absent path
            return Optional.of(new Exercise(criterion.strip(), args, Fixture.ABSENT_PATH, expected, message));
        }
        if (EMPTY_FILE.matcher(criterion).find()) {
            Optional<CommandSurfaceQuery.Command> op = named.map(surface::get).filter(c -> c.arity() == 1);
            if (op.isEmpty()) return Optional.empty();
            List<String> args = new ArrayList<>();
            args.add(op.get().word());
            args.add(null);
            return Optional.of(new Exercise(criterion.strip(), args, Fixture.EMPTY_FILE, expected, message));
        }
        // Everything else is about the CONTENT of an input — see the class note.
        return Optional.empty();
    }
}
