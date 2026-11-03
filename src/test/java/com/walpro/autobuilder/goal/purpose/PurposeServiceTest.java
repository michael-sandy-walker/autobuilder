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
    void aLaterInputIsAppended_andSeveralReadInArrivalOrderEachNamed() {
        PurposeService purpose = PurposeService.fileOnly(files);
        purpose.recordSeed("Build a quill registry.", "seed-file");
        InputEntry second = purpose.addInput(InputEntry.KIND_TEXT, "Add an ink ledger.", "operator");

        assertThat(second.sequence()).isEqualTo(2);
        assertThat(purpose.ledger().seed().content()).isEqualTo("Build a quill registry.");
        assertThat(purpose.specification()).isEqualTo("--- input 1 (seed) ---\nBuild a quill registry.\n\n"
                + "--- input 2 (text) ---\nAdd an ink ledger.");
        assertThat(PurposeService.fileOnly(files).ledger()).isEqualTo(purpose.ledger());
    }

    @Test
    void requirementsAreReadAcrossEveryInput_eachCheckedAgainstItsOwnText_andKnowTheirInput() throws Exception {
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
        assertThat(purpose.gated("tests")).extracting(PurposeService.Requirement::inputId).containsExactly(seed.id(), second.id());
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
     * T4-ED: loaded from the graph, the file the home screen reads is brought level (field: three imports withdrawn in
     * the graph were still listed in the file, so the project list and the opened project disagreed).
     */
    @Test
    void aLedgerLoadedFromTheGraphRewritesTheFile() throws Exception {
        PurposeService.fileOnly(files).recordSeed("Build a quill registry.", "operator");
        InputEntry seed = PurposeService.fileOnly(files).ledger().seed();
        InputEntry brought = new InputEntry("input-x", 2, InputEntry.KIND_PROJECT, "use x", "d", "operator",
                java.time.Instant.now(), null, null, null, null);
        InputEvent out = new InputEvent("event-w", InputEvent.WITHDRAWAL, "input-x", "withdrawn", null, null, null, null,
                java.util.List.of(), InputEvent.DECIDED, null, "operator", java.time.Instant.now());
        var graph = org.mockito.Mockito.mock(com.walpro.autobuilder.graph.input.InputGraphService.class);
        org.mockito.Mockito.when(graph.findAll()).thenReturn(java.util.List.of(seed, brought));
        org.mockito.Mockito.when(graph.findEvents()).thenReturn(java.util.List.of(out));
        GoalPlannerConfigPropertiesStub config = new GoalPlannerConfigPropertiesStub();

        PurposeService fromGraph = new PurposeService(files, provider(graph), provider(null), config);
        fromGraph.load();

        String file = Files.readString(base.resolve(PurposeService.PATH));
        assertThat(file).contains("input-x").contains("\"withdrawal\"");
        assertThat(PurposeService.fileOnly(files).ledger().withdrawn("input-x")).isTrue();
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
