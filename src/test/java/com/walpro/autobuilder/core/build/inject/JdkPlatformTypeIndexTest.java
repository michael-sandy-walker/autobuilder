package com.walpro.autobuilder.core.build.inject;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-BM: the platform index is discovered live from the running JDK, so the assertions pin
 * language-spec facts (java.base contents), not a frozen list of our own.
 */
class JdkPlatformTypeIndexTest {

    @Test
    void javaBaseTypes_containTheCoreVocabulary() {
        assertThat(JdkPlatformTypeIndex.javaBaseTypes("Optional"))
                .containsExactly("java.util.Optional");
        assertThat(JdkPlatformTypeIndex.javaBaseTypes("LocalDate"))
                .containsExactly("java.time.LocalDate");
        assertThat(JdkPlatformTypeIndex.javaBaseTypes("Pattern"))
                .contains("java.util.regex.Pattern");
    }

    @Test
    void exportedTypes_coverNonBaseModules_butBaseViewDoesNot() {
        // java.sql.Date lives in the java.sql module: visible in the all-modules view,
        // absent from the java.base view. (Skip silently on a JRE image without java.sql.)
        var all = JdkPlatformTypeIndex.exportedTypes("Date");
        if (all.contains("java.sql.Date")) {
            assertThat(JdkPlatformTypeIndex.javaBaseTypes("Date"))
                    .contains("java.util.Date")
                    .doesNotContain("java.sql.Date");
        }
        assertThat(all).contains("java.util.Date");
    }

    @Test
    void nestedTypesAndUnknownNames_yieldNothing() {
        // Map.Entry is nested — indexed under neither its own nor its outer simple name shape.
        assertThat(JdkPlatformTypeIndex.exportedTypes("Entry")).isEmpty();
        assertThat(JdkPlatformTypeIndex.exportedTypes("NoSuchPlatformType")).isEmpty();
        assertThat(JdkPlatformTypeIndex.exportedTypes("")).isEmpty();
        assertThat(JdkPlatformTypeIndex.exportedTypes(null)).isEmpty();
    }

    @Test
    void internalPackages_areNotIndexed() {
        // java.base ships jdk.internal.misc.Unsafe but does not export the package — the
        // java.base view must not leak it. (jdk.unsupported exports sun.misc.Unsafe, so only
        // the base view is a sound assertion here.)
        assertThat(JdkPlatformTypeIndex.javaBaseTypes("Unsafe")).isEmpty();
    }
}
