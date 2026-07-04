package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.model.ElementType;
import com.walpro.autobuilder.analysis.structure.JavaStructureParser;
import com.walpro.autobuilder.analysis.structure.model.ParsedElement;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T2-DI: detects "parallel store" coherence violations — the same entity type stored in two or
 * more distinct enclosing classes (e.g. {@code List<Book>} declared in both {@code BookController}
 * and {@code BookService}). Writes to one store are invisible to the other, so dataflow paths
 * silently return empty results. Invisible at the file level — every file compiles, every
 * reference resolves — and invisible to the LLM evaluator that reasons over individual files.
 *
 * <p>Slots into the structural-awareness family (peer of CrossFileConsistencyHeuristic).
 * Penalty when the diff introduces a new violation; reward when the diff resolves one.
 */
@Slf4j
@Component
public class StoreCoherenceHeuristic implements EvaluationHeuristic {

    private static final String NAME = "StoreCoherenceHeuristic";

    /** Suffixes that aren't entity POJOs — controllers/services/exceptions etc. */
    private static final Set<String> NON_ENTITY_SUFFIXES = Set.of(
            "Controller", "Service", "Repository", "Dao", "Mapper",
            "Exception", "Error", "Handler", "Filter", "Interceptor",
            "Config", "Configuration", "Properties", "Application",
            "Request", "Response", "Dto", "Builder", "Factory",
            "Util", "Utils", "Helper", "Constants", "Test", "Tests"
    );

    /**
     * Extracts {@code List<Book>}, {@code CopyOnWriteArrayList<Book>}, etc. — any type whose
     * simple name ENDS in a collection suffix, not an enumerated list of names. The enumerated
     * form was a frozen vocabulary and missed JDK synonyms in the field: run 4b (2026-07-03)
     * declared its parallel stores as {@code ConcurrentMap<Long, Book>} — the INTERFACE, absent
     * from the old alternation — and the heuristic fired zero times while the artifact carried
     * the exact defect it was built for (loan rules unreachable at runtime).
     */
    private static final Pattern COLLECTION_OF_E =
            Pattern.compile("\\b(\\w*(?:List|Set|Collection|Queue|Deque))\\s*<\\s*([A-Z]\\w*)\\s*>");

    /** {@code AnyMap<K, V>} — value type is the entity; matches every *Map simple name. */
    private static final Pattern MAP_OF_E =
            Pattern.compile("\\b(\\w*Map)\\s*<\\s*[^,>]+,\\s*([A-Z]\\w*)\\s*>");

    /** {@code E[]} — element type is the entity. */
    private static final Pattern ARRAY_OF_E =
            Pattern.compile("\\b([A-Z]\\w*)\\s*\\[\\s*\\]\\s+\\w+\\s*[=;]");

    /** Stop-set for the array regex (won't be flagged as entity). */
    private static final Set<String> NON_ENTITY_TYPES = Set.of(
            "String", "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short", "Character",
            "Object", "Number", "Void", "Enum"
    );

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome outcome) {
        if (input == null || outcome == null) return HeuristicOpinion.none(NAME);

        ProjectDiffResult diff = input.projectDiff();
        if (diff == null || diff.changedFiles() == null || diff.changedFiles().isEmpty()) {
            return HeuristicOpinion.none(NAME);
        }

        Map<String, String> previous = input.previousOutput() != null ? input.previousOutput() : Map.of();

        // Current view = previous overlaid with this cycle's changed content.
        Map<String, String> current = new LinkedHashMap<>(previous);
        current.putAll(diff.changedFiles());

        Map<String, List<StoreLocation>> previousStores = parallelStores(previous);
        Map<String, List<StoreLocation>> currentStores = parallelStores(current);

        // Only entities with ≥2 distinct enclosing types are violations.
        Set<String> previousViolations = violationEntities(previousStores);
        Set<String> currentViolations = violationEntities(currentStores);

        Set<String> introduced = new LinkedHashSet<>(currentViolations);
        introduced.removeAll(previousViolations);

        Set<String> resolved = new LinkedHashSet<>(previousViolations);
        resolved.removeAll(currentViolations);

        if (!introduced.isEmpty()) {
            String details = introduced.stream()
                    .map(e -> formatViolation(e, currentStores.get(e)))
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("");
            String rationale = "parallel store introduced — " + details
                    + "; writes to one are invisible to the other";
            log.info("{}: {}", NAME, rationale);
            // CRITICAL, not HIGH (T4-BW, from false completion #3, run 13 2026-07-04): this vote
            // fired correctly on the terminal mutation — a full rewrite giving LoanService its own
            // empty stores — and was averaged away by the LLM's ACCEPTED plus the deterministic
            // score echo (final ACCEPTED 0.664); the artifact shipped with every loan lookup
            // reading an empty map. A deterministic runtime-fatal finding is decisive at
            // negotiation (the CRITICAL overrule path), not one vote among peers.
            return HeuristicOpinion.ofWithScore(
                    NAME, 0.9,
                    MutationAcceptanceState.REJECTED,
                    GoalProgressState.STAGNATING,
                    rationale,
                    Math.max(0.0, outcome.score() - 0.10)
            );
        }

        if (!resolved.isEmpty()) {
            String details = resolved.stream()
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            String rationale = "resolves parallel-store violation for entity '" + details + "'";
            log.info("{}: {}", NAME, rationale);
            return HeuristicOpinion.ofWithScore(
                    NAME, 0.5,
                    MutationAcceptanceState.ACCEPTED,
                    GoalProgressState.PROGRESSING,
                    rationale,
                    Math.min(1.0, outcome.score() + 0.05)
            );
        }

        return HeuristicOpinion.none(NAME);
    }

    // ── Detection ──────────────────────────────────────────────────────────────────────────────

    /**
     * Scans {@code files} for fields whose declared type is a collection of an entity type, and
     * groups them by entity name. Result key = entity simple name; value = each declaration site.
     */
    Map<String, List<StoreLocation>> parallelStores(Map<String, String> files) {
        Map<String, List<StoreLocation>> stores = new HashMap<>();
        Set<String> entityTypes = entityTypes(files);

        for (Map.Entry<String, String> entry : files.entrySet()) {
            String path = entry.getKey();
            String code = entry.getValue();
            if (!path.endsWith(".java") || code == null || code.isBlank()) continue;

            ParsedElement parsed;
            try {
                parsed = JavaStructureParser.parse(path, code);
            } catch (Exception ignored) {
                continue;
            }

            for (ParsedElement type : parsed.children()) {
                if (type.type() != ElementType.CLASS) continue;
                String enclosingSimpleName = simpleName(type.id());
                if (enclosingSimpleName == null) continue;

                for (ParsedElement member : type.children()) {
                    if (member.type() != ElementType.FIELD) continue;
                    String fieldContent = member.content();
                    if (fieldContent == null || fieldContent.isBlank()) continue;

                    String entity = extractStoredEntity(fieldContent);
                    if (entity == null) continue;
                    if (!entityTypes.contains(entity)) continue;

                    int line = lineFromMeta(member);
                    stores.computeIfAbsent(entity, k -> new ArrayList<>())
                            .add(new StoreLocation(enclosingSimpleName, path, line));
                }
            }
        }
        return stores;
    }

    /**
     * Collects PascalCase entity types: declared with at least one mutable instance field or
     * setter, suffix-filtered to drop controllers/services/exceptions/etc.
     */
    Set<String> entityTypes(Map<String, String> files) {
        Set<String> entities = new HashSet<>();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String path = entry.getKey();
            String code = entry.getValue();
            if (!path.endsWith(".java") || code == null || code.isBlank()) continue;

            ParsedElement parsed;
            try {
                parsed = JavaStructureParser.parse(path, code);
            } catch (Exception ignored) {
                continue;
            }
            for (ParsedElement type : parsed.children()) {
                if (type.type() != ElementType.CLASS) continue;
                String simple = simpleName(type.id());
                if (simple == null || !isPascalCase(simple)) continue;
                if (hasNonEntitySuffix(simple)) continue;
                if (NON_ENTITY_TYPES.contains(simple)) continue;

                if (looksLikeEntity(type)) {
                    entities.add(simple);
                }
            }
        }
        return entities;
    }

    private static boolean looksLikeEntity(ParsedElement type) {
        for (ParsedElement member : type.children()) {
            if (member.type() == ElementType.FIELD) {
                String content = member.content();
                if (content == null) continue;
                // Mutable instance field: no "final" and no "static".
                if (!content.contains("final") && !content.contains("static")) {
                    return true;
                }
            } else if (member.type() == ElementType.METHOD) {
                String id = simpleName(member.id());
                if (id != null && id.startsWith("set") && id.length() > 3
                        && Character.isUpperCase(id.charAt(3))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Returns the entity simple name, or null if {@code fieldContent} is not a store-of-entity. */
    static String extractStoredEntity(String fieldContent) {
        Matcher m = MAP_OF_E.matcher(fieldContent);
        if (m.find() && !NON_ENTITY_TYPES.contains(m.group(2))) return m.group(2);

        m = COLLECTION_OF_E.matcher(fieldContent);
        if (m.find() && !NON_ENTITY_TYPES.contains(m.group(2))) return m.group(2);

        m = ARRAY_OF_E.matcher(fieldContent);
        if (m.find() && !NON_ENTITY_TYPES.contains(m.group(1))) return m.group(1);

        return null;
    }

    /** Entities with ≥2 distinct enclosing types. */
    static Set<String> violationEntities(Map<String, List<StoreLocation>> stores) {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, List<StoreLocation>> e : stores.entrySet()) {
            Set<String> enclosing = new HashSet<>();
            for (StoreLocation loc : e.getValue()) {
                enclosing.add(loc.enclosingType());
            }
            if (enclosing.size() >= 2) out.add(e.getKey());
        }
        return out;
    }

    private static String formatViolation(String entity, List<StoreLocation> locations) {
        if (locations == null) return "entity '" + entity + "'";
        StringBuilder sb = new StringBuilder("entity '").append(entity).append("' declared in ");
        for (int i = 0; i < locations.size(); i++) {
            if (i > 0) sb.append(", ");
            StoreLocation loc = locations.get(i);
            sb.append(loc.enclosingType());
            if (loc.line() > 0) sb.append(':').append(loc.line());
        }
        return sb.toString();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────────────────

    private static String simpleName(String id) {
        if (id == null) return null;
        int dot = id.lastIndexOf('.');
        return dot >= 0 ? id.substring(dot + 1) : id;
    }

    private static boolean isPascalCase(String s) {
        return !s.isEmpty() && Character.isUpperCase(s.charAt(0));
    }

    private static boolean hasNonEntitySuffix(String name) {
        for (String suffix : NON_ENTITY_SUFFIXES) {
            if (name.endsWith(suffix)) return true;
        }
        return false;
    }

    private static int lineFromMeta(ParsedElement member) {
        if (member.metadata() == null) return -1;
        Object line = member.metadata().get("line");
        return (line instanceof Integer i) ? i : -1;
    }

    /** Where a store of an entity was declared. */
    record StoreLocation(String enclosingType, String path, int line) {}
}
