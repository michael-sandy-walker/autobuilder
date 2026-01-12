package com.walpro.autobuilder.config;

import com.walpro.autobuilder.agent.meta.MetaOverrideIngestor;
import com.walpro.autobuilder.core.messaging.AgentRoles;
import com.walpro.autobuilder.core.messaging.MessageBus;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class MetaMessagingConfig {

    private final MessageBus messageBus;
    private final MetaOverrideIngestor ingestor;

    @PostConstruct
    public void subscribeMeta() {
        messageBus.subscribe(AgentRoles.META, ingestor);
    }
}
