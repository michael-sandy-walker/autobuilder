package com.walpro.autobuilder.analysis.structure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointPathsMentionTest {

    /** field118: an intent ending "... for POST /loans." made a work item no request mapping could ever satisfy. */
    @Test
    void aSentencesFullStopIsNotPartOfThePath() {
        var m = EndpointPaths.MENTION.matcher("Use the policy for POST /loans. Then GET /a/{id}/b.json, and POST /.");
        assertThat(m.find()).isTrue();
        assertThat(m.group(2)).isEqualTo("/loans");
        assertThat(m.find()).isTrue();
        assertThat(m.group(2)).isEqualTo("/a/{id}/b.json");
        assertThat(m.find()).isTrue();
        assertThat(m.group(2)).isEqualTo("/");
    }
}
