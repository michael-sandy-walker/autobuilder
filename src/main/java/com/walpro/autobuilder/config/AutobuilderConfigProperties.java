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
}
