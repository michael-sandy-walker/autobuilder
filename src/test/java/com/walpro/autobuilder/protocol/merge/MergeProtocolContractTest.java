package com.walpro.autobuilder.protocol.merge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MergeProtocolContractTest {

    @Test
    void isValidResponse_false_whenNull() {
        assertThat(MergeProtocolContract.isValidResponse(null)).isFalse();
    }

    @Test
    void isValidResponse_false_whenMissingOpening() {
        assertThat(MergeProtocolContract.isValidResponse("nope")).isFalse();
    }

    @Test
    void isValidResponse_false_whenMissingClosingBracket() {
        assertThat(MergeProtocolContract.isValidResponse("""
                ```java [src/main/java/A.java
                class A {}
                ```
                """)).isFalse();
    }

    @Test
    void isValidResponse_false_whenMissingClosingFence() {
        assertThat(MergeProtocolContract.isValidResponse("""
                ```java [src/main/java/A.java]
                class A {}
                """)).isFalse();
    }

    @Test
    void isValidResponse_false_whenEmptyMergedContent() {
        assertThat(MergeProtocolContract.isValidResponse("""
                ```java [src/main/java/A.java]
                   
                   
                ```
                """)).isFalse();
    }

    @Test
    void isValidResponse_true_whenWellFormedAndNonBlank() {
        assertThat(MergeProtocolContract.isValidResponse("""
                ```java [src/main/java/A.java]
                class A {}
                ```
                """)).isTrue();
    }
}