package com.example.command;

import com.example.CsvParser;
import com.example.io.FileReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class CheckCommand {

    public int execute(String[] args) {
        if (args.length != 1) {
            System.err.println("wrong number of arguments");
            return 5;
        }

        Path path = Path.of(args[0]);
        if (!Files.exists(path)) {
            System.err.println("no such file: " + args[0]);
            return 1;
        }

        try {
            String content = FileReader.readContent(path);
            if (content.isEmpty()) {
                return 2;
            }

            List<String> logicalRows = splitLogicalRows(content);
            if (logicalRows.isEmpty()) {
                return 2;
            }

            List<String> headerFields = CsvParser.parseLine(logicalRows.get(0));
            int expectedFields = headerFields.size();
            boolean hasMalformed = false;

            for (int i = 1; i < logicalRows.size(); i++) {
                List<String> fields = CsvParser.parseLine(logicalRows.get(i));
                if (fields.size() != expectedFields) {
                    System.out.println("line " + (i + 1) + ": expected " + expectedFields + " fields, found " + fields.size());
                    hasMalformed = true;
                }
            }

            return hasMalformed ? 2 : 0;
        } catch (IOException e) {
            System.err.println("io error: " + e.getMessage());
            return 1;
        }
    }

    private List<String> splitLogicalRows(String content) {
        List<String> rows = new java.util.ArrayList<>();
        StringBuilder currentRow = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        currentRow.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    currentRow.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                    currentRow.append(c);
                } else if (c == '\n') {
                    rows.add(currentRow.toString());
                    currentRow.setLength(0);
                } else {
                    currentRow.append(c);
                }
            }
        }
        if (currentRow.length() > 0) {
            rows.add(currentRow.toString());
        }
        return rows;
    }
}