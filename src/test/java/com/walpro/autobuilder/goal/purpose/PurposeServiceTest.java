package com.walpro.autobuilder.goal.purpose;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T4-EC slice 1: the seed as entry 1 of the input ledger. */
class PurposeServiceTest {

    @TempDir
    Path base;

    private FileService files;

    @BeforeEach
    void setUp() {
        files = new FileService(base.toString(), new ObjectMapper());
    }

    @Test
    void aRecordedSeedIsTheSpecificationAndEntryOne() {
        PurposeService purpose = PurposeService.fileOnly(files);
        purpose.recordSeed("Build a quill registry.", "operator");

        InputEntry seed = purpose.ledger().seed();
        assertThat(purpose.specification()).isEqualTo("Build a quill registry.");
        assertThat(purpose.ledger().entries()).hasSize(1);
        assertThat(seed.sequence()).isEqualTo(1);
        assertThat(seed.kind()).isEqualTo(InputEntry.KIND_SEED);
        assertThat(seed.addedBy()).isEqualTo("operator");
        assertThat(seed.id()).startsWith("input-");
    }

    @Test
    void theDigestIsOverTheSeedFileAsWrittenNotTheTrimmedRoot() throws Exception {
        String raw = "  Build a quill registry.\n";
        files.writeFile(PurposeService.SEED_FILE, raw);
        PurposeService purpose = PurposeService.fileOnly(files);
        purpose.recordSeed(raw.trim(), "seed-file");

        assertThat(purpose.specification()).isEqualTo("Build a quill registry.");
        assertThat(purpose.seedKey()).isEqualTo(PurposeService.sha256(raw));
    }

    @Test
    void withNoSeedFileTheDigestIsOverTheContent() {
        PurposeService purpose = PurposeService.fileOnly(files);
        purpose.recordSeed("Build a quill registry.", "operator");
        assertThat(purpose.seedKey()).isEqualTo(PurposeService.sha256("Build a quill registry."));
    }

    @Test
    void theLedgerSurvivesARestartThroughItsFile() throws Exception {
        PurposeService first = PurposeService.fileOnly(files);
        first.recordSeed("Build a quill registry.", "operator");

        assertThat(Files.readString(base.resolve(PurposeService.PATH))).contains("Build a quill registry.");
        PurposeService again = PurposeService.fileOnly(files);
        assertThat(again.ledger()).isEqualTo(first.ledger());
    }

    @Test
    void aRootWithoutALedgerGetsItsEntryFromItsText_andAMatchingOneIsLeftAlone() {
        PurposeService purpose = PurposeService.fileOnly(files);
        purpose.ensureFor("Build a quill registry.");
        InputEntry migrated = purpose.ledger().seed();
        assertThat(migrated.addedBy()).isEqualTo("migrated");

        purpose.ensureFor("Build a quill registry.");
        assertThat(purpose.ledger().seed()).isSameAs(migrated);
    }

    @Test
    void aNewRootStartsANewLedger() {
        PurposeService purpose = PurposeService.fileOnly(files);
        purpose.recordSeed("Build a quill registry.", "seed-file");
        purpose.recordSeed("Build an ink ledger.", "reset");
        assertThat(purpose.ledger().entries()).singleElement()
                .satisfies(e -> assertThat(e.content()).isEqualTo("Build an ink ledger."));
    }

    @Test
    void moreThanOneInputIsNotReadAsOneSpecificationYet() {
        InputEntry a = new InputEntry("input-a", 1, "seed", "a", null, "seed-file", null, null, null);
        InputEntry b = new InputEntry("input-b", 2, "project", "b", null, "operator", null, null, null);
        assertThatThrownBy(() -> new InputLedger(java.util.List.of(a, b)).specification())
                .isInstanceOf(IllegalStateException.class);
    }
}
