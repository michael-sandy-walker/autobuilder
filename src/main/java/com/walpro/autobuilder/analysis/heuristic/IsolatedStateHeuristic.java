package com.walpro.autobuilder.analysis.heuristic;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import lombok.extern.slf4j.Slf4j;

/**
 * T2-BZ Layer A: detects shadow list declarations in mutated controllers.
 *
 * <p>A "shadow list" occurs when a {@code @RestController} (or {@code @Controller}) in the diff
 * declares its own {@code List<T>} / {@code CopyOnWriteArrayList<T>} field initialised with
 * {@code new ...}, while another controller in {@code previousOutput} already owns a
 * {@code List<T>} for the same element type {@code T}. At runtime the two lists are separate
 * instances — the mutated controller's copy is always empty, causing incorrect 404/409 responses.
 *
 * <p>On match: HIGH confidence, REJECTED. On no match: abstain.
 */
@Slf4j
@Component
public class IsolatedStateHeuristic implements EvaluationHeuristic {

    private static final String NAME = "IsolatedStateHeuristic";

    /** Detects @RestController or @Controller on a line. */
    private static final Pattern CONTROLLER_ANNOTATION =
            Pattern.compile("@(Rest)?Controller\\b");

    /**
     * Detects a list-type field initialised with {@code new}.
     * Group 1: element type (e.g. "Book", "Member").
     * Matches: {@code List<Book>}, {@code ArrayList<Book>},
     * {@code CopyOnWriteArrayList<Book>}, {@code LinkedList<Book>}.
     */
    private static final Pattern NEW_LIST_FIELD = Pattern.compile(
            "(?:List|ArrayList|CopyOnWriteArrayList|LinkedList)" +
            "<([A-Z][a-zA-Z0-9]*)>\\s+\\w+\\s*=\\s*new");

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome original) {
        if (input == null || original == null) return HeuristicOpinion.none(NAME);

        ProjectDiffResult diff = input.projectDiff();
        if (diff == null || diff.changedFiles() == null || diff.changedFiles().isEmpty()) {
            return HeuristicOpinion.none(NAME);
        }

        Map<String, String> previousOutput = input.previousOutput();
        if (previousOutput == null || previousOutput.isEmpty()) {
            return HeuristicOpinion.none(NAME);
        }

        // Build a map: element type T → file path that owns a List<T> in previousOutput
        Map<String, String> existingOwners = buildOwnerMap(previousOutput);
        if (existingOwners.isEmpty()) return HeuristicOpinion.none(NAME);

        // Check each changed controller file for shadow declarations
        for (Map.Entry<String, String> entry : diff.changedFiles().entrySet()) {
            String path = entry.getKey();
            String code = entry.getValue();
            if (!path.endsWith(".java") || code == null || code.isBlank()) continue;
            if (!isController(code)) continue;

            Set<String> shadows = findShadows(path, code, existingOwners);
            if (!shadows.isEmpty()) {
                String rationale = shadows.stream()
                        .map(t -> "Controller declares its own List<%s> for type owned by %s — shared state broken at runtime. Use an injected @Service or move shared state to a common bean.".formatted(t, existingOwners.get(t)))
                        .reduce((a, b) -> a + "; " + b).orElse("");
                log.info("{}: {}", NAME, rationale);
                return HeuristicOpinion.of(
                        NAME,
                        0.90,
                        MutationAcceptanceState.REJECTED,
                        GoalProgressState.STAGNATING,
                        rationale
                );
            }
        }

        return HeuristicOpinion.none(NAME);
    }

    /**
     * Builds a map of element type → owning file path for all {@code @Controller}
     * files in previousOutput that contain a new-initialised list field.
     */
    private static Map<String, String> buildOwnerMap(Map<String, String> files) {
        Map<String, String> owners = new HashMap<>();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String path = entry.getKey();
            String code = entry.getValue();
            if (!path.endsWith(".java") || code == null || code.isBlank()) continue;
            if (!isController(code)) continue;

            Matcher m = NEW_LIST_FIELD.matcher(code);
            while (m.find()) {
                String elementType = m.group(1);
                owners.putIfAbsent(elementType, fileName(path));
            }
        }
        return owners;
    }

    /**
     * Returns the set of element types {@code T} that the changed file shadows
     * (i.e. it declares its own {@code List<T>} when another controller already owns one).
     */
    private static Set<String> findShadows(String changedPath, String code,
                                           Map<String, String> existingOwners) {
        Set<String> shadows = new HashSet<>();
        String changedFile = fileName(changedPath);

        Matcher m = NEW_LIST_FIELD.matcher(code);
        while (m.find()) {
            String elementType = m.group(1);
            String ownerFile = existingOwners.get(elementType);
            // Shadow: another file (not this one) already owns List<T>
            if (ownerFile != null && !ownerFile.equals(changedFile)) {
                shadows.add(elementType);
            }
        }
        return shadows;
    }

    private static boolean isController(String code) {
        return CONTROLLER_ANNOTATION.matcher(code).find();
    }

    private static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
