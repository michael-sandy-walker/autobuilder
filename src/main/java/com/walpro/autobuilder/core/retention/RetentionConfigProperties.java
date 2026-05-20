package com.walpro.autobuilder.core.retention;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * T5-Q: declarative retention for the per-session output directories under
 * {@code file.base-path}. Each directory has its own limits and a minimum-keep
 * floor so a fresh session never deletes its own outputs.
 */
@Configuration
@ConfigurationProperties(prefix = "autobuilder.retention")
@Getter
@Setter
public class RetentionConfigProperties {

    private boolean enabled = false;

    private long scheduleDelayMs = 24 * 60 * 60 * 1000L;
    private long scheduleInitialDelayMs = 60 * 60 * 1000L;

    private int minKeep = 100;

    private DirectoryLimits logs = new DirectoryLimits(7, 500, 0);
    private DirectoryLimits snapshots = new DirectoryLimits(14, 0, 1000);
    private DirectoryLimits mutations = new DirectoryLimits(14, 0, 1000);
    private DirectoryLimits responses = new DirectoryLimits(7, 200, 0);

    @Getter
    @Setter
    public static class DirectoryLimits {

        private int maxAgeDays;
        private int maxSizeMb;
        private int maxCycles;

        public DirectoryLimits() {
        }

        public DirectoryLimits(int maxAgeDays, int maxSizeMb, int maxCycles) {
            this.maxAgeDays = maxAgeDays;
            this.maxSizeMb = maxSizeMb;
            this.maxCycles = maxCycles;
        }
    }
}
