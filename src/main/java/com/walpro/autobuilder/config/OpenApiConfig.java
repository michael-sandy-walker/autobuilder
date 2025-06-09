package com.walpro.autobuilder.config;

import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI apiInfo() {
        return new OpenAPI()
            .info(new Info()
                .title("Autobuilder File API")
                .description("File read/write/upload + watcher events")
                .version("v1.0"));
    }
}
