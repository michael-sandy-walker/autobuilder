package com.walpro.autobuilder.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    private String model;
    private List<Message> messages;
    private double temperature = 0.0;

    /**
     * Optional reasoning budget for models that generate hidden reasoning before their answer
     * ("none" / "low" / "medium" / "high" in the OpenAI-compatible shape). Left off the wire when
     * null, so endpoints and models that do not know the field behave exactly as before and a
     * reasoning-capable model keeps its own default.
     *
     * <p>It exists because the budget is a deployment cost, not a model property: measured on a
     * ~9.3k-token verifier prompt, the same answer cost 19-21 s with reasoning left at the model's
     * default and 1.8 s with it suppressed, the difference being ~700-850 hidden tokens.
     */
    @JsonProperty("reasoning_effort")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String reasoningEffort;

    /** The output cap ({@code OutputBounds}); left off the wire when null. */
    @JsonProperty("max_tokens")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer maxTokens;

    /** Set only on a runaway's changed retry ({@code OutputBounds#RUNAWAY_VARIANTS}); left off the wire when null. */
    @JsonProperty("frequency_penalty")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double frequencyPenalty;

    public ChatRequest(String model, List<Message> messages, double temperature, String reasoningEffort) {
        this(model, messages, temperature, reasoningEffort, null, null);
    }
}
