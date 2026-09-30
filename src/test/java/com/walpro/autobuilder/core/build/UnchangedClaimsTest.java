package com.walpro.autobuilder.core.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-DK: a claim that nothing was changed, answered by the changes since adoption. The wordings are the
 * loop's own, from receiver-questions runs 2026-09-26_21-50-02 and _22-44-36.
 */
class UnchangedClaimsTest {

    private static final String GOAL = "The one class to change is StuckQuestions. It already works as its class comment says.";
    private static final AdoptionManifest.Changes ONLY_THE_ONE = new AdoptionManifest.Changes(
            List.of("src/main/java/com/walpro/autobuilder/core/build/StuckQuestions.java"), List.of(), List.of());

    @TempDir
    Path root;

    @Test
    void theLoopsOwnWordingIsProvenWhenOnlyTheNamedClassChanged() {
        for (String c : List.of(
                "No files under `src/test/java` have been modified",
                "All other classes under `src/main/java` remain unchanged",
                "The project's packages, classes, and naming are preserved",
                "Only StuckQuestions.java under src/main/java is modified; no file under src/test is changed")) {
            assertThat(UnchangedClaims.provenBy(c, ONLY_THE_ONE, GOAL)).as(c).isTrue();
        }
    }

    @Test
    void aChangeTheClaimDeniesIsNotProven() {
        AdoptionManifest.Changes testTouched = new AdoptionManifest.Changes(
                List.of("src/main/java/com/walpro/autobuilder/core/build/StuckQuestions.java",
                        "src/test/java/com/walpro/autobuilder/core/build/StuckQuestionsSpecTest.java"), List.of(), List.of());
        assertThat(UnchangedClaims.provenBy("No files under `src/test/java` have been modified", testTouched, GOAL)).isFalse();

        AdoptionManifest.Changes otherChanged = new AdoptionManifest.Changes(
                List.of("src/main/java/com/walpro/autobuilder/core/build/DeclaredMembers.java"), List.of(), List.of());
        assertThat(UnchangedClaims.provenBy("All other classes under `src/main/java` remain unchanged", otherChanged, GOAL)).isFalse();
        assertThat(UnchangedClaims.provenBy("Only StuckQuestions.java under src/main/java is modified", otherChanged, GOAL)).isFalse();

        AdoptionManifest.Changes removed = new AdoptionManifest.Changes(List.of(), List.of(),
                List.of("src/main/java/com/walpro/autobuilder/core/build/DeclaredMembers.java"));
        assertThat(UnchangedClaims.provenBy("The project's packages, classes, and naming are preserved", removed, GOAL)).isFalse();
    }

    @Test
    void aClaimWithAnythingElseInItIsLeftToTheReader() {
        assertThat(UnchangedClaims.provenBy("No files under src/test/java have been modified and every coil is aligned",
                ONLY_THE_ONE, GOAL)).isFalse();
        assertThat(UnchangedClaims.provenBy("StuckQuestions answers JDK receivers", ONLY_THE_ONE, GOAL)).isFalse();
        assertThat(UnchangedClaims.provenBy("No files under src/test/java have been modified", null, GOAL)).isFalse();
    }

    @Test
    void theManifestRecordsTheAdoptedTreeOnceAndReportsWhatChangedSince() throws Exception {
        Path out = root.resolve("output");
        Files.createDirectories(out.resolve("src/main/java/org/ink"));
        Files.createDirectories(out.resolve("target"));
        Files.writeString(out.resolve("src/main/java/org/ink/Quill.java"), "class Quill {}");
        Files.writeString(out.resolve("src/main/java/org/ink/Well.java"), "class Well {}");
        Files.writeString(out.resolve("target/Quill.class"), "bytes");
        Files.createDirectories(root.resolve("meta"));
        AdoptionManifest manifest = new AdoptionManifest(new FileService(root.toString(), new ObjectMapper()));

        manifest.recordIfAbsent();
        Files.writeString(out.resolve("src/main/java/org/ink/Quill.java"), "class Quill { int nib; }");
        Files.writeString(out.resolve("src/main/java/org/ink/Nib.java"), "class Nib {}");
        Files.delete(out.resolve("src/main/java/org/ink/Well.java"));
        Files.writeString(out.resolve("target/Quill.class"), "other bytes");
        manifest.recordIfAbsent(); // a second call keeps the adoption's manifest

        AdoptionManifest.Changes c = manifest.changes().orElseThrow();
        assertThat(c.modified()).containsExactly("src/main/java/org/ink/Quill.java");
        assertThat(c.added()).containsExactly("src/main/java/org/ink/Nib.java");
        assertThat(c.removed()).containsExactly("src/main/java/org/ink/Well.java");
    }

    /** Shape only: every part a no-change claim, whatever the changes say. */
    @org.junit.jupiter.api.Test
    void aClaimThatNothingChangedIsRecognisedWithoutAnyChanges() {
        org.assertj.core.api.Assertions.assertThat(UnchangedClaims.isClaim("All other classes under src/main remain unchanged")).isTrue();
        org.assertj.core.api.Assertions.assertThat(UnchangedClaims.isClaim("No files under src/test are modified; only Quill.java is modified")).isTrue();
        org.assertj.core.api.Assertions.assertThat(UnchangedClaims.isClaim("No files under src/test are modified; Quill dries in three seconds")).isFalse();
        org.assertj.core.api.Assertions.assertThat(UnchangedClaims.isClaim("")).isFalse();
    }
    /**
     * The templates the planner is given are the shapes this class proves -- filled in, each is a claim and
     * is proven when only the named class changed; and one that a change contradicts is not.
     */
    @Test
    void everyTemplateTheCriteriaAreWrittenInIsRecognisedAndProven() {
        for (String template : UnchangedClaims.TEMPLATES) {
            String claim = template.replace("<path>", "src/test").replace("<Class>", "StuckQuestions");
            if (template.startsWith("All other") || template.startsWith("Only")) {
                claim = template.replace("<path>", "src/main/java").replace("<Class>", "StuckQuestions");
            }
            assertThat(UnchangedClaims.isClaim(claim)).as(claim).isTrue();
            assertThat(UnchangedClaims.provenBy(claim, ONLY_THE_ONE, GOAL)).as(claim).isTrue();
        }
        AdoptionManifest.Changes testTouched = new AdoptionManifest.Changes(
                List.of("src/test/java/com/walpro/autobuilder/core/build/StuckQuestionsTest.java"), List.of(), List.of());
        assertThat(UnchangedClaims.provenBy("No file under src/test is changed", testTouched, GOAL)).isFalse();
    }

    @Test
    void thePlannerIsGivenTheTemplates() {
        String prompt = com.walpro.autobuilder.protocol.planner.PlannerProtocolContract.getSystemPrompt();
        for (String template : UnchangedClaims.TEMPLATES) assertThat(prompt).contains("'" + template + "'");
    }
}
