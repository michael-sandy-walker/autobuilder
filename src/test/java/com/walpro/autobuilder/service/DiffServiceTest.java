package com.walpro.autobuilder.service;

import com.walpro.autobuilder.config.DiffConfig;
import com.walpro.autobuilder.diff.DiffService;
import com.walpro.autobuilder.diff.DiffType;
import com.walpro.autobuilder.model.FileInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiffServiceTest {

    private FileService fileService;
    private DiffService diffService;

    @BeforeEach
    void setup() {
        fileService = mock(FileService.class);
        DiffConfig config = Mockito.mock(DiffConfig.class);
        Mockito.when(config.getExcludeFolders()).thenReturn(List.of());
        diffService = new DiffService(fileService, config);        
    }

    @Test
    void testCompareFolders_withChanges() throws IOException {
        // Setup folder "a"
        when(fileService.listFiles("a")).thenReturn(List.of(
                new FileInfo("unchanged.txt", 10, 0),
                new FileInfo("modified.txt", 10, 0),
                new FileInfo("omitted.txt", 10, 0)
        ));
        when(fileService.readFile("a/unchanged.txt")).thenReturn("same");
        when(fileService.readFile("a/modified.txt")).thenReturn("old");
        when(fileService.readFile("a/omitted.txt")).thenReturn("gone");

        // Setup folder "b"
        when(fileService.listFiles("b")).thenReturn(List.of(
                new FileInfo("unchanged.txt", 10, 0),
                new FileInfo("modified.txt", 10, 0),
                new FileInfo("added.txt", 10, 0)
        ));
        when(fileService.readFile("b/unchanged.txt")).thenReturn("same");
        when(fileService.readFile("b/modified.txt")).thenReturn("new");
        when(fileService.readFile("b/added.txt")).thenReturn("hello");

        var report = diffService.compareFolders("a", "b", Collections.emptySet());

        assertEquals(2, report.entries().size());
        assertTrue(report.entries().stream().anyMatch(e -> e.type().equals(DiffType.MODIFIED) && e.path().equals("modified.txt")));
//        assertTrue(report.entries().stream().anyMatch(e -> e.type().equals(DiffType.DELETED) && e.path().equals("deleted.txt")));
        assertTrue(report.entries().stream().anyMatch(e -> e.type().equals(DiffType.ADDED) && e.path().equals("added.txt")));
    }
}
