package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.graph.goal.GoalGraphService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import com.walpro.autobuilder.client.dto.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public final class GoalKindClassifier {

    public enum GoalKind { EXECUTABLE, COGNITIVE, HYBRID }

    private static final List<String> COGNITIVE_MARKERS = List.of(
            "code review", "review", "analy", "diagnos", "investigat", "root cause",
            "differential analysis", "compare", "identify issues", "gather information",
            "understand", "assess", "evaluate why", "reason about", "inspect", "triage"
    );

    private static final List<String> EXECUTABLE_MARKERS = List.of(
            "add", "remove", "rename", "refactor", "implement", "fix", "update",
            "create", "delete", "introduce class", "add logging", "add test"
    );

    private final LLMClient llmClient;
    private final GoalGraphService goalGraphService;

    /**
     * Keyword-only classification. Use when no GoalEntity is available (e.g. classifying
     * proposed subgoal strings before they are persisted).
     */
    public GoalKind classify(String text) {
        if (text == null || text.isBlank()) return GoalKind.EXECUTABLE;
        String s = text.toLowerCase();
        boolean cog = COGNITIVE_MARKERS.stream().anyMatch(s::contains);
        boolean exe = EXECUTABLE_MARKERS.stream().anyMatch(s::contains);
        if (cog && exe) return GoalKind.HYBRID;
        if (cog) return GoalKind.COGNITIVE;
        return GoalKind.EXECUTABLE;
    }

    /**
     * Entity-aware classification. Priority order:
     * 1. {@code analysisClassifiedKind} in metadata — set by AnalysisAgent, highest trust.
     * 2. {@code goalKind} in metadata — cached LLM result from a previous cycle.
     * 3. Keyword match — only trusted when cognitive markers are present.
     * 4. LLM fallback — called when cog=false (keyword match is unreliable for broad verbs).
     *    Result cached in {@code goalKind} metadata key and persisted to graph.
     */
    public GoalKind classify(GoalEntity goal) {
        if (goal == null) return GoalKind.EXECUTABLE;

        Map<String, Object> meta = goal.getMetadata();

        GoalKind fromAnalysis = parseKind(meta.get("analysisClassifiedKind"));
        if (fromAnalysis != null) return fromAnalysis;

        GoalKind fromCache = parseKind(meta.get("goalKind"));
        if (fromCache != null) return fromCache;

        String desc = goal.getDescription();
        String s = desc != null ? desc.toLowerCase() : "";
        boolean cog = COGNITIVE_MARKERS.stream().anyMatch(s::contains);
        boolean exe = EXECUTABLE_MARKERS.stream().anyMatch(s::contains);

        if (cog) {
            return (exe) ? GoalKind.HYBRID : GoalKind.COGNITIVE;
        }

        // cog=false: keyword match is unreliable — broad executable verbs tell us nothing
        GoalKind llmResult = callLlm(desc != null ? desc : "");
        meta.put("goalKind", llmResult.name());
        try {
            goalGraphService.save(goal);
        } catch (Exception e) {
            log.warn("Failed to persist LLM kind classification for goal {}: {}", goal.getId(), e.getMessage());
        }
        return llmResult;
    }

    private GoalKind callLlm(String description) {
        List<Message> messages = List.of(
                new Message("system",
                        "You classify software development tasks. " +
                        "Reply with exactly one word — COGNITIVE, EXECUTABLE, or HYBRID — and nothing else. " +
                        "COGNITIVE = analysis/investigation only (no code changes). " +
                        "EXECUTABLE = concrete code change (no investigation required). " +
                        "HYBRID = requires both investigation and code changes."),
                new Message("user", "Classify this task: " + description)
        );
        try {
            ResponseEntity<ChatResponse> resp = llmClient.ask(messages);
            if (resp.getBody() == null) return GoalKind.EXECUTABLE;
            String content = resp.getBody().getFirstContent();
            if (content == null) return GoalKind.EXECUTABLE;
            String upper = content.trim().toUpperCase();
            if (upper.contains("COGNITIVE")) return GoalKind.COGNITIVE;
            if (upper.contains("HYBRID")) return GoalKind.HYBRID;
            return GoalKind.EXECUTABLE;
        } catch (Exception e) {
            log.warn("LLM kind classification failed for '{}', defaulting to EXECUTABLE: {}", description, e.getMessage());
            return GoalKind.EXECUTABLE;
        }
    }

    private static GoalKind parseKind(Object value) {
        if (!(value instanceof String s) || s.isBlank()) return null;
        try {
            return GoalKind.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
