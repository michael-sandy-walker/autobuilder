package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.analysis.structure.RequestBodyContractQuery;
import com.walpro.autobuilder.analysis.structure.RequestBodyContractQuery.AcceptedBody;
import com.walpro.autobuilder.analysis.structure.RequestBodyContractQuery.KeyedValues;
import com.walpro.autobuilder.analysis.structure.RequestBodyContractQuery.NamedType;

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
 *
 * <p><b>T1-FV: the model is where the components come from; the handler's own declaration is what
 * decides which of them survive.</b> What an artifact STORES and what its interface ACCEPTS are
 * different shapes — identifiers, timestamps and derived state exist in the entity and are refused
 * at the door. Every field here was derived from the entity, so a body could carry a component the
 * handler was never going to bind; the artifact refused the call before the requirement was
 * reached, and the refusal was recorded as the artifact failing. {@link RequestBodyContractQuery}
 * supplies the declaration, and it is consulted for BOTH halves: which type to read the components
 * from, and which of them can bind.
 *
 * <p><b>An unreadable declaration splits the two entry points, because they promise different
 * things.</b> {@link #acceptableBody} promises a body the application will ACCEPT, and a
 * derivation with nothing to check itself against cannot promise that -- so it DECLINES and its
 * caller abstains, the same trade {@code FieldConstraintDiscovery} already makes one step earlier.
 * {@link #candidateBodies} only ever promised "no 5xx", and its {@code {}} fallback answers that
 * with or without a declaration -- so it stays best-effort.
 *
 * <p><b>Measured before the decline was made binding, on mid-run trees rather than finished
 * ones.</b> An earlier reading over 70 preserved artifacts found every declaration readable, but
 * those are FINAL trees and all of them have controllers because they reached the end. Over 112
 * archived mid-run checkpoints the real figure is 271 of 281 derivations readable, and all ten
 * exceptions are trees where the endpoint is NOT SERVED YET -- nine carry no controller at all.
 * A success probe there fails for an unrelated reason whatever body it sends, so declining loses
 * no verification and stops the synthesizer inventing a body for an endpoint that does not exist.
 */
@lombok.extern.slf4j.Slf4j
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
            Optional<AcceptedBody> accepted = acceptedBody(path, artifactRoot);
            componentSource(path, artifactRoot, accepted)
                    // T4-DX: no token here — this path serves the ENDPOINT probes, which are not
                    // per-criterion and have no collision to avoid.
                    .flatMap(source -> synthesizeFromSource(source, Map.of(), accepted, null))
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
        return acceptableBody(path, artifactRoot, null);
    }

    /**
     * T4-DX: as above, with a token that makes this criterion's values distinct from every other
     * criterion's.
     *
     * <p><b>Per CRITERION, never per REQUEST, and that distinction is the whole design.</b> One
     * criterion's probe sequence is synthesized in a single call and therefore shares one token,
     * so a criterion asserting a collision — create a resource, create it again, expect the
     * refusal — still collides with itself exactly as before. Across criteria the tokens differ,
     * so the second criterion to create a resource no longer inherits the first one's. Measured on
     * run {@code 2026-09-22_05-11-32}: five setup and body requests refused with 409 by an
     * application that was right to refuse them.
     *
     * <p>Applied only to values carrying NO discovered constraint. A pattern-derived value cannot
     * be altered without breaking the pattern it was built to satisfy, and a length-bounded value
     * would have its distinguisher trimmed off; both keep today's behaviour, and a collision on a
     * constrained field stays possible. That residual is real and is recorded rather than papered
     * over.
     */
    public static Optional<String> acceptableBody(String path, Path artifactRoot, String distinguisher) {
        Optional<AcceptedBody> accepted = acceptedBody(path, artifactRoot);
        // T1-FV DoD: where the declaration cannot be read, decline. This method's whole promise is
        // a body the application will ACCEPT, and a derivation with nothing to check itself
        // against cannot make it. Measured over 112 mid-run checkpoints: 271 of 281 readable, and
        // every one of the ten exceptions is a tree whose endpoint is not served yet.
        if (accepted.isEmpty()) return Optional.empty();
        Optional<Path> model = componentSource(path, artifactRoot, accepted);
        if (model.isEmpty()) return Optional.empty();
        Map<String, FieldConstraintDiscovery.Constraint> constraints =
                FieldConstraintDiscovery.discover(artifactRoot);
        try {
            return synthesizeFromSource(model.get(), constraints, accepted, distinguisher);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The handler's declaration, with the read attempt logged so its reach can be counted. */
    private static Optional<AcceptedBody> acceptedBody(String path, Path artifactRoot) {
        Optional<AcceptedBody> accepted = RequestBodyContractQuery.acceptedBodyFor(path, artifactRoot);
        if (accepted.isEmpty()) {
            log.info("T1-FV: no readable body declaration for {} — deriving from the model alone", path);
        }
        return accepted;
    }

    /**
     * Which type the components come from. The declaration wins when it names a type the artifact
     * declares -- that is the request object, and it may be narrower than the entity. Otherwise
     * the path segment picks the model, which is what this class always did.
     */
    private static Optional<Path> componentSource(String path, Path artifactRoot,
                                                  Optional<AcceptedBody> accepted) {
        if (accepted.orElse(null) instanceof NamedType named) {
            Optional<Path> declared = findModelSource(named.simpleName(), artifactRoot);
            if (declared.isPresent()) return declared;
        }
        return lastStaticSegment(path).flatMap(segment -> findModelSource(segment, artifactRoot));
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
                                                         Map<String, FieldConstraintDiscovery.Constraint> constraints,
                                                         Optional<AcceptedBody> accepted,
                                                         String distinguisher) {
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
            if (!bindable(value, accepted)) {
                // T1-FV: the model holds it, the declaration will not take it. Sending it anyway
                // draws a refusal that says nothing about the requirement under test.
                log.info("T1-FV: dropping {} — the handler's declaration does not accept its type", field);
                continue;
            }
            FieldConstraintDiscovery.Constraint constraint = constraints.get(field);
            if (constraint == null) {
                value = distinguished(value, distinguisher);
            }
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

    /**
     * T4-DX: this criterion's own value for an unconstrained textual field.
     *
     * <p>Returns the value unchanged when there is no token, when the value is not textual, or
     * when appending would say nothing — so every existing caller and every constrained field is
     * exactly as before.
     */
    private static String distinguished(String quotedValue, String distinguisher) {
        if (distinguisher == null || distinguisher.isBlank()) return quotedValue;
        if (quotedValue == null || quotedValue.length() < 2 || !quotedValue.startsWith("\"")) return quotedValue;
        String inner = quotedValue.substring(1, quotedValue.length() - 1);
        return quote(inner + distinguisher);
    }

    /** JSON string literal for a generated value; the alphabet is narrow but not assumed. */
    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /**
     * Whether a component's value can bind to what the declaration accepts.
     *
     * <p>Compared by JSON KIND rather than by a table of type pairs, and the kinds come from
     * {@link #placeholderFor} itself -- the same rows that produced the value. So a handler taking
     * keyed integers accepts the model's numeric fields and refuses its booleans and its
     * timestamps, which is exactly the observed case, without this class learning any new type
     * vocabulary. A value type the placeholder table does not know yields no opinion, because a
     * kind nobody can name is not a kind anything can be excluded from.
     */
    private static boolean bindable(String value, Optional<AcceptedBody> accepted) {
        if (!(accepted.orElse(null) instanceof KeyedValues keyed)) return true;
        String acceptedValue = placeholderFor(keyed.valueTypeSimpleName());
        if (acceptedValue == null) return true;
        return jsonKind(value).equals(jsonKind(acceptedValue));
    }

    /** The JSON kind of a rendered placeholder: what a reader would call it, not what Java calls it. */
    private static String jsonKind(String rendered) {
        if (rendered.startsWith("\"")) return "string";
        if ("true".equals(rendered) || "false".equals(rendered)) return "boolean";
        return "number";
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
