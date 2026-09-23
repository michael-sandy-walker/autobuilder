package com.walpro.autobuilder.core.build.inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.LlmCircuitBreaker;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * T4-AZ: resolves missing Java symbols by asking the LLM for the canonical FQN and Maven
 * coordinates, with the answer memoised forever in {@link SymbolImportCache}. The cache makes
 * the steady-state cost zero — each unique symbol is resolved at most once across the
 * autobuilder's lifetime.
 *
 * <p>The prompt is deliberately ecosystem-neutral: it asks the LLM for a JSON resolution or the
 * literal {@code NONE} sentinel, with no hint about Java, Spring, Maven, or any specific
 * framework. The autobuilder's code never enumerates framework names — every entry comes from
 * the LLM's training knowledge of the ecosystem the target happens to live in.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmSymbolImportLookupService implements SymbolImportLookupService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NONE_SENTINEL = "NONE";

    /**
     * System prompt is written to be safe to apply to any ecosystem (Java/Spring today;
     * Python/Node/Rust if the target retargets). The strict JSON contract + NONE sentinel keeps
     * the parser trivial and prevents the LLM from improvising prose.
     */
    private static final String SYSTEM_PROMPT = """
            You resolve compile-time symbol references to their canonical fully-qualified name and
            (when applicable) the package-manager coordinates that provide them. Reply with ONE of:
              - A JSON object on a single line: {"fqn":"<dotted.path.Symbol>","group":"<group-or-empty>","artifactId":"<artifact-or-empty>","version":"<version-or-empty>"}
              - The literal string NONE (no quotes, no other text) if you are not highly confident
                this symbol is a well-known standard-library or third-party type.
            Rules:
              - Output the answer and nothing else. No prose. No markdown.
              - Pick the most canonical FQN. Standard-library types take precedence over alternatives.
              - Leave group/artifactId/version blank for standard-library types that ship with the
                language runtime; populate them only for third-party libraries.
              - If you are uncertain, return NONE. False positives are worse than false negatives.
            """;

    private final LLMClient llmClient;
    private final LlmCircuitBreaker circuitBreaker;
    private final SymbolImportCache cache;

    @Override
    public Optional<Resolution> lookup(String simpleName) {
        if (simpleName == null || simpleName.isBlank()) return Optional.empty();
        if (!isValidSimpleName(simpleName)) return Optional.empty();

        Optional<Resolution> cached = cache.get(simpleName);
        if (cached != null) {
            // Self-heal poisoned cache entries written before the simple-name invariant existed
            // (observed 2026-06-10: BookNotFoundException cached as Spring's
            // ResourceNotFoundException — an import can never resolve a symbol of a different
            // simple name). Invalidate and fall through to a fresh, invariant-checked lookup.
            if (cached.isPresent() && !matchesSimpleName(cached.get().fqn(), simpleName)) {
                log.warn("LlmSymbolImportLookupService: cached resolution {} does not match symbol {} — invalidating",
                        cached.get().fqn(), simpleName);
                cache.invalidate(simpleName);
            } else {
                return cached;
            }
        }

        if (circuitBreaker.isOpen()) {
            log.debug("LlmSymbolImportLookupService: circuit open ({}); skipping lookup for {}",
                    circuitBreaker.pauseReason(), simpleName);
            return Optional.empty();
        }

        Optional<Resolution> resolved = askLlm(simpleName);
        if (resolved.isPresent()) {
            cache.putHit(simpleName, resolved.get());
        } else {
            cache.putMiss(simpleName);
        }
        return resolved;
    }

    @Override
    public void markResolutionFailed(String simpleName) {
        if (simpleName == null || simpleName.isBlank()) return;
        Optional<Resolution> existing = cache.get(simpleName);
        if (existing != null && existing.isPresent()) {
            // Flip to NONE so we stop emitting the same broken fix.
            cache.putMiss(simpleName);
            log.info("LlmSymbolImportLookupService: invalidated resolution for {} (probe rejected)", simpleName);
        }
    }

    private Optional<Resolution> askLlm(String simpleName) {
        try {
            List<Message> conversation = List.of(
                    new Message("system", SYSTEM_PROMPT),
                    new Message("user", "Resolve symbol: " + simpleName)
            );
            ResponseEntity<ChatResponse> response;
            try (var llmPurpose = com.walpro.autobuilder.client.LlmCallContext.scopeIfUnscoped("symbol-import-lookup")) {
                response = llmClient.ask(conversation);
            }
            String content = response.getBody() != null ? response.getBody().getFirstContent() : null;
            return parseResponse(content, simpleName);
        } catch (RuntimeException e) {
            log.debug("LlmSymbolImportLookupService: LLM call failed for {} — {}", simpleName, e.getMessage());
            return Optional.empty();
        }
    }

    static Optional<Resolution> parseResponse(String content, String simpleName) {
        if (content == null) return Optional.empty();
        String trimmed = content.trim();
        if (trimmed.isEmpty()) return Optional.empty();
        if (NONE_SENTINEL.equals(trimmed)) return Optional.empty();

        // Strip markdown code fence if the LLM ignored the "no markdown" instruction.
        if (trimmed.startsWith("```")) {
            int first = trimmed.indexOf('\n');
            int last = trimmed.lastIndexOf("```");
            if (first > 0 && last > first) {
                trimmed = trimmed.substring(first + 1, last).trim();
            }
        }

        try {
            JsonNode node = MAPPER.readTree(trimmed);
            String fqn = textOrNull(node, "fqn");
            if (fqn == null || fqn.isBlank() || !fqn.contains(".")) {
                // Anything that isn't a dotted FQN is rejected as a probable hallucination.
                return Optional.empty();
            }
            if (!matchesSimpleName(fqn, simpleName)) {
                // The LLM answered with its "closest known thing" instead of NONE. An import of
                // a.b.X can only ever resolve `cannot find symbol: X` — different simple name is
                // a structural impossibility, not a discretionary decision. Treat as NONE.
                return Optional.empty();
            }
            String group = textOrNull(node, "group");
            String artifact = textOrNull(node, "artifactId");
            String version = textOrNull(node, "version");
            return Optional.of(new Resolution(fqn, blankToNull(group), blankToNull(artifact), blankToNull(version)));
        } catch (Exception e) {
            // LLM produced something that isn't JSON or has a hostile shape — treat as NONE.
            return Optional.empty();
        }
    }

    /** The FQN's trailing segment must equal the symbol being resolved. */
    static boolean matchesSimpleName(String fqn, String simpleName) {
        if (fqn == null || simpleName == null) return false;
        int dot = fqn.lastIndexOf('.');
        return simpleName.equals(dot >= 0 ? fqn.substring(dot + 1) : fqn);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        return v.asText();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * A valid Java simple identifier has no dots, no spaces, starts with a letter or underscore,
     * and contains only identifier characters. The classifier produces well-formed tokens but
     * defence-in-depth keeps the LLM from being asked nonsense.
     */
    private static boolean isValidSimpleName(String name) {
        if (name.isEmpty()) return false;
        if (!Character.isJavaIdentifierStart(name.charAt(0))) return false;
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) return false;
        }
        return true;
    }
}
