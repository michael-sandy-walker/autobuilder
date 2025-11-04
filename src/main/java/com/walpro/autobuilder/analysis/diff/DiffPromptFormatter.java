package com.walpro.autobuilder.analysis.diff;

public class DiffPromptFormatter {

    public static String format(DiffReport report) {
        StringBuilder sb = new StringBuilder("Recent project changes:\n\n");
        if (report.entries() == null) {
        	sb.append("There are no current changes");
        }

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

        return sb.toString();
    }
}
