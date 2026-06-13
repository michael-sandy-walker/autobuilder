package com.walpro.autobuilder.protocol;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Prompt token budget — the first prompt-budget primitive in the codebase (2026-06-13).
 *
 * <p>Both numbers mirror <em>deployment facts</em>, never behavior judgments: the context
 * window is the deployed model's effective limit (for Ollama, {@code num_ctx} /
 * {@code OLLAMA_CONTEXT_LENGTH} — often far below the model's architectural maximum), and the
 * response reserve is the output room the loop expects mutations to need. Configured under
 * {@code llm.api.*} next to the model they describe; change them when the deployment changes.
 *
 * <p>Token estimation uses the rough ~4-characters-per-token convention. It is a measurement
 * heuristic, not a tokenizer — callers must apply it on both sides of every comparison so the
 * systematic error cancels.
 */
@Component
public class PromptTokenBudget {

    /** Fallbacks when yml omits the keys — keep in sync with application.yml. */
    public static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 32768;
    public static final int DEFAULT_RESPONSE_RESERVE_TOKENS = 4096;
    public static final int DEFAULT_PROMPT_HEADROOM_TOKENS =
            DEFAULT_CONTEXT_WINDOW_TOKENS - DEFAULT_RESPONSE_RESERVE_TOKENS;

    @Value("${llm.api.context-window-tokens:" + DEFAULT_CONTEXT_WINDOW_TOKENS + "}")
    private int contextWindowTokens = DEFAULT_CONTEXT_WINDOW_TOKENS;

    @Value("${llm.api.response-reserve-tokens:" + DEFAULT_RESPONSE_RESERVE_TOKENS + "}")
    private int responseReserveTokens = DEFAULT_RESPONSE_RESERVE_TOKENS;

    /** Tokens available for the user prompt after reserving room for the model's response. */
    public int promptHeadroomTokens() {
        return Math.max(0, contextWindowTokens - responseReserveTokens);
    }

    /** Rough token estimate (~4 chars/token). Use on both sides of a comparison only. */
    public static int estimateTokens(CharSequence text) {
        return text == null ? 0 : (text.length() + 3) / 4;
    }
}
