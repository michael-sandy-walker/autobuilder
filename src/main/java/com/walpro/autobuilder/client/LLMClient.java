package com.walpro.autobuilder.client;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.client.dto.ChatRequest;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class LLMClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private RestTemplate restTemplate;
    private final LlmCircuitBreaker circuitBreaker;

    /** T5-R: token-usage accounting + per-call trace. Optional so unit tests constructing
     *  LLMClient without the full context still work. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @lombok.Setter
    private com.walpro.autobuilder.client.LlmUsageTracker usageTracker;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @lombok.Setter
    private com.walpro.autobuilder.observability.behaviour.BehaviourLogger behaviourLogger;

    /**
     * T4-DQ slice 2: records what the model said, and answers from the recording during a replay.
     * Optional so unit tests constructing LLMClient by hand are unaffected; absent means the client
     * behaves exactly as it always has.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @lombok.Setter
    private LlmExchangeRecorder exchangeRecorder;

    // The model endpoint (OpenAI-compatible chat completions).
    @Value("${llm.api.url}")
    private String apiUrl;

    // The model endpoint's API key, if it asks for one.
    @Value("${llm.api.key}")
    private String apiKey;

    // The model name sent with every request.
    @Value("${llm.api.model}")
    private String model;

    // The longest wait for a model answer; the output controller raises it with the answer's cap.
    @Value("${llm.api.read-timeout-ms:120000}")
    private int readTimeoutMs;

    // Reasoning budget for models that think before answering ("none"/"low"/"medium"/"high").
    // Blank = send nothing, so the model keeps its own default and non-reasoning endpoints are
    // untouched. See ChatRequest#reasoningEffort for the measured cost this controls.
    @Value("${llm.api.reasoning-effort:}")
    private String reasoningEffort;

    // T1-FP: escalate the read timeout across retry attempts so a slow-but-progressing local
    // generation (large prompt / cold model reload) gets a genuine chance to finish on a later
    // attempt instead of timing out at the base on every try and tripping the circuit breaker
    // into an endless pause. factor <= 1.0 reproduces the flat base timeout (opt-in by default).
    @Value("${llm.api.read-timeout-escalation-factor:1.0}")
    private double readTimeoutEscalationFactor;

    // Cap on the escalated read timeout in ms; 0 = uncapped (bounded only by the factor + attempts).
    @Value("${llm.api.read-timeout-max-ms:0}")
    private int readTimeoutMaxMs;

    // How many times a failed model request is tried.
    @Value("${llm.api.retry.max-attempts:3}")
    private int retryMaxAttempts;

    // The wait before retrying a failed model request.
    @Value("${llm.api.retry.delay-ms:5000}")
    private long retryDelayMs;

    // The output room the prompt budget leaves (PromptTokenBudget): the first cap of every role.
    @Value("${llm.api.response-reserve-tokens:4096}")
    private int responseReserveTokens;

    // The context window the model is served with: a cut answer whose prompt and answer reach it filled the room.
    @Value("${llm.api.context-window-tokens:" + com.walpro.autobuilder.protocol.PromptTokenBudget.DEFAULT_CONTEXT_WINDOW_TOKENS + "}")
    private int contextWindowTokens = com.walpro.autobuilder.protocol.PromptTokenBudget.DEFAULT_CONTEXT_WINDOW_TOKENS;

    // Whether answers are capped and runaways retried with a changed request (OutputBounds).
    @Value("${llm.api.output-bounds.enabled:true}")
    private boolean outputBoundsEnabled;

    /** How often a clean answer cut by the cap is asked again with a larger one, per call. */
    static final int MAX_CAP_GROWTHS = 2;

    private OutputBounds outputBounds;

    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(readTimeoutMs);
        restTemplate = new RestTemplate(factory);
        outputBounds = new OutputBounds(responseReserveTokens);
    }

    public ResponseEntity<ChatResponse> ask(List<Message> conversation) {
        return ask(conversation, null);
    }

    /**
     * @param effort this call's reasoning budget in place of the configured one, for a question measured to need it;
     *               null, or an endpoint configured to send none, keeps the configured budget
     */
    public ResponseEntity<ChatResponse> ask(List<Message> conversation, String effort) {
        // T1-FD: fail fast while the circuit is open. Run 4 (2026-07-02): after the breaker
        // opened, REST-path callers kept firing 2-minute-timeout requests for 2+ hours,
        // keeping the local model's queue saturated (the livelock's fuel). An open circuit
        // now rejects immediately on EVERY path; abstain-aware callers defer, and the timed
        // cool-down (see LlmCircuitBreaker) lets the next post-window call probe recovery.
        // Deliberately no recordNetworkFailure here — fast-fails must not extend the window.
        if (circuitBreaker.isOpen()) {
            String reason = circuitBreaker.pauseReason();
            log.warn("LLM call rejected — circuit open ({}). Failing fast.", reason);
            throw new ResourceAccessException("LLM circuit open — failing fast: " + reason);
        }
        // T4-DQ slice 2: during a replay the recording answers, and the endpoint is never called.
        // ONE chokepoint — every agent's call comes through here, so recording and replay need no
        // per-agent plumbing and cannot miss a caller.
        String promptDigest = exchangeRecorder != null ? LlmExchangeRecorder.promptDigest(conversation) : null;
        if (exchangeRecorder != null && exchangeRecorder.replaying()) {
            java.util.Optional<ChatResponse> recorded = exchangeRecorder.replay(
                    LlmCallContext.currentRole(), LlmCallContext.currentCycleId(), promptDigest);
            if (recorded.isPresent()) {
                return ResponseEntity.ok(recorded.get());
            }
            if (exchangeRecorder.strict()) {
                // Strict replay stops AT the divergence: the loop asked something the recorded run
                // never asked, which is the finding, and continuing would measure the fallback
                // rather than the state.
                throw new ResourceAccessException(
                        "T4-DQ strict replay: no recorded answer for prompt " + promptDigest);
            }
        }

        String budget = reasoningEffort == null || reasoningEffort.isBlank() ? null : reasoningEffort.trim();
        ChatRequest request = new ChatRequest(model, conversation, 0.0, budget != null && effort != null ? effort : budget);

        log.debug("Sending request to LLM (core={}):", model);
        for (Message message : conversation) {
            String prefix = switch (message.getRole()) {
                case "system" -> "[SYS]";
                case "user" -> "[USR]";
                case "assistant" -> "[ASSIST]";
                default -> "[???]";
            };
            String content = message.getContent();
            if (message.getRole().equals("system") || message.getRole().equals("assistant")) {
                log.debug("{}\n{}\n{}", prefix, "-----", content);
            } else {
                log.info("{}\n{}\n{}", prefix, "-----", content);
            }
        }

        if (!outputBoundsEnabled || outputBounds == null) {
            return exchange(request, promptDigest, readTimeoutMs).response();
        }
        String role = LlmCallContext.currentRole();
        int variant = -1;
        int growths = 0;
        int contextRescue = -1;
        Integer rescueCap = null;
        Integer room = null;
        while (true) {
            // A runaway's rescue keeps the cap the runaway ran into: that cap says nothing about how long the
            // rescued answer needs, and halving it first cost the live check an extra, cut call (2026-10-01).
            // The halving applies to the role's NEXT call.
            int capTokens = rescueCap != null ? rescueCap : outputBounds.capFor(role);
            rescueCap = null;
            // T6-T: once the prompt's size is known, a cap above the room it leaves is no cap at all.
            if (room != null && room > 0) capTokens = Math.min(capTokens, room);
            request.setMaxTokens(capTokens);
            Exchanged done = exchange(request, promptDigest, outputBounds.timeoutFor(capTokens, readTimeoutMs));
            ChatResponse body = done.response().getBody();
            String finish = body == null || body.getChoices() == null || body.getChoices().isEmpty() ? null
                    : body.getChoices().get(0).getFinishReason();
            String content = body == null ? null : body.getFirstContent();
            Integer completion = body == null || body.getUsage() == null ? null : body.getUsage().getCompletionTokens();
            Integer prompt = body == null || body.getUsage() == null ? null : body.getUsage().getPromptTokens();
            room = OutputBounds.room(contextWindowTokens, prompt);
            OutputBounds.Verdict verdict = OutputBounds.verdict(finish, content, prompt, completion, contextWindowTokens);
            int next = outputBounds.learn(role, verdict, completion, done.durationMs());
            if (verdict == OutputBounds.Verdict.ENDED) return done.response();
            boolean again = switch (verdict) {
                case CUT_CLEAN -> growths++ < MAX_CAP_GROWTHS;
                case RUNAWAY -> variant + 1 < OutputBounds.RUNAWAY_VARIANTS.size();
                case CONTEXT_FULL -> contextRescue + 1 < OutputBounds.CONTEXT_RESCUES.size() && room != null && room > 0;
                case ENDED -> false;
            };
            String retry = !again ? "none left -- the answer is returned as it is"
                    : verdict == OutputBounds.Verdict.CUT_CLEAN ? "the same request with a cap of " + next
                    : verdict == OutputBounds.Verdict.CONTEXT_FULL ? "a note naming the " + room + " tokens left (rescue "
                            + (contextRescue + 2) + " of " + OutputBounds.CONTEXT_RESCUES.size() + ")"
                    : OutputBounds.RUNAWAY_VARIANTS.get(variant + 1).name() + ", cap " + capTokens
                            + " (the role's next call: " + next + ")";
            log.warn("LLM answer {} at {} tokens (role {}, cap {}, prompt {}) -- retry: {}",
                    verdict == OutputBounds.Verdict.RUNAWAY ? "RAN AWAY"
                            : verdict == OutputBounds.Verdict.CONTEXT_FULL ? "FILLED THE CONTEXT" : "was cut clean",
                    completion, role, capTokens, prompt, retry);
            if (behaviourLogger != null) {
                String applied = contextRescue >= 0 ? "context rescue " + (contextRescue + 1)
                        : variant < 0 ? null : OutputBounds.RUNAWAY_VARIANTS.get(variant).name();
                behaviourLogger.llmOutputBounded(LlmCallContext.currentCycleId(), role, capTokens, completion,
                        verdict.name(), applied, next, again ? retry : null);
            }
            if (!again) return done.response();
            if (verdict == OutputBounds.Verdict.CONTEXT_FULL) {
                // An identical request fills the same room again: ask for a smaller answer, saying how small.
                String note = String.format(OutputBounds.CONTEXT_RESCUES.get(++contextRescue), room);
                request.setMessages(withNote(conversation, note));
                continue;
            }
            if (verdict == OutputBounds.Verdict.RUNAWAY) {
                // At temperature 0 a runaway repeats itself on an identical request: change the request.
                OutputBounds.Variant v = OutputBounds.RUNAWAY_VARIANTS.get(++variant);
                rescueCap = capTokens;
                request.setTemperature(v.temperature() == null ? 0.0 : v.temperature());
                request.setFrequencyPenalty(v.frequencyPenalty());
            }
        }
    }

    /** {@code conversation} with {@code note} appended to its last user message (a new list; the original stays). */
    static List<Message> withNote(List<Message> conversation, String note) {
        List<Message> out = new java.util.ArrayList<>(conversation);
        for (int i = out.size() - 1; i >= 0; i--) {
            if ("user".equals(out.get(i).getRole())) {
                out.set(i, new Message("user", out.get(i).getContent() + "\n\n" + note));
                return out;
            }
        }
        out.add(new Message("user", note));
        return out;
    }

    /** One answer and how long its successful attempt took. */
    private record Exchanged(ResponseEntity<ChatResponse> response, long durationMs) {}

    /**
     * Sends {@code request} with the network retries below; {@code baseTimeoutMs} is the first attempt's
     * read timeout (escalated on retries, T1-FP).
     */
    private Exchanged exchange(ChatRequest request, String promptDigest, int baseTimeoutMs) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        HttpEntity<ChatRequest> entity = new HttpEntity<>(request, headers);

        Exception lastException = null;

        // T1-FD (attempt-level breaker counting): every failed attempt is recorded, not one
        // aggregate failure after all retries exhaust. Run 3 (2026-07-03) showed the aggregate
        // form sustains a starvation storm: with a wedged endpoint each call burned
        // retryMaxAttempts × read-timeout (~6 min) before contributing a SINGLE failure, so the
        // threshold took ~48 min to open while the immediate retries kept the endpoint's queue
        // saturated. The attempt is the unit of observation about the endpoint; counting attempts
        // opens the circuit in minutes, and the mid-loop isOpen() check below abandons the
        // remaining attempts the moment this call's own failures (or another thread's) open it.
        for (int attempt = 1; attempt <= retryMaxAttempts; attempt++) {
            int attemptTimeoutMs = readTimeoutForAttempt(
                    baseTimeoutMs, readTimeoutEscalationFactor, attempt,
                    readTimeoutMaxMs <= 0 ? 0 : Math.max(readTimeoutMaxMs, baseTimeoutMs));
            try {
                long callStart = System.currentTimeMillis();
                ResponseEntity<ChatResponse> response = clientForTimeout(attemptTimeoutMs).exchange(
                        apiUrl, HttpMethod.POST, entity, ChatResponse.class);
                long durationMs = System.currentTimeMillis() - callStart;

                circuitBreaker.recordSuccess();
                recordUsage(response.getBody(), durationMs);
                if (exchangeRecorder != null) {
                    exchangeRecorder.record(LlmCallContext.currentRole(),
                            LlmCallContext.currentCycleId(), promptDigest, response.getBody());
                }

                if (response.getBody() != null && response.getBody().getFirstContent() != null) {
                    log.info("[LLM RESPONSE]\n{}\n{}", "-----", response.getBody().getFirstContent());
                } else {
                    log.warn("[LLM RESPONSE] Empty or null");
                }

                return new Exchanged(response, durationMs);

            } catch (HttpStatusCodeException e) {
                int status = e.getStatusCode().value();
                String errorCode = extractErrorCode(e.getResponseBodyAsString());
                circuitBreaker.recordFailure(status, errorCode);
                // Never retry auth failures, quota exhaustion, or rate limits
                if (status == 401 || "insufficient_quota".equals(errorCode) || status == 429) {
                    throw e;
                }
                // 5xx — eligible for retry
                log.warn("LLM HTTP {} on attempt {}/{} — {}",
                        status, attempt, retryMaxAttempts,
                        attempt < retryMaxAttempts ? "retrying" : "giving up");
                lastException = e;

            } catch (ResourceAccessException e) {
                circuitBreaker.recordNetworkFailure();
                log.warn("LLM network/timeout error on attempt {}/{} (readTimeout={}ms): {} — {}",
                        attempt, retryMaxAttempts, attemptTimeoutMs, e.getMessage(),
                        attempt < retryMaxAttempts ? "retrying" : "giving up");
                lastException = e;
            }

            if (circuitBreaker.isOpen()) {
                log.warn("LLM circuit opened after attempt {}/{} — abandoning the remaining retries.",
                        attempt, retryMaxAttempts);
                break;
            }
            if (attempt < retryMaxAttempts) {
                try {
                    Thread.sleep(retryBackoffMs(retryDelayMs, attempt));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (lastException instanceof ResourceAccessException rae) throw rae;
        throw (HttpStatusCodeException) lastException;
    }

    /**
     * T5-R: accumulate this call's token usage (per ambient role + session) and emit an
     * {@code llm_call} trace event. Role/cycle come from {@link LlmCallContext}; token fields are
     * null when the provider omitted the usage block (still counted as a call). Never throws — a
     * telemetry failure must not fail the LLM call.
     */
    private void recordUsage(ChatResponse body, long durationMs) {
        try {
            String role = LlmCallContext.currentRole();
            String cycleId = LlmCallContext.currentCycleId();
            Integer prompt = null;
            Integer completion = null;
            Integer total = null;
            if (body != null && body.getUsage() != null) {
                prompt = body.getUsage().getPromptTokens();
                completion = body.getUsage().getCompletionTokens();
                total = body.getUsage().getTotalTokens();
            }
            if (usageTracker != null) {
                usageTracker.record(role, prompt, completion);
            }
            if (behaviourLogger != null) {
                behaviourLogger.llmCall(cycleId, role, model, prompt, completion, total, durationMs);
            }
        } catch (Exception e) {
            log.debug("T5-R: usage recording failed (ignoring): {}", e.getMessage());
        }
    }

    /** Longest pause between two retry attempts, however high the configured base delay doubles. */
    private static final long MAX_RETRY_BACKOFF_MS = 60_000L;

    /**
     * T1-FD: escalating pause before retry {@code attempt + 1} — the configured base delay,
     * doubled per attempt, capped. A fixed short delay re-queues work against an endpoint that
     * is still busy with the aborted generation (run 3: the client-side timeout abandons the
     * request but the generation keeps computing server-side, so immediate retries stack fresh
     * load onto the backlog that caused the timeout).
     */
    static long retryBackoffMs(long baseDelayMs, int attempt) {
        if (baseDelayMs <= 0) return 0;
        int doublings = Math.min(Math.max(attempt, 1) - 1, 10);
        return Math.min(MAX_RETRY_BACKOFF_MS, baseDelayMs << doublings);
    }

    /**
     * T1-FP: read timeout for a given attempt — the base grown by {@code factor} per prior
     * attempt ({@code base * factor^(attempt-1)}), clamped to {@code capMs} (0 = uncapped) and
     * never below the base. {@code factor <= 1.0} (the default) reproduces the flat base timeout
     * on every attempt, so escalation is opt-in and behaviour-preserving until configured.
     *
     * <p>The escalation gives a LATER retry more time to complete, not an earlier failure — the
     * T1-FD backoff still spaces the attempts, so this does not re-stack load onto an endpoint
     * still computing an abandoned generation. It only rescues a slow-but-progressing generation
     * (large prompt / cold model reload) that would otherwise time out identically every attempt.
     */
    static int readTimeoutForAttempt(int baseMs, double factor, int attempt, int capMs) {
        if (factor <= 1.0 || attempt <= 1) return baseMs;
        double grown = baseMs * Math.pow(factor, attempt - 1);
        long capped = capMs > 0 ? Math.min((long) grown, capMs) : (long) grown;
        return (int) Math.max(baseMs, capped);
    }

    /**
     * T1-FP: the RestTemplate to use for a request needing {@code timeoutMs}. The base timeout
     * reuses the shared client built in {@link #init()}; an escalated timeout gets a per-request
     * client (a local, so concurrent callers never mutate shared state).
     */
    private RestTemplate clientForTimeout(int timeoutMs) {
        if (timeoutMs == readTimeoutMs) return restTemplate;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
    }

    private String extractErrorCode(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return null;
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode code = root.path("error").path("code");
            if (!code.isMissingNode() && !code.isNull()) return code.asText();
            JsonNode type = root.path("error").path("type");
            if (!type.isMissingNode() && !type.isNull()) return type.asText();
        } catch (Exception ignored) {
        }
        return null;
    }
}
