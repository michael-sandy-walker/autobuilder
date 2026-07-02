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
 * introduces a manufactured dependency, reward when it resolves one. Framework-neutral by
 * data: the component-marker annotation list is the only ecosystem knowledge, kept as one
 * table here (candidate for the T4-BM discovery direction if a second ecosystem arrives).
 */
@Slf4j
@Component
public class ManufacturedDependencyHeuristic implements EvaluationHeuristic {

    private static final String NAME = "ManufacturedDependencyHeuristic";

    /** Annotations that mark a class as container-managed (one instance, injected). */
    private static final Pattern COMPONENT_MARKER = Pattern.compile(
            "@(Service|Component|Repository|RestController|Controller|Configuration)\\b");

    private static final Pattern CLASS_DECL = Pattern.compile("\\bclass\\s+([A-Z]\\w*)");

    /** {@code new X(} — candidate manufacture site. */
    private static final Pattern NEW_INSTANCE = Pattern.compile("\\bnew\\s+([A-Z]\\w*)\\s*\\(");

    record ManufactureSite(String enclosingType, String manufacturedType, String path, int line) {
        String key() { return enclosingType + " manufactures " + manufacturedType; }
    }

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome outcome) {
        if (input == null || outcome == null) return HeuristicOpinion.none(NAME);

        ProjectDiffResult diff = input.projectDiff();
        if (diff == null || diff.changedFiles() == null || diff.changedFiles().isEmpty()) {
            return HeuristicOpinion.none(NAME);
        }

        Map<String, String> previous = input.previousOutput() != null ? input.previousOutput() : Map.of();
        Map<String, String> current = new LinkedHashMap<>(previous);
        current.putAll(diff.changedFiles());

        Set<String> previousKeys = keys(manufacturedDependencies(previous));
        List<ManufactureSite> currentSites = manufacturedDependencies(current);
        Set<String> currentKeys = keys(currentSites);

        Set<String> introduced = new LinkedHashSet<>(currentKeys);
        introduced.removeAll(previousKeys);

        Set<String> resolved = new LinkedHashSet<>(previousKeys);
        resolved.removeAll(currentKeys);

        if (!introduced.isEmpty()) {
            String details = currentSites.stream()
                    .filter(s -> introduced.contains(s.key()))
                    .map(s -> s.key() + " via new at " + s.path() + ":" + s.line())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("");
            String rationale = "manufactured dependency introduced — " + details
                    + "; the private instance is invisible to the container-managed one: "
                    + "inject it through the constructor instead of calling new";
            log.info("{}: {}", NAME, rationale);
            return HeuristicOpinion.ofWithScore(
                    NAME, 0.75,
                    MutationAcceptanceState.REJECTED,
                    GoalProgressState.STAGNATING,
                    rationale,
                    Math.max(0.0, outcome.score() - 0.10)
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
}
