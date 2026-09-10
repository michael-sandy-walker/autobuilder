package com.walpro.autobuilder.analysis.structure;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-DD: the worked example a specification gives for its own input format — a sample, and the
 * number of parts the specification says that sample has.
 *
 * <p><b>Why this exists, and it is the whole reason a content fixture can be built at all.</b>
 * Run {@code 2026-08-26_11-02-00} fulfilled on an artifact whose reader merged the wrong parts,
 * and the criteria that would have caught it are all about the CONTENT of an input. Building such
 * an input needs to know how the format works, and the only non-circular source is the
 * specification: asking the artifact would be grading its own homework, and repeating a sample
 * verbatim was measured to detect nothing (a header and a row that are the same line mis-parse
 * identically, so the disagreement cancels).
 *
 * <p><b>The construction, stated without naming a format.</b> A specification that bothers to work
 * an example is telling us two things at once: a sample whose parse is not obvious, and the parse
 * it should have. Those two pin the SEPARATOR without any prose being understood — it is the
 * character whose naive division of the sample comes closest to the stated count from above,
 * because the format's own escaping can only ever MERGE parts, never create them.
 *
 * <p><b>⚠ AND THE EXAMPLE MUST BE DISCRIMINATING, or this declines.</b> If naive division already
 * yields the stated count, the sample exercises nothing the format does, and a fixture built from
 * it would pass against any implementation — coverage with no power to detect, the failure this
 * seam exists to remove. Silence is the correct answer there.
 */
public final class InputFormatExample {

    private InputFormatExample() {
    }

    /**
     * A specification's worked example: the sample, how many parts it truly has, and the separator
     * derived from those two.
     */
    public record Example(String sample, int trueParts, char separator, int naiveParts,
                          List<String> parts) {

        /**
         * T4-DT: the parts the specification says the sample HAS, when it spells them out.
         *
         * <p>The count alone pins the separator, which is what builds a fixture. The parts are the
         * ORACLE — they say what a correct reader should print, and without them the only judge of
         * a parse is a model reading the parser. Twice in twelve days that model read it and
         * answered wrong: run {@code 2026-08-30_00-06-00} closed at 17/19 and
         * {@code 2026-09-10_21-46-44} at 15/19, the second having verified "a file containing
         * {@code a,"b,c","d""e"} is parsed into one row with three fields" against an artifact that
         * finds two.
         *
         * <p>Empty when the specification states a count but not the parts, which is the common
         * case and must stay silent rather than guessed.
         */
        public boolean hasParts() {
            return parts.size() == trueParts;
        }

        /**
         * The part whose value CONTAINS the separator, which is the one a careless reader gets
         * wrong — everything else it would also get right by splitting naively, so asserting it
         * would be coverage with no power to detect. Empty when no part exercises the escaping.
         */
        public OptionalInt discriminatingPartIndex() {
            if (!hasParts()) return OptionalInt.empty();
            for (int i = 0; i < parts.size(); i++) {
                if (parts.get(i).indexOf(separator) >= 0) return OptionalInt.of(i);
            }
            return OptionalInt.empty();
        }

        /** A header line of {@code trueParts} plainly-separated names, sharing no syntax with the sample. */
        public List<String> headerNames() {
            return java.util.stream.IntStream.range(0, trueParts)
                    .mapToObj(i -> "col" + (i + 1)).toList();
        }

        public String headerLine() {
            return String.join(String.valueOf(separator), headerNames());
        }
    }

    private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(
            Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
            Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8),
            Map.entry("nine", 9), Map.entry("ten", 10));

    /**
     * A worked example: a sample that is not an ordinary word — it must carry punctuation, which is
     * what distinguishes {@code a,"b,c","d""e"} from the noun in "the file has three rows" —
     * followed by an assertion of how many parts it has.
     */
    private static final Pattern WORKED_EXAMPLE = Pattern.compile(
            "(\\S*[^\\w\\s]\\S*)\\s+(?:has|contains|holds|parses\\s+(?:in)?to)\\s+([A-Za-z]+|\\d{1,2})\\s+\\w+",
            Pattern.CASE_INSENSITIVE);

    /**
     * The example this specification works for its input format, or empty when it works none, when
     * the stated count cannot be read, or when the example turns out not to be discriminating.
     */
    public static Optional<Example> readFrom(String specText) {
        if (specText == null || specText.isBlank()) return Optional.empty();
        Matcher m = WORKED_EXAMPLE.matcher(specText);
        while (m.find()) {
            String sample = m.group(1);
            Optional<Integer> count = asCount(m.group(2));
            if (count.isEmpty() || count.get() < 2) continue;
            Optional<Example> derived = derive(sample, count.get());
            if (derived.isPresent()) {
                Example e = derived.get();
                return Optional.of(new Example(e.sample(), e.trueParts(), e.separator(),
                        e.naiveParts(), statedParts(specText, m.end(), e)));
            }
        }
        return Optional.empty();
    }

    private static Optional<Integer> asCount(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        if (t.matches("\\d{1,2}")) return Optional.of(Integer.parseInt(t));
        return Optional.ofNullable(NUMBER_WORDS.get(t));
    }

    /**
     * The separator implied by a sample and its true part count: among the punctuation the sample
     * carries, the character dividing it into MORE parts than the specification says, by the
     * smallest margin. Escaping merges parts, so the true separator always over-divides naively
     * and the format's other syntax over-divides further.
     */
    private static Optional<Example> derive(String sample, int trueParts) {
        Set<Character> candidates = new LinkedHashSet<>();
        for (char c : sample.toCharArray()) {
            if (!Character.isLetterOrDigit(c) && !Character.isWhitespace(c)) candidates.add(c);
        }
        char best = 0;
        int bestParts = Integer.MAX_VALUE;
        for (char c : candidates) {
            int parts = sample.split(Pattern.quote(String.valueOf(c)), -1).length;
            if (parts > trueParts && parts < bestParts) {
                best = c;
                bestParts = parts;
            }
        }
        if (best == 0) {
            // Nothing over-divides: the sample exercises none of the format's own syntax, so a
            // fixture built from it could not tell a correct reader from a careless one.
            return Optional.empty();
        }
        return Optional.of(new Example(sample, trueParts, best, bestParts, List.of()));
    }

    /** List delimiters a specification uses to spell parts out. Never the format's own separator. */
    private static final List<String> PART_DELIMITERS = List.of(" / ", " | ", " · ");

    /**
     * T4-DT: the parts listed immediately after the stated count, or empty when they are not
     * spelled out unambiguously.
     *
     * <p><b>Conservative on purpose, because a wrong expected value WALLS the run.</b> The list has
     * to follow a colon, end at the sentence, split on a delimiter that is NOT the format's own
     * separator (otherwise "a, b,c, d" is unreadable by construction), and yield exactly as many
     * non-blank parts as the specification said. Anything else extracts nothing — having no oracle
     * is strictly better than inventing one, which is the T1-FW direction.
     */
    private static List<String> statedParts(String specText, int from, Example e) {
        String rest = specText.substring(Math.min(from, specText.length()));
        int stop = rest.length();
        for (int i = 0; i < rest.length() - 1; i++) {
            if (rest.charAt(i) == '.' && Character.isWhitespace(rest.charAt(i + 1))) { stop = i; break; }
        }
        rest = rest.substring(0, stop).strip();
        if (!rest.startsWith(":")) return List.of();
        String listed = rest.substring(1).strip();
        for (String delimiter : PART_DELIMITERS) {
            if (delimiter.indexOf(e.separator()) >= 0) continue;
            if (!listed.contains(delimiter)) continue;
            List<String> parts = new ArrayList<>();
            for (String part : listed.split(Pattern.quote(delimiter), -1)) parts.add(part.strip());
            if (parts.size() != e.trueParts()) continue;
            if (parts.stream().anyMatch(String::isBlank)) continue;
            return List.copyOf(parts);
        }
        return List.of();
    }
}
