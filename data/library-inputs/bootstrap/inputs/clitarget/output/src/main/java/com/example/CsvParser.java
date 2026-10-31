package com.example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CsvParser {

    public static final class Row {
        private final List<String> fields;
        private final int physicalLineStart;

        public Row(List<String> fields, int physicalLineStart) {
            this.fields = fields;
            this.physicalLineStart = physicalLineStart;
        }

        public List<String> fields() {
            return fields;
        }

        public int physicalLineStart() {
            return physicalLineStart;
        }
    }

    public static List<Row> parse(Path file) throws IOException {
        String content = Files.readString(file);
        if (content.isEmpty()) {
            return List.of();
        }
        List<Row> rows = new ArrayList<>();
        List<String> currentFields = new ArrayList<>();
        StringBuilder currentField = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldStarted = false;
        int physicalLine = 1;
        int rowStartLine = 1;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        currentField.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    currentField.append(c);
                    if (c == '\n') {
                        physicalLine++;
                    }
                }
            } else {
                if (c == '"') {
                    if (!fieldStarted) {
                        inQuotes = true;
                        fieldStarted = true;
                    } else {
                        currentField.append(c);
                    }
                } else if (c == ',') {
                    currentFields.add(currentField.toString());
                    currentField.setLength(0);
                    fieldStarted = false;
                } else if (c == '\n') {
                    currentFields.add(currentField.toString());
                    currentField.setLength(0);
                    fieldStarted = false;
                    rows.add(new Row(new ArrayList<>(currentFields), rowStartLine));
                    currentFields.clear();
                    physicalLine++;
                    rowStartLine = physicalLine;
                } else {
                    currentField.append(c);
                    fieldStarted = true;
                }
            }
        }

        if (fieldStarted || currentField.length() > 0 || !currentFields.isEmpty()) {
            currentFields.add(currentField.toString());
            rows.add(new Row(new ArrayList<>(currentFields), rowStartLine));
        }

        return rows;
    }
}