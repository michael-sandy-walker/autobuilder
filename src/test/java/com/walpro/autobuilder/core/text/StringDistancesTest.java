package com.walpro.autobuilder.core.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StringDistancesTest {

    @Test
    void levenshtein_isZero_forIdenticalStrings() {
        assertThat(StringDistances.levenshtein("BookRepository", "BookRepository")).isZero();
        assertThat(StringDistances.levenshtein("", "")).isZero();
    }

    @Test
    void levenshtein_returnsEditCount_forSmallChanges() {
        // Two-character transposition counts as 2 substitutions in plain Levenshtein.
        assertThat(StringDistances.levenshtein("BookRepository", "BookRepostiory")).isEqualTo(2);
        // Single deletion.
        assertThat(StringDistances.levenshtein("Repository", "Repositry")).isEqualTo(1);
        // Single insertion.
        assertThat(StringDistances.levenshtein("Foo", "Foos")).isEqualTo(1);
    }

    @Test
    void levenshtein_returnsLengthOfOther_whenOneEmpty() {
        assertThat(StringDistances.levenshtein("", "abc")).isEqualTo(3);
        assertThat(StringDistances.levenshtein("abc", "")).isEqualTo(3);
    }

    @Test
    void levenshtein_returnsMaxValue_forNullInputs() {
        assertThat(StringDistances.levenshtein(null, "x")).isEqualTo(Integer.MAX_VALUE);
        assertThat(StringDistances.levenshtein("x", null)).isEqualTo(Integer.MAX_VALUE);
        assertThat(StringDistances.levenshtein(null, null)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void bounded_returnsMaxValue_whenLengthDifferenceExceedsThreshold() {
        // length difference 4 > threshold 2 → early bail.
        assertThat(StringDistances.levenshtein("Foo", "FooFoo!", 2)).isEqualTo(Integer.MAX_VALUE);
        // length difference 1 survives the bail; full distance still computed.
        assertThat(StringDistances.levenshtein("Foo", "Foos", 2)).isEqualTo(1);
    }
}
