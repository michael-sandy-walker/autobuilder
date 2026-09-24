package com.walpro.autobuilder.protocol.evolution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-CQ: a rejected evolution answer is told what it got wrong, not only that it was wrong. */
class EvolutionProtocolContractMismatchTest {

    @Test
    void anAcceptedAnswerHasNoMismatch() {
        assertThat(EvolutionProtocolContract.describeMismatch(
                "```java [src/test/java/org/sample/QuillTest.java]\nclass QuillTest {}\n```\n")).isNull();
    }

    @Test
    void aBlockWithoutAPathIsToldWhereThePathGoes() {
        String why = EvolutionProtocolContract.describeMismatch("```java\nclass QuillTest {}\n```\n");
        assertThat(why).contains("```java").contains("names no file").contains("SAME line");
    }

    @Test
    void anUnclosedBlockIsToldItIsUnclosed() {
        assertThat(EvolutionProtocolContract.describeMismatch("```java [a/B.java]\nclass B {}\n"))
                .contains("never closed");
    }

    @Test
    void proseAloneIsToldWhatForms() {
        assertThat(EvolutionProtocolContract.describeMismatch("I would change the test."))
                .contains("no file block").contains("#NoDiff");
    }
}
