package com.walpro.autobuilder.core.build.inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * T4-BM discovery source 3: resolves a fully-qualified class name to the coordinates of the
 * artifact that provides it by querying a package-coordinate search index (the target
 * ecosystem's artifact search service; URL is config data, not a frozen literal). Consulted
 * ONLY when discovery already holds an exact FQN from a project observation but the artifact is not
 * on the resolved classpath — the {@code fc:} (fully-qualified class) query is precise there,
 * whereas bare simple-name search would guess among thousands of artifacts (false positives are
 * worse than abstaining; the LLM fallback keeps its place for that case).
 *
 * <p>Degrades gracefully: per-session result cache, hard timeout per query, and a consecutive-
 * failure budget that disables the source for the rest of the session once the network proves
 * unavailable — a run without connectivity pays the timeout at most {@value #FAILURE_BUDGET}
 * times.
 */
@Slf4j
@Component
public class CoordinateSearchClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int FAILURE_BUDGET = 3;

    @Value("${autobuilder.build.discovery.search-enabled:true}")
    @Setter
    private boolean enabled = true;

    @Value("${autobuilder.build.discovery.search-url:https://search.maven.org/solrsearch/select}")
    @Setter
    private String searchUrl = "https://search.maven.org/solrsearch/select";

    @Value("${autobuilder.build.discovery.search-timeout-ms:2500}")
    @Setter
    private long timeoutMs = 2500;

    /** Coordinates of an artifact that provides a queried class. */
    public record Coordinates(String groupId, String artifactId, String version) {}

    private final Map<String, Optional<Coordinates>> cache = new ConcurrentHashMap<>();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /**
     * The coordinates providing {@code fqn}, or empty (no result / disabled / budget exhausted /
     * network failure). Never throws.
     */
    public Optional<Coordinates> findByFullyQualifiedName(String fqn) {
        if (!enabled || fqn == null || fqn.isBlank() || !fqn.contains(".")) return Optional.empty();
        if (consecutiveFailures.get() >= FAILURE_BUDGET) return Optional.empty();
        Optional<Coordinates> cached = cache.get(fqn);
        if (cached != null) return cached;

        Optional<Coordinates> result = query(fqn);
        cache.put(fqn, result);
        return result;
    }

    private Optional<Coordinates> query(String fqn) {
        try {
            String url = searchUrl + "?q=" + URLEncoder.encode("fc:" + fqn, StandardCharsets.UTF_8)
                    + "&rows=5&wt=json";
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(timeoutMs))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                registerFailure("status " + response.statusCode());
                return Optional.empty();
            }
            consecutiveFailures.set(0);
            return parse(response.body(), fqn);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            registerFailure(e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Picks the doc whose {@code groupId} is a prefix of the queried FQN's package — the
     * dominant publishing convention, and the only signal strong enough to act on. No
     * first-doc fallback: {@code fc:} matches every artifact that REPUBLISHES the class, and
     * relevance-rank happily surfaces another ecosystem's compatibility shim (field-observed
     * run 26, 2026-07-08: {@code org.springframework.data.jpa.repository.JpaRepository}
     * resolved to {@code io.quarkus:quarkus-spring-data-jpa-api} and the shim even cleared the
     * compile diagnostic — plausible-but-wrong coordinates that compile are worse than an
     * abstain, because nothing downstream smells them). No prefix match → empty; the caller's
     * fallback chain (registry rows, LLM) takes over.
     */
    static Optional<Coordinates> parse(String body, String fqn) {
        try {
            JsonNode docs = MAPPER.readTree(body).path("response").path("docs");
            if (!docs.isArray() || docs.isEmpty()) return Optional.empty();
            String pkg = fqn.substring(0, fqn.lastIndexOf('.'));
            JsonNode best = null;
            for (JsonNode doc : docs) {
                String g = doc.path("g").asText("");
                if (!g.isBlank() && (pkg.equals(g) || pkg.startsWith(g + "."))) {
                    best = doc;
                    break;
                }
            }
            if (best == null) return Optional.empty();
            String g = best.path("g").asText(null);
            String a = best.path("a").asText(null);
            String v = best.path("latestVersion").asText(null);
            if (v == null || v.isBlank()) v = best.path("v").asText(null);
            if (g == null || g.isBlank() || a == null || a.isBlank()) return Optional.empty();
            return Optional.of(new Coordinates(g, a, (v == null || v.isBlank()) ? null : v));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private void registerFailure(String reason) {
        int failures = consecutiveFailures.incrementAndGet();
        log.debug("T4-BM: coordinate search failed ({}) — {}/{} before source disables for the session",
                reason, failures, FAILURE_BUDGET);
        if (failures == FAILURE_BUDGET) {
            log.info("T4-BM: coordinate search disabled for the session after {} consecutive failures", failures);
        }
    }

    /** Test-only — reset session state. */
    void __resetForTest() {
        cache.clear();
        consecutiveFailures.set(0);
    }
}
