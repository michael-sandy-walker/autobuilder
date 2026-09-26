package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The one defect the port review found in the loop's router: the declaring file was matched by substring. */
class StuckQuestionsPortReviewTest {

    @Test
    void aTypeWhoseNamePrefixesAnotherIsReadFromItsOwnDeclaration() {
        Map<String, String> sources = new LinkedHashMap<>();   // the builder listed FIRST, which the substring match fell for
        sources.put("src/main/java/org/warp/CoilBuilder.java", "package org.warp;\npublic class CoilBuilder { public Coil build() { return null; } }\n");
        sources.put("src/main/java/org/warp/Coil.java", "package org.warp;\npublic record Coil(int turns) {}\n");

        var a = StuckQuestions.answer("the build fails: Coil has no getTurns()", sources).orElseThrow();

        assertThat(a.declared()).containsExactly("turns()");
        assertThat(a.meant()).isEqualTo("turns()");
    }

    @Test
    void aNameInACommentIsNotADeclaration() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("src/main/java/org/warp/Notes.java", "package org.warp;\n/** see class Coil */\npublic class Notes {}\n");
        assertThat(StuckQuestions.answer("the build fails: Coil has no getTurns()", sources)).isEmpty();
    }
}
