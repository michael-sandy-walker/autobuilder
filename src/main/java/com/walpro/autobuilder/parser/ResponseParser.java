package com.walpro.autobuilder.parser;

import java.util.*;
import java.util.regex.*;

public class ResponseParser {

	private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
		    "```(?:\\w+)?\\s*\\[(.+?)\\]\\s*\\n(.*?)\\n?```",
		    Pattern.DOTALL
		);

		private static final Pattern CODE_BLOCK_WITH_FILENAME_NEXT_LINE = Pattern.compile(
		    "```(?:\\w+)?\\s*\\n(.+?)\\n(.*?)\\n?```",
		    Pattern.DOTALL
		);

    private static final Pattern DELETE_PATTERN = Pattern.compile("#Delete\\s*\\[(.+?)\\]");

    public static Map<String, String> extractFiles(String input) {
        Map<String, String> files = new LinkedHashMap<>();
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(input);

        while (matcher.find()) {
            String filename = matcher.group(1).trim();
            String content = matcher.group(2).strip(); // Remove leading/trailing blank lines
            if (isValidFilename(filename)) {
                files.put(filename, content);
            }
        }

        matcher = CODE_BLOCK_WITH_FILENAME_NEXT_LINE.matcher(input);
        while (matcher.find()) {
            String filename = matcher.group(1).trim();
            String content = matcher.group(2).strip();

            // Only add if not already extracted
            if (!files.containsKey(filename) && isValidFilename(filename)) {
                files.put(filename, content);
            }
        }

        return files;
    }

    public static Set<String> extractDeletions(String input) {
        Set<String> deletions = new LinkedHashSet<>();
        Matcher matcher = DELETE_PATTERN.matcher(input);

        while (matcher.find()) {
            String path = matcher.group(1).trim();
            if (isValidFilename(path)) {
                deletions.add(path);
            }
        }

        return deletions;
    }

    private static boolean isValidFilename(String line) {
        return line.contains("/") && line.contains(".") && !line.contains(" ");
    }
}
