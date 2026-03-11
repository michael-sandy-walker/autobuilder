package com.walpro.autobuilder.analysis.heuristic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.model.ElementType;
import com.walpro.autobuilder.analysis.structure.JavaStructureParser;
import com.walpro.autobuilder.analysis.structure.model.ParsedElement;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import lombok.extern.slf4j.Slf4j;

/**
 * Detects method calls in changed files that reference methods not present in any known
 * project-local type. Uses name-inference heuristics (camelCase variable → PascalCase type)
 * to cross-check without full type resolution.
 *
 * <p>Returns a MEDIUM-confidence STAGNATING opinion when violations are found, signalling that
 * the mutation introduces a structural inconsistency the loop should continue to resolve.
 * Does NOT hard-reject: code that isn't yet compilable can still be a valid intermediate step.
 */
@Slf4j
@Component
public class CrossFileConsistencyHeuristic implements EvaluationHeuristic {

    private static final String NAME = "CrossFileConsistencyHeuristic";

    /**
     * Matches instance method calls: {@code someVar.methodName(}
     * Excludes chained calls on literals / generic types (e.g. String.format, System.out).
     */
    private static final Pattern METHOD_CALL =
            Pattern.compile("\\b([a-z][a-zA-Z0-9_]*)\\.(\\w+)\\s*\\(");

    /** Well-known standard-library / framework method names to suppress false positives. */
    private static final Set<String> COMMON_METHODS = Set.of(
            "toString", "equals", "hashCode", "getClass", "notify", "notifyAll", "wait",
            "add", "remove", "get", "set", "put", "contains", "isEmpty", "size", "clear",
            "stream", "collect", "filter", "map", "forEach", "toList", "orElse", "orElseThrow",
            "findFirst", "findAny", "anyMatch", "allMatch", "noneMatch",
            "info", "warn", "error", "debug", "trace",
            "append", "indexOf", "substring", "trim", "toLowerCase", "toUpperCase", "replace",
            "format", "valueOf", "parseInt", "parseLong", "of", "ofNullable", "isPresent",
            "perform", "andExpect", "contentType", "content", "andReturn",
            "save", "findAll", "findById", "delete", "existsById"
    );

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome outcome) {
        if (input == null || outcome == null) return HeuristicOpinion.none(NAME);

        ProjectDiffResult diff = input.projectDiff();
        if (diff == null || diff.changedFiles() == null || diff.changedFiles().isEmpty()) {
            return HeuristicOpinion.none(NAME);
        }

        Map<String, String> previousOutput = input.previousOutput();

        // Build the full view: previousOutput overridden by incoming changes.
        Map<String, String> allFiles = new HashMap<>();
        if (previousOutput != null) allFiles.putAll(previousOutput);
        allFiles.putAll(diff.changedFiles());

        // 1. Build method registry: simpleName → set of known method names
        Map<String, Set<String>> typeMethodRegistry = buildMethodRegistry(allFiles);

        if (typeMethodRegistry.isEmpty()) {
            return HeuristicOpinion.none(NAME);
        }

        // 2. Scan each changed file for calls to undeclared methods on known local types.
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> entry : diff.changedFiles().entrySet()) {
            String path = entry.getKey();
            String code = entry.getValue();
            if (!path.endsWith(".java") || code == null || code.isBlank()) continue;

            // Skip test files: they use mock frameworks that generate proxy methods.
            if (path.contains("/test/") || path.contains("\\test\\")) continue;

            findViolations(path, code, typeMethodRegistry, violations);
        }

        if (violations.isEmpty()) {
            return HeuristicOpinion.none(NAME);
        }

        String rationale = "Cross-file consistency: %d call(s) to undeclared methods — %s"
                .formatted(violations.size(), String.join("; ", violations));
        log.info("{}: {}", NAME, rationale);

        // MEDIUM confidence — influences the negotiator vote but does not single-handedly override.
        // Abstain on mutation acceptance (code-first: the mutation can still be merged as an
        // intermediate step). Vote STAGNATING on goal progress to prevent premature GOAL_FULFILLED.
        return HeuristicOpinion.of(
                NAME,
                0.65,
                MutationAcceptanceState.NOT_EVALUATED,
                GoalProgressState.STAGNATING,
                rationale
        );
    }

    // ── Registry building ──────────────────────────────────────────────────────────────────────

    private Map<String, Set<String>> buildMethodRegistry(Map<String, String> files) {
        Map<String, Set<String>> registry = new HashMap<>();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String path = entry.getKey();
            String code = entry.getValue();
            if (!path.endsWith(".java") || code == null || code.isBlank()) continue;
            try {
                ParsedElement file = JavaStructureParser.parse(path, code);
                for (ParsedElement type : file.children()) {
                    String simpleName = simpleName(type.id());
                    if (simpleName == null || simpleName.isBlank()) continue;
                    Set<String> methods = new HashSet<>();
                    for (ParsedElement member : type.children()) {
                        ElementType t = member.type();
                        if (t == ElementType.METHOD || t == ElementType.CONSTRUCTOR) {
                            String memberName = simpleName(member.id());
                            if (memberName != null) methods.add(memberName);
                        }
                    }
                    registry.merge(simpleName, methods, (a, b) -> { a.addAll(b); return a; });
                }
            } catch (Exception ignored) {
                // Unparseable file (e.g. partial/broken code) — skip silently.
            }
        }
        return registry;
    }

    // ── Violation detection ────────────────────────────────────────────────────────────────────

    private void findViolations(String path, String code,
                                Map<String, Set<String>> registry, List<String> violations) {
        Matcher m = METHOD_CALL.matcher(code);
        while (m.find()) {
            String varName = m.group(1);
            String methodName = m.group(2);

            if (COMMON_METHODS.contains(methodName)) continue;

            String inferredType = toPascalCase(varName);
            Set<String> knownMethods = registry.get(inferredType);
            if (knownMethods == null) continue; // not a known local type — skip

            if (!knownMethods.contains(methodName)) {
                violations.add("`%s.%s()` (inferred type: %s) in %s"
                        .formatted(varName, methodName, inferredType, fileName(path)));
                if (violations.size() >= 5) return; // cap report length
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────────────────

    /** Extracts the last segment of a dotted id (e.g. "com.example.Task.setId" → "setId"). */
    private static String simpleName(String id) {
        if (id == null) return null;
        int dot = id.lastIndexOf('.');
        return dot >= 0 ? id.substring(dot + 1) : id;
    }

    /**
     * Converts a camelCase variable name to PascalCase to infer its type.
     * "task" → "Task", "taskRepository" → "TaskRepository", "myService" → "MyService".
     */
    private static String toPascalCase(String name) {
        if (name == null || name.isEmpty()) return name;
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
