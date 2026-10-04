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
 * <p>The maps default empty, so an unconfigured probe is a no-op.
 */
@Configuration
@ConfigurationProperties(prefix = "autobuilder.build")
@Getter
@Setter
public class BuildToolProbeProperties {

    /** {@code filename -> toolName} (e.g. {@code pom.xml -> maven}). Detection is by file presence.
     *  Insertion (yaml) order matters: the first entry is the primary ecosystem for
     *  {@link FileRoleClassifier}'s descriptor fallback. */
    private Map<String, String> toolDetection = new LinkedHashMap<>();

    /** {@code toolName -> ordered diagnostic command list} (each command is a whitespace-tokenised line). */
    private Map<String, List<String>> diagnostics = new LinkedHashMap<>();

    /** T4-AU: {@code toolName -> generated-output directory names} (root-segment match). */
    private Map<String, List<String>> outputDirectories = new LinkedHashMap<>();

    /** T4-AU: {@code toolName -> test directory markers} (path-segment prefix match). */
    private Map<String, List<String>> testDirectories = new LinkedHashMap<>();

    /** T4-AU: {@code toolName -> resource directory markers} (path-segment prefix match). */
    private Map<String, List<String>> resourceDirectories = new LinkedHashMap<>();

    /** T4-AU: extensions that mark a compilable source file. */
    private List<String> sourceExtensions = List.of();

    /** Trailing-line cap on the concatenated diagnostic block — bounds prompt cost. */
    private int diagnosticLineCap = 60;

    /** Per-command timeout. Diagnostics (e.g. {@code dependency:tree}) are slower than {@code compile}. */
    private long diagnosticTimeoutMs = 30_000L;
}
