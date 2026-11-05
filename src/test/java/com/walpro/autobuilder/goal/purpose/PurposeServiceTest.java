package com.walpro.autobuilder.goal.purpose;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.SeedObligationsFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

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
    void aLaterInputIsAppended_andTheSpecificationIsOneTextWrittenAsAFile() throws Exception {
        PurposeService purpose = PurposeService.fileOnly(files);
        purpose.recordSeed("Build a quill registry.", "seed-file");
        assertThat(base.resolve(PurposeService.SPEC_FILE)).as("only the seed: it is the specification").doesNotExist();
        InputEntry second = purpose.addInput(InputEntry.KIND_TEXT, "Add an ink ledger.", "operator");

        assertThat(second.sequence()).isEqualTo(2);
        assertThat(purpose.ledger().seed().content()).isEqualTo("Build a quill registry.");
        // T4-EG: one text, nothing in it says which part came from where
        assertThat(purpose.specification()).isEqualTo("Build a quill registry.\n\nAdd an ink ledger.");
        assertThat(base.resolve(PurposeService.SPEC_FILE)).hasContent("Build a quill registry.\n\nAdd an ink ledger.");
        assertThat(PurposeService.fileOnly(files).ledger()).isEqualTo(purpose.ledger());
    }

    /** T4-EG: a pending import is a proposal -- not in the specification or the requirements until it is kept. */
    @Test
    void aPendingImportCountsForNothingUntilKept() throws Exception {
        PurposeService purpose = PurposeService.fileOnly(files);
        purpose.recordSeed("Build a quill registry.", "seed-file");
        InputEntry brought = purpose.addProjectInput("use the reader", "operator", null, null, "digest");
        InputEvent pending = purpose.recordPendingImport(brought.id(), "reader", "operator");
        files.ensureFolderExists("meta/inputs/" + brought.id());
        files.writeFile(purpose.obligationsPath(brought), SeedObligationsFile.render(brought.content(),
                java.util.List.of("A reader reads lines.")));

        assertThat(purpose.specification()).isEqualTo("Build a quill registry.");
        assertThat(purpose.obligationsOrClauses()).doesNotContain("A reader reads lines.");

        purpose.keepImport(pending, "operator");
        assertThat(purpose.specification()).isEqualTo("Build a quill registry.\n\nuse the reader");
        assertThat(purpose.obligationsOrClauses()).contains("A reader reads lines.");
    }

    @Test
    void requirementsAreReadAcrossEveryInput_eachCheckedAgainstItsOwnText_asOneList() throws Exception {
        PurposeService purpose = PurposeService.fileOnly(files);
        purpose.recordSeed("Build a quill registry.", "seed-file");
        InputEntry seed = purpose.ledger().seed();
        InputEntry second = purpose.addInput(InputEntry.KIND_TEXT, "Add an ink ledger.", "operator");
        files.writeFile(purpose.obligationsPath(seed), SeedObligationsFile.render(seed.content(),
                java.util.List.of("gate: tests The whole suite passes.", "rule: Keep the names.", "A quill has a nib.")));
        files.ensureFolderExists("meta/inputs/" + second.id());
        files.writeFile(purpose.obligationsPath(second), SeedObligationsFile.render(second.content(),
                java.util.List.of("gate: tests The ink tests pass.", "rule: Keep the units.", "A ledger holds ink in millilitres.")));

        assertThat(purpose.obligationsPath(seed)).isEqualTo(SeedObligationsFile.PATH);
        assertThat(purpose.obligationsPath(second)).isEqualTo("meta/inputs/" + second.id() + "/obligations.txt");
        assertThat(purpose.answeredByGate("tests")).containsExactly("The whole suite passes.", "The ink tests pass.");
        assertThat(purpose.rules()).hasSize(2);
        assertThat(purpose.obligationsOrClauses()).contains("A quill has a nib.", "A ledger holds ink in millilitres.");
        assertThat(purpose.writtenLines()).hasSize(6);
    }

    @Test
    void aLaterInputWithoutAFileAddsNothingWritten() throws Exception {
        PurposeService purpose = PurposeService.fileOnly(files);
        purpose.recordSeed("Build a quill registry.", "seed-file");
        purpose.addInput(InputEntry.KIND_TEXT, "Add an ink ledger.", "operator");
        assertThat(purpose.answeredByGate("tests")).isEmpty();
        assertThat(purpose.writtenLines()).isEmpty();
    }

    /**
     * T4-EG: the file is the project's state and the graph its history. A rewind puts the file back and leaves the
     * graph's record of an import made since, so the graph may not bring that import back at the next boot.
     */
    @Test
    void theFileIsTheStateTheGraphIsNotReadOverIt() throws Exception {
        PurposeService.fileOnly(files).recordSeed("Build a quill registry.", "operator");
        InputEntry seed = PurposeService.fileOnly(files).ledger().seed();
        var graph = org.mockito.Mockito.mock(com.walpro.autobuilder.graph.input.InputGraphService.class);
        org.mockito.Mockito.when(graph.findAll()).thenReturn(java.util.List.of(seed, brought()));

        PurposeService loaded = new PurposeService(files, provider(graph), provider(null), new GoalPlannerConfigPropertiesStub());
        loaded.load();

        assertThat(loaded.ledger().entries()).containsExactly(seed);
        org.mockito.Mockito.verify(graph, org.mockito.Mockito.never()).findAll();
    }

    /** T4-ED, kept for a project whose file is gone: the graph answers, and the file the home screen reads is written. */
    @Test
    void withoutTheFileTheLedgerComesFromTheGraphAndIsWritten() throws Exception {
        PurposeService.fileOnly(files).recordSeed("Build a quill registry.", "operator");
        InputEntry seed = PurposeService.fileOnly(files).ledger().seed();
        Files.delete(base.resolve(PurposeService.PATH));
        InputEvent out = new InputEvent("event-w", InputEvent.WITHDRAWAL, "input-x", "withdrawn", null, null, null, null,
                java.util.List.of(), InputEvent.DECIDED, null, "operator", java.time.Instant.now());
        var graph = org.mockito.Mockito.mock(com.walpro.autobuilder.graph.input.InputGraphService.class);
        org.mockito.Mockito.when(graph.findAll()).thenReturn(java.util.List.of(seed, brought()));
        org.mockito.Mockito.when(graph.findEvents()).thenReturn(java.util.List.of(out));

        PurposeService fromGraph = new PurposeService(files, provider(graph), provider(null), new GoalPlannerConfigPropertiesStub());
        fromGraph.load();

        assertThat(Files.readString(base.resolve(PurposeService.PATH))).contains("input-x").contains("\"withdrawal\"");
        assertThat(PurposeService.fileOnly(files).ledger().withdrawn("input-x")).isTrue();
    }

    /** T4-EG: a rewind returns the inputs' files to the point, and a later point brings them back. */
    @Test
    void theInputsFilesGoBackAndForwardWithTheirPoint() throws Exception {
        PurposeService purpose = PurposeService.fileOnly(files);
        purpose.recordSeed("Build a quill registry.", "operator");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        com.fasterxml.jackson.databind.JsonNode before = mapper.valueToTree(purpose.captureState());

        InputEntry input = purpose.addProjectInput("use the reader", "operator", null, null, "digest");
        Files.createDirectories(base.resolve("meta/inputs/" + input.id()));
        Files.writeString(base.resolve("meta/inputs/" + input.id() + "/placed.txt"), "src/main/java/Reader.java\n");
        Files.createDirectories(base.resolve("meta/adoption-baseline/src"));
        Files.writeString(base.resolve("meta/adoption-baseline/src/Reader.java"), "class Reader {}");
        com.fasterxml.jackson.databind.JsonNode after = mapper.valueToTree(purpose.captureState());

        purpose.restoreState(before, mapper);
        assertThat(purpose.ledger().entries()).hasSize(1);
        assertThat(base.resolve("meta/inputs")).doesNotExist();
        assertThat(base.resolve("meta/adoption-baseline")).doesNotExist();
        assertThat(PurposeService.fileOnly(files).ledger().entries()).hasSize(1);

        purpose.restoreState(after, mapper);
        assertThat(purpose.ledger().entries()).extracting(InputEntry::id).contains(input.id());
        assertThat(base.resolve("meta/inputs/" + input.id() + "/placed.txt")).hasContent("src/main/java/Reader.java\n");
        assertThat(base.resolve("meta/adoption-baseline/src/Reader.java")).hasContent("class Reader {}");
    }

    /** T4-EG: a point from before the inputs were captured restores nothing of them -- it must not delete them. */
    @Test
    void aPointThatCapturedNoInputsLeavesThemAsTheyAre() throws Exception {
        PurposeService purpose = PurposeService.fileOnly(files);
        purpose.recordSeed("Build a quill registry.", "operator");
        InputEntry input = purpose.addProjectInput("use the reader", "operator", null, null, "digest");

        purpose.restoreState(null, new ObjectMapper());

        assertThat(purpose.ledger().entries()).extracting(InputEntry::id).contains(input.id());
        assertThat(base.resolve(PurposeService.PATH)).exists();
    }

    private static InputEntry brought() {
        return new InputEntry("input-x", 2, InputEntry.KIND_PROJECT, "use x", "d", "operator",
                java.time.Instant.now(), null, null, null, null);
    }

    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<T> p = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private static final class GoalPlannerConfigPropertiesStub extends com.walpro.autobuilder.config.GoalPlannerConfigProperties {
        @Override
        public boolean isSyncFromGraph() {
            return true;
        }
    }
}
