package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T6-T: claims about one named file the tree contradicts -- never confirms. Invented coordinates; the shape is
 * ladder-rows run 2026-10-06_18-38-56 (an unchanged file a reading called modified and holding 'ran with:').
 */
class ChangeClaimsTest {

    private static final Map<String, String> TREE = Map.of(
            "src/main/java/zeta/Nib.java", "package zeta;\npublic class Nib { static final String LABEL = \"width: \"; }\n",
            "src/main/java/zeta/Inkwell.java", "package zeta;\npublic class Inkwell {}\n");
    private static final AdoptionManifest.Changes ONLY_NIB_CHANGED =
            new AdoptionManifest.Changes(List.of("src/main/java/zeta/Nib.java"), List.of(), List.of());

    @Test
    void aFileClaimedModifiedThatWasNotIsContradicted_oneThatWasIsLeftToTheReading() {
        assertThat(ChangeClaims.answer("Inkwell is modified to support the new behavior", ONLY_NIB_CHANGED, TREE)).isFalse();
        assertThat(ChangeClaims.answer("`Inkwell` is updated to pour", ONLY_NIB_CHANGED, TREE)).isFalse();
        assertThat(ChangeClaims.answer("Nib is modified to support the new behavior", ONLY_NIB_CHANGED, TREE)).isNull();
        assertThat(ChangeClaims.answer("Quill is modified", ONLY_NIB_CHANGED, TREE)).as("not a file of the tree").isNull();
        assertThat(ChangeClaims.answer("Inkwell is modified", null, TREE)).as("not adopted: no record").isNull();
    }

    @Test
    void aFileClaimedToHoldATextItDoesNotIsContradicted_oneThatDoesIsLeftToTheReading() {
        assertThat(ChangeClaims.answer("Inkwell includes the 'ran with:' format", ONLY_NIB_CHANGED, TREE)).isFalse();
        assertThat(ChangeClaims.answer("Nib contains the \"width: \" label", ONLY_NIB_CHANGED, TREE)).isNull();
        assertThat(ChangeClaims.answer("Inkwell pours three drops", ONLY_NIB_CHANGED, TREE)).as("no such claim").isNull();
    }
}
