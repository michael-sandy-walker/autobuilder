package com.walpro.autobuilder.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.walpro.autobuilder.model.FileInfo;

@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileServiceTest {

    @TempDir
    Path tempDir;

    @Autowired
    FileService fileService;

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
