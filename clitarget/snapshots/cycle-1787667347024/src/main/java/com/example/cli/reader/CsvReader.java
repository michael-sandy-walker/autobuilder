package com.example.cli.reader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CsvReader {

    private final List<String> header;
    private final List<List<String>> rows;

    public CsvReader(Path path) throws CsvReaderException {
        try {
            List<String> lines = Files.readAllLines(path);
            if (lines.isEmpty()) {
                throw new CsvReaderException("empty file");
            }
            this.header = splitLine(lines.get(0));
            this.rows = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                this.rows.add(splitLine(lines.get(i)));
            }
        } catch (IOException e) {
            throw new CsvReaderException(e.getMessage(), e);
        }
    }

    public List<String> header() {
        return header;
    }

    public List<List<String>> rows() {
        return rows;
    }

    private static List<String> splitLine(String line) {
        String[] parts = line.split(",", -1);
        List<String> result = new ArrayList<>(parts.length);
        for (String p : parts) {
            result.add(p);
        }
        return result;
    }
}