package com.walpro.autobuilder.core.build;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.FileService;

import java.util.List;

/**
 * T6-U: the tests written from the requirements' own wording before any code changed -- one per atomic claim of each
 * obligation the operator marked {@code gate: derived-test} -- kept under {@value #PATH}, relative to the target.
 *
 * <p>Data, not a component: {@link ProtectedPaths} reads it to freeze the accepted tests, and the service
 * that writes them reads it back after a restart. An entry's {@code content} is the accepted text; the
 * file in the tree is a copy of it, rewritten from here whenever the tree has lost or changed it.
 */
public final class DerivedTestsFile {

    public static final String PATH = "meta/derived-tests.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    private DerivedTestsFile() {
    }

    public enum Status { ACCEPTED, UNACCEPTED }

    /**
     * @param requirement the obligation's text, marker removed, exactly as the closure edge reads it
     * @param claim       the one atomic claim of that requirement this test checks
     * @param path        where the test lives, relative to {@code output/}
     * @param content     the accepted text (for UNACCEPTED: the last attempt, kept for reading)
     * @param reason      why the last attempt was not accepted, or what the review said of the accepted one
     */
    public record Entry(String requirement, String claim, String path, String content, Status status, String reason) {
        public boolean accepted() {
            return status == Status.ACCEPTED;
        }
    }

    public static String readText(FileService files) {
        try {
            return files.exists(PATH) ? files.readFile(PATH) : "";
        } catch (Exception e) {
            return "";
        }
    }

    public static List<Entry> parse(String text) {
        if (text == null || text.isBlank()) return List.of();
        try {
            return List.copyOf(JSON.readValue(text, new TypeReference<List<Entry>>() {}));
        } catch (Exception e) {
            return List.of();
        }
    }

    public static List<Entry> read(FileService files) {
        return parse(readText(files));
    }

    public static void write(FileService files, List<Entry> entries) throws java.io.IOException {
        files.writeFile(PATH, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(entries));
    }
}
