package com.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class Main {

    public static void main(String[] args) {
        System.exit(dispatch(args));
    }

    private static int dispatch(String[] args) {
        if (args.length == 0) {
            System.err.println("unknown command: ");
            return 3;
        }

        String command = args[0];
        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);

        switch (command) {
            case "check":
                if (rest.length != 1) {
                    return 5;
                }
                return handleCheck(rest[0]);
            case "count":
                if (rest.length != 1) {
                    return 5;
                }
                return handleCount(rest[0]);
            case "column":
                if (rest.length != 2) {
                    return 5;
                }
                return handleColumn(rest[0], rest[1]);
            default:
                System.err.println("unknown command: " + command);
                return 3;
        }
    }

    private static int handleCheck(String filePath) {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            System.err.println("no such file: " + filePath);
            return 1;
        }

        try {
            List<CsvParser.Row> rows = CsvParser.parse(path);
            if (rows.isEmpty()) {
                return 2;
            }

            int headerFieldCount = rows.get(0).fields().size();
            boolean malformed = false;

            for (int i = 1; i < rows.size(); i++) {
                CsvParser.Row row = rows.get(i);
                int found = row.fields().size();
                if (found != headerFieldCount) {
                    System.out.println("line " + row.physicalLineStart() + ": expected " + headerFieldCount + " fields, found " + found);
                    malformed = true;
                }
            }

            return malformed ? 2 : 0;
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }

    private static int handleCount(String filePath) {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            System.err.println("no such file: " + filePath);
            return 1;
        }

        try {
            List<CsvParser.Row> rows = CsvParser.parse(path);
            int count = Math.max(0, rows.size() - 1);
            System.out.println(count);
            return 0;
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }

    private static int handleColumn(String filePath, String columnName) {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            System.err.println("no such file: " + filePath);
            return 1;
        }

        try {
            List<CsvParser.Row> rows = CsvParser.parse(path);
            if (rows.isEmpty()) {
                System.err.println("no such column: " + columnName);
                return 4;
            }

            List<String> header = rows.get(0).fields();
            int colIndex = -1;
            for (int i = 0; i < header.size(); i++) {
                if (header.get(i).equals(columnName)) {
                    colIndex = i;
                    break;
                }
            }

            if (colIndex == -1) {
                System.err.println("no such column: " + columnName);
                return 4;
            }

            for (int i = 1; i < rows.size(); i++) {
                List<String> fields = rows.get(i).fields();
                if (colIndex < fields.size()) {
                    System.out.println(fields.get(colIndex));
                }
            }

            return 0;
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }
}