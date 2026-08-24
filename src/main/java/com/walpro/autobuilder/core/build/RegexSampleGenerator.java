package com.walpro.autobuilder.core.build;

import java.util.Optional;

/**
 * T4-DB: one string that matches a pattern, for the narrow subset of patterns whose sample is
 * obvious — and empty for everything else.
 *
 * <p>This exists to make a request body ACCEPTABLE, not to explore an input space, so the bar is
 * "would a specification plausibly write this to constrain a field" rather than any coverage of
 * regular expressions. The supported subset is literal characters, {@code \d}, {@code \w},
 * {@code .} and simple bracket classes, each optionally repeated by {@code {n}}, {@code {n,m}},
 * {@code ?}, {@code +} or {@code *}.
 *
 * <p><b>Everything else returns empty, and that is the point.</b> Alternation, groups,
 * back-references and negated classes all have samples, but producing one requires reasoning this
 * class deliberately does not do — and a wrong sample is worse than no sample here, because the
 * caller would send a body the application refuses and read the refusal as a defect. Abstaining
 * costs one unprobed criterion; guessing costs a false contradiction against a correct artifact.
 *
 * <p>The result is verified against the pattern before it is returned, so a defect in this class
 * yields an abstention rather than an unacceptable body.
 */
final class RegexSampleGenerator {

    /** Digits and lower-case letters only: the intersection that keeps samples boring and printable. */
    private static final char DIGIT = '0';
    private static final char LETTER = 'a';

    private RegexSampleGenerator() {
    }

    /** A string matching {@code regex}, or empty when this class will not commit to one. */
    static Optional<String> sample(String regex) {
        if (regex == null || regex.isEmpty()) return Optional.empty();
        String body = regex;
        if (body.startsWith("^")) body = body.substring(1);
        if (body.endsWith("$") && !body.endsWith("\\$")) body = body.substring(0, body.length() - 1);

        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < body.length()) {
            Atom atom = readAtom(body, i);
            if (atom == null) return Optional.empty();
            i = atom.next();
            Repetition repetition = readRepetition(body, i);
            if (repetition == null) return Optional.empty();
            i = repetition.next();
            out.append(String.valueOf(atom.character()).repeat(repetition.count()));
        }

        String candidate = out.toString();
        // A sample this class cannot verify is a sample it has no business returning.
        try {
            if (!candidate.matches(regex)) return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        return candidate.isEmpty() ? Optional.empty() : Optional.of(candidate);
    }

    /** One matchable unit and the character chosen to represent it. */
    private record Atom(char character, int next) {
    }

    private static Atom readAtom(String regex, int i) {
        char c = regex.charAt(i);
        if (c == '\\') {
            if (i + 1 >= regex.length()) return null;
            char escaped = regex.charAt(i + 1);
            return switch (escaped) {
                case 'd', 'w' -> new Atom(DIGIT, i + 2);
                case 's' -> new Atom(' ', i + 2);
                // An escaped metacharacter denotes itself, which is the easiest sample of all.
                case '.', '\\', '+', '*', '?', '(', ')', '[', ']', '{', '}', '^', '$', '|', '/', '-' ->
                        new Atom(escaped, i + 2);
                default -> null;
            };
        }
        if (c == '[') return readBracketClass(regex, i);
        if (c == '.') return new Atom(LETTER, i + 1);
        // Structural characters have samples, but not ones this class derives.
        if ("()|+*?{}$^".indexOf(c) >= 0) return null;
        return new Atom(c, i + 1);
    }

    /**
     * The first character a simple bracket class admits. Negated classes are refused: the
     * complement of a set is not a set this class enumerates.
     */
    private static Atom readBracketClass(String regex, int i) {
        int close = regex.indexOf(']', i + 1);
        if (close < 0) return null;
        String inner = regex.substring(i + 1, close);
        if (inner.isEmpty() || inner.startsWith("^")) return null;
        if (inner.contains("\\")) {
            if (inner.contains("\\d") || inner.contains("\\w")) return new Atom(DIGIT, close + 1);
            return null;
        }
        // A range contributes its lower bound; a literal set contributes its first member.
        char first = inner.charAt(0);
        if (inner.length() >= 3 && inner.charAt(1) == '-') {
            char upper = inner.charAt(2);
            if (first > upper) return null;
            return new Atom(first, close + 1);
        }
        return new Atom(first, close + 1);
    }

    /** How many times the preceding atom repeats, and where the quantifier ended. */
    private record Repetition(int count, int next) {
    }

    private static Repetition readRepetition(String regex, int i) {
        if (i >= regex.length()) return new Repetition(1, i);
        char c = regex.charAt(i);
        switch (c) {
            case '?':
                return new Repetition(0, i + 1);
            case '*':
                return new Repetition(0, i + 1);
            case '+':
                return new Repetition(1, i + 1);
            case '{':
                break;
            default:
                return new Repetition(1, i);
        }
        int close = regex.indexOf('}', i + 1);
        if (close < 0) return null;
        String inner = regex.substring(i + 1, close);
        try {
            // {n} and {n,m} both sample at their lower bound; {n,} likewise.
            int comma = inner.indexOf(',');
            String lower = comma < 0 ? inner : inner.substring(0, comma);
            int count = Integer.parseInt(lower.trim());
            if (count < 0 || count > 4096) return null;
            return new Repetition(count, close + 1);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
