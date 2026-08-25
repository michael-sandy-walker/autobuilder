package com.example.cli;

import com.example.cli.reader.CsvReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("usage: app.jar <command> [arguments]");
            System.exit(5);
            return;
        }

        String command = args[0];
        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);

        switch (command) {
            case "check" -> {
                if (rest.length != 1) {
                    System.err.println("check requires exactly 1 argument");
                    System.exit(5);
                    return;
                }
                runCheck(rest[0]);
            }
            case "count" -> {
                if (rest.length != 1) {
                    System.err.println("count requires exactly 1 argument");
                    System.exit(5);
                    return;
                }
                runCount(rest[0]);
            }
            case "column" -> {
                if (rest.length != 2) {
                    System.err.println("column requires exactly 2 arguments");
                    System.exit(5);
                    return;
                }
                runColumn(rest[0], rest[1]);
            }
            default -> {
                System.err.println("unknown command: " + command);
                System.exit(3);
            }
        }
    }

    private static void runCheck(String filePath) {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            System.err.println("no such file: " + filePath);
            System.exit(1);
            return;
        }

        try {
            CsvReader reader = new CsvReader(path);
            List<String> header = reader.header();
            List<List<String>> rows = reader.rows();
            int expected = header.size();
            boolean malformed = false;

            for (int i = 0; i < rows.size(); i++) {
                int found = rows.get(i).size();
                if (found != expected) {
                    System.out.println("line " + (i + 2) + ": expected " + expected + " fields, found " + found);
                    malformed = true;
                }
            }

            System.exit(malformed ? 2 : 0);
        } catch (IOException e) {
            System.err.println("error reading file: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void runCount(String filePath) {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            System.err.println("no such file: " + filePath);
            System.exit(1);
            return;
        }

        try {
            CsvReader reader = new CsvReader(path);
            System.out.println(reader.rows().size());
            System.exit(0);
        } catch (IOException e) {
            System.err.println("error reading file: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void runColumn(String filePath, String columnName) {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            System.err.println("no such file: " + filePath);
            System.exit(1);
            return;
        }

        try {
            CsvReader reader = new CsvReader(path);
            List<String> header = reader.header();
            int colIndex = header.indexOf(columnName);

            if (colIndex == -1) {
                System.err.println("no such column: " + columnName);
                System.exit(4);
                return;
            }

            List<List<String>> rows = reader.rows();
            for (List<String> row : rows) {
                if (colIndex < row.size()) {
                    System.out.println(row.get(colIndex));
                }
            }
            System.exit(0);
        } catch (IOException e) {
            System.err.println("error reading file: " + e.getMessage());
            System.exit(1);
        }
    }
}