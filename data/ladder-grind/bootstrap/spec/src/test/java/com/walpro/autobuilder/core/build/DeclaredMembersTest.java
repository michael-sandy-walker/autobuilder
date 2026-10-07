package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-CQ: what a type actually declares for callers, read from its source. */
class DeclaredMembersTest {

    @Test
    void aRecordOffersItsComponentAccessorsAndItsPublicMethods() {
        String src = """
                package org.sample;
                public record Quill(String ink, int nib) {
                    public boolean isDry() { return ink == null; }
                    boolean hidden() { return false; }
                }
                """;
        assertThat(DeclaredMembers.of(src, "Quill")).containsExactly("ink()", "nib()", "isDry()");
    }

    @Test
    void aClassOffersOnlyItsPublicMethods() {
        String src = """
                package org.sample;
                public class Inkwell {
                    public int level() { return 0; }
                    public void fill(int amount, String colour) {}
                    private void spill() {}
                }
                """;
        assertThat(DeclaredMembers.of(src, "Inkwell")).containsExactly("level()", "fill(int, String)");
    }

    @Test
    void anUnknownTypeOrUnparseableSourceOffersNothing() {
        assertThat(DeclaredMembers.of("public class A {}", "B")).isEmpty();
        assertThat(DeclaredMembers.of("not java at all {", "A")).isEmpty();
    }
}
