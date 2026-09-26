package com.walpro.autobuilder.core.stuck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.GrindDetector;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * T4-DY slice B's DoD instrument, deterministic half: the participation-totals constructor grind
 * (run 2026-09-25_20-51-35, cut by early_stop at 12 occurrences, the source block not yet built),
 * replayed through the REAL detector and layer from the run's own compiler output and adopted tree.
 * What the run lacked is the fact that its record has seven components, one of them subGoalId; the
 * replay shows that fact reaching the next evolution prompt at the third occurrence, and the ledger
 * recording its delivery -- and, when the failure then stops, that it ended after the delivery.
 */
class StuckLayerReplayTest {

    private static final String FIXTURE = "/t4dy/participation-totals/";
    private static final String PKG = "src/main/java/com/walpro/autobuilder/core/lifecycle/";

    @TempDir
    Path root;

    private GrindDetector detector;
    private StuckLayer layer;
    private BehaviourLogger events;
    private Map<String, String> adopted;

    private static String fixture(String name) throws Exception {
        try (var in = StuckLayerReplayTest.class.getResourceAsStream(FIXTURE + name)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private void wire() throws Exception {
        adopted = new LinkedHashMap<>();
        for (String type : List.of("ChannelRequest", "ParticipationWindow", "ParticipationWindows", "SignalType")) {
            String content = fixture("adopted/" + type + ".java.txt");
            adopted.put(PKG + type + ".java", content);
            Path baseline = root.resolve("meta/adoption-baseline/" + PKG + type + ".java");
            Files.createDirectories(baseline.getParent());
            Files.writeString(baseline, content);
        }
        Files.createDirectories(root.resolve("bootstrap"));
        Files.writeString(root.resolve("bootstrap/seed.txt"), "Add per-channel totals to ParticipationWindows.");
        FileService files = new FileService(root.toString(), new ObjectMapper());
        events = mock(BehaviourLogger.class);
        layer = new StuckLayer();
        ReflectionTestUtils.setField(layer, "fileService", files);
        ReflectionTestUtils.setField(layer, "behaviourLogger", events);
        detector = new GrindDetector();
        ReflectionTestUtils.setField(detector, "stuckLayer", layer);
    }

    private BuildOutcome grindBuild() throws Exception {
        return new BuildOutcome(BuildResult.COMPILE_ERROR, fixture("compile-block.txt"), 1L, "mvn", "probe");
    }

    private CycleContextData promptContext(String cycle) {
        // The tree the evolution prompt saw: the merged state, i.e. the adopted record (the rewrite never merged).
        return CycleContextData.builder().cycleName(cycle).currentSubGoalId("sg-totals").previousOutput(adopted).build();
    }

    private void failingCycle(String cycle) throws Exception {
        layer.beginCycle(cycle, "sg-totals");
        detector.beginCycle(cycle);
        detector.observeBuild(cycle, grindBuild());
    }

    private JsonNode ledgerFile() throws Exception {
        return new ObjectMapper().readTree(root.resolve(StuckLayer.LEDGER_PATH).toFile());
    }

    private static JsonNode entry(JsonNode ledger, String signaturePart) {
        for (JsonNode e : ledger) if (e.get("signature").asText().contains(signaturePart)) return e;
        throw new AssertionError("no ledger entry for " + signaturePart + " in " + ledger);
    }

    @Test
    void theMissingFactReachesTheNextPromptAtTheThirdOccurrenceAndTheLedgerShowsTheGrindEndedAfterIt() throws Exception {
        wire();
        failingCycle("cycle-1");
        failingCycle("cycle-2");
        assertThat(layer.promptBlock(promptContext("cycle-3"))).as("two occurrences are not yet a repetition of one").isNull();
        failingCycle("cycle-3");

        layer.beginCycle("cycle-4", "sg-totals");
        String prompt = layer.promptBlock(promptContext("cycle-4"));

        assertThat(prompt).as("the constructor the model kept calling wrongly, as the record declares it")
                .contains("ChannelRequest is constructed only as: ChannelRequest(SignalType kind, double urgency,"
                        + " String source, String subGoalId, int age, Integer validForCycles, Map<String,Object> meta)");
        assertThat(prompt).as("the component the rewrite dropped")
                .contains("'subGoalId' is declared as: ChannelRequest.subGoalId (record component)");
        assertThat(prompt).as("the imports the rewrite dropped").contains("java.util.HashMap (JDK class)")
                .contains("java.util.LinkedHashMap (JDK class)");
        assertThat(layer.promptBlock(promptContext("cycle-4"), 400)).as("a whole file that does not fit is left out, not cut")
                .contains("left out").doesNotContain("public class ParticipationWindows");
        verify(events, atLeastOnce()).stuckQuestionAsked(eq("cycle-4"), eq("BUILD"), contains("constructor ChannelRequest"),
                eq(0), anyString(), anyString(), eq(true), contains("String subGoalId"), eq("sg-totals"));

        // The next build compiles: the delivered fact ended the grind, and nothing came back after it.
        detector.observeBuild("cycle-4", new BuildOutcome(BuildResult.COMPILE_OK, "", 1L, "mvn", "probe"));

        JsonNode constructorEntry = entry(ledgerFile(), "constructor ChannelRequest");
        assertThat(constructorEntry.get("status").asText()).isEqualTo("ENDED");
        assertThat(constructorEntry.get("deliveredCycle").asText()).isEqualTo("cycle-4");
        assertThat(constructorEntry.get("recurrencesAfterDelivery").asInt()).isZero();
        assertThat(constructorEntry.get("questions").get(0).get("deliveredToSubGoal").asText()).isEqualTo("sg-totals");
        verify(events).stuckEnded(eq("cycle-4"), eq("BUILD"), contains("constructor ChannelRequest"), eq("cycle-4"), eq(0), eq(1));
        verify(events, never()).diagnosisRequest(any(), any(), any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void aRecurrenceAfterTheFactMovesToTheWholeFileAndThenToTheOperator() throws Exception {
        wire();
        for (String c : List.of("cycle-1", "cycle-2", "cycle-3")) failingCycle(c);
        layer.beginCycle("cycle-4", "sg-totals");
        layer.promptBlock(promptContext("cycle-4"));

        detector.observeBuild("cycle-4", grindBuild()); // the move right after the answer failed the same way
        String second = layer.promptBlock(promptContext("cycle-4"));
        assertThat(second).as("the next question, not the same answer again: the declaring file, whole, as adopted")
                .contains("ChannelRequest.java, as it was adopted").contains("public record ChannelRequest(");
        assertThat(second.split("public record ChannelRequest\\(", -1)).as("one file shown once, however many signatures it answers")
                .hasSize(2);
        verify(events, atLeastOnce()).stuckRecurred(eq("cycle-4"), eq("BUILD"), contains("constructor ChannelRequest"), eq(1), eq(1));

        failingCycle("cycle-5");
        layer.beginCycle("cycle-6", "sg-totals");
        layer.promptBlock(promptContext("cycle-6"));

        verify(events).diagnosisRequest(eq("cycle-5"), eq("BUILD"), contains("constructor ChannelRequest"), anyInt(),
                eq("cycle-1"), eq("every question was asked; none ended it"), any(), any());
        assertThat(entry(ledgerFile(), "constructor ChannelRequest").get("status").asText()).isEqualTo("UNANSWERED");
        verify(events, never()).stuckEnded(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void aRefusalAnswerFollowsItsSubgoalAndNotTheLoopIntoOtherWork() throws Exception {
        wire();
        for (String c : List.of("cycle-1", "cycle-2", "cycle-3")) {
            layer.beginCycle(c, "sg-totals");
            layer.report(StuckObservation.Source.CLOSURE_REFUSAL, "closing 'totals' was refused: 1 criterion not confirmed",
                    List.of("totalBoost() returns 0.0 for no windows"));
        }
        assertThat(layer.promptBlock(promptContext("cycle-4"))).contains("totalBoost() returns 0.0 for no windows");
        CycleContextData elsewhere = CycleContextData.builder().cycleName("cycle-5").currentSubGoalId("sg-other")
                .previousOutput(adopted).build();
        assertThat(layer.promptBlock(elsewhere)).isNull();
    }
}
