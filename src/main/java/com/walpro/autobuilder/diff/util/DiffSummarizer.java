package com.walpro.autobuilder.diff.util;

import com.walpro.autobuilder.diff.model.ElementDescriptor;
import com.walpro.autobuilder.diff.model.ElementType;
import com.walpro.autobuilder.diff.model.StructuralDiffNode;

import java.util.EnumMap;
import java.util.Map;

public class DiffSummarizer {

    public static String summarize(StructuralDiffNode root) {
        DiffStats stats = new DiffStats();
        collectStats(root, stats);
        return stats.formatSummary();
    }

    private static void collectStats(StructuralDiffNode node, DiffStats stats) {
        if (node.isAdded()) {
            stats.increment(node.getType(), ChangeKind.ADDED);
        } else if (node.isDeleted()) {
            stats.increment(node.getType(), ChangeKind.DELETED);
        } else if (node.isModified()) {
            stats.increment(node.getType(), ChangeKind.MODIFIED);
        }

        for (StructuralDiffNode child : node.children()) {
            collectStats(child, stats);
        }
    }

    private enum ChangeKind { ADDED, MODIFIED, DELETED }

    private static class DiffStats {
        private final Map<ChangeKind, EnumMap<ElementType, Integer>> counts = new EnumMap<>(ChangeKind.class);

        public DiffStats() {
            for (ChangeKind kind : ChangeKind.values()) {
                counts.put(kind, new EnumMap<>(ElementType.class));
            }
        }

        public void increment(ElementType type, ChangeKind kind) {
            counts.get(kind).merge(type, 1, Integer::sum);
        }

        public String formatSummary() {
            StringBuilder sb = new StringBuilder();
            for (ChangeKind kind : ChangeKind.values()) {
                EnumMap<ElementType, Integer> typeMap = counts.get(kind);
                if (!typeMap.isEmpty()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(kind.name().charAt(0)).append(kind.name().substring(1).toLowerCase()).append(": ");
                    sb.append(typeMap.entrySet().stream()
                            .map(e -> e.getValue() + " " + e.getKey().name().toLowerCase() + (e.getValue() > 1 ? "s" : ""))
                            .reduce((a, b) -> a + ", " + b).orElse(""));
                }
            }
            return sb.toString().isEmpty() ? "(no changes)" : sb.toString();
        }
    }
    
    public static String summarizePlainText(StructuralDiffNode root) {
        StringBuilder sb = new StringBuilder();
        summarizePlainTextRecursive(root, sb, 0);
        return sb.toString().isBlank() ? "(no changes)" : sb.toString();
    }

    private static void summarizePlainTextRecursive(StructuralDiffNode node, StringBuilder sb, int indent) {
        if (!node.isUnchanged()) {
            sb.append("  ".repeat(indent));
            sb.append("- ").append(node.getType());
            if (node.isAdded()) sb.append(" (added)");
            else if (node.isDeleted()) sb.append(" (deleted)");
            else if (node.isModified()) sb.append(" (modified)");
            sb.append(": ").append(node.getId()).append("\n");
        }

        for (StructuralDiffNode child : node.children()) {
            summarizePlainTextRecursive(child, sb, indent + 1);
        }
    }
    
    public static String summarizeForPrompt(StructuralDiffNode root) {
        StringBuilder sb = new StringBuilder();
        for (StructuralDiffNode child : root.children()) {
            summarizePromptRecursive(child, sb, 0);
        }
        return sb.toString().isBlank() ? "(no changes)" : sb.toString();
    }

    private static void summarizePromptRecursive(StructuralDiffNode node, StringBuilder sb, int indent) {
        if (node == null || node.isUnchanged()) return;

        String prefix = "  ".repeat(indent);
        String changeSymbol = node.isAdded() ? "➕" :
                              node.isDeleted() ? "➖" :
                              node.isModified() ? "✏️" : " ";

        String type = node.getType().name().toLowerCase();
        String label = extractDisplayId(node);

        sb.append(prefix)
          .append(changeSymbol)
          .append(" ").append(capitalize(type)).append(" `")
          .append(label).append("`\n");

        for (StructuralDiffNode child : node.children()) {
            summarizePromptRecursive(child, sb, indent + 1);
        }
    }

    private static String extractDisplayId(StructuralDiffNode node) {
        ElementDescriptor descriptor = node.newElement() != null
                ? node.newElement()
                : node.oldElement();

        if (descriptor == null) return "(unknown)";

        String id = descriptor.id();
        return (id == null || id.isBlank()) ? "(unknown)" : id;
    }

    private static String capitalize(String word) {
        return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }


}
