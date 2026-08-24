package com.walpro.autobuilder.core.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * T4-DB: what a field's value has to look like for the application to accept it, discovered from
 * the artifact's OWN validation code rather than from any table of ours.
 *
 * <p>The remaining verification gap this ticket measured is not a missing probe shape — it is one
 * missing ingredient, a request body a correct application will not refuse. {@link
 * RequestBodySynthesizer} derives field NAMES and TYPES from the model and fills them with
 * placeholders; a specification whose identifier must match {@code \d{13}} refuses
 * {@code "value"}, and 53 setup requests were declined for exactly that reason in one run. This
 * class supplies the missing half: the constraints the artifact itself enforces.
 *
 * <p><b>Discovery, not knowledge.</b> Nothing here knows what an identifier or an address is. It
 * reads the conditions the artifact's own code tests before it throws, keyed by the identifier
 * being tested, and nothing else. A project that validates differently yields no constraints and
 * the caller falls back to exactly today's behaviour.
 *
 * <p><b>Abstention is the whole safety property.</b> A constraint that is discovered but cannot be
 * satisfied ({@link #satisfyingValue} empty) must make the caller abstain, not guess. Asserting a
 * success status against a body the application was always going to refuse would report a
 * contradiction the application never committed — the failure mode {@code
 * DeterministicProbeSynthesizer.succeeded} is written to avoid.
 */
public final class FieldConstraintDiscovery {

    /** {@code private static final Pattern NAME = Pattern.compile("literal")}. */
    private static final Pattern PATTERN_CONSTANT = Pattern.compile(
            "(?:static\\s+)?(?:final\\s+)?Pattern\\s+([A-Za-z0-9_]+)\\s*=\\s*Pattern\\.compile\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** {@code NAME.matcher(field).matches()} — the constant form. */
    private static final Pattern MATCHER_CALL =
            Pattern.compile("([A-Za-z0-9_]+)\\s*\\.matcher\\(\\s*([A-Za-z0-9_]+)\\s*\\)\\s*\\.matches\\(\\s*\\)");

    /** {@code field.matches("literal")} — the inline form. */
    private static final Pattern INLINE_MATCHES = Pattern.compile(
            "([A-Za-z0-9_]+)\\s*\\.matches\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\)");

    /** {@code field.length() > 120} / {@code >= 121} — the upper bound the artifact rejects above. */
    private static final Pattern LENGTH_BOUND =
            Pattern.compile("([A-Za-z0-9_]+)\\s*\\.length\\(\\s*\\)\\s*(>=?)\\s*([0-9]{1,9})");

    private FieldConstraintDiscovery() {
    }

    /**
     * What one identifier's value must satisfy. Both parts are optional and independent: a field
     * may carry a pattern, a length ceiling, both, or neither.
     *
     * @param regex     the pattern the whole value must match, already unescaped from its Java
     *                  source literal, or null
     * @param maxLength the greatest length the artifact does NOT reject, or null
     */
    public record Constraint(String regex, Integer maxLength) {

        Constraint merge(Constraint other) {
            return new Constraint(
                    regex != null ? regex : other.regex,
                    maxLength != null ? min(maxLength, other.maxLength) : other.maxLength);
        }

        private static Integer min(Integer a, Integer b) {
            return b == null ? a : Math.min(a, b);
        }
    }

    /**
     * Constraints by identifier name, discovered across the artifact's main sources.
     *
     * <p>Keyed by the identifier the condition tests, which is the model field name in the shapes
     * that occur: a service validates the value it was handed, and it is handed the field. Two
     * files constraining the same name are merged rather than one silently winning — the caller
     * has to satisfy both to be accepted by both.
     */
    public static Map<String, Constraint> discover(Path artifactRoot) {
        Map<String, Constraint> discovered = new LinkedHashMap<>();
        if (artifactRoot == null || !Files.isDirectory(artifactRoot)) return discovered;
        Path mainSources = artifactRoot.resolve("src/main/java");
        Path scanRoot = Files.isDirectory(mainSources) ? mainSources : artifactRoot;
        try (Stream<Path> files = Files.walk(scanRoot)) {
            List<Path> sources = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();
            for (Path source : sources) {
                try {
                    mergeAll(discovered, fromSource(Files.readString(source)));
                } catch (IOException | RuntimeException e) {
                    // one unreadable or pathological source must not blind the rest
                }
            }
        } catch (IOException e) {
            return discovered;
        }
        return discovered;
    }

    private static void mergeAll(Map<String, Constraint> into, Map<String, Constraint> from) {
        from.forEach((field, constraint) -> into.merge(field, constraint, Constraint::merge));
    }

    /** Constraints stated by one source file. Package-private so the shapes can be tested directly. */
    static Map<String, Constraint> fromSource(String text) {
        Map<String, Constraint> found = new LinkedHashMap<>();
        Map<String, String> patternConstants = new HashMap<>();
        Matcher constants = PATTERN_CONSTANT.matcher(text);
        while (constants.find()) {
            patternConstants.put(constants.group(1), unescapeJavaLiteral(constants.group(2)));
        }

        Matcher matcherCall = MATCHER_CALL.matcher(text);
        while (matcherCall.find()) {
            String regex = patternConstants.get(matcherCall.group(1));
            // A matcher built from something we did not see compiled tells us a pattern applies but
            // not which one. Recording it with a null regex would read as "unconstrained"; leaving
            // it out entirely is the same conclusion and does not pretend to more.
            if (regex != null) {
                found.merge(matcherCall.group(2), new Constraint(regex, null), Constraint::merge);
            }
        }

        Matcher inline = INLINE_MATCHES.matcher(text);
        while (inline.find()) {
            found.merge(inline.group(1), new Constraint(unescapeJavaLiteral(inline.group(2)), null), Constraint::merge);
        }

        Matcher length = LENGTH_BOUND.matcher(text);
        while (length.find()) {
            int bound = Integer.parseInt(length.group(3));
            // "> 120" rejects 121 and above, so 120 is still accepted; ">= 120" rejects 120 itself.
            int greatestAccepted = ">".equals(length.group(2)) ? bound : bound - 1;
            if (greatestAccepted > 0) {
                found.merge(length.group(1), new Constraint(null, greatestAccepted), Constraint::merge);
            }
        }
        return found;
    }

    /**
     * A value satisfying the constraint, or empty when it cannot be built confidently.
     *
     * <p>Empty is a real answer and the caller must honour it. The generated alphabet is
     * deliberately narrow — digits and lower-case letters — because the point is to be ACCEPTED,
     * not to explore the input space.
     *
     * @param fallback the value used when no pattern applies, trimmed to any length ceiling
     */
    public static Optional<String> satisfyingValue(Constraint constraint, String fallback) {
        if (constraint == null) return Optional.ofNullable(fallback);
        String candidate;
        if (constraint.regex() != null) {
            Optional<String> fromPattern = RegexSampleGenerator.sample(constraint.regex());
            if (fromPattern.isEmpty()) return Optional.empty(); // constrained, and we cannot comply
            candidate = fromPattern.get();
        } else {
            candidate = fallback == null ? "" : fallback;
        }
        if (constraint.maxLength() != null && candidate.length() > constraint.maxLength()) {
            // Trimming a pattern-derived value would break the pattern it was built to satisfy, and
            // there is no honest way to satisfy both without solving them together.
            if (constraint.regex() != null) return Optional.empty();
            candidate = candidate.substring(0, constraint.maxLength());
        }
        return candidate.isEmpty() ? Optional.empty() : Optional.of(candidate);
    }

    /** Turns a Java source literal into the string it denotes ({@code \\d} → {@code \d}). */
    static String unescapeJavaLiteral(String literal) {
        StringBuilder out = new StringBuilder(literal.length());
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (c != '\\' || i + 1 >= literal.length()) {
                out.append(c);
                continue;
            }
            char next = literal.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case '"' -> out.append('"');
                case '\'' -> out.append('\'');
                case '\\' -> out.append('\\');
                default -> out.append('\\').append(next);
            }
        }
        return out.toString();
    }
}
