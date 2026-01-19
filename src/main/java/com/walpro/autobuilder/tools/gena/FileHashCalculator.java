package com.walpro.autobuilder.tools.gena;

import com.walpro.autobuilder.util.HashingUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class FileHashCalculator {

    public static String compute(File file) {
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            return HashingUtils.hashSha256(content);
        } catch (Exception e) {
            return "[Error: " + e.getMessage() + "]";
        }
    }
}
