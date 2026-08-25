package com.example.cli.reader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CsvReader {

    private final List<String> header;
    private final List<List<String>> rows;

    public CsvReader(Path path) throws CsvReaderException {
        List<String> lines;
        try {
            lines = Files.readAllLines(path);
        } catch (IOException e) {
            throw new CsvReaderException(e);
        }
        if (lines.isEmpty()) {
            this.header = List.of();
            this.rows = List.of();
            return;
        }
        this.header = splitLine(lines.get(0));
        List<List<String>> data = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            data.add(splitLine(lines.get(i)));
        }
        this.rows = data;
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