package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2-EW: one home for "does this test source belong in the artifact", because there is more than
 * one door into {@code output/}.
 *
 * <p>Run {@code 2026-07-29_00-54-22} failed at {@code artifact-build-failed} on broken
 * {@code BookControllerTest} / {@code BookServiceTest} while the log shows the merge-agent filter
 * had SKIPPED exactly those two files. It worked and was simply not the only writer:
 * {@code MutationApplier} writes evolution's mutations straight to {@code output/}.
 */
class UnrequestedTestSourcePolicyTest {

    private static final String NO_TESTS =
            "Evolve the application into a library book-lending REST API. Books: POST /books (create).";
    private static final String WANTS_TESTS =
            "Build the API and provide unit tests for each service.";

    @Test
    void aTestSourceOnANoTestSpecDoesNotBelong() {
        assertThat(UnrequestedTestSourcePolicy.isUnrequested(
                "src/test/java/com/example/demo/model/BookServiceTest.java", NO_TESTS)).isTrue();
    }

    @Test
    void mainSourcesAlwaysBelong() {
        assertThat(UnrequestedTestSourcePolicy.isUnrequested(
                "src/main/java/com/example/demo/model/Book.java", NO_TESTS)).isFalse();
    }

    @Test
    void aSpecThatAsksForTestsKeepsThem() {
        assertThat(UnrequestedTestSourcePolicy.isUnrequested(
                "src/test/java/com/example/BookServiceTest.java", WANTS_TESTS)).isFalse();
    }

    @Test
    void noSpecMeansNoOpinion_neverADeletion() {
        assertThat(UnrequestedTestSourcePolicy.isUnrequested("src/test/java/A.java", null)).isFalse();
        assertThat(UnrequestedTestSourcePolicy.isUnrequested("src/test/java/A.java", "  ")).isFalse();
        assertThat(UnrequestedTestSourcePolicy.isUnrequested(null, NO_TESTS)).isFalse();
    }

    @Test
    void windowsSeparatorsAreRecognised() {
        assertThat(UnrequestedTestSourcePolicy.isUnrequested(
                "src\\test\\java\\com\\example\\A.java", NO_TESTS)).isTrue();
    }
}
