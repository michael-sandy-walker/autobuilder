package com.walpro.autobuilder.agent.meta;

import com.walpro.autobuilder.core.engine.meta.MetaOverrideEvent;
import com.walpro.autobuilder.core.engine.meta.MetaOverrideLearningStore;
import com.walpro.autobuilder.core.engine.meta.MetaSkipPatternKey;
import com.walpro.autobuilder.core.messaging.AgentMessage;
import com.walpro.autobuilder.core.messaging.AgentMessageHandler;
import com.walpro.autobuilder.graph.meta.MetaOverrideGraphProjector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MetaOverrideIngestor implements AgentMessageHandler {

    private final MetaOverrideLearningStore learningStore;
    private final MetaOverrideGraphProjector graphProjector;

    @Override
    public void onMessage(AgentMessage msg) {
        if (msg == null || msg.payload() == null) return;

        Object ev = msg.payload().get("event");
        if (!(ev instanceof MetaOverrideEvent event)) return;

        // 1) learn
        List<Map<String, Object>> skipped = event.skippedSteps();
        if (skipped != null) {
            for (Map<String, Object> s : skipped) {
                String role = String.valueOf(s.get("role"));
                String reason = String.valueOf(s.get("reason"));
                learningStore.record(new MetaSkipPatternKey(event.mode(), role, reason));
            }
        }

        // 2) project
        graphProjector.project(event);
    }
}
