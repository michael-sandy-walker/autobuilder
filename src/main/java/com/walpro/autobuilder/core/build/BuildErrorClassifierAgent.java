package com.walpro.autobuilder.core.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.LlmCircuitBreaker;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T4-AN-4: adaptive build-error classifier. The primary path issues a small structured-output
 * LLM call that returns a build-tool-agnostic {@link BuildErrorClassifier.Category} plus
 * optional structured details (missing artifact / symbol / suggested fix). The fallback path
 * is the existing T2-CT pattern matcher in {@link BuildErrorClassifier#classify(String)}.
 *
 * <p>The category is the technology-agnostic structural commitment — it does not encode
 * Maven, Gradle, npm, etc. Every build error is fundamentally either "the build configuration
 * is wrong" ({@code CONFIG_FIXABLE}) or "the source code is wrong" ({@code SOURCE_FIXABLE})
 * or both ({@code MIXED}); the LLM has training-data exposure to every major build ecosystem
 * so new technologies work without enumerating their error catalogues in autobuilder code.
 *
 * <p>Cache by {@code SHA256(stderr)} skips the LLM call on stderr-identical retries (the
 * common case during build-fix loops). In-memory only; the optional Neo4j {@code
 * BuildClassification} node mentioned in the ticket is a follow-up — out of scope for the
 * initial ship.
 *
 * <p>This bean is safe to call when {@link BuildClassifierConfigProperties#isAdaptive()} is
 * {@code false}: it short-circuits to {@link BuildErrorClassifier#classify(String)} without
 * consulting the LLM. The pattern fallback now populates {@link BuildErrorClassifier.Category}
 * from {@link BuildErrorClassifier.Kind} via {@link BuildErrorClassifier#defaultCategoryFor},
 * so downstream consumers see a usable category either way.
 */
@Slf4j
@Service
public class BuildErrorClassifierAgent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            You classify build-tool error output. Reply with ONE single-line JSON object:
              {"category":"<CONFIG_FIXABLE|SOURCE_FIXABLE|MIXED|UNKNOWN>",
               "summaryLine":"<one short sentence>",
               "missingArtifact":"<group:artifact[:version] or empty>",
               "missingSymbol":"<simple identifier or empty>",
               "suggestedFix":"<one short sentence or empty>"}
            Rules:
              - Output the JSON object and nothing else. No prose. No markdown. No code fences.
              - "category" partitions the error by what needs to change:
                  CONFIG_FIXABLE  — only the build configuration needs to change (dependency,
                                    plugin version, project layout) — source untouched.
                  SOURCE_FIXABLE  — only source code needs to change (missing class, missing
                                    member, wrong type, wrong import) — config untouched.
                  MIXED           — both need to change (e.g. a missing class that also requires
                                    a new dependency to import).
                  UNKNOWN         — the error is informational, the tool itself failed, or the
                                    cause is not derivable from the stderr.
              - Populate missingArtifact only when CONFIG_FIXABLE / MIXED and the artifact is
                deducible. Use the build-tool's coordinate format (Maven: group:artifact:version,
                npm: package@version, etc.).
              - Populate missingSymbol only when SOURCE_FIXABLE / MIXED and a single bare
                identifier is the root cause. Leave empty for multi-symbol errors.
              - "suggestedFix" is one short imperative sentence the LLM consumer can act on.
              - If you are uncertain, return category="UNKNOWN" with a brief summary. False
                category confidence is worse than honest UNKNOWN.
            """;

    private final LLMClient llmClient;
    private final LlmCircuitBreaker circuitBreaker;
    private final BuildClassifierConfigProperties config;
    private final ConcurrentHashMap<String, BuildErrorClassifier.Classification> cache = new ConcurrentHashMap<>();

    public BuildErrorClassifierAgent(LLMClient llmClient, LlmCircuitBreaker circuitBreaker,
                                     BuildClassifierConfigProperties config) {
        this.llmClient = llmClient;
        this.circuitBreaker = circuitBreaker;
        this.config = config;
        if (config.getModel() != null && !config.getModel().isBlank()) {
            log.info("BuildErrorClassifierAgent: model override '{}' configured but per-call model selection " +
                    "is not yet supported by LLMClient — using the default model. File a follow-up to plumb " +
                    "per-call model selection through ChatRequest.", config.getModel());
        }
    }

    /**
     * Classify a build failure. Returns the pattern-matcher result when adaptive mode is off,
     * the circuit breaker is open, the input is blank, or the LLM call fails/returns malformed
     * output. Otherwise returns the LLM's adaptive classification, cached by SHA-256(stderr).
     */
    public BuildErrorClassifier.Classification classify(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return BuildErrorClassifier.classify(stderr);
        }
        if (!config.isAdaptive()) {
            return BuildErrorClassifier.classify(stderr);
        }

        String cacheKey = sha256(stderr);
        BuildErrorClassifier.Classification cached = cache.get(cacheKey);
        if (cached != null) return cached;

        if (circuitBreaker.isOpen()) {
            log.debug("BuildErrorClassifierAgent: circuit open ({}); falling back to pattern matcher",
                    circuitBreaker.pauseReason());
            return BuildErrorClassifier.classify(stderr);
        }

        BuildErrorClassifier.Classification adaptive = askLlm(stderr);
        if (adaptive == null) return BuildErrorClassifier.classify(stderr);

        cache.put(cacheKey, adaptive);
        return adaptive;
    }

    private BuildErrorClassifier.Classification askLlm(String stderr) {
        try {
            List<Message> conversation = List.of(
                    new Message("system", SYSTEM_PROMPT),
                    new Message("user", stderr)
            );
            ResponseEntity<ChatResponse> response = llmClient.ask(conversation);
            String content = response.getBody() != null ? response.getBody().getFirstContent() : null;
            return parseResponse(content, stderr);
        } catch (RuntimeException e) {
            log.debug("BuildErrorClassifierAgent: LLM call failed — {}", e.getMessage());
            return null;
        }
    }

    static BuildErrorClassifier.Classification parseResponse(String content, String stderr) {
        if (content == null) return null;
        String trimmed = content.trim();
        if (trimmed.isEmpty()) return null;

        // Strip a markdown fence if the LLM ignored the instruction.
        if (trimmed.startsWith("```")) {
            int first = trimmed.indexOf('\n');
            int last = trimmed.lastIndexOf("```");
            if (first > 0 && last > first) {
                trimmed = trimmed.substring(first + 1, last).trim();
            }
        }

        try {
            JsonNode node = MAPPER.readTree(trimmed);
            String categoryStr = textOrNull(node, "category");
            if (categoryStr == null || categoryStr.isBlank()) return null;
            BuildErrorClassifier.Category category;
            try {
                category = BuildErrorClassifier.Category.valueOf(categoryStr.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return null;
            }

            // Kind stays load-bearing for the deterministic resolver chain. Re-derive it from the
            // existing pattern matcher so the adaptive classification remains a strict superset of
            // the fallback — the LLM tags the structural category, the regex matchers stay the
            // source of truth for which deterministic resolver fires.
            BuildErrorClassifier.Classification fallback = BuildErrorClassifier.classify(stderr);

            return new BuildErrorClassifier.Classification(
                    fallback.kind(),
                    fallback.criteria(),
                    category,
                    blankToNull(textOrNull(node, "summaryLine")),
                    blankToNull(textOrNull(node, "missingArtifact")),
                    blankToNull(textOrNull(node, "missingSymbol")),
                    blankToNull(textOrNull(node, "suggestedFix"))
            );
        } catch (Exception e) {
            return null;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        return v.asText();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by every Java distribution — unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    int cacheSize() {
        return cache.size();
    }
}
