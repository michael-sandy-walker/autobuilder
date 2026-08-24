package com.walpro.autobuilder.core.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * T4-CB: synthesizes candidate JSON request bodies for exercising a write endpoint, derived from
 * the target artifact's OWN model sources — discovery, never domain knowledge baked into the
 * framework. The last static path segment is matched (case-insensitively, with naive plural
 * stripping) against class file names under the artifact's main sources; the match's
 * {@code private} fields become JSON properties with type-appropriate placeholder values.
 *
 * <p>The consumer's assertion is only "no 5xx / no connection death", so an imperfect body is
 * harmless: a validation 400 on a placeholder value proves the endpoint is alive and handled,
 * which is all the gate claims. {@code {}} is always the last candidate — it exercises binding
 * and the validation surface even when no model class is derivable.
 */
public final class RequestBodySynthesizer {

    private static final Pattern PRIVATE_FIELD =
            Pattern.compile("private\\s+(?:final\\s+)?([A-Za-z0-9_.]+)(?:<[^>]*>)?\\s+([a-zA-Z0-9_]+)\\s*[;=]");

    private RequestBodySynthesizer() {
    }

    /**
     * Candidate bodies for a POST to {@code path}, most specific first, {@code {}} always last.
     * Never returns an empty list.
     */
    public static List<String> candidateBodies(String path, Path artifactRoot) {
        List<String> candidates = new ArrayList<>();
        try {
            lastStaticSegment(path)
                    .flatMap(segment -> findModelSource(segment, artifactRoot))
                    .flatMap(source -> synthesizeFromSource(source, Map.of()))
                    .ifPresent(candidates::add);
        } catch (RuntimeException e) {
            // synthesis is best-effort; the {} fallback below always stands
        }
        candidates.add("{}");
        return candidates;
    }

    /**
     * T4-DB: a body whose every DISCOVERED constraint is satisfied, or empty when one of them
     * cannot be.
     *
     * <p>Distinct from {@link #candidateBodies} in what it promises. That method is best-effort
     * and its consumer only asks "no 5xx", so a placeholder that draws a validation 400 still
     * answers the question. A success probe asks the opposite question — did the application
     * ACCEPT this — and a body the application was always going to refuse cannot answer it. So
     * this method declines rather than degrade: no model source, no usable field, or a constraint
     * {@link FieldConstraintDiscovery#satisfyingValue} will not commit to, and the caller gets
     * nothing and abstains.
     *
     * <p>It does not promise the application will accept the result — undiscovered validation is
     * always possible, which is why the caller must still treat a refusal as an abstention rather
     * than a contradiction.
     */
    public static Optional<String> acceptableBody(String path, Path artifactRoot) {
        Optional<Path> model = lastStaticSegment(path).flatMap(segment -> findModelSource(segment, artifactRoot));
        if (model.isEmpty()) return Optional.empty();
        Map<String, FieldConstraintDiscovery.Constraint> constraints =
                FieldConstraintDiscovery.discover(artifactRoot);
        try {
            return synthesizeFromSource(model.get(), constraints);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The last path segment that is not a normalized path variable ({@code {}}). */
    private static Optional<String> lastStaticSegment(String path) {
        if (path == null) return Optional.empty();
        String[] segments = path.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            String s = segments[i].trim();
            if (!s.isEmpty() && !s.contains("{")) return Optional.of(s);
        }
        return Optional.empty();
    }

    /**
     * A source file whose basename matches the segment or its naive singular forms
     * (drop {@code -s} / {@code -es}). Prefers {@code src/main/java}; skips test sources.
     */
    private static Optional<Path> findModelSource(String segment, Path artifactRoot) {
        if (artifactRoot == null || !Files.isDirectory(artifactRoot)) return Optional.empty();
        Path mainSources = artifactRoot.resolve("src/main/java");
        Path scanRoot = Files.isDirectory(mainSources) ? mainSources : artifactRoot;
        String lower = segment.toLowerCase(Locale.ROOT);
        List<String> nameCandidates = new ArrayList<>(List.of(lower));
        if (lower.endsWith("es")) nameCandidates.add(lower.substring(0, lower.length() - 2));
        if (lower.endsWith("s")) nameCandidates.add(lower.substring(0, lower.length() - 1));
        try (Stream<Path> files = Files.walk(scanRoot)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        if (!name.endsWith(".java")) return false;
                        String base = name.substring(0, name.length() - 5).toLowerCase(Locale.ROOT);
                        return nameCandidates.contains(base);
                    })
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * The model's fields as a JSON object, with {@code constraints} applied to the values.
     *
     * <p>With an empty map this is the original placeholder behaviour. With constraints, a field
     * the artifact restricts gets a conforming value — and a restriction that cannot be met fails
     * the WHOLE body, because a body carrying one value the application rejects is not a body that
     * can create anything, however correct its other fields are.
     */
    private static Optional<String> synthesizeFromSource(Path source,
                                                         Map<String, FieldConstraintDiscovery.Constraint> constraints) {
        String text;
        try {
            text = Files.readString(source);
        } catch (IOException e) {
            return Optional.empty();
        }
        StringBuilder json = new StringBuilder("{");
        Matcher m = PRIVATE_FIELD.matcher(text);
        boolean first = true;
        while (m.find()) {
            String field = m.group(2);
            String value = placeholderFor(m.group(1));
            if (value == null) continue; // unknown/nested type — omit rather than guess
            if ("serialVersionUID".equals(field)) continue;
            FieldConstraintDiscovery.Constraint constraint = constraints.get(field);
            if (constraint != null) {
                // Only a textual value can be shaped to a pattern or a length; a constrained
                // non-textual field is a shape this class does not model, so it declines.
                if (!value.startsWith("\"")) return Optional.empty();
                Optional<String> satisfying = FieldConstraintDiscovery.satisfyingValue(
                        constraint, value.substring(1, value.length() - 1));
                if (satisfying.isEmpty()) return Optional.empty();
                value = quote(satisfying.get());
            }
            if (!first) json.append(",");
            json.append('"').append(field).append("\":").append(value);
            first = false;
        }
        if (first) return Optional.empty(); // no usable fields — the {} fallback covers it
        return Optional.of(json.append("}").toString());
    }

    /** JSON string literal for a generated value; the alphabet is narrow but not assumed. */
    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** JSON placeholder per Java type; null = omit the field (never guess at nested objects). */
    private static String placeholderFor(String type) {
        String simple = type.substring(type.lastIndexOf('.') + 1);
        return switch (simple) {
            case "String", "CharSequence" -> "\"value\"";
            case "int", "Integer", "long", "Long", "short", "Short", "byte", "Byte", "BigInteger" -> "1";
            case "double", "Double", "float", "Float", "BigDecimal" -> "1.0";
            case "boolean", "Boolean" -> "true";
            case "LocalDate" -> "\"2026-01-01\"";
            case "LocalDateTime", "Instant", "Date", "OffsetDateTime", "ZonedDateTime" -> "\"2026-01-01T00:00:00Z\"";
            case "UUID" -> "\"00000000-0000-0000-0000-000000000000\"";
            default -> null;
        };
    }
}
