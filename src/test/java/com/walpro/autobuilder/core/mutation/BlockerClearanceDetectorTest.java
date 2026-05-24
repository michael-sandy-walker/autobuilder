package com.walpro.autobuilder.core.mutation;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.graph.mutation.DeferredMutationGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class BlockerClearanceDetectorTest {

    private DeferredMutationLedger ledger;
    private FileService fileService;
    private BlockerClearanceDetector detector;
    private FakeGraph graph;

    @BeforeEach
    void setUp() throws Exception {
        graph = new FakeGraph();
        ledger = new DeferredMutationLedger(graph);
        fileService = Mockito.mock(FileService.class);
        when(fileService.exists(anyString())).thenReturn(true);
        detector = new BlockerClearanceDetector(ledger, fileService);
    }

    @Test
    void mergedMethodDeclaration_clearsMatchingMissingMemberBlocker() throws Exception {
        CycleContextData ctx = baseContext("cycle-A", "goal", "sub");
        DeferredMutation d = ledger.defer(ctx,
                List.of(new FileMutation("LoanService.java", "x")),
                List.of(MutationBlocker.missingMember("Loan", "setReturned", "boolean")),
                0.7, 0.65);

        // Merge of cycle-B adds setReturned(boolean) to Loan.java.
        String loanJava = """
                package com.example.demo;
                public class Loan {
                    private boolean returned;
                    public void setReturned(boolean returned) { this.returned = returned; }
                }
                """;
        when(fileService.readString("output/src/main/java/com/example/demo/Loan.java"))
                .thenReturn(loanJava);

        List<DeferredMutation> nowReady = detector.detectAndClear(
                "cycle-B",
                List.of("src/main/java/com/example/demo/Loan.java"),
                "output");

        assertThat(nowReady).extracting(DeferredMutation::id).containsExactly(d.id());
        assertThat(graph.findById(d.id()).state()).isEqualTo(DeferredMutationState.READY_TO_RESTAGE);
    }

    @Test
    void mergedConstructor_clearsMissingConstructorBlocker() throws Exception {
        CycleContextData ctx = baseContext("cycle-A", "goal", "sub");
        DeferredMutation d = ledger.defer(ctx,
                List.of(new FileMutation("X.java", "x")),
                List.of(MutationBlocker.missingMember("Book", "Book", "long,String,String")),
                0.7, 0.65);

        String bookJava = """
                package com.example.demo;
                public class Book {
                    public Book(long id, String title, String isbn) {}
                }
                """;
        when(fileService.readString("output/src/main/java/com/example/demo/Book.java"))
                .thenReturn(bookJava);

        List<DeferredMutation> nowReady = detector.detectAndClear(
                "cycle-B",
                List.of("src/main/java/com/example/demo/Book.java"),
                "output");

        assertThat(nowReady).extracting(DeferredMutation::id).containsExactly(d.id());
    }

    @Test
    void mergedTypeDeclaration_clearsMissingTypeBlocker() throws Exception {
        CycleContextData ctx = baseContext("cycle-A", "goal", "sub");
        DeferredMutation d = ledger.defer(ctx,
                List.of(new FileMutation("X.java", "x")),
                List.of(MutationBlocker.missingType("LoanNotFoundException")),
                0.7, 0.65);

        String exJava = """
                package com.example.demo;
                public class LoanNotFoundException extends RuntimeException {}
                """;
        when(fileService.readString("output/src/main/java/com/example/demo/LoanNotFoundException.java"))
                .thenReturn(exJava);

        List<DeferredMutation> nowReady = detector.detectAndClear(
                "cycle-B",
                List.of("src/main/java/com/example/demo/LoanNotFoundException.java"),
                "output");

        assertThat(nowReady).extracting(DeferredMutation::id).containsExactly(d.id());
    }

    @Test
    void mergedSignatureMismatch_doesNotClear() throws Exception {
        CycleContextData ctx = baseContext("cycle-A", "goal", "sub");
        ledger.defer(ctx,
                List.of(new FileMutation("X.java", "x")),
                List.of(MutationBlocker.missingMember("Loan", "setReturned", "boolean")),
                0.7, 0.65);

        // Merge added setReturned(int), not setReturned(boolean) → fingerprints differ.
        String loanJava = """
                public class Loan {
                    public void setReturned(int code) {}
                }
                """;
        when(fileService.readString("output/Loan.java")).thenReturn(loanJava);

        List<DeferredMutation> nowReady = detector.detectAndClear(
                "cycle-B", List.of("Loan.java"), "output");

        assertThat(nowReady).isEmpty();
    }

    @Test
    void nonJavaPath_skipped() throws Exception {
        CycleContextData ctx = baseContext("cycle-A", "goal", "sub");
        ledger.defer(ctx,
                List.of(new FileMutation("X.java", "x")),
                List.of(MutationBlocker.missingType("T")),
                0.7, 0.65);

        // Detector should not even attempt to read pom.xml.
        List<DeferredMutation> nowReady = detector.detectAndClear(
                "cycle-B", List.of("pom.xml"), "output");

        assertThat(nowReady).isEmpty();
    }

    private CycleContextData baseContext(String cycleName, String goalId, String subId) {
        return CycleContextData.builder()
                .cycleName(cycleName)
                .currentGoalId(goalId)
                .currentSubGoalId(subId)
                .lastAttemptId("attempt-" + cycleName)
                .outputPath("output")
                .build();
    }

    private static class FakeGraph implements DeferredMutationGraphService {
        private final Map<String, DeferredMutation> store = new LinkedHashMap<>();
        @Override public void persist(DeferredMutation d) { store.put(d.id(), d); }
        @Override public void updateState(String id, DeferredMutationState s) {
            DeferredMutation cur = store.get(id);
            if (cur != null) store.put(id, cur.withState(s));
        }
        @Override public Set<String> dropBlockersAndFindCleared(Set<String> fps) {
            Set<String> cleared = new HashSet<>();
            for (DeferredMutation d : store.values()) {
                if (!d.state().isOpen()) continue;
                List<MutationBlocker> remaining = new ArrayList<>();
                for (MutationBlocker b : d.blockers()) if (!fps.contains(b.fingerprint())) remaining.add(b);
                if (remaining.size() != d.blockers().size()) {
                    DeferredMutation u = new DeferredMutation(
                            d.id(), d.attemptId(), d.cycleId(),
                            d.intentSubgoalId(), d.intentGoalId(),
                            d.llmScore(), d.heuristicScore(),
                            d.deferredAtCycleIndex(), d.deferredAt(),
                            d.mutationDir(), d.state(),
                            d.touchedFiles(), remaining);
                    store.put(d.id(), u);
                    if (remaining.isEmpty() && u.state() == DeferredMutationState.DEFERRED) {
                        cleared.add(d.id());
                    }
                }
            }
            return cleared;
        }
        @Override public List<DeferredMutation> findOpen() {
            List<DeferredMutation> out = new ArrayList<>();
            for (DeferredMutation d : store.values()) if (d.state().isOpen()) out.add(d);
            return out;
        }
        @Override public List<DeferredMutation> findReady() {
            List<DeferredMutation> out = new ArrayList<>();
            for (DeferredMutation d : store.values())
                if (d.state() == DeferredMutationState.READY_TO_RESTAGE) out.add(d);
            return out;
        }
        @Override public List<DeferredMutation> findByIntentGoal(String g) {
            List<DeferredMutation> out = new ArrayList<>();
            for (DeferredMutation d : store.values())
                if (d.state().isOpen() && g.equals(d.intentGoalId())) out.add(d);
            return out;
        }
        @Override public DeferredMutation findById(String id) { return store.get(id); }
    }
}
