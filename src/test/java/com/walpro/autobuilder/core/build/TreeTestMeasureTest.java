package com.walpro.autobuilder.core.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class TreeTestMeasureTest {

    private static final TreeTestMeasure FOUR = new TreeTestMeasure(true, 4);
    private static final TreeTestMeasure SIX = new TreeTestMeasure(true, 6);
    private static final TreeTestMeasure UNCOMPILED = new TreeTestMeasure(false, 1);

    @Test
    void aSuiteThatDoesNotCompileIsWorseThanAnyThatRuns() {
        assertThat(UNCOMPILED.worseThan(new TreeTestMeasure(true, 51))).isTrue();
        assertThat(SIX.worseThan(FOUR)).isTrue();
        assertThat(FOUR.worseThan(SIX)).isFalse();
        assertThat(FOUR.worseThan(FOUR)).isFalse();
        assertThat(FOUR.worseThan(null)).isFalse();
    }

    @Test
    void admissibleKeepsTheSuitesProgressAndNeverChoosesBlind() {
        assertThat(TreeTestMeasure.admissible(SIX, null)).as("nothing known to keep").isTrue();
        assertThat(TreeTestMeasure.admissible(null, null)).isTrue();
        assertThat(TreeTestMeasure.admissible(FOUR, FOUR)).isTrue();
        assertThat(TreeTestMeasure.admissible(FOUR, SIX)).isTrue();
        assertThat(TreeTestMeasure.admissible(SIX, FOUR)).isFalse();
        assertThat(TreeTestMeasure.admissible(null, FOUR)).as("an unmeasured point against a known tree").isFalse();
    }

    @Test
    void bestIgnoresTheUnknown() {
        assertThat(TreeTestMeasure.best(Arrays.asList(SIX, null, FOUR, UNCOMPILED))).contains(FOUR);
        assertThat(TreeTestMeasure.best(Arrays.asList(null, null))).isEmpty();
    }

    @Test
    void roundTripsAsJsonWithNoDerivedProperties() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(FOUR);
        assertThat(json).isEqualTo("{\"testsCompile\":true,\"failing\":4}");
        assertThat(mapper.readValue(json, TreeTestMeasure.class)).isEqualTo(FOUR);
    }
}
