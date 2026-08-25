package com.example.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            System.err.println("no such file: " + filePath);
            System.exit(1);
            return;
        }
        try {
            List<String> lines = Files.readAllLines(path);
            if (lines.isEmpty()) {
                System.exit(0);
                return;
            }
            int headerFields = lines.get(0).split(",", -1).length;
            boolean malformed = false;
            for (int i = 1; i < lines.size(); i++) {
                int found = lines.get(i).split(",", -1).length;
                if (found != headerFields) {
                    System.out.println("line " + (i + 1) + ": expected " + headerFields + " fields, found " + found);
                    malformed = true;
                }
            }
            System.exit(malformed ? 2 : 0);
        } catch (IOException e) {
            System.err.println("io error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void runCount(String filePath) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            System.err.println("no such file: " + filePath);
            System.exit(1);
            return;
        }
        try {
            List<String> lines = Files.readAllLines(path);
            int dataRows = Math.max(0, lines.size() - 1);
            System.out.println(dataRows);
            System.exit(0);
        } catch (IOException e) {
            System.err.println("io error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void runColumn(String filePath, String columnName) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            System.err.println("no such file: " + filePath);
            System.exit(1);
            return;
        }
        try {
            List<String> lines = Files.readAllLines(path);
            if (lines.isEmpty()) {
                System.err.println("no such column: " + columnName);
                System.exit(4);
                return;
            }
            String[] header = lines.get(0).split(",", -1);
            int colIndex = -1;
            for (int i = 0; i < header.length; i++) {
                if (header[i].equals(columnName)) {
                    colIndex = i;
                    break;
                }
            }
            if (colIndex == -1) {
                System.err.println("no such column: " + columnName);
                System.exit(4);
                return;
            }
            for (int i = 1; i < lines.size(); i++) {
                String[] fields = lines.get(i).split(",", -1);
                if (colIndex < fields.length) {
                    System.out.println(fields[colIndex]);
                }
            }
            System.exit(0);
        } catch (IOException e) {
            System.err.println("io error: " + e.getMessage());
            System.exit(1);
        }
    }
}