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
}
