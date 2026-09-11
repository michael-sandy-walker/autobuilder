package com.example.csv;

import java.util.ArrayList;
import java.util.List;

public class CsvParser {

    public static List<CsvRecord> parse(String content) {
        List<CsvRecord> records = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return records;
        }

        int i = 0;
        int n = content.length();
        int currentLine = 1;
        List<String> currentFields = new ArrayList<>();
        StringBuilder currentField = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldStarted = false;

        while (i < n) {
            char c = content.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && content.charAt(i + 1) == '"') {
                        currentField.append('"');
                        i += 2;
                    } else {
                        inQuotes = false;
                        i++;
                    }
                } else {
                    currentField.append(c);
                    if (c == '\n') {
                        currentLine++;
                    }
                    i++;
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                    fieldStarted = true;
                    i++;
                } else if (c == ',') {
                    currentFields.add(currentField.toString());
                    currentField.setLength(0);
                    fieldStarted = false;
                    i++;
                } else if (c == '\n') {
                    currentFields.add(currentField.toString());
                    currentField.setLength(0);
                    fieldStarted = false;
                    records.add(new CsvRecord(currentFields, currentLine));
                    currentFields = new ArrayList<>();
                    currentLine++;
                    i++;
                } else {
                    currentField.append(c);
                    fieldStarted = true;
                    i++;
                }
            }
        }

        if (fieldStarted || !currentField.isEmpty() || !currentFields.isEmpty()) {
            currentFields.add(currentField.toString());
            records.add(new CsvRecord(currentFields, currentLine));
        }

        return records;
    }
}