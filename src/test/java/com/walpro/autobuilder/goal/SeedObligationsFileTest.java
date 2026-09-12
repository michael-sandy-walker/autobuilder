package com.walpro.autobuilder.goal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** T6-S: the four states, and the guard that makes normalising an edit safe. */
class SeedObligationsFileTest {

    private static final String SEED = "check <file> reports malformed rows. Exit 0 when clean.";
    private static final List<String> OBLIGATIONS =
            List.of("check <file> reports malformed rows", "Exit 0 when clean");

    @Test
    void aPristineFileIsUsedAsWritten() {
        var parsed = SeedObligationsFile.read(SeedObligationsFile.render(SEED, OBLIGATIONS), SEED);
        assertThat(parsed.state()).isEqualTo(SeedObligationsFile.State.PRISTINE);
        assertThat(parsed.obligations()).isEqualTo(OBLIGATIONS);
    }

    /** A hand edit is kept and reported as the operator's — never overwritten from the seed. */
    @Test
    void aHandEditedFileIsKeptAndReportedAsEdited() {
        String edited = SeedObligationsFile.render(SEED, OBLIGATIONS)
                .replace("Exit 0 when clean", "Exit 0 when every row is well formed");
        var parsed = SeedObligationsFile.read(edited, SEED);
        assertThat(parsed.state()).isEqualTo(SeedObligationsFile.State.EDITED);
        assertThat(parsed.obligations()).contains("Exit 0 when every row is well formed");
        assertThat(SeedObligationsFile.obligationsFrom(parsed)).isPresent();
    }

    @Test
    void aChangedSeedWithNoEditsRegenerates() {
        var parsed = SeedObligationsFile.read(
                SeedObligationsFile.render(SEED, OBLIGATIONS), SEED + " Exit 2 otherwise.");
        assertThat(parsed.state()).isEqualTo(SeedObligationsFile.State.SEED_MOVED);
        assertThat(SeedObligationsFile.obligationsFrom(parsed)).isEmpty();
    }

    /**
     * ⚠ The row the body hash exists for. Without it, editing a seed silently destroys the
     * operator's corrections; with it, the file is kept and the operator is told.
     */
    @Test
    void aChangedSeedWithEditsIsKeptRatherThanClobbered() {
        String edited = SeedObligationsFile.render(SEED, OBLIGATIONS)
                .replace("Exit 0 when clean", "Exit 0 when every row is well formed");
        var parsed = SeedObligationsFile.read(edited, SEED + " Exit 2 otherwise.");
        assertThat(parsed.state()).isEqualTo(SeedObligationsFile.State.SEED_MOVED_WITH_EDITS);
        assertThat(SeedObligationsFile.obligationsFrom(parsed)).isPresent();
    }

    @Test
    void anUnparseableFileRegenerates() {
        assertThat(SeedObligationsFile.read("just some prose\nwith no headers\n", SEED).state())
                .isEqualTo(SeedObligationsFile.State.MALFORMED);
    }

    /** Formatting-only differences are accepted as a normalisation. */
    @Test
    void normalisationThatOnlyReformatsIsAccepted() {
        assertThat(SeedObligationsFile.preservesContent(
                List.of("- exit 0 when clean.", "  reports malformed rows  "),
                List.of("Exit 0 when clean", "reports malformed rows"))).isTrue();
    }

    /**
     * ⚠ And the one that matters: handing prose to a model and asking for formatting invites it to
     * rewrite instead. A normalisation that changes what the operator wrote is refused, and their
     * version stands — a badly formatted obligation they meant beats a tidy one they did not.
     */
    @Test
    void normalisationThatChangesTheOperatorsWordsIsRefused() {
        assertThat(SeedObligationsFile.preservesContent(
                List.of("Exit 0 when clean"),
                List.of("Exit 0 when the file is valid"))).isFalse();
        assertThat(SeedObligationsFile.preservesContent(
                List.of("Exit 0 when clean", "reports malformed rows"),
                List.of("Exit 0 when clean"))).isFalse();
    }
}
