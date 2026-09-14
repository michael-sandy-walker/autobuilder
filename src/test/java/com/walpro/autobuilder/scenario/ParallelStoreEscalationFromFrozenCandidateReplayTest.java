package com.walpro.autobuilder.scenario;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.heuristic.HeuristicOpinion;
import com.walpro.autobuilder.analysis.heuristic.StoreCoherenceHeuristic;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2-ES against the real thing: the parallel store that run {@code 2026-09-13_09-44-03} rejected
 * seven times over fifteen minutes, replayed without the model.
 *
 * <p><b>The tree is RECONSTRUCTED, not captured, and the reason is the finding.</b> That run
 * archived 22 cycles and not one contains {@code BookStore.java} — every parallel-store mutation
 * was REJECTED, so the defect never entered merged state, and {@code freeze_scenario.sh} captures
 * merged trees. A defect that exists only in rejected candidates is invisible to every tree-based
 * harness here, which is precisely the class of defect this ticket is about. So the scenario pairs
 * the preserved artifact's {@code BookRepository.java} (verbatim, its store still on line 12 as the
 * field rationale said) with the model's rejected {@code BookStore.java}, lifted verbatim from
 * {@code autobuilder.log}.
 *
 * <p>What this proves that the unit fixtures cannot: the subject and the resolution paths come out
 * right against code the MODEL wrote, not against code written to be detected. The candidate stores
 * its entities in a {@code ConcurrentHashMap<Long, Book>} — the interface-typed shape that defeated
 * the heuristic's original enumerated vocabulary in the field (run 4b, 2026-07-03).
 *
 * <p>Out of scope here: the three-cycle streak and the injection. Those are cycle-sequence
 * behaviour and live in {@code EvaluationAgentStructuralEscalationTest}; a frozen tree is one
 * cycle's input and cannot express a sequence.
 */
class ParallelStoreEscalationFromFrozenCandidateReplayTest {

    private static final String SCENARIO = "parallel-store-rejected-2026-09-13";

    private final StoreCoherenceHeuristic heuristic = new StoreCoherenceHeuristic();

    private static Map<String, String> frozenTree() throws IOException {
        Path root = Path.of("src/test/resources/scenarios", SCENARIO, "tree");
        assertThat(Files.isDirectory(root))
                .as("frozen scenario %s is missing — re-freeze it before running this", SCENARIO)
                .isTrue();
        Map<String, String> files = new HashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                files.put(root.relativize(p).toString(), Files.readString(p));
            }
        }
        return files;
    }

    /**
     * The declaration the model actually wrote, pinned so a later edit to the frozen tree cannot
     * quietly turn this into a test about something else.
     */
    @Test
    void theFrozenCandidateCarriesTheInterfaceTypedStoreThatDefeatedTheOldVocabulary() throws IOException {
        Map<String, String> tree = frozenTree();

        String store = tree.get("src/main/java/com/example/library/domain/store/BookStore.java");
        String repository = tree.get("src/main/java/com/example/library/domain/repository/BookRepository.java");

        assertThat(store).contains("private final Map<Long, Book> books = new ConcurrentHashMap<>();");
        assertThat(repository).contains("private final List<Book> books = new ArrayList<>();");
    }

    /**
     * The whole escalation rests on a subject that survives the model rewriting the file, and on
     * paths that name the real holders. Both are asserted here against real output.
     */
    @Test
    void theRealCandidateYieldsTheSubjectAndBothResolutionPaths() throws IOException {
        Map<String, String> tree = frozenTree();
        String storePath = "src/main/java/com/example/library/domain/store/BookStore.java";

        // The cycle that introduced the defect: everything but the store already on disk, the
        // store arriving as this cycle's change — which is how the heuristic sees an introduction.
        Map<String, String> previous = new HashMap<>(tree);
        previous.remove(storePath);

        CycleContextData context = CycleContextData.builder()
                .cycleName("replay")
                .previousOutput(previous)
                .projectDiff(new ProjectDiffResult(null, Map.of(storePath, tree.get(storePath)), Set.of()))
                .build();

        HeuristicOpinion opinion = heuristic.evaluate(context,
                new EvaluationOutcome(0.85, MutationAcceptanceState.ACCEPTED,
                        GoalProgressState.PROGRESSING, null, null));

        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.defectFinding()).isTrue();

        // The identity the streak counts, from real code.
        assertThat(opinion.identityKey()).isEqualTo("StoreCoherenceHeuristic|Book");

        // One way out per declaring type, each naming the other holder — the choice that
        // re-asking never offered across those seven attempts.
        assertThat(opinion.defect().resolutionPaths()).hasSize(2);
        assertThat(opinion.defect().resolutionPaths())
                .anySatisfy(p -> assertThat(p).startsWith("BookRepository is the only type")
                        .contains("BookStore"))
                .anySatisfy(p -> assertThat(p).startsWith("BookStore is the only type")
                        .contains("BookRepository"));

        // And the rationale reproduces the field line the run logged seven times.
        assertThat(opinion.rationale())
                .contains("parallel store introduced")
                .contains("BookRepository:12")
                .contains("writes to one are invisible to the other");
    }
}
