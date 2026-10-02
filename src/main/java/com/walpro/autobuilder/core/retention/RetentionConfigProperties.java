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

    /** Whether old per-session output (logs, snapshots, mutations, responses) is deleted on a schedule. */
    private boolean enabled = false;

    /** Time between two retention passes. */
    private long scheduleDelayMs = 24 * 60 * 60 * 1000L;
    /** Time from startup to the first retention pass. */
    private long scheduleInitialDelayMs = 60 * 60 * 1000L;

    /** The fewest entries each directory keeps, whatever its limits say. */
    private int minKeep = 100;

    /** Retention limits for the logs directory (active days, size in MB, cycles; 0 means no limit). */
    private DirectoryLimits logs = new DirectoryLimits(7, 500, 0);
    /** Retention limits for the snapshots directory (active days, size in MB, cycles; 0 means no limit). */
    private DirectoryLimits snapshots = new DirectoryLimits(14, 0, 1000);
    /** Retention limits for the mutations directory (active days, size in MB, cycles; 0 means no limit). */
    private DirectoryLimits mutations = new DirectoryLimits(14, 0, 1000);
    /** Retention limits for the responses directory (active days, size in MB, cycles; 0 means no limit). */
    private DirectoryLimits responses = new DirectoryLimits(7, 200, 0);

    @Getter
    @Setter
    public static class DirectoryLimits {

        /**
         * Retention window measured in ACTIVE days -- days on which this directory actually
         * received something -- not calendar days. Idle days do not count against it.
         */
        private int maxActiveDays;
        private int maxSizeMb;
        private int maxCycles;

        public DirectoryLimits() {
        }

        public DirectoryLimits(int maxActiveDays, int maxSizeMb, int maxCycles) {
            this.maxActiveDays = maxActiveDays;
            this.maxSizeMb = maxSizeMb;
            this.maxCycles = maxCycles;
        }
    }
}
