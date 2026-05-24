package com.walpro.autobuilder.core.mutation;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.graph.mutation.DeferredMutationGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * T4-BE replay test for the run that motivated the ticket
 * ({@code behaviour-2026-05-23_01-14-39.ndjson}, cycle {@code cycle-1779492075276}):
 *
 * <ol>
 *   <li>Evolution proposes a complete {@code LoanService} implementation that calls
 *       {@code loan.setReturned(true|false)}.</li>
 *   <li>Probe stderr names {@code Loan.setReturned(boolean)} as the missing member.</li>
 *   <li>{@link BlockerExtractor} attributes the rejection to a single
 *       {@link MutationBlocker#missingMember} fingerprint and {@link DeferredMutationLedger#defer}
 *       persists the mutation with state DEFERRED.</li>
 *   <li>Two cycles later, a merge writes {@code setReturned(boolean)} into {@code Loan.java}.
 *       {@link BlockerClearanceDetector} parses the merged file, drops the BLOCKED_BY edge,
 *       flips state to READY_TO_RESTAGE.</li>
 *   <li>{@link DeferredRestageScheduler#scheduleIfReady} picks the candidate up at the next
 *       {@code cycle_start}: intent goal still active → schedule directly; the restager
 *       (mocked here — the integration mvn build is exercised by the broader test suite)
 *       reports a staged context.</li>
 * </ol>
 *
 * <p><b>What this replay proves:</b> the ledger + clearance + scheduler chain wires together
 * end-to-end on the exact symbols that the original incident burned a working LoanService
 * implementation on. The probe / build / LLM evaluation are mocked because they require a
 * standing Maven environment + Neo4j; their own unit tests cover those layers separately.
 */
class Cycle1779492075276ReplayTest {

    private FakeGraph graph;
    private DeferredMutationLedger ledger;
    private FileService fileService;
    private GoalPlannerService planner;
    private BlockerClearanceDetector detector;
    private DeferredMutationRestager restager;
    private DeferredRestageScheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        graph = new FakeGraph();
        ledger = new DeferredMutationLedger(graph);
        fileService = Mockito.mock(FileService.class);
        planner = Mockito.mock(GoalPlannerService.class);
        restager = Mockito.mock(DeferredMutationRestager.class);
        detector = new BlockerClearanceDetector(ledger, fileService);
        scheduler = new DeferredRestageScheduler(ledger, restager, planner, fileService);
        ReflectionTestUtils.setField(scheduler, "refocusThreshold", 0.6);
        ReflectionTestUtils.setField(scheduler, "refocusCooldownCycles", 3);
        ReflectionTestUtils.setField(scheduler, "ttlCycles", 50);
        ReflectionTestUtils.setField(scheduler, "maxOpen", 20);
        when(fileService.exists(anyString())).thenReturn(true);
    }

    @Test
    void replay_recoversLoanServiceImplementationAfterSetReturnedLands() throws Exception {
        // ---- Step 1: cycle-1779492075276 — Evolution proposes the LoanService implementation. ----
        // Faithful subset of the real probe stderr.
        String probeStderr = """
                [ERROR] /src/main/java/com/example/demo/LoanService.java:[47,13] cannot find symbol
                [ERROR]   symbol:   method setReturned(boolean)
                [ERROR]   location: class com.example.demo.Loan
                [ERROR] /src/main/java/com/example/demo/LoanService.java:[70,13] cannot find symbol
                [ERROR]   symbol:   method setReturned(boolean)
                [ERROR]   location: class com.example.demo.Loan
                """;
        Optional<List<MutationBlocker>> extracted = BlockerExtractor.extract(probeStderr);
        assertThat(extracted).isPresent();
        assertThat(extracted.get()).containsExactly(
                MutationBlocker.missingMember("Loan", "setReturned", "boolean")
        );

        // Defer the mutation (mirrors EvaluationAgent.tryDeferRejectedMutation).
        String loanServiceImpl = """
                package com.example.demo;
                // ...full impl that calls loan.setReturned(true|false)...
                """;
        CycleContextData ctx_1779492075276 = ctx("cycle-1779492075276",
                "goal-87f989c9-4250-4e64-9ccf-c5f18761fb21",
                "goal-c444f82e-e2ab-41e2-b5ce-6cf655aaf310");

        DeferredMutation d = ledger.defer(
                ctx_1779492075276,
                List.of(new FileMutation("src/main/java/com/example/demo/LoanService.java", loanServiceImpl)),
                extracted.get(),
                null,        // no LLM score — probe failed before LLM ran
                0.715        // matches the heuristic globalScore from the original cycle log
        );
        assertThat(d).isNotNull();
        assertThat(d.state()).isEqualTo(DeferredMutationState.DEFERRED);

        // ---- Step 2: cycle-1779492111551 — a merge writes setReturned to Loan.java. ----
        // BlockerClearanceDetector parses the merged file and clears the matching blocker.
        String loanJavaWithSetReturned = """
                package com.example.demo;
                public class Loan {
                    private boolean returned;
                    public void setReturned(boolean returned) { this.returned = returned; }
                }
                """;
        when(fileService.readString("output/src/main/java/com/example/demo/Loan.java"))
                .thenReturn(loanJavaWithSetReturned);

        List<DeferredMutation> nowReady = detector.detectAndClear(
                "cycle-1779492111551",
                List.of("src/main/java/com/example/demo/Loan.java"),
                "output");

        assertThat(nowReady).extracting(DeferredMutation::id).containsExactly(d.id());
        assertThat(graph.findById(d.id()).state()).isEqualTo(DeferredMutationState.READY_TO_RESTAGE);

        // ---- Step 3: next cycle_start — scheduler picks up the candidate. ----
        // Intent subgoal still active. We pretend the system is on a sibling subgoal so the
        // candidate is technically out of scope — combined score 0.715 (above 0.6 threshold)
        // and no prior promotion → completion-bias re-focus fires.
        when(planner.getCurrentSubGoalId()).thenReturn("some-other-subgoal");
        when(planner.isInActiveScope("goal-c444f82e-e2ab-41e2-b5ce-6cf655aaf310")).thenReturn(true);
        when(planner.isInActiveScope("some-other-subgoal")).thenReturn(true);
        when(planner.focusOnSubGoal("goal-c444f82e-e2ab-41e2-b5ce-6cf655aaf310"))
                .thenReturn("some-other-subgoal");
        // Restager mock — in real run it would re-probe via Maven and route through eval+merge.
        // Here we assert it gets called with the right candidate.
        when(restager.attemptRestage(any(), any())).thenAnswer(inv -> {
            DeferredMutation candidate = inv.getArgument(0);
            CycleContextData stagedCtx = inv.getArgument(1);
            ledger.markRestaged(candidate.id());
            return DeferredMutationRestager.RestageResult.staged(stagedCtx);
        });

        CycleContextData nextCycleCtx = ctx("cycle-1779492144231",
                "goal-87f989c9-4250-4e64-9ccf-c5f18761fb21",
                "some-other-subgoal");
        CycleContextData scheduled = scheduler.scheduleIfReady(nextCycleCtx);

        // ---- Assertions ----
        assertThat(scheduled).isNotNull();
        // Scope was promoted to the original intent subgoal.
        Mockito.verify(planner).focusOnSubGoal("goal-c444f82e-e2ab-41e2-b5ce-6cf655aaf310");
        // Restager fired.
        Mockito.verify(restager).attemptRestage(any(), any());
        // Final state of the deferred record: RESTAGED (the restage step transitioned it).
        assertThat(graph.findById(d.id()).state()).isEqualTo(DeferredMutationState.RESTAGED);
    }

    @Test
    void replay_inScopeNoRefocusNeeded() {
        // If the system happens to still be on the original subgoal when blockers clear, no
        // scope shift is needed — restager fires immediately.
        Optional<List<MutationBlocker>> extracted = BlockerExtractor.extract("""
                [ERROR] cannot find symbol
                [ERROR]   symbol:   method setReturned(boolean)
                [ERROR]   location: class Loan
                """);
        assertThat(extracted).isPresent();

        DeferredMutation d = ledger.defer(
                ctx("cycle-A", "goal-root", "sub-original"),
                List.of(new FileMutation("LoanService.java", "x")),
                extracted.get(), null, 0.715);
        Set<String> fps = new HashSet<>();
        for (MutationBlocker b : d.blockers()) fps.add(b.fingerprint());
        ledger.recordBlockerClearance("cycle-B", fps);

        when(planner.getCurrentSubGoalId()).thenReturn("sub-original");
        when(planner.isInActiveScope("sub-original")).thenReturn(true);
        when(restager.attemptRestage(any(), any())).thenReturn(
                DeferredMutationRestager.RestageResult.staged(ctx("cycle-C", "goal-root", "sub-original")));

        scheduler.scheduleIfReady(ctx("cycle-C", "goal-root", "sub-original"));

        Mockito.verify(planner, Mockito.never()).focusOnSubGoal(any());
        Mockito.verify(restager).attemptRestage(any(), any());
    }

    private CycleContextData ctx(String cycleName, String goalId, String subId) {
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
