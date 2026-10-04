package com.walpro.autobuilder.core.briefing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-EA: a briefing is split into sections and a prompt carries only the ones it is about, whole, within a budget. */
class BriefingTest {

    private static final String TICKET = """
            # The ticket
            - **The inkwell refills itself** when the level drops below a mark.
            - **2026-01-02 decision:** the refill never overflows; a full well refuses the refill.
            - **2026-01-03:** the quill's nib is unrelated to refilling.
            """;
    private static final String CONVENTIONS = """
            # Conventions
            ## discover, do not list
            Facts about the platform are discovered, never kept in a table.
            """;

    private static Briefing briefing() {
        List<Briefing.Section> s = new ArrayList<>(Briefing.parse("ticket", TICKET, false));
        s.addAll(Briefing.parse("conventions", CONVENTIONS, true));
        return new Briefing(s);
    }

    @Test
    void aDocumentSplitsAtHeadingsAndTopLevelBoldItems() {
        List<Briefing.Section> s = Briefing.parse("ticket", TICKET, false);
        assertThat(s).extracting(Briefing.Section::heading)
                .containsExactly("The inkwell refills itself", "2026-01-02 decision:", "2026-01-03:");
    }

    @Test
    void aPromptCarriesTheSectionsItIsAboutAndTheConventionsNothingElse() {
        List<Briefing.Section> chosen = briefing().select("Make the inkwell refuse a refill when it is full", 2000);
        assertThat(chosen).extracting(Briefing.Section::heading)
                .startsWith("discover, do not list")
                .contains("The inkwell refills itself", "2026-01-02 decision:")
                .doesNotContain("2026-01-03:");
    }

    @Test
    void aSectionThatDoesNotFitIsLeftOutWholeNeverCut() {
        String block = briefing().promptBlock("refill the inkwell", 90);
        assertThat(block).contains("discover, do not list").doesNotContain("refills itself");
        assertThat(briefing().promptBlock("refill the inkwell", 30)).isNull();
    }

    @Test
    void noBriefingDirectoryMeansNoBlock(@TempDir Path root) throws Exception {
        BriefingSource source = new BriefingSource();
        ReflectionTestUtils.setField(source, "fileService", new FileService(root.toString(), new ObjectMapper()));
        assertThat(source.block("anything", 8000)).isNull();

        Files.createDirectories(root.resolve(BriefingSource.DIR));
        Files.writeString(root.resolve(BriefingSource.DIR).resolve("ticket.md"), TICKET);
        Files.writeString(root.resolve(BriefingSource.DIR).resolve("conventions.md"), CONVENTIONS);
        assertThat(source.block("the inkwell refill", 8000)).contains("BRIEFING").contains("refills itself")
                .contains("discover, do not list");
    }
}
