package com.example.cli;

import com.example.cli.reader.CsvReader;
import com.example.cli.reader.CsvReaderException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("unknown command: ");
            System.exit(3);
            return;
        }

        String command = args[0];
        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);

        switch (command) {
            case "check" -> {
                if (rest.length != 1) {
                    System.err.println("wrong number of arguments for check");
                    System.exit(5);
                    return;
                }
                runCheck(rest[0]);
            }
            case "count" -> {
                if (rest.length != 1) {
                    System.err.println("wrong number of arguments for count");
                    System.exit(5);
                    return;
                }
                runCount(rest[0]);
            }
            case "column" -> {
                if (rest.length != 2) {
                    System.err.println("wrong number of arguments for column");
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
            int headerCount = reader.header().size();
            boolean malformed = false;
            int lineNum = 1;
            for (List<String> row : reader.rows()) {
                lineNum++;
                if (row.size() != headerCount) {
                    System.out.println("line " + lineNum + ": expected " + headerCount + " fields, found " + row.size());
                    malformed = true;
                }
            }
            System.exit(malformed ? 2 : 0);
        } catch (CsvReaderException e) {
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
        } catch (CsvReaderException e) {
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
            for (List<String> row : reader.rows()) {
                if (colIndex < row.size()) {
                    System.out.println(row.get(colIndex));
                }
            }
            System.exit(0);
        } catch (CsvReaderException e) {
            System.err.println("error reading file: " + e.getMessage());
            System.exit(1);
        }
    }
}