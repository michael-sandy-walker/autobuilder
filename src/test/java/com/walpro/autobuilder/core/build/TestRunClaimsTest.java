package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A claim about a test run, answered by the run -- and nothing wider. Wording from run 2026-09-26_14-44. */
class TestRunClaimsTest {

    private static final TestRunReport.Case OK = new TestRunReport.Case("m", TestRunReport.Outcome.PASSED);
    private static final TestRunReport GREEN = new TestRunReport(3, 0, 0, 0, List.of(),
            Map.of("org.warp.CoilSpecTest", List.of(OK, OK), "org.warp.HarnessTest", List.of(OK)));

    @Test
    void theFieldWordingIsProvenByAGreenRun() {
        assertThat(TestRunClaims.provenBy("CoilSpecTest passes in full (all test methods green)", GREEN)).isTrue();
        assertThat(TestRunClaims.provenBy("The full test suite under src/test/java passes with no regressions", GREEN)).isTrue();
        assertThat(TestRunClaims.provenBy("every test in CoilSpecTest passes", GREEN)).isTrue();
        assertThat(TestRunClaims.provenBy("the whole suite passes.", GREEN)).isTrue();
        assertThat(TestRunClaims.provenBy("`HarnessTest` passes", GREEN)).isTrue();
    }

    @Test
    void aCompoundClaimIsLeftToTheReader() {
        assertThat(TestRunClaims.provenBy("CoilSpecTest passes and every coil is aligned", GREEN)).isFalse();
        assertThat(TestRunClaims.provenBy("The suite passes and covers every route", GREEN)).isFalse();
        assertThat(TestRunClaims.provenBy("Coil.align returns the turns asserted by CoilSpecTest", GREEN)).isFalse();
    }

    @Test
    void aClassTheRunDidNotRunIsNotProven() {
        assertThat(TestRunClaims.provenBy("PlasmaTest passes", GREEN)).isFalse();
    }

    @Test
    void aRedRunProvesNothing() {
        TestRunReport red = new TestRunReport(3, 1, 0, 0,
                List.of(new TestRunReport.Failure("org.warp.HarnessTest", "m", TestRunReport.Outcome.FAILED, "x")),
                Map.of("org.warp.CoilSpecTest", List.of(OK, OK),
                        "org.warp.HarnessTest", List.of(new TestRunReport.Case("m", TestRunReport.Outcome.FAILED))));
        assertThat(TestRunClaims.provenBy("CoilSpecTest passes", red)).isFalse();
        assertThat(TestRunClaims.provenBy("the whole suite passes", red)).isFalse();
        assertThat(TestRunClaims.provenBy("CoilSpecTest passes", null)).isFalse();
    }

    /** receiver-questions run 2026-09-26_21-50-02: the loop's own criteria, crossed on a green run. */
    @Test
    void theReceiverQuestionsWordingIsProvenByAGreenRun() {
        assertThat(TestRunClaims.provenBy("Every test in CoilSpecTest passes", GREEN)).isTrue();
        assertThat(TestRunClaims.provenBy("The full test suite (CoilSpecTest, HarnessTest) passes", GREEN)).isTrue();
        assertThat(TestRunClaims.provenBy("The full test suite (CoilSpecTest, PlasmaTest) passes", GREEN))
                .as("a listed class that did not run").isFalse();
        assertThat(TestRunClaims.provenBy("Every test in CoilSpecTest passes and the coils align", GREEN)).isFalse();
    }

    /** Shape only: a GUARD over the existing tests -- not a bare "X passes", which is work while X fails. */
    @org.junit.jupiter.api.Test
    void aGuardOverTheExistingTestsIsRecognisedWithoutAnyRun() {
        org.assertj.core.api.Assertions.assertThat(TestRunClaims.isGuardClaim(
                "The existing tests in `src/test/java` continue to pass without modification.")).isTrue();
        org.assertj.core.api.Assertions.assertThat(TestRunClaims.isGuardClaim("All existing tests still pass")).isTrue();
        org.assertj.core.api.Assertions.assertThat(TestRunClaims.isGuardClaim("Every test in QuillSpecTest passes"))
                .as("the work itself while QuillSpecTest fails").isFalse();
        org.assertj.core.api.Assertions.assertThat(TestRunClaims.isGuardClaim("The whole suite passes")).isFalse();
        org.assertj.core.api.Assertions.assertThat(TestRunClaims.isGuardClaim("The quill dries and the existing tests pass")).isFalse();
        org.assertj.core.api.Assertions.assertThat(TestRunClaims.isGuardClaim(null)).isFalse();
    }

    /** The user's decision 2026-10-02, record first: the run answers such a claim either way. */
    @Test
    void theRunAnswersEitherWay_andOnlyWhatItRan() {
        TestRunReport red = new TestRunReport(3, 1, 0, 0,
                List.of(new TestRunReport.Failure("org.warp.HarnessTest", "m", TestRunReport.Outcome.FAILED, "x")),
                Map.of("org.warp.CoilSpecTest", List.of(OK, OK),
                        "org.warp.HarnessTest", List.of(new TestRunReport.Case("m", TestRunReport.Outcome.FAILED))));
        assertThat(TestRunClaims.answer("CoilSpecTest passes", GREEN)).isTrue();
        assertThat(TestRunClaims.answer("CoilSpecTest passes", red)).as("its own cases passed").isTrue();
        assertThat(TestRunClaims.answer("Every test in HarnessTest passes", red)).isFalse();
        assertThat(TestRunClaims.answer("the whole suite passes", red)).isFalse();
        assertThat(TestRunClaims.answer("The full test suite (CoilSpecTest, HarnessTest) passes", red)).isFalse();
        assertThat(TestRunClaims.answer("PlasmaTest passes", red)).as("not run: no answer").isNull();
        assertThat(TestRunClaims.answer("The full test suite (CoilSpecTest, PlasmaTest) passes", GREEN)).isNull();
        assertThat(TestRunClaims.answer("The suite passes and covers every route", red)).as("compound").isNull();
    }
}
