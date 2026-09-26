package com.walpro.autobuilder.analysis.diff.builder;

import com.walpro.autobuilder.analysis.diff.model.*;

import com.walpro.autobuilder.analysis.structure.model.ParsedElement;

import java.util.*;

public class StructuralDiffBuilder {

    public static StructuralDiffNode diff(ParsedElement oldElement, ParsedElement newElement) {
        // Case: added
        if (oldElement == null) {
            return new StructuralDiffNode(null, toDescriptor(newElement), buildChildren(null, newElement.children()));
        }

        // Case: deleted
        if (newElement == null) {
            return new StructuralDiffNode(toDescriptor(oldElement), null, buildChildren(oldElement.children(), null));
        }

        List<StructuralDiffNode> children = buildChildren(oldElement.children(), newElement.children());

        return new StructuralDiffNode(
                toDescriptor(oldElement),
                toDescriptor(newElement),
                children
        );
    }

    private static ElementDescriptor toDescriptor(ParsedElement element) {
        return new ElementDescriptor(
                element.id(),
                element.type(),
                element.content(),
                element.metadata()
        );
    }

    private static List<StructuralDiffNode> buildChildren(List<ParsedElement> oldChildren, List<ParsedElement> newChildren) {
        Map<String, ParsedElement> oldMap = toIdMap(oldChildren);
        Map<String, ParsedElement> newMap = toIdMap(newChildren);

        Set<String> allIds = new HashSet<>();
        allIds.addAll(oldMap.keySet());
        allIds.addAll(newMap.keySet());

        List<StructuralDiffNode> result = new ArrayList<>();
        for (String id : allIds) {
            ParsedElement oldChild = oldMap.get(id);
            ParsedElement newChild = newMap.get(id);
            result.add(diff(oldChild, newChild));
        }

        // Optional: sort children for deterministic output (e.g., by id)
        result.sort(Comparator.comparing(StructuralDiffNode::getId));

        return result;
    }

    private static Map<String, ParsedElement> toIdMap(List<ParsedElement> list) {
        if (list == null) return Map.of();
        Map<String, ParsedElement> map = new HashMap<>();
        for (ParsedElement e : list) {
            map.put(e.id(), e);
        }
        return map;
    }
}
