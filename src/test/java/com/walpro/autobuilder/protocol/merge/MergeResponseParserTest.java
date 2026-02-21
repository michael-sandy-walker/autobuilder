package com.walpro.autobuilder.protocol.merge;

import com.walpro.autobuilder.agent.merge.model.MergeOutcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MergeResponseParserTest {

    private final MergeResponseParser parser = new MergeResponseParser();

    @Test
    void parse_returnsFailure_whenContentNull() {
        MergeOutcome out = parser.parse(null);

        assertThat(out.successful()).isFalse();
        assertThat(out.mergedContent()).isNull();
        assertThat(out.reasoning()).contains("Missing content");
    }

    @Test
    void parse_returnsFailure_whenMissingCodeBlock() {
        MergeOutcome out = parser.parse("hello");

        assertThat(out.successful()).isFalse();
        assertThat(out.mergedContent()).isNull();
        assertThat(out.reasoning()).contains("Missing code block");
    }

    @Test
    void parse_returnsFailure_whenMalformedCodeBlock_missingClosingFence() {
        MergeOutcome out = parser.parse("""
                ```java [src/main/java/A.java]
                class A {}
                """);

        assertThat(out.successful()).isFalse();
        assertThat(out.mergedContent()).isNull();
        assertThat(out.reasoning()).contains("Malformed code block");
    }

    @Test
    void parse_returnsFailure_whenMalformedCodeBlock_missingClosingBracket() {
        MergeOutcome out = parser.parse("""
                ```java [src/main/java/A.java
                class A {}
                ```
                """);

        assertThat(out.successful()).isFalse();
        assertThat(out.mergedContent()).isNull();
        // current implementation returns "Malformed code block" when it can't find ] or fences properly
        assertThat(out.reasoning()).containsAnyOf("Malformed code block", "Parser exception");
    }

    @Test
    void parse_returnsMergedContent_whenValid() {
        MergeOutcome out = parser.parse("""
                ```java [src/main/java/A.java]
                package a;

                public class A {}
                ```
                """);

        assertThat(out.successful()).isTrue();
        assertThat(out.mergedContent()).isEqualTo("""
                package a;

                public class A {}
                """.trim());
        assertThat(out.reasoning()).isNull();
    }

    @Test
    void parse_trimsMergedContent() {
        MergeOutcome out = parser.parse("""
                ```java [src/main/java/A.java]
                  
                  public class A { }
                  
                ```
                """);

        assertThat(out.successful()).isTrue();
        assertThat(out.mergedContent()).isEqualTo("public class A { }");
        assertThat(out.reasoning()).isNull();
    }
}