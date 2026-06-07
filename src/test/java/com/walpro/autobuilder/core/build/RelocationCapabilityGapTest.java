package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** T2-DZ: tests for {@link RelocationCapabilityGap} — per-type, sub-package-aware, revisable. */
class RelocationCapabilityGapTest {

    @Test
    void from_null_returnsNull() {
        assertThat(RelocationCapabilityGap.from(null)).isNull();
    }

    @Test
    void from_derivesLocatedAndAttemptedPackagesFromPaths() {
        // Conflict order: (simpleName, candidatePath = attempted write, existingPath = on disk).
        var conflict = new PackageFragmentationCheck.Conflict(
                "Member",
                "src/main/java/com/example/demo/member/Member.java",
                "src/main/java/com/example/demo/Member.java");

        RelocationCapabilityGap gap = RelocationCapabilityGap.from(conflict);

        assertThat(gap.typeName()).isEqualTo("Member");
        assertThat(gap.locatedPackage()).isEqualTo("com.example.demo");
        assertThat(gap.attemptedPackage()).isEqualTo("com.example.demo.member");
    }

    @Test
    void packageOf_distinguishesNestedPackages_notWholePackageRejection() {
        // The gap is scoped per type to its exact (sub-)package — demo and demo.member differ.
        assertThat(RelocationCapabilityGap.packageOf("src/main/java/com/example/demo/Member.java"))
                .isEqualTo("com.example.demo");
        assertThat(RelocationCapabilityGap.packageOf("src/main/java/com/example/demo/member/Member.java"))
                .isEqualTo("com.example.demo.member");
    }

    @Test
    void packageOf_pathWithoutSourceRoot_fallsBackToSlashToDot() {
        assertThat(RelocationCapabilityGap.packageOf("com/example/demo/Member.java"))
                .isEqualTo("com.example.demo");
    }

    @Test
    void summary_isPresentTenseAndNamesMissingCapability_noPermanenceClaim() {
        RelocationCapabilityGap gap =
                new RelocationCapabilityGap("Member", "com.example.demo", "com.example.demo.member");

        String summary = gap.summary();

        assertThat(summary)
                .contains("capability gap")
                .contains(RelocationCapabilityGap.MISSING_CAPABILITY)
                .contains("Member")
                .contains("located in com.example.demo")
                .contains("com.example.demo.member")
                .contains("does not have yet");
        // Revisable framing — must not assert the relocation is impossible / forbidden / never.
        assertThat(summary.toLowerCase())
                .doesNotContain("impossible")
                .doesNotContain("forbidden")
                .doesNotContain("never");
    }
}
