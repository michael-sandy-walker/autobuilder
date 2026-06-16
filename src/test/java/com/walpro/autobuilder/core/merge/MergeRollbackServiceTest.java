package com.walpro.autobuilder.core.merge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.FileService;

class MergeRollbackServiceTest {

    @TempDir
    Path tempDir;

    private FileService fileService;
    private final MergeRollbackService service = new MergeRollbackService();

    @BeforeEach
    void setUp() {
        fileService = new FileService(tempDir.toString(), new ObjectMapper());
    }

    private void write(String rel, String content) throws IOException {
        fileService.writeFileResolved(fileService.resolveSecurePath(rel), content);
    }

    @Test
    void rollback_preservesInjectorPaths_deletesNewMutationFiles_restoresModified() throws IOException {
        // Staged state on disk: a bootstrapped pom (injector, newly added), a new LLM source file
        // (newly added), and a modified existing file.
        write("output/pom.xml", "<project>bootstrapped</project>");
        write("output/New.java", "class New {}");
        write("output/Existing.java", "MODIFIED");

        Map<String, String> originals = new LinkedHashMap<>();
        originals.put("pom.xml", "");          // newly added by the injector
        originals.put("New.java", "");         // newly added by the LLM mutation
        originals.put("Existing.java", "ORIGINAL"); // pre-existing, modified by the mutation
        Set<String> newlyAdded = new LinkedHashSet<>(Set.of("pom.xml", "New.java"));

        MergeRollbackPoint point = service.snapshot("c1", originals, newlyAdded);

        // Evaluation rejected the (compile-clean) mutation: preserve the injector's pom.
        service.rollback(point, "output", fileService, Set.of("pom.xml"));

        assertThat(fileService.exists("output/pom.xml"))
                .as("injector-bootstrapped pom is preserved across an evaluation-rejection rollback")
                .isTrue();
        assertThat(fileService.readString("output/pom.xml")).contains("bootstrapped");
        assertThat(fileService.exists("output/New.java"))
                .as("the rejected LLM mutation's new file is still removed")
                .isFalse();
        assertThat(fileService.readString("output/Existing.java"))
                .as("a modified existing file is still restored to its original content")
                .isEqualTo("ORIGINAL");
    }

    @Test
    void rollback_withoutPreserve_deletesEverythingNewlyAdded() throws IOException {
        write("output/pom.xml", "<project/>");
        write("output/New.java", "class New {}");

        Map<String, String> originals = new LinkedHashMap<>();
        originals.put("pom.xml", "");
        originals.put("New.java", "");
        MergeRollbackPoint point = service.snapshot("c1", originals,
                new LinkedHashSet<>(Set.of("pom.xml", "New.java")));

        // Compile-failure rollback (no preserve): the fixes did not achieve a green build, so they go too.
        service.rollback(point, "output", fileService);

        assertThat(fileService.exists("output/pom.xml")).isFalse();
        assertThat(fileService.exists("output/New.java")).isFalse();
    }
}
