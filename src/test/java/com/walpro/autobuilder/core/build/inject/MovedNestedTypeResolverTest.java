package com.walpro.autobuilder.core.build.inject;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T6-T: a nested type moved out to a top-level one -- every {@code Owner.T} left in the candidate is rewritten to
 * {@code T}. Invented coordinates; the shape is rule-extraction runs 2026-10-19_15-23-35 and 2026-10-19_16-19-07.
 */
class MovedNestedTypeResolverTest {

    private static final String NIB = "src/main/java/org/ink/pen/Nib.java";
    private static final String QUILL = "src/main/java/org/ink/pen/Quill.java";
    private static final String DESK = "src/main/java/org/ink/desk/Desk.java";
    private static final String BLOT = "src/main/java/org/ink/pen/Blotter.java";

    private static Map<String, String> tree(boolean nestedStillThere) {
        Map<String, String> t = new LinkedHashMap<>();
        t.put(NIB, "package org.ink.pen;\n\npublic record Nib(int width) {}\n");
        t.put(QUILL, "package org.ink.pen;\n\npublic class Quill {\n"
                + (nestedStillThere ? "    public record Nib(int width) {}\n" : "")
                + "    public Quill.Nib tip() { return null; }\n}\n");
        t.put(DESK, "package org.ink.desk;\n\nimport org.ink.pen.Quill;\n\npublic class Desk {\n"
                + "    java.util.Set<Quill.Nib> nibs;\n    org.ink.pen.Quill.Nib spare;\n    Quill quill;\n}\n");
        t.put(BLOT, "package org.ink.pen;\n\nimport org.ink.pen.Quill.Nib;\n\nclass Blotter { Nib n; Quill.Nibbler other; }\n");
        return t;
    }

    @Test
    void everyOwnerDotTypeIsRewrittenToTheTopLevelType_importAddedWhereThePackageDiffers() {
        Map<String, String> out = MovedNestedTypeResolver.rewrite(tree(false), "Quill", "Nib");

        assertThat(out).containsOnlyKeys(QUILL, DESK, BLOT);
        assertThat(out.get(QUILL)).contains("public Nib tip()").doesNotContain("Quill.Nib");
        assertThat(out.get(DESK)).contains("import org.ink.pen.Nib;", "java.util.Set<Nib> nibs;", "Nib spare;",
                "Quill quill;").doesNotContain("Quill.Nib");
        assertThat(out.get(BLOT)).as("the old nested import goes; same package needs none")
                .doesNotContain("import org.ink.pen.Quill.Nib;").contains("Quill.Nibbler other");
    }

    @Test
    void nothingIsRewrittenWhileTheOwnerStillDeclaresTheNestedType() {
        assertThat(MovedNestedTypeResolver.rewrite(tree(true), "Quill", "Nib")).isEmpty();
    }

    @Test
    void nothingIsRewrittenWithoutATopLevelTypeOfThatName() {
        Map<String, String> t = tree(false);
        t.remove(NIB);
        assertThat(MovedNestedTypeResolver.rewrite(t, "Quill", "Nib")).isEmpty();
    }
}
