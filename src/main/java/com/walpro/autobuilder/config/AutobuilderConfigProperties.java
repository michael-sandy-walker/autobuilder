package com.walpro.autobuilder.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@Getter
@Setter
@ConfigurationProperties(prefix = "autobuilder.service")
public class AutobuilderConfigProperties {
    /** Whether startup resets the target when the start mode discards the tree: the lineage is severed, meta/ cleaned and the root goal re-seeded (the graph keeps earlier runs as history). */
    private boolean resetOnStart = false;

    /**
     * T4-CD slice 1: when true AND a preserved artifact from a prior run exists
     * ({@code meta/preserved-artifact/}, written by T4-CA at every pause transition) AND the
     * loaded goal state is resumable (root present, not COMPLETED), the boot resumes that
     * lineage instead of resetting: {@code output/} is restored from the preserved copy, goal
     * state is kept and re-grounded, and {@code reset-on-start} is skipped. When no resumable
     * state exists the boot falls through to the configured fresh-start path, so both flags can
     * be passed on every launch ("resume if possible, else fresh").
     */
    private boolean resumeFromPreserved = false;
}
