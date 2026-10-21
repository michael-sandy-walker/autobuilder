package com.walpro.autobuilder.core.merge.session;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** A session as a directory: {@code edits.ndjson} (the authority, one edit per line) and {@code graph.json} (derived). */
public class FileMergeSessionStore implements MergeSessionStore {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final Path root;

    public FileMergeSessionStore(Path root) {
        this.root = root;
    }

    private Path dir(String id) {
        if (id == null || !id.matches("[A-Za-z0-9._+-]{1,120}")) throw new IllegalArgumentException("not a session id: " + id);
        return root.resolve(id);
    }

    @Override
    public void create(String sessionId) throws IOException {
        Path d = dir(sessionId);
        if (Files.exists(d.resolve("edits.ndjson"))) throw new IllegalArgumentException("session exists: " + sessionId);
        Files.createDirectories(d);
        Files.writeString(d.resolve("edits.ndjson"), "");
    }

    @Override
    public boolean exists(String sessionId) {
        return Files.isRegularFile(dir(sessionId).resolve("edits.ndjson"));
    }

    @Override
    public void append(String sessionId, List<MergeSession.Edit> edits) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (MergeSession.Edit e : edits) sb.append(MAPPER.writeValueAsString(e)).append('\n');
        Files.writeString(dir(sessionId).resolve("edits.ndjson"), sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    @Override
    public List<MergeSession.Edit> log(String sessionId) throws IOException {
        List<MergeSession.Edit> out = new ArrayList<>();
        for (String line : Files.readAllLines(dir(sessionId).resolve("edits.ndjson"))) {
            if (!line.isBlank()) out.add(MAPPER.readValue(line, MergeSession.Edit.class));
        }
        return out;
    }

    @Override
    public void publish(String sessionId, String graphJson) throws IOException {
        Files.writeString(dir(sessionId).resolve("graph.json"), graphJson);
    }
}
