package com.walpro.autobuilder.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "autobuilder.parser")
public class ParserConfigProperties {
    private String sourceRoot;
}
