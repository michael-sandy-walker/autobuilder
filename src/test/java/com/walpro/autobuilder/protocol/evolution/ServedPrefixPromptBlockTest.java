package com.walpro.autobuilder.protocol.evolution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-CQ: the served-prefix fact, stated only where a prefix is configured. Invented prefix. */
class ServedPrefixPromptBlockTest {

    @Test
    void aConfiguredPrefixIsStatedWithTheRuleThatDecidesATestsPath() {
        String block = ServedPrefixPromptBlock.render("/widgets");
        assertThat(block)
                .contains("a route declared as /x answers at /widgets/x")
                .contains("in-process")
                .contains("WITHOUT it");
    }

    @Test
    void noPrefixLeavesThePromptExactlyAsItWas() {
        assertThat(ServedPrefixPromptBlock.render(null)).isNull();
        assertThat(ServedPrefixPromptBlock.appendTo("prompt", "")).isEqualTo("prompt");
    }
}
