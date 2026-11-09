package com.walpro.autobuilder.observability.narration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.client.NarratorChannel;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * T4-EM: the narrator replayed on real runs' cycles, against the live model, before any field run. Opt-in:
 * {@code -Dt4em.replay=<dir of behaviour .ndjson files, one JSON event per line>} {@code -Dt4em.out=<file>}.
 * Writes each cycle's record and what the narrator said, for checking every telling against its record.
 */
@EnabledIfSystemProperty(named = "t4em.replay", matches = ".+")
class CycleNarratorReplayLiveTest {

    @Test
    @SuppressWarnings("unchecked")
    void narrateRealCycles() throws Exception {
        NarratorChannel channel = new NarratorChannel(true,
                System.getProperty("t4em.url", "http://localhost:11434/v1/chat/completions"), "",
                System.getProperty("t4em.model", "qwen3.8"), 300_000, "none", 32768, 512, null, null);
        BehaviourLogger logger = mock(BehaviourLogger.class);
        List<String> said = new ArrayList<>();
        List<Long> took = new ArrayList<>();
        doAnswer(inv -> { said.add(inv.getArgument(1)); took.add(inv.getArgument(2)); return null; })
                .when(logger).cycleNarrated(any(), any(), anyLong(), any());
        CycleNarrator narrator = new CycleNarrator(channel, logger, mock(ObjectProvider.class));
        ObjectMapper mapper = new ObjectMapper();
        StringBuilder out = new StringBuilder();
        List<Path> files;
        try (Stream<Path> s = Files.list(Path.of(System.getProperty("t4em.replay")))) {
            files = s.filter(p -> p.toString().endsWith(".ndjson")).sorted().toList();
        }
        for (Path file : files) {
            out.append("######## ").append(file.getFileName()).append('\n');
            CycleRecord record = null;
            String cycle = null;
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) continue;
                Map<String, Object> env = mapper.readValue(line, Map.class);
                String c = CycleRecord.cycleOf(env.get("cycleId"));
                if (c == null) continue;
                if (!c.equals(cycle)) { cycle = c; record = new CycleRecord(); }
                // the backfill puts the goal's description on its status change, as the live narrator looks it up
                Object payload = env.get("payload");
                Object goalText = payload instanceof Map<?, ?> m ? m.get("goalDescription") : null;
                boolean root = payload instanceof Map<?, ?> m2 && Boolean.TRUE.equals(m2.get("goalIsRoot"));
                record.add(env, goalText == null ? null : String.valueOf(goalText), root);
                if (!record.ended()) continue;
                if (record.worthTelling()) {
                    String text = record.render();
                    int before = said.size();
                    narrator.tell(cycle, text);
                    out.append("=== ").append(cycle).append('\n').append(text).append('\n')
                            .append(">>> ").append(said.size() > before ? said.get(said.size() - 1) : "(nothing)")
                            .append(said.size() > before ? "   [" + took.get(took.size() - 1) + " ms]" : "").append("\n\n");
                }
                cycle = null;
            }
        }
        Files.writeString(Path.of(System.getProperty("t4em.out", "target/t4em-replay.txt")), out.toString());
    }
}
