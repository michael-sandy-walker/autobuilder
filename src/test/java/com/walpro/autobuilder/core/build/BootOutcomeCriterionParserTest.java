package com.walpro.autobuilder.core.build;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T2-FL: the launch reader. The criteria here are field text, not invented phrasings — the first
 * is the exact string run {@code 2026-09-23_18-35-30} declined three times.
 */
class BootOutcomeCriterionParserTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "The application starts successfully without exiting with code 1.",
            "The application starts successfully",
            "the application boots successfully",
            "The app starts up without errors",
            "the service comes up cleanly",
            "The program runs without crashing",
            "The application starts and stays up",
            "the artifact launches successfully",
            "The application starts successfully without exiting with a non-zero exit code",
    })
    void aSentenceAssertingOnlyTheLaunchReadsAsTheLaunchClaim(String criterion) {
        assertThat(BootOutcomeCriterionParser.parse(criterion))
                .contains(BootOutcomeCriterionParser.CLAIM);
    }

    /**
     * The guard that keeps this reader from manufacturing a false completion. Every string here
     * asserts something the launch does not establish, so reading it as the launch claim would
     * promote a leaf on evidence for part of it — and the gate records this claim on any clean
     * launch, which is what makes the over-match dangerous rather than merely wrong.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "The application starts successfully and serves all three resource types",
            "The application starts successfully and GET /books returns 200",
            "The application starts successfully, then the seeded books are present",
            "GET /books returns HTTP 200 once the application starts successfully",
            "The application starts successfully on port 8080 with the datasource configured",
    })
    void aSentenceAssertingMoreThanTheLaunchIsLeftToTheReadersThatCanJudgeTheRest(String criterion) {
        assertThat(BootOutcomeCriterionParser.parse(criterion)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "The application starts",
            "the application runs",
            "Create BookController.java",
            "A class in the application sources declares a 'static void main' method",
            "count <file> prints the line count as a bare integer",
            "GET /books returns HTTP 200",
    })
    void aSentenceStatingNoLaunchOutcomeHasNoOpinion(String criterion) {
        assertThat(BootOutcomeCriterionParser.parse(criterion)).isEmpty();
    }

    @Test
    void nothingIsReadOutOfNothing() {
        assertThat(BootOutcomeCriterionParser.parse(null)).isEmpty();
        assertThat(BootOutcomeCriterionParser.parse("   ")).isEmpty();
    }

    /**
     * The claim is the join key between this reader and the gate's evidence, so its spelling is
     * an interface and not a detail: the two boot-gate sites record this constant, and
     * {@code whyNotSatisfied} asks the observation for it by value.
     */
    @Test
    void theClaimNamesNoProtocolOrPackagingFormat() {
        assertThat(BootOutcomeCriterionParser.CLAIM).isEqualTo("launch → runs");
    }
}
