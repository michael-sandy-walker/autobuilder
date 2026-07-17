package com.walpro.autobuilder.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.model.FileInfo;

/**
 * T4-CL: {@link FileService} is a filesystem utility with no graph dependency — it is
 * constructed directly here rather than through {@code @SpringBootTest}. Booting the full
 * context (as this test did before) started every bean including the graph layer and
 * {@code GoalPlannerService}'s migrate-on-init, which under the test profile targets the
 * production Neo4j; a file-operations test has no business reaching a database. Direct
 * construction also isolates the writes in a {@link TempDir} instead of the committed
 * {@code test-root/}.
 */
class FileServiceTest {

    @TempDir
    Path tempDir;

    private FileService fileService;

    @BeforeEach
    void setUp() {
        fileService = new FileService(tempDir.toString(), new ObjectMapper());
    }

    @Test
    void testWriteAndReadFile() throws IOException {
        String relPath = "subdir/test.txt";
        String content = "Hello, test!";

        fileService.writeFile(relPath, content);
        String read = fileService.readFile(relPath);

        assertEquals(content, read);
    }

    @Test
    void testListFiles() throws IOException {
        fileService.writeFile("a.txt", "A");
        fileService.writeFile("b.txt", "B");

        List<FileInfo> files = fileService.listFiles("");
        boolean containsA = false;
        boolean containsB = false;
        for (FileInfo file : files) {
            if (file.getName().equals("a.txt")) {
                containsA = true;
            } else if (file.getName().equals("b.txt")) {
                containsB = true;
            }
        }
        assertTrue(containsA);
        assertTrue(containsB);
    }

    @Test
    void testSecurityBlockParentEscape() {
        assertThrows(SecurityException.class, () -> {
            fileService.readFile("../../etc/passwd");
        });
    }
}
