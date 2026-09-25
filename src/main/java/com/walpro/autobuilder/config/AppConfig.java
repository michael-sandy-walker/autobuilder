package com.walpro.autobuilder.config;

import com.walpro.autobuilder.analysis.structure.SpecEndpointBehaviourQuery;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AppConfig {

    @Bean(name = "fileWatcherExecutor")
    public Executor fileWatcherExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("FileWatcher-");
        executor.initialize();
        return executor;
    }

    /** T5-BR: the specification's per-endpoint statuses -- a pure reader, so it carries no annotation. */
    @Bean
    public SpecEndpointBehaviourQuery specEndpointBehaviourQuery() {
        return new SpecEndpointBehaviourQuery();
    }
}
