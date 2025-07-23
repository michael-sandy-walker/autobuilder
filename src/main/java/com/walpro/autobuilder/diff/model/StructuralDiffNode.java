package com.walpro.autobuilder.diff.model;

import java.util.List;

public record StructuralDiffNode(
    ElementDescriptor oldElement,          // null if ADDED
    ElementDescriptor newElement,          // null if DELETED
    List<StructuralDiffNode> children      // recursively aligned subelements
) {

    public boolean isAdded() {
        return oldElement == null && newElement != null;
    }

    public boolean isDeleted() {
        return oldElement != null && newElement == null;
    }

    public boolean isModified() {
        return oldElement != null && newElement != null && !oldElement.content().equals(newElement.content());
    }

    public boolean isUnchanged() {
        return oldElement != null && newElement != null && oldElement.content().equals(newElement.content());
    }

    public ElementType getType() {
        if (newElement != null) return newElement.type();
        if (oldElement != null) return oldElement.type();
        return ElementType.UNKNOWN;
    }

    public String getId() {
        if (newElement != null) return newElement.id();
        if (oldElement != null) return oldElement.id();
        return "(unknown)";
    }
}
