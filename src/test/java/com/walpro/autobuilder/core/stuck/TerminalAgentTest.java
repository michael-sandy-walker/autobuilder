package com.walpro.autobuilder.core.stuck;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.GrindDetector;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T4-DZ in the loop: the boundary, the agent, and its place as the last authority of the stuck ladder. */
class TerminalAgentTest {

    private static final Path Q1 = Path.of("src/test/resources/t4dz/q1-indexOf-on-map");

    @TempDir
    Path root;

    static Map<String, String> files(Path dir) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> w = Files.walk(dir)) {
            for (Path f : (Iterable<Path>) w.filter(Files::isRegularFile)::iterator) {
                out.put(dir.relativize(f).toString().replace('\\', '/'), Files.readString(f));
            }
        }
        return out;
    }

    static LLMClient modelReplying(String content) {
        LLMClient llm = mock(LLMClient.class);
        ChatResponse r = new ChatResponse();
        ChatResponse.Choice c = new ChatResponse.Choice();
        c.setMessage(new Message("assistant", content));
        r.setChoices(List.of(c));
        when(llm.ask(anyList())).thenReturn(ResponseEntity.ok(r));
        return llm;
    }

    private TerminalAgent agent(LLMClient llm, TerminalAgentRecorder recorder) {
        TerminalAgent a = new TerminalAgent();
        ReflectionTestUtils.setField(a, "llmClient", llm);
        ReflectionTestUtils.setField(a, "recorder", recorder);
        return a;
    }

    @Test
    void theBoundaryRefusesWritesExecutionAndPathsOutsideTheCopy() {
        for (List<String> argv : List.of(
                List.of("sed", "-i", "s/a/b/", "x"), List.of("rm", "-rf", "src"), List.of("find", ".", "-delete"),
                List.of("find", ".", "-fls", "out"), List.of("jar", "cf", "x.jar", "src"), List.of("java", "Main"),
                List.of("mvn", "test"), List.of("javac", "-processor", "p.P", "A.java"), List.of("javac", "-J-javaagent:x", "A.java"),
                List.of("javac", "@args"), List.of("javac", "-d", "out", "A.java"), List.of("cat", "/etc/passwd"),
                List.of("grep", "x", "../../y"))) {
            assertThat(ReadOnlyCopy.refuseReason(argv)).as(String.join(" ", argv)).isNotNull();
        }
        assertThat(ReadOnlyCopy.refuseReason(List.of("sed", "-n", "10,20p", "A.java"))).isNull();
        assertThat(ReadOnlyCopy.refuseReason(List.of("javap", "java.util.Map"))).isNull();
    }

    @Test
    void theCopyCannotBeWrittenAndJavacCompilesOutsideIt() throws Exception {
        try (ReadOnlyCopy copy = ReadOnlyCopy.of(Map.of("src/A.java", "class A { int x = \"no\"; }"), Map.of(), "err")) {
            String before = copy.contentHash();
            assertThat(copy.root().resolve("src").toFile().canWrite()).isFalse();
            ReadOnlyCopy.Result compiled = copy.run(List.of("javac", "src/A.java"));
            assertThat(compiled.output()).contains("incompatible types");
            assertThat(copy.contentHash()).isEqualTo(before);
        }
    }

    @Test
    void theModelProposesTheCommandsRunAndTheOutputIsTheAnswerAndTheGraphRecordsIt() throws Exception {
        LLMClient llm = modelReplying("{\"question\": \"What type is active?\", \"commands\": ["
                + "[\"grep\", \"-n\", \"active\", \"src/main/java/com/walpro/autobuilder/core/lifecycle/ParticipationWindows.java\"],"
                + "[\"rm\", \"-rf\", \"src\"]]}");
        TerminalAgentRecorder recorder = mock(TerminalAgentRecorder.class);

        StuckDiagnosis.Asked asked = agent(llm, recorder).ask("the build fails: x",
                List.of("compiler=" + Files.readString(Q1.resolve("COMPILER_OUTPUT.txt"))),
                files(Q1.resolve("tree")), files(Q1.resolve("candidate")), "c1");

        assertThat(asked.answered()).isTrue();
        assertThat(asked.question()).isEqualTo("What type is active?");
        assertThat(asked.answer()).contains("Observed").contains("Map<String, ParticipationWindow> active")
                .doesNotContain("rm -rf");
        ArgumentCaptor<TerminalAgent.Exchange> ex = ArgumentCaptor.forClass(TerminalAgent.Exchange.class);
        verify(recorder).record(eq("c1"), ex.capture());
        assertThat(ex.getValue().commands()).hasSize(2);
        assertThat(ex.getValue().commands().get(1).refused()).startsWith("not allowed: rm");
        assertThat(ex.getValue().wroteToCopy()).isFalse();
    }

    @Test
    void aReplyWithNothingRunnableIsNoAnswer() throws Exception {
        TerminalAgentRecorder recorder = mock(TerminalAgentRecorder.class);
        StuckDiagnosis.Asked asked = agent(modelReplying("I think you should import java.util.List."), recorder)
                .ask("the build fails: x", List.of(), files(Q1.resolve("tree")), Map.of(), "c1");
        assertThat(asked.answered()).isFalse();
    }

    /**
     * The ladder: structure first -- the declared names, the failing line, the declaring file whole --
     * and only when the failure comes back after all three does the agent run. The archived question
     * no structural lookup covers (a List method called on a Map) is the case.
     */
    @Test
    void theAgentIsAskedOnlyAfterEveryStructuralQuestionAndItsAnswerReachesThePrompt() throws Exception {
        Map<String, String> adopted = files(Q1.resolve("tree"));
        for (var e : adopted.entrySet()) {
            Path b = root.resolve("meta/adoption-baseline/" + e.getKey());
            Files.createDirectories(b.getParent());
            Files.writeString(b, e.getValue());
        }
        LLMClient llm = modelReplying("{\"question\": \"What type is active?\", \"commands\": [[\"grep\", \"-n\", \"record ParticipationWindows\","
                + " \"src/main/java/com/walpro/autobuilder/core/lifecycle/ParticipationWindows.java\"]]}");
        TerminalAgentRecorder recorder = mock(TerminalAgentRecorder.class);
        StuckLayer layer = new StuckLayer();
        ReflectionTestUtils.setField(layer, "fileService", new FileService(root.toString(), new ObjectMapper()));
        ReflectionTestUtils.setField(layer, "terminalAgent", agent(llm, recorder));
        ReflectionTestUtils.setField(layer, "terminalAgentRecorder", recorder);
        GrindDetector detector = new GrindDetector();
        ReflectionTestUtils.setField(detector, "stuckLayer", layer);
        BuildOutcome failed = new BuildOutcome(BuildResult.COMPILE_ERROR, Files.readString(Q1.resolve("COMPILER_OUTPUT.txt")), 1L, "mvn", "t");
        Map<String, String> change = files(Q1.resolve("candidate"));
        CycleContextData ctx = CycleContextData.builder().cycleName("c4").currentSubGoalId("sg").previousOutput(adopted).build();

        for (String c : List.of("c1", "c2", "c3")) {
            layer.beginCycle(c, "sg");
            detector.observeBuild(c, failed, change);
        }
        layer.beginCycle("c4", "sg");
        assertThat(layer.promptBlock(ctx)).as("structure answers first: what the receiver's JDK type declares")
                .contains("Map declares:");
        detector.observeBuild("c4", failed, change); // it came back after the declared names
        assertThat(layer.promptBlock(ctx)).as("the line constructs nothing; the declaring file whole").contains("as it was adopted");
        verify(llm, never()).ask(anyList());

        detector.observeBuild("c4", failed, change); // it came back after the whole file
        String prompt = layer.promptBlock(ctx);

        assertThat(prompt).contains("Observed").contains("public record ParticipationWindows(Map<String, ParticipationWindow> active)");
        verify(recorder).record(eq("c4"), any());
        StuckLedger.Entry e = layer.ledger().entries().stream().filter(x -> x.signature().contains("indexOf")).findFirst().orElseThrow();
        assertThat(e.asked().get(e.asked().size() - 1).authority()).startsWith("observation:");
    }
}
