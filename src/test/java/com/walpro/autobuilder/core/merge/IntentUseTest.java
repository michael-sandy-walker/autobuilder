package com.walpro.autobuilder.core.merge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-EC: the forms the replay met in real trees (field119, field122, field126), on invented coordinates. */
class IntentUseTest {

    private static final String KNOT = "lib/src/main/java/org/knot/Knot.java";
    private static final String KNOT_TEXT = "package org.knot;\npublic class Knot { }\n";
    private static final String TIE = "lib/src/main/java/org/knot/Tie.java";
    private static final String TIE_TEXT = "package org.knot;\npublic class Tie { Knot knot; }\n";
    private static final String CLI = "lib/src/main/java/org/knot/Cli.java";
    private static final String CLI_TEXT = "package org.knot;\npublic class Cli { Knot knot = new Knot(); }\n";
    private static final String COIL = "src/main/java/org/warp/Coil.java";

    private static List<String> unused(String coil) {
        return IntentUse.unused(List.of(KNOT), Set.of(KNOT, TIE, CLI),
                Map.of(KNOT, KNOT_TEXT, TIE, TIE_TEXT, CLI, CLI_TEXT, COIL, coil));
    }

    @Test
    void importedAndCalledIsUsed() {
        assertThat(unused("package org.warp;\nimport org.knot.Knot;\nclass Coil { Knot k = new Knot(); }\n")).isEmpty();
    }

    @Test
    void aWildcardImportAndAQualifiedNameAreUses() {
        assertThat(unused("package org.warp;\nimport org.knot.*;\nclass Coil { Knot k; }\n")).isEmpty();
        assertThat(unused("package org.warp;\nclass Coil { org.knot.Knot k; }\n")).isEmpty();
    }

    @Test
    void aStaticImportIsAUse_itsCallsCarryNoTypeName() {
        assertThat(unused("package org.warp;\nimport static org.knot.Knot.tie;\nclass Coil { Object o = tie(); }\n")).isEmpty();
    }

    /** field126: Isbn imported, never used, and the yardstick's grep passed it. */
    @Test
    void anImportAloneIsNotAUse() {
        assertThat(unused("package org.warp;\nimport org.knot.Knot;\nclass Coil { }\n")).containsExactly(KNOT);
    }

    /** field119: the reader imported, and a private parser named after it. */
    @Test
    void aMethodNamedAfterTheTypeIsNotAUse() {
        assertThat(unused("package org.warp;\nimport org.knot.Knot;\nclass Coil { void parseWithKnot() { } }\n")).containsExactly(KNOT);
    }

    /** field130: rows of the reader's type, filled by the project's own parser. */
    @Test
    void aTypeNestedInItIsNotAUse_itsMembersAre() {
        assertThat(unused("package org.warp;\nimport org.knot.Knot;\nclass Coil { Knot.Row r = new Knot.Row(); }\n")).containsExactly(KNOT);
        assertThat(unused("package org.warp;\nimport org.knot.Knot;\nclass Coil { Object o = Knot.tie(); }\n")).isEmpty();
        assertThat(unused("package org.warp;\nclass Coil { org.knot.Knot.Row r; }\n")).containsExactly(KNOT);
    }

    @Test
    void commentsAndStringsAreNotUses() {
        assertThat(unused("package org.warp;\n// uses Knot\n/* Knot */\nclass Coil { String s = \"new Knot()\"; }\n")).containsExactly(KNOT);
    }

    @Test
    void aStringHoldingSlashesDoesNotHideTheCodeAfterIt() {
        assertThat(unused("package org.warp;\nimport org.knot.Knot;\nclass Coil { String s = \"http://a\"; Knot k; }\n")).isEmpty();
    }

    @Test
    void anotherTypeOfTheSameNameIsNotAUse() {
        assertThat(unused("package org.warp;\nimport org.other.Knot;\nclass Coil { Knot k; }\n")).containsExactly(KNOT);
    }

    /** clitarget's Main calls its reader in every run: the input's own files prove nothing. */
    @Test
    void theInputsOwnFilesAloneAreNotAUse() {
        assertThat(unused("package org.warp;\nclass Coil { }\n")).containsExactly(KNOT);
    }

    @Test
    void usingAnInputTypeThatUsesItIsAUse() {
        assertThat(unused("package org.warp;\nimport org.knot.Tie;\nclass Coil { Tie t; }\n")).isEmpty();
    }

    @Test
    void aTypeNoLongerInTheTreeIsNotUsed() {
        assertThat(IntentUse.unused(List.of(KNOT), Set.of(KNOT), Map.of(COIL, "package org.warp;\nclass Coil { }\n"))).containsExactly(KNOT);
    }
}
