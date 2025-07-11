package com.walpro.autobuilder.parser;

import java.util.*;
import java.util.regex.*;

import lombok.extern.slf4j.Slf4j;

@Slf4j
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
            } else {
                log.debug("Skipped invalid filename: {}", filename);
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
            } else {
                log.debug("Skipped invalid filename for deletion: {}", path);
            }
        }

        return deletions;
    }

    private static boolean isValidFilename(String line) {
        return line.contains(".") && !line.contains(" ");
    }

    public static Optional<GoalStackDirectives> extractSubGoalStackDirectives(String reply) {
        GoalStackDirectives mods = new GoalStackDirectives();

        String[] lines = reply.split("\n");
        List<String> target = null;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#SubGoalUpdate")) {
                target = mods.update;
            } else if (line.startsWith("#SubGoalAppend")) {
                target = mods.append;
            } else if (line.startsWith("#SubGoalRemove")) {
                target = mods.remove;
            } else if (line.startsWith("- ") && target != null) {
                target.add(line.substring(2).trim());
            }
        }

        boolean hasChanges = !mods.update.isEmpty() || !mods.append.isEmpty() || !mods.remove.isEmpty();
        return hasChanges ? Optional.of(mods) : Optional.empty();
    }

    public static class GoalStackDirectives {
        public List<String> update = new ArrayList<>();
        public List<String> append = new ArrayList<>();
        public List<String> remove = new ArrayList<>();
    }

    public static Optional<List<String>> extractSubGoals(String reply) {
        if (reply == null || !reply.contains("#SubGoal")) return Optional.empty();

        List<String> result = new ArrayList<>();
        boolean inBlock = false;

        for (String line : reply.lines().toList()) {
            line = line.trim();
            if (line.equalsIgnoreCase("#SubGoal")) {
                inBlock = true;
                continue;
            }
            if (inBlock) {
                if (line.startsWith("#") && !line.startsWith("#SubGoal")) break; // end of block
                if (line.startsWith("- ")) {
                    result.add(line.substring(2).trim());
                }
            }
        }

        return result.isEmpty() ? Optional.empty() : Optional.of(result);
    }
}
