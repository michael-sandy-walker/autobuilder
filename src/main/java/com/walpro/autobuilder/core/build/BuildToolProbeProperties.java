package com.walpro.autobuilder.core.build;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T4-AN-5: configuration for the build-tool diagnostic probe. All ecosystem knowledge lives here
 * as data (populated from {@code application.yml}), never as literals in framework code — adding a
 * build system is a yaml-only change (see [[feedback-no-hardcoded-ecosystem-data]]).
 *
 * <p>Binds under {@code autobuilder.build}; the nested keys ({@code tool-detection},
 * {@code diagnostics}, {@code diagnostics-enabled}, {@code diagnostic-line-cap},
 * {@code diagnostic-timeout-ms}) are disjoint from the {@code @Value}-read keys
 * ({@code autobuilder.build.enabled/command/...}) in {@link BuildExecutor}, so the two binding
 * styles coexist on the same prefix.
 *
 * <p>{@code diagnosticsEnabled} defaults {@code false} (the Chain-H convention: off in code, turned
 * on via yml for field validation) and the maps default empty, so an unconfigured probe is a no-op.
 */
@Configuration
@ConfigurationProperties(prefix = "autobuilder.build")
@Getter
@Setter
public class BuildToolProbeProperties {

    /** Whether the diagnostic probe runs at all. Off by default; yml turns it on for validation. */
    private boolean diagnosticsEnabled = false;

    /** {@code filename -> toolName} (e.g. {@code pom.xml -> maven}). Detection is by file presence. */
    private Map<String, String> toolDetection = new LinkedHashMap<>();

    /** {@code toolName -> ordered diagnostic command list} (each command is a whitespace-tokenised line). */
    private Map<String, List<String>> diagnostics = new LinkedHashMap<>();

    /** Trailing-line cap on the concatenated diagnostic block — bounds prompt cost. */
    private int diagnosticLineCap = 60;

    /** Per-command timeout. Diagnostics (e.g. {@code dependency:tree}) are slower than {@code compile}. */
    private long diagnosticTimeoutMs = 30_000L;
}
