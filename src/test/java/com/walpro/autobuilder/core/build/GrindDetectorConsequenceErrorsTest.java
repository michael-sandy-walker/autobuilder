package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T6-T: a failed build's errors in files the change did not write are consequences of its errors in the files it
 * did write, and are not stuck cases of their own. Ladder-grind run 2026-10-10_19-22-18: one real error stopped
 * annotation processing and every class reading a generated member reported it missing.
 */
class GrindDetectorConsequenceErrorsTest {

    private static BuildOutcome failed(String stderr) {
        return new BuildOutcome(BuildResult.COMPILE_ERROR, stderr, 1L, "mvn", "probe");
    }

    private static final String BOTH = """
            [ERROR] /x/src/main/java/org/sample/Ledger.java:[14,9] cannot find symbol
            [ERROR]   symbol:   variable tally
            [ERROR]   location: class org.sample.Ledger
            [ERROR] /x/src/main/java/org/sample/Quill.java:[30,21] incompatible types: int cannot be converted to String
            [ERROR] /x/src/main/java/org/sample/Well.java:[8,5] cannot find symbol
            [ERROR]   symbol:   variable tally
            [ERROR]   location: class org.sample.Well
            """;

    @Test
    void errorsInFilesTheChangeDidNotWriteAreSetAsideWhenItsOwnFilesHaveErrors() {
        List<String> signatures = GrindDetector.signaturesOf(failed(BOTH), Set.of("src/main/java/org/sample/Quill.java"));

        assertThat(signatures).containsExactly("the build fails: Quill.java: incompatible types: int cannot be converted to String");
    }

    @Test
    void errorsOnlyInFilesTheChangeDidNotWriteAreWhatItBrokeAndAreKept() {
        List<String> signatures = GrindDetector.signaturesOf(failed(BOTH), Set.of("src/main/java/org/sample/Nib.java"));

        assertThat(signatures).hasSize(3).anyMatch(s -> s.startsWith("the build fails: Ledger.java: cannot find symbol"));
    }

    @Test
    void withoutKnowingWhatTheChangeWroteEveryErrorIsKeptAsBefore() {
        assertThat(GrindDetector.signaturesOf(failed(BOTH))).hasSize(3);
        assertThat(GrindDetector.signaturesOf(failed(BOTH), Set.of())).hasSize(3);
    }
}
