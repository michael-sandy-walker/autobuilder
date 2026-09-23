package com.walpro.autobuilder.core.build.inject;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-BM source 3: response parsing + doc-pick preference. Network behavior (timeouts, failure
 * budget) is exercised only through the parse/disable seams — no test opens a socket.
 */
class CoordinateSearchClientTest {

    @Test
    void parse_prefersDocWhoseGroupPrefixesTheQueriedPackage() {
        String body = """
                {"response":{"docs":[
                  {"g":"com.shaded.everything","a":"uber-jar","latestVersion":"9.9"},
                  {"g":"org.apache.commons","a":"commons-lang3","latestVersion":"3.14.0"}
                ]}}
                """;
        Optional<CoordinateSearchClient.Coordinates> c =
                CoordinateSearchClient.parse(body, "org.apache.commons.lang3.StringUtils");
        assertThat(c).isPresent();
        assertThat(c.get().groupId()).isEqualTo("org.apache.commons");
        assertThat(c.get().artifactId()).isEqualTo("commons-lang3");
        assertThat(c.get().version()).isEqualTo("3.14.0");
    }

    @Test
    void parse_noGroupPrefixesThePackage_abstains() {
        // Run-26 field lesson: fc: matches every artifact that REPUBLISHES a class, and the
        // first (relevance-ranked) doc was another ecosystem's compatibility shim
        // (JpaRepository -> io.quarkus:quarkus-spring-data-jpa-api) whose dep even cleared
        // the compile diagnostic. Without the group-prefix signal, abstain — the registry
        // fallback and LLM know better than a guess that happens to compile.
        String body = """
                {"response":{"docs":[
                  {"g":"io.other","a":"other-lib","latestVersion":"1.2"},
                  {"g":"io.second","a":"second-lib","latestVersion":"2.0"}
                ]}}
                """;
        assertThat(CoordinateSearchClient.parse(body, "org.example.Widget")).isEmpty();
    }

    @Test
    void parse_groupPrefixMustBeSegmentAligned() {
        // "org.spring" must not claim "org.springframework.data..." — prefix match is on
        // whole dot-segments.
        String body = """
                {"response":{"docs":[
                  {"g":"org.spring","a":"not-it","latestVersion":"1.0"}
                ]}}
                """;
        assertThat(CoordinateSearchClient.parse(body, "org.springframework.data.jpa.repository.JpaRepository"))
                .isEmpty();
    }

    @Test
    void parse_emptyDocsOrGarbage_abstains() {
        assertThat(CoordinateSearchClient.parse("{\"response\":{\"docs\":[]}}", "a.b.C")).isEmpty();
        assertThat(CoordinateSearchClient.parse("not json at all", "a.b.C")).isEmpty();
        assertThat(CoordinateSearchClient.parse("{}", "a.b.C")).isEmpty();
        assertThat(CoordinateSearchClient.parse(
                "{\"response\":{\"docs\":[{\"latestVersion\":\"1.0\"}]}}", "a.b.C"))
                .as("doc without coordinates is unusable")
                .isEmpty();
    }

    @Test
    void findByFqn_disabledOrMalformedInput_abstainsWithoutNetwork() {
        CoordinateSearchClient client = new CoordinateSearchClient();
        client.setEnabled(false);
        assertThat(client.findByFullyQualifiedName("org.example.Widget")).isEmpty();

        client.setEnabled(true);
        assertThat(client.findByFullyQualifiedName(null)).isEmpty();
        assertThat(client.findByFullyQualifiedName("NoDotsHere")).isEmpty();
    }

    // ── T1-GF: groupId-constrained query, version asked separately ──────────────────────────

    @Test
    void groupCandidates_areSegmentAlignedPrefixes_longestFirst_neverASingleSegment() {
        assertThat(CoordinateSearchClient.groupCandidates("org.springframework.data.jpa.repository"))
                .containsExactly("org.springframework.data.jpa.repository", "org.springframework.data.jpa",
                        "org.springframework.data", "org.springframework");
        assertThat(CoordinateSearchClient.groupCandidates("org")).isEmpty();
    }

    @Test
    void preferredVersion_takesTheNewestPlainRelease_overANewerQualifiedOne() {
        var versions = new CoordinateSearchClient.ArtifactVersions(CoordinateSearchClient.Availability.PRESENT,
                java.util.List.of("4.0.0-M2", "3.5.3", "3.5.2", "1.2.8.RELEASE"));

        assertThat(CoordinateSearchClient.preferredVersion(versions)).contains("3.5.3");
    }

    @Test
    void preferredVersion_fallsBackToTheNewest_whenNoPlainReleaseExists_andAbstainsOnUnknown() {
        var qualifiedOnly = new CoordinateSearchClient.ArtifactVersions(CoordinateSearchClient.Availability.PRESENT,
                java.util.List.of("2.0.1.Final", "1.9.Final"));
        assertThat(CoordinateSearchClient.preferredVersion(qualifiedOnly)).contains("2.0.1.Final");
        assertThat(CoordinateSearchClient.preferredVersion(
                new CoordinateSearchClient.ArtifactVersions(CoordinateSearchClient.Availability.UNKNOWN,
                        java.util.List.of()))).isEmpty();
    }

    @Test
    void versionSuitability_prefersAnAlignedVersionWhenItIsPublished() {
        assertThat(VersionSuitability.choose(java.util.List.of("3.5.3", "3.3.5", "3.2.0"), "3.3.5")).contains("3.3.5");
        assertThat(VersionSuitability.choose(java.util.List.of("3.5.3", "3.3.5"), "9.9.9")).contains("3.5.3");
        assertThat(VersionSuitability.choose(java.util.List.of(), "3.3.5")).isEmpty();
    }

    @Test
    void pomEditor_findsALiteralVersionDeclaredForTheSameGroup_andSkipsPropertyReferences() {
        String pom = """
                <project><dependencies>
                  <dependency><groupId>org.acme</groupId><artifactId>a-props</artifactId><version>${acme.version}</version></dependency>
                  <dependency><groupId>org.acme</groupId><artifactId>a-core</artifactId><version>2.4.1</version></dependency>
                  <dependency><groupId>org.other</groupId><artifactId>x</artifactId><version>9.0</version></dependency>
                </dependencies></project>
                """;

        assertThat(PomDependencyEditor.declaredVersionInGroup(pom, "org.acme")).contains("2.4.1");
        assertThat(PomDependencyEditor.declaredVersionInGroup(pom, "org.none")).isEmpty();
    }

    @Test
    void aSpentFailureBudget_coolsDown_andIsRetriedAfterward_ratherThanDisablingTheSession() {
        // T1-GF: the index measured flaky, not absent. The old budget switched it off for the
        // rest of the session after three failures in a row.
        long[] now = {1_000_000L};
        CoordinateSearchClient client = new CoordinateSearchClient();
        client.setClock(() -> now[0]);

        client.registerFailure("timeout");
        client.registerFailure("timeout");
        assertThat(client.cooling()).as("budget not yet spent").isFalse();
        client.registerFailure("timeout");
        assertThat(client.cooling()).isTrue();

        now[0] += CoordinateSearchClient.COOLDOWN_MS - 1;
        assertThat(client.cooling()).isTrue();
        now[0] += 1;
        assertThat(client.cooling()).as("one query is let through once the cooldown ends").isFalse();

        client.registerFailure("still down");
        assertThat(client.cooling()).as("a failed retry cools down again").isTrue();
    }
}
