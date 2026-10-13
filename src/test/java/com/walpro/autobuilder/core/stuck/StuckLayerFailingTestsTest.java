package com.walpro.autobuilder.core.stuck;

import com.walpro.autobuilder.core.build.TestRunGate;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T6-T: a set of failing tests is followed as it changes. Ladder-grind, four runs (2026-10-10/11): each took one
 * trace, always of the same test, while two to four kept failing; and a trace stayed in the prompt after its test
 * had begun to pass.
 */
class StuckLayerFailingTestsTest {

    private static final List<String> EVIDENCE = List.of(
            "LedgerTest.totals -- expected 3 but was 2",
            "QuillTest.dries -- expected true",
            "WellTest.fills -- expected 9");
    private static final Map<String, String> TREE = Map.of("src/main/java/org/sample/Quill.java", "class Quill {}");
    private static final Map<String, String> LATER_TREE = Map.of("src/main/java/org/sample/Quill.java", "class Quill { int nib; }");

    private static StuckLayer layerWhereFail(String... names) {
        StuckLayer layer = new StuckLayer();
        TestRunGate gate = mock(TestRunGate.class);
        when(gate.treeFailing()).thenReturn(Optional.of(Set.of(names)));
        ReflectionTestUtils.setField(layer, "testRunGate", gate);
        return layer;
    }

    private static void traced(StuckLayer layer, String test, Map<String, String> tree, long at) {
        @SuppressWarnings("unchecked")
        Map<String, Integer> on = (Map<String, Integer>) ReflectionTestUtils.getField(layer, "tracedOnTree");
        @SuppressWarnings("unchecked")
        Map<String, Long> when = (Map<String, Long>) ReflectionTestUtils.getField(layer, "tracedAt");
        on.put(test, tree.hashCode());
        when.put(test, at);
    }

    private static String name(Optional<StuckLayer.FailingTest> t) {
        return t.map(f -> f.testClass() + "." + f.method()).orElse(null);
    }

    @Test
    void theTraceGoesToATestNotTracedYetAndSkipsOneThatPassesNow() {
        StuckLayer layer = layerWhereFail("LedgerTest.totals", "WellTest.fills");   // QuillTest.dries passes now

        assertThat(name(layer.nextToTrace(EVIDENCE, TREE))).isEqualTo("LedgerTest.totals");
        traced(layer, "LedgerTest.totals", TREE, 1);
        assertThat(name(layer.nextToTrace(EVIDENCE, TREE))).as("the next one, not the first again").isEqualTo("WellTest.fills");
        traced(layer, "WellTest.fills", TREE, 2);
        assertThat(layer.nextToTrace(EVIDENCE, TREE)).as("each traced on this very tree: nothing new to say").isEmpty();
    }

    @Test
    void whenEachWasTracedTheOneWhoseTraceIsOfAnOlderTreeAndOldestIsTakenAgain() {
        StuckLayer layer = layerWhereFail("LedgerTest.totals", "QuillTest.dries", "WellTest.fills");
        traced(layer, "LedgerTest.totals", TREE, 2);
        traced(layer, "QuillTest.dries", TREE, 1);
        traced(layer, "WellTest.fills", LATER_TREE, 3);

        assertThat(name(layer.nextToTrace(EVIDENCE, LATER_TREE))).isEqualTo("QuillTest.dries");
    }

    @Test
    void aTraceIsShownOnlyWhileItsTestStillFails() {
        StuckLedger ledger = new StuckLedger();
        StuckObservation o = new StuckObservation(StuckObservation.Source.TEST_FAILURES,
                "the same tests keep failing: LedgerTest.totals, QuillTest.dries", "sg", "c1", EVIDENCE);
        ledger.observe(o);
        StuckLedger.Entry e = ledger.pending().get(0);
        String ledgerTrace = "The failing test LedgerTest.totals was run again under the debugger. It failed with: 2";
        String quillTrace = "The failing test QuillTest.dries was run again under the debugger. It failed with: false";
        ledger.recordAsked(e, new StuckDiagnosis.Asked("q", "observation", ledgerTrace, false), "c1", "sg");
        ledger.observe(new StuckObservation(o.source(), o.signature(), "sg", "c2", EVIDENCE));
        ledger.recordAsked(e, new StuckDiagnosis.Asked("q", "observation", quillTrace, false), "c2", "sg");

        assertThat(StuckLayer.shownAnswer(e, Optional.of(Set.of("LedgerTest.totals", "QuillTest.dries"))))
                .as("both still fail: both traces, the latest first").isEqualTo(quillTrace + "\n" + ledgerTrace);
        assertThat(StuckLayer.shownAnswer(e, Optional.of(Set.of("QuillTest.dries")))).isEqualTo(quillTrace);
        assertThat(StuckLayer.shownAnswer(e, Optional.of(Set.of("WellTest.fills"))))
                .as("every test it traced passes now").isNull();
        assertThat(StuckLayer.shownAnswer(e, Optional.empty())).as("the tree's tests unknown: nothing is withheld")
                .contains(ledgerTrace).contains(quillTrace);
    }

    /**
     * T6-T, the user's "a register of corrected method names" (2026-10-13): what the run already wrote, was
     * corrected on and stopped writing is said in the prompt before it is written again -- one line each, and only
     * while there is room.
     */
    @Test
    void whatWasPutRightEarlierInTheRunIsNamedBeforeItIsWrittenAgain() {
        StuckLayer layer = new StuckLayer();
        StuckLedger ledger = layer.ledger();
        for (String c : List.of("c1", "c2", "c3")) {
            ledger.observe(new StuckObservation(StuckObservation.Source.BUILD, "the build fails: Quill has no getInk()", "sg", c, List.of()));
        }
        assertThat(layer.correctedBlock(10_000)).as("still failing: nothing is corrected yet").isNull();
        ledger.recordAsked(ledger.pending().get(0), new StuckDiagnosis.Asked("q", "structure", "Quill declares: ink().", false), "c3", "sg");
        ledger.buildSucceeded("c4");

        assertThat(layer.correctedBlock(10_000)).contains("CORRECTED EARLIER IN THIS RUN", "  - Quill has no getInk()")
                .doesNotContain("the build fails:");
        assertThat(layer.correctedBlock(5)).as("no room for a line: nothing, never a heading alone").isNull();
    }
}
