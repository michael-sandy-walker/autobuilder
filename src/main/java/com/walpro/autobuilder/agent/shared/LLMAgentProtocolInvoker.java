package com.walpro.autobuilder.agent.shared;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

@Component
@com.walpro.autobuilder.core.checkpoint.StateHolder(
        value = com.walpro.autobuilder.core.checkpoint.StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "per-role count of consecutive discarded answers and the last reason, for the discard event and the streak warning")
public class LLMAgentProtocolInvoker {

    static final int MAX_ATTEMPTS = 2;

    /** T5-BO: optional, so narrow unit tests need not wire it. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.observability.behaviour.BehaviourLogger behaviourLogger;

    /** Consecutive discards per role; a valid answer resets its role's count. */
    private final java.util.Map<String, Integer> consecutiveDiscards = new java.util.concurrent.ConcurrentHashMap<>();

    /** Why each role's most recent answer was discarded; cleared with its count. */
    private final java.util.Map<String, String> lastMismatch = new java.util.concurrent.ConcurrentHashMap<>();

    public <T> LLMResponseResult<T> callWithValidation(
            LLMClient llmClient,
            String systemPrompt,
            String userPrompt,
            Predicate<String> isValidResponse,
            Function<String, T> parseResponse
    ) {
        return callWithValidation(llmClient, systemPrompt, userPrompt, isValidResponse, parseResponse, null);
    }

    /**
     * As above, with {@code describeMismatch} saying what a rejected answer got wrong, in terms the
     * model can act on. T4-CQ: run 2026-09-24_16-49-56 lost its last twenty minutes to fifteen
     * answers discarded in a row for one layout detail, and each retry was told only that the
     * format did not match -- so the second attempt had nothing to correct against.
     */
    public <T> LLMResponseResult<T> callWithValidation(
            LLMClient llmClient,
            String systemPrompt,
            String userPrompt,
            Predicate<String> isValidResponse,
            Function<String, T> parseResponse,
            Function<String, String> describeMismatch
    ) {
        return callWithValidation(llmClient, systemPrompt, userPrompt, isValidResponse, parseResponse,
                describeMismatch, null);
    }

    /**
     * As above, with {@code consult}: T4-EE -- the model's first reply may be a QUESTION the system answers
     * before the real answer. {@code consult} returns the reply to send back when the content is such a
     * question, or null when it is not; the question and its answer stay in the conversation, and the
     * exchange is not counted as a failed attempt. At most one question per call.
     */
    public <T> LLMResponseResult<T> callWithValidation(
            LLMClient llmClient,
            String systemPrompt,
            String userPrompt,
            Predicate<String> isValidResponse,
            Function<String, T> parseResponse,
            Function<String, String> describeMismatch,
            Function<String, String> consult
    ) {
        String role = com.walpro.autobuilder.client.LlmCallContext.currentRole();
        String content = null;
        String mismatch = null;
        String firstUserMessage = withDiscardStreak(role, userPrompt);
        List<Message> opening = new java.util.ArrayList<>(List.of(
                new Message("system", systemPrompt),
                new Message("user", firstUserMessage)));
        boolean consulted = consult == null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            List<Message> messages = new java.util.ArrayList<>(opening);
            if (attempt > 1) {
                messages.add(new Message("assistant", content));
                messages.add(new Message("user", retryInstruction(mismatch)));
            }

            ChatResponse response = llmClient.ask(messages).getBody();

            content = response != null ? response.getFirstContent() : null;

            if (!consulted && attempt == 1 && content != null) {
                consulted = true;
                String reply;
                try {
                    reply = consult.apply(content);
                } catch (RuntimeException e) {
                    reply = null; // a question nobody could answer must not cost the call
                }
                if (reply != null) {
                    opening.add(new Message("assistant", content));
                    opening.add(new Message("user", reply));
                    attempt--;
                    continue;
                }
            }

            if (content != null && isValidResponse.test(content)) {
                consecutiveDiscards.remove(role);
                lastMismatch.remove(role);
                return LLMResponseResult.success(parseResponse.apply(content), content);
            }
            mismatch = describe(content, describeMismatch);
        }
        reportDiscard(content, mismatch);

        String failureReason = content == null
                ? "LLM response was null"
                : "Response did not match protocol: " + mismatch;

        return LLMResponseResult.failure(content, failureReason);
    }

    /** Past this many discards in a row, the next first attempt is told why before it answers. */
    static final int STREAK_WARNING_AT = 2;

    /**
     * T4-CQ: a streak of discards escalates into the prompt itself. The retry corrects one answer;
     * a role that keeps failing the same way needs the reason BEFORE it writes, not after.
     */
    private String withDiscardStreak(String role, String userPrompt) {
        int n = consecutiveDiscards.getOrDefault(role, 0);
        String why = lastMismatch.get(role);
        if (n < STREAK_WARNING_AT || why == null) return userPrompt;
        return userPrompt + "\n\n## Your previous answers were not usable\n"
                + "Your last " + n + " answers were discarded unread, because " + why + ".\n"
                + "Follow the response format in the instructions exactly.\n";
    }

    static String retryInstruction(String mismatch) {
        return "Your previous response did not match the required format"
                + (mismatch == null || mismatch.isBlank() ? "" : ": " + mismatch) + ". "
                + "Respond ONLY with the exact structured format described in the instructions. "
                + "Do not add any explanation, preamble, or prose.";
    }

    private static String describe(String content, Function<String, String> describeMismatch) {
        if (content == null || content.isBlank()) return "the answer was empty";
        String said = null;
        if (describeMismatch != null) {
            try {
                said = describeMismatch.apply(content);
            } catch (RuntimeException ignored) {
                // A describer that fails must not cost the retry; fall back to quoting the answer.
            }
        }
        if (said != null && !said.isBlank()) return said;
        String first = content.strip().lines().findFirst().orElse("");
        if (first.length() > 120) first = first.substring(0, 120);
        return "the answer began with `" + first + "`, which is not the start the format requires";
    }

    private void reportDiscard(String content, String mismatch) {
        String role = com.walpro.autobuilder.client.LlmCallContext.currentRole();
        int n = consecutiveDiscards.merge(role, 1, Integer::sum);
        if (mismatch != null) lastMismatch.put(role, mismatch);
        if (behaviourLogger == null) return;
        String first = content == null ? "(no answer)" : content.strip().lines().findFirst().orElse("");
        if (first.length() > 120) first = first.substring(0, 120);
        behaviourLogger.llmResponseDiscarded(com.walpro.autobuilder.client.LlmCallContext.currentCycleId(),
                role, mismatch == null ? "did not match the protocol" : mismatch, first, n);
    }
}
