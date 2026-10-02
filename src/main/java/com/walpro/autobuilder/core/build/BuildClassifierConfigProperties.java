package com.walpro.autobuilder.core.build;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * T4-AN-4: configuration for the adaptive {@link BuildErrorClassifierAgent}.
 *
 * <p>{@code adaptive} defaults to {@code false} so existing runs stay on the deterministic
 * T2-CT pattern matcher until the adaptive path is validated in a live session. Setting it
 * true wires the LLM-backed classifier; the pattern matcher remains the fallback when the
 * LLM call fails or returns malformed output.
 *
 * <p>{@code model} is reserved for the per-call model override the ticket describes
 * (classifier is a small structured-output prompt, so a smaller/faster model is appropriate).
 * The current {@link com.walpro.autobuilder.client.LLMClient} has no per-call model selection;
 * the field is plumbed for the follow-up that adds it, and is logged when set so the
 * configuration surface is discoverable without a code reference.
 */
@Configuration
@ConfigurationProperties(prefix = "autobuilder.build.classifier")
@Getter
@Setter
public class BuildClassifierConfigProperties {
    /** Whether build errors are classified by a model call (true) or by the deterministic pattern matcher alone (false); the matcher stays the fallback. */
    private boolean adaptive = false;
    /** Reserved: a model to use for build-error classification; logged when set, not yet applied. */
    private String model;
}
