package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T2-DI pattern 5: detects a container-managed component being MANUFACTURED with {@code new}
 * inside another container-managed component instead of being injected. Observed in the run-5
 * fulfilled artifact (2026-07-02): {@code BookServiceImpl}'s constructor did
 * {@code this.bookRepository = new BookRepositoryImpl();} — so books saved through the service
 * lived in a private instance while {@code LoanServiceImpl} (properly injected) read the empty
 * container singleton, making every loan attempt fail at runtime with "Book not found" although
 * all business rules were correctly implemented. Compiles clean, passes code-reading
 * verification, breaks only at runtime.
 *
 * <p>The sibling {@link StoreCoherenceHeuristic} cannot see this shape: there is only ONE store
 * field in the code — the duplication is between two <i>instances</i> of the same class.
 *
 * <p>Same introduced/resolved contract as the store-coherence check: penalty when the diff
 * introduces a manufactured dependency, reward when it resolves one.
 *
 * <p><b>Ecosystem-gated by construction (user steer 2026-07-02):</b> this check encodes a
 * dependency-injection-container rule, so it must only ever judge code that uses such a
 * container. Both sides of the detection require a container-marker annotation — a project
 * with no markers yields an empty component set and the heuristic never forms an opinion.
 * The marker list is the single table of ecosystem knowledge here; feeding it from discovery
 * (project pom → classpath) instead of literals is the [[T4-BM]] direction, and any future
 * ecosystem-specific heuristic must follow the same self-gating shape.
 */
@Slf4j
@Component
public class ManufacturedDependencyHeuristic implements EvaluationHeuristic {

    private static final String NAME = "ManufacturedDependencyHeuristic";

    /** Annotations that mark a class as container-managed (one instance, injected) — built from
     *  {@link com.walpro.autobuilder.analysis.structure.AnnotationVocabularies#CONTAINER_MANAGED}
     *  (one home; this class's own list had drifted, missing the advice stereotypes — an advice
     *  class manufacturing a sibling component went undetected). */
    private static final Pattern COMPONENT_MARKER = Pattern.compile(
            "@(" + String.join("|",
                    com.walpro.autobuilder.analysis.structure.AnnotationVocabularies.CONTAINER_MANAGED)
                    + ")\\b");

    /**
     * Configuration classes are the container's sanctioned place to manufacture components
     * (factory methods returning new instances ARE the idiom there) — they are never flagged
     * as the manufacturing side, only recognised as manufactured targets. The composite
     * application-entry annotation is a configuration class too (run 23, 2026-07-07: the
     * duplicate-manufacture defect lived in the entry class, which carries no plain
     * configuration marker).
     */
    private static final Pattern FACTORY_CLASS_MARKER = Pattern.compile(
            "@(Configuration|SpringBootApplication)\\b");

    /** Marks a factory method inside a configuration class. */
    private static final Pattern FACTORY_METHOD_MARKER = Pattern.compile("@Bean\\b");

    /** Identifier directly followed by the parameter list — the method name in a signature. */
    private static final Pattern METHOD_NAME = Pattern.compile("(\\w+)\\s*\\(");

    private static final Pattern CLASS_DECL = Pattern.compile("\\bclass\\s+([A-Z]\\w*)");

    /** {@code new X(} — candidate manufacture site. */
    private static final Pattern NEW_INSTANCE = Pattern.compile("\\bnew\\s+([A-Z]\\w*)\\s*\\(");

    record ManufactureSite(String enclosingType, String manufacturedType, String path, int line) {
        String key() { return enclosingType + " manufactures " + manufacturedType; }
    }

    /**
     * T1-FK (run 23, 2026-07-07): the same project-declared type manufactured with {@code new}
     * inside two or more DIFFERENT factory methods of one configuration class. Each factory
     * method builds its own instance, so consumers wired from different methods hold different
     * instances of what the container idiom implies is one shared component — state written
     * through one consumer is invisible to the others. A single manufacture per factory method
     * remains the sanctioned idiom and is never flagged; the defect signature is the duplication.
     * The key deliberately omits method names so renaming a factory method neither introduces
     * nor resolves the finding.
     */
    record FactoryDuplicate(String factoryType, String manufacturedType, List<String> methodNames, String path) {
        String key() { return factoryType + " duplicate-manufactures " + manufacturedType; }
    }

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome outcome) {
        if (input == null || outcome == null) return HeuristicOpinion.none(NAME);

        ProjectDiffResult diff = input.projectDiff();
        if (diff == null || diff.changedFiles() == null || diff.changedFiles().isEmpty()) {
            return HeuristicOpinion.none(NAME);
        }

        Map<String, String> previous = input.previousOutput() != null ? input.previousOutput() : Map.of();
        Map<String, String> current = diff.overlay(previous);

        Set<String> previousKeys = keys(manufacturedDependencies(previous));
        previousKeys.addAll(dupeKeys(duplicateFactoryManufactures(previous)));
        List<ManufactureSite> currentSites = manufacturedDependencies(current);
        List<FactoryDuplicate> currentDupes = duplicateFactoryManufactures(current);
        Set<String> currentKeys = keys(currentSites);
        currentKeys.addAll(dupeKeys(currentDupes));

        Set<String> introduced = new LinkedHashSet<>(currentKeys);
        introduced.removeAll(previousKeys);

        Set<String> resolved = new LinkedHashSet<>(previousKeys);
        resolved.removeAll(currentKeys);

        if (!introduced.isEmpty()) {
            String siteDetails = currentSites.stream()
                    .filter(s -> introduced.contains(s.key()))
                    .map(s -> s.key() + " via new at " + s.path() + ":" + s.line()
                            + " — the private instance is invisible to the container-managed one: "
                            + "inject it through the constructor instead of calling new")
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("");
            String dupeDetails = currentDupes.stream()
                    .filter(d -> introduced.contains(d.key()))
                    .map(d -> d.key() + " — built with new in separate factory methods "
                            + d.methodNames() + " at " + d.path()
                            + ", so each consumer gets its own instance and state written through "
                            + "one is invisible to the others: declare it once as a shared component "
                            + "and inject that single instance everywhere")
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("");
            String details = siteDetails.isEmpty() ? dupeDetails
                    : dupeDetails.isEmpty() ? siteDetails : siteDetails + "; " + dupeDetails;
            String rationale = "manufactured dependency introduced — " + details;
            log.info("{}: {}", NAME, rationale);
            // CRITICAL, not HIGH (T4-BW): same defect family as the sibling store-coherence
            // check — deterministic, introduced by this diff, and fatal at runtime while
            // invisible to compilation and file-level review. Run 13 (2026-07-04) showed a
            // correct HIGH vote from this family being averaged away at the exact merge that
            // broke the artifact; decisive findings use the CRITICAL overrule path. Defect-marked
            // so the score echo's confidence cannot outrank it in the tiebreak (run 14).
            return HeuristicOpinion.ofDefectWithScore(
                    NAME, 0.9,
                    MutationAcceptanceState.REJECTED,
                    GoalProgressState.STAGNATING,
                    rationale,
                    Math.max(0.0, outcome.score() - 0.10),
                    new HeuristicOpinion.StructuralDefect(
                            defectSubject(introduced),
                            resolutionPaths(introduced, currentSites, currentDupes))
            );
        }

        if (!resolved.isEmpty()) {
            String rationale = "resolves manufactured dependency: "
                    + String.join(", ", resolved);
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
     * Finds every {@code new X(...)} inside a container-managed class where {@code X} is itself
     * a container-managed type. Both sides must carry a component marker — plain helpers, DTOs
     * and collections are never flagged.
     */
    List<ManufactureSite> manufacturedDependencies(Map<String, String> files) {
        Set<String> componentTypes = componentTypes(files);
        List<ManufactureSite> sites = new ArrayList<>();
        if (componentTypes.isEmpty()) return sites;

        for (Map.Entry<String, String> entry : files.entrySet()) {
            String path = entry.getKey();
            String code = entry.getValue();
            if (!path.endsWith(".java") || code == null || code.isBlank()) continue;
            if (!COMPONENT_MARKER.matcher(code).find()) continue;
            // Factory/configuration classes legitimately manufacture components — skip as
            // the manufacturing side.
            if (FACTORY_CLASS_MARKER.matcher(code).find()) continue;

            Matcher cls = CLASS_DECL.matcher(code);
            if (!cls.find()) continue;
            String enclosing = cls.group(1);

            Matcher m = NEW_INSTANCE.matcher(code);
            while (m.find()) {
                String manufactured = m.group(1);
                if (!componentTypes.contains(manufactured)) continue;
                int line = 1 + (int) code.substring(0, m.start()).chars().filter(c -> c == '\n').count();
                sites.add(new ManufactureSite(enclosing, manufactured, path, line));
            }
        }
        return sites;
    }

    /**
     * T1-FK: finds every project-declared type manufactured with {@code new} inside two or more
     * different factory methods of one configuration class. Ecosystem-gated on the factory-class
     * and factory-method markers; the manufactured type needs no marker of its own — being built
     * inside factory methods IS its container-managed signal (run 23: the duplicated store types
     * carried no annotations at all, they existed only as factory-method products). Types not
     * declared in the project are never flagged: manufacturing a library type in several factory
     * methods is unjudgeable without knowing its state semantics.
     */
    List<FactoryDuplicate> duplicateFactoryManufactures(Map<String, String> files) {
        List<FactoryDuplicate> duplicates = new ArrayList<>();
        Set<String> declared = declaredTypes(files);
        if (declared.isEmpty()) return duplicates;

        for (Map.Entry<String, String> entry : files.entrySet()) {
            String path = entry.getKey();
            String code = entry.getValue();
            if (!path.endsWith(".java") || code == null || code.isBlank()) continue;
            if (!FACTORY_CLASS_MARKER.matcher(code).find()) continue;

            Matcher cls = CLASS_DECL.matcher(code);
            if (!cls.find()) continue;
            String factoryType = cls.group(1);

            Map<String, Set<String>> methodsByManufacturedType = new LinkedHashMap<>();
            Matcher factoryMethod = FACTORY_METHOD_MARKER.matcher(code);
            while (factoryMethod.find()) {
                int bodyStart = code.indexOf('{', factoryMethod.end());
                if (bodyStart < 0) continue;
                String signature = code.substring(factoryMethod.end(), bodyStart);
                Matcher name = METHOD_NAME.matcher(signature);
                String methodName = null;
                // Last identifier-before-parens in the signature region is the method name
                // (earlier matches belong to annotation arguments like @Qualifier("...")).
                while (name.find()) methodName = name.group(1);
                if (methodName == null) continue;

                int bodyEnd = matchingBrace(code, bodyStart);
                Matcher m = NEW_INSTANCE.matcher(code.substring(bodyStart, bodyEnd));
                while (m.find()) {
                    String manufactured = m.group(1);
                    if (!declared.contains(manufactured)) continue;
                    methodsByManufacturedType
                            .computeIfAbsent(manufactured, k -> new LinkedHashSet<>())
                            .add(methodName);
                }
            }

            for (Map.Entry<String, Set<String>> byType : methodsByManufacturedType.entrySet()) {
                if (byType.getValue().size() >= 2) {
                    duplicates.add(new FactoryDuplicate(
                            factoryType, byType.getKey(), List.copyOf(byType.getValue()), path));
                }
            }
        }
        return duplicates;
    }

    /** Index just past the brace closing the block opened at {@code openBrace}; end of string if unbalanced. */
    private static int matchingBrace(String code, int openBrace) {
        int depth = 0;
        for (int i = openBrace; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i + 1;
        }
        return code.length();
    }

    /** Simple names of every class declared in the project files, marker or not. */
    Set<String> declaredTypes(Map<String, String> files) {
        Set<String> types = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String code = entry.getValue();
            if (!entry.getKey().endsWith(".java") || code == null) continue;
            Matcher cls = CLASS_DECL.matcher(code);
            while (cls.find()) {
                types.add(cls.group(1));
            }
        }
        return types;
    }

    /** Simple names of classes carrying a component marker annotation. */
    Set<String> componentTypes(Map<String, String> files) {
        Set<String> types = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String code = entry.getValue();
            if (!entry.getKey().endsWith(".java") || code == null) continue;
            if (!COMPONENT_MARKER.matcher(code).find()) continue;
            Matcher cls = CLASS_DECL.matcher(code);
            if (cls.find()) {
                types.add(cls.group(1));
            }
        }
        return types;
    }

    private static Set<String> keys(List<ManufactureSite> sites) {
        Set<String> keys = new LinkedHashSet<>();
        for (ManufactureSite s : sites) keys.add(s.key());
        return keys;
    }

    private static Set<String> dupeKeys(List<FactoryDuplicate> duplicates) {
        Set<String> keys = new LinkedHashSet<>();
        for (FactoryDuplicate d : duplicates) keys.add(d.key());
        return keys;
    }

    /**
     * T2-ES: what this finding is about, order-independent so it keys identically on every cycle
     * it survives. The site keys already exclude the declaration line, so the subject is stable
     * across a rewrite that moves the code — which is the condition the whole escalation rests on.
     */
    static String defectSubject(Set<String> introduced) {
        return introduced.stream().sorted().reduce((a, b) -> a + "," + b).orElse("");
    }

    /**
     * T2-ES: the way out of each introduced manufacture, phrased as a requirement about the code
     * so it stays confirmable once the defect is gone. The remedies were already written into the
     * rationale as trailing advice; stated here they can also become the acceptance criteria of an
     * escalated work item, which is the difference between telling the model and asking it.
     */
    static List<String> resolutionPaths(Set<String> introduced,
                                        List<ManufactureSite> sites,
                                        List<FactoryDuplicate> duplicates) {
        List<String> paths = new ArrayList<>();
        sites.stream()
                .filter(s -> introduced.contains(s.key()))
                .sorted(java.util.Comparator.comparing(ManufactureSite::key))
                .forEach(s -> paths.add(s.enclosingType() + " receives its " + s.manufacturedType()
                        + " through its constructor, and creates no " + s.manufacturedType()
                        + " of its own"));
        duplicates.stream()
                .filter(d -> introduced.contains(d.key()))
                .sorted(java.util.Comparator.comparing(FactoryDuplicate::key))
                .forEach(d -> paths.add(d.factoryType() + " builds one shared "
                        + d.manufacturedType() + " that every one of " + d.methodNames()
                        + " returns, rather than building a separate one in each"));
        return paths;
    }
}
