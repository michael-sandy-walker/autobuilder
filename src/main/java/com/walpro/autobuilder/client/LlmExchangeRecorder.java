package com.walpro.autobuilder.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * T4-DQ slice 2: records what the model said, so a run can be replayed exactly.
 *
 * <p>Two reasons this exists, and the second stands on its own. The first is the round-trip proof:
 * checkpoint at N, run to M, rewind to N, feed back the SAME answers, and any state the checkpoint
 * failed to capture shows up as the loop taking a different path. Without pinned answers a
 * divergence proves nothing, because the model could simply have said something else.
 *
 * <p>The second is that deterministic replay of a live run is worth having regardless. Small live
 * batches under measured variance cannot settle anything; a recorded run replayed against a changed
 * build can.
 *
 * <p><b>Keyed by prompt, not by call order.</b> A replay that hands out answers in sequence would
 * silently mis-pair them the moment the loop asked one fewer question, and would then be measuring
 * the mis-pairing rather than the state. Keying on the prompt's digest means a question the
 * recording does not contain is reported as exactly what it is: the loop, in the restored state,
 * asking something it did not ask before, which is a divergence and the whole point of the check.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@StateHolder(value = StateBucket.ALREADY_PERSISTED,
        note = "the recording is the durable home — llm-recordings/exchanges.ndjson; the in-memory "
                + "map is an index loaded from it")
public class LlmExchangeRecorder {

    /** Relative to {@code file.base-path}. */
    public static final String RECORDING_PATH = "llm-recordings/exchanges.ndjson";
    static final String RECORDING_DIR = "llm-recordings";

    /** Separates conversation turns inside the digested prompt. */
    private static final String TURN_SEPARATOR = "\n<<turn>>\n";

    /** What the recorder does on each call. */
    public enum Mode {
        /** Neither record nor replay — the default; the loop calls the model as always. */
        OFF,
        /** Call the model and write down what it said. */
        RECORD,
        /** Answer from the recording; a question it does not contain is a divergence. */
        REPLAY
    }

    private final FileService fileService;
    private final ObjectMapper objectMapper;

    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    @Setter
    // Whether model exchanges are recorded or replayed: OFF (call the model), RECORD (call it and keep what it said) or REPLAY (answer from the recording).
    @Value("${autobuilder.replay.mode:OFF}")
    private Mode mode = Mode.OFF;

    /**
     * In REPLAY, whether an unrecorded prompt fails the call (strict) or falls through to the real
     * model (lenient). Strict is right for the round-trip proof — a divergence should stop the
     * replay at the point it happened. Lenient is right for replaying a run against a changed build,
     * where some divergence is the thing being measured and the run should keep going.
     */
    @Setter
    @Value("${autobuilder.replay.strict:false}")
    private boolean strict = false;

    /** promptDigest to recorded response content. Loaded lazily from the file. */
    private final Map<String, String> index = new HashMap<>();
    private boolean indexLoaded;

    public Mode mode() {
        return mode;
    }

    public boolean recording() {
        return mode == Mode.RECORD;
    }

    public boolean replaying() {
        return mode == Mode.REPLAY;
    }

    public boolean strict() {
        return strict;
    }

    /** The digest a conversation is keyed by — the same prompt always produces the same key. */
    public static String promptDigest(List<Message> conversation) {
        if (conversation == null) return sha256("<empty>");
        StringBuilder sb = new StringBuilder();
        for (Message m : conversation) {
            sb.append(m.getRole()).append(' ').append(m.getContent()).append(TURN_SEPARATOR);
        }
        return sha256(sb.toString());
    }

    /** Write down what the model said for this prompt. Never throws into the LLM call path. */
    public synchronized void record(String role, String cycleId, String promptDigest, ChatResponse response) {
        if (mode != Mode.RECORD || promptDigest == null || response == null) return;
        String content = response.getFirstContent();
        if (content == null) return;
        try {
            loadIndex();
            fileService.ensureFolderExists(RECORDING_DIR);
            Exchange exchange = new Exchange(promptDigest, role, cycleId, content, Instant.now());
            // One line per exchange, explicitly: the injected mapper indents by default, which
            // would make the recording a stream of pretty-printed objects rather than a log.
            fileService.writeString(RECORDING_PATH,
                    objectMapper.writer()
                            .without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                            .writeValueAsString(exchange) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            index.put(promptDigest, content);
        } catch (IOException | RuntimeException e) {
            log.warn("T4-DQ: could not record the LLM exchange for {}: {}", promptDigest, e.getMessage());
        }
    }

    /**
     * The recorded answer to this prompt, or empty when the recording does not contain it — which
     * during a replay is a divergence, and is reported as one.
     */
    public synchronized Optional<ChatResponse> replay(String role, String cycleId, String promptDigest) {
        if (mode != Mode.REPLAY) return Optional.empty();
        loadIndex();
        String content = index.get(promptDigest);
        if (content == null) {
            log.warn("T4-DQ: replay divergence — role={} asked a prompt the recording does not contain ({}).",
                    role, promptDigest);
            if (behaviourLogger != null) {
                behaviourLogger.replayDivergence(cycleId, role, promptDigest,
                        strict ? "refused" : "fell through to the live model");
            }
            return Optional.empty();
        }
        return Optional.of(responseOf(content));
    }

    /** How many exchanges the recording holds. */
    public synchronized int recordedExchanges() {
        loadIndex();
        return index.size();
    }

    /** Drop the loaded index so the next read re-reads the file. */
    public synchronized void reload() {
        indexLoaded = false;
        index.clear();
    }

    private void loadIndex() {
        if (indexLoaded) return;
        indexLoaded = true;
        try {
            if (!fileService.exists(RECORDING_PATH)) return;
            String content = fileService.readString(RECORDING_PATH);
            if (content.isBlank()) return;
            // Concatenated-value read for the same reason the transition log uses one: it recovers
            // a recording however it was written.
            try (var values = objectMapper.readerFor(Exchange.class).readValues(content)) {
                while (values.hasNextValue()) {
                    Exchange exchange = (Exchange) values.nextValue();
                    index.put(exchange.promptDigest(), exchange.response());
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("T4-DQ: could not read the LLM recording: {}", e.getMessage());
        }
    }

    static ChatResponse responseOf(String content) {
        ChatResponse response = new ChatResponse();
        ChatResponse.Choice choice = new ChatResponse.Choice();
        Message message = new Message();
        message.setRole("assistant");
        message.setContent(content);
        choice.setMessage(message);
        choice.setFinishReason("stop");
        response.setChoices(List.of(choice));
        return response;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** One recorded call: what was asked (by digest), by whom, and what came back. */
    public record Exchange(String promptDigest, String role, String cycleId, String response, Instant at) {}
}
