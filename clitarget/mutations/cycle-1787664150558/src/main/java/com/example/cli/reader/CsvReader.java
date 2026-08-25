package com.example.cli.reader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CsvReader {

    private final List<String> header;
    private final List<List<String>> rows;

    private CsvReader(List<String> header, List<List<String>> rows) {
        this.header = header;
        this.rows = rows;
    }

    public static CsvReader read(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path);
        if (lines.isEmpty()) {
            return new CsvReader(List.of(), List.of());
        }

        List<String> header = splitLine(lines.get(0));
        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            rows.add(splitLine(lines.get(i)));
        }

        return new CsvReader(header, rows);
    }

    public List<String> header() {
        return header;
    }

    public List<List<String>> rows() {
        return rows;
    }

    private static List<String> splitLine(String line) {
        if (line.isEmpty()) {
            return List.of();
        }
        String[] parts = line.split(",", -1);
        return List.of(parts);
    }
}