package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
 * failure budget after which the source is skipped for a cooldown and then retried with one
 * query (T1-GF) — an unreachable index costs at most one timeout per cooldown.
 */
@Slf4j
@Component
@StateHolder(value = StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "memo of external registry answers plus live failure backoff")
public class CoordinateSearchClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int FAILURE_BUDGET = 3;

    // Whether a missing dependency is looked up on the Maven Central search service.
    @Value("${autobuilder.build.discovery.search-enabled:true}")
    @Setter
    private boolean enabled = true;

    // The Maven Central search endpoint dependency discovery queries.
    @Value("${autobuilder.build.discovery.search-url:https://search.maven.org/solrsearch/select}")
    @Setter
    private String searchUrl = "https://search.maven.org/solrsearch/select";

    // The timeout of one Maven Central search request.
    @Value("${autobuilder.build.discovery.search-timeout-ms:2500}")
    @Setter
    private long timeoutMs = 2500;

    /** Coordinates of an artifact that provides a queried class. */
    public record Coordinates(String groupId, String artifactId, String version) {}

    /**
     * T4-DH: what the repository says about a coordinate.
     *
     * <p>{@code UNKNOWN} is not a detail. The repair for {@code ABSENT} is deleting a declared
     * dependency, so a failed query must never be read as absence — no network, a rate limit or a
     * changed response shape would otherwise strip a dependency the project needs.
     */
    public enum Availability { PRESENT, ABSENT, UNKNOWN }

    /** Published versions of one {@code groupId:artifactId}, newest first when known. */
    public record ArtifactVersions(Availability availability, List<String> versions) {
        public ArtifactVersions {
            versions = versions == null ? List.of() : List.copyOf(versions);
        }
        static ArtifactVersions unknown() { return new ArtifactVersions(Availability.UNKNOWN, List.of()); }
        static ArtifactVersions absent()  { return new ArtifactVersions(Availability.ABSENT, List.of()); }
        public boolean declaresVersion(String v) { return v != null && versions.contains(v); }
        public Optional<String> newest() { return versions.isEmpty() ? Optional.empty() : Optional.of(versions.get(0)); }
    }

    private final Map<String, Optional<Coordinates>> cache = new ConcurrentHashMap<>();
    private final Map<String, ArtifactVersions> versionCache = new ConcurrentHashMap<>();
    private final Map<String, Availability> publishedCache = new ConcurrentHashMap<>();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /**
     * T1-GF: how long the index is skipped once the failure budget is spent, before ONE query is
     * let through to find out whether it is back. It replaces a disable that lasted the whole
     * session: the index measured flaky, not absent (two 20-second timeouts, then an answer in a
     * third of a second), so one bad minute switched it off for a run that then never asked again.
     * The loop's documented scheduler cadence — a retry at most once a cycle, costing at most one
     * query timeout.
     */
    static final long COOLDOWN_MS = 60_000L;

    @Setter
    private java.util.function.LongSupplier clock = System::currentTimeMillis;
    private volatile long retryAfterMs;

    /**
     * The coordinates providing {@code fqn}, or empty (no result / disabled / budget exhausted /
     * network failure). Never throws.
     */
    public Optional<Coordinates> findByFullyQualifiedName(String fqn) {
        if (!enabled || fqn == null || fqn.isBlank() || !fqn.contains(".")) return Optional.empty();
        if (cooling()) return Optional.empty();
        Optional<Coordinates> cached = cache.get(fqn);
        if (cached != null) return cached;

        Optional<Coordinates> result = query(fqn);
        // T1-GF: a lookup that could not be consulted is NOT an answer. Caching it made one
        // transient timeout read as "no artifact provides this class" for the rest of the session.
        if (result != null) cache.put(fqn, result);
        return result == null ? Optional.empty() : result;
    }

    /**
     * T4-DH: the versions the repository publishes for {@code groupId:artifactId}, newest first.
     *
     * <p>This is the question the loop could not ask. When a build fails because a DECLARED
     * dependency will not resolve, the useful facts are whether the artifact exists at all and,
     * if it does, which versions are real — both deterministic, neither needing a model. Measured
     * on run {@code 2026-08-28_03-51-21}: one coordinate did not exist (18 of 29 failures) while
     * another existed with the requested version invented, and those two need different repairs.
     */
    public ArtifactVersions versionsOf(String groupId, String artifactId) {
        if (!enabled) return ArtifactVersions.unknown();
        if (groupId == null || groupId.isBlank() || artifactId == null || artifactId.isBlank()) {
            return ArtifactVersions.unknown();
        }
        if (cooling()) return ArtifactVersions.unknown();
        String key = groupId + ":" + artifactId;
        ArtifactVersions cached = versionCache.get(key);
        if (cached != null) return cached;

        String q = "g:\"" + groupId + "\" AND a:\"" + artifactId + "\"";
        Optional<String> body = queryBody(q, 60, "&core=gav");
        ArtifactVersions result = body.map(CoordinateSearchClient::parseVersions)
                                      .orElseGet(ArtifactVersions::unknown);
        versionCache.put(key, result);
        return result;
    }

    /**
     * Versions newest-first. Ordered by publish timestamp rather than by parsing the version
     * strings: version ORDERING is ecosystem semantics that differs per project (qualifiers,
     * date stamps, {@code -rc-} suffixes), while publish time is a fact the repository states.
     */
    static ArtifactVersions parseVersions(String body) {
        try {
            JsonNode response = MAPPER.readTree(body).path("response");
            JsonNode docs = response.path("docs");
            if (!docs.isArray()) return ArtifactVersions.unknown();
            if (response.path("numFound").asInt(-1) == 0 || docs.isEmpty()) return ArtifactVersions.absent();
            List<JsonNode> ordered = new ArrayList<>();
            docs.forEach(ordered::add);
            ordered.sort(Comparator.comparingLong((JsonNode d) -> d.path("timestamp").asLong(0L)).reversed());
            List<String> versions = new ArrayList<>();
            for (JsonNode doc : ordered) {
                String v = doc.path("v").asText(null);
                if (v != null && !v.isBlank() && !versions.contains(v)) versions.add(v);
            }
            return versions.isEmpty() ? ArtifactVersions.absent()
                                      : new ArtifactVersions(Availability.PRESENT, versions);
        } catch (Exception e) {
            // A shape we cannot read is not evidence of absence.
            return ArtifactVersions.unknown();
        }
    }

    /**
     * T4-DL: does ANY published artifact contain this exact class? Tri-state for the same reason
     * {@link #versionsOf} is — the consumer acts on ABSENT by telling the loop to remove an import,
     * so a rate limit or a dropped network must never be read as "this API does not exist".
     *
     * <p>Measured need: run 2026-08-28_05-27-49 wrote {@code import com.sun.opengl.util.GLCapabilities}
     * in nine responses. That is JOGL 1.x, unpublished under that name for over a decade. The loop
     * already discovered as much — the coordinate search returned nothing — and then said nothing,
     * so the model kept the invented API and the renderer stayed a placeholder comment.
     */
    public Availability publishesClass(String fqn) {
        if (!enabled || fqn == null || fqn.isBlank() || !fqn.contains(".")) return Availability.UNKNOWN;
        if (cooling()) return Availability.UNKNOWN;
        Availability cached = publishedCache.get(fqn);
        if (cached != null) return cached;
        Availability result = queryBody("fc:" + fqn, 1, "")
                .map(CoordinateSearchClient::parsePresence)
                .orElse(Availability.UNKNOWN);
        publishedCache.put(fqn, result);
        return result;
    }

    static Availability parsePresence(String body) {
        try {
            JsonNode response = MAPPER.readTree(body).path("response");
            int found = response.path("numFound").asInt(-1);
            if (found < 0) return Availability.UNKNOWN;
            return found == 0 ? Availability.ABSENT : Availability.PRESENT;
        } catch (Exception e) {
            return Availability.UNKNOWN;
        }
    }

    /**
     * T1-GF: constrained by groupId, then versioned separately.
     *
     * <p>An unconstrained {@code fc:} query ranks every artifact that REPUBLISHES the class, one
     * document per version, so a popular class's top rows are other projects' shims —
     * {@code JpaRepository}'s top five were all {@code io.quarkus} — and the prefix filter in
     * {@link #parse} then abstained. The filter's own convention (a groupId is a prefix of the
     * package) is asked of the index instead: each segment-aligned prefix of the package, longest
     * first, as an exact {@code g:} constraint. Those are the only groups {@link #parse} would
     * accept anyway, so this cannot select anything it would have rejected.
     *
     * <p>The version is then asked as its own question ({@link #versionsOf}, newest first by
     * publish time): the {@code fc:} rows are per version and in no useful order, and the first
     * accepted one for {@code SpringApplication} was a 2015 release.
     *
     * @return the answer, or {@code null} when the index could not be consulted at all
     */
    private Optional<Coordinates> query(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        if (lastDot <= 0) return Optional.empty();
        String pkg = fqn.substring(0, lastDot);
        for (String group : groupCandidates(pkg)) {
            Optional<String> body = queryBody("fc:\"" + fqn + "\" AND g:\"" + group + "\"", 1, "");
            if (body.isEmpty()) {
                log.info("T1-GF: coordinate search for {} could not be consulted — abstaining, not cached", fqn);
                return null;
            }
            Optional<Coordinates> hit = parse(body.get(), fqn);
            if (hit.isEmpty()) continue;
            Coordinates c = hit.get();
            // No version rather than the hit row's own: those rows are per version and in no
            // useful order. An absent version is filled by the local repository, then by the
            // resolver's own version question.
            String version = preferredVersion(versionsOf(c.groupId(), c.artifactId())).orElse(null);
            Coordinates chosen = new Coordinates(c.groupId(), c.artifactId(), version);
            log.info("T1-GF: coordinate search resolved {} -> {}:{}:{}", fqn,
                    chosen.groupId(), chosen.artifactId(), chosen.version());
            return Optional.of(chosen);
        }
        log.info("T1-GF: coordinate search found no artifact whose group prefixes the package of {}", fqn);
        return Optional.empty();
    }

    /** Segment-aligned prefixes of {@code pkg} with at least two segments, longest first. */
    static List<String> groupCandidates(String pkg) {
        List<String> out = new ArrayList<>();
        String[] parts = pkg.split("\\.");
        for (int n = parts.length; n >= 2; n--) {
            out.add(String.join(".", java.util.Arrays.copyOfRange(parts, 0, n)));
        }
        return out;
    }

    /** {@link VersionSuitability} over what the index publishes; empty when it could not say. */
    static Optional<String> preferredVersion(ArtifactVersions versions) {
        if (versions == null || versions.availability() != Availability.PRESENT) return Optional.empty();
        return VersionSuitability.choose(versions.versions(), null);
    }

    /** Shared transport: one request, one failure-budget decision. Empty = could not be consulted. */
    private Optional<String> queryBody(String q, int rows, String extraParams) {
        try {
            String url = searchUrl + "?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
                    + "&rows=" + rows + "&wt=json" + extraParams;
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
            return Optional.of(response.body());
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

    void registerFailure(String reason) {
        int failures = consecutiveFailures.incrementAndGet();
        log.debug("T4-BM: coordinate search failed ({}) — {}/{} before the source cools down",
                reason, failures, FAILURE_BUDGET);
        if (failures >= FAILURE_BUDGET) {
            retryAfterMs = clock.getAsLong() + COOLDOWN_MS;
            log.info("T1-GF: coordinate search unavailable after {} consecutive failures — skipped for {} s, then retried",
                    failures, COOLDOWN_MS / 1000);
        }
    }

    /** True while the spent budget's cooldown runs; afterwards one query is let through. */
    boolean cooling() {
        return consecutiveFailures.get() >= FAILURE_BUDGET && clock.getAsLong() < retryAfterMs;
    }

}
