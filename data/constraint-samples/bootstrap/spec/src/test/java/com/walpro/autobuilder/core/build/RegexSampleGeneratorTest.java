package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-DB: the generator's contract is narrow on purpose — a sample that matches, or nothing. A
 * wrong sample is worse than no sample, because the caller would send a body the application
 * refuses and the refusal would be read as a defect in the application.
 */
class RegexSampleGeneratorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "\\d{13}",          // the constraint the live specification actually states
            "\\d+",
            "[0-9]{4}",
            "[a-z]{3}",
            "[a-zA-Z]{2}",
            "abc",
            "^\\d{5}$",
            "\\d{2,6}",
            "[a-z]+\\d{2}",
            "a?b",
            "x*y",
    })
    void aSupportedPatternYieldsAValueThatMatchesIt(String regex) {
        Optional<String> sample = RegexSampleGenerator.sample(regex);

        assertThat(sample).as("sample for %s", regex).isPresent();
        assertThat(sample.get()).as("sample %s for %s", sample.get(), regex).matches(regex);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "(alpha|beta)",     // alternation has a sample; deriving it is reasoning this class does not do
            "[^a-z]{3}",        // the complement of a set is not a set this class enumerates
            "(\\d{3})-\\1",     // a back-reference constrains two places at once
            "\\p{Alpha}{4}",
            "[",
            "\\",
    })
    void anUnsupportedPatternAbstainsRatherThanApproximating(String regex) {
        assertThat(RegexSampleGenerator.sample(regex)).as("sample for %s", regex).isEmpty();
    }

    @Test
    void theLiveSpecificationsIdentifierPatternIsThirteenDigits() {
        assertThat(RegexSampleGenerator.sample("\\d{13}")).contains("0000000000000");
    }

    @Test
    void anEmptyOrNullPatternAbstains() {
        assertThat(RegexSampleGenerator.sample(null)).isEmpty();
        assertThat(RegexSampleGenerator.sample("")).isEmpty();
    }

    @Test
    void aPatternWhoseOnlySampleIsEmptyAbstainsBecauseAnEmptyBodyValueCreatesNothing() {
        assertThat(RegexSampleGenerator.sample("x*")).isEmpty();
    }
}
