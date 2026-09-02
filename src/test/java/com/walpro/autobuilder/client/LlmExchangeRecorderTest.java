package com.walpro.autobuilder.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * T4-DQ slice 2: without pinned answers a divergence proves nothing — the model could simply have
 * said something else. These are the properties the pinning rests on.
 */
class LlmExchangeRecorderTest {

    @TempDir
    Path tmp;

    /** The mapper the application injects, indentation and all — see the transition-log test. */
    private final ObjectMapper mapper = new com.walpro.autobuilder.config.JacksonConfig().objectMapper();
    private FileService fileService;

    @BeforeEach
    void setUp() {
        fileService = new FileService(tmp.toString(), mapper);
    }

    @Test
    @DisplayName("what was recorded comes back for the same prompt")
    void recordedAnswersComeBack() {
        LlmExchangeRecorder recorder = recorder(LlmExchangeRecorder.Mode.RECORD);
        List<Message> conversation = conversation("write the controller");
        String digest = LlmExchangeRecorder.promptDigest(conversation);
        recorder.record("evolution", "cycle-1", digest, LlmExchangeRecorder.responseOf("here it is"));

        LlmExchangeRecorder replayer = recorder(LlmExchangeRecorder.Mode.REPLAY);
        ChatResponse replayed = replayer.replay("evolution", "cycle-9", digest).orElseThrow();

        assertThat(replayed.getFirstContent()).isEqualTo("here it is");
    }

    @Test
    @DisplayName("the same prompt digests the same way; a different one does not")
    void digestIsAPropertyOfThePrompt() {
        assertThat(LlmExchangeRecorder.promptDigest(conversation("a")))
                .isEqualTo(LlmExchangeRecorder.promptDigest(conversation("a")))
                .isNotEqualTo(LlmExchangeRecorder.promptDigest(conversation("b")));
    }

    @Test
    @DisplayName("a prompt the recording does not contain is reported as a divergence, not guessed at")
    void unrecordedPromptIsADivergence() {
        LlmExchangeRecorder recorder = recorder(LlmExchangeRecorder.Mode.RECORD);
        recorder.record("evolution", "cycle-1",
                LlmExchangeRecorder.promptDigest(conversation("the one it asked")),
                LlmExchangeRecorder.responseOf("answered"));

        BehaviourLogger behaviour = mock(BehaviourLogger.class);
        LlmExchangeRecorder replayer = recorder(LlmExchangeRecorder.Mode.REPLAY);
        replayer.setBehaviourLogger(behaviour);

        String unseen = LlmExchangeRecorder.promptDigest(conversation("something else entirely"));
        assertThat(replayer.replay("evolution", "cycle-9", unseen)).isEmpty();
        verify(behaviour).replayDivergence(eq("cycle-9"), eq("evolution"), eq(unseen), anyString());
    }

    @Test
    @DisplayName("OFF neither records nor answers — the default leaves the client as it was")
    void offModeIsInert() {
        LlmExchangeRecorder recorder = recorder(LlmExchangeRecorder.Mode.OFF);
        String digest = LlmExchangeRecorder.promptDigest(conversation("anything"));
        recorder.record("evolution", "cycle-1", digest, LlmExchangeRecorder.responseOf("x"));

        assertThat(recorder.recordedExchanges()).isZero();
        assertThat(recorder.replay("evolution", "cycle-1", digest)).isEmpty();
    }

    @Test
    @DisplayName("each exchange is one line under the injected pretty-printing mapper")
    void eachExchangeIsOneLine() throws java.io.IOException {
        LlmExchangeRecorder writer = recorder(LlmExchangeRecorder.Mode.RECORD);
        writer.record("planner", "cycle-1", LlmExchangeRecorder.promptDigest(conversation("a")),
                LlmExchangeRecorder.responseOf("first"));
        writer.record("planner", "cycle-2", LlmExchangeRecorder.promptDigest(conversation("b")),
                LlmExchangeRecorder.responseOf("second\nwith a newline in it"));

        assertThat(java.nio.file.Files.readAllLines(tmp.resolve(LlmExchangeRecorder.RECORDING_PATH)))
                .hasSize(2);
        assertThat(recorder(LlmExchangeRecorder.Mode.REPLAY)
                .replay("planner", "cycle-9", LlmExchangeRecorder.promptDigest(conversation("b"))))
                .get()
                .extracting(com.walpro.autobuilder.client.dto.ChatResponse::getFirstContent)
                .isEqualTo("second\nwith a newline in it");
    }

    @Test
    @DisplayName("the recording survives a fresh reader over the same file")
    void recordingIsDurable() {
        LlmExchangeRecorder writer = recorder(LlmExchangeRecorder.Mode.RECORD);
        writer.record("planner", "cycle-1",
                LlmExchangeRecorder.promptDigest(conversation("plan it")),
                LlmExchangeRecorder.responseOf("the plan"));

        assertThat(recorder(LlmExchangeRecorder.Mode.REPLAY).recordedExchanges()).isEqualTo(1);
    }

    private LlmExchangeRecorder recorder(LlmExchangeRecorder.Mode mode) {
        LlmExchangeRecorder recorder = new LlmExchangeRecorder(fileService, mapper);
        recorder.setMode(mode);
        return recorder;
    }

    private static List<Message> conversation(String userContent) {
        return List.of(new Message("system", "you are the evolution agent"),
                new Message("user", userContent));
    }
}
