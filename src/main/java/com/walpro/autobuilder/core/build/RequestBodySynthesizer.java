package com.walpro.autobuilder.core.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
                    .flatMap(RequestBodySynthesizer::synthesizeFromSource)
                    .ifPresent(candidates::add);
        } catch (RuntimeException e) {
            // synthesis is best-effort; the {} fallback below always stands
        }
        candidates.add("{}");
        return candidates;
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

    private static Optional<String> synthesizeFromSource(Path source) {
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
            String value = placeholderFor(m.group(1));
            if (value == null) continue; // unknown/nested type — omit rather than guess
            if ("serialVersionUID".equals(m.group(2))) continue;
            if (!first) json.append(",");
            json.append('"').append(m.group(2)).append("\":").append(value);
            first = false;
        }
        if (first) return Optional.empty(); // no usable fields — the {} fallback covers it
        return Optional.of(json.append("}").toString());
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
