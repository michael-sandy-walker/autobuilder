package com.walpro.autobuilder.core.stuck;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-CT: a stuck signature that earlier runs of the SAME task noticed is asked about at its first sighting
 * here, starting with the question that ended it there; nothing else changes.
 */
class StuckPriorsTest {

    private static final String SIGNATURE = "the build fails: Quill.java: cannot find symbol variable NIB in class Quill";
    private static final String SEED = "Grow the quill library: Quill.nib() returns the nib width.";

    /** An in-memory store: outcomes by task, as the graph keeps them, and every record this run made. */
    static final class Store implements StuckPriors {
        final Map<String, Prior> byTask = new java.util.HashMap<>();
        final List<Recorded> recorded = new ArrayList<>();

        @Override
        public void record(Recorded outcome, String cycleId) {
            recorded.add(outcome);
        }

        @Override
        public Prior priorFor(String taskKey, String source, String signature) {
            return SIGNATURE.equals(signature) ? byTask.getOrDefault(taskKey, Prior.NONE) : Prior.NONE;
        }
    }

    private static StuckObservation build(String cycle) {
        return new StuckObservation(StuckObservation.Source.BUILD, SIGNATURE, "sg", cycle, List.of());
    }

    private static StuckLayer layer(Store store) {
        FileService files = Mockito.mock(FileService.class);
        try {
            Mockito.when(files.exists("bootstrap/seed.txt")).thenReturn(true);
            Mockito.when(files.readString("bootstrap/seed.txt")).thenReturn(SEED);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        StuckLayer layer = new StuckLayer();
        ReflectionTestUtils.setField(layer, "fileService", files);
        ReflectionTestUtils.setField(layer, "priors", store);
        var purpose = com.walpro.autobuilder.goal.purpose.PurposeService.fileOnly(files);
        purpose.recordSeed(SEED, "test");
        ReflectionTestUtils.setField(layer, "purposeService", purpose);
        return layer;
    }

    private static CycleContextData cycle(String name) {
        return CycleContextData.builder().cycleName(name).currentSubGoalId("sg")
                .previousOutput(Map.of("src/main/java/org/ink/Quill.java", "package org.ink;\nclass Quill {\n    int nib() { return 1; }\n}\n"))
                .build();
    }

    @Test
    void theTaskIsTheSeedsHashAndABlankSeedNamesNoTask() {
        assertThat(StuckPriors.taskKey(SEED)).hasSize(64).isEqualTo(StuckPriors.taskKey(SEED));
        assertThat(StuckPriors.taskKey(SEED + " ")).isNotEqualTo(StuckPriors.taskKey(SEED));
        assertThat(StuckPriors.taskKey("  ")).isNull();
        assertThat(StuckPriors.taskKey(null)).isNull();
    }

    @Test
    void aPriorCountsRunsNotRecordsAndItsEndingQuestionIsTheOneThatEndedItMostOften() {
        var p = StuckPriors.Prior.of(List.of(
                new StuckPriors.Row("run-1", 0, StuckPriors.Outcome.RECURRED),
                new StuckPriors.Row("run-1", 1, StuckPriors.Outcome.ENDED),
                new StuckPriors.Row("run-2", 1, StuckPriors.Outcome.ENDED),
                new StuckPriors.Row("run-3", 0, StuckPriors.Outcome.ENDED),
                new StuckPriors.Row("run-4", 2, StuckPriors.Outcome.UNANSWERED)));
        assertThat(p.runs()).isEqualTo(4);
        assertThat(p.endedRuns()).isEqualTo(3);
        assertThat(p.endingRung()).isEqualTo(1);

        assertThat(StuckPriors.Prior.of(List.of(new StuckPriors.Row("a", 2, StuckPriors.Outcome.ENDED),
                new StuckPriors.Row("b", 1, StuckPriors.Outcome.ENDED))).endingRung())
                .as("a tie goes to the earlier question").isEqualTo(1);
        assertThat(StuckPriors.Prior.of(List.of(new StuckPriors.Row("a", 0, StuckPriors.Outcome.UNANSWERED))).endingRung()).isNull();
        assertThat(StuckPriors.Prior.of(List.of()).seenBefore()).isFalse();
    }

    @Test
    void aPrimedSignatureIsAQuestionAtItsFirstSightingStartingWhereItEndedBefore() {
        StuckLedger l = new StuckLedger();
        l.prime(StuckObservation.Source.BUILD, SIGNATURE, new StuckPriors.Prior(2, 1, 1));
        assertThat(l.observe(build("c1"))).isEqualTo(StuckLedger.Change.NOW_PENDING);
        assertThat(l.pending()).singleElement().satisfies(e -> {
            assertThat(e.rung()).isEqualTo(1);
            assertThat(e.prior().runs()).isEqualTo(2);
        });
    }

    @Test
    void anEndingQuestionThisSourceDoesNotHaveIsIgnoredAndAnUnprimedSignatureStillWaitsForItsThirdCycle() {
        StuckLedger l = new StuckLedger();
        l.prime(StuckObservation.Source.BUILD, SIGNATURE, new StuckPriors.Prior(1, 1, 99));
        l.observe(build("c1"));
        assertThat(l.pending()).singleElement().extracting(StuckLedger.Entry::rung).isEqualTo(0);

        StuckLedger fresh = new StuckLedger();
        fresh.prime(StuckObservation.Source.BUILD, SIGNATURE, StuckPriors.Prior.NONE);
        assertThat(fresh.observe(build("c1"))).as("no prior, no priming").isEqualTo(StuckLedger.Change.COUNTED);
    }

    @Test
    void theLayerAsksAtTheFirstSightingAndSaysWhereTheCountComesFrom() {
        String prompt = firstSighting(new StuckPriors.Prior(3, 2, 2)); // question 2: the declaring file, whole
        assertThat(prompt).contains(SIGNATURE, "Quill")
                .contains("(this failure also came up in 3 earlier run(s) of this same task; the answer to this same question ended it in 2 of them)");

        assertThat(firstSighting(new StuckPriors.Prior(3, 2, 0)))
                .as("the question that ended it there found nothing here, so the answer shown is another question's")
                .contains("(this failure also came up in 3 earlier run(s) of this same task; an answer the loop looked up ended it in 2 of them)");
    }

    private static String firstSighting(StuckPriors.Prior prior) {
        Store store = new Store();
        store.byTask.put(StuckPriors.taskKey(SEED), prior);
        StuckLayer layer = layer(store);
        layer.beginCycle("c1", "sg");
        layer.report(StuckObservation.Source.BUILD, SIGNATURE, List.of());
        return layer.promptBlock(cycle("c1"));
    }

    @Test
    void anotherTasksPriorChangesNothing() {
        Store other = new Store();
        other.byTask.put(StuckPriors.taskKey("another seed entirely"), new StuckPriors.Prior(5, 5, 0));
        StuckLayer layer = layer(other);
        layer.beginCycle("c1", "sg");
        layer.report(StuckObservation.Source.BUILD, SIGNATURE, List.of());
        assertThat(layer.promptBlock(cycle("c1"))).isNull();
    }

    @Test
    void howAnEntryTurnedOutIsRecordedUnderTheTask() {
        Store store = new Store();
        StuckLayer layer = layer(store);
        for (String c : List.of("c1", "c2", "c3")) {
            layer.beginCycle(c, "sg");
            layer.report(StuckObservation.Source.BUILD, SIGNATURE, List.of());
        }
        layer.promptBlock(cycle("c3"));
        layer.buildSucceeded("c4");

        assertThat(store.recorded).singleElement().satisfies(r -> {
            assertThat(r.taskKey()).isEqualTo(StuckPriors.taskKey(SEED));
            assertThat(r.signature()).isEqualTo(SIGNATURE);
            assertThat(r.outcome()).isEqualTo(StuckPriors.Outcome.ENDED);
            assertThat(r.question()).isNotBlank();
        });
    }
}
