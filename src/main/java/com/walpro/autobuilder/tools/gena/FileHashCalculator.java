package com.walpro.autobuilder.tools.gena;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

public class FileHashCalculator {

    public static String compute(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "[Error: " + e.getMessage() + "]";
        }
    }
}
