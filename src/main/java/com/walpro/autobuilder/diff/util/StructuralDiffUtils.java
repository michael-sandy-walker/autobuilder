package com.walpro.autobuilder.diff.util;

import com.walpro.autobuilder.diff.model.*;

import java.util.ArrayList;
import java.util.List;

public class StructuralDiffUtils {

    public static List<StructuralDiffNode> flatten(StructuralDiffNode root) {
        List<StructuralDiffNode> result = new ArrayList<>();
        flattenRecursive(root, result);
        return result;
    }

    private static void flattenRecursive(StructuralDiffNode node, List<StructuralDiffNode> result) {
        result.add(node);
        for (StructuralDiffNode child : node.children()) {
            flattenRecursive(child, result);
        }
    }

    public static List<StructuralDiffNode> filterByType(StructuralDiffNode root, ElementType type) {
        return flatten(root).stream()
                .filter(n -> n.getType() == type)
                .toList();
    }

    public static boolean hasChanges(StructuralDiffNode node) {
        return node.isAdded() || node.isDeleted() || node.isModified()
               || node.children().stream().anyMatch(StructuralDiffUtils::hasChanges);
    }
}
