package com.walpro.autobuilder.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

@Configuration
@ConfigurationProperties(prefix = "autobuilder.diff")
@Getter
@Setter
public class DiffConfigProperties {
    /** Folders left out of diff analysis. */
    private List<String> excludeFolders;
    /** File names left out of diff analysis. */
    private List<String> excludeFiles;
}