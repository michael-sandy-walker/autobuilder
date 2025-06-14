package com.walpro.autobuilder.diff;

public class DiffPromptFormatter {

    public static String formatForLLM(DiffReport report, String structureSummary) {
        StringBuilder sb = new StringBuilder("Recent project changes:\n\n");

        for (DiffEntry entry : report.entries()) {
            switch (entry.type()) {
                case MODIFIED -> {
                    sb.append("1. File modified: ").append(entry.path()).append("\n");
                    sb.append("--- BEFORE ---\n").append(entry.oldContent()).append("\n");
                    sb.append("--- AFTER ---\n").append(entry.newContent()).append("\n\n");
                }
                case ADDED -> {
                    sb.append("2. File added: ").append(entry.path()).append("\n");
                    sb.append("--- CONTENT ---\n").append(entry.newContent()).append("\n\n");
                }
                case DELETED -> {
                    sb.append("3. File deleted: ").append(entry.path()).append("\n\n");
                }
            }
        }

        sb.append("Project structure after changes:\n---\n")
          .append(structureSummary)
          .append("\n---\n\n")
          .append("What improvements or corrections should be made next?\n")
          .append("Respond only with code blocks in the form:\n")
          .append("```java [relative/path/to/File.java]\n<file contents>\n```");

        return sb.toString();
    }
}
