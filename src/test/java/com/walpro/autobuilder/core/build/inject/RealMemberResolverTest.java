package com.walpro.autobuilder.core.build.inject;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T6-T: an invented member answered with the receiver's REAL members that fit how it was used. JDK types stand in for
 * a library (field51: RecordDeclaration.getComponents() iterated with getNameAsString() on each element).
 */
class RealMemberResolverTest {

    private static final String SOURCE = """
            package org.ink;
            import java.util.HashMap;
            import java.util.Map;
            class Tally {
              String keys(HashMap<String, Integer> m) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, Integer> e : m.entrys()) {
                  sb.append(e.getKey());
                }
                return m.texts().strip();
              }
            }
            """;

    @Test
    void theUseOfTheMissingMemberIsReadAtItsLine() {
        RealMemberResolver.Use iterated = RealMemberResolver.useAt(SOURCE, 7, "entrys").orElseThrow();
        assertThat(iterated.iterated()).isTrue();
        assertThat(iterated.calledOnResult()).containsExactly("getKey");

        RealMemberResolver.Use value = RealMemberResolver.useAt(SOURCE, 10, "texts").orElseThrow();
        assertThat(value.iterated()).isFalse();
        assertThat(value.calledOnResult()).containsExactly("strip");
        assertThat(RealMemberResolver.useAt(SOURCE, 3, "entrys")).isEmpty();
    }

    @Test
    void onlyRealMembersThatFitTheUseAreOffered() {
        RealMemberResolver.Use iterated = RealMemberResolver.useAt(SOURCE, 7, "entrys").orElseThrow();

        List<String> fit = RealMemberResolver.fitting("java.util.HashMap", "entrys", iterated, Path.of("/nonexistent"), "");

        assertThat(fit).as("iterable, and its elements have getKey()").contains("entrySet").doesNotContain("keySet", "values", "size");
    }

    @Test
    void theCallIsRenamedAndAnIteratedVariableBecomesVar() {
        String changed = RealMemberResolver.substitute(SOURCE, 7, "entrys", "entrySet");

        assertThat(changed).contains("for (var e : m.entrySet())").doesNotContain("entrys");
        assertThat(RealMemberResolver.substitute(SOURCE, 7, "nothere", "x")).isNull();
    }

    @Test
    void typesAreAlikeByTheMethodsTheyShare() {
        assertThat(RealMemberResolver.likeness(java.util.ArrayList.class, java.util.LinkedList.class))
                .isGreaterThan(RealMemberResolver.likeness(java.util.ArrayList.class, java.lang.Thread.class));
        assertThat(RealMemberResolver.likeness(null, String.class)).isZero();
    }
}
