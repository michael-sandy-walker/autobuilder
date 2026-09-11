package com.example.cli;

import com.example.csv.CsvParser;
import com.example.csv.CsvRecord;

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

        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            System.err.println("no such file: " + filePath);
            System.exit(1);
            return;
        }

        if (content.isEmpty()) {
            System.exit(2);
            return;
        }

        List<CsvRecord> records = CsvParser.parse(content);
        if (records.isEmpty()) {
            System.exit(2);
            return;
        }

        int headerFieldCount = records.get(0).fields().size();
        boolean malformed = false;
        for (int i = 1; i < records.size(); i++) {
            CsvRecord record = records.get(i);
            if (record.fields().size() != headerFieldCount) {
                System.out.println("line " + record.startLine() + ": expected " + headerFieldCount + " fields, found " + record.fields().size());
                malformed = true;
            }
        }
        System.exit(malformed ? 2 : 0);
    }

    private static void runCount(String filePath) {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            System.err.println("no such file: " + filePath);
            System.exit(1);
            return;
        }

        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            System.err.println("no such file: " + filePath);
            System.exit(1);
            return;
        }

        if (content.isEmpty()) {
            System.out.println("0");
            System.exit(0);
            return;
        }

        List<CsvRecord> records = CsvParser.parse(content);
        int dataRows = Math.max(0, records.size() - 1);
        System.out.println(dataRows);
        System.exit(0);
    }

    private static void runColumn(String filePath, String columnName) {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            System.err.println("no such file: " + filePath);
            System.exit(1);
            return;
        }

        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            System.err.println("no such file: " + filePath);
            System.exit(1);
            return;
        }

        if (content.isEmpty()) {
            System.err.println("no such column: " + columnName);
            System.exit(4);
            return;
        }

        List<CsvRecord> records = CsvParser.parse(content);
        if (records.isEmpty()) {
            System.err.println("no such column: " + columnName);
            System.exit(4);
            return;
        }

        List<String> header = records.get(0).fields();
        int colIndex = -1;
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i).equals(columnName)) {
                colIndex = i;
                break;
            }
        }

        if (colIndex == -1) {
            System.err.println("no such column: " + columnName);
            System.exit(4);
            return;
        }

        for (int i = 1; i < records.size(); i++) {
            List<String> fields = records.get(i).fields();
            if (colIndex < fields.size()) {
                System.out.println(fields.get(colIndex));
            }
        }
        System.exit(0);
    }
}