package com.example;

import java.util.ArrayList;
import java.util.List;

public final class CsvFieldParser {

    private CsvFieldParser() {
    }

    public static List<String> parseFields(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldStarted = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                    fieldStarted = true;
                } else if (c == ',') {
                    fields.add(current.toString());
                    current.setLength(0);
                    fieldStarted = false;
                } else {
                    current.append(c);
                    fieldStarted = true;
                }
            }
        }

        fields.add(current.toString());
        return fields;
    }
}