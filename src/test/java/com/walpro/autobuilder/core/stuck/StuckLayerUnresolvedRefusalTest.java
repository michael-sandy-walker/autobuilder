package com.walpro.autobuilder.core.stuck;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.investigate.EvidenceReader;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4-EU: a closure refusal that keeps coming back is first named, then has the lines it is about read and set
 * beside it, and -- when it comes back after that -- pauses the run with the diagnosis request as the reason.
 * The shape is the pantry case of 2026-11-12 (a check saying "none asserts a value" about a test that did),
 * with invented names.
 */
class StuckLayerUnresolvedRefusalTest {

    private static final String REFUSAL = "closing 'the shelf' was refused: Shelf.nameOf is called only in"
            + " QuillSpecTest.namesTheFirstQuill, which assert only assertThat -- none asserts a value it returns";
    private static final String MODEL_SAYS = "The lines compare the value with a literal; the check misreads them.";

    @TempDir
    Path root;

    private StuckLayer layer;
    private BehaviourLogger events;
    private EvidenceReader reader;

    @BeforeEach
    void wire() {
        layer = new StuckLayer();
        events = mock(BehaviourLogger.class);
        reader = mock(EvidenceReader.class);
        ReflectionTestUtils.setField(layer, "fileService", new FileService(root.toString(), new ObjectMapper()));
        ReflectionTestUtils.setField(layer, "behaviourLogger", events);
        ReflectionTestUtils.setField(layer, "evidenceReader", reader);
        when(reader.read(eq(REFUSAL), any(), any())).thenReturn(new EvidenceReader.Reading(
                List.of(new EvidenceReader.Place("src/test/java/quill/QuillSpecTest.java", 4, 4,
                        List.of("        assertThat(shelf.nameOf(1)).contains(\"Q-0001\");"))),
                MODEL_SAYS, List.of("#Probe find nameOf")));
    }

    private void refused(String cycle) {
        layer.beginCycle(cycle, "sg-shelf");
        layer.report(StuckObservation.Source.CLOSURE_REFUSAL, REFUSAL, List.of("Shelf.nameOf(id) is covered by a test"));
    }

    private String prompt(String cycle) {
        return layer.promptBlock(CycleContextData.builder().cycleName(cycle).currentSubGoalId("sg-shelf")
                .outputPath(root.toString()).previousOutput(Map.of()).build());
    }

    @Test
    void theLinesAreSetBesideTheRefusal_thenTheRunPausesWhenItStillComesBack() {
        for (String c : List.of("cycle-1", "cycle-2", "cycle-3")) refused(c);
        assertThat(prompt("cycle-4")).contains("The requirement(s) it refused on");
        assertThat(layer.takePauseRequest()).isEmpty();

        refused("cycle-4");                                   // back after the requirement was named
        String second = prompt("cycle-5");
        assertThat(second).contains("none asserts a value it returns")
                .contains("src/test/java/quill/QuillSpecTest.java:4-4")
                .contains("4:         assertThat(shelf.nameOf(1)).contains(\"Q-0001\");")
                .doesNotContain(MODEL_SAYS);                   // a model's reading never reaches a prompt
        verify(events).refusalEvidenceRead(eq("cycle-5"), eq(REFUSAL), any(), eq(List.of("src/test/java/quill/QuillSpecTest.java:4-4")),
                eq(MODEL_SAYS), anyLong());
        assertThat(layer.takePauseRequest()).isEmpty();        // one more attempt, with the lines in front of it

        refused("cycle-5");                                   // back after the lines were read: nothing left to ask
        verify(events).diagnosisRequest(eq("cycle-5"), eq("CLOSURE_REFUSAL"), eq(REFUSAL), anyInt(), eq("cycle-1"),
                eq("every question was asked; none ended it"), any(), any());
        assertThat(layer.takePauseRequest()).contains(StuckLayer.PAUSE_REASON);
        assertThat(layer.takePauseRequest()).isEmpty();        // taken once
        assertThat(layer.find(new StuckObservation(StuckObservation.Source.CLOSURE_REFUSAL, REFUSAL, null, null, List.of()))
                .status()).isEqualTo(StuckLedger.Status.UNANSWERED);
    }

    @Test
    void withNoLinesFound_theOperatorIsAsked_andTheNextReturnPauses() {
        when(reader.read(eq(REFUSAL), any(), any())).thenReturn(new EvidenceReader.Reading(List.of(), null, List.of()));
        for (String c : List.of("cycle-1", "cycle-2", "cycle-3")) refused(c);
        prompt("cycle-4");
        refused("cycle-4");
        prompt("cycle-5");                                    // the second question has no answer: the request goes out
        verify(events).diagnosisRequest(any(), eq("CLOSURE_REFUSAL"), eq(REFUSAL), anyInt(), any(), any(), any(), any());
        assertThat(layer.takePauseRequest()).isEmpty();

        refused("cycle-5");
        assertThat(layer.takePauseRequest()).contains(StuckLayer.PAUSE_REASON);
        verify(events, times(1)).diagnosisRequest(any(), any(), any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void aRefusalThatStopsAfterTheLinesWereRead_pausesNothing() {
        for (String c : List.of("cycle-1", "cycle-2", "cycle-3")) refused(c);
        prompt("cycle-4");
        refused("cycle-4");
        prompt("cycle-5");
        layer.beginCycle("cycle-6", "sg-shelf");               // no refusal in cycle 5 or 6
        assertThat(layer.takePauseRequest()).isEmpty();
    }

    @Test
    void onlyAClosureRefusalPauses_theOtherSourcesKeepCycling() {
        for (int i = 1; i <= 6; i++) {
            layer.beginCycle("cycle-" + i, "sg-shelf");
            layer.report(StuckObservation.Source.TEST_FAILURES, "the same tests keep failing: QuillSpecTest.namesTheFirstQuill", List.of());
            layer.report(StuckObservation.Source.FLAT_ROOT, "the root has not moved for half its window", List.of());
            layer.report(StuckObservation.Source.PROTECTED_WRITE, "write to quill.txt refused: protected", List.of());
            prompt("cycle-" + i);
        }
        assertThat(layer.takePauseRequest()).isEmpty();
    }

    @Test
    void switchedOff_theRequestIsMadeAndTheRunGoesOn() {
        ReflectionTestUtils.setField(layer, "pauseOnUnresolvedRefusal", false);
        for (String c : List.of("cycle-1", "cycle-2", "cycle-3")) refused(c);
        prompt("cycle-4");
        refused("cycle-4");
        prompt("cycle-5");
        refused("cycle-5");
        verify(events).diagnosisRequest(any(), eq("CLOSURE_REFUSAL"), any(), anyInt(), any(), any(), any(), any());
        assertThat(layer.takePauseRequest()).isEmpty();
    }
}
