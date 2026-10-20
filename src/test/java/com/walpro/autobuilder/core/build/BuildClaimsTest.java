package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A claim that the code compiles, answered by a test run of the tree -- confirm-only. Wording from the 2026-10-20 merge check. */
class BuildClaimsTest {

    private static final TestRunReport RAN = new TestRunReport(4, 1, 0, 0, List.of(),
            Map.of("org.warp.CoilTest", List.of(new TestRunReport.Case("m", TestRunReport.Outcome.PASSED))));

    @Test
    void aCompileClaimIsConfirmedByAnyRunOfTheTree() {
        assertThat(BuildClaims.answer("mvn compile succeeds with no errors", RAN)).isTrue();
        assertThat(BuildClaims.answer("The project compiles without errors.", RAN)).isTrue();
        assertThat(BuildClaims.answer("The type mismatch at line 57 (List assigned to int) is resolved", RAN)).isTrue();
        assertThat(BuildClaims.answer("the compile error in Coil.java is fixed", RAN)).isTrue();
    }

    @Test
    void noRunAnswersNothingAndNeverContradicts() {
        assertThat(BuildClaims.answer("mvn compile succeeds with no errors", null)).isNull();
        assertThat(BuildClaims.answer("mvn compile succeeds with no errors", new TestRunReport(0, 0, 0, 0, List.of(), Map.of()))).isNull();
    }

    @Test
    void aClaimThatSaysMoreIsLeftToTheReader() {
        assertThat(BuildClaims.isClaim("The project packages into a startable application artifact without build errors")).isFalse();
        assertThat(BuildClaims.isClaim("mvn compile succeeds and Coil.align returns the turns")).isFalse();
        assertThat(BuildClaims.isClaim("The new methods do not break any existing functionality")).isFalse();
    }
}
