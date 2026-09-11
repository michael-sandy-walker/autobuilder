package com.example.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class FileReader {

    public static String readContent(Path path) throws IOException {
        return Files.readString(path);
    }

    public static List<String> readLines(Path path) throws IOException {
        return Files.readAllLines(path);
    }
}