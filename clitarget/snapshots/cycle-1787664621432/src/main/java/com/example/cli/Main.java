package com.example.cli;

import com.example.cli.reader.CsvReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Main {

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("unknown command: ");
            System.exit(3);
        }

        String command = args[0];
        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);

        switch (command) {
            case "check" -> {
                if (rest.length != 1) {
                    System.err.println("wrong number of arguments");
                    System.exit(5);
                }
                runCheck(rest[0]);
            }
            case "count" -> {
                if (rest.length != 1) {
                    System.err.println("wrong number of arguments");
                    System.exit(5);
                }
                runCount(rest[0]);
            }
            case "column" -> {
                if (rest.length != 2) {
                    System.err.println("wrong number of arguments");
                    System.exit(5);
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
        }

        try {
            CsvReader reader = new CsvReader(path);
            List<String> header = reader.header();
            int expectedFields = header.size();
            List<List<String>> rows = reader.rows();

            boolean hasMalformed = false;
            for (int i = 0; i < rows.size(); i++) {
                List<String> row = rows.get(i);
                if (row.size() != expectedFields) {
                    System.out.println("line " + (i + 2) + ": expected " + expectedFields + " fields, found " + row.size());
                    hasMalformed = true;
                }
            }

            System.exit(hasMalformed ? 2 : 0);
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
        }

        try {
            CsvReader reader = new CsvReader(path);
            List<List<String>> rows = reader.rows();
            System.out.println(rows.size());
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
        }

        try {
            CsvReader reader = new CsvReader(path);
            List<String> header = reader.header();
            List<List<String>> rows = reader.rows();

            int colIndex = header.indexOf(columnName);
            if (colIndex == -1) {
                System.err.println("no such column: " + columnName);
                System.exit(4);
            }

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