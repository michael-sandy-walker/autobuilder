package com.walpro.autobuilder.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "autobuilder.evolution")
@Getter
@Setter
public class EvolutionConfigProperties {

    /**
     * Pre-probe coverage-guard kill switch (default true == guard enabled).
     *
     * <p>When false, {@code EvolutionAgent} skips the pre-probe rejection branch entirely;
     * proposals reach the compile probe regardless of whether their missing-symbols set
     * is "addressed." Intended as a falsifiable experiment: probe diagnostics carry richer
     * structured signal (driving T2-DT steering on the next cycle) than guard rejections,
     * so the hypothesis is that the guard is net-negative even when its rejections are
     * "correct." Set via {@code autobuilder.evolution.coverage-guard.enabled: false}.
     */
    private CoverageGuard coverageGuard = new CoverageGuard();

    @Getter
    @Setter
    public static class CoverageGuard {
        private boolean enabled = true;
    }
}
